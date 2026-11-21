package com.bmf.bmfstown.block;

import com.bmf.bmfstown.network.ModNetworking;
import com.bmf.bmfstown.town.Town;
import com.bmf.bmfstown.town.TownSaveData;
import com.bmf.bmfstown.town.TownWandMode;
import com.bmf.bmfstown.town.TownZone;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.InteractionResult;
import net.minecraft.core.registries.BuiltInRegistries;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 酒吧菜单板（决策 #86 升格，2026-11-21 用户拍板）：菜单是<b>酒馆的资产</b>，
 * 不绑定酒保实体——板放在酒吧里，右键打开菜单编辑 UI，酒保照旧读镇级
 * {@code Town.tavernMenu}，板只是视图与编辑入口。
 *
 * <p><b>交互分流</b>（公告栏 {@link BulletinBoardBlock} 同构骨架）：
 * <ul>
 *   <li>板在<b>酒馆区</b>内 → 打开编辑 UI（城镇成员可编辑，非成员只读）；</li>
 *   <li>板在<b>区外</b> → 只读展示（聊天栏打印菜单，公告栏同款）；</li>
 *   <li>板被拆菜单不丢（数据在镇级）；同镇多块板共享同一菜单。</li>
 * </ul>
 *
 * <p>无 BlockEntity——菜单数据全部在 {@link TownSaveData} 存档层（设计稿 §1）。
 * 口径提醒：菜单未配置=<b>不维护</b>（#108 拍板：有什么上什么，不自行开新批次）。
 */
public class TavernMenuBoardBlock extends Block {

    public static final com.mojang.serialization.MapCodec<TavernMenuBoardBlock> CODEC =
            simpleCodec(TavernMenuBoardBlock::new);

    public TavernMenuBoardBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected com.mojang.serialization.MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
            Player player, BlockHitResult hit) {
        if (level instanceof ServerLevel serverLevel) {
            Town town = resolveTown(serverLevel, pos);
            if (town == null) {
                player.displayClientMessage(
                        Component.translatable("msg.bmfstown.menu_board.no_town"), false);
            } else if (inTavernZone(town, pos)) {
                boolean member = player instanceof ServerPlayer sp && town.isMember(sp.getUUID());
                // 区内：发快照开 UI（成员=可编辑；非成员=只读）
                ModNetworking.sendTavernMenuSnapshot((ServerPlayer) player, town, pos, member, true);
            } else {
                // 区外：只读打印（公告栏同款聊天栏展示）
                printMenu(player, town, serverLevel, pos);
            }
            serverLevel.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN,
                    SoundSource.BLOCKS, 0.8F, 1.0F);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** 板所在镇：以方块位置反查领地（公告栏同款登记制口径）。 */
    @Nullable
    public static Town resolveTown(ServerLevel level, BlockPos pos) {
        return TownSaveData.get(level).findOverlapping(
                level.dimension().location().toString(), pos, pos);
    }

    /** 板是否在本镇任一酒馆区内（区内才给编辑 UI，区外只读——设计稿 §1）。 */
    public static boolean inTavernZone(Town town, BlockPos pos) {
        for (TownZone zone : town.zones(TownWandMode.TAVERN)) {
            if (zone.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    /** 区外只读展示：菜单逐行打进聊天栏（库存不给——区外没有酒窖上下文）。 */
    private static void printMenu(net.minecraft.world.entity.player.Player player,
            Town town, ServerLevel level, BlockPos pos) {
        player.displayClientMessage(Component.translatable(
                "msg.bmfstown.menu_board.header", town.name()), false);
        Map<Item, Integer> menu = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : town.tavernMenu().entrySet()) {
            ResourceLocation rl = ResourceLocation.tryParse(e.getKey());
            Item it = rl == null ? Items.AIR : BuiltInRegistries.ITEM.get(rl);
            if (it != Items.AIR) {
                menu.put(it, e.getValue());
            }
        }
        if (menu.isEmpty()) {
            player.displayClientMessage(
                    Component.translatable("msg.bmfstown.menu_board.empty"), false);
            return;
        }
        for (Map.Entry<Item, Integer> e : menu.entrySet()) {
            player.displayClientMessage(Component.translatable(
                    "msg.bmfstown.menu_board.entry",
                    new net.minecraft.world.item.ItemStack(e.getKey()).getHoverName(),
                    e.getValue()), false);
        }
        player.displayClientMessage(
                Component.translatable("msg.bmfstown.menu_board.outside_hint"), false);
    }
}
