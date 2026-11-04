package com.bmf.bmfstown.town;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工坊登记会话（v0.12 工人重做，**城镇级**）：手册城镇页「登记方块」按钮开启，
 * 之后玩家**手持城镇手册**在城镇范围内右键箱子/工作台登记或取消登记，
 * 收起手册取消。
 *
 * <h2>与选区会话（{@link ZoneSelectSessions}）的同与异</h2>
 * 同：服务端会话表、开始/结束有客户端同步、互斥开启（开一个关另一个）。
 * 异：选区会话 60 秒从创建起硬超时；登记是"逐个点"的活，**每次登记都续期**，
 * 2 分钟无操作才失效——登记 20 个箱子不该中途断。
 *
 * <h2>状态存哪</h2>
 * 会话本身是瞬态（不落档）；已登记的方块落在 {@link Town} 的城镇级
 * 工坊登记表里（随存档持久化）。会话只回答"这个玩家现在正在给哪个镇登记"。
 */
public final class RegisterSessions {

    /** 一条进行中的登记会话：目标城镇（登记范围 = 该镇全部区域）。 */
    record Session(UUID townId, long lastAction) {
    }

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    /** 无操作过期时长（毫秒）；每次成功登记/取消都会续期。 */
    private static final long EXPIRE_MS = 120_000L;

    private RegisterSessions() {
    }

    /** 开启（或重置）玩家的登记会话。 */
    public static void start(UUID playerId, UUID townId) {
        SESSIONS.put(playerId, new Session(townId, System.currentTimeMillis()));
    }

    /** 操作续期：登记/取消一个方块时调，防止长流水线中途过期。 */
    public static void touch(UUID playerId) {
        Session s = SESSIONS.get(playerId);
        if (s != null) {
            SESSIONS.put(playerId, new Session(s.townId(), System.currentTimeMillis()));
        }
    }

    /** 取消会话。返回 true = 之前确实有会话。 */
    public static boolean cancel(UUID playerId) {
        return SESSIONS.remove(playerId) != null;
    }

    /** 会话是否存活（无操作超时自动清除）。 */
    public static boolean active(UUID playerId) {
        Session s = SESSIONS.get(playerId);
        if (s == null) {
            return false;
        }
        if (System.currentTimeMillis() - s.lastAction() > EXPIRE_MS) {
            SESSIONS.remove(playerId);
            return false;
        }
        return true;
    }

    /** 会话目标城镇；无会话（或已过期）返回 null。 */
    @Nullable
    public static Session get(UUID playerId) {
        return active(playerId) ? SESSIONS.get(playerId) : null;
    }
}
