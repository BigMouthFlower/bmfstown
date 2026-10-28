package com.bmf.bmfstown.town;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 城镇数据的世界存档（SavedData）。
 *
 * <h2>什么是 SavedData</h2>
 * 与方块实体、实体无关的"世界级"数据（比如末影龙战斗数据、地图）都用它：
 * 挂在世界数据仓库（DimensionDataStorage）下，随存档自动写盘/读盘，
 * 不用自己管文件。键名 {@link #DATA_NAME} 就是存档文件名。
 *
 * <h2>为什么存在主世界</h2>
 * 城镇数据跨维度有效（在主世界建的城镇，去下界也要能查到归属），
 * 而每个维度的数据仓库是独立的——所以统一挂主世界，Town 里记录维度字符串。
 *
 * <h2>为什么改数据要 setDirty</h2>
 * SavedData 靠脏标记决定要不要写盘。忘记标脏 → 内存里改了、盘上没写，
 * 退档就"丢失"。所有修改方法内部都 {@code setDirty()}，调用方不用操心。
 */
public class TownSaveData extends SavedData {

    public static final String DATA_NAME = "bmfstown_towns";

    private final Map<UUID, Town> towns = new HashMap<>();

    /** 取当前世界的城镇数据（没有就建一份新的）。永远挂主世界。 */
    public static TownSaveData get(ServerLevel anyLevel) {
        return anyLevel.getServer().overworld().getDataStorage()
                .computeIfAbsent(
                        new Factory<>(TownSaveData::new, TownSaveData::load),
                        DATA_NAME);
    }

    // ==========================================================
    //  修改（全部内部 setDirty）
    // ==========================================================

    /** 加入一个新城镇并标脏。 */
    public void add(Town town) {
        towns.put(town.id(), town);
        setDirty();
    }

    /**
     * 删除：zoneIdText 为 null 删整个城镇；否则按 zoneId 在全部城镇里找
     * 那一条子区域删掉（不依赖 townIdText——手册可能只传了区域 ID）。
     */
    public boolean remove(String townIdText, String zoneIdText) {
        if (zoneIdText == null) {
            Town town = findTown(townIdText);
            if (town == null) {
                return false;
            }
            towns.remove(town.id());
            setDirty();
            return true;
        }
        UUID zoneId = UUID.fromString(zoneIdText);
        for (Town town : towns.values()) {
            for (TownWandMode mode : TownWandMode.values()) {
                List<TownZone> list = town.zonesForWrite(mode);
                if (list.removeIf(z -> z.id().equals(zoneId))) {
                    setDirty();
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 删除城镇区块（v0.9.1 不连续选区；"B:" 目标用）。
     * 区块下还挂着功能区时拒绝（返回 false），先删功能区。
     */
    public boolean removeBlock(UUID townId, UUID blockId) {
        Town town = towns.get(townId);
        if (town == null) {
            return false;
        }
        for (TownWandMode mode : TownWandMode.values()) {
            for (TownZone zone : town.zones(mode)) {
                if (blockId.equals(zone.parentBlock())) {
                    return false; // 区块内还有功能区
                }
            }
        }
        // ⚠ 必须用可写列表：townBlocks() 返回不可变副本，removeIf 直接抛异常
        if (town.townBlocksForWrite().removeIf(b -> b.id().equals(blockId))) {
            setDirty();
            return true;
        }
        return false;
    }

    /** 改区块名（不依赖城镇 id，全域按区块 id 找——手册可能只传区块 ID）。 */
    public boolean renameBlockAny(String blockIdText, String newName) {
        UUID blockId = UUID.fromString(blockIdText);
        for (Town town : towns.values()) {
            List<TownZone> blocks = town.townBlocksForWrite();
            for (int i = 0; i < blocks.size(); i++) {
                TownZone b = blocks.get(i);
                if (b.id().equals(blockId)) {
                    blocks.set(i, new TownZone(b.id(), newName, b.min(), b.max(), b.parentBlock()));
                    setDirty();
                    return true;
                }
            }
        }
        return false;
    }

    /** 改城镇区块名（"B:" 目标用）。 */
    public boolean renameBlock(UUID townId, UUID blockId, String newName) {
        Town town = towns.get(townId);
        if (town == null) {
            return false;
        }
        List<TownZone> blocks = town.townBlocksForWrite();
        for (int i = 0; i < blocks.size(); i++) {
            TownZone b = blocks.get(i);
            if (b.id().equals(blockId)) {
                blocks.set(i, new TownZone(b.id(), newName, b.min(), b.max(), b.parentBlock()));
                setDirty();
                return true;
            }
        }
        return false;
    }

    /** 改名（城镇或子区域）。返回 false = 找不到目标。 */
    public boolean rename(String townIdText, String zoneIdText, String newName) {
        if (zoneIdText == null) {
            Town town = findTown(townIdText);
            if (town == null) {
                return false;
            }
            town.rename(newName);
            setDirty();
            return true;
        }
        UUID zoneId = UUID.fromString(zoneIdText);
        for (Town town : towns.values()) {
            for (TownWandMode mode : TownWandMode.values()) {
                List<TownZone> list = town.zonesForWrite(mode);
                for (int i = 0; i < list.size(); i++) {
                    TownZone zone = list.get(i);
                    if (zone.id().equals(zoneId)) {
                        // record 不可变：原位替换实现"改名"
                        list.set(i, new TownZone(zone.id(), newName, zone.min(), zone.max(), zone.parentBlock()));
                        setDirty();
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ==========================================================
    //  查询
    // ==========================================================

    /** 全部城镇，按创建时间排序（手册里新的排在后面）。 */
    public List<Town> allTowns() {
        List<Town> list = new ArrayList<>(towns.values());
        list.sort((a, b) -> Long.compare(a.createdAt(), b.createdAt()));
        return list;
    }

    public int townCount() {
        return towns.size();
    }

    /** 按 UUID 字符串找城镇；格式不对或不存在返回 null。 */
    public Town findTown(String idText) {
        try {
            return towns.get(UUID.fromString(idText));
        } catch (IllegalArgumentException e) {
            return null; // 手册传来的 ID 被裁剪/损坏，不炸，静默失败
        }
    }

    /** 与任一区块重叠的已存在城镇（同维度才算——跨维度天然不重叠）。 */
    public Town findOverlapping(String dimension, BlockPos min, BlockPos max) {
        for (Town town : towns.values()) {
            if (town.dimension().equals(dimension) && town.boxOverlaps(min, max)) {
                return town;
            }
            for (TownZone block : town.townBlocks()) {
                if (town.dimension().equals(dimension) && boxesOverlap(min, max, block)) {
                    return town;
                }
            }
        }
        return null;
    }

    private static boolean boxesOverlap(BlockPos min, BlockPos max, TownZone zone) {
        return min.getX() <= zone.max().getX() && max.getX() >= zone.min().getX()
                && min.getY() <= zone.max().getY() && max.getY() >= zone.min().getY()
                && min.getZ() <= zone.max().getZ() && max.getZ() >= zone.min().getZ();
    }

    /** 包含此方块（同维度）的城镇——子区域归属校验用；"城镇内"= 完整落在**任一**区块内。 */
    public Town findContaining(String dimension, BlockPos min, BlockPos max) {
        for (Town town : towns.values()) {
            if (!town.dimension().equals(dimension)) {
                continue;
            }
            TownZone probe = new TownZone(UUID.randomUUID(), "", min, max);
            if (probe.inside(town.min(), town.max())) {
                return town;
            }
            for (TownZone block : town.townBlocks()) {
                if (probe.inside(block.min(), block.max())) {
                    return town;
                }
            }
        }
        return null;
    }

    // ==========================================================
    //  序列化
    // ==========================================================

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        CompoundTag root = new CompoundTag();
        for (Town town : towns.values()) {
            root.put(town.id().toString(), town.save());
        }
        tag.put("Towns", root);
        return tag;
    }

    public static TownSaveData load(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        TownSaveData data = new TownSaveData();
        CompoundTag root = tag.getCompound("Towns");
        for (String key : root.getAllKeys()) {
            CompoundTag townTag = root.getCompound(key);
            if (townTag.hasUUID("Id")) {
                Town town = Town.load(townTag);
                data.towns.put(town.id(), town);
            }
        }
        return data;
    }

    // ==========================================================
    //  手册数据导出（发给客户端渲染 UI）
    // ==========================================================

    /**
     * 导出为文本行，供手册 UI 展示。
     *
     * <p>格式（每行一条，字段用 | 分隔）：
     * <pre>
     * T|城镇ID|名字|维度|owner|minX,minY,minZ|maxX,maxY,maxZ
     * Z|城镇ID|类别序号|区域ID|名字|min|max
     * </pre>
     *
     * <p>不用 NBT/JSON 编码器过网络，是因为这个包是"纯展示数据"，
     * 文本行直观、好调试（抓包直接能读），且不受 codec 组合数限制。
     */
    public String toBlob(net.minecraft.server.level.ServerLevel level) {
        StringBuilder sb = new StringBuilder();
        for (Town town : allTowns()) {
            sb.append("T|").append(town.id()).append('|').append(town.name())
                    .append('|').append(town.dimension()).append('|').append(town.ownerName())
                    .append('|').append(TownWandItem.fmt(town.min()))
                    .append('|').append(TownWandItem.fmt(town.max()))
                    .append('\n');
            // 追加区块行（v0.9.1 不连续选区）：B|城镇ID|区块ID|名字|min|max
            // 0 号主区块就是 T 行的 min/max 本体，不重复发
            for (TownZone block : town.townBlocks()) {
                sb.append("B|").append(town.id()).append('|').append(block.id())
                        .append('|').append(block.name())
                        .append('|').append(block.minText())
                        .append('|').append(block.maxText())
                        .append('\n');
            }
            // 居民行（手册"居民列表"用，v0.8.1）：M|城镇ID|实体ID|职业id|名字
            // 名字放最后（split 限段数），玩家重命名可能带 | 不至于炸解析
            // v0.9.1：扫描范围扩展到所有区块（追加区块里的镇民也要进列表）
            java.util.Set<Integer> seenResidents = new java.util.HashSet<>();
            java.util.List<net.minecraft.world.phys.AABB> blocks = new java.util.ArrayList<>();
            blocks.add(new net.minecraft.world.phys.AABB(
                    town.min().getX(), town.min().getY(), town.min().getZ(),
                    town.max().getX() + 1, town.max().getY() + 1, town.max().getZ() + 1));
            for (TownZone block : town.townBlocks()) {
                blocks.add(new net.minecraft.world.phys.AABB(
                        block.min().getX(), block.min().getY(), block.min().getZ(),
                        block.max().getX() + 1, block.max().getY() + 1, block.max().getZ() + 1));
            }
            for (net.minecraft.world.phys.AABB box : blocks) {
                for (com.bmf.bmfstown.entity.Resident r : level.getEntitiesOfClass(
                        com.bmf.bmfstown.entity.Resident.class, box,
                        r -> town.id().equals(r.getIdentity().townId()))) {
                    if (!seenResidents.add(r.getId())) {
                        continue; // 区块重叠时同一个人可能被扫到两次
                    }
                    sb.append("M|").append(town.id()).append('|').append(r.getId())
                            .append('|').append(r.getProfession().displayName().substring("profession.bmfstown.".length()))
                            .append('|').append(r.getName().getString())
                            .append('\n');
                }
            }
            for (TownWandMode mode : TownWandMode.values()) {
                if (mode == TownWandMode.TOWN) {
                    continue;
                }
                for (TownZone zone : town.zones(mode)) {
                    sb.append("Z|").append(town.id()).append('|').append(mode.ordinal())
                            .append('|').append(zone.id()).append('|').append(zone.name())
                            .append('|').append(zone.minText())
                            .append('|').append(zone.maxText());
                    // 住宅区附带"床数|已入住"——镇民面板和手册显示占用情况用。
                    // 其他类型补 0|0 占位，保证所有 Z 行字段数一致，解析不用分支。
                    if (mode == TownWandMode.HOUSING) {
                        sb.append('|').append(HousingZones.capacity(level, zone))
                                .append('|').append(HousingZones.occupantCount(level, zone));
                    } else {
                        sb.append("|0|0");
                    }
                    // 归属区块（v0.9.1）：null = 0 号主区块，发空段
                    sb.append('|').append(zone.parentBlock() == null ? "" : zone.parentBlock());
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }
}
