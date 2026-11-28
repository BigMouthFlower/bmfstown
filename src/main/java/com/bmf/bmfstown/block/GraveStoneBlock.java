package com.bmf.bmfstown.block;

import com.bmf.bmfstown.town.GraveyardData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * 墓碑方块（2026-11-28 决策 #143）。
 *
 * <h2>形状：占 1 格，但不是整块</h2>
 * <pre>
 *   俯视                正视（从 +Z 侧看）
 *   ┌────────────┐      ┌────────────┐  y=16 整格顶
 *   │  ┌──────┐  │      │            │
 *   │  │ 8×6  │  │      │  ┌──────┐  │  y=13 碑顶
 *   │  └──────┘  │      │  │      │  │
 *   │            │      │  └──────┘  │  y=2  底座顶
 *   └────────────┘      └────────────┘  y=0
 * </pre>
 * 底座 {@code (1,0,3)-(15,2,13)}、碑身 {@code (4,2,5)-(12,13,11)}（朝南时）。
 * 碑只有 13px 高（人 32px），所以从上方能跨过去，但撞不进碑身。
 *
 * <h2>贴图分工</h2>
 * 正面（{@code FACING} 那一侧）= {@code bmfstown:block/grave_face}（唯一手绘贴图，
 * 一道浅拱 + 刻痕区）；其余五面 = 石材本身。
 * 所以<b>8 种变体共用同一张碑面</b>，加第 9 种石头美术零成本。
 *
 * <h2>两个交互（2026-11-28 决策 #145 收成两个）</h2>
 * <ol>
 *   <li><b>放置</b>：放下就是一块<b>无名碑</b>，不写碑位表。</li>
 *   <li><b>右键（不分蹲/不蹲）</b>：
 *     <ul>
 *       <li>已刻字 ⇒ 打开碑文界面（{@code GraveEpitaphScreen}，2026-11-28 #146
 *           从聊天栏改成界面：碑文会长，聊天栏是流水账）；</li>
 *       <li>空碑 ⇒ 打开 {@code GravePickScreen}：上半待葬名单，点一行在<b>下半显示生平</b>，
 *           再点同一行刻上。</li>
 *     </ul>
 *     原先的"空手 + 潜行 = 刻最早一个"已删除：它返回 {@code SUCCESS} 会
 *     <b>堵死</b> {@code useWithoutItem}（原版先调 useItemOn），界面永远弹不出来。</li>
 * </ol>
 *
 * <h2>清位为什么挂 {@code onRemove}</h2>
 * {@code playerWillDestroy} <b>只认玩家挖</b>：爆炸、活塞推、{@code /setblock}
 * 全都不走它。表脏了就会出现"挖掉碑、重新放一块，结果顶着上一个人的名字"。
 * {@code onRemove} 是<b>方块真的消失</b>时触发（{@code BlockBehaviour.java:193}），
 * 这是唯一从最宽口子上堵住的地方（坑80）。
 */
public class GraveStoneBlock extends HorizontalDirectionalBlock {

    public static final EnumProperty<GraveStoneVariant> VARIANT = GraveStoneVariant.PROPERTY;
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    public static final com.mojang.serialization.MapCodec<GraveStoneBlock> CODEC =
            simpleCodec(GraveStoneBlock::new);

    // ---- 碰撞盒 ----
    //
    // 碑身是"薄板"，只在**正面方向**上薄；侧面看是宽 8px 的板。
    // 因为碑在两个轴上都对称，四个朝向**只有两种形状**：
    //   · 朝南 / 朝北：板沿 X 展开（宽 8），沿 Z 薄（厚 6，偏向前侧）
    //   · 朝东 / 朝西：板沿 Z 展开（宽 8），沿 X 薄（厚 6，偏向前侧）
    // 所以显式写两份，不用运行时旋转——反射式旋转 VoxelShape 的 API
    // 在 1.21.1 里不可靠（`Shapes` 没有公开 rotate），显式写反而更不会错。

    /** 底座（朝南/朝北）：x 1~15、y 0~2、z 3~13。 */
    private static final VoxelShape BASE_NS = Block.box(1, 0, 3, 15, 2, 13);
    /** 碑身（朝南/朝北）：x 4~12、y 2~13、z 5~11。 */
    private static final VoxelShape PILLAR_NS = Block.box(4, 2, 5, 12, 13, 11);
    /** 朝南/朝北的完整形状。 */
    private static final VoxelShape SHAPE_NS = Shapes.or(BASE_NS, PILLAR_NS);

    /** 底座（朝东/朝西）：x 3~13、y 0~2、z 1~15。 */
    private static final VoxelShape BASE_EW = Block.box(3, 0, 1, 13, 2, 15);
    /** 碑身（朝东/朝西）：x 5~11、y 2~13、z 4~12。 */
    private static final VoxelShape PILLAR_EW = Block.box(5, 2, 4, 11, 13, 12);
    /** 朝东/朝西的完整形状。 */
    private static final VoxelShape SHAPE_EW = Shapes.or(BASE_EW, PILLAR_EW);

