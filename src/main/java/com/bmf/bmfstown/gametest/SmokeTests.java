package com.bmf.bmfstown.gametest;

import com.bmf.bmfstown.BmfTown;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * GameTest 冒烟用例（2026-11-07 架子立项）：验证"无头服务器 → 测试结构 → 断言 → 报告"
 * 整条管线能跑。真正的炼金用例在 {@link AlchemyFlowTests}（搭完脚手架后补）。
 */
@GameTestHolder(BmfTown.MOD_ID)
public class SmokeTests {

    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void smokePlaceBrewingStand(GameTestHelper helper) {
        // GameTestHelper 的方法吃的是**结构内相对坐标**，不要 absolutePos 再转一遍
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, Blocks.BREWING_STAND);
        helper.assertBlock(pos, b -> b == Blocks.BREWING_STAND, "酿造台未放置");
        helper.succeed();
    }
}
