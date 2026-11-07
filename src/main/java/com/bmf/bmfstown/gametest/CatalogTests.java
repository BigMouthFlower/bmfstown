package com.bmf.bmfstown.gametest;

import com.bmf.bmfstown.BmfTown;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * 目录解析全键测试：炼金目录的每一个"药水 × 修饰"组合都必须能 resolve。
 * 覆盖 2026-11-06 的 bug（resolve 拿最终变体和基础药水比对，加红石订单被拒）——
 * 防复发断言。
 */
@GameTestHolder(BmfTown.MOD_ID)
public class CatalogTests {

    /** 常用修饰组合（药效档 × 容器档的全部合法搭配）。 */
    private static final String[] MODS = {
            "none", "long", "strong",
            "splash", "lingering",
            "long+splash", "long+lingering", "strong+splash", "strong+lingering"
    };

    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void catalogResolveVanillaSwiftness(GameTestHelper helper) {
        var level = helper.getLevel();
        int ok = 0;
        var fails = new StringBuilder();
        for (String mod : MODS) {
            String key = "alch|minecraft:swiftness|" + mod;
            var recipe = com.bmf.bmfstown.alchemy.AlchemyCatalog.resolve(level, key);
            if (recipe != null) {
                ok++;
            } else {
                fails.append(key).append("; ");
            }
        }
        if (fails.isEmpty()) {
            helper.succeed();
        } else {
            helper.fail("resolve 失败的键: " + fails);
        }
    }

    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void catalogRejectsWildKeys(GameTestHelper helper) {
        var level = helper.getLevel();
        // 野键/畸形键必须解析失败（服务端拒单的依据）
        String[] wild = {"", "alch|", "alch|minecraft:swiftness",
                "alch|minecraft:not_a_potion|none",
                "alch|minecraft:swiftness|bogus",
                "alch|minecraft:swiftness|none|extra"};
        for (String key : wild) {
            if (com.bmf.bmfstown.alchemy.AlchemyCatalog.resolve(level, key) != null) {
                helper.fail("野键不应解析成功: " + key);
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void catalogSecretPotionsResolve(GameTestHelper helper) {
        var level = helper.getLevel();
        // 秘传药水（mix 恒注册）也必须在目录里——普通档
        for (String id : new String[]{"bmfstown_haste", "bmfstown_blindness", "bmfstown_luck",
                "bmfstown_levitation"}) {
            var recipe = com.bmf.bmfstown.alchemy.AlchemyCatalog.resolve(level, "alch|minecraft:" + id + "|none");
            // 注册名带 bmfstown 命名空间
            recipe = recipe == null
                    ? com.bmf.bmfstown.alchemy.AlchemyCatalog.resolve(level, "alch|bmfstown:" + id + "|none")
                    : recipe;
            if (recipe == null) {
                helper.fail("秘传药水不在目录: " + id);
                return;
            }
        }
        helper.succeed();
    }

    /**
     * 两步腐化链必须收录（决策 #36，2026-11-07 用户抓出瞬间伤害缺失）：
     * 伤害/缓慢/隐形没有"粗制+主料"单步配方，目录要带中间底药链收录，
     * 且链字段齐全（chainBase=中间药、chainItem=中间药主料、ingredient=腐蛛眼）。
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void catalogTwoStepCorruptionChains(GameTestHelper helper) {
        var level = helper.getLevel();
        for (String id : new String[]{"harming", "slowness", "invisibility"}) {
            var r = com.bmf.bmfstown.alchemy.AlchemyCatalog.resolve(
                    level, "alch|minecraft:" + id + "|none");
            if (r == null) {
                helper.fail("两步链药水不在目录: " + id);
                return;
            }
            if (r.chains().isEmpty()) {
                helper.fail("两步链药水缺中间步字段: " + id);
                return;
            }
            if (!r.ingredient().toString().contains("fermented_spider_eye")) {
                helper.fail("两步链主料应为腐蛛眼: " + id + " 实际 " + r.ingredient());
                return;
            }
        }
        helper.succeed();
    }

    /** 秘传底药链不回退（#33 悬浮=缓降+腐蛛眼）：链字段必须是缓降+幻翼膜。 */
    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void catalogLevitationChainIntact(GameTestHelper helper) {
        var level = helper.getLevel();
        var r = com.bmf.bmfstown.alchemy.AlchemyCatalog.resolve(
                level, "alch|bmfstown:bmfstown_levitation|none");
        r = r == null ? com.bmf.bmfstown.alchemy.AlchemyCatalog.resolve(
                level, "alch|minecraft:bmfstown_levitation|none") : r;
        if (r == null) {
            helper.fail("悬浮秘传不在目录");
            return;
        }
        if (r.chains().size() != 1
                || r.chains().get(0).basePotion().value() != net.minecraft.world.item.alchemy.Potions.SLOW_FALLING.value()
                || r.chains().get(0).item() != net.minecraft.world.item.Items.PHANTOM_MEMBRANE) {
            helper.fail("悬浮底药链回退：chains=" + r.chains());
            return;
        }
        helper.succeed();
    }

    /**
     * 目录完备性总账（2026-11-07 用户要求）：独立 BFS 遍历原版酿造图——
     * 从水瓶出发沿一切"内容 mix"走全图——得到理论可酿药水全集，与目录双向对账。
     * 漏收录（如两步腐化系当初漏伤害）与多余收录（如变体被当基础药）都算失败。
     *
     * <p>刻意排除：水瓶/平凡/稠厚/粗制——无效果底药，设计口径不可下单。
     * 秘传与门禁解耦，直接并入期望集（BFS 看不到被锁的秘传 mix）。
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 400)
    public static void catalogCoversAllBrewablePotions(GameTestHelper helper) {
        var level = helper.getLevel();
        var brewing = level.potionBrewing();
        // 1) BFS：水瓶出发，一切药水内容 mix 可达的药水
        java.util.Set<net.minecraft.world.item.alchemy.Potion> reachable = new java.util.HashSet<>();
        java.util.Deque<net.minecraft.core.Holder<net.minecraft.world.item.alchemy.Potion>> queue =
                new java.util.ArrayDeque<>();
        reachable.add(net.minecraft.world.item.alchemy.Potions.WATER.value());
        queue.add(net.minecraft.world.item.alchemy.Potions.WATER);
        while (!queue.isEmpty()) {
            var cur = queue.poll();
            ItemStack curStack = PotionContents.createItemStack(Items.POTION, cur);
            for (var e : net.minecraft.core.registries.BuiltInRegistries.ITEM.entrySet()) {
                ItemStack ing = new ItemStack(e.getValue());
                if (!brewing.isIngredient(ing) || !brewing.hasMix(curStack, ing)) {
                    continue;
                }
                ItemStack result = brewing.mix(ing, curStack); // mix(试剂, 药水)，参数序别反
                var rp = result.getOrDefault(DataComponents.POTION_CONTENTS,
                        PotionContents.EMPTY).potion().orElse(null);
                if (rp != null && reachable.add(rp.value())) {
                    queue.add(rp);
                }
            }
        }
        // 2) 期望集 = 可达 − 刻意排除 ∪ 秘传
        for (var skip : new net.minecraft.core.Holder[]{
                net.minecraft.world.item.alchemy.Potions.WATER,
                net.minecraft.world.item.alchemy.Potions.MUNDANE,
                net.minecraft.world.item.alchemy.Potions.THICK,
                net.minecraft.world.item.alchemy.Potions.AWKWARD}) {
            reachable.remove(skip.value());
        }
        for (var e : com.bmf.bmfstown.alchemy.ModPotions.SECRETS.entrySet()) {
            reachable.add(e.getValue().potion().value());
        }
        // 3) 双向对账
        var recipes = com.bmf.bmfstown.alchemy.AlchemyCatalog.catalog(level);
        java.util.Set<net.minecraft.world.item.alchemy.Potion> bases = new java.util.HashSet<>();
        for (var r : recipes) {
            bases.add(r.base().value());
        }
        var missing = new StringBuilder();
        for (var p : reachable) {
            if (bases.contains(p)) {
                continue;
            }
            // 变体（long_/strong_）合法形态=挂在基础药的 extended/strong 位
            boolean asVariant = recipes.stream().anyMatch(r ->
                    (r.extended() != null && r.extended().value() == p)
                            || (r.strong() != null && r.strong().value() == p));
            if (!asVariant) {
                missing.append(net.minecraft.core.registries.BuiltInRegistries.POTION
                        .getKey(p)).append("; ");
            }
        }
        var extra = new StringBuilder();
        for (var r : recipes) {
            if (!reachable.contains(r.base().value())) {
                extra.append(net.minecraft.core.registries.BuiltInRegistries.POTION
                        .getKey(r.base().value())).append("; ");
            }
        }
        if (missing.length() > 0 || extra.length() > 0) {
            helper.fail("目录对账失败——漏收录: " + missing + "多余: " + extra);
        } else {
            helper.succeed();
        }
    }

    /**
     * 伤害两步链**全路径**锁定（决策 #37 柔性链）：剧毒/蜘蛛眼 与 治疗/闪烁西瓜
     * 两条原版合法路径都必须收录——柔性选链的前提是路径齐全。少一条 = 玩家备
     * 那条料会变"莫名缺料"。主料各路径同为腐蛛眼。
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void catalogHarmingChainPathLocked(GameTestHelper helper) {
        var level = helper.getLevel();
        var r = com.bmf.bmfstown.alchemy.AlchemyCatalog.resolve(
                level, "alch|minecraft:harming|none");
        if (r == null) {
            helper.fail("瞬间伤害不在目录");
            return;
        }
        boolean hasPoison = r.chains().stream().anyMatch(c ->
                c.basePotion().value() == net.minecraft.world.item.alchemy.Potions.POISON.value()
                        && c.item() == net.minecraft.world.item.Items.SPIDER_EYE
                        && c.ingredient() == net.minecraft.world.item.Items.FERMENTED_SPIDER_EYE);
        boolean hasHealing = r.chains().stream().anyMatch(c ->
                c.basePotion().value() == net.minecraft.world.item.alchemy.Potions.HEALING.value()
                        && c.item() == net.minecraft.world.item.Items.GLISTERING_MELON_SLICE
                        && c.ingredient() == net.minecraft.world.item.Items.FERMENTED_SPIDER_EYE);
        if (!hasPoison || !hasHealing) {
            helper.fail("伤害链路径不全：chains=" + r.chains());
            return;
        }
        helper.succeed();
    }
}
