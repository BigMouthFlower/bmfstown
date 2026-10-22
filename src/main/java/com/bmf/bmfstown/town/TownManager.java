package com.bmf.bmfstown.town;

import com.bmf.bmfstown.network.ModNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * 城镇业务的唯一入口：创建、归属校验、改名、删除。
 *
 * <h2>为什么逻辑收在这里而不是散在物品/网络层</h2>
 * 魔杖确认、手册按钮最终都做同一件事——改 {@link TownSaveData}。
 * 收口在一个类里，规则（比如"子区域必须在城镇内"）只写一遍，
 * 哪个入口来调都不会漏。
 *
 * <h2>校验原则：服务端说了算</h2>
 * 手册 UI 只是"发起请求的遥控器"。改数据的每一个请求都在这里
 * 重新校验（ID 是否存在、名字是否合法），客户端不可信。
 */
public final class TownManager {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** 名字最长 24 个字，防止刷屏。 */
    private static final int MAX_NAME_LENGTH = 24;

    /** 平铺子区域自动向上扩的格数（容纳放在地板上的床/箱子）。 */
    private static final int FLAT_ZONE_EXPAND = 4;

    private TownManager() {
    }

    // ==========================================================
    //  创建
    // ==========================================================

    /**
     * 魔杖确认创建城镇。成功后清空魔杖选点，并给客户端推一份
     * 手册数据（open=true 让手册自动弹出，玩家立刻看到新城镇）。
     */
    public static void tryCreateTown(ServerPlayer player, BlockPos a, BlockPos b) {
        ServerLevel level = player.serverLevel();
        TownSaveData data = TownSaveData.get(level);

        Town overlap = data.findOverlapping(level.dimension().location().toString(), a, b);
        if (overlap != null) {
            player.displayClientMessage(Component.translatable(
                    "msg.bmfstown.town.overlap", overlap.name()), false);
            return;
        }

        // 默认名是"数据"不是"文案"——定下来就存进存档了，
        // 所以在服务端取译文定稿（单人 = 客户端语言；专用服 = 服务端语言）
        String name = Component.translatable("name.bmfstown.town.default",
                data.townCount() + 1).getString();
        // 领地是"立柱"：Y 方向自动扩到世界全高。
        // 玩家选点基本都在地面上点两下（Y 范围只有 1 格），如果领地也
        // 只有 1 格高，房子里放的床/箱子全都"不在领地内"——这正是
        // "子区域明明贴着边却提示不在城镇里"的根源（子区域往上选就
        // 超出了领地的天花板）。立柱语义下 X/Z 贴边即可，Y 永远够用。
        BlockPos townMin = new BlockPos(Math.min(a.getX(), b.getX()), level.getMinBuildY(),
                Math.min(a.getZ(), b.getZ()));
        BlockPos townMax = new BlockPos(Math.max(a.getX(), b.getX()), level.getMaxBuildY() - 1,
                Math.max(a.getZ(), b.getZ()));
        Town town = new Town(UUID.randomUUID(), name,
                player.getName().getString(),
                level.dimension().location().toString(), townMin, townMax);
        data.add(town);

        player.displayClientMessage(Component.translatable(
                "msg.bmfstown.town.created", name,
                TownWandItem.fmt(town.min()), TownWandItem.fmt(town.max())), false);
        ModNetworking.sendHandbookSync(player, true);
    }

    /**
     * 魔杖确认创建子区域（就餐区/住宅/工作区）。
     * 规则：整个选区必须完整落在同维度某个城镇的外框内。
     */
    public static void tryCreateZone(ServerPlayer player, TownWandMode mode, BlockPos a, BlockPos b) {
        ServerLevel level = player.serverLevel();
        TownSaveData data = TownSaveData.get(level);

        Town town = data.findContaining(level.dimension().location().toString(), a, b);
        if (town == null) {
            player.displayClientMessage(Component.translatable(
                    "msg.bmfstown.zone.need_town",
                    Component.translatable(mode.displayName()),
                    Component.translatable(mode.displayName())), false);
            return;
        }

        // 平铺选区（两点同高）自动向上扩几格：玩家习惯沿着地板点两下，
        // 但床/箱子都放在地板"上面"一格——不扩的话扫描区域里永远找不到它们。
        // 非平铺（明确选了高度）的选区不动，尊重玩家输入。
        BlockPos low = a.getY() <= b.getY() ? a : b;
        BlockPos high = a.getY() <= b.getY() ? b : a;
        if (low.getY() == high.getY()) {
            high = new BlockPos(high.getX(), high.getY() + FLAT_ZONE_EXPAND, high.getZ());
        }
        String name = Component.translatable("name.bmfstown.zone.default",
                Component.translatable(mode.displayName()).getString(),
                town.zones(mode).size() + 1).getString();
        TownZone created = new TownZone(UUID.randomUUID(), name, low, high);
        town.addZone(mode, created);
        data.setDirty();
        // 排障好帮手：范围对不对，一眼从日志看出（历史上 1x1x1 的锅不止一次）
        LOGGER.info("[BmfTown] {} 创建 {}「{}」范围 {}~{}",
                player.getName().getString(),
                Component.translatable(mode.displayName()).getString(), name,
                created.min().toShortString(), created.max().toShortString());

        player.displayClientMessage(Component.translatable(
                "msg.bmfstown.zone.created", town.name(),
                Component.translatable(mode.displayName()), name), false);
        ModNetworking.sendHandbookSync(player, true);
    }

    // ==========================================================
    //  手册操作（改名/删除）
    // ==========================================================

    /** ID 约定："T:uuid" = 城镇，"Z:uuid" = 子区域。 */
    public static void rename(ServerPlayer player, String targetId, String newName) {
        String clean = newName == null ? "" : newName.trim();
        if (clean.isEmpty()) {
            player.displayClientMessage(Component.translatable("msg.bmfstown.name.empty"), false);
            return;
        }
        if (clean.length() > MAX_NAME_LENGTH) {
            clean = clean.substring(0, MAX_NAME_LENGTH);
        }

        TownSaveData data = TownSaveData.get(player.serverLevel());
        if (targetId.startsWith("T:")) {
            if (!data.rename(targetId.substring(2), null, clean)) {
                return; // 目标已不存在（可能被别人删了），静默忽略
            }
        } else if (targetId.startsWith("Z:")) {
            // 改子区域：townId 不参与查找，按 zoneId 全域找
            if (!data.rename("-", targetId.substring(2), clean)) {
                return;
            }
        } else {
            return;
        }
        ModNetworking.sendHandbookSync(player, false); // 刷新打开中的手册
    }

    public static void delete(ServerPlayer player, String targetId) {
        TownSaveData data = TownSaveData.get(player.serverLevel());
        boolean ok;
        if (targetId.startsWith("T:")) {
            ok = data.remove(targetId.substring(2), null);
        } else if (targetId.startsWith("Z:")) {
            ok = data.remove("-", targetId.substring(2));
        } else {
            return;
        }
        if (ok) {
            ModNetworking.sendHandbookSync(player, false);
        }
    }
}
