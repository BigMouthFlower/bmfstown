package com.bmf.bmfstown.gametest;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.ModEntities;
import com.bmf.bmfstown.entity.NavOps;
import com.bmf.bmfstown.entity.Resident;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * NavOps 全局寻路口径（2026-11-18：酒保顶着挡路酒桶无限重发寻路卡死的修复）。
 * 目标被围死 → 有限重试（3 次、次次隔 20t）→ STUCK 放弃 + 目标拉黑 10 秒；
 * 别的坐标不受牵连。纯逻辑驱动（noAi 居民 + 手动按 20t 间隔调 approach），
 * 不依赖真实行走。
 */
@GameTestHolder(BmfTown.MOD_ID)
public class NavOpsTests {

    @GameTest(template = "room11x4x11", timeoutTicks = 300)
    public static void navOpsStuckGivesUpAndBlacklists(GameTestHelper helper) {
        Resident r = helper.spawn(ModEntities.RESIDENT.get(), new BlockPos(3, 1, 3));
        r.setNoAi(true); // AI 不动：navigation 永不 tick，isDone 恒 true，计数确定性推进
        // 目标 = 实体墙芯：寻路 createPath 必败（path=null→isDone 恒 true），
        // 且居民永远到不了 2 格内（贴墙 dx=2,dy=1 → distSqr 5 > 4）
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 0; dy <= 2; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    helper.setBlock(new BlockPos(8 + dx, 1 + dy, 8 + dz), Blocks.STONE);
                }
            }
        }
        BlockPos target = helper.absolutePos(new BlockPos(8, 2, 8));
        // "别的目标"要离居民 2 格开外（distSqr>4），否则 approach 直接 ARRIVED 而非 MOVING
        BlockPos other = helper.absolutePos(new BlockPos(1, 1, 9));

        final int[] attempts = {0};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            helper.runAtTickTime(10 + i * 20, () -> {
                NavOps.Nav nav = NavOps.approach(r, target, 2.0D, 1.0D);
                if (nav != NavOps.Nav.MOVING) {
                    helper.fail("第 " + (idx + 1) + " 次推进应仍在 MOVING，实际 " + nav);
                }
                if (NavOps.blocked(r, target)) {
                    helper.fail("重试期内目标不应被拉黑");
                }
            });
        }
        helper.runAtTickTime(10 + 3 * 20, () -> {
            NavOps.Nav nav = NavOps.approach(r, target, 2.0D, 1.0D);
            if (nav != NavOps.Nav.STUCK) {
                throw new GameTestAssertException("3 次推进后应判 STUCK，实际 " + nav);
            }
            if (!NavOps.blocked(r, target)) {
                throw new GameTestAssertException("STUCK 后目标应被拉黑（10 秒内）");
            }
            if (NavOps.blocked(r, other)) {
                throw new GameTestAssertException("别的坐标不应被牵连拉黑");
            }
        });
        // 拉黑期内直拒：不走路不烧重试，别的目标照常 MOVING（2026-11-19 语义）
        helper.runAtTickTime(10 + 3 * 20 + 10, () -> {
            if (NavOps.approach(r, target, 2.0D, 1.0D) != NavOps.Nav.STUCK) {
                helper.fail("拉黑期内再调 approach 应立即 STUCK（不许再撞）");
            }
            if (NavOps.approach(r, other, 2.0D, 1.0D) != NavOps.Nav.MOVING) {
                helper.fail("别的目标不受牵连，应照常 MOVING");
            }
            helper.succeed();
        });
    }

    /**
     * findStandable：几何中心被实体方块占住时，就近螺旋找到能站的格子
     * （2026-11-19 回城/回岗卡死的病根——目标必须是可站立格）。
     */
    @GameTest(template = "room11x4x11", timeoutTicks = 100)
    public static void navOpsFindStandableNearBlockedCenter(GameTestHelper helper) {
        BlockPos center = new BlockPos(5, 1, 5);
        // 中心是实心石头：站不进去
        helper.setBlock(center, Blocks.STONE);
        // findStandable 走绝对坐标（level 空间）——相对坐标要换算（坑27）
        BlockPos centerAbs = helper.absolutePos(center);
        BlockPos found = com.bmf.bmfstown.entity.NavOps.findStandable(
                helper.getLevel(), centerAbs, 3, centerAbs.getY() - 1, centerAbs.getY() + 2);
        if (found == null) {
            helper.fail("中心被占时应在附近找到可站立格");
        }
        if (found.equals(centerAbs)) {
            helper.fail("不该返回被占的中心本身");
        }
        // 找到的格子必须能站：脚下实心 + 本格与头顶无碰撞
        var level = helper.getLevel();
        if (level.getBlockState(found.below()).getCollisionShape(level, found.below()).isEmpty()
                || !level.getBlockState(found).getCollisionShape(level, found).isEmpty()
                || !level.getBlockState(found.above()).getCollisionShape(level, found.above()).isEmpty()) {
            helper.fail("找到的 " + found.toShortString() + " 不是可站立格");
        }
        // 四周全围死时返回 null（半径内确实无处可站）
        BlockPos box = new BlockPos(3, 1, 3);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                helper.setBlock(new BlockPos(box.getX() + dx, 1, box.getZ() + dz), Blocks.STONE);
                helper.setBlock(new BlockPos(box.getX() + dx, 2, box.getZ() + dz), Blocks.STONE);
            }
        }
        BlockPos none = com.bmf.bmfstown.entity.NavOps.findStandable(
                helper.getLevel(), helper.absolutePos(box), 1,
                helper.absolutePos(box).getY(), helper.absolutePos(box).getY() + 1);
        if (none != null) {
            helper.fail("3×3×2 全实心的箱体内不应找到可站立格，实际 " + none.toShortString());
        }
        helper.succeed();
    }

    /**
     * 集成用例（真实 AI，2026-11-19 用户实测场景复现）：居民在<b>城外</b>，
     * 直线路径被一排木桶挡死，侧翼有空位——必须绕路进城，不许顶着桶卡死。
     *
     * <p>布局：城镇=结构东半（x5..10, z3..9）；桶排摆在 x=4（城外贴边，z3..7）；
     * 工作台贴城西边（桶排正东）——工人在城外认领它，直线 3 格内"到位"会不停
     * 停掉导航与回城拔河（修前病根）。修后：城外期间职业挂起，回城把绕路走完。
     */
    @GameTest(template = "room11x4x11", timeoutTicks = 900)
    public static void residentOutsideTownWalksAroundBarrelRowIntoTown(
            GameTestHelper helper) {
        // 全图地板（城镇区 prepareTown 会再铺一遍，重叠无害）
        for (int x = 0; x <= 10; x++) {
            for (int z = 0; z <= 10; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.SMOOTH_STONE);
            }
        }
        // 城镇=东半
        AlchemyScaffold.Setup s = AlchemyScaffold.prepareTown(
                helper, new BlockPos(5, 1, 3), new BlockPos(10, 1, 9), 0);
        // 工作台贴城西边缘（工人的认领目标）
        BlockPos table = new BlockPos(5, 1, 6);
        helper.setBlock(table, Blocks.CRAFTING_TABLE);
        s.town().workshop().stations.add(helper.absolutePos(table).asLong());
        // 桶排：x=4, z3..7——挡死从西边进城的直线；南北两翼（z0..2 / z8..10）空
        for (int z = 3; z <= 7; z++) {
            helper.setBlock(new BlockPos(4, 1, z), Blocks.BARREL);
        }
        // 工人生成在城外西侧 (2,1,6)，正对桶排
        Resident worker = helper.spawn(ModEntities.RESIDENT.get(), new BlockPos(2, 1, 6));
        worker.getIdentity().setProfession(com.bmf.bmfstown.entity.profile.ProfessionType.WORKER);
        worker.getIdentity().setTownId(s.town().id());
        worker.syncClient();
        worker.getJobState().setOrderItem("minecraft:redstone_torch");
        s.residents().add(worker);
        // 断言：限时内自己走进城镇范围（用 town.containsEntity 权威口径，与游戏一致）
        com.bmf.bmfstown.town.Town townRef = s.town();
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (helper.getTick() % 100 == 0) {
                        com.mojang.logging.LogUtils.getLogger().info(
                                "[NavOpsTests] tick={} worker.pos={} contains={}",
                                helper.getTick(), worker.blockPosition().toShortString(),
                                townRef.containsEntity(worker.blockPosition()));
                    }
                    if (!townRef.containsEntity(worker.blockPosition())) {
                        throw new GameTestAssertException("居民还没进城，abs="
                                + worker.blockPosition().toShortString()
                                + "，tick=" + helper.getTick());
                    }
                })
                .thenSucceed();
    }

    /**
     * 实体归属口径（2026-11-19 实测，存档解码实锤）：酒馆选区 Y68~73（架空
     * 高台），居民在楼下地面 Y63——XZ 足迹在城内、Y 在选区之外。方块级
     * contains 严格三轴判"出城"，回城目标又锁高台中心 → 整晚掐掉-换点拔河。
     * containsEntity 只看 XZ：楼下/楼上都算在城里；选区外仍判出城。
     */
    @GameTest(template = "room11x4x11", timeoutTicks = 100)
    public static void containsEntityIgnoresYBounds(GameTestHelper helper) {
        // 城镇边界也要换算绝对坐标（坑27：相对/绝对混用，相对边界配绝对点=永远 false）
        com.bmf.bmfstown.town.Town town = new com.bmf.bmfstown.town.Town(
                java.util.UUID.randomUUID(), "高台城", "测试员", "minecraft:overworld",
                helper.absolutePos(new BlockPos(5, 4, 3)),
                helper.absolutePos(new BlockPos(10, 4, 9)), 0L);
        BlockPos below = helper.absolutePos(new BlockPos(6, 1, 4)); // XZ 在城内，Y 在选区下
        BlockPos above = helper.absolutePos(new BlockPos(6, 8, 4)); // XZ 在城内，Y 在选区上
        BlockPos inside = helper.absolutePos(new BlockPos(6, 4, 4)); // 三轴全在
        BlockPos westOut = helper.absolutePos(new BlockPos(4, 4, 4)); // X 在城外
        BlockPos southOut = helper.absolutePos(new BlockPos(6, 4, 12)); // Z 在城外
        if (!town.containsEntity(below)) {
            helper.fail("高台选区楼下的地面居民应算在城里（XZ 足迹口径）");
        }
        if (!town.containsEntity(above)) {
            helper.fail("高台选区上空的居民应算在城里（XZ 足迹口径）");
        }
        if (!town.containsEntity(inside) || !town.contains(inside)) {
            helper.fail("三轴全在的坐标两种口径都应在城里");
        }
        if (town.contains(below)) {
            helper.fail("方块级 contains 仍应严格三轴（Y 在选区外=不在）");
        }
        if (town.containsEntity(westOut) || town.containsEntity(southOut)) {
            helper.fail("XZ 足迹在城外的坐标应判出城");
        }
        helper.succeed();
    }

    /**
     * 进度验收（2026-11-19 实测"阿灯一动不动"）：寻路给了路径（可达目标）
     * 但物理上走不动（noAi 居民永不挪窝）——旧实现只认 isDone，永远 MOVING
     * 静默卡死。修后：3 秒没挪窝掐掉重寻路，烧满 3 次推进判 STUCK 并拉黑。
     */
    @GameTest(template = "room11x4x11", timeoutTicks = 800)
    public static void navOpsStalledPathGivesUpAndBlacklists(GameTestHelper helper) {
        Resident r = helper.spawn(ModEntities.RESIDENT.get(), new BlockPos(3, 1, 3));
        r.setNoAi(true);
        // 目标在开阔地板上（寻路必成、路径"进行中"），但 noAi 居民永不挪窝。
        // 断言只锚末态（烧满 3 次"卡死掐掉+推进"后必拉黑），不锚中间节拍——
        // noAi 下导航 tick 是否自停会改变节拍，末态一致。
        BlockPos target = helper.absolutePos(new BlockPos(8, 1, 8));
        for (int i = 0; i < 34; i++) {
            final int idx = i;
            helper.runAtTickTime(10 + i * 20, () -> {
                NavOps.Nav nav = NavOps.approach(r, target, 2.0D, 1.0D);
                if (nav == NavOps.Nav.ARRIVED) {
                    helper.fail("第 " + idx + " 次调用不该误判到位（noAi 永远走不过去）");
                }
            });
        }
        helper.runAtTickTime(670, () -> {
            if (!NavOps.blocked(r, target)) {
                throw new GameTestAssertException("卡死目标最终应被拉黑（进度验收烧满判 STUCK）");
            }
            helper.succeed();
        });
    }

    /**
     * 拉黑递增退避（2026-11-19 改版）：同一目标第二次 STUCK，拉黑时长应翻倍
     * （10 秒→20 秒）。旧版递增条件"到期后 11 秒内再失败"在换点循环里永远
     * 赶不上，堵死的目标每 25 秒被撞一次（实测日志实锤）。
     */
    @GameTest(template = "room11x4x11", timeoutTicks = 700)
    public static void navOpsBlacklistEscalatesOnRepeatStuck(GameTestHelper helper) {
        Resident r = helper.spawn(ModEntities.RESIDENT.get(), new BlockPos(3, 1, 3));
        r.setNoAi(true);
        // 目标=实体墙芯：寻路必败（isDone 恒真），推进按 20t 节流确定性烧
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 0; dy <= 2; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    helper.setBlock(new BlockPos(8 + dx, 1 + dy, 8 + dz), Blocks.STONE);
                }
            }
        }
        BlockPos target = helper.absolutePos(new BlockPos(8, 2, 8));
        for (int i = 0; i < 3; i++) {
            helper.runAtTickTime(10 + i * 20, () ->
                    NavOps.approach(r, target, 2.0D, 1.0D));
        }
        // 第 1 次 STUCK（t=70，拉黑 10 秒=200t，到期 t=270）
        helper.runAtTickTime(70, () -> {
            if (NavOps.approach(r, target, 2.0D, 1.0D) != NavOps.Nav.STUCK) {
                helper.fail("第 1 次 STUCK 应在 t=70 判定");
            }
        });
        // 到期后重新烧 3 次推进（t=280/300/320 MOVING）
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            helper.runAtTickTime(280 + i * 20, () -> {
                if (NavOps.approach(r, target, 2.0D, 1.0D) != NavOps.Nav.MOVING) {
                    helper.fail("第 " + (idx + 1) + " 轮推进应 MOVING（拉黑已到期）");
                }
            });
        }
        // 第 2 次 STUCK（t=340，递增后拉黑 20 秒=400t，到期 t=740）
        helper.runAtTickTime(340, () -> {
            if (NavOps.approach(r, target, 2.0D, 1.0D) != NavOps.Nav.STUCK) {
                helper.fail("第 2 次 STUCK 应在 t=340 判定");
            }
        });
        // t=600：不递增的话 200t 拉黑早到（540）期了；递增到 400t 则仍在拉黑
        helper.runAtTickTime(600, () -> {
            if (NavOps.approach(r, target, 2.0D, 1.0D) != NavOps.Nav.STUCK) {
                helper.fail("t=600 仍应在拉黑期内（第 2 次拉黑翻倍到 20 秒）");
            }
            helper.succeed();
        });
    }

    /**
     * 分级脱困（2026-11-19 反馈"兜底能用但太慢、缺轻量修正"）：
     * 卡 5 秒=轻量自救（跳+掐掉路径）；轻量 2 次仍卡=传送脱困。
     * 手动按 ~120t 间隔驱动（模拟 20t 轮询节奏）。
     */
    @GameTest(template = "room11x4x11", timeoutTicks = 600)
    public static void residentWedgedTeleportsFree(GameTestHelper helper) {
        Resident r = helper.spawn(ModEntities.RESIDENT.get(), new BlockPos(2, 1, 2));
        BlockPos far = helper.absolutePos(new BlockPos(9, 1, 9));
        final BlockPos[] held = new BlockPos[1];
        // 先让 AI 开着落地（noAi 物理不 tick、永不 onGround、寻路起点无效
        // ——对照实验实锤），落地瞬间冻结：位置不变+路径永不走完=楔死状态
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (!r.onGround()) {
                        throw new GameTestAssertException("等落地 tick=" + helper.getTick());
                    }
                })
                .thenExecute(() -> {
                    r.setNoAi(true);
                    boolean ok = r.getNavigation().moveTo(
                            far.getX() + 0.5D, far.getY(), far.getZ() + 0.5D, 1.0D);
                    if (!ok) {
                        helper.fail("落地平地 moveTo 应成功建路（noAi 冻结在地面）");
                    }
                    // 环墙制造真楔死（他的立足格周围全封住——模拟"楔进酒桶缝"）
                    BlockPos feet = r.blockPosition();
                    held[0] = feet.immutable();
                    for (int dy = 0; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                if (dx != 0 || dz != 0) {
                                    helper.setBlock(new BlockPos(
                                            feet.getX() + dx, feet.getY() + dy,
                                            feet.getZ() + dz), Blocks.STONE);
                                }
                            }
                        }
                    }
                })
                .thenExecute(() -> r.unstickIfWedged(helper.getLevel())) // 开观察窗
                // +120t：第 1 级轻量自救——路径被掐掉（navDone 翻 true），不传送
                .thenIdle(120)
                .thenExecute(() -> {
                    r.unstickIfWedged(helper.getLevel());
                    if (!r.getNavigation().isDone()) {
                        helper.fail("卡 5 秒应触发轻量自救（掐掉路径），实际路径仍在跑");
                    }
                    if (!r.blockPosition().equals(held[0])) {
                        helper.fail("轻量自救不许传送");
                    }
                    // 重发路径恢复楔死状态
                    if (!r.getNavigation().moveTo(
                            far.getX() + 0.5D, far.getY(), far.getZ() + 0.5D, 1.0D)) {
                        helper.fail("重发路径应成功（noAi 冻结在地面）");
                    }
                })
                // +120t：第 2 次轻量自救——仍不传送
                .thenIdle(120)
                .thenExecute(() -> {
                    r.unstickIfWedged(helper.getLevel());
                    if (!r.blockPosition().equals(held[0])) {
                        helper.fail("第 2 次轻量自救仍不许传送");
                    }
                    if (!r.getNavigation().moveTo(
                            far.getX() + 0.5D, far.getY(), far.getZ() + 0.5D, 1.0D)) {
                        helper.fail("重发路径应成功（noAi 冻结在地面）");
                    }
                })
                // +120t：轻量 2 次烧完=真楔死 → 传送脱困
                .thenIdle(120)
                .thenExecute(() -> {
                    r.unstickIfWedged(helper.getLevel());
                    if (r.blockPosition().equals(held[0])) {
                        throw new GameTestAssertException("轻量 2 次无效应传送脱困，实际仍在原地 "
                                + r.blockPosition().toShortString());
                    }
                    helper.succeed();
                });
    }

    @GameTest(template = "room11x4x11", timeoutTicks = 500)
    public static void residentIdleNotTeleported(GameTestHelper helper) {
        Resident r = helper.spawn(ModEntities.RESIDENT.get(), new BlockPos(2, 1, 2));
        r.setNoAi(true);
        BlockPos spawnAbs = helper.absolutePos(new BlockPos(2, 1, 2));
        // 没有任何路径（工作驻足/发呆）：位置长时间不变也不许脱困（两级都不行）
        helper.runAtTickTime(20, () -> r.unstickIfWedged(helper.getLevel()));
        helper.runAtTickTime(140, () -> r.unstickIfWedged(helper.getLevel()));
        helper.runAtTickTime(260, () -> r.unstickIfWedged(helper.getLevel()));
        helper.runAtTickTime(400, () -> {
            r.unstickIfWedged(helper.getLevel());
            if (!r.blockPosition().equals(spawnAbs)) {
                helper.fail("合法驻足不该被脱困（更不该被传送）");
            }
            helper.succeed();
        });
    }

    /**
     * 跨空隙带放行（2026-11-19 回归：酒保/客人走几步就被拉回，酒吧没法上班）。
     * 酒馆等追加区块在城框外、与城框之间有空隙带——居民穿空隙途中"不在任何
     * 区块 XZ"，旧代码在放行判定【之前】就挂起职业 Goal：路径断了没人重发，
     * 下一轮 navTarget 变空 → 回城接管拉回城里 → 挂起解除 → Goal 又带他出去
     * ……无限循环。修后：带着城内（含追加区块）目标的合法跨区不挂起不回城。
     */
    @GameTest(template = "room11x4x11", timeoutTicks = 1200)
    public static void gapBandCrossingNotSuspended(GameTestHelper helper) {
        for (int x = 0; x <= 10; x++) {
            for (int z = 0; z <= 10; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.SMOOTH_STONE);
            }
        }
        // 追加区块（东条）与城框（西条）之间 x 4..6 为空隙带。
        // 必须注册进 TownSaveData（否则城检 findTown=null 直接跳过、
        // 漫步也无限制——第一版用例因此假绿/假红）
        com.bmf.bmfstown.town.Town town = new com.bmf.bmfstown.town.Town(
                java.util.UUID.randomUUID(), "跨隙城", "测试员", "minecraft:overworld",
                helper.absolutePos(new BlockPos(1, 1, 1)),
                helper.absolutePos(new BlockPos(3, 1, 9)), 0L);
        town.addTownBlock(new com.bmf.bmfstown.town.TownZone(
                java.util.UUID.randomUUID(), "酒馆",
                helper.absolutePos(new BlockPos(7, 1, 1)),
                helper.absolutePos(new BlockPos(9, 1, 9))));
        com.bmf.bmfstown.town.TownSaveData.get(helper.getLevel()).add(town);
        com.bmf.bmfstown.gametest.AlchemyScaffold.forceWorkTime(helper); // 锁工作时段
        BlockPos barCenter = helper.absolutePos(new BlockPos(8, 1, 5));
        // 居民生成在空隙带正中，直调 navigation 去追加区块（模拟酒保上班）
        Resident keeper = helper.spawn(ModEntities.RESIDENT.get(), new BlockPos(5, 1, 5));
        keeper.getIdentity().setTownId(town.id());
        keeper.syncClient();
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (!keeper.onGround()) {
                        throw new GameTestAssertException("等落地 tick=" + helper.getTick());
                    }
                })
                .thenExecute(() -> {
                    if (!keeper.getNavigation().moveTo(
                            barCenter.getX() + 0.5D, barCenter.getY(),
                            barCenter.getZ() + 0.5D, 1.0D)) {
                        helper.fail("穿空隙去追加区块的寻路应成功");
                    }
                    // 落地瞬间城检可能先跑一轮（当时还没路径=瞬态挂起），
                    // moveTo 后下一轮城检自动放行解除——不在此刻断言
                })
                .thenWaitUntil(() -> {
                    // 东向偏置：导航一空闲立刻重发去东条——漫步 Goal 的目标
                    // 锚在城框（西条）内，随机触发会抢导航把人往西溜达，
                    // 上一版靠单次 moveTo 因此偶发 600t 到不了（并行波动）
                    if (keeper.getNavigation().isDone()) {
                        keeper.getNavigation().moveTo(
                                barCenter.getX() + 0.5D, barCenter.getY(),
                                barCenter.getZ() + 0.5D, 1.0D);
                    } else {
                        // 漫步路径进行中且目标在西条：掐掉，下一 tick 偏置重发
                        // （漫步目标改深后路径耗时变长，只等 isDone 窗口太小
                        // ——2026-11-20 偶发翻车的真因）
                        var tp = keeper.getNavigation().getTargetPos();
                        if (tp != null && tp.getX() < barCenter.getX()) {
                            keeper.getNavigation().stop();
                        }
                    }
                    // 行为级到达断言：脚底 X 越过东条西缘（绝对坐标；
                    // relativePos 基准与结构角不对应，勿用）。若回归（空隙被
                    // 挂起+回城拉回西条），永远到不了东条 → 超时失败
                    if (keeper.blockPosition().getX() >= barCenter.getX()) {
                        if (keeper.jobSuspended()) {
                            throw new GameTestAssertException("到达东条时不应仍处于挂起");
                        }
                        return; // 穿过空隙到达追加区块
                    }
                    throw new GameTestAssertException("等跨过空隙 tick=" + helper.getTick());
                })
                .thenSucceed();
    }

    /**
     * 水源站落脚点选择（2026-11-19 实测：含水树叶被箱子/酒桶排围挡时，
     * 直达树叶的寻路退化、楔进方块缝，取水任务永远完不成）。
     * spotNear 必须返回站旁"可站立且不被拉黑"的落脚点，且拉黑后换格。
     */
    @GameTest(template = "room11x4x11", timeoutTicks = 100)
    public static void spotNearSkipsObstructedStation(GameTestHelper helper) {
        for (int x = 0; x <= 10; x++) {
            for (int z = 0; z <= 10; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.SMOOTH_STONE);
            }
        }
        // 含水树叶（三面围挡：北/西/东，头顶也盖死——只留南侧可接近）
        helper.setBlock(new BlockPos(5, 1, 5), Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties
                        .BlockStateProperties.WATERLOGGED, true));
        helper.setBlock(new BlockPos(5, 1, 4), Blocks.SMOOTH_STONE);
        helper.setBlock(new BlockPos(4, 1, 5), Blocks.SMOOTH_STONE);
        helper.setBlock(new BlockPos(6, 1, 5), Blocks.SMOOTH_STONE);
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.SMOOTH_STONE);
        BlockPos leaf = helper.absolutePos(new BlockPos(5, 1, 5));
        Resident r = helper.spawn(ModEntities.RESIDENT.get(), new BlockPos(5, 1, 8));
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (!r.onGround()) {
                        throw new GameTestAssertException("等落地 tick=" + helper.getTick());
                    }
                })
                .thenExecute(() -> {
                    BlockPos spot = com.bmf.bmfstown.entity.NavOps.spotNear(
                            helper.getLevel(), r, leaf, 2, 1, 3.0D);
                    if (spot == null) {
                        helper.fail("南侧开阔必有落脚点");
                    }
                    var lvl = helper.getLevel();
                    if (lvl.getBlockState(spot.below()).getCollisionShape(lvl, spot.below()).isEmpty()
                            || !lvl.getBlockState(spot).getCollisionShape(lvl, spot).isEmpty()
                            || !lvl.getBlockState(spot.above()).getCollisionShape(lvl, spot.above()).isEmpty()) {
                        helper.fail("落脚点必须可站立: " + spot.toShortString());
                    }
                    if (spot.distSqr(leaf) > 9.0D) {
                        helper.fail("落脚点必须在交互范围内: " + spot.toShortString());
                    }
                    // 拉黑换格：同一个落脚点不能被反复选中（换点失败级联的根基）
                    com.bmf.bmfstown.entity.NavOps.blacklist(r, spot);
                    BlockPos again = com.bmf.bmfstown.entity.NavOps.spotNear(
                            helper.getLevel(), r, leaf, 2, 1, 3.0D);
                    if (spot.equals(again)) {
                        helper.fail("拉黑后必须换下一个落脚点，不能再选 " + spot.toShortString());
                    }
                })
                .thenSucceed();
    }

    /**
     * "能站但到不了"的落脚点必须被路径验证拦下（2026-11-19 实测"每次取水
     * 必靠卡死兜底"第二层病根）：西侧栅栏+石墙围出露天腔 (4,1,5)——
     * isStandable 通过、离站 1 格、离居民最近，但寻路进不去（栅栏寻路器
     * 永不翻越）。旧实现按几何最近直接选它 → approach 烧重试 → 楔死判定
     * 先到=永远靠传送救场。现在必须跳过它选可达点，且腔体落点被当场拉黑。
     * 布局要点：居民出生格与所有比腔体更近的格子都必须不是候选（要么超出
     * 站点交互范围、要么石块占住）——否则 spotNear 短路先返回它们，腔体
     * 轮不到评估（前两版测试就是这么假阴的）。
     */
    @GameTest(template = "room11x4x11", timeoutTicks = 300)
    public static void spotNearSkipsPathUnreachableSpot(GameTestHelper helper) {
        for (int x = 0; x <= 10; x++) {
            for (int z = 0; z <= 10; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.SMOOTH_STONE);
            }
        }
        // 露天腔 (4,1,5)：北/南/西三面 **3 格高**石墙（跳不上 2 格高差；墙顶
        // y=4 节点在结构区外——2 高墙/栅栏都封不死：栅栏上空格被寻路器当
        // 可站节点、单层石块可跳上去斜跳进腔体，前两版全漏）；东面=树叶
        // 全碰撞 + 头顶 2 格石
        helper.setBlock(new BlockPos(5, 1, 5), Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties
                        .BlockStateProperties.WATERLOGGED, true));
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.SMOOTH_STONE);
        helper.setBlock(new BlockPos(5, 3, 5), Blocks.SMOOTH_STONE);
        for (int y = 1; y <= 3; y++) {
            helper.setBlock(new BlockPos(4, y, 4), Blocks.SMOOTH_STONE);
            helper.setBlock(new BlockPos(4, y, 6), Blocks.SMOOTH_STONE);
            helper.setBlock(new BlockPos(3, y, 5), Blocks.SMOOTH_STONE);
        }
        // 堵掉所有 distSqr(居民) ≤ 5 的候选并封死跳台——**必须加高到 2**：
        // yBand=1 时单层石块的顶 (x,2,z) 本身就是可站候选（前两版假阴的
        // 真凶：石块顶 d=2 比陷阱 d=5 更近，spotNear 短路先返回，陷阱轮
        // 不到评估）；加高后顶部 y=3 超出候选带。三面 3 高墙封死入腔路线
        helper.setBlock(new BlockPos(2, 1, 5), Blocks.SMOOTH_STONE);
        for (BlockPos p : new BlockPos[]{
                new BlockPos(3, 1, 3), new BlockPos(3, 1, 4), new BlockPos(3, 1, 6),
                new BlockPos(3, 1, 7), new BlockPos(4, 1, 7)}) {
            helper.setBlock(p, Blocks.SMOOTH_STONE);
            helper.setBlock(p.above(), Blocks.SMOOTH_STONE);
        }
        BlockPos trap = helper.absolutePos(new BlockPos(4, 1, 5));
        Resident r = helper.spawn(ModEntities.RESIDENT.get(), new BlockPos(2, 1, 6));
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (!r.onGround()) {
                        throw new GameTestAssertException("等落地 tick=" + helper.getTick());
                    }
                })
                .thenExecute(() -> {
                    r.setNoAi(true); // 落地后冻结：漫步 Goal 不抢位，选点输入确定性（坑32 口径）
                    BlockPos spot = com.bmf.bmfstown.entity.NavOps.spotNear(
                            helper.getLevel(), r, helper.absolutePos(new BlockPos(5, 1, 5)),
                            2, 1, 3.0D);
                    if (spot == null) {
                        helper.fail("腔外开阔必有可达落脚点");
                    }
                    if (spot.equals(trap)) {
                        helper.fail("到不了的露天腔落点不能被选中: " + spot.toShortString());
                    }
                    if (!com.bmf.bmfstown.entity.NavOps.blocked(r, trap)) {
                        helper.fail("路径验证不过的落点必须被当场拉黑");
                    }
                })
                .thenSucceed();
    }

    /**
     * "终点可达但路必穿拉黑格"不算可达（2026-11-19 实测"传送完 5 秒又楔回
     * 同一缝"）：单门小黑屋，门格是屋里一切落脚点的必经之路。门被拉黑后
     * 寻路器照样把门当路过点生成完整路径——spotNear 必须拒收这类路径，
     * 返回 null 让调用方整站拉黑换目标，而不是把人领进缝里再靠传送救场。
     */
    @GameTest(template = "room11x4x11", timeoutTicks = 300)
    public static void spotNearRejectsPathThroughBlockedCell(GameTestHelper helper) {
        for (int x = 0; x <= 10; x++) {
            for (int z = 0; z <= 10; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.SMOOTH_STONE);
            }
        }
        // 隔墙 x=6（y1..2 全高），门开 2 格高 (6,1,5)+(6,2,5)——居民身高
        // 1.8，1 高的门洞钻不过去（首版踩坑）；屋内 x=7..10
        for (int z = 0; z <= 10; z++) {
            for (int y = 1; y <= 2; y++) {
                if (z == 5) {
                    continue; // 门柱整列留空（2 格高门洞）
                }
                helper.setBlock(new BlockPos(6, y, z), Blocks.SMOOTH_STONE);
            }
        }
        // 含水树叶（站点）在屋内深处：候选半径 3 只覆盖屋内格（x≥6），
        // 门格本身被 candidate 循环的 blocked 过滤排除
        helper.setBlock(new BlockPos(9, 1, 5), Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties
                        .BlockStateProperties.WATERLOGGED, true));
        helper.setBlock(new BlockPos(9, 2, 5), Blocks.SMOOTH_STONE);
        Resident r = helper.spawn(ModEntities.RESIDENT.get(), new BlockPos(2, 1, 5));
        BlockPos door = helper.absolutePos(new BlockPos(6, 1, 5));
        BlockPos leaf = helper.absolutePos(new BlockPos(9, 1, 5));
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (!r.onGround()) {
                        throw new GameTestAssertException("等落地 tick=" + helper.getTick());
                    }
                })
                .thenExecute(() -> {
                    r.setNoAi(true); // 落地后冻结（坑32 口径）
                    // 拉黑前：门开着，屋内必有可达落脚点（range 2.5 把门格
                    // distSqr=9 排除在候选外，候选只剩屋内格）
                    BlockPos spot = NavOps.spotNear(helper.getLevel(), r, leaf, 3, 1, 2.5D);
                    if (spot == null) {
                        helper.fail("门开着时屋内必有可达落脚点");
                    }
                    if (spot.equals(door)) {
                        helper.fail("门格不该被选为落脚点");
                    }
                    // 拉黑门格（楔死传送的同款调用）：去屋内的唯一路线必穿门
                    // 楔死级拉黑（markWedge）：只有楔死格才拦路径路过；普通拉黑
                    // 不拦路过（2026-11-20 桶区雪崩修复后两级别语义分离）
                    NavOps.markWedge(r, door, 1200L);
                    BlockPos after = NavOps.spotNear(helper.getLevel(), r, leaf, 3, 1, 2.5D);
                    if (after != null) {
                        helper.fail("唯一路线穿拉黑格的落脚点不算可达，不能选 "
                                + after.toShortString());
                    }
                })
                .thenSucceed();
    }

    /**
     * 拉黑分级（2026-11-20 桶区雪崩修复）：普通拉黑只拦"当目标"、<b>不拦
     * 路径路过</b>；楔死级（markWedge）才拦路过。单走廊场景：路中一格先
     * 普通拉黑——走廊尽头的落脚点仍可达；改楔死级——唯一路线穿楔死格，
     * 走廊内所有落脚点必须全拒。旧实现两级别不分：桶排近道上的普通拉黑
     * 让周围所有候选"路径被拒"→逐个拉黑→一个 tick 锁死整个桶区。
     */
    @GameTest(template = "room11x4x11", timeoutTicks = 300)
    public static void plainBlacklistDoesNotBlockTransit(GameTestHelper helper) {
        for (int x = 0; x <= 10; x++) {
            for (int z = 0; z <= 10; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.SMOOTH_STONE);
            }
        }
        // 单走廊 z=5：两侧 2 高墙（z=4/z=6），唯一路线沿 z=5 直行
        for (int x = 0; x <= 10; x++) {
            helper.setBlock(new BlockPos(x, 1, 4), Blocks.SMOOTH_STONE);
            helper.setBlock(new BlockPos(x, 2, 4), Blocks.SMOOTH_STONE);
            helper.setBlock(new BlockPos(x, 1, 6), Blocks.SMOOTH_STONE);
            helper.setBlock(new BlockPos(x, 2, 6), Blocks.SMOOTH_STONE);
        }
        // 站点：含水树叶（走廊尽头 x=9）
        helper.setBlock(new BlockPos(9, 1, 5), Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties
                        .BlockStateProperties.WATERLOGGED, true));
        BlockPos mid = helper.absolutePos(new BlockPos(5, 1, 5));
        Resident r = helper.spawn(ModEntities.RESIDENT.get(), new BlockPos(1, 1, 5));
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (!r.onGround()) {
                        throw new GameTestAssertException("等落地 tick=" + helper.getTick());
                    }
                })
                .thenExecute(() -> {
                    r.setNoAi(true); // 落地后冻结：漫步 Goal 不抢位（坑32 口径）
                    BlockPos leaf = helper.absolutePos(new BlockPos(9, 1, 5));
                    // ① 普通拉黑路中格：走廊尽头落脚点仍可达
                    NavOps.blacklist(r, mid, 1200L);
                    BlockPos spot = NavOps.spotNear(
                            helper.getLevel(), r, leaf, 3, 1, 3.0D);
                    if (spot == null) {
                        helper.fail("普通拉黑不该拦路径路过——走廊尽头落脚点应可达");
                    }
                })
                .thenIdle(70) // 等 pathOk 缓存（60t）过期，② 才会真寻路
                .thenExecute(() -> {
                    BlockPos leaf = helper.absolutePos(new BlockPos(9, 1, 5));
                    // ② 改楔死级：唯一路线穿楔死格，走廊内全部不可达
                    NavOps.markWedge(r, mid, 1200L);
                    BlockPos after = NavOps.spotNear(
                            helper.getLevel(), r, leaf, 3, 1, 3.0D);
                    if (after != null) {
                        helper.fail("楔死格拦路过——唯一路线穿楔死格应全部不可达: "
                                + after.toShortString());
                    }
                })
                .thenSucceed();
    }

    /**
     * allowAbove=false 时站点上方的落点一律不选（2026-11-20 实测"封桶顶
     * 酒保站盖板上隔着方块交互"）：站点石块+顶上盖石（盖顶=可站格 dy+2，
     * yBand=2 内），侧面同层地板开阔。禁上方必须只选 y≤站点 的侧面格；
     * 缺省重载（取水口径）不查此项。
     */
    @GameTest(template = "room11x4x11", timeoutTicks = 300)
    public static void spotNearNeverPicksAboveWhenForbidden(GameTestHelper helper) {
        for (int x = 0; x <= 10; x++) {
            for (int z = 0; z <= 10; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.SMOOTH_STONE);
            }
        }
        // 站点（代酒桶）：实心石块；顶上盖石——盖顶 (5,3,5) 是 dy+2 可站格
        helper.setBlock(new BlockPos(5, 1, 5), Blocks.SMOOTH_STONE);
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.SMOOTH_STONE);
        Resident r = helper.spawn(ModEntities.RESIDENT.get(), new BlockPos(8, 1, 8));
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (!r.onGround()) {
                        throw new GameTestAssertException("等落地 tick=" + helper.getTick());
                    }
                })
                .thenExecute(() -> {
                    r.setNoAi(true); // 落地后冻结（坑32 口径）
                    BlockPos station = helper.absolutePos(new BlockPos(5, 1, 5));
                    BlockPos spot = com.bmf.bmfstown.entity.NavOps.spotNear(
                            helper.getLevel(), r, station, 2, 2, 3.0D, false);
                    if (spot == null) {
                        helper.fail("侧面同层地板开阔必有落脚点");
                    }
                    if (spot.getY() > station.getY()) {
                        helper.fail("禁上方时选了高于站点的落点 " + spot.toShortString()
                                + "——正是实机站盖板交互的病根");
                    }
                })
                .thenSucceed();
    }

    /**
     * approach 的 Y 门槛（2026-11-20 实测"封桶顶酒保站盖板上隔着交互、
     * 禁上方也没变化"的真身）：人已在盖板上时，侧面落脚点距离 2~3 格、
     * 视线通畅——纯距离判定直接 ARRIVED 短路，永远不触发走下去的导航。
     * 带门槛（maxDyAbove=0，桶交互口径）必须判未到位并发路；无门槛
     * （ cellar/客人口径）同几何仍然到位。
     */
    @GameTest(template = "room11x4x11", timeoutTicks = 300)
    public static void approachArrivalGateRejectsAboveStand(GameTestHelper helper) {
        for (int x = 0; x <= 10; x++) {
            for (int z = 0; z <= 10; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.SMOOTH_STONE);
            }
        }
        // 盖板（代封桶顶的石台阶）：居民站上面 = 桶顶场景
        helper.setBlock(new BlockPos(6, 1, 5), Blocks.SMOOTH_STONE);
        Resident r = helper.spawn(ModEntities.RESIDENT.get(), new BlockPos(6, 2, 5));
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (!r.onGround()) {
                        throw new GameTestAssertException("等落地 tick=" + helper.getTick());
                    }
                })
                .thenExecute(() -> {
                    BlockPos spot = helper.absolutePos(new BlockPos(4, 1, 6));
                    // 无门槛：距离 distSqr=4+1+1=6 < 9、视线通 → 到位（旧口径）
                    NavOps.Nav plain = NavOps.approach(r, spot, 3.0D, 1.0D);
                    if (plain != NavOps.Nav.ARRIVED) {
                        helper.fail("无门槛口径应判到位（证明确实是 Y 门槛拦的）");
                    }
                    // 有门槛（桶交互）：脚 Y 高于落脚点 → 必须不算到位
                    NavOps.Nav gated = NavOps.approach(r, spot, 3.0D, 1.0D, 0);
                    if (gated == NavOps.Nav.ARRIVED) {
                        helper.fail("带 Y 门槛时站在盖板上不算到位——短路就是实机病根");
                    }
                })
                .thenSucceed();
    }
}
