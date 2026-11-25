package com.bmf.bmfstown.gametest;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.ModEntities;
import com.bmf.bmfstown.ModItems;
import com.bmf.bmfstown.entity.JobKit;
import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.entity.StonemasonGoal;
import com.bmf.bmfstown.entity.profile.ProfessionType;
import com.bmf.bmfstown.town.Town;
import com.bmf.bmfstown.town.TownSaveData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;

import java.util.UUID;

/**
 * 石匠"打断不刷物品、不吞物品"契约测试（坑 65，2026-11-25 用户实测）。
 *
 * <h2>用户报告的原话</h2>
 * "他每次一拿东西我就打断他，一拿东西我就打断他，我发现个箱子里送的东西
 * 越来越多，这他妈什么 bug 呀……这是个非常严重的刷物品 bug。"
 *
 * <h2>两个洞，方向相反</h2>
 * <ol>
 *   <li><b>凭空造物（刷）</b>：{@code tickScan} 一选中箱子就把数量写进
 *       {@code workingHeld}（此时料<b>还在箱子里没扣</b>），而 {@code stop()}
 *       只要看到这个字段非零就往容器塞一份"退还"。用户反复打断会反复多一份。</li>
 *   <li><b>凭空吞物</b>：拆出的通货放在 Goal 内存的 {@code pending} 列表；
 *       {@code stop()} 只调 {@code giveBack}（扫背包）然后 {@code pending.clear()}
 *       ——{@code pending} 不在背包里扫不到，紧跟着的 clear 把产物抹掉。</li>
 * </ol>
 *
 * <h2>为什么用契约式测法而不是跑 AI</h2>
 * 让真实 Goal 恰好停在"已扫描未取料"的窗口受摸鱼/寻路/作息影响。首版就是
 * 那么写的，结果同一用例两次运行可红可绿（{@code canUse} 里有随机摸鱼判定，
 * 而且反复调它还会改变认领状态）。这里改成
 * <b>直接摆状态 → 调 stop() → 验物品守恒</b>：走的是生产代码的同一条退料
 * 路径，但时序完全确定。
 *
 * <h2>守的是守恒律</h2>
 * <pre>
 *   箱里的建材 + 箱里的通货 + 身上的 + 掉地上的 ≡ 初始值
 * </pre>
 * 多出来 = 刷，少掉了 = 吞。不关心内部怎么记账，只要求玩家能感知的总量不变。
 */
@GameTestHolder(BmfTown.MOD_ID)
public final class StonemasonConservationTests {

    /** 切石机岗位（相对坐标；3x3x3 结构内）。 */
    private static final BlockPos CUTTER = new BlockPos(1, 1, 1);
    /** 料箱（相对坐标）。 */
    private static final BlockPos CHEST = new BlockPos(4, 1, 4);
    /** 石匠出生点。 */
    private static final BlockPos SPAWN = new BlockPos(2, 1, 2);

