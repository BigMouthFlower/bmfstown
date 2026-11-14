package com.bmf.bmfstown.block;

import com.bmf.bmfstown.eerie.TownBulletinData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.InteractionResult;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 镇公告栏（v0.16 沉浸层，决策 #57）：表达层第三出口——镇民闲逛随机
 * 发现的非常规事件（恐怖线索/应验）在这里公示，玩家侧唯一的恐怖线
 * 进度窗口（与写书同源：发现了才公示、才写得到）。
 *
 * <p><b>占位实现（#57 拍板）</b>：整块简单方块 + 右键把最近
 * {@value #SHOW_COUNT} 条公告打进聊天栏；美化/面板 UI/尺寸后议——
 * 数据层 {@link TownBulletinData} 不随表现改。
 */
public class BulletinBoardBlock extends Block {

    /** 右键展示的最近条数。 */
    private static final int SHOW_COUNT = 10;

    public static final com.mojang.serialization.MapCodec<BulletinBoardBlock> CODEC =
            simpleCodec(BulletinBoardBlock::new);

    public BulletinBoardBlock(Properties properties) {
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
            UUID townId = resolveTown(serverLevel, pos);
            if (townId == null) {
                player.displayClientMessage(
                        Component.translatable("bulletin.bmfstown.no_town"), false);
            } else if (player.isShiftKeyDown()) {
                // 潜行右键 = 本镇"过客刷新"开关（决策 #61；普通右键仍是看公告）
                var switches = com.bmf.bmfstown.town.WandererState.get(serverLevel);
                boolean enabled = !switches.isEnabled(townId);
                switches.setEnabled(townId, enabled);
                player.displayClientMessage(Component.translatable(
                        enabled ? "msg.bmfstown.wanderers.toggle_on"
                                : "msg.bmfstown.wanderers.toggle_off"), false);
            } else {
                var entries = TownBulletinData.get(serverLevel).latest(townId, SHOW_COUNT);
                player.displayClientMessage(
                        Component.translatable("bulletin.bmfstown.header"), false);
                if (entries.isEmpty()) {
                    player.displayClientMessage(
                            Component.translatable("bulletin.bmfstown.empty"), false);
                } else {
                    for (var entry : entries) {
                        // 第N日 + 时段 + 事件（各自语言渲染）
                        player.displayClientMessage(Component.translatable(
                                        "bulletin.bmfstown.entry", entry.day(), entry.display()),
                                false);
                    }
                }
            }
            serverLevel.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN,
                    SoundSource.BLOCKS, 0.8F, 1.0F);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * 公告栏所在镇：以方块位置反查领地（登记制）。
     * 未划领地的公告栏只报"无归属"——公告是城镇级数据。
     */
    @Nullable
    private static UUID resolveTown(ServerLevel level, BlockPos pos) {
        var data = com.bmf.bmfstown.town.TownSaveData.get(level);
        var town = data.findOverlapping(level.dimension().location().toString(),
                pos, pos);
        return town == null ? null : town.id();
    }
}