    public GraveStoneBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(VARIANT, GraveStoneVariant.STONE_BRICKS));
    }

    @Override
    protected com.mojang.serialization.MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, VARIANT);
    }

    /**
     * 放置时朝向玩家——碑面朝人，不是背面朝人。
     *
     * <p>与原版告示牌同口径：{@code getOpposite()} 因为 FACING 表示"正面朝向"，
     * 而玩家站在自己那一侧看它。
     *
     * <p><b>材质这里拿不到</b>：{@code BlockItem} 的顺序是
     * `setBlock(默认state)` → `updateBlockStateFromTag(应用 block_state 组件)`
     * → `setPlacedBy`。所以放置瞬间只能是默认材质，
     * 组件会在 {@code setPlacedBy} 之前被原版自动 apply（已核 {@code BlockItem.java:78-82}）。
     * <b>不要在这里尝试读组件</b>——读了也没用，会被随后覆盖。
     */
    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
            CollisionContext context) {
        // 碑在两个轴上都对称 ⇒ 四个朝向只有两种形状（见文件头的常量注释）
        return switch (state.getValue(FACING)) {
            case EAST, WEST -> SHAPE_EW;
            default -> SHAPE_NS;
        };
    }

    // ==========================================================
    //  放置：认人
    // ==========================================================

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
            @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        // 没有"刻碑"这一步之前，放下就是一块无名碑。刻碑走蹲下右键。
    }

    // ==========================================================
    //  右键（只有一个行为）
    // ==========================================================

    /**
     * <b>不处理 {@code useItemOn}</b>——这里刻意留空。
     *
     * <p>曾经这里挂过"空手 + 潜行 + 右键 = 刻最早一个待葬的"（决策 #143 的旧路径）。
     * 它有两条独立的毛病，2026-11-28 用户实测后要求删除：
     * <ol>
     *   <li><b>把选人界面彻底堵死了</b>：原版 {@code ServerPlayerGameMode.useItemOn}
     *       (:325-337) 先调 {@code blockstate.useItemOn}，只有它返回
     *       {@code PASS_TO_DEFAULT_BLOCK_INTERACTION} 才会继续调 {@code useWithoutItem}。
     *       旧路径返回 {@code SUCCESS}（{@code consumesAction()==true}）⇒ 直接 return
     *       ⇒ 潜行右键<b>永远</b>走不到弹界面那条分支，{@code GravePickScreen} 成了死代码。</li>
     *   <li><b>多一个玩家必须记住的键位</b>：它想解决的"只有一个人待葬时两下完事"
     *       其实界面里点一下也是一下；而代价是玩家要记住 Shift。</li>
     * </ol>
     *
     * <p><b>状态本身就是键位</b>：碑上有没有字已经区分了"读"和"刻"，
     * 不需要再拿 Shift 区分一次。
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
            BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        // 手上有东西 ⇒ 照常让玩家放方块/用物品；空手 ⇒ 交给 useWithoutItem。
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    /**
     * 空手右键，行为<b>只看碑的状态</b>：
     * <ul>
     *   <li><b>已刻字</b> ⇒ 打开碑文界面（读）；</li>
     *   <li><b>空碑</b> ⇒ 打开选人界面：上半是待葬名单，点一行在<b>下半显示他的生平</b>，
     *       再点同一行才刻上（2026-11-28 用户定的口径）。</li>
     * </ul>
     *
     * <p>蹲/不蹲<b>没有区别</b>——这是 2026-11-28 用户明确要求的：
     * "为什么不是右键一定是潜行右键呢……右键打开一个界面，选择要刻碑的人，
     * 然后同时底下也会显示他的生平信息和墓志铭。难道不应该是这样吗？"
     *
     * <p>分开的理由不成立：原来怕"路过顺手看一眼被界面糊脸"，但<b>已刻字的碑
     * 根本不弹界面</b>，空碑本来就是"我要干点什么"的信号。
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level,
            BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level instanceof ServerLevel serverLevel)) {
            // 客户端这一侧只负责别抢原版交互；真正的判断在服务端做
            return InteractionResult.sidedSuccess(true);
        }
        GraveyardData data = GraveyardData.get(serverLevel);
        GraveyardData.Grave grave = data.graveAt(pos);
        if (grave != null) {
            if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                // 碑文走界面，不打聊天栏：碑文会长（用户 2026-11-28 明确要求），
                // 聊天栏是流水账、会被顶掉、滚走就看不回来。
                com.bmf.bmfstown.network.ModNetworking.sendEpitaph(
                        serverPlayer, pos, grave);
            }
            serverLevel.playSound(null, pos, SoundEvents.STONE_HIT,
                    SoundSource.BLOCKS, 0.6F, 1.4F);
            return InteractionResult.SUCCESS;
        }
        // 空碑 → 选人界面（名册空时界面自己显示"没有待葬的人"）
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            com.bmf.bmfstown.network.ModNetworking.sendGraveCandidates(
                    serverPlayer, pos, serverLevel);
            serverLevel.playSound(null, pos, SoundEvents.STONE_HIT,
                    SoundSource.BLOCKS, 0.6F, 1.6F);
        }
        return InteractionResult.SUCCESS;
    }

    // ==========================================================
    //  清位
    // ==========================================================

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos,
            BlockState newState, boolean movedByPiston) {
        // 只在"换成了别的方块"时清位；同方块换状态（比如 FACING 变了）不清。
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel) {
            GraveyardData.get(serverLevel).clearSlot(pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}