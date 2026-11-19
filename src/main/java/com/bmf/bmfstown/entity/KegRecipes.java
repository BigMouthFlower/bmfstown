package com.bmf.bmfstown.entity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * BAC 发酵配方的<b>数据驱动</b>读取（决策 #65 起步，#85 二阶段扩展）——
 * 直接读数据包 JSON，零 BAC 类、零反射。
 *
 * <p>配方文件在 {@code data/brewinandchewin/recipe/fermenting/*.json}。经全量核对
 * （4.3.0，23 份，#85 全景扫描）：
 * <ul>
 *   <li>{@code base_fluid}：{@code ingredient.tag}（流体 tag，带 # 前缀）+ {@code amount}
 *       （millibuckets）。三种基液源：
 *       ① {@code #c:water}/{@code #c:milk}（整桶 1000mB，不可累加）；
 *       ② {@code #c:honey}（蜂蜜瓶 250mB/瓶，1000mB 配方 = 4 瓶累加）；
 *       ③ <b>酒倒酒</b>（#85 阶段一）：tag 是 BAC 酒名（如
 *       {@code #brewinandchewin:beer}）——流体与物品同名，基液源=窖里的瓶装酒
 *       （合成余料=酒杯），250mB/杯累加。强根艾尔链（啤酒→强根艾尔）的现实形态；</li>
 *   <li>{@code temperature}（#85 阶段二）：1~5 档（1 冷 / 2 较冷 / 3 常温 /
 *       4 较热 / 5 热，判定照抄 BAC {@code isValidTemp} 字节码）。酒桶 NBT
 *       直接存当前档位（{@code Temperature} 键，BAC 每 tick 按环境自刷新），
 *       酒保排产前读桶判档：不满足的配方不往桶里投料（投了也不开酿）；
 *       JSON 无该字段 = 无要求（不判档）；</li>
 *   <li>{@code result}：带 {@code amount} = 流体产物（酒，接酒时流体 id==物品 id，
 *       走接瓶入窖通道）；不带 = 物品产物（肉干/泡菜/熏鱼，BAC 完成时放
 *       <b>5 号出料槽</b>——字节码 {@code inventory.insertItem(5, result.copy())}，
 *       走收货入窖通道）；</li>
 *   <li><b>无基液配方</b>（#85 阶段二：jerky/kimchi/kippers 直酿；
 *       kombucha 无基液但流体产物）：配料直投，桶必须为空（BAC canFerment 口径）；</li>
 *   <li>{@code ingredients}：4 槽，空槽是 {@code []}；条目 {@code {item}} 或
 *       {@code {tag}}（tag 无 # 前缀，#88 起按 tag 全成员匹配——肉干配料
 *       {@code foods/jerky_meat}=腐肉+各种生肉，囤哪种都行），数量恒 1；
 *       配料含未安装 mod 的物品（如 kombucha 的 farmersrespite 茶叶）→
 *       该条解析失败整条跳过；</li>
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

    /**
     * 一层基液映射：配方 tag（去 #）→ (流体 id, 源物品, 每份源物的 droplets,
     * 可累加, 基液是酒)。可累加=蜂蜜/酒按 250mB/份凑足；不可累加=水/奶整桶。
     */
    private record BaseSrc(String fluidId, Item source, long perSourceDroplets,
            boolean accumulatable, boolean isDrink) {
    }

    private static final Map<String, BaseSrc> BASE_TAGS = Map.of(
            "c:water", new BaseSrc("minecraft:water", Items.WATER_BUCKET,
                    1000L * KegAccess.DROPLETS_PER_MB, false, false),
            "c:milk", new BaseSrc("minecraft:milk", Items.MILK_BUCKET,
                    1000L * KegAccess.DROPLETS_PER_MB, false, false),
            "c:honey", new BaseSrc("brewinandchewin:honey", Items.HONEY_BOTTLE,
                    KegAccess.TANKARD_DROPLETS, true, false));

    /** BAC 命名空间（酒倒酒的判定域）。 */
    private static final String BAC_NS = "brewinandchewin";

    /**
     * 一条配料（2026-11-18 #88：tag 感知）——item 条目直指具体物品；
     * tag 条目匹配<b>任意成员</b>（肉干的 {@code foods/jerky_meat} =
     * 腐肉 + 各种生肉，玩家囤哪种都该能酿），{@code rep} 只是展示/兜底
     * 代表物品（tag 首成员）。匹配口径 {@link #matches}。
     */
    public record KegIng(ItemStack rep, @Nullable TagKey<Item> tag) {

        /** 酒窖里的实物是否满足这条配料（tag 条目按 tag 成员判，不锁代表物品）。 */
        public boolean matches(ItemStack st) {
            if (st.isEmpty()) {
                return false;
            }
            return this.tag != null ? st.is(this.tag) : st.is(this.rep.getItem());
        }

        /** 展示/预留记账用的代表物品。 */
        public Item displayItem() {
            return this.rep.getItem();
        }
    }

    /**
     * 一条可自动化的发酵配方（#85 起覆盖全型：一层/二层酒、直酿、流体/物品产物）。
     *
     * @param resultFluidId 产物 id——流体产物时=流体 id（==同名物品，接瓶口径）；
     *                      物品产物时=物品 id
     * @param resultCount   产物件数（流体恒 1；物品产物 BAC 的 count）
     * @param resultIsFluid true=流体产物（接瓶入窖）；false=物品产物（5 号槽收货）
     * @param baseFluidId   基液流体 id；null=无基液（直酿）
     * @param baseSource    基液源物品（水桶/奶桶/蜂蜜瓶/BAC 酒瓶）；null=无基液
     * @param baseIsDrink   基液是酒（酒倒酒）——排产时对下层酒做原料预留（#85）
     * @param temperature   温度档 0~5（0=JSON 无该字段，不判档；1~5 照抄 BAC）
     */
    public record FermentRecipe(ResourceLocation id, String resultFluidId, int resultCount,
            boolean resultIsFluid, @Nullable String baseFluidId, int baseAmountMb,
            @Nullable Item baseSource, long basePerSourceDroplets, boolean baseIsDrink,
            int temperature, List<KegIng> ingredients) {
    }

    private static final Map<MinecraftServer, List<FermentRecipe>> CACHE = new WeakHashMap<>();

    /** 本服可自动化的全部配方（BAC 不在 = 空表）。 */
    public static List<FermentRecipe> all(ServerLevel level) {
        return CACHE.computeIfAbsent(level.getServer(), KegRecipes::parse);
    }

    private static List<FermentRecipe> parse(MinecraftServer server) {
        List<FermentRecipe> out = new ArrayList<>();
        Map<ResourceLocation, Resource> files;
        try {
            files = server.getResourceManager().listResources("recipe",
                    rl -> rl.getNamespace().equals(BAC_NS)
                            && rl.getPath().startsWith("recipe/fermenting/")
                            && rl.getPath().endsWith(".json"));
        } catch (Exception e) {
            return out;
        }
        for (Map.Entry<ResourceLocation, Resource> e : files.entrySet()) {
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
        // 温度档（#85 阶段二）：0=无要求（不判档），1~5=BAC 判档
        int temperature = root.has("temperature")
                ? root.get("temperature").getAsInt() : 0;

        // ---- 产物（流体 / 物品，二选一）----
        if (!root.has("result")) {
            return null;
        }
        JsonObject result = root.getAsJsonObject("result");
        if (!result.has("id")) {
            return null;
        }
        ResourceLocation resId = ResourceLocation.tryParse(result.get("id").getAsString());
        if (resId == null || !BuiltInRegistries.ITEM.containsKey(resId)) {
            return null; // 产物物品不存在（流体产物也要求同名物品——接瓶口径）
        }
        boolean fluidResult = result.has("amount");
        int resultCount = fluidResult ? 1
                : (result.has("count") ? Math.max(1, result.get("count").getAsInt()) : 1);

        // ---- 基液（可无——直酿配方）----
        String baseFluidId = null;
        int baseAmountMb = 0;
        Item baseSource = null;
        long basePerSource = 0;
        boolean baseIsDrink = false;
        if (root.has("base_fluid")) {
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
                // 酒倒酒（#85 阶段一）：tag=BAC 酒名，流体与物品同名——
                // 验证两者都在（BAC 不在/改版 → 整条跳过，降级安全）
                ResourceLocation drink = ResourceLocation.tryParse(tag);
                if (drink == null || !BAC_NS.equals(drink.getNamespace())) {
                    return null;
                }
                boolean fluidExists = BuiltInRegistries.FLUID.keySet().stream()
                        .anyMatch(k -> k.equals(drink));
                if (!fluidExists || !BuiltInRegistries.ITEM.containsKey(drink)) {
                    return null;
                }
                src = new BaseSrc(tag, BuiltInRegistries.ITEM.get(drink),
                        KegAccess.TANKARD_DROPLETS, true, true);
            }
            int amountMb = bf.get("amount").getAsInt();
            // 水/奶必须整桶一次倒足；蜂蜜/酒按杯累加（任意配方量都可凑）
            if (!src.accumulatable() && amountMb != 1000) {
                return null;
            }
            baseFluidId = src.fluidId();
            baseAmountMb = amountMb;
            baseSource = src.source();
            basePerSource = src.perSourceDroplets();
            baseIsDrink = src.isDrink();
        }

        // ---- 配料（直酿与倒酒配方共用；#88 tag 感知）----
        List<KegIng> ings = new ArrayList<>();
        if (root.has("ingredients")) {
            for (JsonElement el : root.getAsJsonArray("ingredients")) {
                if (!el.isJsonObject()) {
                    continue; // [] 空槽
                }
                JsonObject io = el.getAsJsonObject();
                KegIng ing = representative(io);
                if (ing == null || ing.rep().isEmpty()) {
                    return null; // 配料解析不出（mod 不在/tag 空等）：整条跳过
                }
                ings.add(ing);
            }
        }
        if (ings.isEmpty()) {
            return null;
        }
        return new FermentRecipe(id, resId.toString(), resultCount, fluidResult,
                baseFluidId, baseAmountMb, baseSource, basePerSource, baseIsDrink,
                temperature, List.copyOf(ings));
    }

    /** 配料条目 → KegIng（item 直取；tag 取首个成员当代表 + 记 TagKey 全成员匹配）。 */
    private static KegIng representative(JsonObject io) {
        if (io.has("item")) {
            ResourceLocation rl = ResourceLocation.tryParse(io.get("item").getAsString());
            if (rl == null) {
                return null;
            }
            return BuiltInRegistries.ITEM.getOptional(rl)
                    .map(st -> new KegIng(new ItemStack(st), null)).orElse(null);
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
            TagKey<Item> key = TagKey.create(Registries.ITEM, rl);
            return BuiltInRegistries.ITEM.getTag(key)
                    .flatMap(named -> named.stream().findFirst())
                    .map(h -> new KegIng(new ItemStack(h.value()), key)).orElse(null);
        }
        return null;
    }
}
