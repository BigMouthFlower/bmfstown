package com.bmf.bmfstown.block;

import com.bmf.bmfstown.calendar.TownCalendar;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;

/**
 * 挂历（v0.9.2）：镇民历的"实体显示设备"——挂在墙上，右键看今天几号。
 *
 * <p>设计定稿（用户）：日期不做常驻 HUD，做成世界里的物件——
 * 右键后在**物品栏上方那条**（actionbar）打一行
 * "镇民历 第N年M月D日 · 满月"，抬头看月亮对日期。
 *
 * <p>放置：复用原版 {@link FaceAttachedHorizontalDirectionalBlock} 骨架
 * （按钮/告示牌同款），地面 / 墙面 / 天花板三态放置与依附判定免费获得；
 * 支撑方块被拆时自动脱落（{@link #updateShape}）。
 */
public class WallCalendarBlock extends FaceAttachedHorizontalDirectionalBlock {

    public static final com.mojang.serialization.MapCodec<WallCalendarBlock> CODEC =
            simpleCodec(WallCalendarBlock::new);

    public WallCalendarBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(FACE, AttachFace.WALL));
    }

    @Override
    protected com.mojang.serialization.MapCodec<? extends FaceAttachedHorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, FACE);
    }

    // ------------------------------------------------------------
    //  形状：2 像素薄面板（贴墙 / 平铺地面 / 吸顶）
    // ------------------------------------------------------------

    private static final VoxelShape FLOOR_SHAPE = Block.box(0, 0, 0, 16, 2, 16);
    private static final VoxelShape CEILING_SHAPE = Block.box(0, 14, 0, 16, 16, 16);
    private static final VoxelShape NORTH_WALL_SHAPE = Block.box(0, 0, 0, 16, 16, 2);
    private static final VoxelShape SOUTH_WALL_SHAPE = Block.box(0, 0, 14, 16, 16, 16);
    private static final VoxelShape WEST_WALL_SHAPE = Block.box(0, 0, 0, 2, 16, 16);
    private static final VoxelShape EAST_WALL_SHAPE = Block.box(14, 0, 0, 16, 16, 16);

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
            CollisionContext context) {
        // 原版语义：WALL 的 FACING = 正面朝向（背离墙），面板贴在 FACING 的**反侧**
        return switch (state.getValue(FACE)) {
            case FLOOR -> FLOOR_SHAPE;
            case CEILING -> CEILING_SHAPE;
            case WALL -> switch (state.getValue(FACING)) {
                case NORTH -> SOUTH_WALL_SHAPE;
                case SOUTH -> NORTH_WALL_SHAPE;
                case WEST -> EAST_WALL_SHAPE;
                default -> WEST_WALL_SHAPE;
            };
        };
    }

    // ------------------------------------------------------------
    //  依附判定与脱落：基类 FaceAttachedHorizontalDirectionalBlock 已全部
    //  实现（canSurvive / updateShape / getConnectedDirection），无需子类插手。
    //  第一版在这里手写并把 WALL 的 FACING 语义理解反了 180°
    //  （判定贴墙、模型悬空），实测教训：先读基类再动手。
    // ------------------------------------------------------------

    // ------------------------------------------------------------
    //  交互：右键 → actionbar 报日期
    // ------------------------------------------------------------

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
            Player player, BlockHitResult hit) {
        if (level instanceof ServerLevel serverLevel) {
            TownCalendar.CalendarDate date = TownCalendar.get(serverLevel).today(serverLevel);
            player.displayClientMessage(Component.translatable(
                    "msg.bmfstown.calendar.today",
                    date.year(), date.month(), date.day(),
                    TownCalendar.moonPhaseName(date.moonPhase())), true);
            serverLevel.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN,
                    SoundSource.BLOCKS, 0.8F, 1.0F);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
