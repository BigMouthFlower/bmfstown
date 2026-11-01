package com.bmf.bmfstown.client;

import com.bmf.bmfstown.network.ModNetworking;
import net.minecraft.client.Minecraft;

/**
 * 客户端的昼夜流速镜像（v0.10）。
 *
 * <p>原理：ClientLevel 自带 Neo 的"可变昼夜"机制——`dayTimePerTick`
 * 浮点步长 + `dayTimeFraction` 小数桶，太阳/月亮动画按它平滑插值。
 * 服务端设置流速后把 perTick 镜像过来，客户端本地推进就与服务端
 * 同速率，太阳不再每秒跳变。
 */
public final class ClientTimeFlow {

    private ClientTimeFlow() {
    }

    /** 收到流速镜像（设置流速 / 玩家登录补发）。 */
    public static void handleSync(ModNetworking.TimeSpeedSyncPayload payload) {
        var level = Minecraft.getInstance().level;
        if (level != null) {
            level.setDayTimePerTick(payload.perTick());
        }
    }
}
