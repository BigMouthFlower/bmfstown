package com.bmf.bmfstown.entity;

import com.bmf.bmfstown.ModDataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * netmusic（酒石酸菌 NetMusic，网易云音乐 mod）联动门面——<b>零引用</b>接入。
 *
 * <h2>为什么零引用而不是 compileOnly 依赖</h2>
 * netmusic 是<b>可选</b>的运行时同伴 mod（和 BAC 同款处境）：用户装了才有这个玩法，
 * 不装则整条通道静默跳过，本 mod 一切功能照旧。因此本类绝不 import 任何
 * {@code com.github.tartaricacid.*} 类，全部通过三条零依赖通道访问：
 * <ol>
 *   <li><b>注册表</b>：{@code netmusic:music_player}（唱片机方块）/
 *       {@code netmusic:music_cd}（唱片物品）按 id 直查——不在则返回 null；</li>
 *   <li><b>原版公开 API</b>：唱片机状态读方块比较器输出
 *       （{@code BlockState#getAnalogOutputSignal}，netmusic 实现 0=空 / 7=有盘没放 /
 *       15=播放中）；插/取唱片走它注册的通用 {@code ItemHandler.BLOCK} 能力；</li>
 *   <li><b>反射</b>：唯一绕不开的是"触发播放"（{@code TileEntityMusicPlayer#setPlayToClient}
 *       把流媒体消息广播给附近客户端）和读唱片歌曲信息（VIP 检查），两处反射封装在
 *       本类内部，方法签名对准 netmusic 1.1.x（NeoForge 1.21.1）。</li>
 * </ol>
 *
 * <h2>netmusic 侧的关键机制（1.1.5 反编译核对）</h2>
 * <ul>
 *   <li>音乐机方块实体单槽（slot 0）；播放中/有盘时槽位拒绝再插——<b>"不打断歌"
 *       是 mod 内建的</b>，我们只需要"槽空才插"；</li>
 *   <li>歌放完（放置默认单曲模式）自动停、盘留在槽里——收盘是我们的活；</li>
 *   <li>VIP 歌：mod 会拒插（仅玩家路径）。我们直插能力接口绕过了那道门，
 *       所以插之前必须自己查 {@code SongInfo.vip}，VIP 盘不碰（客户端流不动它）。</li>
 * </ul>
 *
 * <p>GameTest 环境不装 netmusic（坑15 同款处境）：netmusic 路径在 {@link #loaded()}
 * 为 false 时全部返回"无事发生"，验收靠实机；<b>原版唱片机路径无此限制</b>，
 * GameTest 可直接测（原版盘/原版机器不依赖任何 mod）。
 *
 * <h2>原版唱片机（1.21.1 vanilla，与 netmusic 双通道并存）</h2>
 * <ul>
 *   <li>机器识别：{@code level.getBlockEntity(pos) instanceof JukeboxBlockEntity}
 *       （原版全 public API，零反射）；netmusic 机器不是 JukeboxBlockEntity，互不冲突；</li>
 *   <li>盘判据：{@code DataComponents.JUKEBOX_PLAYABLE}（1.21 数据驱动唱片组件，
 *       原版全部唱片 + 部分 mod 盘通用，{@code canPlaceItem} 同口径）；</li>
 *   <li>播放中 = {@code getSongPlayer().getSong() != null}——注意原版比较器
 *       {@code getComparatorOutput} 只看槽里有没有盘、<b>不判播放</b>，与 netmusic
 *       的 0/7/15 口径不同，状态读取不走比较器；</li>
 *   <li>插入即开播：{@code setTheItem} 内部调 {@code songPlayer.play} 并自动维护
 *       HAS_RECORD 方块状态与比较器输出——无需手动触发；取盘
 *       {@code removeTheItem()} 同理自动停播清状态（调用方保证非播放中才取）。</li>
 * </ul>
 */
public final class TavernDj {

    /** netmusic 的 modid（{@code ModList.isLoaded} 的 key）。 */
    public static final String MODID = "netmusic";

    /** 唱片机对外的三种状态（比较器口径；NO_PLAYER=那格根本不是唱片机）。 */
    public enum MachineState { NO_PLAYER, EMPTY, DISC_IDLE, PLAYING }

    private static final String PLAYER_BLOCK_ID = "netmusic:music_player";
    private static final String CD_ITEM_ID = "netmusic:music_cd";

    // ---- 注册表缓存（查不到就保持 null，每次调用重查——map lookup，kegBlock 同款）----
    private static volatile Block playerBlockCache;
    private static volatile Item cdItemCache;

    // ---- 反射产物（懒解析，一次成功终身有效；失败也只试一次）----
    private static volatile boolean reflTried;
    private static volatile Method mGetSongInfo;
    private static volatile Class<?> songInfoClass;
    private static volatile Method mSetPlayToClient;
    private static volatile Method mMarkDirty;

    private TavernDj() {
    }

    /** netmusic 在不在（mod 未装时整条 DJ 通道静默关闭）。 */
    public static boolean loaded() {
        return ModList.get().isLoaded(MODID);
    }

    /** netmusic 唱片机方块（mod 不在 = null）。 */
    @Nullable
    public static Block musicPlayerBlock() {
        Block b = playerBlockCache;
        if (b == null) {
            b = BuiltInRegistries.BLOCK.getOptional(
                    ResourceLocation.fromNamespaceAndPath(MODID, "music_player")).orElse(null);
            if (b != null) {
                playerBlockCache = b;
            }
        }
        return b;
    }

    /** netmusic 唱片物品（mod 不在 = null）。 */
    @Nullable
    public static Item cdItem() {
        Item it = cdItemCache;
        if (it == null) {
            it = BuiltInRegistries.ITEM.getOptional(
                    ResourceLocation.fromNamespaceAndPath(MODID, "music_cd")).orElse(null);
            if (it != null) {
                cdItemCache = it;
            }
        }
        return it;
    }

    /** 这是不是一张可被 DJ 通道识别的唱片（netmusic 盘 或 原版系可播盘）。 */
    public static boolean isCd(ItemStack st) {
        if (st.isEmpty()) {
            return false;
        }
        if (st.has(DataComponents.JUKEBOX_PLAYABLE)) {
            return true; // 原版唱片与数据驱动唱片（含部分 mod 盘），无 mod 依赖
        }
        Item cd = cdItem();
        return cd != null && st.is(cd);
    }

    // ==========================================================
    //  机器类型（盘与机器必须同型配对，#102 追记）
    // ==========================================================

    /** 唱片机类型：原版唱片机 / netmusic 音乐机。 */
    public enum MachineKind { VANILLA, NETMUSIC }

    /** 这台唱片机的类型（原版 {@link JukeboxBlockEntity} 优先；其余按 netmusic 音乐机）。 */
    public static MachineKind kindOf(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof JukeboxBlockEntity
                ? MachineKind.VANILLA : MachineKind.NETMUSIC;
    }

    /**
     * 这张盘能不能放进指定<b>类型</b>的机器：原版机要 {@code JUKEBOX_PLAYABLE}
     * 组件；netmusic 机要 SongInfo（非 VIP、时长合法）。同型才可插——原版盘
     * 没有 SongInfo 进不了 netmusic 机；netmusic 盘没有可播组件进不了原版机。
     * {@link #cdPlayable(ItemStack)} 是"任意机器"的宽松版（选窖容器前的粗筛）。
     */
    public static boolean cdPlayableFor(MachineKind kind, ItemStack st) {
        if (st.isEmpty()) {
            return false;
        }
        if (kind == MachineKind.VANILLA) {
            return st.has(DataComponents.JUKEBOX_PLAYABLE);
        }
        Object info = songInfo(st);
        return info != null && playable(info);
    }

    // ==========================================================
    //  唱片机状态与槽位操作（原版能力接口，零引用）
    // ==========================================================

    /**
     * 唱片机当前状态：原版唱片机（{@link JukeboxBlockEntity}）优先分流，
     * 其次 netmusic（比较器 0=空 / 7=有盘没放 / 15=播放中）。
     * 注意原版比较器只看槽里有没有盘（不判播放），播放中必须读 songPlayer。
     */
    public static MachineState sense(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof JukeboxBlockEntity jbe) {
            if (jbe.getTheItem().isEmpty()) {
                return MachineState.EMPTY;
            }
            return jbe.getSongPlayer().getSong() != null
                    ? MachineState.PLAYING : MachineState.DISC_IDLE;
        }
        Block mb = musicPlayerBlock();
        if (mb == null || !level.getBlockState(pos).is(mb)) {
            return MachineState.NO_PLAYER;
        }
        int cmp = level.getBlockState(pos).getAnalogOutputSignal(level, pos);
        return switch (cmp) {
            case 15 -> MachineState.PLAYING;
            case 7 -> MachineState.DISC_IDLE;
            default -> MachineState.EMPTY;
        };
    }

    /** 唱片机槽位句柄（netmusic 用；mod 不在/方块不对 = null）。 */
    @Nullable
    private static IItemHandler invAt(Level level, BlockPos pos) {
        return level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
    }

    /** 看一眼槽里的盘（只读，不取）。 */
    public static ItemStack peekDisc(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof JukeboxBlockEntity jbe) {
            return jbe.getTheItem();
        }
        IItemHandler inv = invAt(level, pos);
        return inv == null ? ItemStack.EMPTY : inv.getStackInSlot(0);
    }

    /** 取出槽里的盘（放完收盘用；调用方保证不在播放中——原版取盘会停播）。 */
    public static ItemStack extractDisc(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof JukeboxBlockEntity jbe) {
            return jbe.getTheItem().isEmpty() ? ItemStack.EMPTY : jbe.removeTheItem();
        }
        IItemHandler inv = invAt(level, pos);
        return inv == null ? ItemStack.EMPTY : inv.extractItem(0, 1, false);
    }

    /**
     * 塞盘并开播：槽空才插（插满返回 false，绝不覆盖）。netmusic 路径插完调
     * setPlayToClient 广播；原版路径 {@code setTheItem} 本身即开播（songPlayer.play）
     * 并自动维护 HAS_RECORD 方块状态。调用方需先用 {@link #cdPlayable} 筛过
     * （netmusic 路径这里会兜底再查一次 VIP）。
     *
     * <p>netmusic 触发失败（反射断链等）时把盘<b>当场抽回</b>再返回 false——
     * 否则盘留在机器里、调用方又把它记回货列，一份变两份（复制物品，坑43）。
     * 原版路径 {@code setTheItem} 本身原子开播，无此风险。
     */
    public static boolean insertAndPlay(ServerLevel level, BlockPos pos, ItemStack disc) {
        if (level.getBlockEntity(pos) instanceof JukeboxBlockEntity jbe) {
            if (!jbe.getTheItem().isEmpty() || !disc.has(DataComponents.JUKEBOX_PLAYABLE)) {
                return false;
            }
            jbe.setTheItem(disc.copy()); // 交副本，所有权归机器（坑43 同源防御）
            return true;
        }
        Object info = songInfo(disc);
        if (info == null || !playable(info)) {
            return false;
        }
        IItemHandler inv = invAt(level, pos);
        if (inv == null || !inv.getStackInSlot(0).isEmpty()) {
            return false;
        }
        if (!inv.insertItem(0, disc, false).isEmpty()) {
            return false;
        }
        if (!triggerPlay(level, pos, info)) {
            inv.extractItem(0, 1, false); // 抽回，绝不把盘留在机器里
            return false;
        }
        return true;
    }

    // ==========================================================
    //  点歌标记（"这张盘是我们放出去的"凭证）
    // ==========================================================

    public static boolean hasDjMark(ItemStack st) {
        return st.getOrDefault(ModDataComponents.DJ_QUEUED.get(), false);
    }

    public static void markDj(ItemStack st) {
        st.set(ModDataComponents.DJ_QUEUED.get(), true);
    }

    // ==========================================================
    //  歌曲信息（反射 ItemMusicCD.SongInfo）
    // ==========================================================

    /**
     * 这张盘能不能放：netmusic 盘带 SongInfo、非 VIP、时长合法
     * （玩家手写的自制盘同样走这里）；原版系盘带 {@code JUKEBOX_PLAYABLE}
     * 组件即可（原版无 VIP 概念），<b>不依赖 netmusic 在场</b>。
     */
    public static boolean cdPlayable(ItemStack st) {
        if (st.isEmpty()) {
            return false;
        }
        if (st.has(DataComponents.JUKEBOX_PLAYABLE)) {
            return true;
        }
        Object info = songInfo(st);
        return info != null && playable(info);
    }

    /** 歌名（诊断日志用；拿不到就回退物品名）。 */
    public static String cdTitle(ItemStack st) {
        Object info = songInfo(st);
        if (info != null) {
            try {
                Object name = songInfoClass.getField("songName").get(info);
                if (name != null && !name.toString().isEmpty()) {
                    return name.toString();
                }
            } catch (ReflectiveOperationException ignored) {
                // 落回物品名
            }
        }
        return st.getHoverName().getString();
    }

    private static boolean playable(Object info) {
        try {
            Field vip = songInfoClass.getField("vip");
            if (vip.getBoolean(info)) {
                return false; // VIP 歌客户端流不动，mod 玩家路径也拒插
            }
            Field time = songInfoClass.getField("songTime");
            return time.getInt(info) > 0;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /**
     * 触发播放：调 mod 的 {@code TileEntityMusicPlayer#setPlayToClient(SongInfo)}
     * （内部会广播播放消息给附近客户端并开演）。失败=签名变了/环境异常，
     * 调用方负责把盘退回并拉长冷却。
     */
    private static boolean triggerPlay(Level level, BlockPos pos, Object info) {
        if (!ensureReflection()) {
            return false;
        }
        BlockEntity te = level.getBlockEntity(pos);
        if (te == null) {
            return false;
        }
        try {
            Object disc = songInfoClass.cast(info);
            mSetPlayToClient.invoke(te, disc);
            mMarkDirty.invoke(te);
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /** 读盘上的 SongInfo（不是 netmusic 盘 / 反射断链 = null）。 */
    @Nullable
    private static Object songInfo(ItemStack st) {
        if (!loaded() || st.isEmpty()) {
            return null;
        }
        if (!ensureReflection()) {
            return null;
        }
        try {
            return mGetSongInfo.invoke(null, st);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** 反射解析（只试一次，失败记死——mod 版本对不上别每 5 秒撞一次）。 */
    private static boolean ensureReflection() {
        if (reflTried) {
            return mSetPlayToClient != null;
        }
        reflTried = true;
        try {
            Class<?> cd = Class.forName("com.github.tartaricacid.netmusic.item.ItemMusicCD");
            mGetSongInfo = cd.getMethod("getSongInfo", ItemStack.class);
            songInfoClass = Class.forName("com.github.tartaricacid.netmusic.item.ItemMusicCD$SongInfo");
            Class<?> te = Class.forName(
                    "com.github.tartaricacid.netmusic.tileentity.TileEntityMusicPlayer");
            mSetPlayToClient = te.getMethod("setPlayToClient", songInfoClass);
            mMarkDirty = te.getMethod("markDirty");
            return true;
        } catch (Throwable t) {
            mGetSongInfo = null;
            songInfoClass = null;
            mSetPlayToClient = null;
            mMarkDirty = null;
            return false;
        }
    }
}
