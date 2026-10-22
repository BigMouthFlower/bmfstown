package com.bmf.bmfstown.town;

import com.bmf.bmfstown.ModDataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 镇界魔杖——划分城镇/就餐区/住宅/工作区的唯一工具。
 *
 * <h2>交互总表</h2>
 * <table>
 *   <tr><th>操作</th><th>效果</th></tr>
 *   <tr><td>按住 Alt + 滚轮</td><td>切换模式（城镇/就餐区/住宅/工作区），选点清空</td></tr>
 *   <tr><td>右键方块</td><td>依次选第 1、第 2 个角；两点齐后再按则提示</td></tr>
 *   <tr><td>潜行 + 右键</td><td>确认创建（城镇模式建城镇；其余模式建子区域，须在城镇内）</td></tr>
 *   <tr><td>把魔杖换下主手</td><td>选点作废，重新拿起从头选</td></tr>
 * </table>
 *
 * <h2>为什么选点存"数据组件"而不是普通 NBT</h2>
 * 数据组件自动跨端同步（服务端 set，客户端立刻能读到做预览）、
 * 自动随物品进存档。见 {@link ModDataComponents}。
 *
 * <h2>为什么"离开主手"在 inventoryTick 里清</h2>
 * 没有"物品被切走"的专用事件。{@code inventoryTick} 每 tick 对背包里
 * 每一份该物品跑一次，带上"这份物品现在在哪个槽"的信息——
 * 检查"我是否还在主手"就等价于"玩家有没有把它切走/扔掉/放进箱子"。
 * 扔地上、放箱子同样触发（entity 不是 Player），行为一致：离开主手即作废。
 */
public class TownWandItem extends Item {

    public TownWandItem(Properties properties) {
        super(properties);
    }

    // ==========================================================
    //  选点与确认（右键）
    // ==========================================================

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        Level level = context.getLevel();
        ItemStack stack = context.getItemInHand();
        BlockPos pos = context.getClickedPos();

        // 潜行 + 右键 = 确认创建。
        // 注意这只是"兜底路径"：正常流程由客户端判定潜行后发
        // WandConfirmPayload（见 TownWandClient），因为服务端读潜行状态
        // 会与使用包竞速——潜行比点击晚到一瞬时，确认就被当成选点了。
        if (player.isShiftKeyDown()) {
            if (!level.isClientSide && player instanceof net.minecraft.server.level.ServerPlayer sp) {
                confirm(sp);
            }
            return InteractionResult.SUCCESS;
        }

        // 普通右键 = 选点。逻辑只在服务端跑，组件改动自动同步到客户端。
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        TownWandMode mode = getMode(stack);
        BlockPos a = stack.get(ModDataComponents.WAND_POINT_A.get());

