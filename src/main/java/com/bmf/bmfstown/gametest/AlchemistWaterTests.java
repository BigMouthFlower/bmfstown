package com.bmf.bmfstown.gametest;

import com.bmf.bmfstown.BmfTown;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * 水源站补水资源行为用例（2026-11-07 用户委托，决策 #34 验收）：
 * 箱里**只给空瓶不给水瓶**，考 NPC 的取水能力——
 * 取空瓶 → 寻路到登记的水源站 → 模拟装水（材料账做实）→ 回台酿造 → 入库。
 *
 * <p>四种形态各一测：水源方块（围栏防流溢）/ 橡树叶含水 / 红树叶含水 /
 * 含水炼药锅（3 格水恰好被 3 瓶取尽 → 断言空锅，账实相符的铁证）。
 * 订单用最便宜的迅捷药水（地狱疣+糖两步），成功口径 = 4 瓶盖章成品
 * （一套料 3 瓶酿 + 1 瓶手艺补，决策 #27）。
 *
 * <p>轮询统一 {@code helper.succeedWhen}（铁律：只抛 GameTestAssertException；
 * 硬断言用 helper.fail）。
 */
@GameTestHolder(BmfTown.MOD_ID)
public class AlchemistWaterTests {

    /** 水源站统一摆位：与酿造台（8,1,9）/箱子（2,1,9）对角，强迫 NPC 真寻路过去。 */
    private static final BlockPos STATION_REL = new BlockPos(2, 1, 2);

    /** 水源方块的防流溢围栏（水源会横向流动；底=地面、顶=空气，围住四面即可）。 */
    private static final BlockPos[] CURB = {
            new BlockPos(1, 1, 2), new BlockPos(3, 1, 2),
            new BlockPos(2, 1, 1), new BlockPos(2, 1, 3)
    };

    /** 水源方块：登记 → 取空瓶 → 装水 → 酿造 4 瓶；站本体（水源）不消耗。 */
    @GameTest(template = "water11x4x11", timeoutTicks = 24000)
    public static void refillFromWaterSourceBlock(GameTestHelper helper) {
        var s = baseScene(helper);
        for (BlockPos p : CURB) {
            helper.setBlock(p, Blocks.STONE_BRICKS);
        }
        helper.setBlock(STATION_REL, Blocks.WATER);
        registerStation(s, helper);
        AlchemyScaffold.spawnAlchemist(s, helper,
                "alch|minecraft:swiftness|none", new BlockPos(4, 1, 5));
        helper.succeedWhen(() -> {
            BlockState st = helper.getBlockState(STATION_REL);
            if (!(st.is(Blocks.WATER) && st.getFluidState().isSource())) {
                helper.fail("水源方块不应被消耗: " + st.getBlock());
            }
            assertFourStamped(s, helper);
        });
    }

    /** 橡树叶含水方块：同链路；树叶含水状态保持（无限水源，不消耗）。 */
    @GameTest(template = "water11x4x11", timeoutTicks = 24000)
    public static void refillFromWaterloggedOakLeaves(GameTestHelper helper) {
        var s = baseScene(helper);
        helper.setBlock(STATION_REL, Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(LeavesBlock.WATERLOGGED, true)
                .setValue(LeavesBlock.PERSISTENT, true)); // PERSISTENT 防衰减干扰
        registerStation(s, helper);
        AlchemyScaffold.spawnAlchemist(s, helper,
                "alch|minecraft:swiftness|none", new BlockPos(4, 1, 5));
        helper.succeedWhen(() -> {
            BlockState st = helper.getBlockState(STATION_REL);
            if (!st.is(Blocks.OAK_LEAVES)
                    || !st.getValue(LeavesBlock.WATERLOGGED)) {
                helper.fail("含水橡树叶不应被消耗: " + st.getBlock());
            }
            assertFourStamped(s, helper);
        });
    }