    /**
     * <b>核心用例一：只"选中"还没取料时打断，箱子里的料不能多。</b>
     *
     * <p>这正是用户反复打断时踩的窗口。旧代码在这里会凭空多出一份
     * （{@code workingHeld} 提前落账 + {@code stop()} 无条件退料）。
     *
     * <p>断言算上背包与掉落物：多出来的那份可能被 {@code stowOrDrop} 兜底塞进
     * 背包或掉在脚边，只数箱子会漏。
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 300)
    public static void interruptingBeforePickupNeverDuplicates(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Scene sc = scene(helper, level);
        Container chest = chestAt(helper, level);
        chest.setItem(0, new ItemStack(Items.STONE_BRICKS, 8));
        chest.setChanged();

        // 摆出"我已选好这箱，但还没走过去取"的状态
        sc.goal.testPlanOnlyForTest(Items.STONE_BRICKS);
        if (sc.goal.testSnapshot().heldCount() != 0) {
            throw new GameTestAssertException("只选中未取料，却已落账 held>0（坑 65 复发）");
        }

        sc.goal.stop(); // 用户在这一瞬间打断他

        int after = countAll(sc.mason, level, Items.STONE_BRICKS);
        if (after > 8) {
            throw new GameTestAssertException(
                    "只选中未取料时被打断，石砖从 8 变成 " + after + " —— 凭空造物（坑 65）");
        }
        if (after < 8) {
            throw new GameTestAssertException(
                    "只选中未取料时被打断，石砖从 8 变成 " + after + " —— 凭空吞物（坑 65）");
        }
        helper.succeed();
    }

    /**
     * <b>核心用例二：反复打断 20 次，总量恒定。</b>
     *
     * <p>用户的原话就是"一拿东西我就打断"，单次可能恰好踩在安全窗口，
     * 所以这条连续打断并每轮校验——旧代码下累计多出的份数会很明显。
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 300)
    public static void repeatedInterruptsKeepConservation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Scene sc = scene(helper, level);
        Container chest = chestAt(helper, level);
        chest.setItem(0, new ItemStack(Items.STONE_BRICKS, 8));
        chest.setChanged();

        for (int i = 0; i < 20; i++) {
            // 每轮重新摆"选了但没取"的状态（玩家在石匠刚选好料时反复打断）
            sc.goal.testPlanOnlyForTest(Items.STONE_BRICKS);
            sc.goal.stop();
            int now = countAll(sc.mason, level, Items.STONE_BRICKS);
            if (now != 8) {
                throw new GameTestAssertException(
                        "第 " + (i + 1) + " 次打断后石砖总数 = " + now + "（应为 8）"
                                + " —— 反复打断会累积刷物品/吞物品（坑 65）");
            }
        }
        helper.succeed();
    }

    /**
     * <b>核心用例三：真端着料时打断，料必须原样退回。</b>
     *
     * <p>这是"真·已从容器扣减"的情形，退料<b>必须</b>生效——否则就是吞货。
     * 和用例一的区别只在落账时机：只有这里 {@code workingHeld} 才允许非零。
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 300)
    public static void interruptingWithHeldMaterialsReturnsThem(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Scene sc = scene(helper, level);
        Container chest = chestAt(helper, level);
        chest.setItem(0, new ItemStack(Items.STONE_BRICKS, 8));
        chest.setChanged();

        // 真扣 8 个到手（走生产代码的同一条扣减路径）
        int taken = sc.goal.testTakeIntoHandForTest(chest, Items.STONE_BRICKS, 8,
                helper.absolutePos(CHEST));
        if (taken != 8) {
            throw new GameTestAssertException("取料数量异常：" + taken + "（应为 8）");
        }
        if (countInChest(level, sc, Items.STONE_BRICKS) != 0) {
            throw new GameTestAssertException("取料后箱子应空，实际还有料（扣减没生效）");
        }

        sc.goal.stop(); // 端着料被打断

        int after = countAll(sc.mason, level, Items.STONE_BRICKS);
        if (after != 8) {
            throw new GameTestAssertException(
                    "端着 8 个料被打断后总数 = " + after + "（应为 8）"
                            + " —— 少了是吞货，多了是刷货（坑 65）");
        }
        if (sc.goal.testSnapshot().heldCount() != 0) {
            throw new GameTestAssertException(
                    "退料后记账没清零，下次 stop 会退第二遍（坑 65 的复发路径）");
        }
        helper.succeed();
    }

    /**
     * <b>核心用例四：拆出的通货还没入库时被打断，通货不能消失。</b>
     *
     * <p>这是第二个洞。旧版 {@code stop()} 里 {@code giveBack} 扫背包扫不到
     * {@code pending}（它是 Goal 内存里的 List），紧跟着的 {@code clear()}
     * 直接把产物抹了——玩家看到的是"拆完被打断，料没了"。
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 300)
    public static void interruptingWithPendingCargoNeverLosesIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Scene sc = scene(helper, level);

        // 模拟 tickWork 已结算出 5 份石料，但 STORE_PUT 还没跑就被打断
        sc.goal.testAddPendingForTest(new ItemStack(ModItems.MATERIAL_STONE.get(), 5));
        sc.goal.stop();

        int stone = countAll(sc.mason, level, ModItems.MATERIAL_STONE.get());
        if (stone != 5) {
            throw new GameTestAssertException(
                    "未入库的 5 份石料被打断后只剩 " + stone + " 份"
                            + " —— 凭空吞掉产物（坑 65 的第二个洞）");
        }
        helper.succeed();
    }

    /**
     * 正向兜底：没有端料、没有 pending 时打断，不许凭空往箱子里塞东西。
     *
     * <p>旧代码在扫描阶段已落账的情况下即使"没取"，也会塞——正是本 bug 的机制。
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 300)
    public static void idleStopPutsNothingIntoContainers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Scene sc = scene(helper, level);
        int before = countAll(sc.mason, level, Items.STONE_BRICKS);

        sc.goal.stop(); // 空手打断

        int after = countAll(sc.mason, level, Items.STONE_BRICKS);
        if (after != before) {
            throw new GameTestAssertException(
                    "空手打断却让石砖从 " + before + " 变成 " + after
                            + " —— 退料逻辑在没端料时乱塞（坑 65）");
        }
        helper.succeed();
    }

    // ==========================================================
    //  布景 / 工具
    // ==========================================================

    /** 场景句柄：镇民 + 它的石匠 Goal（已断言非空）。 */
    private record Scene(Resident mason, StonemasonGoal goal) {
    }

