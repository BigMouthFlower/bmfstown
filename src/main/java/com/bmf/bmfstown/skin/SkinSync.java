package com.bmf.bmfstown.skin;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.network.ModNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * 皮肤系统服务端挂载（v0.15，同炼金目录 {@code AlchemySync} 的双触发模式）：
 * <ul>
 *   <li>{@link AddReloadListenerEvent} —— 把 {@link SkinCatalog} 挂进数据包重载链
 *       （服务端启动与每次 /reload 都会重建目录）；</li>
 *   <li>登录 + 数据包同步 —— 把目录推给客户端（渲染查贴图/slim 全靠这份缓存）。</li>
 * </ul>
 *
 * <p>为什么类无 {@code @EventBusSubscriber} 之外的注册动作：游戏总线事件
 * （登录/重载/数据包同步）全在 NeoForge.EVENT_BUS，注解即挂载，无静态字段
 * 需要提前初始化（pitfall：类没有 @SubscribeEvent 方法不能挂注解）。
 */
@EventBusSubscriber(modid = BmfTown.MOD_ID)
public final class SkinSync {

    private SkinSync() {
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new SkinCatalog());
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ModNetworking.sendSkinCatalogSync(player);
        }
    }

    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        for (ServerPlayer player : event.getRelevantPlayers().toList()) {
            ModNetworking.sendSkinCatalogSync(player);
        }
    }
}
