package com.bmf.bmfstown.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 酒馆点单队列（决策 #65）：客人落座点单 → 酒保扫描送酒。
 *
 * <p>纯内存（不落档）：点单是瞬时状态，存读档/重启后队列清空，
 * 客人侧等单有超时自取兜底，不会卡死。酒保侧按"点单人在自己酒馆区内"
 * 过滤——避免跨镇送酒。
 */
public final class TavernOrders {

    private TavernOrders() {
    }

    /** level → (客人 UUID → 点单时所在酒馆区坐标锚点)。 */
    private static final Map<UUID, BlockPos> ORDERS = new HashMap<>();

    /** 已被酒保领走、还没送到的单（#66：交付前校验客人还在等——
     *  等超时/被打断的客人不塞酒，酒回窖，杜绝"塞给已经放弃的人"）。 */
    private static final java.util.Set<UUID> CLAIMED = new java.util.HashSet<>();

    /**
     * 点单铃声（#67 用户拍板"顾客主动通知酒保"）：客人挂单瞬间把同世界的
     * 酒保（48 格内，纯职业过滤——非本镇酒保 claim 时按锚点过滤天然领不到
     * 别镇的单，误响无害）标记进来；酒保 Goal 每 tick 消费
     * {@link #takeBell}，清掉扫描/喘息冷却立刻受理。
     */
    private static final java.util.Set<UUID> BELL = new java.util.HashSet<>();

    /** 落座点单（重复点单幂等）+ 打铃唤醒酒保。 */
    public static void place(Entity patron, BlockPos tavernAnchor) {
        ORDERS.put(patron.getUUID(), tavernAnchor.immutable());
        CLAIMED.remove(patron.getUUID());
        ringKeepers(patron);
    }

    /** 点单瞬间唤醒周围的酒保（低频事件，直接实体扫描可接受）。 */
    private static void ringKeepers(Entity patron) {
        if (!(patron.level() instanceof ServerLevel sl)) {
            return;
        }
        for (Resident r : sl.getEntitiesOfClass(Resident.class,
                new net.minecraft.world.phys.AABB(patron.blockPosition()).inflate(48.0D),
                r -> r.getProfession() == com.bmf.bmfstown.entity.profile.ProfessionType.TAVERNKEEP
                        && r.isAlive())) {
            BELL.add(r.getUUID());
        }
    }

    /** 酒保消费铃（每 tick 一次）：返回 true = 有人点单叫你，立刻回 SCAN 受理。 */
    public static boolean takeBell(Entity keeper) {
        return BELL.remove(keeper.getUUID());
    }

    /** 取消点单（喝到/超时/被打断）。 */
    public static void cancel(Entity patron) {
        ORDERS.remove(patron.getUUID());
        CLAIMED.remove(patron.getUUID());
    }

    /** 交付完成（酒已到客人手）：清领单标记。 */
    public static void close(Entity patron) {
        CLAIMED.remove(patron.getUUID());
    }

    /** 这位客人的单是否还在"已领待送"状态（酒保交付前校验用）。 */
    public static boolean isClaimed(Entity patron) {
        return CLAIMED.contains(patron.getUUID());
    }

    /** 酒保是否有人在等（按酒保所属酒馆区锚点过滤）。 */
    public static boolean hasOrderNear(ServerLevel level, java.util.List<BlockPos> anchors) {
        if (ORDERS.isEmpty()) {
            return false;
        }
        for (UUID id : ORDERS.keySet()) {
            BlockPos anchor = ORDERS.get(id);
            for (BlockPos a : anchors) {
                if (a != null && a.equals(anchor) && level.getEntity(id) instanceof net.minecraft.world.entity.LivingEntity le && le.isAlive()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 酒保领单：拿走最近的等单人（移出队列），返回 null = 没有可服务的单。
     * 仅接受点单锚点在本酒保酒馆区内的单。
     */
    public static Entity claim(ServerLevel level, java.util.List<BlockPos> anchors) {
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos bestAnchor = null;
        for (Map.Entry<UUID, BlockPos> e : ORDERS.entrySet()) {
            boolean mine = false;
            for (BlockPos a : anchors) {
                if (a != null && a.equals(e.getValue())) {
                    mine = true;
                    break;
                }
            }
            if (!mine) {
                continue;
            }
            if (level.getEntity(e.getKey()) instanceof net.minecraft.world.entity.LivingEntity le
                    && le.isAlive()) {
                // 距离基准用锚点区（点单位置），酒保跨区送酒由 navigate 兜底
                double d = le.blockPosition().distSqr(e.getValue());
                if (d < bestDist) {
                    bestDist = d;
                    best = le;
                    bestAnchor = e.getValue();
                }
            } else {
                // 死了/下线：清掉
                ORDERS.remove(e.getKey());
            }
        }
        if (best != null) {
            ORDERS.remove(best.getUUID());
            CLAIMED.add(best.getUUID()); // 领走待送：客人中途取消时据此作废交付
        }
        return best;
    }
}
