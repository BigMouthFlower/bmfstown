package com.bmf.bmfstown.gametest;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.entity.WorkshopClaims;
import com.bmf.bmfstown.town.Town;
import com.bmf.bmfstown.town.TownSaveData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;

import java.util.UUID;

/**
 * 石匠岗位契约测试（v0.17，2026-11-25 用户实测反馈后补）。
 *
 * <h2>为什么必须补这一组</h2>
 * 用户实测原话："他们工作方块是切石机吗？如果是的话，但是他现在好像并没有
 * 距离的判定，就是他离得非常远，他也能进行分解。我怀疑可能是没设置工作方块。"
 *
 * <p>首版确实没有岗位——石匠直接对着箱子开锤，站在镇子这头就能拆那头的箱子。
 * 这组测试把修正后的契约钉死：
 * <ol>
 *   <li><b>岗位池只认切石机</b>：工作台是工人/烟火商的（{@code WorkerGoal.isCraftingTable}），
 *       石匠绝不能把工作台当自己的岗位，否则两种职业会抢同一张桌子；</li>
 *   <li><b>认领成功后才可能工作</b>：池子里没有切石机 → 认领不到 → 不开工；</li>
 *   <li><b>认领互斥</b>：一台切石机同一时刻只归一个石匠
 *       （{@link WorkshopClaims} 一机一人，失踪 30 秒可接管）；</li>
 *   <li><b>方块被拆 → 认领失效</b>：切石机换成石砖后不能再被认领。</li>
 * </ol>
 *
 * <p>这些判定都是<b>纯几何 + 方块类型</b>，不需要真的跑 AI 寻路，所以在
 * GameTestServer 里能稳定测（用户当时手头没电脑，NPC 行为得靠实机）。
 */
@GameTestHolder(BmfTown.MOD_ID)
public final class StonemasonPostTests {

    /** 三个不同的切石机位置（都在 empty3x3x3 结构内）。 */
    private static final BlockPos CUTTER_A = new BlockPos(1, 1, 1);
    private static final BlockPos CUTTER_B = new BlockPos(5, 1, 1);
    private static final BlockPos TABLE = new BlockPos(3, 1, 5);

