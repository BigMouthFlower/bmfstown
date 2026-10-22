package com.bmf.bmfstown.network;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.ModDataComponents;
import com.bmf.bmfstown.town.TownManager;
import com.bmf.bmfstown.town.TownWandItem;
import com.bmf.bmfstown.town.TownWandMode;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 网络包注册与处理器。
 *
 * <h2>包一览</h2>
 * <table>
 *   <tr><th>方向</th><th>包</th><th>作用</th></tr>
 *   <tr><td>C → S</td><td>{@link WandModePayload}</td><td>Alt+滚轮切魔杖模式</td></tr>
 *   <tr><td>C → S</td><td>{@link HandbookRequestPayload}</td><td>手册打开时拉取最新数据</td></tr>
 *   <tr><td>C → S</td><td>{@link HandbookRenamePayload}</td><td>手册里改名城镇/区域</td></tr>
 *   <tr><td>C → S</td><td>{@link HandbookDeletePayload}</td><td>手册里删除城镇/区域</td></tr>
 *   <tr><td>S → C</td><td>{@link HandbookSyncPayload}</td><td>推全量城镇数据给手册</td></tr>
 * </table>
 *
 * <h2>为什么手册用"全量推送"而不是增量</h2>
 * 城镇数据量小（几十条顶天），全量推送实现最简单、不容易出状态不一致；
 * 哪怕将来城镇多了，再优化成按需查询也不迟。
 */
public final class ModNetworking {

    // ==========================================================
    //  C → S：切换魔杖模式
    // ==========================================================

    /** 客户端 → 服务端：把主手魔杖切到第 mode 个模式。 */
    public record WandModePayload(int mode) implements CustomPacketPayload {

