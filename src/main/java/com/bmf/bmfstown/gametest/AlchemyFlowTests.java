package com.bmf.bmfstown.gametest;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.entity.AlchemistGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * 炼金行为端到端用例（2026-11-07 用户委托清单的"行为类"部分）：
 * 滞留全链、换单复用、多人抢台。
 *
 * <p>轮询统一用 {@code helper.succeedWhen}（每 tick 跑一次 criterion，
 * 抛异常=继续等，全过=测试成功；硬断言用 {@code helper.fail} 即刻判负）。
 * {@code startSequence().thenWaitUntil} 的异常会炸服务器，禁用。
 */
@GameTestHolder(BmfTown.MOD_ID)
public class AlchemyFlowTests {

    private static final net.minecraft.core.Holder<net.minecraft.world.item.alchemy.Potion> SWIFT =
            Potions.SWIFTNESS;

    /** 滞留全链：取料→疣→主料→火药→龙息→收瓶（玻璃瓶不残留）→4 瓶盖章入库。 */
    @GameTest(template = "flow11x4x11", timeoutTicks = 24000)
    public static void lingeringChainFullFlow(GameTestHelper helper) {
        var s = AlchemyScaffold.prepareTown(helper, new BlockPos(1, 0, 1), new BlockPos(9, 0, 9), 1);
        AlchemyScaffold.stockChest(s, helper, true, 1);
        AlchemyScaffold.spawnAlchemist(s, helper,
                "alch|minecraft:swiftness|lingering", new BlockPos(4, 1, 5));
        helper.succeedWhen(() -> {
            if (AlchemyScaffold.countStamped(s, helper, Items.LINGERING_POTION) < 4) {
                throw new net.minecraft.gametest.framework.GameTestAssertException("等待滞留成品 4 瓶");
            }
            var stand = s.stand(helper, s.standRels().get(0));
            for (int i = 0; i < 3; i++) {
                if (!stand.getItem(i).isEmpty()) {
                    helper.fail("瓶槽未清空: slot " + i + " = " + stand.getItem(i).getItem());
                }
            }
            if (!stand.getItem(3).isEmpty()) {
                helper.fail("料槽残留（龙息玻璃瓶未收走）: " + stand.getItem(3).getItem());
            }
        });
    }

    /** 换单立即生效 + 复用：延时链第二步完成后改喷溅——基础药**不重酿**，直接转火药。 */
    @GameTest(template = "splash11x4x11", timeoutTicks = 24000)
    public static void orderChangeReuseMidChain(GameTestHelper helper) {
        var s = AlchemyScaffold.prepareTown(helper, new BlockPos(1, 0, 1), new BlockPos(9, 0, 9), 1);
        AlchemyScaffold.stockChest(s, helper, false, 1); // 无龙息：断掉"重开滞留链"的退路
        var r = AlchemyScaffold.spawnAlchemist(s, helper,
                "alch|minecraft:swiftness|long", new BlockPos(4, 1, 5));
        helper.succeedWhen(() -> {
            // 成品优先：出了 4 瓶喷溅就直接终检（镇民入库后会立刻开下一轮，
            // 台子状态会往前走——先撞"等待基础药"分支就永远到不了成品检查）
            int splashDone = AlchemyScaffold.countStamped(s, helper, Items.SPLASH_POTION);
            if (splashDone >= 4) {
                if (AlchemyScaffold.countInChest(s, helper, Items.SUGAR) != 0) {
                    helper.fail("糖有剩余 = 链被重开（基础药未复用）");
                }
                if (AlchemyScaffold.countInChest(s, helper, Items.NETHER_WART) != 0) {
                    helper.fail("地狱疣有剩余");
                }
                if (AlchemyScaffold.countStamped(s, helper, Items.POTION) != 0) {
                    helper.fail("不应产出普通瓶成品");
                }
                return; // 全过 → succeed
            }
            var stand = s.stand(helper, s.standRels().get(0));
            if (stand == null || stand.getItem(0).isEmpty() || !stand.getItem(0).is(Items.POTION)) {
                throw new net.minecraft.gametest.framework.GameTestAssertException("等待基础药上瓶（stand=" + describeStand(stand) + "）");
            }
            var c = stand.getItem(0).getOrDefault(DataComponents.POTION_CONTENTS,
                    PotionContents.EMPTY);
            if (c.potion().orElse(null) != SWIFT) {
                throw new net.minecraft.gametest.framework.GameTestAssertException("等待基础药上瓶（stand=" + describeStand(stand) + "）");
            }
            if (!"alch|minecraft:swiftness|splash"
                    .equals(r.getJobState().orderItem())) {
                r.getJobState().setOrderItem("alch|minecraft:swiftness|splash");
            }
            throw new net.minecraft.gametest.framework.GameTestAssertException("换单完成，等待喷溅成品 4 瓶");
        });
    }

