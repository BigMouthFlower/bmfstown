package com.bmf.bmfstown.town;

import com.bmf.bmfstown.BmfTown;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * 领地保护（v0.11.1）：城镇区域内的方块交互按**成员白名单**放行。
 *
 * <h2>语义（用户定稿）</h2>
 * <ul>
 *   <li>成员与创建者**完全同权**（子区域增删改/容器/门/破坏放置全放开）；</li>
 *   <li>非成员：容器、门、破坏、放置、魔杖操作全部拒绝 + 提示；</li>
 *   <li>解散城镇不归这里管（命令侧按 ownerId 校验）；</li>
 *   <li>**空成员表 = 保护未启用**（老城镇向后兼容，与今日行为一致），
 *       首次 {@code /bmfstown town add} 后自动启用；</li>
 *   <li>创造模式玩家绕过（房主/服主调试点）。</li>
 * </ul>
 *
 * <p> TownProtection 本身不含数据——判定全部走 {@link TownSaveData}。
 */
@EventBusSubscriber(modid = BmfTown.MOD_ID)
public final class TownProtection {

    private TownProtection() {
    }

    /** 方块位置所在城镇（维度匹配 + 外框/追加区块包含）；不在任何城镇返回 null。 */
    private static Town townAt(ServerLevel level, BlockPos pos) {
        String dim = level.dimension().location().toString();
        for (Town town : TownSaveData.get(level).allTowns()) {
            if (town.dimension().equals(dim) && town.contains(pos)) {
                return town;
            }
        }
        return null;
    }

    /** 统一判定：该玩家此位置能否交互。true = 放行；false = 已拒绝并发提示。 */
    private static boolean allow(Player player, ServerLevel level, BlockPos pos) {
        Town town = townAt(level, pos);
        if (town == null || !town.protectionEnabled() || town.isMember(player.getUUID())) {
            return true;
        }
        player.displayClientMessage(Component.translatable(
                "msg.bmfstown.town.not_member", town.name()), true);
        return false;
    }

    private static boolean bypass(Player player) {
        return player.isCreative();
    }

    // ------------------------------------------------------------
    //  破坏 / 放置
    // ------------------------------------------------------------

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !(event.getPlayer() instanceof ServerPlayer player)
                || bypass(player)) {
            return;
        }
        if (!allow(player, level, event.getPos())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !(event.getEntity() instanceof ServerPlayer player)
                || bypass(player)) {
            return;
        }
        if (!allow(player, level, event.getPos())) {
            event.setCanceled(true);
        }
    }

    // ------------------------------------------------------------
    //  右键：容器 / 门 / 魔杖
    // ------------------------------------------------------------

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || bypass(event.getEntity())) {
            return;
        }
        var state = event.getLevel().getBlockState(event.getPos());
        boolean isContainer = level.getBlockEntity(event.getPos()) instanceof Container;
        boolean isDoor = state.getBlock() instanceof DoorBlock
                || state.getBlock() instanceof TrapDoorBlock
                || state.getBlock() instanceof FenceGateBlock;
        boolean isWand = event.getItemStack().getItem() instanceof TownWandItem;
        if (!isContainer && !isDoor && !isWand) {
            return;
        }
        if (!allow(event.getEntity(), level, event.getPos())) {
            event.setCanceled(true);
        }
    }
}
