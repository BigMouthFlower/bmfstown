package com.bmf.bmfstown.network;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.ModDataComponents;
import com.bmf.bmfstown.town.TownManager;
import com.bmf.bmfstown.town.TownWandItem;
import com.bmf.bmfstown.town.TownWandMode;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
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
    //  C → S：魔杖选点 / 确认（客户端权威，见 TownWandClient）
    // ==========================================================

    /**
     * 客户端 → 服务端：选角点。index 0 = 第 1 点（A），1 = 第 2 点（B）。
     *
     * <p>为什么坐标由客户端提供：潜行状态、准星命中都是客户端本地信息，
     * 走服务端 useOn 判定会出现两类真实事故——潜行包和使用包同 tick 竞速
     * 导致"确认点击被当成选点"；高处方块超服务端判定距离被静默拒收。
     * 客户端发指令、服务端只执行，所见即所得。
     */
    public record WandPointPayload(int index, BlockPos pos) implements CustomPacketPayload {

        public static final Type<WandPointPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "wand_point"));

        public static final StreamCodec<ByteBuf, WandPointPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, WandPointPayload::index,
                        BlockPos.STREAM_CODEC, WandPointPayload::pos,
                        WandPointPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 客户端 → 服务端：确认创建（原"潜行+右键"，由客户端判定潜行后发包）。 */
    public record WandConfirmPayload() implements CustomPacketPayload {

        public static final Type<WandConfirmPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "wand_confirm"));

        public static final StreamCodec<ByteBuf, WandConfirmPayload> STREAM_CODEC =
                StreamCodec.unit(new WandConfirmPayload());

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

    /** C → S：手册城镇页"增加选区"按钮——开启（或重开）空手选区会话。 */
    public record HandbookAppendZonePayload(String townId) implements CustomPacketPayload {

        public static final Type<HandbookAppendZonePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "handbook_append_zone"));

        public static final StreamCodec<ByteBuf, HandbookAppendZonePayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, HandbookAppendZonePayload::townId,
                        HandbookAppendZonePayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S → C：空手选区会话状态（客户端据此拦截手册右键的"打开屏幕"）。 */
    public record ZoneSelectSyncPayload(boolean active) implements CustomPacketPayload {

        public static final Type<ZoneSelectSyncPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "zone_select_sync"));

        public static final StreamCodec<ByteBuf, ZoneSelectSyncPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL, ZoneSelectSyncPayload::active,
                        ZoneSelectSyncPayload::new);

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
    //  C → S：站岗位置确认（工作页"站岗"按钮 → 红色预览 → 右键确认）
    // ==========================================================

    /**
     * @param entityId 镇民实体 id（客户端从 ResidentMenu.getResident().getId() 拿）
     * @param pos      玩家右键确认的岗哨方块（服务端会复核职业后才落账）
     */
    public record WorkGuardPostPayload(int entityId, BlockPos pos)
            implements CustomPacketPayload {

        public static final Type<WorkGuardPostPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "work_guard_post"));

        public static final StreamCodec<ByteBuf, WorkGuardPostPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, WorkGuardPostPayload::entityId,
                        BlockPos.STREAM_CODEC, WorkGuardPostPayload::pos,
                        WorkGuardPostPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * C → S：跟随开关（工作页"跟随"按钮）。与服务端 toggle 幂等，
     * 走和站岗确认同一条自定义包链路（菜单按钮链路留作兜底）。
     */
    public record WorkFollowPayload(int entityId) implements CustomPacketPayload {

        public static final Type<WorkFollowPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "work_follow"));

        public static final StreamCodec<ByteBuf, WorkFollowPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, WorkFollowPayload::entityId,
                        WorkFollowPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * C → S：工人工坊绑定确认（三步选取完成，一次带齐三件套）。
     * 服务端复核职业=工人、合成台方块无误后才落账。
     */
    public record WorkshopBindPayload(int entityId, BlockPos table, BlockPos input,
                                      BlockPos output) implements CustomPacketPayload {

        public static final Type<WorkshopBindPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "workshop_bind"));

        public static final StreamCodec<ByteBuf, WorkshopBindPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, WorkshopBindPayload::entityId,
                        BlockPos.STREAM_CODEC, WorkshopBindPayload::table,
                        BlockPos.STREAM_CODEC, WorkshopBindPayload::input,
                        BlockPos.STREAM_CODEC, WorkshopBindPayload::output,
                        WorkshopBindPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * C → S：从城镇手册居民列表打开镇民面板。
     * 不走交互锁（用户定稿：NPC 该干啥干啥），面板照常打开；
     * fromHandbook=true → 镇民面板关闭时回跳城镇手册。
     */
    public record OpenResidentFromHandbookPayload(int entityId, int reopenMode)
            implements CustomPacketPayload {

        public static final Type<OpenResidentFromHandbookPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "open_resident_handbook"));

        public static final StreamCodec<ByteBuf, OpenResidentFromHandbookPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, OpenResidentFromHandbookPayload::entityId,
                        ByteBufCodecs.VAR_INT, OpenResidentFromHandbookPayload::reopenMode,
                        OpenResidentFromHandbookPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * C → S：GATTACA 伪装确认（观察者在自己列表里查看某人 UI 后点"伪装"）。
     * 服务端复制对方全部数据（想法保留、特性一并复制生效），一天后自动复原。
     */
    public record GattacaMimicPayload(int observerId, int targetId)
            implements CustomPacketPayload {

        public static final Type<GattacaMimicPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "gattaca_mimic"));

        public static final StreamCodec<ByteBuf, GattacaMimicPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, GattacaMimicPayload::observerId,
                        ByteBufCodecs.VAR_INT, GattacaMimicPayload::targetId,
                        GattacaMimicPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * C → S：把玩家传送到目标镇民身边（资料页"传送"按钮）。
     */
    public record TeleportToResidentPayload(int entityId)
            implements CustomPacketPayload {

        public static final Type<TeleportToResidentPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "teleport_resident"));

        public static final StreamCodec<ByteBuf, TeleportToResidentPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, TeleportToResidentPayload::entityId,
                        TeleportToResidentPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * C → S：重命名镇民（资料页"重命名"按钮 → 输入屏回车）。
     * 服务端复核长度（1~32，对齐原版命名牌口径）后 setCustomName（自动入 NBT）。
     */
    public record ResidentRenamePayload(int entityId, String name)
            implements CustomPacketPayload {

        public static final Type<ResidentRenamePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "resident_rename"));

        public static final StreamCodec<ByteBuf, ResidentRenamePayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, ResidentRenamePayload::entityId,
                        ByteBufCodecs.STRING_UTF8, ResidentRenamePayload::name,
                        ResidentRenamePayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * C → S：工人开工/停工切换（服务端翻转开关并回执）。
     */
    public record WorkerTogglePayload(int entityId) implements CustomPacketPayload {

        public static final Type<WorkerTogglePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "worker_toggle"));

        public static final StreamCodec<ByteBuf, WorkerTogglePayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, WorkerTogglePayload::entityId,
                        WorkerTogglePayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * C → S：簿记员请求城镇物资统计（面板按钮）。
     * 服务端：有新鲜数据 → 直接回 CensusDataPayload；没有 → 委托扫描并提示稍候。
     */
    public record CensusRequestPayload(int entityId) implements CustomPacketPayload {

        public static final Type<CensusRequestPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "census_request"));

        public static final StreamCodec<ByteBuf, CensusRequestPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, CensusRequestPayload::entityId,
                        CensusRequestPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * S → C：城镇物资统计数据（打开选择界面用）。
     * entries = (产物注册名, 总数)；精度（有/无、数量级、精确）由客户端按簿记员段位渲染。
     */
    public record CensusEntryPayload(String item, int count) {

        public static final StreamCodec<ByteBuf, CensusEntryPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, CensusEntryPayload::item,
                        ByteBufCodecs.VAR_INT, CensusEntryPayload::count,
                        CensusEntryPayload::new);
    }

    public record CensusDataPayload(int entityId, String townName, int precision,
            java.util.List<CensusEntryPayload> entries) implements CustomPacketPayload {

        public static final Type<CensusDataPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "census_data"));

        public static final StreamCodec<ByteBuf, CensusDataPayload> STREAM_CODEC = StreamCodec.of(
                (buf, payload) -> {
                    ByteBufCodecs.VAR_INT.encode(buf, payload.entityId());
                    ByteBufCodecs.STRING_UTF8.encode(buf, payload.townName());
                    ByteBufCodecs.VAR_INT.encode(buf, payload.precision());
                    ByteBufCodecs.VAR_INT.encode(buf, payload.entries().size());
                    for (CensusEntryPayload e : payload.entries()) {
                        CensusEntryPayload.STREAM_CODEC.encode(buf, e);
                    }
                },
                buf -> {
                    int entityId = ByteBufCodecs.VAR_INT.decode(buf);
                    String townName = ByteBufCodecs.STRING_UTF8.decode(buf);
                    int precision = ByteBufCodecs.VAR_INT.decode(buf);
                    int size = ByteBufCodecs.VAR_INT.decode(buf);
                    java.util.List<CensusEntryPayload> list = new java.util.ArrayList<>(size);
                    for (int i = 0; i < size; i++) {
                        list.add(CensusEntryPayload.STREAM_CODEC.decode(buf));
                    }
                    return new CensusDataPayload(entityId, townName, precision,
                            java.util.List.copyOf(list));
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * C → S：簿记员寻物（玩家在选择界面点了某物品）。
     * 服务端找"最近的装着该物品的容器"，簿记员带路过去并撒粒子。
     */
    public record BookkeeperFindPayload(int entityId, String item) implements CustomPacketPayload {

        public static final Type<BookkeeperFindPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "bookkeeper_find"));

        public static final StreamCodec<ByteBuf, BookkeeperFindPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, BookkeeperFindPayload::entityId,
                        ByteBufCodecs.STRING_UTF8, BookkeeperFindPayload::item,
                        BookkeeperFindPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * C → S：工人订单切换（工坊配方目录索引）。
     * 服务端复核职业与段位解锁（防客户端改包越级下单）。
     */
    public record WorkerOrderPayload(int entityId, String product)
            implements CustomPacketPayload {

        public static final Type<WorkerOrderPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "worker_order"));

        public static final StreamCodec<ByteBuf, WorkerOrderPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, WorkerOrderPayload::entityId,
                        ByteBufCodecs.STRING_UTF8, WorkerOrderPayload::product,
                        WorkerOrderPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * C → S：perk 选档（成长区分支行点击）。
     *
     * <p>服务端用 {@code Perks.canChoose} 复核段位与分支合法性后才落档，
     * 客户端改包跳级选传奇分支在这里被拒。
     */
    public record PerkChoosePayload(int entityId, String node, String branch)
            implements CustomPacketPayload {

        public static final Type<PerkChoosePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "perk_choose"));

        public static final StreamCodec<ByteBuf, PerkChoosePayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, PerkChoosePayload::entityId,
                        ByteBufCodecs.STRING_UTF8, PerkChoosePayload::node,
                        ByteBufCodecs.STRING_UTF8, PerkChoosePayload::branch,
                        PerkChoosePayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ==========================================================
    //  处理器
    // ==========================================================

    /** perk 选档：复核段位与分支合法性，覆盖同节点旧选择（免费改选）。 */
    private static void handlePerkChoose(PerkChoosePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player player = ctx.player();
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return;
            }
            if (!(serverPlayer.level().getEntity(payload.entityId())
                    instanceof com.bmf.bmfstown.entity.Resident resident)) {
                return; // 镇民不在了：静默丢弃
            }
            if (!com.bmf.bmfstown.entity.profile.Perks.canChoose(
                    resident.getIdentity(), resident.getGrowth(),
                    resident.getTraitState().trait(),
                    payload.node(), payload.branch())) {
                // 段位不够/节点或分支不认识：拒绝并提示（改包客户端会走到这）
                serverPlayer.displayClientMessage(Component.translatable(
                        "msg.bmfstown.perk.rejected"), true);
                com.mojang.logging.LogUtils.getLogger().info(
                        "[BmfTown][perk] {} 选档被拒：node={}，branch={}",
                        resident.getName().getString(), payload.node(), payload.branch());
                return;
            }
            resident.getGrowth().setPerkChoice(
                    com.bmf.bmfstown.entity.profile.Perks.ownerOf(payload.node()),
                    payload.node(), payload.branch());
            resident.syncClient();
            com.mojang.logging.LogUtils.getLogger().info(
                    "[BmfTown][perk] {} 选定分支：{} : {}",
                    resident.getName().getString(), payload.node(), payload.branch());
        });
    }

    /** 跟随开关：校验镇民存在且是战士，切换 FOLLOW / NONE。 */
    private static void handleWorkFollow(WorkFollowPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player player = ctx.player();
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return;
            }
            if (!(serverPlayer.level().getEntity(payload.entityId())
                    instanceof com.bmf.bmfstown.entity.Resident resident)) {
                com.mojang.logging.LogUtils.getLogger().info(
                        "[BmfTown][工作] 收到跟随请求，但实体 {} 不是镇民（已走远/死亡？）",
                        payload.entityId());
                return;
            }
            if (!resident.isWarrior()) {
                com.mojang.logging.LogUtils.getLogger().info(
                        "[BmfTown][工作] {} 请求跟随被拒：职业={}，不是战士",
                        resident.getName().getString(), resident.getProfession());
                serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "msg.bmfstown.work.need_warrior"), true);
                return;
            }
            if (resident.getWorkMode() == com.bmf.bmfstown.entity.WorkMode.FOLLOW) {
                resident.setWorkMode(com.bmf.bmfstown.entity.WorkMode.NONE, null, null);
                com.mojang.logging.LogUtils.getLogger().info(
                        "[BmfTown][工作] {} 停止工作（跟随开关关闭）",
                        resident.getName().getString());
                serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "msg.bmfstown.work.follow_off", resident.getName().getString()), true);
            } else {
                resident.setWorkMode(com.bmf.bmfstown.entity.WorkMode.FOLLOW,
                        serverPlayer.getUUID(), null);
                com.mojang.logging.LogUtils.getLogger().info(
                        "[BmfTown][工作] {} 进入跟随模式（跟随玩家 {}）",
                        resident.getName().getString(), serverPlayer.getName().getString());
                serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "msg.bmfstown.work.follow_on", resident.getName().getString()), true);
            }
        });
    }

    /** 站岗确认：校验镇民存在且是战士，落账 GUARD_POST。 */
    private static void handleWorkGuardPost(WorkGuardPostPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player player = ctx.player();
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return;
            }
            if (!(serverPlayer.level().getEntity(payload.entityId())
                    instanceof com.bmf.bmfstown.entity.Resident resident)) {
                return; // 镇民不在了（走远/死亡）：静默丢弃
            }
            if (!resident.isWarrior()) {
                serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "msg.bmfstown.work.need_warrior"), true);
                return;
            }
            resident.setWorkMode(com.bmf.bmfstown.entity.WorkMode.GUARD_POST, null, payload.pos());
            serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "msg.bmfstown.work.guard_set",
                    resident.getName().getString(),
                    payload.pos().toShortString(),
                    com.bmf.bmfstown.config.Config.WARRIOR_GUARD_RADIUS.get()), true);
        });
    }

    /** 工坊绑定：校验职业=工人、合成台方块无误，三件套落账。 */
    private static void handleWorkshopBind(WorkshopBindPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player player = ctx.player();
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return;
            }
            if (!(serverPlayer.level().getEntity(payload.entityId())
                    instanceof com.bmf.bmfstown.entity.Resident resident)) {
                return; // 镇民不在了：静默丢弃
            }
            if (resident.getProfession()
                    != com.bmf.bmfstown.entity.profile.ProfessionType.WORKER) {
                serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "msg.bmfstown.work.need_worker"), true);
                return;
            }
            ServerLevel level = serverPlayer.serverLevel();
            if (!(level.getBlockState(payload.table())
                    .is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE))) {
                serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "msg.bmfstown.workshop.need_table"), true);
                return;
            }
            resident.getJobState().setWorkshop(
                    payload.table().asLong(), payload.input().asLong(), payload.output().asLong());
            serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "msg.bmfstown.workshop.bound", resident.getName().getString()), true);
        });
    }

    /** 手册居民列表打开镇民面板：不走交互锁，NPC 不被打断。 */
    private static void handleOpenResidentFromHandbook(OpenResidentFromHandbookPayload payload,
                                                       IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer serverPlayer)) {
                return;
            }
            if (!(serverPlayer.level().getEntity(payload.entityId())
                    instanceof com.bmf.bmfstown.entity.Resident resident)) {
                return;
            }
            boolean fromHandbook = payload.reopenMode() != 0;
            serverPlayer.openMenu(new com.bmf.bmfstown.menu.ResidentMenuProvider(resident, fromHandbook),
                    buf -> {
                        buf.writeVarInt(resident.getId());
                        buf.writeBoolean(fromHandbook); // 免距离关闭
                        if (fromHandbook) {
                            buf.writeVarInt(payload.reopenMode()); // 关闭回跳目标
                        }
                    });
        });
    }

    /** 传送玩家到镇民身边（支持跨维度）。 */
    private static void handleTeleportToResident(TeleportToResidentPayload payload,
                                                 IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer serverPlayer)) {
                return;
            }
            if (!(serverPlayer.level().getEntity(payload.entityId())
                    instanceof com.bmf.bmfstown.entity.Resident resident)) {
                return;
            }
            if (!(resident.level() instanceof net.minecraft.server.level.ServerLevel targetLevel)) {
                return;
            }
            serverPlayer.teleportTo(targetLevel,
                    resident.getX(), resident.getY(), resident.getZ(),
                    resident.getYRot(), resident.getXRot());
            serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "msg.bmfstown.teleport.done",
                    resident.getName().getString()), false);
        });
    }

    /** GATTACA 伪装确认：校验观察者特性后执行复制。 */
    private static void handleGattacaMimic(GattacaMimicPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer serverPlayer)) {
                return;
            }
            if (!(serverPlayer.level().getEntity(payload.observerId())
                    instanceof com.bmf.bmfstown.entity.Resident observer)
                    || !(serverPlayer.level().getEntity(payload.targetId())
                    instanceof com.bmf.bmfstown.entity.Resident target)) {
                return;
            }
            if (observer.getTraitState().trait()
                    != com.bmf.bmfstown.entity.profile.TraitType.GATTACA
                    || observer.getTraitState().isMimicking()) {
                return; // 特性被换掉或已在模仿中：拒绝（客户端按钮应不可用）
            }
            int day = (int) (serverPlayer.serverLevel().getDayTime() / 24000L);
            observer.getTraitState().startMimic(observer, target, day);
            serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "msg.bmfstown.gattaca.mimic",
                    observer.getName().getString(),
                    target.getName().getString()), false);
        });
    }

    /** 重命名镇民：校验长度后 setCustomName（原版 NBT 持久化，头顶名牌同步生效）。 */
    private static void handleResidentRename(ResidentRenamePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer serverPlayer)) {
                return;
            }
            if (!(serverPlayer.level().getEntity(payload.entityId())
                    instanceof com.bmf.bmfstown.entity.Resident resident)) {
                return;
            }
            String name = payload.name().trim();
            if (name.isEmpty() || name.length() > 32) {
                serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "msg.bmfstown.rename.invalid"), true);
                return;
            }
            resident.setCustomName(net.minecraft.network.chat.Component.literal(name));
            serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "msg.bmfstown.rename.done", name), false);
        });
    }

    private static void handleCensusData(CensusDataPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof net.minecraft.client.player.LocalPlayer player) {
                // 先关容器：面板 → 搜索屏是 setScreen 直接替换，容器不关的话
                // 服务端交互锁残留（FaceInteractingPlayerGoal 占 MOVE+LOOK），
                // 簿记员带路 Goal 被冻结（"选完物品不走，重开面板才走"根因）
                if (player.containerMenu != null
                        && player.containerMenu != player.inventoryMenu) {
                    player.closeContainer();
                }
                com.bmf.bmfstown.client.BookkeeperSearchScreen.openWith(payload);
            }
        });
    }

    /** 簿记员：请求城镇物资统计（有新鲜数据直接下发，否则委托扫描）。 */
    private static void handleCensusRequest(CensusRequestPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player player = ctx.player();
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return;
            }
            if (!(serverPlayer.level().getEntity(payload.entityId())
                    instanceof com.bmf.bmfstown.entity.Resident resident)) {
                return;
            }
            if (resident.getProfession()
                    != com.bmf.bmfstown.entity.profile.ProfessionType.BOOKKEEPER) {
                serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "msg.bmfstown.bookkeeper.need_bookkeeper"), true);
                return;
            }
            var town = resident.boundTown();
            if (town == null) {
                serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "msg.bmfstown.bookkeeper.no_town"), true);
                return;
            }
            var census = com.bmf.bmfstown.town.TownCensusManager.request(
                    serverPlayer.serverLevel(), town, resident, serverPlayer);
            if (census == null) {
                // 扫描完成会自动推送给请求者并弹出选择界面，无需再点
                serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "msg.bmfstown.bookkeeper.counting"), false);
                return;
            }
            java.util.List<CensusEntryPayload> entries = new java.util.ArrayList<>();
            for (var e : census.totals().entrySet()) {
                entries.add(new CensusEntryPayload(
                        com.bmf.bmfstown.entity.profile.WorkshopCrafts.keyOf(e.getKey()),
                        e.getValue()));
            }
            playCensusSync(serverPlayer, resident, town, census);
        });
    }

    /**
     * 服务端把城镇物资统计发给玩家（打开选择界面）。
     * 精度按簿记员段位：生疏/入门=有无；熟练/老练=数量级；专家+=精确。
     */
    public static void playCensusSync(ServerPlayer player,
            com.bmf.bmfstown.entity.Resident resident,
            com.bmf.bmfstown.town.Town town,
            com.bmf.bmfstown.town.TownCensusManager.Census census) {
        int bkTier = com.bmf.bmfstown.entity.profile.SkillTiers.tierOf(
                resident.getGrowth().skillXp(com.bmf.bmfstown.entity.profile.SkillType.BOOKKEEPING));
        int precision = bkTier < 2 ? 0 : bkTier < 4 ? 1 : 2;
        java.util.List<CensusEntryPayload> entries = new java.util.ArrayList<>();
        for (var e : census.totals().entrySet()) {
            entries.add(new CensusEntryPayload(
                    com.bmf.bmfstown.entity.profile.WorkshopCrafts.keyOf(e.getKey()),
                    e.getValue()));
        }
        player.connection.send(new CensusDataPayload(
                resident.getId(), town.name(), precision, entries));
    }

    /** 簿记员寻物：找最近的装着该物品的容器，挂带路目标。 */
    private static void handleBookkeeperFind(BookkeeperFindPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player player = ctx.player();
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return;
            }
            if (!(serverPlayer.level().getEntity(payload.entityId())
                    instanceof com.bmf.bmfstown.entity.Resident resident)) {
                return;
            }
            if (resident.getProfession()
                    != com.bmf.bmfstown.entity.profile.ProfessionType.BOOKKEEPER) {
                serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "msg.bmfstown.bookkeeper.need_bookkeeper"), true);
                return;
            }
            var town = resident.boundTown();
            var census = town == null ? null : com.bmf.bmfstown.town.TownCensusManager.peek(town);
            if (census == null) {
                serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "msg.bmfstown.bookkeeper.counting"), true);
                return;
            }
            var item = com.bmf.bmfstown.entity.profile.WorkshopCrafts.BuiltInItemKey.item(
                    payload.item());
            var where = census.where().get(item);
            if (where == null || where.isEmpty()) {
                serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "msg.bmfstown.bookkeeper.not_found",
                        item.getDefaultInstance().getHoverName()), false);
                return;
            }
            // 离玩家最近的容器
            BlockPos best = null;
            double bestDist = Double.MAX_VALUE;
            for (BlockPos pos : where) {
                double d = pos.distSqr(serverPlayer.blockPosition());
                if (d < bestDist) {
                    bestDist = d;
                    best = pos;
                }
            }
            resident.setBookkeeperFindTarget(best);
            // 夜里被点单：叫醒去带路（用户定稿：叫醒就干活，别躺回去），
            // 带完 tickSleep 自动恢复夜间回床流程
            resident.wakeUpForErrand();
            serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "msg.bmfstown.bookkeeper.leading",
                    resident.getName().getString(),
                    item.getDefaultInstance().getHoverName()), false);
        });
    }

    /** 工人开工/停工切换：校验职业后翻转开关。 */
    private static void handleWorkerToggle(WorkerTogglePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player player = ctx.player();
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return;
            }
            if (!(serverPlayer.level().getEntity(payload.entityId())
                    instanceof com.bmf.bmfstown.entity.Resident resident)) {
                return;
            }
            if (resident.getProfession()
                    != com.bmf.bmfstown.entity.profile.ProfessionType.WORKER) {
                serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "msg.bmfstown.work.need_worker"), true);
                return;
            }
            boolean nowEnabled = !resident.getJobState().workerEnabled();
            resident.getJobState().setWorkerEnabled(nowEnabled);
            serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    nowEnabled ? "msg.bmfstown.workshop.enabled" : "msg.bmfstown.workshop.disabled",
                    resident.getName().getString()), false);
        });
    }

    /** 工人订单：校验职业、索引合法与段位解锁，落账。 */
    private static void handleWorkerOrder(WorkerOrderPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player player = ctx.player();
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return;
            }
            if (!(serverPlayer.level().getEntity(payload.entityId())
                    instanceof com.bmf.bmfstown.entity.Resident resident)) {
                return;
            }
            if (resident.getProfession()
                    != com.bmf.bmfstown.entity.profile.ProfessionType.WORKER) {
                serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "msg.bmfstown.work.need_worker"), true);
                return;
            }
            var product = com.bmf.bmfstown.entity.profile.WorkshopCrafts.BuiltInItemKey.item(
                    payload.product());
            if (product == net.minecraft.world.item.Items.AIR
                    || !com.bmf.bmfstown.entity.profile.WorkshopCrafts.isWhitelisted(product)) {
                return; // 非白名单产物：静默丢弃
            }
            if (!com.bmf.bmfstown.entity.profile.WorkshopCrafts.lineAllowed(
                    resident.getArchive(), product)) {
                serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "msg.bmfstown.workshop.locked"), true);
                return;
            }
            var craft = com.bmf.bmfstown.entity.profile.WorkshopCrafts.byProduct(
                    serverPlayer.serverLevel().getRecipeManager(),
                    serverPlayer.serverLevel().registryAccess(), payload.product());
            if (craft == null) {
                serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "msg.bmfstown.workshop.locked"), true);
                return; // 白名单里但没有对应配方（异常数据）：拒绝
            }
            resident.getJobState().setOrderItem(payload.product());
            // 双通道反馈：主聊天栏（醒目、不消失）+ actionbar（轻提示）
            serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "msg.bmfstown.workshop.order_set",
                    craft.result().getDefaultInstance().getHoverName()), false);
            serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "msg.bmfstown.workshop.order_set",
                    craft.result().getDefaultInstance().getHoverName()), true);
        });
    }

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

    private static void handleWandPoint(WandPointPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player player = ctx.player();
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return;
            }
            ItemStack held = serverPlayer.getMainHandItem();
            if (!(held.getItem() instanceof TownWandItem)) {
                return; // 手里没拿魔杖（切走了/包延迟），忽略
            }
            if (payload.index() == 0) {
                held.set(ModDataComponents.WAND_POINT_A.get(), payload.pos().immutable());
                held.remove(ModDataComponents.WAND_POINT_B.get());
                com.bmf.bmfstown.BmfTown.LOGGER.debug(
                        "[BmfTown][选点] 第1点 = {}", payload.pos().toShortString());
            } else {
                BlockPos a = held.get(ModDataComponents.WAND_POINT_A.get());
                if (a == null || payload.pos().equals(a)) {
                    com.bmf.bmfstown.BmfTown.LOGGER.warn(
                            "[BmfTown][选点] 收到第2点 {} 但第1点未定或相同，忽略", payload.pos().toShortString());
                    return;
                }
                held.set(ModDataComponents.WAND_POINT_B.get(), payload.pos().immutable());
                com.bmf.bmfstown.BmfTown.LOGGER.debug(
                        "[BmfTown][选点] 第2点 = {}（第1点 = {}）",
                        payload.pos().toShortString(), a.toShortString());
            }
        });
    }

    private static void handleWandConfirm(WandConfirmPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer serverPlayer) {
                TownWandItem.confirm(serverPlayer);
            }
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

    /** 手册"增加选区"：开启空手选区会话（客户端自行关闭手册进世界选点）。 */
    private static void handleHandbookAppendZone(HandbookAppendZonePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer serverPlayer) {
                TownManager.startAppendBlock(serverPlayer, payload.townId());
            }
        });
    }

    private static void handleZoneSelectSync(ZoneSelectSyncPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() ->
                com.bmf.bmfstown.client.TownHandbookClient.setZoneSelecting(payload.active()));
    }

    /** 选区会话状态推送给客户端（会话开始/结束时调）。 */
    public static void sendZoneSelectSync(ServerPlayer player, boolean active) {
        PacketDistributor.sendToPlayer(player, new ZoneSelectSyncPayload(active));
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
    //  S → C：嘲讽同步（客户端渲染红罩用）
    // ==========================================================

    /**
     * 服务端 → 客户端：这只怪被嘲讽到游戏时间 untilGameTime。
     *
     * <p>为什么 UUID 用字符串而不用 VAR_INT 实体 id：实体 id 每次进服重排，
     * 而渲染层恰好拿得到实体的 UUID——对键最稳。
     */
    public record TauntSyncPayload(String monsterId, long untilGameTime)
            implements CustomPacketPayload {

        public static final Type<TauntSyncPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "taunt_sync"));

        public static final StreamCodec<ByteBuf, TauntSyncPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, TauntSyncPayload::monsterId,
                        ByteBufCodecs.VAR_LONG, TauntSyncPayload::untilGameTime,
                        TauntSyncPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static void handleTauntSync(TauntSyncPayload payload, IPayloadContext ctx) {
        // 处理器体只在客户端执行；ClientTauntState 是 client 包的类，
        // 专用服务器永远不会走到这行，类不会加载。
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof net.minecraft.client.player.LocalPlayer) {
                com.bmf.bmfstown.client.ClientTauntState.handleSync(
                        java.util.UUID.fromString(payload.monsterId()), payload.untilGameTime());
            }
        });
    }

    // ==========================================================
    //  S → C：镇民想法日志同步（"喜好"页下方列表用）
    // ==========================================================

    /**
     * 一条想法：发生日 + 色调 + 语言文件 key + 标记。
     *
     * <p>不传数值（用户定稿：为沉浸感面板不显示心情增减的具体数字）、
     * 不传现成文案（客户端 I18n 现译，切语言即时生效）。
     * 序列化到网络时四个字段一起编，所以打包成独立 record 带自己的 codec。
     *
     * <p>tone：0 = 中性（灰）、1 = 正面（绿）、2 = 负面（红）；
     * marker：0 = 无、1 = 乐观 "+"、2 = 悲观 "−"（v0.8 性格滤镜，
     * 客户端渲染在文案尾部）。
     */
    public record MoodThought(int day, int tone, String key, int marker)
            implements CustomPacketPayload {

        public static final StreamCodec<ByteBuf, MoodThought> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, MoodThought::day,
                        ByteBufCodecs.VAR_INT, MoodThought::tone,
                        ByteBufCodecs.STRING_UTF8, MoodThought::key,
                        ByteBufCodecs.VAR_INT, MoodThought::marker,
                        MoodThought::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        /** 心情想法包共用一个 Type 占位（本 record 只作为嵌套数据，不单独注册）。 */
        public static final Type<MoodThought> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "mood_thought"));
    }

    /**
     * 服务端 → 客户端：某镇民的完整想法日志（最多 30 条）+ 偏好摘要。
     *
     * <p>什么时候发：玩家右键镇民打开面板时发一次（服务端权威），
     * 面板开着期间不实时推送——想法本来就是低频事件，
     * 下次打开面板自然会看到新的。镇民用 UUID 定位（实体 id 进服重排）。
     *
     * <p>{@code prefs} 是 v0.8 偏好体系的紧凑编码
     * （{@code blocks|animal|color|music|weather|mob|personality}，
     * 空段 = 没有这项偏好）——1.21.1 的 {@code ByteBufCodecs.list}
     * 没有"codec + 容量"重载，整条串比逐字段编省事也省字节。
     */
    public record MoodSyncPayload(String residentId, java.util.List<MoodThought> thoughts,
            String prefs, String workerOrder, String trait, String mimic)
            implements CustomPacketPayload {

        public static final Type<MoodSyncPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "mood_sync"));

        /**
         * 手写编解码，不用 composite+list 组合——1.21.1 的
         * {@code ByteBufCodecs.list} 没有"codec+容量"重载（那是 1.21.2+ 的），
         * {@code collection} 又会把类型推断成具体的 Collection 实现类。
         * 就三个逻辑字段（uuid 字符串 + 思考列表 + 偏好串），手写十行最直白。
         */
        public static final StreamCodec<ByteBuf, MoodSyncPayload> STREAM_CODEC = StreamCodec.of(
                (buf, payload) -> {
                    ByteBufCodecs.STRING_UTF8.encode(buf, payload.residentId());
                    ByteBufCodecs.VAR_INT.encode(buf, payload.thoughts().size());
                    for (MoodThought t : payload.thoughts()) {
                        MoodThought.STREAM_CODEC.encode(buf, t);
                    }
                    ByteBufCodecs.STRING_UTF8.encode(buf, payload.prefs());
                    ByteBufCodecs.STRING_UTF8.encode(buf, payload.workerOrder());
                    ByteBufCodecs.STRING_UTF8.encode(buf, payload.trait());
                    ByteBufCodecs.STRING_UTF8.encode(buf, payload.mimic());
                },
                buf -> {
                    String residentId = ByteBufCodecs.STRING_UTF8.decode(buf);
                    int size = ByteBufCodecs.VAR_INT.decode(buf);
                    java.util.List<MoodThought> list = new java.util.ArrayList<>(size);
                    for (int i = 0; i < size; i++) {
                        list.add(MoodThought.STREAM_CODEC.decode(buf));
                    }
                    String prefs = ByteBufCodecs.STRING_UTF8.decode(buf);
                    String workerOrder = ByteBufCodecs.STRING_UTF8.decode(buf);
                    String trait = ByteBufCodecs.STRING_UTF8.decode(buf);
                    String mimic = ByteBufCodecs.STRING_UTF8.decode(buf);
                    return new MoodSyncPayload(residentId, java.util.List.copyOf(list), prefs,
                            workerOrder, trait, mimic);
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static void handleMoodSync(MoodSyncPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof net.minecraft.client.player.LocalPlayer) {
                com.bmf.bmfstown.client.ClientMoodState.handleSync(payload);
            }
        });
    }

    /**
     * 服务端把镇民的想法日志与偏好摘要发给玩家（打开面板时调用）。
     */
    public static void playMoodSync(ServerPlayer player,
            com.bmf.bmfstown.entity.Resident resident) {
        java.util.List<MoodThought> list = new java.util.ArrayList<>();
        for (var t : resident.getStatus().thoughts()) {
            list.add(new MoodThought(t.day(), t.tone(), t.key(), t.marker()));
        }
        // 偏好懒生成：面板打开这一下正好是最合适的触发点——
        // 保证发出去的 prefs 串一定不是"未生成"的空壳。
        // （服务端 level() 必然是 ServerLevel，instanceof 只是防御姿态）
        if (resident.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            resident.getStatus().ensureGenerated(resident, serverLevel);
            resident.syncClient();
        }
        var trait = resident.getTraitState().trait();
        var traitState = resident.getTraitState();
        String mimic = traitState.isMimicking()
                ? traitState.mimicTargetName() + "|" + traitState.mimicTraitId() : "";
        player.connection.send(new MoodSyncPayload(
                resident.getStringUUID(), java.util.List.copyOf(list),
                resident.getPrefs().prefsString(),
                resident.getJobState().orderItem(),
                trait == null ? "" : trait.id(),
                mimic));
    }

    // ==========================================================
    //  注册
    // ==========================================================

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        // "1" 是协议版本号；客户端与服务端不一致时拒绝连接，防串版本。
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(WandModePayload.TYPE, WandModePayload.STREAM_CODEC,
                ModNetworking::handleWandMode);
        registrar.playToServer(WandPointPayload.TYPE, WandPointPayload.STREAM_CODEC,
                ModNetworking::handleWandPoint);
        registrar.playToServer(WandConfirmPayload.TYPE, WandConfirmPayload.STREAM_CODEC,
                ModNetworking::handleWandConfirm);
        registrar.playToServer(HandbookRequestPayload.TYPE, HandbookRequestPayload.STREAM_CODEC,
                ModNetworking::handleHandbookRequest);
        registrar.playToServer(HandbookRenamePayload.TYPE, HandbookRenamePayload.STREAM_CODEC,
                ModNetworking::handleHandbookRename);
        registrar.playToServer(HandbookDeletePayload.TYPE, HandbookDeletePayload.STREAM_CODEC,
                ModNetworking::handleHandbookDelete);
        registrar.playToServer(WorkGuardPostPayload.TYPE, WorkGuardPostPayload.STREAM_CODEC,
                ModNetworking::handleWorkGuardPost);
        registrar.playToServer(WorkFollowPayload.TYPE, WorkFollowPayload.STREAM_CODEC,
                ModNetworking::handleWorkFollow);
        registrar.playToServer(WorkshopBindPayload.TYPE, WorkshopBindPayload.STREAM_CODEC,
                ModNetworking::handleWorkshopBind);
        registrar.playToServer(WorkerOrderPayload.TYPE, WorkerOrderPayload.STREAM_CODEC,
                ModNetworking::handleWorkerOrder);
        registrar.playToServer(WorkerTogglePayload.TYPE, WorkerTogglePayload.STREAM_CODEC,
                ModNetworking::handleWorkerToggle);
        registrar.playToServer(CensusRequestPayload.TYPE, CensusRequestPayload.STREAM_CODEC,
                ModNetworking::handleCensusRequest);
        registrar.playToServer(ResidentRenamePayload.TYPE, ResidentRenamePayload.STREAM_CODEC,
                ModNetworking::handleResidentRename);
        registrar.playToServer(OpenResidentFromHandbookPayload.TYPE,
                OpenResidentFromHandbookPayload.STREAM_CODEC,
                ModNetworking::handleOpenResidentFromHandbook);
        registrar.playToServer(TeleportToResidentPayload.TYPE,
                TeleportToResidentPayload.STREAM_CODEC,
                ModNetworking::handleTeleportToResident);
        registrar.playToServer(GattacaMimicPayload.TYPE,
                GattacaMimicPayload.STREAM_CODEC,
                ModNetworking::handleGattacaMimic);
        registrar.playToServer(BookkeeperFindPayload.TYPE, BookkeeperFindPayload.STREAM_CODEC,
                ModNetworking::handleBookkeeperFind);
        registrar.playToClient(CensusDataPayload.TYPE, CensusDataPayload.STREAM_CODEC,
                ModNetworking::handleCensusData);
        registrar.playToServer(PerkChoosePayload.TYPE, PerkChoosePayload.STREAM_CODEC,
                ModNetworking::handlePerkChoose);
        registrar.playToServer(HandbookAppendZonePayload.TYPE, HandbookAppendZonePayload.STREAM_CODEC,
                ModNetworking::handleHandbookAppendZone);
        registrar.playToClient(ZoneSelectSyncPayload.TYPE, ZoneSelectSyncPayload.STREAM_CODEC,
                ModNetworking::handleZoneSelectSync);
        registrar.playToClient(HandbookSyncPayload.TYPE, HandbookSyncPayload.STREAM_CODEC,
                ModNetworking::handleHandbookSync);
        registrar.playToClient(TauntSyncPayload.TYPE, TauntSyncPayload.STREAM_CODEC,
                ModNetworking::handleTauntSync);
        registrar.playToClient(MoodSyncPayload.TYPE, MoodSyncPayload.STREAM_CODEC,
                ModNetworking::handleMoodSync);
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
                .get(player.serverLevel()).toBlob(player.serverLevel());
        PacketDistributor.sendToPlayer(player, new HandbookSyncPayload(blob, open));
    }
}
