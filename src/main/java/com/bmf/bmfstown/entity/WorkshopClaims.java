package com.bmf.bmfstown.entity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 工坊岗位认领表（v0.13 从 WorkerGoal 提取共享）：
 * 工作台 asLong → 占用记录。**工人与烟火工匠共池**（决策 #30 先到先得）——
 * 谁先心跳谁占用，另一职业跳过该桌；不新增登记字段，玩家零配置。
 *
 * <p>服务端内存级；重启清空 → 各职业重新认领，无副作用。
 */
public final class WorkshopClaims {

    /** 岗位认领的过期时间（毫秒）：持有者长时间不刷新视为失踪，允许接管。 */
    public static final long STALE_MS = 30_000L;

    /** 占用记录。 */
    public record Claim(UUID holder, long at) {
    }

    private static final Map<Long, Claim> CLAIMS = new HashMap<>();

    /** 该岗位是否被别人占着（自己的记录不算；陈旧记录视为失踪可接管）。 */
    public static boolean isTaken(long key, UUID self) {
        Claim claim = CLAIMS.get(key);
        return claim != null && !claim.holder().equals(self)
                && System.currentTimeMillis() - claim.at() < STALE_MS;
    }

    /** 认领/心跳（持有者每 tick 刷新时间戳）。 */
    public static void put(long key, UUID holder) {
        CLAIMS.put(key, new Claim(holder, System.currentTimeMillis()));
    }

    /** 只释放自己的认领：接管过别人的陈旧记录时不误删。 */
    public static void release(long key, UUID self) {
        Claim claim = CLAIMS.get(key);
        if (claim != null && self.equals(claim.holder())) {
            CLAIMS.remove(key);
        }
    }

    private WorkshopClaims() {
    }
}
