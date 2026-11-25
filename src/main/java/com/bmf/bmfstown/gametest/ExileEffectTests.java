package com.bmf.bmfstown.gametest;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.eerie.ExileEffect;
import com.bmf.bmfstown.eerie.VerificationEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 应验效果「流放地」的 GameTest（决策 #129）。
 *
 * <h2>能测什么、不能测什么</h2>
 * 无头 GameTest 环境<b>没有玩家</b>，所以"传送进房间 / 到期送回"这条链
 * 测不到（也不该用假玩家去测——传送成功与否不是这轮改动的风险点，
 * 风险点在房间形状与清理）。这里守的是三件确定性的东西：
 * <ol>
 *   <li>外壳封闭：内空四周与上下都必须是命令方块（玩家出不去）；</li>
 *   <li>内空干净：内层全是空气（否则玩家会被卡在方块里）；</li>
 *   <li>拆得干净：{@code clearRoom} 之后整块区域不留漂浮方块。</li>
 * </ol>
 * 这三条任意一条破了，玩家就会掉进虚空、卡住、或在世界上留下垃圾。
 */
@GameTestHolder(BmfTown.MOD_ID)
@PrefixGameTestTemplate(false)
public class ExileEffectTests {

    /**
     * 房间形状：外壳封闭 + 内空三格全是空气。
     *
     * <p>反向验证过：把 {@code shell} 判定改成永远 false（不建外壳）→ 本用例
     * 报"侧面没封住，玩家走出去掉虚空"。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 100)
    public static void roomIsSealedAndHollow(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(1, 1, 1));
        int half = ExileEffect.shellHalfForTest();
        int innerTop = ExileEffect.innerHeightForTest();

        ExileEffect.buildRoomForTest(level, base);
        try {
            // 1. 内空：脚下一格到头顶一格全是空气
            for (int dy = 1; dy <= innerTop; dy++) {
                for (int dx = -half + 1; dx <= half - 1; dx++) {
                    for (int dz = -half + 1; dz <= half - 1; dz++) {
                        BlockState s = level.getBlockState(base.offset(dx, dy, dz));
                        if (!s.isAir()) {
                            helper.fail("内空不该有方块：" + base.offset(dx, dy, dz) + " = " + s);
                        }
                    }
                }
            }
            // 2. 四面外墙：每一层都必须封住（漏一格就是掉虚空）
            for (int dy = 1; dy <= innerTop; dy++) {
                for (int dx = -half; dx <= half; dx++) {
                    for (int dz = -half; dz <= half; dz++) {
                        boolean wall = Math.abs(dx) == half || Math.abs(dz) == half;
                        if (!wall) {
                            continue;
                        }
                        BlockState s = level.getBlockState(base.offset(dx, dy, dz));
                        if (!ExileEffect.isShellBlockForTest(s)) {
                            helper.fail("侧面没封住，玩家走出去掉虚空：" + base.offset(dx, dy, dz) + " = " + s);
                        }
                    }
                }
            }
            // 3. 地板与天花板
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    BlockState floor = level.getBlockState(base.offset(dx, 0, dz));
                    if (!ExileEffect.isShellBlockForTest(floor)) {
                        helper.fail("地板漏了，玩家会掉下去：" + base.offset(dx, 0, dz) + " = " + floor);
                    }
                    BlockState ceil = level.getBlockState(base.offset(dx, innerTop + 1, dz));
                    if (!ExileEffect.isShellBlockForTest(ceil)) {
                        helper.fail("天花板漏了：" + base.offset(dx, innerTop + 1, dz) + " = " + ceil);
                    }
                }
            }
        } finally {
            ExileEffect.clearRoomForTest(level, base);
        }
        helper.succeed();
    }

    /**
     * 拆得干净：{@code clearRoom} 之后整块区域全是空气，不留漂浮方块。
     *
     * <p>这条守的是"应验结束后世界回到原样"——留一块漂浮在 y=250 的命令
     * 方块房，玩家飞上去就会看见，属于明显的世界观污染。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 100)
    public static void roomClearsCompletely(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(1, 1, 1));
        int half = ExileEffect.shellHalfForTest();
        int innerTop = ExileEffect.innerHeightForTest();

        ExileEffect.buildRoomForTest(level, base);
        ExileEffect.clearRoomForTest(level, base);

        for (int dy = 0; dy <= innerTop + 1; dy++) {
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    BlockState s = level.getBlockState(base.offset(dx, dy, dz));
                    if (!s.isAir() && !s.is(Blocks.AIR)) {
                        helper.fail("拆完还留了方块：" + base.offset(dx, dy, dz) + " = " + s);
                    }
                }
            }
        }
        helper.succeed();
    }

    /** 流放地已登记进效果注册表，且故事线声明的 id 能对上（防字符串写错）。 */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 100)
    public static void exileIsRegistered(GameTestHelper helper) {
        if (!VerificationEffects.isRegistered(ExileEffect.ID)) {
            helper.fail("流放地没登记进 VerificationEffects（故事线写 exile 会只 warn 不生效）");
        }
        if (!"exile".equals(ExileEffect.ID)) {
            helper.fail("ExileEffect.ID 与故事线登记表里的字符串不一致：" + ExileEffect.ID);
        }
        helper.succeed();
    }

    /** 无玩家时执行不该抛异常（应验静默退化成"只有公告"）。 */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 100)
    public static void exileWithoutPlayerIsSilent(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ExileEffect.resetForTest();
        try {
            // 无头环境本来就没有玩家；这里只要不抛异常即为通过
            VerificationEffects.run(ExileEffect.ID, level,
                    java.util.UUID.randomUUID(), null);
            if (ExileEffect.activeSessionsForTest() != 0) {
                helper.fail("没有玩家时不该建立流放会话，实际 " + ExileEffect.activeSessionsForTest());
            }
        } finally {
            ExileEffect.resetForTest();
        }
        helper.succeed();
    }
}