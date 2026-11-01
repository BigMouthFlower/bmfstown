package com.bmf.bmfstown.client;

import com.bmf.bmfstown.network.ModNetworking;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端缓存的世界解锁状态（v0.10，**访问空间 = 城镇**）。
 *
 * <p>UI（面板/手册）的可见性开关直接查 {@link #isUnlocked(UUID, String)}：
 * 没解锁就不画那个标签页/条目。同步时机：解锁瞬间全量快照广播 +
 * 玩家登录补发——UI 永远不用自己关心"解锁"是什么，只问"这个城镇开了没"。
 */
public final class ClientUnlockState {

    /** 城镇 id → 已解锁 id 集合。 */
    private static final Map<UUID, Set<String>> BY_TOWN = new ConcurrentHashMap<>();

    private ClientUnlockState() {
    }

    /** 收到全量快照（解锁瞬间 / 登录补发）。 */
    public static void handleSync(ModNetworking.UnlockSyncPayload payload) {
        BY_TOWN.clear();
        payload.towns().forEach(entry ->
                BY_TOWN.put(UUID.fromString(entry.townId()), Set.copyOf(entry.ids())));
    }

    /** 该城镇的该 id 是否已解锁（客户端缓存口径，随快照更新）。 */
    public static boolean isUnlocked(UUID townId, String id) {
        Set<String> set = BY_TOWN.get(townId);
        return set != null && set.contains(id);
    }
}