    /** 红树叶含水方块（红树林口径，1.21 唯一天然生成含水树叶的树种）：同链路。 */
    @GameTest(template = "water11x4x11", timeoutTicks = 24000)
    public static void refillFromWaterloggedMangroveLeaves(GameTestHelper helper) {
        var s = baseScene(helper);
        helper.setBlock(STATION_REL, Blocks.MANGROVE_LEAVES.defaultBlockState()
                .setValue(LeavesBlock.WATERLOGGED, true)
                .setValue(LeavesBlock.PERSISTENT, true));
        registerStation(s, helper);
        AlchemyScaffold.spawnAlchemist(s, helper,
                "alch|minecraft:swiftness|none", new BlockPos(4, 1, 5));
        helper.succeedWhen(() -> {
            BlockState st = helper.getBlockState(STATION_REL);
            if (!st.is(Blocks.MANGROVE_LEAVES)
                    || !st.getValue(LeavesBlock.WATERLOGGED)) {
                helper.fail("含水红树叶不应被消耗: " + st.getBlock());
            }
            assertFourStamped(s, helper);
        });
    }

    /**
     * 含水炼药锅（3 格水）：账实相符铁证——3 瓶缺口恰好取尽 3 格水，
     * 断言成品 4 瓶 **且** 锅变成空锅（Blocks.CAULDRON）。
     */
    @GameTest(template = "water11x4x11", timeoutTicks = 24000)
    public static void refillDrainsWaterCauldron(GameTestHelper helper) {
        var s = baseScene(helper);
        helper.setBlock(STATION_REL, Blocks.WATER_CAULDRON.defaultBlockState()
                .setValue(LayeredCauldronBlock.LEVEL, 3));
        registerStation(s, helper);
        AlchemyScaffold.spawnAlchemist(s, helper,
                "alch|minecraft:swiftness|none", new BlockPos(4, 1, 5));
        helper.succeedWhen(() -> {
            // 先等成品（批量完成前锅可能还没被动过）
            if (AlchemyScaffold.countStamped(s, helper, Items.POTION) < 4) {
                throw new GameTestAssertException("等待空瓶补水链产出 4 瓶盖章迅捷药水");
            }
            BlockState st = helper.getBlockState(STATION_REL);
            if (!st.is(Blocks.CAULDRON)) {
                helper.fail("3 格水应被 3 瓶取尽变空锅，实际: " + st.getBlock());
            }
        });
    }

    /**
     * 两步腐化链订单（瞬间伤害）+ 只给空瓶（2026-11-07 用户实测场景）：
     * 伤害=两步链（地狱疣→中间药→腐蛛眼），比普通药水多要链料——
     * 验证 #34 补水与 #35 目录泛化**叠加**端到端：取空瓶→装水→三步酿造→4 瓶入库。
     * 箱里把两步链两种可能路径的料都备齐（蜘蛛眼/腐蛛眼/闪烁西瓜）。
     */
    @GameTest(template = "water11x4x11", timeoutTicks = 24000)
    public static void refillFeedsTwoStepHarming(GameTestHelper helper) {
        var s = baseScene(helper);
        var chest = s.chest(helper);
        chest.setItem(4, new ItemStack(Items.SPIDER_EYE));
        chest.setItem(5, new ItemStack(Items.FERMENTED_SPIDER_EYE));
        chest.setItem(6, new ItemStack(Items.GLISTERING_MELON_SLICE));
        chest.setChanged();
        for (BlockPos p : CURB) {
            helper.setBlock(p, Blocks.STONE_BRICKS);
        }
        helper.setBlock(STATION_REL, Blocks.WATER);
        registerStation(s, helper);
        AlchemyScaffold.spawnAlchemist(s, helper,
                "alch|minecraft:harming|none", new BlockPos(4, 1, 5));
        helper.succeedWhen(() -> {
            if (AlchemyScaffold.countStamped(s, helper, Items.POTION,
                    net.minecraft.world.effect.MobEffects.HARM) < 4) {
                throw new GameTestAssertException(
                        "等待空瓶补水链产出 4 瓶盖章瞬间伤害（两步腐化链 + 补水叠加）");
            }
        });
    }

