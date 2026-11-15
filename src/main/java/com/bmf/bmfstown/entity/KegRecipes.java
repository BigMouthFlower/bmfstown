package com.bmf.bmfstown.entity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * BAC 发酵配方的<b>数据驱动</b>读取（决策 #65）——直接读数据包 JSON，零 BAC 类、零反射。
 *
 * <p>配方文件在 {@code data/brewinandchewin/recipe/fermenting/*.json}。经全量核对
 * （4.3.0，21 份）：
 * <ul>
 *   <li>{@code base_fluid}：{@code ingredient.tag}（流体 tag，带 # 前缀）+ {@code amount}
 *       （millibuckets）。一层酒基液：{@code #c:water}/{@code #c:milk}（整桶 1000mB）、
 *       {@code #c:honey}（蜂蜜瓶 250mB/瓶，1000mB 配方 = 4 瓶累加）；二层酒基液
 *       是 {@code #brewinandchewin:vodka} 之类的<b>酒倒酒</b>——v1 不自动化，跳过；</li>
 *   <li>{@code temperature}：有的配方要求加热桶/冰盒温度档（1~4）——v1 不自动化，
 *       只做常温（无该字段的）配方；</li>
 *   <li>{@code result}：带 {@code amount} = 流体产物（酒，接酒时流体 id==物品 id）；
 *       不带 = 物品产物（奶酪轮/腌菜干）——酒保只管酒，跳过；</li>
 *   <li>{@code ingredients}：4 槽，空槽是 {@code []}；条目 {@code {item}} 或
 *       {@code {tag}}（tag 无 # 前缀），数量恒 1；</li>
 *   <li>无基液的（jerky/kimchi/kippers，发酵食品）自动被 base_fluid 过滤掉。</li>
 * </ul>
 *
 * <h2>降级安全</h2>
 * BAC 不在 → 命名空间下无资源 → 返回空表 → 酒保酿造循环整体静默停用；
 * 版本更新改了字段 → 解析异常逐文件吞掉 → 只损失该配方。缓存按 server 记
 * （WeakHashMap），{@code /reload} 不刷新（重启才重读——数据包运行中变动极罕见）。
 */
public final class KegRecipes {

    private KegRecipes() {
    }

    /** 一层基液映射：配方 tag（去 #）→ (流体 id, 源物品, 每份源物的 droplets)。 */
    private record BaseSrc(String fluidId, Item source, long perSourceDroplets) {
    }

    private static final Map<String, BaseSrc> BASE_TAGS = Map.of(
            "c:water", new BaseSrc("minecraft:water", Items.WATER_BUCKET,
                    1000L * KegAccess.DROPLETS_PER_MB),
            "c:milk", new BaseSrc("minecraft:milk", Items.MILK_BUCKET,
                    1000L * KegAccess.DROPLETS_PER_MB),
            "c:honey", new BaseSrc("brewinandchewin:honey", Items.HONEY_BOTTLE,
                    KegAccess.TANKARD_DROPLETS));

    /**
     * 一条可自动化的发酵配方（已过滤：常温、一层基液、流体产物且可接瓶）。
     *
     * @param ingredients 投配料清单（每样 1 个，tag 配方取 tag 首个成员为代表物品）
     */
    public record FermentRecipe(ResourceLocation id, String resultFluidId,
            String baseFluidId, int baseAmountMb,
            Item baseSource, long basePerSourceDroplets,
            List<ItemStack> ingredients) {
    }

    private static final Map<MinecraftServer, List<FermentRecipe>> CACHE = new WeakHashMap<>();

    /** 本服可自动化的全部配方（BAC 不在 = 空表）。 */
    public static List<FermentRecipe> all(ServerLevel level) {
        return CACHE.computeIfAbsent(level.getServer(), KegRecipes::parse);
    }

    private static List<FermentRecipe> parse(MinecraftServer server) {
        List<FermentRecipe> out = new ArrayList<>();
        Map<ResourceLocation, net.minecraft.server.packs.resources.Resource> files;
        try {
            files = server.getResourceManager().listResources("recipe",
                    rl -> rl.getNamespace().equals("brewinandchewin")
                            && rl.getPath().startsWith("recipe/fermenting/")
                            && rl.getPath().endsWith(".json"));
        } catch (Exception e) {
            return out;
        }
        for (Map.Entry<ResourceLocation, net.minecraft.server.packs.resources.Resource> e
                : files.entrySet()) {
            try (InputStream in = e.getValue().open()) {
                JsonObject root = JsonParser.parseReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                FermentRecipe r = parseOne(e.getKey(), root);
                if (r != null) {
                    out.add(r);
                }
            } catch (Exception ignored) {
                // 单个配方字段变了：只损失这一条，不炸
            }
        }
        return List.copyOf(out);
    }

    private static FermentRecipe parseOne(ResourceLocation id, JsonObject root) {
        if (!root.has("base_fluid") || root.has("temperature")) {
            return null; // 无基液（发酵食品）或温度档配方：v1 不自动化
        }
        JsonObject bf = root.getAsJsonObject("base_fluid");
        if (!bf.has("ingredient") || !bf.has("amount") || !bf.has("unit")) {
            return null;
        }
        JsonObject ing = bf.getAsJsonObject("ingredient");
        String tag = ing.has("tag") ? ing.get("tag").getAsString() : null;
        if (tag == null) {
            return null;
        }
        if (tag.startsWith("#")) {
            tag = tag.substring(1);
        }
        BaseSrc src = BASE_TAGS.get(tag);
        if (src == null) {
            return null; // 二层酒基液（酒倒酒）：v1 不自动化
        }
        int amountMb = bf.get("amount").getAsInt();
        // 水/奶必须整桶一次倒足；蜂蜜按瓶累加
        if (src.source() != Items.HONEY_BOTTLE && amountMb != 1000) {
            return null;
        }
        if (!root.has("result")) {
            return null;
        }
        JsonObject result = root.getAsJsonObject("result");
        if (!result.has("id") || !result.has("amount")) {
            return null; // 物品产物（奶酪轮/腌菜）：酒保只管酒
        }
        ResourceLocation resId = ResourceLocation.tryParse(result.get("id").getAsString());
        if (resId == null || !BuiltInRegistries.ITEM.containsKey(resId)) {
            return null; // 流体没有同名物品 → 接不出瓶，跳过
        }
        List<ItemStack> ings = new ArrayList<>();
        if (root.has("ingredients")) {
            for (JsonElement el : root.getAsJsonArray("ingredients")) {
                if (!el.isJsonObject()) {
                    continue; // [] 空槽
                }
                JsonObject io = el.getAsJsonObject();
                ItemStack st = representative(io);
                if (st == null || st.isEmpty()) {
                    return null; // 配料解析不出（tag 空等）：整条跳过
                }
                ings.add(st);
            }
        }
        if (ings.isEmpty()) {
            return null;
        }
        return new FermentRecipe(id, resId.toString(),
                src.fluidId(), amountMb, src.source(), src.perSourceDroplets(),
                List.copyOf(ings));
    }

    /** 配料条目 → 代表物品（item 直取；tag 取首个成员）。 */
    private static ItemStack representative(JsonObject io) {
        if (io.has("item")) {
            ResourceLocation rl = ResourceLocation.tryParse(io.get("item").getAsString());
            if (rl == null) {
                return null;
            }
            return BuiltInRegistries.ITEM.getOptional(rl)
                    .map(ItemStack::new).orElse(null);
        }
        if (io.has("tag")) {
            String t = io.get("tag").getAsString();
            if (t.startsWith("#")) {
                t = t.substring(1);
            }
            ResourceLocation rl = ResourceLocation.tryParse(t);
            if (rl == null) {
                return null;
            }
            return BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, rl))
                    .flatMap(named -> named.stream().findFirst())
                    .map(h -> new ItemStack(h.value())).orElse(null);
        }
        return null;
    }
}
