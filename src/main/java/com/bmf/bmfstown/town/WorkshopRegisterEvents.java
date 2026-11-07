package com.bmf.bmfstown.town;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.network.ModNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 工坊登记的事件接线（v0.12 工人重做）。
 *
 * <h2>交互表（登记会话激活时）</h2>
 * <table>
 *   <tr><th>操作</th><th>效果</th></tr>
 *   <tr><td>手持手册右键容器</td><td>登记/取消登记为<b>仓储</b>（大箱子两半一起登记）</td></tr>
 *   <tr><td>手持手册右键工作台</td><td>登记/取消登记为<b>岗位</b></td></tr>
 *   <tr><td>手持手册右键其它方块</td><td>提示"不用登记"，会话保持</td></tr>
 *   <tr><td>收起手册</td><td><b>结束登记</b>（与选区会话同款）</td></tr>
 * </table>
 *
 * <h2>为什么登记动作只认手册</h2>
 * 登记入口从手册发起（工坊区详情页按钮），手册就是"城镇管理态"的实体——
 * 和选区会话（右键选点必须手持手册）同一套心智，不引入第三个工具。
 */
@EventBusSubscriber(modid = BmfTown.MOD_ID)
public final class WorkshopRegisterEvents {

    private WorkshopRegisterEvents() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        // 双侧都要取消：酿造台 GUI 是客户端预测打开的（client 返回 SUCCESS 本地开屏），
        // 只取消服务端拦不住它（首测实测踩坑）
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        Player player = event.getEntity();
        RegisterSessions.Session session = RegisterSessions.get(player.getUUID());
        if (session == null) {
            return;
        }
        // 会话激活且主手拿着城镇手册才拦截（与选区会话同口径）
        if (!(player.getMainHandItem().getItem() instanceof TownHandbookItem)) {
            if (!event.getLevel().isClientSide()) {
                player.displayClientMessage(Component.translatable(
                        "msg.bmfstown.register.need_handbook"), true);
            }
            return; // 不拦截：手里的东西正常用
        }
        // 会话激活：这一下右键只属于登记（客户端取消 = 阻止 GUI 预测）
        event.setCanceled(true);

        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        var town = TownSaveData.get(serverPlayer.serverLevel()).findTown(session.townId().toString());
        if (town == null) {
            endSession(serverPlayer, false);
            return;
        }
        BlockPos pos = event.getPos().immutable();
        // 登记范围 = 城镇区域（主外框或任一追加区块）——镇外的东西不归镇民管
        if (!town.contains(pos)) {
            player.displayClientMessage(Component.translatable(
                    "msg.bmfstown.register.outside"), true);
            return;
        }
        Town.WorkshopRegister register = town.workshop();
        BlockState state = serverPlayer.serverLevel().getBlockState(pos);