        /** 包类型 ID。命名空间必须是本 mod id，否则注册时被拒。 */
        public static final Type<WandModePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "wand_mode"));

        /** 只有一个 int，用 VAR_INT 省字节。 */
        public static final StreamCodec<ByteBuf, WandModePayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, WandModePayload::mode,
                        WandModePayload::new);

        /**
         * 接口要求的"这个包是谁"。
         * record 组件名叫 mode，不会自动映射，必须手动指回 TYPE。
         */
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ==========================================================
    //  C → S：手册拉数据（空包，只有"我要刷新"这一个语义）
    // ==========================================================

    public record HandbookRequestPayload() implements CustomPacketPayload {

        public static final Type<HandbookRequestPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "handbook_request"));

        /** 空包用 unit：不读不写字段，反序列化固定返回同一个实例。 */
        public static final StreamCodec<ByteBuf, HandbookRequestPayload> STREAM_CODEC =
                StreamCodec.unit(new HandbookRequestPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ==========================================================
    //  C → S：改名 / 删除
    // ==========================================================

    /**
     * targetId 约定（与 {@link TownManager} 一致）：
     * "T:uuid" = 改城镇名，"Z:uuid" = 改区域名。
     */
    public record HandbookRenamePayload(String targetId, String newName)
            implements CustomPacketPayload {

        public static final Type<HandbookRenamePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "handbook_rename"));

        public static final StreamCodec<ByteBuf, HandbookRenamePayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, HandbookRenamePayload::targetId,
                        ByteBufCodecs.STRING_UTF8, HandbookRenamePayload::newName,
                        HandbookRenamePayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record HandbookDeletePayload(String targetId) implements CustomPacketPayload {

        public static final Type<HandbookDeletePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "handbook_delete"));

        public static final StreamCodec<ByteBuf, HandbookDeletePayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, HandbookDeletePayload::targetId,
                        HandbookDeletePayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ==========================================================
    //  S → C：手册数据同步
    // ==========================================================

    /**
     * 服务端 → 客户端：全量城镇数据（文本行格式，见
     * {@code TownSaveData#toBlob}）。
     *
     * @param open true = 同步完顺手打开手册（创建城镇/区域后用，
     *             玩家马上能可视化看到刚建的东西）
     */
    public record HandbookSyncPayload(String blob, boolean open) implements CustomPacketPayload {

        public static final Type<HandbookSyncPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "handbook_sync"));

        public static final StreamCodec<ByteBuf, HandbookSyncPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, HandbookSyncPayload::blob,
                        ByteBufCodecs.BOOL, HandbookSyncPayload::open,
                        HandbookSyncPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ==========================================================
    //  处理器
    // ==========================================================

    private static void handleWandMode(WandModePayload payload, IPayloadContext ctx) {
        // enqueueWork：把处理挪到主线程。改物品组件、发消息都不是线程安全的。
        ctx.enqueueWork(() -> {
            Player player = ctx.player();
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return; // 双保险：这个包理论上只该从服务端通道来
            }
            ItemStack held = serverPlayer.getMainHandItem();
            if (!(held.getItem() instanceof TownWandItem)) {
                return; // 手里没拿魔杖，包可能是延迟到的新旧包，直接忽略
            }

            TownWandMode newMode = TownWandMode.byIndex(payload.mode());
            int newIdx = newMode.ordinal();
            Integer curIdx = held.get(ModDataComponents.WAND_MODE.get());

            if (curIdx != null && curIdx == newIdx) {
                return; // 重复包（滚轮抖动），不刷消息
            }

            held.set(ModDataComponents.WAND_MODE.get(), newIdx);
            // 换模式 = 换区域类型，旧选点没有意义，清掉重新选
            held.remove(ModDataComponents.WAND_POINT_A.get());
            held.remove(ModDataComponents.WAND_POINT_B.get());

            serverPlayer.displayClientMessage(Component.translatable(
                    "msg.bmfstown.wand.mode_switch",
                    Component.translatable(newMode.displayName()),
                    newIdx + 1, TownWandMode.values().length), true);
        });
    }

    private static void handleHandbookRequest(HandbookRequestPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer serverPlayer) {
                sendHandbookSync(serverPlayer, false);
            }
        });
    }

    private static void handleHandbookRename(HandbookRenamePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer serverPlayer) {
                TownManager.rename(serverPlayer, payload.targetId(), payload.newName());
            }
        });
    }

    private static void handleHandbookDelete(HandbookDeletePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer serverPlayer) {
                TownManager.delete(serverPlayer, payload.targetId());
            }
        });
    }

    private static void handleHandbookSync(HandbookSyncPayload payload, IPayloadContext ctx) {
        // playToClient 的处理器在客户端跑；仍要 enqueueWork 挪到主线程，
        // 因为 setScreen / 更新 UI 必须在主线程做。
        ctx.enqueueWork(() ->
                com.bmf.bmfstown.client.TownHandbookClient.handleSync(payload));
    }

    // ==========================================================
    //  注册
    // ==========================================================

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        // "1" 是协议版本号；客户端与服务端不一致时拒绝连接，防串版本。
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(WandModePayload.TYPE, WandModePayload.STREAM_CODEC,
                ModNetworking::handleWandMode);
        registrar.playToServer(HandbookRequestPayload.TYPE, HandbookRequestPayload.STREAM_CODEC,
                ModNetworking::handleHandbookRequest);
        registrar.playToServer(HandbookRenamePayload.TYPE, HandbookRenamePayload.STREAM_CODEC,
                ModNetworking::handleHandbookRename);
        registrar.playToServer(HandbookDeletePayload.TYPE, HandbookDeletePayload.STREAM_CODEC,
                ModNetworking::handleHandbookDelete);
        registrar.playToClient(HandbookSyncPayload.TYPE, HandbookSyncPayload.STREAM_CODEC,
                ModNetworking::handleHandbookSync);
    }

    private ModNetworking() {
    }

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(ModNetworking::onRegisterPayloads);
    }

    // ==========================================================
    //  发送便捷方法
    // ==========================================================

    /** 客户端发任意包给服务端。客户端代码不要直接碰 PacketDistributor。 */
    public static void sendToServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    /** 服务端给某个玩家推一份手册数据（open=true 时客户端自动打开手册）。 */
    public static void sendHandbookSync(ServerPlayer player, boolean open) {
        String blob = com.bmf.bmfstown.town.TownSaveData
                .get(player.serverLevel()).toBlob();
        PacketDistributor.sendToPlayer(player, new HandbookSyncPayload(blob, open));
    }
}