    /** 多人抢台：两镇民三台——应各占一台并行生产，互不抢锁。 */
    @GameTest(template = "multi13x4x11", timeoutTicks = 30000)
    public static void multiResidentClaimDifferentStands(GameTestHelper helper) {
        var s = AlchemyScaffold.prepareTown(helper, new BlockPos(1, 0, 1), new BlockPos(11, 0, 9), 3);
        AlchemyScaffold.stockChest(s, helper, true, 2);
        AlchemyScaffold.spawnAlchemist(s, helper,
                "alch|minecraft:swiftness|splash", new BlockPos(3, 1, 4));
        AlchemyScaffold.spawnAlchemist(s, helper,
                "alch|minecraft:swiftness|splash", new BlockPos(5, 1, 4));
        int[] maxClaims = {0};
        helper.succeedWhen(() -> {
            int c = 0;
            for (BlockPos p : s.standRels()) {
                if (AlchemistGoal.claimedResidentAt(helper.absolutePos(p)) != null) {
                    c++;
                }
            }
            if (c > maxClaims[0]) {
                maxClaims[0] = c;
            }
            if (AlchemyScaffold.countStamped(s, helper, Items.SPLASH_POTION) < 8) {
                throw new net.minecraft.gametest.framework.GameTestAssertException("等待 8 瓶（两镇民各 4）");
            }
            if (maxClaims[0] < 2) {
                helper.fail("从未同时占用 ≥2 台（抢台失效），最大同时占用=" + maxClaims[0]);
            }
        });
    }

    /** 水瓶直酿全链（决策 #36）：虚弱=水瓶+腐蛛眼（1.21 底药是水不是粗制）。
     *  箱里**刻意不给地狱疣**——验证步链跳过地狱疣步、水瓶直接上主料。 */
    @GameTest(template = "flow11x4x11", timeoutTicks = 24000)
    public static void weaknessWaterDirectFlow(GameTestHelper helper) {
        var s = AlchemyScaffold.prepareTown(helper, new BlockPos(1, 0, 1), new BlockPos(9, 0, 9), 1);
        var chest = s.chest(helper);
        int slot = 0;
        for (int i = 0; i < 3 && slot < chest.getContainerSize(); i++, slot++) {
            chest.setItem(slot, PotionContents.createItemStack(Items.POTION, Potions.WATER));
        }
        chest.setItem(slot++, new ItemStack(Items.FERMENTED_SPIDER_EYE));
        chest.setItem(slot, new ItemStack(Items.BLAZE_POWDER));
        chest.setChanged();
        AlchemyScaffold.spawnAlchemist(s, helper,
                "alch|minecraft:weakness|none", new BlockPos(4, 1, 5));
        helper.succeedWhen(() -> {
            if (AlchemyScaffold.countStamped(s, helper, Items.POTION,
                    net.minecraft.world.effect.MobEffects.WEAKNESS) < 4) {
                throw new net.minecraft.gametest.framework.GameTestAssertException(
                        "等待水瓶直酿 4 瓶盖章虚弱药水");
            }
        });
    }

    private static String describeStand(
            net.minecraft.world.level.block.entity.BrewingStandBlockEntity stand) {
        if (stand == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 5; i++) {
            ItemStack st = stand.getItem(i);
            sb.append(st.isEmpty() ? "-" : st.getItem().toString());
            if (i < 4) {
                sb.append(", ");
            }
        }
        return sb.append("]").toString();
    }
}
