package com.bmf.bmfstown.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端侧的嘲讽状态缓存：被嘲讽怪 UUID → 截止游戏时间。
 *
 * <p><b>为什么要有这个类</b>：{@code TauntManager} 的嘲讽表只在服务端内存里，
 * 客户端渲染层想知道"这只怪被嘲讽了吗"只能靠服务端告诉它——
 * 服务端施放嘲讽时广播 {@code TauntSyncPayload}，这里收下并按客户端
 * 游戏时间（与服务端同步）自动过期，不需要第二条"结束"包。
 */
public final class ClientTauntState {

    /** 被嘲讽怪 UUID → 截止游戏时间（客户端时间轴）。 */
    private static final Map<UUID, Long> UNTIL = new HashMap<>();

    private ClientTauntState() {
    }

    /** 收到服务端同步：登记（或刷新）一条嘲讽。 */
    public static void handleSync(UUID monsterId, long untilGameTime) {
        UNTIL.put(monsterId, untilGameTime);
        com.bmf.bmfstown.BmfTown.LOGGER.info(
                "[BmfTown][perk][客户端] 收到嘲讽同步：{} 直至 {}（当前游戏时间 {}）",
                monsterId, untilGameTime,
                Minecraft.getInstance().level == null ? "?" : Minecraft.getInstance().level.getGameTime());
    }

    /** 渲染层查询：这只怪现在是否被嘲讽着（顺带清过期条目）。 */
    public static boolean isTaunted(Entity entity) {
        if (UNTIL.isEmpty() || Minecraft.getInstance().level == null) {
            return false;
        }
        Long until = UNTIL.get(entity.getUUID());
        if (until == null) {
            return false;
        }
        if (Minecraft.getInstance().level.getGameTime() > until) {
            UNTIL.remove(entity.getUUID());
            return false;
        }
        return true;
    }

    /** 换世界/断线时清空（防串档）。 */
    public static void clear() {
        UNTIL.clear();
    }

    /** 兜底清理（游戏时间早于 now 的条目），低频调用即可。 */
    public static void sweep(long nowGameTime) {
        Iterator<Map.Entry<UUID, Long>> it = UNTIL.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() <= nowGameTime) {
                it.remove();
            }
        }
    }
}
