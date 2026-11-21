package com.bmf.bmfstown.entity;

import com.bmf.bmfstown.BmfTown;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * 称号注册表（数据包化，与名字池/皮肤清单同哲学）：从
 * {@code data/<ns>/titles/*.json} 读称号定义（id→显示文本）。
 *
 * <h2>格式</h2>
 * <pre>{@code
 * {
 *   "titles": [
 *     { "id": "dragon_slayer", "text": "屠龙者" }
 *   ]
 * }
 * }</pre>
 *
 * <h2>与 {@link com.bmf.bmfstown.entity.domain.TitleState} 的分工</h2>
 * 镇民侧只存称号 id（TitleState，随实体存档）；显示文本查这张表。
 * <b>id 未登记时 {@link #textOf} 回落显示 id 本身</b>——称号可以先授予、
 * 文案后补（用户拍板：具体有哪些称号后定，数据结构先行）。
 *
 * <p>内容纪律：mod 内置 titles 目录暂空（无占位条目），正式称号清单由用户
 * 定稿后填 JSON，/reload 生效。
 */
@EventBusSubscriber(modid = BmfTown.MOD_ID)
public final class ResidentTitles extends SimpleJsonResourceReloadListener {

    private static final Gson GSON = new Gson();

    private static volatile Map<String, String> texts = Map.of();

    public ResidentTitles() {
        super(GSON, "titles");
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new ResidentTitles());
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files,
                         ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<String, String> loaded = new HashMap<>();
        int bad = 0;
        for (Map.Entry<ResourceLocation, JsonElement> file : files.entrySet()) {
            if (!(file.getValue() instanceof JsonObject root)
                    || !(root.get("titles") instanceof JsonArray arr)) {
                bad++;
                continue;
            }
            for (JsonElement el : arr) {
                if (!(el instanceof JsonObject entry)
                        || !(entry.get("id") instanceof JsonElement idEl)
                        || !(entry.get("text") instanceof JsonElement textEl)
                        || idEl.getAsString().isBlank()
                        || textEl.getAsString().isBlank()) {
                    bad++;
                    continue;
                }
                loaded.putIfAbsent(idEl.getAsString().trim(), textEl.getAsString().trim());
            }
        }
        texts = Map.copyOf(loaded);
        BmfTown.LOGGER.info("[BmfTown][称号] 称号注册表已加载：{} 条{}",
                texts.size(), bad > 0 ? "（跳过 " + bad + " 条坏条目）" : "");
    }

    /**
     * 称号显示文本：已登记返回登记文本；未登记回落 id 本身
     * （称号可先授予后补文案，绝不因缺文案而炸）。
     */
    public static String textOf(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        String text = texts.get(id);
        return text != null ? text : id;
    }

    /** 该 id 是否已登记（未登记=纯 id 显示，游戏不炸）。 */
    public static boolean isDefined(String id) {
        return id != null && texts.containsKey(id);
    }

    /** 登记的称号总数（诊断用）。 */
    public static int size() {
        return texts.size();
    }

    // ==========================================================
    //  测试专用（GameTest 注入假表；生产路径绝不调用）
    // ==========================================================

    /** GameTest 用：临时注入假表。 */
    public static void testOnlyLoad(Map<String, String> fake) {
        texts = Map.copyOf(fake);
    }
}
