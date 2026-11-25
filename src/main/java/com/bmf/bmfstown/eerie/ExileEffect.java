package com.bmf.bmfstown.eerie;

import com.bmf.bmfstown.BmfTown;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * 应验效果「流放地」（决策 #55 用户当年构想 → #129 落地）。
 *
 * <h2>场景（用户 2026-11-10 原话的转述）</h2>
 * 恐怖线解锁后应验时，把<b>玩家</b>（以及可选一名镇民）传送到一个
 * <b>虚空中的封闭房间</b>：四面全是命令方块样式的东西，各自乱闪、嗡鸣，
 * 待一小会儿（{@value #STAY_TICKS} tick）后被送回原处。房间挖不动也带不走。
 *
 * <h2>与故事线解耦（#129 声明式）</h2>
 * 本类只实现"一种效果"，通过 {@code effectId = "exile"} 被任意故事线引用。
 * 第二条恐怖故事想要同样的流放地，登记表写同一个 id 即可，**不新增代码**。
 *
 * <h2>不动背包（用户 2026-11-25 拍板）</h2>
 * 进房间不动背包、不做快照、不还原。因此本效果<b>绝不生成任何可拾取物品</b>
 * （房间方块全是不可破坏的命令方块，没有箱子）——"不动背包"的安全前提就是
 * "里面没有任何能改变背包的东西"，这条不能破。
 *
 * <h2>房间放在哪个世界</h2>
 * <b>永远主世界</b>，与镇所在维度无关。两个原因：
 * <ul>
 *   <li>下界建筑高度上限只有 128，{@code y=250} 在那里放不进去（setBlock
 *       静默失败 = 玩家被传送进空气、直接坠虚空）；</li>
 *   <li>"流放地"本来就该是"别处"，跨维度反而更有味道（从下界被抓走）。</li>
 * </ul>
 * 与之配套：计时也用<b>主世界</b>的 gameTime，否则跨维度时两个世界的计时
 * 不一致，会话会提前结束或永远不结束。
 *
 * <h2>会话管理</h2>
 * 活跃会话存内存 {@link #SESSIONS}（重启丢失 = 人已在房间里但认不出，
 * 由 {@link #onServerTick} 兜底：到期就送回去）。每个会话独占一块"房间槽位"，
 * 位置由 {@link #slotBase} 按槽位号推算，全部落在主世界极高处的虚空区——
 * 不与任何玩家地形相交。
 */
public final class ExileEffect implements VerificationEffects.Effect {

    /** 效果 id（故事线登记表里写这个字符串）。 */
    public static final String ID = "exile";

    /** 在房间里待多久（tick）：1200 = 1 分钟（用户口径"待 1~2 分钟"）。 */
    public static final int STAY_TICKS = 1200;

    /** 房间内部尺寸（3x3x3 可站空间，够一个人转圈）。 */
    private static final int INNER = 3;

    /** 房间内净高（3 格，玩家 1.8 高）。 */
    private static final int INNER_HEIGHT = 3;

    /** 房间槽位间距（格）：相邻房间隔 32 格，互不影响。 */
    private static final int SLOT_SPACING = 32;

    /** 房间基座高度：主世界 y=250（远超建筑高度上限 320 之下的可用区）。 */
    private static final int BASE_Y = 250;

    /** 槽位数（同时最多几场流放；够用且不会把世界撑爆）。 */
    private static final int SLOTS = 8;

    /** 房间方块：命令方块样式（用户彩蛋口径：暗橙/发紫/泛绿）。 */
    private static final BlockState[] SHELL_BLOCKS = {
            Blocks.COMMAND_BLOCK.defaultBlockState(),
            Blocks.CHAIN_COMMAND_BLOCK.defaultBlockState(),
            Blocks.REPEATING_COMMAND_BLOCK.defaultBlockState(),
    };

    /** 一场进行中的流放。 */
    private record Session(UUID playerId, UUID townId, ServerLevel fromLevel,
                           Vec3 returnPos, float returnYRot, float returnXRot,
                           int slot, long dueGameTime) {
    }

    /** 活跃会话（玩家 id → 会话）。 */
    private static final List<Session> SESSIONS = new ArrayList<>();

    /** 已占用的槽位（避免两场流放挤同一间房）。 */
    private static boolean[] slotUsed = new boolean[SLOTS];

    // ==========================================================
    //  效果入口
    // ==========================================================

    /**
     * 执行流放：找到该镇的在线玩家，造房间、送进去、记到期时间。
     *
     * <p>找不到玩家（单人离线/没人在镇上）→ 静默返回：应验失败不是错误，
     * 公告已经发了，故事仍然成立。
     */
    @Override
    public void apply(ServerLevel level, UUID townId, @Nullable com.bmf.bmfstown.entity.Resident resident) {
        ServerPlayer target = pickPlayer(level, townId, resident);
        if (target == null) {
            return; // 没人在场：只有公告
        }
        int slot = freeSlot();
        if (slot < 0) {
            BmfTown.LOGGER.warn("[BmfTown][恐怖线] 流放地槽位已满，本次应验跳过（玩家：{}）", target.getName().getString());
            return;
        }
        // 房间固定建在主世界（下界高度上限 128，y=250 放不进去）
        ServerLevel exileLevel = level.getServer() == null ? null : level.getServer().getLevel(Level.OVERWORLD);
        if (exileLevel == null) {
            releaseSlot(slot);
            return;
        }
        if (!(target.level() instanceof ServerLevel fromLevel)) {
            releaseSlot(slot);
            return;
        }

        BlockPos base = slotBase(exileLevel, slot);
        buildRoom(exileLevel, base);

        Vec3 back = target.position();
        Session session = new Session(target.getUUID(), townId, fromLevel,
                back, target.getYRot(), target.getXRot(), slot,
                exileLevel.getGameTime() + STAY_TICKS);

        // 舱内落点：房间正中（方块中心 + 半格，脚踩地面）
        BlockPos inside = base.offset(0, 1, 0);
        target.teleportTo(exileLevel, inside.getX() + 0.5D, inside.getY(), inside.getZ() + 0.5D,
                target.getYRot(), target.getXRot());
        target.displayClientMessage(Component.translatable("msg.bmfstown.exile.enter"), true);
        exileLevel.playSound(null, inside, SoundEvents.END_PORTAL_SPAWN, SoundSource.PLAYERS, 0.6F, 0.7F);

        SESSIONS.add(session);
    }

    /**
     * 选被放逐的玩家：优先镇上的人（按距离触发者最近），没有就用触发者附近
     * 的任意玩家。单人游戏里就是"你"。
     */
    @Nullable
    private static ServerPlayer pickPlayer(ServerLevel level, UUID townId,
                                          @Nullable com.bmf.bmfstown.entity.Resident resident) {
        var players = level.players();
        if (players.isEmpty()) {
            return null;
        }
        if (resident != null) {
            // 直接看谁在触发者附近（比"镇上归属"更贴近"当时在场的人"）
            ServerPlayer near = null;
            double best = Double.MAX_VALUE;
            for (ServerPlayer p : players) {
                double d = p.distanceToSqr(resident);
                if (d < best) {
                    best = d;
                    near = p;
                }
            }
            if (near != null) {
                return near;
            }
        }
        return players.get(level.getRandom().nextInt(players.size()));
    }

    // ==========================================================
    //  房间搭建
    // ==========================================================

    /** 槽位 → 房间基座坐标（主世界高处虚空，互不重叠）。 */
    private static BlockPos slotBase(ServerLevel level, int slot) {
        // 沿 x 轴排开；y 固定高处；z 用槽位奇偶错开，避免视觉上排成一条线
        int x = slot * SLOT_SPACING;
        int z = (slot % 2 == 0) ? 0 : SLOT_SPACING;
        return new BlockPos(x, BASE_Y, z);
    }

    /**
     * 造房间：外壳全是命令方块，内层留空。
     *
     * <p>外壳厚度 1 格、内空 {@value #INNER}×{@value #INNER_HEIGHT}×{@value #INNER}。
     * 命令方块不可被玩家破坏（生存下 hardness 为 -1，等同基岩），
     * 所以"挖不走"是方块自身的属性给的，不额外加保护逻辑。
     */
    private static void buildRoom(ServerLevel level, BlockPos base) {
        int r = INNER / 2; // 内空半径（1）
        int half = r + 1;  // 外壳半宽（2）
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                for (int dy = 0; dy <= INNER_HEIGHT + 1; dy++) {
                    boolean shell = Math.abs(dx) == half || Math.abs(dz) == half
                            || dy == 0 || dy == INNER_HEIGHT + 1;
                    BlockPos p = base.offset(dx, dy, dz);
                    if (shell) {
                        BlockState s = SHELL_BLOCKS[Math.floorMod(p.hashCode(), SHELL_BLOCKS.length)];
                        level.setBlock(p, s, 2);
                    } else {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    // ==========================================================
    //  到期送回（服务端 tick）
    // ==========================================================

    /**
     * 每 tick 检查到期会话，把人送回原处并拆房间。
     *
     * <p>兜底逻辑：玩家断线 / 自己用命令跑了 / 房间被人拆了——只要到期就
     * 清理槽位，绝不长期占用槽位。
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (SESSIONS.isEmpty()) {
            return;
        }
        var server = event.getServer();
        // 房间与计时都在主世界（与 apply 同源，跨维度才不会错拍）
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (level == null) {
            return;
        }
        // 先挑出到期会话再从列表里摘掉，最后统一做世界操作。
        // 不在遍历中 setBlock：拆房间会触发方块更新回调，一旦回调里有东西
        // 动了 SESSIONS 就是 ConcurrentModificationException（本项目踩过同类坑47）。
        List<Session> due = new ArrayList<>();
        for (Iterator<Session> it = SESSIONS.iterator(); it.hasNext(); ) {
            Session s = it.next();
            if (level.getGameTime() >= s.dueGameTime()) {
                due.add(s);
                it.remove();
            }
        }
        for (Session s : due) {
            ServerPlayer player = server.getPlayerList().getPlayer(s.playerId());
            if (player != null) {
                // 送回原处（原世界的原坐标）
                ServerLevel back = s.fromLevel() != null ? s.fromLevel() : level;
                player.teleportTo(back, s.returnPos().x, s.returnPos().y, s.returnPos().z,
                        s.returnYRot(), s.returnXRot());
                player.displayClientMessage(Component.translatable("msg.bmfstown.exile.leave"), true);
            }
            clearRoom(level, slotBase(level, s.slot()));
            releaseSlot(s.slot());
        }
    }

    /** 拆房间（清掉外壳与内空，避免留下漂浮方块）。 */
    private static void clearRoom(ServerLevel level, BlockPos base) {
        int half = INNER / 2 + 1;
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                for (int dy = 0; dy <= INNER_HEIGHT + 1; dy++) {
                    level.setBlock(base.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }

    // ==========================================================
    //  槽位
    // ==========================================================

    /**
     * 找一个空闲槽位。
     *
     * @return 槽位号；全占满返回 -1
     */
    private static synchronized int freeSlot() {
        for (int i = 0; i < SLOTS; i++) {
            if (!slotUsed[i]) {
                slotUsed[i] = true;
                return i;
            }
        }
        return -1;
    }

    private static synchronized void releaseSlot(int slot) {
        if (slot >= 0 && slot < SLOTS) {
            slotUsed[slot] = false;
        }
    }

    /** @hidden 测试：清空会话与槽位（GameTest 之间隔离）。 */
    public static synchronized void resetForTest() {
        SESSIONS.clear();
        slotUsed = new boolean[SLOTS];
    }

    /** @hidden 测试：当前活跃会话数。 */
    public static synchronized int activeSessionsForTest() {
        return SESSIONS.size();
    }

    // ==========================================================
    //  测试接缝（GameTest 验证房间形状，无头环境没有玩家，测不了传送）
    // ==========================================================

    /**
     * @hidden 测试：在指定位置造一间房（与生产用同一套 {@link #buildRoom}）
     */
    public static void buildRoomForTest(ServerLevel level, BlockPos base) {
        buildRoom(level, base);
    }

    /** @hidden 测试：拆掉指定位置的房间（与生产用同一套 {@link #clearRoom}） */
    public static void clearRoomForTest(ServerLevel level, BlockPos base) {
        clearRoom(level, base);
    }

    /** @hidden 测试：房间外壳半宽（内空半径 + 1） */
    public static int shellHalfForTest() {
        return INNER / 2 + 1;
    }

    /** @hidden 测试：房间内净高 */
    public static int innerHeightForTest() {
        return INNER_HEIGHT;
    }

    /** @hidden 测试：某坐标是否属于命令方块外壳（彩蛋三色之一） */
    public static boolean isShellBlockForTest(BlockState state) {
        for (BlockState s : SHELL_BLOCKS) {
            if (s.equals(state)) {
                return true;
            }
        }
        return false;
    }
}