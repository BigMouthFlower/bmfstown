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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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

    /** S → C：魔法降雪状态（开始/结束/中途进服补发）。 */
    public record SnowfallSyncPayload(boolean active, long centerPacked, int radius)
            implements CustomPacketPayload {

        public static final Type<SnowfallSyncPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "snowfall_sync"));

        public static final StreamCodec<ByteBuf, SnowfallSyncPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL, SnowfallSyncPayload::active,
                        ByteBufCodecs.VAR_LONG, SnowfallSyncPayload::centerPacked,
                        ByteBufCodecs.VAR_INT, SnowfallSyncPayload::radius,
                        SnowfallSyncPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S → C：解锁状态全量快照（按城镇分组；解锁瞬间与登录时发送）。 */
    public record TownUnlockEntry(String townId, java.util.List<String> ids) {
    }

    public record UnlockSyncPayload(java.util.List<TownUnlockEntry> towns)
            implements CustomPacketPayload {

        public static final Type<UnlockSyncPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "unlock_sync"));

        public static final StreamCodec<ByteBuf, UnlockSyncPayload> STREAM_CODEC =
                StreamCodec.ofMember(
                        (payload, buf) -> {
                            ByteBufCodecs.VAR_INT.encode(buf, payload.towns().size());
                            for (TownUnlockEntry town : payload.towns()) {
                                ByteBufCodecs.STRING_UTF8.encode(buf, town.townId());
                                ByteBufCodecs.VAR_INT.encode(buf, town.ids().size());
                                for (String id : town.ids()) {
                                    ByteBufCodecs.STRING_UTF8.encode(buf, id);
                                }
                            }
                        },
                        buf -> {
                            int towns = ByteBufCodecs.VAR_INT.decode(buf);
                            java.util.List<TownUnlockEntry> list = new java.util.ArrayList<>(towns);
                            for (int i = 0; i < towns; i++) {
                                String townId = ByteBufCodecs.STRING_UTF8.decode(buf);
                                int n = ByteBufCodecs.VAR_INT.decode(buf);
                                java.util.List<String> ids = new java.util.ArrayList<>(n);
                                for (int j = 0; j < n; j++) {
                                    ids.add(ByteBufCodecs.STRING_UTF8.decode(buf));
                                }
                                list.add(new TownUnlockEntry(townId, java.util.List.copyOf(ids)));
                            }
                            return new UnlockSyncPayload(java.util.List.copyOf(list));
                        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S → C：昼夜流速镜像（客户端 ClientLevel 据此平滑插值太阳动画）。 */
    public record TimeSpeedSyncPayload(float perTick)
            implements CustomPacketPayload {

        public static final Type<TimeSpeedSyncPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "time_speed_sync"));

        public static final StreamCodec<ByteBuf, TimeSpeedSyncPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.FLOAT, TimeSpeedSyncPayload::perTick,
                        TimeSpeedSyncPayload::new);

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
     * C → S：武器偏好循环切换（工作页战士按钮，决策 #49）。
     *
     * <p>走自定义包而非菜单按钮协议的理由（2026-11-16 二次实测修订）：
     * 跟随/站岗确认早已改走自定义链路且实测可用，而武器偏好的菜单按钮
     * 协议两轮实测均"点了没反应"（静态链路查无断点，疑似协议层问题）——
     * 统一收编到已验证的链路，服务端逻辑从 ResidentMenu.clickMenuButton
     * 迁到 handleWeaponPref（原菜单分支保留作兜底）。
     */
    public record WeaponPrefPayload(int entityId) implements CustomPacketPayload {

        public static final Type<WeaponPrefPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "weapon_pref"));

        public static final StreamCodec<ByteBuf, WeaponPrefPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, WeaponPrefPayload::entityId,
                        WeaponPrefPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * C → S：手册城镇页「登记方块」按钮——开启（或取消）工坊登记会话（城镇级）。
     * 服务端复核城镇存在、成员身份、同维度后开启会话。
     */
    public record HandbookRegisterSessionPayload(String townId) implements CustomPacketPayload {

        public static final Type<HandbookRegisterSessionPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "handbook_register_session"));

        public static final StreamCodec<ByteBuf, HandbookRegisterSessionPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, HandbookRegisterSessionPayload::townId,
                        HandbookRegisterSessionPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S → C：工坊登记会话状态（客户端据此在世界里画登记小框）。 */
    public record RegisterSessionSyncPayload(boolean active, String zoneId)
            implements CustomPacketPayload {

        public static final Type<RegisterSessionSyncPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "register_session_sync"));

        public static final StreamCodec<ByteBuf, RegisterSessionSyncPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL, RegisterSessionSyncPayload::active,
                        ByteBufCodecs.STRING_UTF8, RegisterSessionSyncPayload::zoneId,
                        RegisterSessionSyncPayload::new);

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
     * C → S：跟随见习确认（卡点学者在选人列表查看某人 UI 后点"跟随见习"）。
     * 服务端校验领域对口后，让学者跟随目标实践（观察计数走 PracticeObserver）。
     */
    public record PracticeFollowPayload(int scholarId, int targetId)
            implements CustomPacketPayload {

        public static final Type<PracticeFollowPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "practice_follow"));

        public static final StreamCodec<ByteBuf, PracticeFollowPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, PracticeFollowPayload::scholarId,
                        ByteBufCodecs.VAR_INT, PracticeFollowPayload::targetId,
                        PracticeFollowPayload::new);

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
     * C → S：请求某个居民的履历数据（打开履历页时发一次）。
     *
     * <p><b>为什么不走快照</b>：统计表会随职业越做越长，而快照是
     * `DATA_ARCHIVE` 实体同步数据——名字一变、心情一动就整包重发。
     * 履历是"打开时才需要"的按需数据，不该拖着每一次心情波动走。
     * 与 {@link CensusRequestPayload} 同一个思路（点开才拉）。
     */
    public record LedgerRequestPayload(int entityId) implements CustomPacketPayload {

        public static final Type<LedgerRequestPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "ledger_request"));

        public static final StreamCodec<ByteBuf, LedgerRequestPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, LedgerRequestPayload::entityId,
                        LedgerRequestPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * S → C：履历数据。
     *
     * <p><b>为什么天数是服务端算好发过来</b>：客户端没有 `ServerLevel`，
     * `TownCalendar.absoluteDay` 拿不到权威天数（同一个老坑：客户端自己算
     * 年龄会跳变，见 §3.5）。天数和统计都在服务端定稿，客户端只负责画。
     *
     * <h2>为什么发"生日"而不是"活了几天"（2026-11-28 用户第二次打回）</h2>
     * 用户原话："履历显示的应该是他的年龄和出生日期，而非活了多久，
     * 活了多久具体到天无人在意，年龄就能看到大致了。"
     *
     * <p>这话是对的，而且不只是"精度"问题：<b>年龄已经由 {@code ageYears} 给出，
     * 再报一个"活了 2829 天"就是在同一屏用两把尺量同一个量</b>。
     * 生日给了年龄<b>给不出的那半条信息</b>——哪天长一岁（年内偏移是独立抽的，
     * 见 {@code AgingRolls}）。所以这一栏换成生日，信息量是净增的。
     *
     * <p>生日<b>换算成年月日再发</b>：客户端没有 {@code TownCalendar}（那是
     * 主世界的 {@code SavedData}），自己换算不了。换算用
     * {@code TownCalendar.dateOf(epoch, birthDay)}，允许 {@code birthDay < epoch}
     * ——29 岁的居民生日必然早于开镇，此时 {@code birthYear <= 0} 表示"开镇前"。
     *
     * @param daysInTown 在镇几天（`today − arrivalDay`）；**-1 = 未知，客户端不画这一行**
     * @param ageYears 整岁；-1 = 未生成
     * @param birthYear 生日所在年（1 = 元年；**<= 0 = 开镇前**）；{@code UNKNOWN_DAY} 生日时无意义
     * @param birthMonth 生日所在月（1~12）
     * @param birthDay 生日日号（1~8，= 月相序号 + 1）
     * @param hasBirthday 生日投影是否存在（老档居民没有；false 时三个生日字段都不画）
     */
    public record LedgerDataPayload(int entityId, int daysInTown,
            int ageYears, int birthYear, int birthMonth, int birthDay,
            boolean hasBirthday, java.util.Map<String, Integer> stats)
            implements CustomPacketPayload {

        public static final Type<LedgerDataPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "ledger_data"));

        public static final StreamCodec<ByteBuf, LedgerDataPayload> STREAM_CODEC = StreamCodec.of(
                (buf, payload) -> {
                    ByteBufCodecs.VAR_INT.encode(buf, payload.entityId());
                    // daysInTown 可能是 -1（未知）。可以走 VAR_INT：原版 varint 是
                    // zigzag 变长编码，负数无损。
                    ByteBufCodecs.VAR_INT.encode(buf, payload.daysInTown());
                    ByteBufCodecs.VAR_INT.encode(buf, payload.ageYears());
                    ByteBufCodecs.VAR_INT.encode(buf, payload.birthYear());
                    ByteBufCodecs.VAR_INT.encode(buf, payload.birthMonth());
                    ByteBufCodecs.VAR_INT.encode(buf, payload.birthDay());
                    ByteBufCodecs.BOOL.encode(buf, payload.hasBirthday());
                    ByteBufCodecs.VAR_INT.encode(buf, payload.stats().size());
                    payload.stats().forEach((k, v) -> {
                        ByteBufCodecs.STRING_UTF8.encode(buf, k);
                        ByteBufCodecs.VAR_INT.encode(buf, v);
                    });
                },
                buf -> {
                    int entityId = ByteBufCodecs.VAR_INT.decode(buf);
                    int daysInTown = ByteBufCodecs.VAR_INT.decode(buf);
                    int ageYears = ByteBufCodecs.VAR_INT.decode(buf);
                    int birthYear = ByteBufCodecs.VAR_INT.decode(buf);
                    int birthMonth = ByteBufCodecs.VAR_INT.decode(buf);
                    int birthDay = ByteBufCodecs.VAR_INT.decode(buf);
                    boolean hasBirthday = ByteBufCodecs.BOOL.decode(buf);
                    int size = ByteBufCodecs.VAR_INT.decode(buf);
                    java.util.Map<String, Integer> stats = new java.util.LinkedHashMap<>();
                    for (int i = 0; i < size; i++) {
                        String k = ByteBufCodecs.STRING_UTF8.decode(buf);
                        stats.put(k, ByteBufCodecs.VAR_INT.decode(buf));
                    }
                    return new LedgerDataPayload(entityId, daysInTown, ageYears,
                            birthYear, birthMonth, birthDay, hasBirthday,
                            java.util.Map.copyOf(stats));
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * C → S：请求"这块碑可以刻谁"的名单（2026-11-28 决策 #144）。
     *
     * <p><b>为什么走"请求-回包"而不是把名单塞进方块状态</b>：
     * 名册是镇级的（{@code GraveyardData} 挂主世界，全局一份），
     * 但玩家只是右键一块碑——他要的是"此刻这一下能刻谁"，
     * 不是全镇几十个死人的历史。放方块状态里就得每次名册变动刷新所有碑，
     * 而实际上玩家看名单的时间以秒计。
     *
     * @param pos 被右键的那块碑（回包里原样带回，客户端只认这一块）
     */
    public record GravePickRequestPayload(net.minecraft.core.BlockPos pos)
            implements CustomPacketPayload {

        public static final Type<GravePickRequestPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "grave_pick_request"));

        public static final StreamCodec<ByteBuf, GravePickRequestPayload> STREAM_CODEC =
                StreamCodec.composite(
                        net.minecraft.core.BlockPos.STREAM_CODEC,
                        GravePickRequestPayload::pos,
                        GravePickRequestPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * S → C：待葬名单（刻碑选人界面用）。
     *
     * <p><b>只发"显示需要的四样"</b>：UUID（回选用）、名字、死亡日、死因。
     * 碑文那一整串统计<b>不发</b>——名单是"挑人"，挑完才看碑文；
     * 一次传几十条的全量碑文纯属浪费，而且会让列表渲染变重。
     *
     * <p>{@code candidates} 为空 = 名册里一个待葬的都没有，客户端据此显示
     * "没有待葬的人"而不是开一个空列表。
     *
     * <p><b>不带请求序号</b>：发起请求的是"右键一块碑"这一个动作，
     * 同一时刻玩家只可能有一个选人界面（{@code setScreen} 是替换）。
     * 加一个永远等于 0 的字段只会让人以为有防串包机制。
     *
     * @param pos 原样带回的碑位——界面靠它决定"刻到哪块碑"
     */
    public record GravePickListPayload(net.minecraft.core.BlockPos pos,
            java.util.List<GraveCandidate> candidates) implements CustomPacketPayload {

        public static final Type<GravePickListPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "grave_pick_list"));

        public static final StreamCodec<ByteBuf, GravePickListPayload> STREAM_CODEC =
                StreamCodec.of(
                        (buf, payload) -> {
                            net.minecraft.core.BlockPos.STREAM_CODEC.encode(buf, payload.pos());
                            ByteBufCodecs.VAR_INT.encode(buf, payload.candidates().size());
                            for (GraveCandidate c : payload.candidates()) {
                                GraveCandidate.STREAM_CODEC.encode(buf, c);
                            }
                        },
                        buf -> {
                            net.minecraft.core.BlockPos pos =
                                    net.minecraft.core.BlockPos.STREAM_CODEC.decode(buf);
                            int size = ByteBufCodecs.VAR_INT.decode(buf);
                            java.util.List<GraveCandidate> list =
                                    new java.util.ArrayList<>(size);
                            for (int i = 0; i < size; i++) {
                                list.add(GraveCandidate.STREAM_CODEC.decode(buf));
                            }
                            return new GravePickListPayload(pos, java.util.List.copyOf(list));
                        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 待葬名单里的一行。
     *
     * <p><b>2026-11-28 补 {@code profile}</b>：原先只发"名字/死亡日/死因"四样，
     * 理由是"名单是挑人、挑完才看碑文"。用户实测直接否掉——挑人的时候
     * 最需要知道的恰恰是<b>这个人是谁</b>，只给一串名字根本没法判断该刻谁。
     * 现在每行自带一段生平（见 {@link #profileOf}），点选后显示在界面下半部。
     *
     * <p>原版 {@code Component} 的 {@code STREAM_CODEC} 需要 registry 上下文，
     * 而这个包的编解码器是 {@code StreamCodec<ByteBuf, ...>}（不带上下文），
     * 所以用 {@code TRUSTED_CONTEXT_FREE_STREAM_CODEC}——发出去的内容是
     * 我们自己拼的翻译键，不是玩家输入，不需要 registry 查表。
     *
     * @param deathDay 死亡日（世界绝对天数）；客户端只用来排序/显示
     * @param cause    死因 id（{@code old_age}/{@code killed}/{@code other}），
     *                 客户端拼 {@code death.bmfstown.cause.<cause>}
     * @param profile  生平分段（享年/在镇/职业/死因/前几条统计），已排好序
     *
     * <p><b>2026-11-28 二次改：从"一个用 \n 拼的组件"改成"行数组"。</b>
     * 拼 {@code \n} 的写法逼客户端自己 split 才能算高度、才能滚，
     * 而"一段占几行"本该由渲染端按窗口宽度定（中文没空格，只能像素折行）。
     * 现在发的是<b>逻辑分段</b>，客户端每段自己折行。
     */
    public record GraveCandidate(java.util.UUID id, String name, long deathDay, String causeKey,
            java.util.List<net.minecraft.network.chat.Component> profile) {

        public static final StreamCodec<ByteBuf, GraveCandidate> STREAM_CODEC =
                StreamCodec.of(
                        (buf, c) -> {
                            net.minecraft.core.UUIDUtil.STREAM_CODEC.encode(buf, c.id());
                            ByteBufCodecs.STRING_UTF8.encode(buf, c.name());
                            ByteBufCodecs.VAR_LONG.encode(buf, c.deathDay());
                            ByteBufCodecs.STRING_UTF8.encode(buf, c.causeKey());
                            ByteBufCodecs.VAR_INT.encode(buf, c.profile().size());
                            for (var line : c.profile()) {
                                net.minecraft.network.chat.ComponentSerialization
                                        .TRUSTED_CONTEXT_FREE_STREAM_CODEC.encode(buf, line);
                            }
                        },
                        buf -> {
                            var id = net.minecraft.core.UUIDUtil.STREAM_CODEC.decode(buf);
                            String name = ByteBufCodecs.STRING_UTF8.decode(buf);
                            long deathDay = ByteBufCodecs.VAR_LONG.decode(buf);
                            String causeKey = ByteBufCodecs.STRING_UTF8.decode(buf);
                            int n = ByteBufCodecs.VAR_INT.decode(buf);
                            java.util.List<net.minecraft.network.chat.Component> profile =
                                    new java.util.ArrayList<>(n);
                            for (int i = 0; i < n; i++) {
                                profile.add(net.minecraft.network.chat.ComponentSerialization
                                        .TRUSTED_CONTEXT_FREE_STREAM_CODEC.decode(buf));
                            }
                            return new GraveCandidate(id, name, deathDay, causeKey,
                                    java.util.List.copyOf(profile));
                        });
    }

    /**
     * 一个人的生平分段（{@link GraveCandidate#profile()} 的产出）。
     *
     * <p><b>直接返回 {@code GraveText.epitaphLines}</b>：名单里看的生平
     * 与碑上刻的碑文必须是<b>同一份东西</b>。原先这里另拼了一个
     * "用 \n 连起来"的版本，于是字段顺序要维护两遍——这才是比
     * "生平只有一个死因"更隐蔽的问题：改一处漏一处。
     *
     * <p>未知项（享年/在镇算不出来、无职业）在 {@code GraveText} 里整项不出现，
     * 不会印出"享年 -1"。
     */
    public static java.util.List<net.minecraft.network.chat.Component> profileOf(
            com.bmf.bmfstown.town.GraveyardData.Grave g, long epochDay) {
        return java.util.List.copyOf(
                com.bmf.bmfstown.town.GraveText.epitaphLines(g, epochDay));
    }

    /**
     * C → S：选定了某位逝者，刻到这块碑上。
     *
     * <p><b>服务端必须重新校验三件事</b>（客户端的名单可能已经过期）：
     * ① 这块位置还是墓碑、② 这块碑还没刻过人、③ 这个 UUID 真的待葬。
     * 任一不成立就回一条聊天提示，<b>绝不静默绑上</b>——
     * 静默成功会让"屏幕上显示刻的是 A、实际刻的是 B"变成可能。
     */
    public record GraveCarvePayload(net.minecraft.core.BlockPos pos,
            java.util.UUID who) implements CustomPacketPayload {

        public static final Type<GraveCarvePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "grave_carve"));

        public static final StreamCodec<ByteBuf, GraveCarvePayload> STREAM_CODEC =
                StreamCodec.composite(
                        net.minecraft.core.BlockPos.STREAM_CODEC, GraveCarvePayload::pos,
                        net.minecraft.core.UUIDUtil.STREAM_CODEC, GraveCarvePayload::who,
                        GraveCarvePayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * S → C：一块碑的碑文（刻完立刻弹界面看，右键已刻碑也走它）。
     *
     * <h2>为什么必须做成界面，不能继续往聊天栏打</h2>
     * 用户 2026-11-28 原话："刻上之后就没有 ui 了，什么狗屎，而且以后
     * 墓志铭会很长，你也要就这样放聊天框吗，蠢死了，顾头不顾尾。"
     * 聊天栏是**流水账**：十条碑文会把玩家的对话全顶掉，而且几个 tick 后
     * 就滚走了，玩家想再看一遍只能再右键一次。<b>碑文是"一件东西"，
     * 该有一个能停下来读的地方。</b>
     *
     * <h2>为什么发"行数组"而不是一个长字符串</h2>
     * 界面要<b>按宽度自动折行</b>（中文没有空格，机器折行和人工排版结果
     * 不一样）。发一个字符串客户端自己 split，等于把"一行是什么"这件事
     * 在客户端重定义一遍；发数组则让服务端（{@code GraveText} 的唯一口径）
     * 决定<b>逻辑分段</b>，客户端只负责在每段内折行。<b>分段是内容，
     * 折行是排版</b>——两件事不能混。
     *
     * <p>名字单独一个字段：它是标题，字号/颜色/位置都跟正文不同，
     * 混在数组里客户端就得猜"第一行是不是名字"。
     */
    public record GraveEpitaphPayload(net.minecraft.core.BlockPos pos, String name,
            java.util.List<net.minecraft.network.chat.Component> lines)
            implements CustomPacketPayload {

        public static final Type<GraveEpitaphPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "grave_epitaph"));

        public static final StreamCodec<ByteBuf, GraveEpitaphPayload> STREAM_CODEC =
                StreamCodec.of(
                        (buf, payload) -> {
                            net.minecraft.core.BlockPos.STREAM_CODEC.encode(buf, payload.pos());
                            ByteBufCodecs.STRING_UTF8.encode(buf, payload.name());
                            ByteBufCodecs.VAR_INT.encode(buf, payload.lines().size());
                            for (var line : payload.lines()) {
                                net.minecraft.network.chat.ComponentSerialization
                                        .TRUSTED_CONTEXT_FREE_STREAM_CODEC.encode(buf, line);
                            }
                        },
                        buf -> {
                            var pos = net.minecraft.core.BlockPos.STREAM_CODEC.decode(buf);
                            String name = ByteBufCodecs.STRING_UTF8.decode(buf);
                            int n = ByteBufCodecs.VAR_INT.decode(buf);
                            java.util.List<net.minecraft.network.chat.Component> lines =
                                    new java.util.ArrayList<>(n);
                            for (int i = 0; i < n; i++) {
                                lines.add(net.minecraft.network.chat.ComponentSerialization
                                        .TRUSTED_CONTEXT_FREE_STREAM_CODEC.decode(buf));
                            }
                            return new GraveEpitaphPayload(pos, name, java.util.List.copyOf(lines));
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

    /** C → S：请求重发炼金目录（弹层打开时自愈式拉取，不依赖登录时序）。 */
    public record AlchemyCatalogRequestPayload() implements CustomPacketPayload {

        public static final AlchemyCatalogRequestPayload INSTANCE = new AlchemyCatalogRequestPayload();

        public static final Type<AlchemyCatalogRequestPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "alchemy_catalog_request"));

        public static final StreamCodec<ByteBuf, AlchemyCatalogRequestPayload> STREAM_CODEC =
                StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * C → S：炼金订单（订单键 alch|&lt;potion&gt;|&lt;none|long|strong&gt;）。
     * 服务端复核职业与目录存在性（AlchemyCatalog.resolve 反查拒绝野键）。
     */
    public record AlchemistOrderPayload(int entityId, String orderKey)
            implements CustomPacketPayload {

        public static final Type<AlchemistOrderPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "alchemist_order"));

        public static final StreamCodec<ByteBuf, AlchemistOrderPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, AlchemistOrderPayload::entityId,
                        ByteBufCodecs.STRING_UTF8, AlchemistOrderPayload::orderKey,
                        AlchemistOrderPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * C → S：烟火订单（订单键 {@code fw|<火药n>|<星1>|…}，完整指定制——
     * 星列表编码进一个键字符串，按钮协议 int id 装不下）。
     * 服务端复核职业与 8 格约束（FireworksCatalog.parse 拒绝野键/超约束）。
     */
    public record FireworksOrderPayload(int entityId, String orderKey)
            implements CustomPacketPayload {

        public static final Type<FireworksOrderPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "fireworks_order"));

        public static final StreamCodec<ByteBuf, FireworksOrderPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, FireworksOrderPayload::entityId,
                        ByteBufCodecs.STRING_UTF8, FireworksOrderPayload::orderKey,
                        FireworksOrderPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * C → S：酒吧菜单板编辑（#86 升格，2026-11-21 拍板）。op：0=set（itemId+
     * target，UI 限 1~32 服务端再钳）、1=remove、2=clear（回落不维护——#108）。
     * 服务端复核：板在镇领地内 + 板在酒馆区内 + 城镇成员（与命令同口径），
     * 全过才改 {@code Town.tavernMenu} 并回快照。
     */
    public record TavernMenuEditPayload(int op, String itemId, int target, BlockPos boardPos)
            implements CustomPacketPayload {

        public static final Type<TavernMenuEditPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "tavern_menu_edit"));

        public static final StreamCodec<ByteBuf, TavernMenuEditPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, TavernMenuEditPayload::op,
                        ByteBufCodecs.STRING_UTF8, TavernMenuEditPayload::itemId,
                        ByteBufCodecs.VAR_INT, TavernMenuEditPayload::target,
                        BlockPos.STREAM_CODEC, TavernMenuEditPayload::boardPos,
                        TavernMenuEditPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * S → C：酒吧菜单快照（菜单板右键开 UI / 编辑后刷新）。条目
     * {@code itemId|target|stock|exempt}（exempt=闭包豁免项标签，#86 排产
     * 口径）——库存<b>服务端算好下发</b>（{@code TavernkeepGoal.menuStockOf}，
     * 排产同源口径），客户端不算账（口径分裂防坑）。
     */
    public record TavernMenuSnapshotPayload(boolean editable, boolean open,
            BlockPos boardPos, java.util.List<String> entries)
            implements CustomPacketPayload {

        public static final Type<TavernMenuSnapshotPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "tavern_menu_snapshot"));

        public static final StreamCodec<ByteBuf, TavernMenuSnapshotPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL, TavernMenuSnapshotPayload::editable,
                        ByteBufCodecs.BOOL, TavernMenuSnapshotPayload::open,
                        BlockPos.STREAM_CODEC, TavernMenuSnapshotPayload::boardPos,
                        stringList(), TavernMenuSnapshotPayload::entries,
                        TavernMenuSnapshotPayload::new);

        private static StreamCodec<ByteBuf, java.util.List<String>> stringList() {
            return StreamCodec.of(
                    (buf, list) -> {
                        ByteBufCodecs.VAR_INT.encode(buf, list.size());
                        for (String e : list) {
                            ByteBufCodecs.STRING_UTF8.encode(buf, e);
                        }
                    },
                    buf -> {
                        int n = ByteBufCodecs.VAR_INT.decode(buf);
                        java.util.List<String> out = new java.util.ArrayList<>(n);
                        for (int i = 0; i < n; i++) {
                            out.add(ByteBufCodecs.STRING_UTF8.decode(buf));
                        }
                        return out;
                    });
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * S → C：炼金目录同步（客户端 PotionBrewing 是 vanilla 自举实例，
     * 反查必须服务端做——条目 "potionId|hasLong|hasStrong"）。
     */
    public record AlchemyCatalogSyncPayload(java.util.List<String> entries)
            implements CustomPacketPayload {

        public static final Type<AlchemyCatalogSyncPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "alchemy_catalog_sync"));

        public static final StreamCodec<ByteBuf, AlchemyCatalogSyncPayload> STREAM_CODEC = StreamCodec.of(
                (buf, payload) -> {
                    ByteBufCodecs.VAR_INT.encode(buf, payload.entries().size());
                    for (String e : payload.entries()) {
                        ByteBufCodecs.STRING_UTF8.encode(buf, e);
                    }
                },
                buf -> {
                    int n = ByteBufCodecs.VAR_INT.decode(buf);
                    java.util.List<String> entries = new java.util.ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        entries.add(ByteBufCodecs.STRING_UTF8.decode(buf));
                    }
                    return new AlchemyCatalogSyncPayload(entries);
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * C → S：请求重发皮肤目录（自愈式拉取，同炼金目录请求——不依赖登录时序）。
     */
    public record SkinCatalogRequestPayload() implements CustomPacketPayload {

        public static final SkinCatalogRequestPayload INSTANCE = new SkinCatalogRequestPayload();

        public static final Type<SkinCatalogRequestPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "skin_catalog_request"));

        public static final StreamCodec<ByteBuf, SkinCatalogRequestPayload> STREAM_CODEC =
                StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * S → C：皮肤目录同步（条目 "id|贴图路径|slim(0/1)"——贴图与 slim 是客户端
     * 选模型、查贴图的全部所需；author/tags 服务端留档，不同步省流量）。
     */
    public record SkinCatalogSyncPayload(java.util.List<String> entries)
            implements CustomPacketPayload {

        public static final Type<SkinCatalogSyncPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "skin_catalog_sync"));

        public static final StreamCodec<ByteBuf, SkinCatalogSyncPayload> STREAM_CODEC = StreamCodec.of(
                (buf, payload) -> {
                    ByteBufCodecs.VAR_INT.encode(buf, payload.entries().size());
                    for (String e : payload.entries()) {
                        ByteBufCodecs.STRING_UTF8.encode(buf, e);
                    }
                },
                buf -> {
                    int n = ByteBufCodecs.VAR_INT.decode(buf);
                    java.util.List<String> entries = new java.util.ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        entries.add(ByteBufCodecs.STRING_UTF8.decode(buf));
                    }
                    return new SkinCatalogSyncPayload(entries);
                });

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

    /** 武器偏好切换：校验镇民存在且是战士，循环切一档并回显。 */
    private static void handleWeaponPref(WeaponPrefPayload payload, IPayloadContext ctx) {
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
            var pref = resident.getJobState().weaponPref().next();
            resident.applyWeaponPref(pref);
            serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "msg.bmfstown.work.weapon_pref_set",
                    net.minecraft.network.chat.Component.translatable(
                            "gui.bmfstown.resident.work.weapon."
                                    + pref.name().toLowerCase(java.util.Locale.ROOT))), true);
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

    /** 跟随见习确认：校验学者身份与领域对口后指定跟随目标。 */
    private static void handlePracticeFollow(PracticeFollowPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer serverPlayer)) {
                return;
            }
            if (!(serverPlayer.level().getEntity(payload.scholarId())
                    instanceof com.bmf.bmfstown.entity.Resident scholar)
                    || !(serverPlayer.level().getEntity(payload.targetId())
                    instanceof com.bmf.bmfstown.entity.Resident target)) {
                return;
            }
            if (scholar.getProfession() != com.bmf.bmfstown.entity.profile.ProfessionType.SCHOLAR) {
                return;
            }
            var content = scholar.getWritingBook()
                    .get(com.bmf.bmfstown.ModDataComponents.BOOK_CONTENT.get());
            if (!(content != null
                    && content.draft() instanceof com.bmf.bmfstown.book.BookDraft.Perk perk
                    && content.isStuck())) {
                return; // 没有卡点的 perk 书：拒绝（客户端按钮应不可用）
            }
            if (target.getProfession() == com.bmf.bmfstown.entity.profile.ProfessionType.NONE
                    || !perk.professionId().equals(
                            target.getProfession().getSerializedName())) {
                return; // 领域不对口：拒绝（客户端清单已过滤，这里双保险）
            }
            scholar.setFollowResidentId(target.getUUID());
            scholar.syncClient();
            serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "msg.bmfstown.practice.follow",
                    scholar.getName().getString(),
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

    /** 履历页：请求某个居民的一生统计（打开页面时发一次）。 */
    private static void handleLedgerRequest(LedgerRequestPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player player = ctx.player();
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return;
            }
            if (!(serverPlayer.level().getEntity(payload.entityId())
                    instanceof com.bmf.bmfstown.entity.Resident resident)) {
                return;
            }
            sendLedger(serverPlayer, resident);
        });
    }

    /**
     * 把履历数据发给一个玩家。
     *
     * <p><b>天数在这里算、在客户端不许再算</b>：客户端拿不到权威
     * {@code today}（没有 {@code ServerLevel}），自己算会跳变。
     *
     * <p><b>零项不发</b>：只传 {@code > 0} 的统计项，空表项不发——
     * 省字节，也让客户端不必再过滤一遍。
     */
    public static void sendLedger(ServerPlayer player,
            com.bmf.bmfstown.entity.Resident resident) {
        long today = com.bmf.bmfstown.calendar.TownCalendar.absoluteDay(
                player.serverLevel());
        var identity = resident.getIdentity();
        long birthDay = identity.birthDay();
        long arrivalDay = identity.arrivalDay();

        int daysInTown = com.bmf.bmfstown.entity.domain.AgingRolls.daysSince(today, arrivalDay);
        int ageYears = identity.ageYears(today);

        // ⚠ 不再算 daysAlive（2026-11-28 用户第二次打回："活了多久具体到天无人在意，
        //   年龄就能看到大致了"）。年龄已经有 ageYears，同一屏再报一个天数就是用
        //   两把尺量同一个量。生日给的是年龄给不出的那半条信息：哪一天长一岁。
        boolean hasBirthday = birthDay != com.bmf.bmfstown.entity.domain.AgingRolls.UNKNOWN_DAY;
        int birthYear = 0;
        int birthMonth = 0;
        int birthDom = 0;
        if (hasBirthday) {
            // 生日是从今天往前推的投影，必然早于开镇（29 岁 ≈ 2784 天前），
            // 所以这里必须允许算出"开镇前"的日期——dateOf 全程 floorMod，负天数安全。
            var calendar = com.bmf.bmfstown.calendar.TownCalendar.get(player.serverLevel());
            var date = com.bmf.bmfstown.calendar.TownCalendar.dateOf(
                    calendar.epochDay(player.serverLevel()), birthDay);
            birthYear = date.year();
            birthMonth = date.month();
            birthDom = date.day();
        }

        java.util.Map<String, Integer> stats = new java.util.LinkedHashMap<>();
        // ① ledger 里的终身累计
        resident.getLedger().view().forEach((k, v) -> {
            if (v != null && v > 0) {
                stats.put(k, v);
            }
        });
        // ② 已经有了、但不进 ledger 的终身量（唯一真相源在 KnowledgeState）
        int read = resident.getKnowledge().totalBooksRead();
        if (read > 0) {
            stats.put(com.bmf.bmfstown.entity.domain.ResidentLedger.Keys.BOOKS_READ, read);
        }
        // ⚠ <b>天数类绝不进 stats</b>（2026-11-28 用户截图实测的残留 bug）。
        // 初版把 days_alive/days_in_town 也塞进这张表，可是：
        //   ① 它们已经在包体里各有一个独立字段（daysAlive/daysInTown），重复；
        //   ② 它们**没有 lang 键**（不是统计项，是表头），客户端对缺键的兜底
        //      是"标签置空"，于是履历页统计区多出一行**只有数字、没有名字**的
        //      "2829"——玩家截图里那个来路不明的数就是它；
        //   ③ 值有几千，客户端按值降序取前 N 条时必然霸占前几名，
        //      把"拆出的材料 3 / 击杀 1"挤到看不见。
        // 结论：天数是"时间"，不是"次数"。时间走表头那三行，统计区只放计数。

        player.connection.send(new LedgerDataPayload(resident.getId(),
                daysInTown, ageYears, birthYear, birthMonth, birthDom,
                hasBirthday, stats));
    }

    private static void handleLedgerData(LedgerDataPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.bmf.bmfstown.client.ClientLedgerState.accept(payload));
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

    /** 工人开工/停工切换：校验职业后翻转开关（炼金术士共用同一开关槽，v0.12 泛化）。 */
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
            var prof = resident.getProfession();
            if (prof != com.bmf.bmfstown.entity.profile.ProfessionType.WORKER
                    && prof != com.bmf.bmfstown.entity.profile.ProfessionType.ALCHEMIST
                    && prof != com.bmf.bmfstown.entity.profile.ProfessionType.FIREWORKSMAN) {
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

    /** 服务端把炼金目录（可酿药水+变体可用性）发给玩家。 */
    public static void sendAlchemyCatalogSync(ServerPlayer player) {
        var level = player.serverLevel();
        java.util.List<String> entries = new java.util.ArrayList<>();
        for (var r : com.bmf.bmfstown.alchemy.AlchemyCatalog.catalog(level)) {
            String id = net.minecraft.core.registries.BuiltInRegistries.POTION
                    .getKey(r.base().value()).toString();
            entries.add(id + "|" + (r.extended() != null ? "1" : "0")
                    + "|" + (r.strong() != null ? "1" : "0")
                    + "|" + (r.hasSplash() ? "1" : "0")
                    + "|" + (r.hasLingering() ? "1" : "0"));
        }
        player.connection.send(new AlchemyCatalogSyncPayload(entries));
    }

    /** C2S 处理：按请求重发目录（自愈）。 */
    private static void handleAlchemyCatalogRequest(AlchemyCatalogRequestPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer serverPlayer) {
                sendAlchemyCatalogSync(serverPlayer);
            }
        });
    }

    /** S2C 处理：写入客户端炼金目录缓存。 */
    private static void handleAlchemyCatalogSync(AlchemyCatalogSyncPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.bmf.bmfstown.client.ClientAlchemyCatalog.accept(payload.entries()));
    }

    /** 服务端把皮肤目录（id→贴图/slim）发给玩家。 */
    public static void sendSkinCatalogSync(ServerPlayer player) {
        java.util.List<String> entries = new java.util.ArrayList<>();
        for (var e : com.bmf.bmfstown.skin.SkinCatalog.entries()) {
            entries.add(e.id() + "|" + e.texture() + "|" + (e.slim() ? "1" : "0"));
        }
        player.connection.send(new SkinCatalogSyncPayload(entries));
    }

    /** C2S 处理：按请求重发皮肤目录（自愈）。 */
    private static void handleSkinCatalogRequest(SkinCatalogRequestPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer serverPlayer) {
                sendSkinCatalogSync(serverPlayer);
            }
        });
    }

    /** S2C 处理：写入客户端皮肤目录缓存。 */
    private static void handleSkinCatalogSync(SkinCatalogSyncPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.bmf.bmfstown.client.ClientSkinCatalog.accept(payload.entries()));
    }

    /** 炼金订单：校验职业与目录存在性（反查拒绝野键），落账。 */
    private static void handleAlchemistOrder(AlchemistOrderPayload payload, IPayloadContext ctx) {
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
                    != com.bmf.bmfstown.entity.profile.ProfessionType.ALCHEMIST) {
                serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "msg.bmfstown.work.need_worker"), true);
                return;
            }
            var recipe = com.bmf.bmfstown.alchemy.AlchemyCatalog.resolve(
                    serverPlayer.serverLevel(), payload.orderKey());
            if (recipe == null) {
                // 野键：可见拒绝（静默丢弃曾让"订单保存不上"无从排查，2026-11-06）
                BmfTown.LOGGER.warn("[BmfTown][炼金] {} 下单被拒（目录中无此键：{}）",
                        resident.getName().getString(), payload.orderKey());
                serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "msg.bmfstown.workshop.order_invalid"), true);
                return;
            }
            // 秘传门禁：未解锁的秘传订单可见拒绝（2026-11-07 修——之前目录与门禁解耦后
            // resolve 能过、canUse 过不了，NPC 会原地发呆）
            String modStr = payload.orderKey().split("\\|")[2];
            String secretId = com.bmf.bmfstown.alchemy.ModPotions.secretIdOf(
                    recipe.finalPotion(modStr));
            if (secretId != null
                    && !com.bmf.bmfstown.alchemy.AlchemyGate.isOpen(secretId)) {
                serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "msg.bmfstown.alchemy.secret_locked"), true);
                return;
            }
            resident.getJobState().setOrderItem(payload.orderKey());
            // 反查最终产物名做反馈
            String mod = payload.orderKey().split("\\|")[2];
            var finalPotion = recipe.finalPotion(mod);
            var name = finalPotion == null ? payload.orderKey()
                    : net.minecraft.network.chat.Component.translatable(
                            net.minecraft.world.item.alchemy.Potion.getName(
                                    java.util.Optional.of(finalPotion),
                                    "item.minecraft.potion.effect.")).getString();
            serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "msg.bmfstown.workshop.order_set", name), false);
        });
    }

    /** 烟火订单：校验职业与订单键合法性（parse 拒绝野键/超 8 格约束），落账。 */
    private static void handleFireworksOrder(FireworksOrderPayload payload, IPayloadContext ctx) {
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
                    != com.bmf.bmfstown.entity.profile.ProfessionType.FIREWORKSMAN) {
                serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "msg.bmfstown.work.need_worker"), true);
                return;
            }
            var order = com.bmf.bmfstown.fireworks.FireworksCatalog.parse(payload.orderKey());
            if (order == null) {
                // 野键/超约束：可见拒绝（静默丢弃曾让"订单保存不上"无从排查）
                BmfTown.LOGGER.warn("[BmfTown][烟火] {} 下单被拒（非法订单键：{}）",
                        resident.getName().getString(), payload.orderKey());
                serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "msg.bmfstown.workshop.order_invalid"), true);
                return;
            }
            resident.getJobState().setOrderItem(payload.orderKey());
            // 反馈：产物展示名（等价重放出真实组件烟花）
            var stack = com.bmf.bmfstown.fireworks.FireworksCatalog.displayStack(payload.orderKey());
            serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "msg.bmfstown.workshop.order_set",
                    stack.isEmpty() ? payload.orderKey() : stack.getHoverName()), false);
            serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "msg.bmfstown.workshop.order_set",
                    stack.isEmpty() ? payload.orderKey() : stack.getHoverName()), true);
        });
    }

    /** C2S 处理：酒吧菜单板编辑——板镇内+酒馆区内+成员三道闸（命令同口径），改账回快照。 */
    private static void handleTavernMenuEdit(TavernMenuEditPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)
                    || !(sp.level() instanceof net.minecraft.server.level.ServerLevel sl)) {
                return;
            }
            var town = com.bmf.bmfstown.block.TavernMenuBoardBlock.resolveTown(sl, payload.boardPos());
            if (town == null || !com.bmf.bmfstown.block.TavernMenuBoardBlock
                    .inTavernZone(town, payload.boardPos())) {
                return; // 板无归属/不在酒馆区：不给编辑（区外只读设计）
            }
            if (!town.isMember(sp.getUUID())) {
                sp.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "msg.bmfstown.town.not_member", town.name()), true);
                return;
            }
            switch (payload.op()) {
                case 0 -> {
                    if (payload.itemId() != null && !payload.itemId().isBlank()) {
                        // UI 上限 1~32（设计稿 §6.3）；命令链不限（测试/管理用）
                        town.setTavernMenuItem(payload.itemId(),
                                Math.max(1, Math.min(32, payload.target())));
                    }
                }
                case 1 -> town.removeTavernMenuItem(payload.itemId());
                case 2 -> town.clearTavernMenu();
                default -> { }
            }
            com.bmf.bmfstown.town.TownSaveData.get(sl).setDirty();
            sendTavernMenuSnapshot(sp, town, payload.boardPos(), true, false);
        });
    }

    /**
     * 构建并发送菜单快照（S2C）。条目 {@code itemId|target|stock|exempt}：
     * 库存走 {@code TavernkeepGoal.menuStockOf}（排产同源口径——板上显示 2/4
     * 时排产认的也是这个 2）；豁免标签=闭包减菜单直选（#86 二层酒/配料豁免）。
     */
    public static void sendTavernMenuSnapshot(ServerPlayer player,
            com.bmf.bmfstown.town.Town town, BlockPos boardPos,
            boolean editable, boolean open) {
        if (!(player.level() instanceof net.minecraft.server.level.ServerLevel sl)) {
            return;
        }
        java.util.Map<Item, Integer> menu = new java.util.LinkedHashMap<>();
        for (var e : town.tavernMenu().entrySet()) {
            var rl = net.minecraft.resources.ResourceLocation.tryParse(e.getKey());
            Item it = rl == null ? null : BuiltInRegistries.ITEM.get(rl);
            if (it != null && it != Items.AIR) {
                menu.put(it, e.getValue());
            }
        }
        java.util.Set<Item> closure = menu.isEmpty()
                ? java.util.Set.of()
                : com.bmf.bmfstown.entity.TavernkeepGoal.menuClosureOf(sl, menu);
        // 快照行 = 菜单直选项 + 闭包豁免项（target=0 暗行，#86 二层酒/配料豁免
        // 可视化——玩家不用懂闭包规则也能看懂酒保为什么顺手酿啤酒/肉干）
        java.util.Map<Item, Integer> bill = new java.util.LinkedHashMap<>(menu);
        for (Item it : closure) {
            bill.putIfAbsent(it, 0);
        }
        java.util.Map<Item, Integer> stock = bill.isEmpty()
                ? java.util.Map.of()
                : com.bmf.bmfstown.entity.TavernkeepGoal.menuStockOf(sl, town, bill);
        java.util.List<String> entries = new java.util.ArrayList<>();
        for (java.util.Map.Entry<Item, Integer> e : bill.entrySet()) {
            int stockN = stock.getOrDefault(e.getKey(), 0);
            boolean exempt = !menu.containsKey(e.getKey());
            entries.add(BuiltInRegistries.ITEM.getKey(e.getKey()) + "|"
                    + e.getValue() + "|" + stockN + "|" + (exempt ? "1" : "0"));
        }
        player.connection.send(new TavernMenuSnapshotPayload(editable, open,
                boardPos.immutable(), entries));
    }

    /** S2C 处理：菜单快照入客户端缓存；open=开板 UI，open=false 刷新已开 UI。 */
    private static void handleTavernMenuSnapshot(TavernMenuSnapshotPayload payload,
            IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            com.bmf.bmfstown.client.ClientTavernMenu.accept(payload.editable(),
                    payload.open(), payload.boardPos(), payload.entries());
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
                BmfTown.LOGGER.warn("[BmfTown][工人] {} 下单被拒（非白名单产物：{}）",
                        resident.getName().getString(), payload.product());
                serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "msg.bmfstown.workshop.order_invalid"), true);
                return;
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
                BmfTown.LOGGER.warn("[BmfTown][工人] {} 下单被拒（白名单内无配方：{}）",
                        resident.getName().getString(), payload.product());
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

    /** 手册城镇页「登记方块」：开关工坊登记会话（城镇级）。 */
    private static void handleHandbookRegisterSession(HandbookRegisterSessionPayload payload,
                                                      IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer serverPlayer) {
                TownManager.toggleRegisterSession(serverPlayer, payload.townId());
            }
        });
    }

    private static void handleZoneSelectSync(ZoneSelectSyncPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() ->
                com.bmf.bmfstown.client.TownHandbookClient.setZoneSelecting(payload.active()));
    }

    private static void handleRegisterSessionSync(RegisterSessionSyncPayload payload,
                                                  IPayloadContext ctx) {
        ctx.enqueueWork(() ->
                com.bmf.bmfstown.client.WorkshopRegisterClient.setSession(
                        payload.active(), payload.zoneId()));
    }

    private static void handleSnowfallSync(SnowfallSyncPayload payload, IPayloadContext ctx) {

        ctx.enqueueWork(() ->
                com.bmf.bmfstown.client.SnowfallClient.set(
                        payload.active(), payload.centerPacked(), payload.radius()));
    }


    /** 向整个维度广播魔法降雪状态（开始/结束）。 */
    public static void sendSnowfallSync(ServerLevel level, boolean active, long centerPacked, int radius) {
        PacketDistributor.sendToPlayersInDimension(level, new SnowfallSyncPayload(active, centerPacked, radius));
    }

    private static void handleUnlockSync(UnlockSyncPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() ->
                com.bmf.bmfstown.client.ClientUnlockState.handleSync(payload));
    }

    /** 向整个维度广播解锁状态全量快照（解锁瞬间 / 玩家登录时调）。 */
    /** 向整个维度广播解锁状态全量快照（按城镇分组；解锁瞬间 / 玩家登录时调）。 */
    public static void sendUnlockSync(ServerLevel level,
            java.util.Map<java.util.UUID, java.util.Set<String>> snapshot) {
        var towns = snapshot.entrySet().stream()
                .map(e -> new TownUnlockEntry(
                        e.getKey().toString(), java.util.List.copyOf(e.getValue())))
                .toList();
        PacketDistributor.sendToPlayersInDimension(level, new UnlockSyncPayload(towns));
    }

    private static void handleTimeSpeedSync(TimeSpeedSyncPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() ->
                com.bmf.bmfstown.client.ClientTimeFlow.handleSync(payload));
    }

    /** 向整个维度广播昼夜流速镜像（设置流速 / 玩家登录时调）。 */
    public static void sendTimeSpeedSync(ServerLevel level, float perTick) {
        PacketDistributor.sendToPlayersInDimension(level, new TimeSpeedSyncPayload(perTick));
    }

    /** 选区会话状态推送给客户端（会话开始/结束时调）。 */
    public static void sendZoneSelectSync(ServerPlayer player, boolean active) {
        PacketDistributor.sendToPlayer(player, new ZoneSelectSyncPayload(active));
    }

    /** 工坊登记会话状态推送给客户端（开始/结束/每次登记后调——后者顺带刷小框数据）。 */
    public static void sendRegisterSessionSync(ServerPlayer player, boolean active, String zoneId) {
        PacketDistributor.sendToPlayer(player, new RegisterSessionSyncPayload(active, zoneId));
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
    public record MoodThought(int day, int tone, String key, int marker, String arg)
            implements CustomPacketPayload {

        public MoodThought {
            if (arg != null && arg.isEmpty()) {
                arg = null; // 空串 = 无参数想法，还原 null 语义
            }
        }

        public static final StreamCodec<ByteBuf, MoodThought> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, MoodThought::day,
                        ByteBufCodecs.VAR_INT, MoodThought::tone,
                        ByteBufCodecs.STRING_UTF8, MoodThought::key,
                        ByteBufCodecs.VAR_INT, MoodThought::marker,
                        ByteBufCodecs.STRING_UTF8, MoodThought::encodedArg,
                        MoodThought::new);

        /** 无参数想法 arg 为 null，而 STRING 编码不接受 null——空串过渡。 */
        private String encodedArg() {
            return this.arg == null ? "" : this.arg;
        }

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

    // ==========================================================
    //  刻碑选人（2026-11-28 决策 #144）
    // ==========================================================

    /** 服务端：把"这块碑此刻能刻谁"发回去。 */
    private static void handleGravePickRequest(GravePickRequestPayload payload,
            IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)
                    || !(player.level() instanceof ServerLevel level)) {
                return;
            }
            sendGraveCandidates(player, payload.pos(), level);
        });
    }

    /** 客户端：收到碑文 → 开碑文界面（刻完那一刻看，以及右键已刻碑时看）。 */
    private static void handleGraveEpitaph(GraveEpitaphPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof net.minecraft.client.player.LocalPlayer) {
                com.bmf.bmfstown.client.GraveEpitaphScreen.openWith(payload);
            }
        });
    }

    /** 客户端：收到名单 → 开选人界面（名单为空时界面自己显示"没有人"）。 */
    private static void handleGravePickList(GravePickListPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof net.minecraft.client.player.LocalPlayer) {
                com.bmf.bmfstown.client.GravePickScreen.openWith(payload);
            }
        });
    }

    /**
     * 服务端：玩家在选人界面点了某人 → 刻到那块碑上。
     *
     * <p><b>三重复核，缺一不可</b>：
     * <ol>
     *   <li><b>位置还是墓碑</b>——名单发出到玩家点选之间，碑可能已被挖掉。
     *       不查就会把"一个人的碑位"写到一个已经不存在的位置，
     *       那块位置将来放任何方块都可能被误判成碑位。</li>
     *   <li><b>这块碑还没刻过人</b>——防连点两个名字，后者覆盖前者
     *       （一人一碑、一碑一人是 #143 定的）。</li>
     *   <li><b>这个 UUID 真的待葬</b>——别人的碑可能刚被刻上，
     *       名单是那一刻的快照。</li>
     * </ol>
     * 任一条不过就发聊天提示，不静默绑。
     */
    private static void handleGraveCarve(GraveCarvePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)
                    || !(player.level() instanceof ServerLevel level)) {
                return;
            }
            var pos = payload.pos();
            // 距离校验：方块交互包不带位置校验的话，改客户端就能刻任意远处的碑
            if (player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D,
                    pos.getZ() + 0.5D) > 64.0D) {
                player.displayClientMessage(Component.translatable(
                        "grave.bmfstown.too_far"), false);
                return;
            }
            var data = com.bmf.bmfstown.town.GraveyardData.get(level);
            // 三重复核全在 GraveCarving 里（可单测），这里只负责"查世界 + 发提示"
            var result = com.bmf.bmfstown.town.GraveCarving.check(data, pos,
                    level.getBlockState(pos).is(
                            com.bmf.bmfstown.block.ModBlocks.GRAVE_STONE.get()),
                    payload.who());
            if (result != com.bmf.bmfstown.town.GraveCarving.Result.OK) {
                player.displayClientMessage(
                        Component.translatable(
                                com.bmf.bmfstown.town.GraveCarving.messageKey(result)),
                        false);
                return;
            }
            var grave = data.find(payload.who());
            data.bindSlot(pos, grave.id());
            player.displayClientMessage(
                    Component.translatable("grave.bmfstown.carved", grave.name()), false);
            level.playSound(null, pos, net.minecraft.sounds.SoundEvents.STONE_PLACE,
                    net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, 0.8F);
            // 刻完立刻把碑文<b>开成界面</b>给玩家看。
            //
            // 2026-11-28 用户实测打回："刻上之后就没有 ui 了……以后墓志铭会很长，
            // 你也要就这样放聊天框吗，蠢死了，顾头不顾尾。"
            // 聊天栏是流水账：碑文越长越会把对话顶掉，而且滚走就看不到了。
            // 碑文是一件"东西"，要有一个能停下来读的地方 ⇒ 走 S→C 包开专用屏。
            sendEpitaph(player, pos, grave);
        });
    }

    /**
     * 把这个镇所有待葬者发给玩家。
     *
     * <p>镇按<b>碑的位置</b>解析，不是按玩家站位——玩家常站在镇界外一格立碑
     * （顺手、也合法），按站位解析会突然找不到镇、名单变空。
     *
     * <p>墓碑不在任何镇内（野外立的无名碑）时只列"不属于任何镇"的逝者，
     * 不跨镇串名单——不然野外一块碑能刻到别镇的人，坟墓就串门了。
     */
    public static void sendGraveCandidates(ServerPlayer player,
            net.minecraft.core.BlockPos pos, ServerLevel level) {
        var data = com.bmf.bmfstown.town.GraveyardData.get(level);
        var town = com.bmf.bmfstown.town.TownSaveData.get(level)
                .findContaining(level.dimension().location().toString(), pos, pos);
        java.util.UUID townId = town == null ? null : town.id();

        // 纪元取一次（会顺带落锚），后面每条生平都用它把 deathDay 换算成日期。
        long epochDay = com.bmf.bmfstown.calendar.TownCalendar.get(level).epochDay(level);
        java.util.List<GraveCandidate> list = new java.util.ArrayList<>();
        // 过滤规则只存在于 GraveyardData.unburiedOfTown（用例调的是同一个方法）
        for (var g : data.unburiedOfTown(townId)) {
            list.add(new GraveCandidate(g.id(), g.name(), g.deathDay(),
                    com.bmf.bmfstown.town.GraveText.causeShortKey(g),
                    profileOf(g, epochDay)));
        }
        player.connection.send(new GravePickListPayload(pos, java.util.List.copyOf(list)));
    }

    /**
     * 把一块碑的碑文发给玩家，客户端开碑文界面显示（刻完 / 右键已刻碑）。
     *
     * <p><b>一个出口</b>：刻完那一刻和以后右键看，必须看到同一份东西。
     * 原先两条路各写一遍（一条打聊天栏、一条打聊天栏），改一处漏一处
     * 就会出现"刻的时候和看的时候不一样"。现在都走这里。
     */
    public static void sendEpitaph(ServerPlayer player,
            net.minecraft.core.BlockPos pos,
            com.bmf.bmfstown.town.GraveyardData.Grave grave) {
        // 纪元必须在服务端取（客户端没有 ServerLevel），这里一次算好塞进行里。
        long epochDay = com.bmf.bmfstown.calendar.TownCalendar
                .get(player.serverLevel()).epochDay(player.serverLevel());
        player.connection.send(new GraveEpitaphPayload(pos, grave.name(),
                java.util.List.copyOf(
                        com.bmf.bmfstown.town.GraveText.epitaphLines(grave, epochDay))));
    }

    /**
     * 服务端把镇民的想法日志与偏好摘要发给玩家（打开面板时调用）。
     */
    public static void playMoodSync(ServerPlayer player,
            com.bmf.bmfstown.entity.Resident resident) {
        java.util.List<MoodThought> list = new java.util.ArrayList<>();
        for (var t : resident.getStatus().thoughts()) {
            list.add(new MoodThought(t.day(), t.tone(), t.key(), t.marker(), t.arg()));
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
        registrar.playToServer(WeaponPrefPayload.TYPE, WeaponPrefPayload.STREAM_CODEC,
                ModNetworking::handleWeaponPref);
        registrar.playToServer(HandbookRegisterSessionPayload.TYPE,
                HandbookRegisterSessionPayload.STREAM_CODEC,
                ModNetworking::handleHandbookRegisterSession);
        registrar.playToServer(WorkerOrderPayload.TYPE, WorkerOrderPayload.STREAM_CODEC,
                ModNetworking::handleWorkerOrder);
        registrar.playToServer(AlchemistOrderPayload.TYPE, AlchemistOrderPayload.STREAM_CODEC,
                ModNetworking::handleAlchemistOrder);
        registrar.playToServer(FireworksOrderPayload.TYPE, FireworksOrderPayload.STREAM_CODEC,
                ModNetworking::handleFireworksOrder);
        registrar.playToServer(TavernMenuEditPayload.TYPE, TavernMenuEditPayload.STREAM_CODEC,
                ModNetworking::handleTavernMenuEdit);
        registrar.playToClient(TavernMenuSnapshotPayload.TYPE, TavernMenuSnapshotPayload.STREAM_CODEC,
                ModNetworking::handleTavernMenuSnapshot);
        registrar.playToClient(AlchemyCatalogSyncPayload.TYPE, AlchemyCatalogSyncPayload.STREAM_CODEC,
                ModNetworking::handleAlchemyCatalogSync);
        registrar.playToServer(AlchemyCatalogRequestPayload.TYPE, AlchemyCatalogRequestPayload.STREAM_CODEC,
                ModNetworking::handleAlchemyCatalogRequest);
        registrar.playToClient(SkinCatalogSyncPayload.TYPE, SkinCatalogSyncPayload.STREAM_CODEC,
                ModNetworking::handleSkinCatalogSync);
        registrar.playToServer(SkinCatalogRequestPayload.TYPE, SkinCatalogRequestPayload.STREAM_CODEC,
                ModNetworking::handleSkinCatalogRequest);
        registrar.playToServer(WorkerTogglePayload.TYPE, WorkerTogglePayload.STREAM_CODEC,
                ModNetworking::handleWorkerToggle);
        registrar.playToServer(CensusRequestPayload.TYPE, CensusRequestPayload.STREAM_CODEC,
                ModNetworking::handleCensusRequest);
        registrar.playToServer(LedgerRequestPayload.TYPE, LedgerRequestPayload.STREAM_CODEC,
                ModNetworking::handleLedgerRequest);
        registrar.playToClient(LedgerDataPayload.TYPE, LedgerDataPayload.STREAM_CODEC,
                ModNetworking::handleLedgerData);
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
        registrar.playToServer(PracticeFollowPayload.TYPE, PracticeFollowPayload.STREAM_CODEC,
                ModNetworking::handlePracticeFollow);
        registrar.playToServer(HandbookAppendZonePayload.TYPE, HandbookAppendZonePayload.STREAM_CODEC,
                ModNetworking::handleHandbookAppendZone);
        registrar.playToClient(ZoneSelectSyncPayload.TYPE, ZoneSelectSyncPayload.STREAM_CODEC,
                ModNetworking::handleZoneSelectSync);
        registrar.playToClient(RegisterSessionSyncPayload.TYPE, RegisterSessionSyncPayload.STREAM_CODEC,
                ModNetworking::handleRegisterSessionSync);
        registrar.playToClient(SnowfallSyncPayload.TYPE, SnowfallSyncPayload.STREAM_CODEC,
                ModNetworking::handleSnowfallSync);
        registrar.playToClient(HandbookSyncPayload.TYPE, HandbookSyncPayload.STREAM_CODEC,
                ModNetworking::handleHandbookSync);
        registrar.playToClient(TauntSyncPayload.TYPE, TauntSyncPayload.STREAM_CODEC,
                ModNetworking::handleTauntSync);
        registrar.playToClient(UnlockSyncPayload.TYPE, UnlockSyncPayload.STREAM_CODEC,
                ModNetworking::handleUnlockSync);
        registrar.playToClient(TimeSpeedSyncPayload.TYPE, TimeSpeedSyncPayload.STREAM_CODEC,
                ModNetworking::handleTimeSpeedSync);
        registrar.playToClient(MoodSyncPayload.TYPE, MoodSyncPayload.STREAM_CODEC,
                ModNetworking::handleMoodSync);
        registrar.playToServer(GravePickRequestPayload.TYPE,
                GravePickRequestPayload.STREAM_CODEC,
                ModNetworking::handleGravePickRequest);
        registrar.playToClient(GravePickListPayload.TYPE,
                GravePickListPayload.STREAM_CODEC,
                ModNetworking::handleGravePickList);
        registrar.playToClient(GraveEpitaphPayload.TYPE,
                GraveEpitaphPayload.STREAM_CODEC,
                ModNetworking::handleGraveEpitaph);
        registrar.playToServer(GraveCarvePayload.TYPE, GraveCarvePayload.STREAM_CODEC,
                ModNetworking::handleGraveCarve);
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
                .get(player.serverLevel()).toBlob(player.serverLevel(), player.getUUID());
        PacketDistributor.sendToPlayer(player, new HandbookSyncPayload(blob, open));
    }
}
