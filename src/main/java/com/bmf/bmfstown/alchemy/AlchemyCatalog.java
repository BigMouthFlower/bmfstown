package com.bmf.bmfstown.alchemy;

import com.bmf.bmfstown.BmfTown;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 炼金订单目录（v0.12）：从原版 {@link PotionBrewing} **运行时反查**，零手写配方——
 * 与工人的 {@code WorkshopCrafts} 同哲学：原版表改了这里自动跟。
 *
 * <h2>反查方法</h2>
 * PotionBrewing 的混合表本身不可枚举，但判定/试配方法全公开
 * （{@code isIngredient / isBrewablePotion / hasMix / mix}）——
 * 目录生成 = 对每个可酿药水 P，扫全物品注册表找"粗制药水 + 它 → P"的主料；
 * 延时/强化变体用 红石/荧石 试配探测。组合规模小（药水约 40 × 物品一次过滤），
 * 结果缓存，数据包同步时失效重建。
 *
 * <h2>订单键</h2>
 * {@code alch|<potion注册名>|<none|long|strong>}，如
 * {@code alch|minecraft:swiftness|long}（延时的迅捷药水）。用 {@code |} 分隔
 * 是因为注册名本身含冒号。
 */
@EventBusSubscriber(modid = BmfTown.MOD_ID)
public final class AlchemyCatalog {

    /**
     * 一条等价酿造路径（决策 #37 柔性链）：中间底药 + 它的主料 + 最终主料。
     * 同一产物可有多条原版合法路径（伤害=剧毒/蜘蛛眼/腐蛛眼 与 治疗/闪烁西瓜/腐蛛眼），
     * NPC 重建步链时按"箱里有什么料"选路，玩家备哪样走哪样。
     */
    public record Chain(Holder<Potion> basePotion, Item item, Item ingredient) {
    }

    /**
     * 一条炼金配方：主料 → 基础药水（可选延时/强化变体）。
     *
     * <p>两步链（决策 #36/#37）：原版腐化系药水没有"粗制+主料"的单步配方
     * （伤害=剧毒/治疗+腐蛛眼、缓慢=迅捷/跳跃+腐蛛眼、隐形=夜视+腐蛛眼），
     * 目录收录**全部等价路径**（chains，按链料注册名排序保证确定性）——
     * NPC 酿造步链从中按库存现选一条插中间步（引擎同决策 #33 的秘传底药步）。
     * 单步配方（含全部秘传）chains 为空；秘传底药≠粗制（悬浮=缓降）时
     * chains 恰有一条。ingredient 为首选路径的最终主料（通常各路径相同）。
     */
    public record Recipe(Holder<Potion> base, Item ingredient,
                         @Nullable Holder<Potion> extended, @Nullable Holder<Potion> strong,
                         boolean hasSplash, boolean hasLingering,
                         List<Chain> chains,
                         @Nullable Holder<Potion> startPotion) {

        /** 按修饰组合取最终产物药水（"none|long|strong|splash|lingering|long+splash…"，组间自由组合）；档位缺失回落基础。 */
        public Holder<Potion> finalPotion(String modifier) {
            Holder<Potion> result = this.base;
            for (String part : modifier.split("\\+")) {
                switch (part) {
                    case "long" -> {
                        if (this.extended != null) {
                            result = this.extended;
                        }
                    }
                    case "strong" -> {
                        if (this.strong != null) {
                            result = this.strong;
                        }
                    }
                    default -> {
                    }
                }
            }
            return result;
        }
    }

    /** 缓存目录。 */
    private static volatile List<Recipe> cache;