        if (a == null) {
            // 还没有第一个点：这次点击定第一个角
            stack.set(ModDataComponents.WAND_POINT_A.get(), pos.immutable());
            stack.remove(ModDataComponents.WAND_POINT_B.get());
            player.displayClientMessage(Component.translatable(
                    "msg.bmfstown.wand.point1", modeDisplay(mode), fmt(pos)), true);
        } else if (stack.get(ModDataComponents.WAND_POINT_B.get()) == null) {
            if (pos.equals(a)) {
                player.displayClientMessage(Component.translatable(
                        "msg.bmfstown.wand.point_same"), true);
            } else {
                stack.set(ModDataComponents.WAND_POINT_B.get(), pos.immutable());
                // 选完立刻报尺寸：哪个轴是 1 一眼看出（薄片选区是床扫不到的头号原因）
                String size = (Math.abs(pos.getX() - a.getX()) + 1) + "x"
                        + (Math.abs(pos.getY() - a.getY()) + 1) + "x"
                        + (Math.abs(pos.getZ() - a.getZ()) + 1);
                player.displayClientMessage(Component.translatable(
                        "msg.bmfstown.wand.point2", modeDisplay(mode), fmt(pos), size), true);
            }
        } else {
            // 两点已齐：不覆盖（防手滑），提示玩家想重选就切走魔杖再回来
            player.displayClientMessage(Component.translatable(
                    "msg.bmfstown.wand.both_set"), true);
        }
        return InteractionResult.CONSUME;
    }

    /**
     * 确认创建（服务端执行）。
     *
     * <p>校验与存档都在 {@link TownManager}：城镇模式 → 画新城镇（不能与
     * 已有城镇重叠）；子区域模式 → 选区必须完整落在某城镇内。
     * 成功后清空选点并给客户端推手册数据（自动打开手册，可视化确认）。
     *
     * <p>两个点都没选时静默返回——按住右键会连发确认包，
     * 创建成功后的后续包不该刷"请先选点"。
     */
    public static void confirm(ServerPlayer player) {
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof TownWandItem)) {
            return; // 确认包到达时魔杖可能已切走
        }
        BlockPos a = stack.get(ModDataComponents.WAND_POINT_A.get());
        BlockPos b = stack.get(ModDataComponents.WAND_POINT_B.get());
        if (a == null && b == null) {
            return;
        }
        if (a == null || b == null) {
            player.displayClientMessage(Component.translatable(
                    "msg.bmfstown.wand.need_points"), true);
            return;
        }

        TownWandMode mode = getMode(stack);
        com.bmf.bmfstown.BmfTown.LOGGER.info(
                "[BmfTown][确认] 模式 = {}，第1点 = {}，第2点 = {}",
                mode, a.toShortString(), b.toShortString());
        if (mode == TownWandMode.TOWN) {
            TownManager.tryCreateTown(player, a, b);
        } else {
            TownManager.tryCreateZone(player, mode, a, b);
        }

        // 无论成功与否都清选点：失败（重叠/越界）也让玩家重新选，
        // 免得旧选点残留导致二次确认时困惑。
        stack.remove(ModDataComponents.WAND_POINT_A.get());
        stack.remove(ModDataComponents.WAND_POINT_B.get());
    }

    // ==========================================================
    //  离开主手 → 选点作废
    // ==========================================================

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity,
                              int slot, boolean selected) {
        super.inventoryTick(stack, level, entity, slot, selected);
        if (level.isClientSide) {
            return; // 服务端清一次，组件同步会带上客户端，不必两边清
        }
        boolean inMainHand = entity instanceof Player player
                && player.getMainHandItem() == stack;
        if (inMainHand) {
            return;
        }
        boolean hadSelection = stack.has(ModDataComponents.WAND_POINT_A.get());
        if (hadSelection) {
            stack.remove(ModDataComponents.WAND_POINT_A.get());
            stack.remove(ModDataComponents.WAND_POINT_B.get());
            if (entity instanceof Player player) {
                player.displayClientMessage(Component.translatable("msg.bmfstown.wand.invalidated"), true);
            }
        }
    }

    // ==========================================================
    //  工具方法与提示
    // ==========================================================

    /** 读模式；组件缺省视为 TOWN（0）。 */
    public static TownWandMode getMode(ItemStack stack) {
        Integer idx = stack.get(ModDataComponents.WAND_MODE.get());
        return TownWandMode.byIndex(idx == null ? 0 : idx);
    }

    /** 模式的本地化名（lang 文件），玩家可见文本一律走这里。 */
    public static Component modeDisplay(TownWandMode mode) {
        return Component.translatable(mode.displayName());
    }

    /** 坐标显示成 "x, y, z"，日志和聊天里都用这一种格式。 */
    public static String fmt(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        TownWandMode mode = getMode(stack);
        tooltip.add(Component.translatable("tip.bmfstown.wand.mode", modeDisplay(mode)));

        BlockPos a = stack.get(ModDataComponents.WAND_POINT_A.get());
        BlockPos b = stack.get(ModDataComponents.WAND_POINT_B.get());
        Component unselected = Component.translatable("tip.bmfstown.wand.unselected");
        tooltip.add(Component.translatable("tip.bmfstown.wand.point_a",
                a == null ? unselected : fmt(a)));
        tooltip.add(Component.translatable("tip.bmfstown.wand.point_b",
                b == null ? unselected : fmt(b)));
        tooltip.add(Component.translatable("tip.bmfstown.wand.hint_scroll"));
        tooltip.add(Component.translatable("tip.bmfstown.wand.hint_pick"));
        tooltip.add(Component.translatable("tip.bmfstown.wand.hint_confirm"));
        tooltip.add(Component.translatable("tip.bmfstown.wand.hint_discard"));
    }

    /** 魔杖不是工具，不参与挖掘加速之类的判定——保持默认即可。 */
    @Override
    public boolean canAttackBlock(BlockState state, Level level, BlockPos pos,
                                  Player player) {
        // 允许正常挖掘（选点走右键，不占用左键，玩家还能拆方块）
        return true;
    }
}
