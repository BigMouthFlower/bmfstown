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
        Town town = new Town(UUID.randomUUID(), name,
                player.getName().getString(),
                level.dimension().location().toString(), a, b);
        data.add(town);

        player.displayClientMessage(Component.translatable(
                "msg.bmfstown.town.created", name,
                TownWandItem.fmt(town.min()), TownWandItem.fmt(town.max())), false);
        if (town.min().getY() == town.max().getY()) {
            player.displayClientMessage(Component.translatable(
                    "msg.bmfstown.zone.flat_hint"), false);
        }
        ModNetworking.sendHandbookSync(player, true);
    }

    /**
     * 魔杖确认创建子区域（就餐区/住宅/工作区）。
     * 规则：整个选区必须完整落在同维度某城镇的**某个区块**（0 号外框或追加区块）内，
     * 功能区归属于该区块（v0.9.1 不连续选区）。
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
        // 判定归属区块：0 号外框优先，其次追加区块
        TownZone probe = new TownZone(UUID.randomUUID(), "", a, b);
        UUID parentBlock = null;
        if (!probe.inside(town.min(), town.max())) {
            for (TownZone block : town.townBlocks()) {
                if (probe.inside(block.min(), block.max())) {
                    parentBlock = block.id();
                    break;
                }
            }
        }

        // 默认名是"数据"不是"文案"——定下来就存进存档了，
        // 所以在服务端取译文定稿（单人 = 客户端语言；专用服 = 服务端语言）
        String kindName = Component.translatable(mode.displayName()).getString();
        String name = Component.translatable("name.bmfstown.zone.default",
                kindName, town.zones(mode).size() + 1).getString();
        TownZone created = new TownZone(UUID.randomUUID(), name, a, b, parentBlock);
        town.addZone(mode, created);
        data.setDirty();
        // 排障好帮手：范围对不对，一眼从日志看出（历史上 1x1x1 的锅不止一次）
        LOGGER.info("[BmfTown] {} 创建 {}「{}」范围 {}~{}",
                player.getName().getString(),
                kindName, name,
                created.min().toShortString(), created.max().toShortString());
        // 两点同高 = 薄片选区：床/箱子都放在地板"上面"一格，薄片里扫不到。
        // 不替玩家改范围，只提醒一句，要不要重选由玩家决定。
        if (created.min().getY() == created.max().getY()) {
            player.displayClientMessage(Component.translatable(
                    "msg.bmfstown.zone.flat_hint"), false);
        }

        player.displayClientMessage(Component.translatable(
                "msg.bmfstown.zone.created", town.name(),
                Component.translatable(mode.displayName()), name), false);
        ModNetworking.sendHandbookSync(player, true);
    }

    // ==========================================================
    //  追加城镇区块（v0.9.1 不连续选区）
    // ==========================================================

    /**
     * 手册"增加选区"按钮：开启空手选区会话；**会话已激活时再点 = 取消**（可靠的取消通道，
     * 不依赖潜行竞速）。之后空手右键两个角 → {@link #confirmAppendBlock}；Shift+右键方块也可取消。
     */
    public static void startAppendBlock(ServerPlayer player, String townIdText) {
        if (ZoneSelectSessions.cancel(player.getUUID())) {
            player.displayClientMessage(Component.translatable(
                    "msg.bmfstown.zoneblock.canceled"), true);
            return;
        }
        Town town = TownSaveData.get(player.serverLevel()).findTown(townIdText);
        if (town == null) {
            player.displayClientMessage(Component.translatable(
                    "msg.bmfstown.town.gone"), true);
            return;
        }
        if (!town.dimension().equals(player.serverLevel().dimension().location().toString())) {
            player.displayClientMessage(Component.translatable(
                    "msg.bmfstown.zoneblock.wrong_dim"), true);
            return;
        }
        ZoneSelectSessions.start(player.getUUID(), town.id());
        ModNetworking.sendZoneSelectSync(player, true);
        player.displayClientMessage(Component.translatable(
                "msg.bmfstown.zoneblock.started", town.name()), false);
        player.displayClientMessage(Component.translatable(
                "msg.bmfstown.zoneblock.hint"), false);
    }

    /**
     * 空手选满两个角后创建追加区块。
     * 规则：同维度；与**任何**城镇的**任何**区块（含自己的）不重叠。
     * 成功后会话结束、推手册数据（open=true 让手册自动弹回）。
     */
    public static void confirmAppendBlock(ServerPlayer player, java.util.UUID townId,
                                          BlockPos a, BlockPos b) {
        ServerLevel level = player.serverLevel();
        TownSaveData data = TownSaveData.get(level);
        Town town = data.findTown(townId.toString());
        if (town == null) {
            ZoneSelectSessions.cancel(player.getUUID());
            ModNetworking.sendZoneSelectSync(player, false);
            player.displayClientMessage(Component.translatable(
                    "msg.bmfstown.town.gone"), true);
            return;
        }

        Town overlap = data.findOverlapping(level.dimension().location().toString(), a, b);
        if (overlap != null && !overlap.id().equals(town.id())) {
            player.displayClientMessage(Component.translatable(
                    "msg.bmfstown.town.overlap", overlap.name()), false);
            return;
        }
        // 与自己已有区块重叠也拒绝（区块相叠没有语义）
        if (boxesTouchSelf(town, a, b)) {
            player.displayClientMessage(Component.translatable(
                    "msg.bmfstown.zoneblock.self_overlap"), true);
            return;
        }

        String name = Component.translatable("name.bmfstown.townblock.default",
                town.townBlocks().size() + 2).getString();
        TownZone created = new TownZone(java.util.UUID.randomUUID(), name, a, b);
        town.addTownBlock(created);
        data.setDirty();
        ZoneSelectSessions.cancel(player.getUUID());
        ModNetworking.sendZoneSelectSync(player, false);
        LOGGER.info("[BmfTown] {} 为城镇「{}」追加区块「{}」范围 {}~{}",
                player.getName().getString(), town.name(), name,
                created.min().toShortString(), created.max().toShortString());
        player.displayClientMessage(Component.translatable(
                "msg.bmfstown.zoneblock.created", town.name(), name,
                TownWandItem.fmt(created.min()), TownWandItem.fmt(created.max())), false);
        if (created.min().getY() == created.max().getY()) {
            player.displayClientMessage(Component.translatable(
                    "msg.bmfstown.zone.flat_hint"), false);
        }
        ModNetworking.sendHandbookSync(player, true);
    }

    /** 新选区是否与已有追加区块重叠。 */
    private static boolean boxesTouchSelf(Town town, BlockPos a, BlockPos b) {
        Town probe = new Town(java.util.UUID.randomUUID(), "检查用", "",
                town.dimension(), a, b);
        for (TownZone block : town.townBlocks()) {
            if (probe.boxOverlaps(block.min(), block.max())) {
                return true;
            }
        }
        return false;
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
        } else if (targetId.startsWith("B:")) {
            // 改城镇区块名（v0.9.1）：townId 不参与查找，全域按区块 id 找
            if (!data.renameBlockAny(targetId.substring(2), clean)) {
                return;
            }
        } else {
            return;
        }
        ModNetworking.sendHandbookSync(player, false); // 刷新打开中的手册
    }

    public static void delete(ServerPlayer player, String targetId) {
        TownSaveData data = TownSaveData.get(player.serverLevel());
        boolean ok = false;
        if (targetId.startsWith("T:")) {
            ok = data.remove(targetId.substring(2), null);
        } else if (targetId.startsWith("Z:")) {
            ok = data.remove("-", targetId.substring(2));
        } else if (targetId.startsWith("B:")) {
            // 删城镇区块：区块下还挂着功能区时拒绝并提示
            for (Town town : data.allTowns()) {
                for (TownZone block : town.townBlocks()) {
                    if (targetId.substring(2).equals(block.id().toString())) {
                        if (town.countZonesInBlock(block.id()) > 0) {
                            player.displayClientMessage(Component.translatable(
                                    "msg.bmfstown.zoneblock.has_children"), false);
                            return;
                        }
                        ok = data.removeBlock(town.id(), block.id());
                        break;
                    }
                }
            }
        } else {
            return;
        }
        if (ok) {
            ModNetworking.sendHandbookSync(player, false);
        }
    }
}