        // 岗位判定必须在容器之前：酿造台本身是 Container，先判容器会被吞进
        // 容器池，酿造台岗位池永远空、炼金术士认领不到台（2026-11-06 实测踩坑）
        if (toggleWaterSource(serverPlayer, register, pos, state, event.getFace())
                || toggleStation(serverPlayer, register, pos, state)
                || toggleContainer(serverPlayer, register, pos, state)) {
            TownSaveData.get(serverPlayer.serverLevel()).setDirty();
            RegisterSessions.touch(player.getUUID());
            // 推手册数据：客户端渲染小框要读登记表（手册没开着也只更新缓存）
            ModNetworking.sendHandbookSync(serverPlayer, false);
        } else {
            player.displayClientMessage(Component.translatable(
                    "msg.bmfstown.register.invalid"), true);
        }
    }

    /** 容器登记/取消。命中返回 true（提示已发）。 */
    private static boolean toggleContainer(ServerPlayer player,
            Town.WorkshopRegister register, BlockPos pos, BlockState state) {
        if (!(player.serverLevel().getBlockEntity(pos) instanceof Container)) {
            return false;
        }
        boolean removed = register.containers.remove(pos.asLong());
        BlockPos other = linkedChestHalf(player, pos, state);
        if (removed) {
            if (other != null) {
                register.containers.remove(other.asLong()); // 成对登记成对取消
            }
            player.displayClientMessage(Component.translatable(
                    "msg.bmfstown.register.container_off", pos.toShortString()), true);
            return true;
        }
        register.containers.add(pos.asLong());
        if (other != null) {
            register.containers.add(other.asLong()); // 大箱子 = 两半一起登记
        }
        player.displayClientMessage(Component.translatable(
                "msg.bmfstown.register.container_on", pos.toShortString()), true);
        return true;
    }

    /**
     * 大箱另一半（确定是双箱才返回，否则 null）。
     *
     * <p>坑（反编译实锤）：{@code ChestBlock.getConnectedDirection} 对**单箱**
     * 不返回 null，而是返回朝向的逆时针方向——所以"link != null"完全不能当
     * 大箱判据，必须三重验证：本方 TYPE 非 SINGLE + 邻居是大箱 + 邻居指回来。
     */
    @Nullable
    private static BlockPos linkedChestHalf(ServerPlayer player, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof ChestBlock)
                || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return null;
        }
        Direction toOther = ChestBlock.getConnectedDirection(state);
        BlockPos other = pos.relative(toOther);
        BlockState otherState = player.serverLevel().getBlockState(other);
        if (!(otherState.getBlock() instanceof ChestBlock)
                || otherState.getValue(ChestBlock.TYPE) == ChestType.SINGLE
                || ChestBlock.getConnectedDirection(otherState) != toOther.getOpposite()) {
            return null; // 邻居不是箱/是单箱/没指回来：都不是真双箱
        }
        return other;
    }

    /**
     * 水源站登记/取消（v0.12.1 炼金补水，决策 #34）。三种有效形态：
     * 含水炼药锅（右键锅本体）/ 含水方块（右键本体）/ 水源方块（原版射线
     * 穿透流体点不到水面——右键水源**贴脸的实心面**，登记相邻的那个水源格）。
     * 命中返回 true。位置有效性在镇民取水时实时校验，登记不锁方块状态。
     */
    private static boolean toggleWaterSource(ServerPlayer player,
            Town.WorkshopRegister register, BlockPos pos, BlockState state,
            @Nullable Direction face) {
        // 容器优先：箱子也能被含水——含水箱子按容器登记，不当水源站
        if (player.serverLevel().getBlockEntity(pos) instanceof Container) {
            return false;
        }
        BlockPos target = null;
        if (isWaterStationState(state)) {
            target = pos; // 锅/含水方块/（少见但允许的）水源本体
        } else if (face != null) {
            BlockPos adj = pos.relative(face);
            if (isWaterStationState(player.serverLevel().getBlockState(adj))) {
                target = adj; // 点水源旁边的实心面 → 登记贴脸的水
            }
        }
        if (target == null) {
            return false;
        }
        if (register.waterSources.remove(target.asLong())) {
            player.displayClientMessage(Component.translatable(
                    "msg.bmfstown.register.water_off", target.toShortString()), true);
        } else {
            register.waterSources.add(target.asLong());
            player.displayClientMessage(Component.translatable(
                    "msg.bmfstown.register.water_on", target.toShortString()), true);
        }
        return true;
    }

    /** 该方块状态是否可作水源站：含水炼药锅 / 水源 / 含水方块。 */
    private static boolean isWaterStationState(BlockState state) {
        if (state.is(Blocks.WATER_CAULDRON)) {
            return true;
        }
        if (state.is(Blocks.WATER) && state.getFluidState().isSource()) {
            return true;
        }
        return state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
                && state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED);
    }

    /** 岗位登记/取消，按方块类型分流（v0.12 炼金扩展）：工作台 → 工人/烟火商岗位池，
     * 酿造台 → 炼金术士岗位池。命中返回 true。
     */
    private static boolean toggleStation(ServerPlayer player,
            Town.WorkshopRegister register, BlockPos pos, BlockState state) {
        if (state.is(Blocks.CRAFTING_TABLE)) {
            if (register.stations.remove(pos.asLong())) {
                player.displayClientMessage(Component.translatable(
                        "msg.bmfstown.register.station_off", pos.toShortString()), true);
            } else {
                register.stations.add(pos.asLong());
                player.displayClientMessage(Component.translatable(
                        "msg.bmfstown.register.station_on", pos.toShortString()), true);
            }
            return true;
        }
        if (state.is(Blocks.BREWING_STAND)) {
            if (register.brewStations.remove(pos.asLong())) {
                player.displayClientMessage(Component.translatable(
                        "msg.bmfstown.register.brew_off", pos.toShortString()), true);
            } else {
                register.brewStations.add(pos.asLong());
                register.containers.remove(pos.asLong()); // 清掉误登进容器池的旧登记
                player.displayClientMessage(Component.translatable(
                        "msg.bmfstown.register.brew_on", pos.toShortString()), true);
            }
            return true;
        }
        return false;
    }

    /** 结束会话并同步客户端（正常结束/异常兜底共用）。 */
    private static void endSession(ServerPlayer player, boolean notify) {
        RegisterSessions.cancel(player.getUUID());
        ModNetworking.sendRegisterSessionSync(player, false, "");
        if (notify) {
            player.displayClientMessage(Component.translatable(
                    "msg.bmfstown.register.canceled"), true);
        }
    }

    /** 登出清会话（会话是瞬态，这里只是显式收尸）。 */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        RegisterSessions.cancel(event.getEntity().getUUID());
    }

    /**
     * 收起手册即结束登记（用户定稿，与选区会话同款）：
     * 会话激活期间每 tick 检查主手——不再是城镇手册就结束。
     */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity().level().isClientSide()
                || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        UUID playerId = player.getUUID();
        if (!RegisterSessions.active(playerId)) {
            return;
        }
        if (player.getMainHandItem().getItem() instanceof TownHandbookItem) {
            return; // 手册还在主手：登记继续
        }
        // 超时静默失效的场合不用刷提示——sendRegisterSessionSync 无论如何要发
        endSession(player, RegisterSessions.cancel(playerId));
    }
}