    /** 搭一个最小场景：镇（覆盖整个结构）+ 登记的切石机与箱子 + 石匠。 */
    private static Scene scene(GameTestHelper helper, ServerLevel level) {
        for (int x = 0; x <= 7; x++) {
            for (int z = 0; z <= 7; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.SMOOTH_STONE);
            }
        }
        helper.setBlock(CUTTER, Blocks.STONECUTTER);
        helper.setBlock(CHEST, Blocks.CHEST);

        BlockPos min = helper.absolutePos(new BlockPos(0, 0, 0));
        BlockPos max = helper.absolutePos(new BlockPos(7, 4, 7));
        Town town = new Town(UUID.randomUUID(), "石匠守恒镇", "tester",
                level.dimension().location().toString(), min, max);
        TownSaveData.get(level).add(town); // 必须登记，否则 boundTown 拿不到
        town.workshop().stations.add(helper.absolutePos(CUTTER).asLong());
        town.workshop().containers.add(helper.absolutePos(CHEST).asLong());

        Resident mason = helper.spawn(ModEntities.RESIDENT.get(), SPAWN);
        mason.getIdentity().setProfession(ProfessionType.STONEMASON);
        mason.getIdentity().setTownId(town.id());
        mason.syncClient(); // getProfession() 读同步快照，改完必须刷新

        StonemasonGoal goal = mason.stonemasonGoalForTest();
        if (goal == null) {
            throw new GameTestAssertException("石匠 Goal 不存在（注册漏了？）");
        }
        return new Scene(mason, goal);
    }

    private static Container chestAt(GameTestHelper helper, ServerLevel level) {
        var be = level.getBlockEntity(helper.absolutePos(CHEST));
        if (!(be instanceof ChestBlockEntity chest)) {
            throw new GameTestAssertException("箱子没放上（布景失败）");
        }
        return chest;
    }

    /** 只数登记容器里的某物品（用于"扣减是否真生效"的中间断言）。 */
    private static int countInChest(ServerLevel level, Scene sc, Item item) {
        int n = 0;
        Town town = sc.mason.boundTown();
        if (town == null) {
            return 0;
        }
        for (long p : town.workshop().containers) {
            Container c = JobKit.containerAt(level, BlockPos.of(p));
            if (c == null) {
                continue;
            }
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack st = c.getItem(i);
                if (!st.isEmpty() && st.is(item)) {
                    n += st.getCount();
                }
            }
        }
        return n;
    }

    /**
     * 全场景清点某物品总数：掉落物 + 石匠背包 + 登记容器。
     *
     * <p>为什么要算掉落物：退料兜底是 {@code 容器 → 背包 → 掉脚边}，
     * 只数容器会把"掉在地上"的料当成凭空消失而误报。
     */
    private static int countAll(Resident mason, ServerLevel level, Item item) {
        int n = 0;
        // ① 掉在地上的（兜底最后一级）
        for (ItemEntity entity : level.getEntitiesOfClass(
                ItemEntity.class, mason.getBoundingBox().inflate(24))) {
            if (entity.getItem().is(item)) {
                n += entity.getItem().getCount();
            }
        }
        // ② 石匠背包
        var inv = mason.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (!st.isEmpty() && st.is(item)) {
                n += st.getCount();
            }
        }
        // ③ 登记的容器
        Town town = mason.boundTown();
        if (town != null) {
            for (long p : town.workshop().containers) {
                Container c = JobKit.containerAt(level, BlockPos.of(p));
                if (c == null) {
                    continue;
                }
                for (int i = 0; i < c.getContainerSize(); i++) {
                    ItemStack st = c.getItem(i);
                    if (!st.isEmpty() && st.is(item)) {
                        n += st.getCount();
                    }
                }
            }
        }
        return n;
    }
}