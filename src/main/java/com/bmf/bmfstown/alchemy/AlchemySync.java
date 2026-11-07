package com.bmf.bmfstown.alchemy;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.network.ModNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * 炼金目录同步触发器：登录 + 数据包重载（配方可能变了）时，
 * 把服务端反查的目录推给客户端（客户端 PotionBrewing 是 vanilla 实例，不可靠）。
 */
@EventBusSubscriber(modid = BmfTown.MOD_ID)
public final class AlchemySync {

    private AlchemySync() {
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ModNetworking.sendAlchemyCatalogSync(player);
        }
    }

    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        for (ServerPlayer player : event.getRelevantPlayers().toList()) {
            ModNetworking.sendAlchemyCatalogSync(player);
        }
    }
}
