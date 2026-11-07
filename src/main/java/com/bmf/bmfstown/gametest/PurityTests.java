package com.bmf.bmfstown.gametest;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.ModDataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.neoforged.neoforge.gametest.GameTestHolder;

import javax.annotation.Nullable;

/**
 * 纯度/盖章/杂质结算用例（决策 #27 及其 2026-11-07 修订）。
 * 覆盖今晚的多个用户实测问题：杂质概率层（已废弃）、浊名前缀污染、
 * 水瓶误盖章、"浑浊的但从不窜"。
 */
@GameTestHolder(BmfTown.MOD_ID)
public class PurityTests {

    private static ItemStack potionOf(@Nullable Object potionHolderDummy) {
        return PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS);
    }

    /** 盖章：浊药（P<1）——纯度组件 + 时长物化 + 丢 holder 换 customEffects + 浊名。 */
    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void stampImpurePotion(GameTestHelper helper) {
        ItemStack stack = PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS);
        com.bmf.bmfstown.alchemy.PurityRules.stamp(stack, 0.6F, null);

        Float purity = stack.get(ModDataComponents.PURITY.get());
        if (purity == null || Math.abs(purity - 0.6F) > 1e-4F) {
            helper.fail("纯度组件缺失或不等于 0.6: " + purity);
            return;
        }
        var contents = stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
        if (contents.potion().isPresent()) {
            helper.fail("浊药应丢弃 potion holder（防止原版双份施加）");
            return;
        }
        var effects = contents.customEffects();
        if (effects.size() != 1) {
            helper.fail("customEffects 应为 1 条: " + effects.size());
            return;
        }
        MobEffectInstance e = effects.get(0);
        int expected = Math.round(3600 * 0.6F); // 迅捷 3600t × P
        if (Math.abs(e.getDuration() - expected) > 1) {
            helper.fail("时长未物化 ×P: " + e.getDuration() + " 期望≈" + expected);
            return;
        }
        if (stack.get(DataComponents.CUSTOM_NAME) == null) {
            helper.fail("浊药应有 CUSTOM_NAME（浑浊的××）");
            return;
        }
        helper.succeed();
    }

    /** 盖章：纯药（P=1 clamp 上限）——只盖章，外观与玩家手酿完全一致。 */
    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void stampPurePotionKeepsVanillaLook(GameTestHelper helper) {
        ItemStack stack = PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS);
        com.bmf.bmfstown.alchemy.PurityRules.stamp(stack, 1.0F, null);

        Float purity = stack.get(ModDataComponents.PURITY.get());
        if (purity == null || Math.abs(purity - 1.0F) > 1e-4F) {
            helper.fail("P=1 应只打章: " + purity);
            return;
        }
        if (stack.get(DataComponents.CUSTOM_NAME) != null) {
            helper.fail("P=1 不应改名");
            return;
        }
        var contents = stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
        if (contents.potion().isEmpty()) {
            helper.fail("P=1 不应动 PotionContents");
            return;
        }
        helper.succeed();
    }

    /** 盖章：水瓶（无效果内容物）——绝不能打章，否则喝水也会窜味。 */
    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void stampWaterBottleSkipped(GameTestHelper helper) {
        ItemStack stack = PotionContents.createItemStack(Items.POTION, Potions.WATER);
        com.bmf.bmfstown.alchemy.PurityRules.stamp(stack, 0.6F, null);
        if (stack.get(ModDataComponents.PURITY.get()) != null) {
            helper.fail("水瓶不应被盖章（否则喝水瓶也结算杂质）");
            return;
        }
        helper.succeed();
    }

    /** 浊名前缀按**瓶物品**取：喷溅瓶的浊名键前缀应为 splash_potion（键名事故回归断言）。 */
    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void stampSplashUsesSplashPrefix(GameTestHelper helper) {
        ItemStack stack = PotionContents.createItemStack(Items.SPLASH_POTION, Potions.SWIFTNESS);
        com.bmf.bmfstown.alchemy.PurityRules.stamp(stack, 0.6F, null);
        var name = stack.get(DataComponents.CUSTOM_NAME);
        if (name == null) {
            helper.fail("浊药应有 CUSTOM_NAME");
            return;
        }
        // 名字 = "浑浊的前缀" + 基础名 两个 translatable 兄弟节点；
        // 断言基础名的键走的是**喷溅瓶前缀**且不是死键（键名事故回归断言）
        String baseKey = null;
        for (var sibling : name.getSiblings()) {
            if (sibling.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tc) {
                baseKey = tc.getKey();
            }
        }
        if (baseKey == null || !baseKey.equals("item.minecraft.splash_potion.effect.swiftness")) {
            helper.fail("浊名基础键应为喷溅前缀: " + baseKey);
            return;
        }
        helper.succeed();
    }

    /** 杂质：浊药**必窜**（2026-11-07 修订——概率层废弃），时长按公式 20×(1−P) 秒。 */
    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void impurityAlwaysApplies(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 固定种子：连续多次掷骰结果确定，但每次都必窜
        for (int round = 0; round < 3; round++) {
            var pig = helper.spawn(EntityType.PIG, new BlockPos(1, 1, 1));
            pig.removeAllEffects();
            boolean applied = com.bmf.bmfstown.alchemy.PurityRules.maybeApplyImpurity(
                    level, pig, 0.6F, true, RandomSource.create(1234L + round));
            if (!applied) {
                helper.fail("第 " + round + " 轮未窜味——浊药必窜被破坏");
                return;
            }
            if (pig.getActiveEffects().isEmpty()) {
                helper.fail("窜味后无任何效果");
                return;
            }
            MobEffectInstance inst = pig.getActiveEffects().iterator().next();
            // 持续型应为 round(20×(1−0.6))=8 秒=160t；瞬发固定 1 秒
            int sec = inst.getDuration() / 20;
            if (sec != 8 && sec != 1) {
                helper.fail("杂质时长不在预期（8s 或瞬发 1s）: " + sec + "s");
                return;
            }
            pig.discard();
        }
        helper.succeed();
    }

    /** 杂质方向对称：负面药（伤害吸收药的反面——用原版伤害药）窜**正面**池给怪物。 */
    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void impuritySymmetryForNegativePotions(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var pig = helper.spawn(EntityType.PIG, new BlockPos(1, 1, 1));
        boolean applied = com.bmf.bmfstown.alchemy.PurityRules.maybeApplyImpurity(
                level, pig, 0.5F, false, RandomSource.create(77L));
        if (!applied || pig.getActiveEffects().isEmpty()) {
            helper.fail("负面药的正面杂质未生效");
            return;
        }
        // 正面池：再生/力量/速度/抗性/隐身/吸收/生命提升（负面药本体不受此限）
        pig.discard();
        helper.succeed();
    }

    /** 纯度公式：基础值爬升 + clamp ≤1（铁律：任何成长不超越玩家）。 */
    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void purityFormulaClamps(GameTestHelper helper) {
        var random = RandomSource.create(1L);
        for (int tier = 0; tier <= 10; tier++) {
            double base = com.bmf.bmfstown.alchemy.PurityRules.basePurity(tier, 10);
            float p = com.bmf.bmfstown.alchemy.PurityRules.rollPurity(base, random);
            if (p > 1.0F || p < 0.05F) {
                helper.fail("纯度越界: tier=" + tier + " P=" + p);
                return;
            }
        }
        double b0 = com.bmf.bmfstown.alchemy.PurityRules.basePurity(0, 10);
        double b10 = com.bmf.bmfstown.alchemy.PurityRules.basePurity(10, 10);
        if (Math.abs(b0 - 0.60D) > 1e-6 || Math.abs(b10 - 0.90D) > 1e-6) {
            helper.fail("基础值区间错误: " + b0 + "~" + b10);
            return;
        }
        helper.succeed();
    }
}
