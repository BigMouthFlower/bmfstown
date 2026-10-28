package com.bmf.bmfstown.town;

import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 空手选区会话（v0.9.1 不连续选区）：手册"增加选区"按钮开启，
 * 之后玩家**空手**右键两个角完成追加区块创建，Shift+右键取消。
 *
 * <h2>为什么是服务端会话而不是物品数据组件</h2>
 * 魔杖的选点存物品数据组件（跟随物品、自动同步做预览）；
 * 这里选点不在任何物品上，只能挂在服务端玩家会话表里。
 * 代价是没有客户端预览（v0.9.1 接受：聊天提示报点与尺寸）。
 *
 * <h2>生命周期</h2>
 * 开启：手册按钮（{@code TownManager.toggleAppendBlock}）；
 * 结束：确认创建 / Shift+右键取消 / 玩家登出 / 目标城镇被删（惰性清理）。
 * 会话常驻到显式结束——中途关游戏重进仍在（选点本身不保存，重进后从头选）。
 */
public final class ZoneSelectSessions {

    /** 一条进行中的选区会话：目标城镇 + 已选的点。 */
    record Session(UUID townId, BlockPos first, BlockPos second, long createdAt) {
    }

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    /** 会话有效期（毫秒）：超时自动失效，防止忘取消导致右键一直被拦截。 */
    private static final long EXPIRE_MS = 60_000L;

    /** 会话是否存在且未过期（过期顺手清除）。 */
    private static boolean live(Session s) {
        if (s == null) {
            return false;
        }
        if (System.currentTimeMillis() - s.createdAt() > EXPIRE_MS) {
            SESSIONS.values().removeIf(x ->
                    System.currentTimeMillis() - x.createdAt() > EXPIRE_MS);
            return false;
        }
        return true;
    }

    private ZoneSelectSessions() {
    }

    /** 开启（或重置）玩家的选区会话。 */
    public static void start(UUID playerId, UUID townId) {
        SESSIONS.put(playerId, new Session(townId, null, null, System.currentTimeMillis()));
    }

    /** 取消会话。返回 true = 之前确实有会话。 */
    public static boolean cancel(UUID playerId) {
        return SESSIONS.remove(playerId) != null;
    }

    /** 玩家是否有进行中的会话（60 秒未操作自动失效）。 */
    public static boolean active(UUID playerId) {
        return live(SESSIONS.get(playerId));
    }

    /** 会话的目标城镇；无会话（或已过期）返回 null。 */
    @Nullable
    public static UUID townId(UUID playerId) {
        Session s = SESSIONS.get(playerId);
        return live(s) ? s.townId() : null;
    }

    /** 记一个点：first 空 → 记 first；否则记 second。返回当前会话。 */
    public static Session pick(UUID playerId, BlockPos pos) {
        Session s = SESSIONS.get(playerId);
        if (!live(s)) {
            return null;
        }
        Session next = s.first() == null
                ? new Session(s.townId(), pos.immutable(), null, s.createdAt())
                : new Session(s.townId(), s.first(), pos.immutable(), s.createdAt());
        SESSIONS.put(playerId, next);
        return next;
    }

    /** 取出并结束会话（确认创建时用）。 */
    @Nullable
    public static Session take(UUID playerId) {
        return SESSIONS.remove(playerId);
    }

    /** 会话是否已经选满两个点。 */
    public static boolean complete(UUID playerId) {
        Session s = SESSIONS.get(playerId);
        return live(s) && s.first() != null && s.second() != null;
    }
}