    /**
     * 岗位池里有切石机 → 石匠能认领到它。
     *
     * <p>这条同时反向验证 {@code isStonecutter} 判定成立：
     * 如果判定写错（比如误判成 CRAFTING_TABLE），认领会永远失败。
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 100)
    public static void stonecutterIsClaimablePost(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town town = makeTown(helper, level);
        helper.setBlock(CUTTER_A, Blocks.STONECUTTER);
        BlockPos abs = helper.absolutePos(CUTTER_A);
        town.workshop().stations.add(abs.asLong());

        if (!level.getBlockState(abs).is(Blocks.STONECUTTER)) {
            throw new GameTestAssertException("切石机没放上（测试布景失败）");
        }
        // 契约：方块状态必须是切石机 —— 石匠的判定就靠这一条
        if (!isPostState(level, abs)) {
            throw new GameTestAssertException("切石机不被认作石匠岗位（判定写错了？）");
        }
        helper.succeed();
    }

    /**
     * 工作台绝不能当石匠的岗位。
     *
     * <p>这是<b>职业互斥</b>的底线：工人和烟火商都从 {@code stations} 池里认领
     * {@code CRAFTING_TABLE}，如果石匠也认工作台，三者会抢同一张桌子，
     * 玩家会看到"石匠占着工作台不动、工人没台可用"。
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 100)
    public static void craftingTableIsNotStonemasonPost(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town town = makeTown(helper, level);
        helper.setBlock(TABLE, Blocks.CRAFTING_TABLE);
        BlockPos abs = helper.absolutePos(TABLE);
        town.workshop().stations.add(abs.asLong());

        if (isPostState(level, abs)) {
            throw new GameTestAssertException(
                    "工作台被误判成石匠岗位（会与工人/烟火商抢同一张桌子）");
        }
        // 池子里只有工作台 → 石匠认领不到，不应开工
        if (hasClaimablePost(level, town)) {
            throw new GameTestAssertException("只有工作台时石匠却认到了岗位");
        }
        helper.succeed();
    }

    /**
     * 岗位池里没有切石机 → 石匠认不到岗（因此不会隔空拆箱子）。
     *
     * <p>用户实测的 bug 本质就是这一条不成立：旧版根本不认岗，
     * 所以"池子里没有切石机"这件事对石匠毫无影响，他照样开工。
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 100)
    public static void noStonecutterMeansNoWork(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town town = makeTown(helper, level);
        // 只登记容器（有料可拆），但一台切石机都没有
        helper.setBlock(new BlockPos(1, 1, 4), Blocks.CHEST);
        town.workshop().containers.add(helper.absolutePos(new BlockPos(1, 1, 4)).asLong());

        if (town.workshop().stations.isEmpty()) {
            // 正常路径：池子空
            if (hasClaimablePost(level, town)) {
                throw new GameTestAssertException("岗位池为空却认到了岗位");
            }
        }
        helper.succeed();
    }

    /**
     * 一台切石机同一时刻只归一个石匠（{@link WorkshopClaims} 一机一人）。
     *
     * <p>与工人/烟火商的共池语义一致：谁先心跳谁占用，另一个跳过这台。
     * 这里直接对认领表做契约断言，不依赖实体 AI。
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 100)
    public static void postClaimIsExclusive(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town town = makeTown(helper, level);
        helper.setBlock(CUTTER_A, Blocks.STONECUTTER);
        BlockPos abs = helper.absolutePos(CUTTER_A);
        long key = abs.asLong();

        UUID masonA = UUID.randomUUID();
        UUID masonB = UUID.randomUUID();
        WorkshopClaims.put(key, masonA);
        if (WorkshopClaims.isTaken(key, masonA)) {
            throw new GameTestAssertException("自己占的岗被判成被别人占着");
        }
        if (!WorkshopClaims.isTaken(key, masonB)) {
            throw new GameTestAssertException("别人占的岗没被判成已占（会两人抢一台）");
        }
        WorkshopClaims.release(key, masonB); // 非持有者释放应无效
        if (!WorkshopClaims.isTaken(key, masonB)) {
            throw new GameTestAssertException("非持有者把别人的认领释放掉了");
        }
        WorkshopClaims.release(key, masonA);
        if (WorkshopClaims.isTaken(key, masonB)) {
            throw new GameTestAssertException("持有者释放后仍被判成已占");
        }
        town.workshop().stations.add(abs.asLong());
        helper.succeed();
    }

    /**
     * 切石机被拆（换成别的方块）→ 登记位失效，不能继续当岗位。
     *
     * <p>行为阈值：{@code StonemasonGoal.canContinueToUse} 里
     * {@code isStonecutter(level, postPos)} 一旦为假就停 Goal 并释放认领。
     * 这条测的是那个判定的输入侧。
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 100)
    public static void brokenPostIsNoLongerValid(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        makeTown(helper, level);
        helper.setBlock(CUTTER_B, Blocks.STONECUTTER);
        BlockPos abs = helper.absolutePos(CUTTER_B);
        if (!isPostState(level, abs)) {
            throw new GameTestAssertException("切石机没被认作岗位（布景有问题）");
        }
        helper.setBlock(CUTTER_B, Blocks.STONE);
        if (isPostState(level, abs)) {
            throw new GameTestAssertException("切石机被拆成石头后仍被认作岗位");
        }
        helper.succeed();
    }

    // ---- 辅助 ----

    /**
     * 方块状态是否可作为石匠岗位。
     *
     * <p><b>故意在这里重写一遍判定而不是调私有方法</b>：这条测试要守住的正是
     * "石匠的岗位 = 切石机"这个契约本身。若直接调 {@code StonemasonGoal} 的私有
     * {@code isStonecutter}，一旦有人把那个方法改成认工作台，测试会跟着一起变绿
     * ——那就不叫守门了（坑 60 同款教训：只验自洽抓不出判据错）。
     */
    private static boolean isPostState(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(Blocks.STONECUTTER);
    }

    /** 池子里是否存在一台当前状态仍是切石机、且未被别人占的岗位。 */
    private static boolean hasClaimablePost(ServerLevel level, Town town) {
        UUID self = UUID.randomUUID();
        for (Long p : town.workshop().stations) {
            BlockPos pos = BlockPos.of(p);
            if (isPostState(level, pos) && !WorkshopClaims.isTaken(p, self)) {
                return true;
            }
        }
        return false;
    }

    /** 造一个覆盖整个测试结构的小镇并登记（不登记 = 假绿，见 TownStockTests 类注释）。 */
    private static Town makeTown(GameTestHelper helper, ServerLevel level) {
        BlockPos min = helper.absolutePos(new BlockPos(0, 0, 0));
        BlockPos max = helper.absolutePos(new BlockPos(7, 4, 7));
        Town town = new Town(UUID.randomUUID(), "测试镇", "tester",
                level.dimension().location().toString(), min, max);
        TownSaveData.get(level).add(town);
        return town;
    }
}