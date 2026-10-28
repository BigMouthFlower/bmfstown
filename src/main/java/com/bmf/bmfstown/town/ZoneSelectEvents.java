package com.bmf.bmfstown.town;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.network.ModNetworking;
import net.minecraft.core.BlockPos;

import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * 空手选区的事件接线（v0.9.1 不连续选区）。
 *
 * <h2>交互表（会话激活时）</h2>
 * <table>
 *   <tr><th>操作</th><th>效果</th></tr>
 *   <tr><td>手持手册右键方块</td><td>依次选第 1、第 2 个角；两点齐 → 立即创建追加区块</td></tr>
 *   <tr><td>收起手册（主手换成别的/空手）</td><td><b>取消选区</b>（与魔杖"离开主手即作废"同款，用户定稿）</td></tr>
 * </table>
 *
 * <h2>为什么两点齐就立即创建</h2>
 * 魔杖走客户端确认包是因为服务端读潜行与使用包竞速；
 * 手册选区把确认并入"第二个点"——按钮本身就是明确意图，不存在手滑确认问题。
 * 取消与魔杖同款："收起手册即作废"（{@link #onPlayerTick}），没有潜行竞态。
 *
 * <h2>为什么要求空手</h2>
 * 会话激活时会拦截右键（取消事件）——如果手里拿着方块/食物不拦截，
 * 一次右键既是"选点"又是"放置"就乱了。空手时原版右键最多打开容器，
 * 拦截掉毫无副作用；手里有东西则整个会话静默让路。
 */
@EventBusSubscriber(modid = BmfTown.MOD_ID)
public final class ZoneSelectEvents {

    private ZoneSelectEvents() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return; // 只认主手，副手点选不算
        }
        Player player = event.getEntity();
        UUID playerId = player.getUUID();
        if (!ZoneSelectSessions.active(playerId)) {
            return;
        }
        // 会话激活且**主手拿着城镇手册**才拦截——手册就是选区工具（用户定稿）
        if (!(player.getMainHandItem().getItem() instanceof TownHandbookItem)) {
            player.displayClientMessage(Component.translatable(
                    "msg.bmfstown.zoneblock.need_handbook"), true);
            return; // 不拦截：手里的东西正常用
        }
        // 会话激活：这一下右键只属于选区（拦截后手册不会打开屏幕）
        event.setCanceled(true);

        BlockPos pos = event.getPos();
        ZoneSelectSessions.Session session = ZoneSelectSessions.pick(playerId, pos);
        if (session == null) {
            return; // 理论不可达（active 已判）
        }
        if (session.second() == null) {
            // 还差一个点：报第 1 点
            player.displayClientMessage(Component.translatable(
                    "msg.bmfstown.zoneblock.point1",
                    TownWandItem.fmt(pos)), true);
            return;
        }

        // 两点齐 → 立即创建（会话由 TownManager 结束）
        if (player instanceof ServerPlayer serverPlayer) {
            TownManager.confirmAppendBlock(serverPlayer, session.townId(),
                    session.first(), session.second());
        }
    }

    /** 登出清会话（会话本来就只存内存，这里只是显式收尸）。 */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        ZoneSelectSessions.cancel(event.getEntity().getUUID());
    }

    /**
     * 收起手册即取消（用户定稿，与魔杖"离开主手即作废"同款）：
     * 会话激活期间每 tick 检查主手——不再是城镇手册就取消并提示。
     */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity().level().isClientSide()
                || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        UUID playerId = player.getUUID();
        if (!ZoneSelectSessions.active(playerId)) {
            return;
        }
        if (player.getMainHandItem().getItem() instanceof TownHandbookItem) {
            return; // 手册还在主手：选区继续
        }
        ZoneSelectSessions.cancel(playerId);
        ModNetworking.sendZoneSelectSync(player, false);
        player.displayClientMessage(Component.translatable(
                "msg.bmfstown.zoneblock.canceled"), true);
    }
}