    /** 数据包重载完成：目录作废重建。 */
    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        cache = null;
    }

    /** 反查目录（缓存；serverLevel 仅首次构建用）。 */
    public static List<Recipe> catalog(net.minecraft.world.level.Level level) {
        List<Recipe> cached = cache;
        if (cached != null) {
            return cached;
        }
        cache = build(level);
        return cache;
    }

    /** 按订单键解析（找不到/档位不存在 = null）。 */
    @Nullable
    public static Recipe resolve(net.minecraft.world.level.Level level, String orderKey) {
        if (!orderKey.startsWith("alch|")) {
            return null;
        }
        String[] parts = orderKey.split("\\|");
        if (parts.length != 3) {
            return null;
        }
        Holder<Potion> potion = BuiltInRegistries.POTION
                .getHolder(ResourceLocation.parse(parts[1]))
                .orElse(null);
        if (potion == null) {
            return null;
        }
        String mod = parts[2];
        for (Recipe recipe : catalog(level)) {
            if (recipe.base().value() != potion.value()) {
                continue;
            }
            // 变体可用性校验：档位不存在直接拒绝（绝不静默回落）
            boolean allAvailable = true;
            for (String part : mod.split("\\+")) {
                boolean ok = switch (part) {
                    case "long" -> recipe.extended() != null;
                    case "strong" -> recipe.strong() != null;
                    case "splash" -> recipe.hasSplash();
                    case "lingering" -> recipe.hasLingering();
                    default -> part.equals("none");
                };
                if (!ok) {
                    allAvailable = false;
                    break;
                }
            }
            // 订单键里存的是基础药水注册名（UI 侧 "alch|<基础>|<mod>"），
            // finalPotion(mod) 是最终变体（如 long_swiftness）——不能拿它和键比对，
            // 否则一切带延时/强化的订单都会被判"目录中无此键"（2026-11-06 修）
            if (allAvailable) {
                return recipe;
            }
        }
        return null;
    }

    // ==========================================================
    //  目录构建（全反查）
    // ==========================================================

    private static List<Recipe> build(net.minecraft.world.level.Level level) {
        PotionBrewing brewing = level.potionBrewing();
        ItemStack awkward = PotionContents.createItemStack(Items.POTION, Potions.AWKWARD);
        ItemStack redstone = new ItemStack(Items.REDSTONE);
        ItemStack glowstone = new ItemStack(Items.GLOWSTONE_DUST);
        List<Recipe> out = new ArrayList<>();
        for (Holder<Potion> potion : BuiltInRegistries.POTION.holders().toList()) {
            // 无效果底药不可下单（设计口径，总账对账测试同口径）：
            // 水瓶/平凡/稠厚/粗制——它们只是酿造中间态，且水瓶直酿反查会
            // 把"水+红石=平凡"这类配方也当产品收进来
            if (potion.value() == Potions.WATER.value()
                    || potion.value() == Potions.MUNDANE.value()
                    || potion.value() == Potions.THICK.value()
                    || potion.value() == Potions.AWKWARD.value()) {
                continue;
            }
            // 注意：不做 isBrewablePotion 预过滤——它会漏掉"没有下游配方"的终产物
            // （秘传药水全部是终产物，2026-11-07 GameTest 抓出）。收录与否由
            // 下面的主料反查决定。
            Item ingredient = null;
            Holder<Potion> startPotion = null;
            List<Chain> chains = List.of();
            // 秘传药水走注册表直查：hasMix 会被门禁 mixin 拦（未解锁=false），
            // 但目录=配方表本身，应与门禁解耦——权限在接单/开酿时结算，
            // 否则解锁秘传后目录缓存不会失效，选单里永远看不到新配方。
            var secretEntry = com.bmf.bmfstown.alchemy.ModPotions.entryOf(potion);
            if (secretEntry != null) {
                ingredient = secretEntry.catalyst();
                // 秘传底药≠粗制（悬浮=缓降）：目录直接带中间步（底药主料反查）
                if (secretEntry.base().value() != Potions.AWKWARD.value()) {
                    Item baseItem = findDirectIngredient(brewing, awkward, secretEntry.base());
                    if (baseItem != null) {
                        chains = List.of(new Chain(secretEntry.base(), baseItem, ingredient));
                    }
                }
            } else {
                ingredient = findDirectIngredient(brewing, awkward, potion);
                if (ingredient == null) {
                    // 两步链反查（决策 #36/#37）：原版腐化系（伤害/缓慢/隐形）没有
                    // "粗制+主料"单步配方——只有"粗制→中间药→腐蛛眼"两步，漏查
                    // = 选单里没有瞬间伤害（2026-11-07 用户实测抓出）。
                    // 收录全部等价路径（柔性链，决策 #37）：死认一条会让玩家备的
                    // 另一条合法料变成"莫名缺料"（备了蜘蛛眼、反查选中治疗路径）。
                    // 变体（long_/strong_ 前缀）绝不走两步链：它们由基础药的
                    // 红石/荧石变体位表达，反查会造出重复条目（选单双份）
                    ResourceLocation pk = BuiltInRegistries.POTION.getKey(potion.value());
                    boolean variant = pk.getPath().startsWith("long_")
                            || pk.getPath().startsWith("strong_");
                if (!variant) {
                    chains = findTwoStepChains(brewing, awkward, potion);
                    if (!chains.isEmpty()) {
                        ingredient = chains.get(0).ingredient();
                    } else {
                        // 水瓶直酿族（决策 #36）：1.21 起虚弱=水瓶+腐蛛眼（原版
                        // PotionBrewing L222），底药是水不是粗制——从粗制出发的
                        // 反查永远够不着。startPotion=WATER 让引擎跳过地狱疣步
                        ItemStack waterStack = PotionContents.createItemStack(
                                net.minecraft.world.item.Items.POTION, Potions.WATER);
                        Item waterDirect = findDirectIngredient(brewing, waterStack, potion);
                        if (waterDirect != null) {
                            ingredient = waterDirect;
                            startPotion = Potions.WATER;
                        }
                    }
                }
                }
            }
            if (ingredient == null) {
                continue; // 找不到主料的（水瓶/平凡/粗制/无原版配方如幸运等）跳过
            }
            // 延时/强化变体探测
            ItemStack baseStack = PotionContents.createItemStack(Items.POTION, potion);
            Holder<Potion> extended = probeVariant(brewing, baseStack, redstone);
            Holder<Potion> strong = probeVariant(brewing, baseStack, glowstone);
            // 容器变体探测（喷溅=火药；滞留=喷溅+龙息）——containerMix 对药水物品全适用
            boolean hasSplash = brewing.hasContainerMix(baseStack,
                    new ItemStack(net.minecraft.world.item.Items.GUNPOWDER));
            ItemStack splashStack = PotionContents.createItemStack(
                    net.minecraft.world.item.Items.SPLASH_POTION, potion);
            boolean hasLingering = brewing.hasContainerMix(splashStack,
                    new ItemStack(net.minecraft.world.item.Items.DRAGON_BREATH));
            out.add(new Recipe(potion, ingredient, extended, strong, hasSplash, hasLingering,
                    chains, startPotion));
        }
        return List.copyOf(out);
    }

    /**
     * 单步反查：是否存在物品 I 使 底药+I=目标药水。
     * 注意原版 mix(试剂, 药水)——与 hasMix(药水, 试剂) 参数序相反！
     * 之前按 hasMix 的顺序传参 → 反查永远失败 → 目录为空（弹层空白根因）。
     *
     * <p>必须排除容器类试剂（火药/龙息）：isIngredient 对它们也返回 true，
     * hasMix 走容器混合也返回 true，产物内容不变 → 粗制会拿火药当"主料"
     * 混进目录（2026-11-07 总账对账测试抓出）。
     */
    @Nullable
    private static Item findDirectIngredient(PotionBrewing brewing, ItemStack fromStack,
            Holder<Potion> target) {
        for (var entry : BuiltInRegistries.ITEM.entrySet()) {
            ItemStack ing = new ItemStack(entry.getValue());
            if (!brewing.isIngredient(ing) || brewing.isContainerIngredient(ing)
                    || !brewing.hasMix(fromStack, ing)) {
                continue;
            }
            ItemStack result = brewing.mix(ing, fromStack);
            Holder<Potion> resultPotion = result.getOrDefault(
                    net.minecraft.core.component.DataComponents.POTION_CONTENTS,
                    PotionContents.EMPTY).potion().orElse(null);
            if (resultPotion != null && resultPotion.value() == target.value()) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 两步链全路径反查（决策 #37 柔性链）：收集所有 粗制+A=B 且 B+I=目标 的等价路径。
     * 死认一条的教训（2026-11-07 用户实测）：反查按注册表迭代序恰好选中
     * 治疗/闪烁西瓜路径，玩家按直觉备了蜘蛛眼 → "莫名缺料"死等。
     * 同一中间药只记首条主料；按链料注册名排序保证跨会话确定性。
     */
    private static List<Chain> findTwoStepChains(PotionBrewing brewing, ItemStack awkward,
            Holder<Potion> target) {
        java.util.Map<Holder<Potion>, Chain> byMid = new java.util.LinkedHashMap<>();
        for (var be : BuiltInRegistries.ITEM.entrySet()) {
            ItemStack first = new ItemStack(be.getValue());
            if (!brewing.isIngredient(first) || brewing.isContainerIngredient(first)
                    || !brewing.hasMix(awkward, first)) {
                continue;
            }
            ItemStack mid = brewing.mix(first, awkward); // mix(试剂, 药水)
            Holder<Potion> midPotion = mid.getOrDefault(
                    net.minecraft.core.component.DataComponents.POTION_CONTENTS,
                    PotionContents.EMPTY).potion().orElse(null);
            if (midPotion == null || midPotion.value() == Potions.AWKWARD.value()
                    || byMid.containsKey(midPotion)) {
                continue;
            }
            for (var ie : BuiltInRegistries.ITEM.entrySet()) {
                ItemStack second = new ItemStack(ie.getValue());
                if (!brewing.isIngredient(second) || brewing.isContainerIngredient(second)
                        || !brewing.hasMix(mid, second)) {
                    continue;
                }
                ItemStack result = brewing.mix(second, mid);
                Holder<Potion> rp = result.getOrDefault(
                        net.minecraft.core.component.DataComponents.POTION_CONTENTS,
                        PotionContents.EMPTY).potion().orElse(null);
                if (rp != null && rp.value() == target.value()) {
                    byMid.put(midPotion, new Chain(midPotion, be.getValue(), ie.getValue()));
                    break;
                }
            }
        }
        return byMid.values().stream()
                .sorted(java.util.Comparator.comparing(c ->
                        BuiltInRegistries.ITEM.getKey(c.item()).getPath()))
                .toList();
    }

    /** 试配变体：mix 结果确实是一个"新药水"才认定变体存在。 */
    @Nullable
    private static Holder<Potion> probeVariant(PotionBrewing brewing,
            ItemStack baseStack, ItemStack modifier) {
        if (!brewing.hasMix(baseStack, modifier)) {
            return null;
        }
        ItemStack result = brewing.mix(modifier, baseStack); // mix(试剂, 药水)
        return result.getOrDefault(
                net.minecraft.core.component.DataComponents.POTION_CONTENTS,
                PotionContents.EMPTY).potion().orElse(null);
    }

    /** 组装订单键（UI 与调试命令共用）。 */
    public static String orderKey(Holder<Potion> finalPotion, String modifier) {
        ResourceLocation key = BuiltInRegistries.POTION.getKey(finalPotion.value());
        return "alch|" + key + "|" + modifier;
    }

    private AlchemyCatalog() {
    }
}
