package com.bmf.bmfstown.entity;

import com.bmf.bmfstown.BmfTown;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ServerLevelAccessor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 镇民名字池（v0.15 数据包化，.ai/designs/name_system.md）：从数据包
 * {@code data/<ns>/names/*.json} 读名字池，生成「姓 + 名」组合全名。
 *
 * <h2>格式（登记制，与皮肤清单同哲学）</h2>
 * <pre>{@code
 * {
 *   "surnames": ["陈", "周"],      // 姓池（可与多个文件合并）
 *   "given":    ["满仓", "铁栓"],  // 名池
 *   "names":    ["阿竹", "老陈"]   // 可选：整名直用（不用组合时填这个）
 * }
 * }</pre>
 * 取名规则：姓池与名池都非空 → 随机组合（大池天然低撞名）；否则回落到
 * {@code names} 整名池；两者都空 → 返回 null（镇民无名，不炸）。
 *
 * <h2>镇内去重（尽力而为）</h2>
 * {@link #random(RandomSource, ServerLevelAccessor)} 会扫一遍当前世界的镇民
 * 排除已用名，试抓若干次仍全占用则接受重复（用户拍板：允许重复，不造编号兜底名）。
 *
 * <h2>内容纪律</h2>
 * 名单是用户内容：mod 内置的 core.json 只是让系统能玩的<b>初稿占位</b>，
 * 正式名单由用户直接改 JSON（/reload 生效），机制对数量无假设。
 *
 * <p>赋名调用点只有 Resident.finalizeSpawn 与 readAdditionalSaveData 兜底两处，
 * 依旧走原版 CustomName——命名牌/重命名面板/头顶名牌全兼容，玩家改过不覆盖。
 */
@EventBusSubscriber(modid = BmfTown.MOD_ID)
public final class ResidentNames extends SimpleJsonResourceReloadListener {

    private static final Gson GSON = new Gson();

    /** 去重重试次数：试这么多次仍全占用就接受重复（用户拍板）。 */
    private static final int DEDUP_ATTEMPTS = 10;

    private static volatile List<String> surnames = List.of();
    private static volatile List<String> given = List.of();
    private static volatile List<String> wholeNames = List.of();

    public ResidentNames() {
        super(GSON, "names");
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new ResidentNames());
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files,
                         ResourceManager resourceManager, ProfilerFiller profiler) {
        List<String> sur = new ArrayList<>();
        List<String> giv = new ArrayList<>();
        List<String> whole = new ArrayList<>();
        int bad = 0;
        for (Map.Entry<ResourceLocation, JsonElement> file : files.entrySet()) {
            if (!(file.getValue() instanceof JsonObject root)) {
                bad++;
                continue;
            }
            bad += readStrings(root, "surnames", sur);
            bad += readStrings(root, "given", giv);
            bad += readStrings(root, "names", whole);
        }
        surnames = List.copyOf(sur);
        given = List.copyOf(giv);
        wholeNames = List.copyOf(whole);
        BmfTown.LOGGER.info("[BmfTown][名字] 名字池已加载：姓 {} × 名 {}（组合 {} 种）+ 整名 {} 个{}",
                surnames.size(), given.size(), (long) surnames.size() * given.size(),
                wholeNames.size(), bad > 0 ? "（跳过 " + bad + " 条坏条目）" : "");
    }

    /** 读一个字符串数组字段（去重合并进 out），返回坏条目数。 */
    private static int readStrings(JsonObject root, String field, List<String> out) {
        if (!(root.get(field) instanceof JsonArray arr)) {
            return 0; // 字段可选
        }
        int bad = 0;
        for (JsonElement el : arr) {
            if (el.isJsonPrimitive() && !el.getAsString().isBlank()) {
                String v = el.getAsString().trim();
                if (!out.contains(v)) {
                    out.add(v);
                }
            } else {
                bad++;
            }
        }
        return bad;
    }

    // ==========================================================
    //  取名
    // ==========================================================

    /**
     * 随机取名（带镇内去重，尽力而为）。
     *
     * @return 名字；池子全空返回 null（调用方不设名）
     */
    public static String random(RandomSource random, ServerLevelAccessor level) {
        Set<String> used = collectUsedNames(level);
        for (int i = 0; i < DEDUP_ATTEMPTS; i++) {
            String candidate = rawRandom(random);
            if (candidate == null) {
                return null;
            }
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
        // 池子枯竭（重试全撞）：接受重复（用户拍板 #48）
        return rawRandom(random);
    }

    /** 无去重随机取名；池子全空返回 null。 */
    public static String rawRandom(RandomSource random) {
        List<String> sur = surnames;
        List<String> giv = given;
        if (!sur.isEmpty() && !giv.isEmpty()) {
            return sur.get(random.nextInt(sur.size())) + giv.get(random.nextInt(giv.size()));
        }
        List<String> whole = wholeNames;
        if (!whole.isEmpty()) {
            return whole.get(random.nextInt(whole.size()));
        }
        return null;
    }

    /** 收集当前世界镇民已占用的名字（去重用；非服务端/world 空则返回空集）。 */
    private static Set<String> collectUsedNames(ServerLevelAccessor level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return Set.of();
        }
        Set<String> used = new HashSet<>();
        for (Entity e : serverLevel.getEntities().getAll()) {
            if (e instanceof Resident r && r.hasCustomName()) {
                used.add(r.getName().getString());
            }
        }
        return used;
    }

    // ==========================================================
    //  测试专用（GameTest 注入假池；生产路径绝不调用）
    // ==========================================================

    /** GameTest 用：临时注入假池，测组合/回落/空态逻辑。 */
    public static void testOnlyLoad(List<String> surnames, List<String> given, List<String> wholeNames) {
        ResidentNames.surnames = List.copyOf(surnames);
        ResidentNames.given = List.copyOf(given);
        ResidentNames.wholeNames = List.copyOf(wholeNames);
    }
}