    /**
     * 用户实测对照（2026-11-07 决策 #37）：箱里"有蜘蛛眼、**没腐蛛眼**"——
     * 瞬间伤害=两步链（地狱疣→中间药→腐蛛眼），链料缺一样 NPC 就该停等；
     * 水瓶已先装好在背包是正常中间态（先取水再取料的既定次序）。
     * 柔性选链（#37）下箱里有蜘蛛眼 → 走剧毒路径，仍因缺腐蛛眼停等；
     * 断言 600t 内不得产出瞬间伤害（缺腐蛛眼能酿出=有 bug）。
     * 正路径（料全齐）用例见 {@link #refillFeedsTwoStepHarming}。
     */
    @GameTest(template = "water11x4x11", timeoutTicks = 700)
    public static void harmingStallsWithoutFSE(GameTestHelper helper) {
        var s = baseScene(helper);
        var chest = s.chest(helper);
        chest.setItem(4, new ItemStack(Items.SPIDER_EYE)); // 蜘蛛眼有
        // 刻意不给腐蛛眼（FERMENTED_SPIDER_EYE）
        for (BlockPos p : CURB) {
            helper.setBlock(p, Blocks.STONE_BRICKS);
        }
        helper.setBlock(STATION_REL, Blocks.WATER);
        registerStation(s, helper);
        AlchemyScaffold.spawnAlchemist(s, helper,
                "alch|minecraft:harming|none", new BlockPos(4, 1, 5));
        helper.startSequence().thenExecuteAfter(600, () -> {
            if (AlchemyScaffold.countStamped(s, helper, Items.POTION,
                    net.minecraft.world.effect.MobEffects.HARM) >= 1) {
                helper.fail("缺腐蛛眼不应产出瞬间伤害（NPC 应停等，点名播报腐蛛眼）");
            }
        }).thenSucceed();
    }

    // ==========================================================
    //  场景脚手架（本地件：水箱不带水瓶，只给空瓶）
    // ==========================================================

    /** 地面+箱子+1 酿造台+镇（复用 AlchemyScaffold），登记表先不挂水源站。 */
    private static AlchemyScaffold.Setup baseScene(GameTestHelper helper) {
        var s = AlchemyScaffold.prepareTown(helper,
                new BlockPos(1, 0, 1), new BlockPos(9, 0, 9), 1);
        stockEmpties(s, helper);
        return s;
    }

    /** 箱料：空瓶 ×3 + 地狱疣 + 糖 + 烈焰粉——**刻意不给水瓶**（考验取水）。 */
    private static void stockEmpties(AlchemyScaffold.Setup s, GameTestHelper helper) {
        var chest = s.chest(helper);
        chest.setItem(0, new ItemStack(Items.GLASS_BOTTLE, 3));
        chest.setItem(1, new ItemStack(Items.NETHER_WART));
        chest.setItem(2, new ItemStack(Items.SUGAR));
        chest.setItem(3, new ItemStack(Items.BLAZE_POWDER));
        chest.setChanged();
    }

    private static void registerStation(AlchemyScaffold.Setup s, GameTestHelper helper) {
        s.town().workshop().waterSources.add(helper.absolutePos(STATION_REL).asLong());
    }

    /** 成功口径：4 瓶盖章迅捷药水（迅捷有 customEffects，countStamped 可识别）。 */
    private static void assertFourStamped(AlchemyScaffold.Setup s, GameTestHelper helper) {
        if (AlchemyScaffold.countStamped(s, helper, Items.POTION) < 4) {
            throw new GameTestAssertException(
                    "等待空瓶补水链产出 4 瓶盖章迅捷药水（取空瓶→水源站装水→酿造→入库）");
        }
    }
}
