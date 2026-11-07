package com.bmf.bmfstown.town;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一个城镇：外框（边界）+ 三类子区域。
 *
 * <h2>归属关系</h2>
 * 就餐区/住宅/工作区必须完全落在城镇外框内（见 {@link TownZone#inside}），
 * 这是"小区必须属于城镇"这条规则的数据保证——校验在创建时做，
 * 存档里的数据因此天然合法。
 *
 * <h2>子区域按模式分三张表</h2>
 * {@code zones} 的 key 是 {@link TownWandMode}，但 TOWN 永远不会出现
 * （城镇本身就是外框，不会"城镇里有城镇"）。
 */
public class Town {

    private final UUID id;
    private String name;
    /** 创建者名字，仅手册里展示用；权限控制以后再加。 */
    private final String ownerName;
    /** 所在维度，如 "minecraft:overworld"。跨维度不能选区。 */
    private final String dimension;
    /** 外框对角（含端点）。 */
    private final BlockPos min;
    private final BlockPos max;
    /** 三类子区域。 */
    private final Map<TownWandMode, List<TownZone>> zones = new EnumMap<>(TownWandMode.class);
    /**
     * 追加的城镇区块（v0.9.1 不连续选区）：min/max 外框是 0 号区块本体，
     * 这里存第 2 块起的不连续区块。镇民"在城镇中" = 在外框或任一追加区块内。
     */
    private final List<TownZone> townBlocks = new ArrayList<>();
    /** 创建时间戳——手册里"新的排在后面"就靠它排序。 */
    private final long createdAt;

    /**
     * 成员白名单（v0.11.1 领地权限）：UUID → 最近一次记录的名字。
     * 成员与创建者**完全同权**（子区域增删改/容器/门/破坏放置），唯一例外
     * 是解散城镇只归创建者。**空表 = 未启用保护**（老城镇向后兼容——
     * 保护随第一次 `/bmfstown town add` 启用）。
     */
    private final java.util.Map<UUID, String> members = new java.util.HashMap<>();
    /** 创建者 UUID（解散权限判定）。老档没有此字段（null）——解散回退为全体成员。 */
    private java.util.UUID ownerId;

    /**
     * 工坊登记表（v0.12 工人重做，用户拍板**城镇级**）：玩家手工登记的方块。
     * 统一池：容器（取料+入库共用）与岗位（工作站）分开两组坐标，
     * 但登记动作只有一个——右键什么类型的方块就自动进哪组。
     * 登记范围 = 城镇区域（主外框或任一追加区块）；不依赖任何子区域——
     * 工坊区对工人是纯手续（用户 22:40 拍板推翻"区级池"初版）。
     */
    public static final class WorkshopRegister {
        /** 已登记容器（取料与入库统一池），存 BlockPos.asLong()。 */
        public final java.util.HashSet<Long> containers = new java.util.HashSet<>();
        /** 已登记工作站（岗位），存 BlockPos.asLong()。 */
        public final java.util.HashSet<Long> stations = new java.util.HashSet<>();
        /** 已登记酿造台（v0.12 炼金术士岗位池）：岗位按方块类型分流，与工作台互不混用。 */
        public final java.util.HashSet<Long> brewStations = new java.util.HashSet<>();
        /** 已登记水源站（v0.12.1 炼金补水，决策 #34）：水源方块/含水方块/含水炼药锅。 */
        public final java.util.HashSet<Long> waterSources = new java.util.HashSet<>();

        public boolean isEmpty() {
            return containers.isEmpty() && stations.isEmpty() && brewStations.isEmpty()
                    && waterSources.isEmpty();
        }

        /**
         * 旧档迁移（2026-11-06，幂等）：登记判定顺序 bug 期间，酿造台被误登进
         * 容器池——扫描容器池，把酿造台挪进酿造台岗位池（炼金认领前调用）。
         */
        public void migrateBrewStands(net.minecraft.server.level.ServerLevel level) {
            boolean moved = false;
            var it = containers.iterator();
            while (it.hasNext()) {
                long p = it.next();
                BlockPos pos = BlockPos.of(p);
                if (!level.isLoaded(pos)) {
                    continue; // 未加载区块不动（下次再试），避免同步加载
                }
                if (level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.BREWING_STAND)) {
                    it.remove();
                    brewStations.add(p);
                    moved = true;
                }
            }
            if (moved) {
                TownSaveData.get(level).setDirty();
            }
        }
    }

    private final WorkshopRegister workshop = new WorkshopRegister();

    public Town(UUID id, String name, String ownerName, String dimension,
                BlockPos min, BlockPos max) {
        this(id, name, ownerName, dimension, min, max, System.currentTimeMillis());
    }

    public Town(UUID id, String name, String ownerName, String dimension,
                BlockPos min, BlockPos max, long createdAt) {
        this.createdAt = createdAt;
        this.id = id;
        this.name = name;
        this.ownerName = ownerName;
        this.dimension = dimension;
        // 与 TownZone 同一套规整逻辑：保证 min ≤ max
        this.min = new BlockPos(
                Math.min(min.getX(), max.getX()),
                Math.min(min.getY(), max.getY()),
                Math.min(min.getZ(), max.getZ()));
        this.max = new BlockPos(
                Math.max(min.getX(), max.getX()),
                Math.max(min.getY(), max.getY()),
                Math.max(min.getZ(), max.getZ()));
    }

    // ==========================================================
    //  查询
    // ==========================================================

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String ownerName() {
        return ownerName;
    }

    // ==========================================================
    //  成员白名单（v0.11.1 领地权限）
    // ==========================================================

    public java.util.UUID ownerId() {
        return this.ownerId;
    }

    public void setOwnerId(java.util.UUID ownerId) {
        this.ownerId = ownerId;
    }

    /** 是否为成员。空表 = 未启用保护，视为"谁都是成员"（老城镇兼容）。 */
    public boolean isMember(java.util.UUID playerId) {
        return this.members.isEmpty() || this.members.containsKey(playerId);
    }

    /** 保护是否已启用（成员表非空）。 */
    public boolean protectionEnabled() {
        return !this.members.isEmpty();
    }

    /** 成员名单只读视图（命令 list 展示用）。 */
    public java.util.Map<UUID, String> members() {
        return java.util.Collections.unmodifiableMap(this.members);
    }

    /** 加入成员（重复幂等）。 */
    public void addMember(java.util.UUID playerId, String name) {
        this.members.put(playerId, name);
    }

    /** 按 UUID 移除成员：返回被移除者的名字，没找到返回 null。 */
    public String removeMember(java.util.UUID playerId) {
        return this.members.remove(playerId);
    }

    /** 按名字（忽略大小写）移除成员：返回被移除者的名字，没找到返回 null。 */
    public String removeMemberByName(String name) {
        for (java.util.Map.Entry<UUID, String> e : this.members.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) {
                this.members.remove(e.getKey());
                return e.getValue();
            }
        }
        return null;
    }

    // ==========================================================
    //  工坊登记表（v0.12 工人重做，城镇级）
    // ==========================================================

    /** 本镇的工坊登记表（容器池 + 岗位池）。 */
    public WorkshopRegister workshop() {
        return this.workshop;
    }

    public String dimension() {
        return dimension;
    }

    public BlockPos min() {
        return min;
    }

    public BlockPos max() {
        return max;
    }

    /** 某类子区域列表（没有则为空表）。 */
    public List<TownZone> zones(TownWandMode kind) {
        return zones.getOrDefault(kind, List.of());
    }

    /** 可写的子区域列表（没有就建一张）——改名/删除要原地改。 */
    public List<TownZone> zonesForWrite(TownWandMode kind) {
        return zones.computeIfAbsent(kind, k -> new ArrayList<>());
    }

    /** 全部子区域（手册与存档遍历用）。 */
    public List<TownZone> allZones() {
        List<TownZone> all = new ArrayList<>();
        for (TownWandMode mode : TownWandMode.values()) {
            all.addAll(zones(mode));
        }
        return all;
    }

    public long createdAt() {
        return createdAt;
    }

    /** 方块（含整格）是否在城镇内：0 号外框或任一追加区块（v0.9.1 并集）。 */
    public boolean contains(BlockPos pos) {
        if (pos.getX() >= min.getX() && pos.getX() <= max.getX()
                && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ()) {
            return true;
        }
        for (TownZone block : this.townBlocks) {
            if (block.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    /** 追加的城镇区块（第 2 块起；0 号是外框本体，不在列表里）。 */
    public List<TownZone> townBlocks() {
        return List.copyOf(this.townBlocks);
    }

    /** 追加一个城镇区块（调用方负责重叠校验与标脏）。 */
    public void addTownBlock(TownZone block) {
        this.townBlocks.add(block);
    }

    /** 可写的追加区块列表（区块改名用）。 */
    public List<TownZone> townBlocksForWrite() {
        return this.townBlocks;
    }

    /** 某区块（含 0 号外框，blockId = null）名下的功能区数量。 */
    public int countZonesInBlock(UUID blockId) {
        int count = 0;
        for (TownWandMode mode : TownWandMode.values()) {
            for (TownZone zone : zones(mode)) {
                if (blockId == null ? zone.parentBlock() == null : blockId.equals(zone.parentBlock())) {
                    count++;
                }
            }
        }
        return count;
    }

    /** 两城镇不能重叠：**任一**区块（含 0 号外框与追加区块）与对方任一区块相交即算。 */
    public boolean overlaps(Town other) {
        if (boxOverlaps(min, max, other.min, other.max)) {
            return true;
        }
        for (TownZone block : this.townBlocks) {
            if (boxOverlaps(block.min(), block.max(), other.min, other.max)) {
                return true;
            }
        }
        for (TownZone otherBlock : other.townBlocks) {
            if (boxOverlaps(min, max, otherBlock.min(), otherBlock.max())) {
                return true;
            }
            for (TownZone block : this.townBlocks) {
                if (boxOverlaps(block.min(), block.max(),
                        otherBlock.min(), otherBlock.max())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 供 findOverlapping 用的单盒子重叠判定（新建城镇还没有追加区块，只查外框）。 */
    boolean boxOverlaps(BlockPos otherMin, BlockPos otherMax) {
        return boxOverlaps(min, max, otherMin, otherMax);
    }

    private static boolean boxOverlaps(BlockPos aMin, BlockPos aMax,
                                       BlockPos bMin, BlockPos bMax) {
        return aMin.getX() <= bMax.getX() && aMax.getX() >= bMin.getX()
                && aMin.getY() <= bMax.getY() && aMax.getY() >= bMin.getY()
                && aMin.getZ() <= bMax.getZ() && aMax.getZ() >= bMin.getZ();
    }

    /** 添加子区域（调用方负责归属校验）。 */
    public void addZone(TownWandMode kind, TownZone zone) {
        zones.computeIfAbsent(kind, k -> new ArrayList<>()).add(zone);
    }

    void rename(String newName) {
        this.name = newName;
    }

    // ==========================================================
    //  NBT 存档
    // ==========================================================

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("Name", name);
        tag.putString("Owner", ownerName);
        tag.putString("Dim", dimension);
        tag.putLong("Min", min.asLong());
        tag.putLong("Max", max.asLong());
        tag.putLong("Created", createdAt);
        ListTag zoneList = new ListTag();
        for (TownZone zone : allZones()) {
            CompoundTag z = zone.save();
            // 子区域类别也要存：读回来才知道挂回哪张表
            z.putInt("Kind", kindIndexOf(zone));
            zoneList.add(z);
        }
        tag.put("Zones", zoneList);
        ListTag blockList = new ListTag();
        for (TownZone block : this.townBlocks) {
            blockList.add(block.save());
        }
        tag.put("TownBlocks", blockList);
        if (this.ownerId != null) {
            tag.putUUID("OwnerId", this.ownerId);
        }
        ListTag memberList = new ListTag();
        for (java.util.Map.Entry<UUID, String> e : this.members.entrySet()) {
            CompoundTag m = new CompoundTag();
            m.putUUID("Id", e.getKey());
            m.putString("Name", e.getValue());
            memberList.add(m);
        }
        tag.put("Members", memberList);
        // 工坊登记表（v0.12，城镇级）：容器/岗位坐标（long 数组最省）
        if (!this.workshop.isEmpty()) {
            tag.putLongArray("WorkshopContainers", this.workshop.containers.stream()
                    .mapToLong(Long::longValue).toArray());
            tag.putLongArray("WorkshopStations", this.workshop.stations.stream()
                    .mapToLong(Long::longValue).toArray());
            tag.putLongArray("WorkshopBrewStations", this.workshop.brewStations.stream()
                    .mapToLong(Long::longValue).toArray());
            tag.putLongArray("WorkshopWaterSources", this.workshop.waterSources.stream()
                    .mapToLong(Long::longValue).toArray());
        }
        return tag;
    }

    /** {@link TownZone} 反查自己在哪张表里——存档用。 */
    private int kindIndexOf(TownZone zone) {
        for (TownWandMode mode : TownWandMode.values()) {
            if (zones.getOrDefault(mode, List.of()).contains(zone)) {
                return mode.ordinal();
            }
        }
        return 0;
    }

    public static Town load(CompoundTag tag) {
        Town town = new Town(
                tag.getUUID("Id"),
                tag.getString("Name"),
                tag.getString("Owner"),
                tag.getString("Dim"),
                BlockPos.of(tag.getLong("Min")),
                BlockPos.of(tag.getLong("Max")),
                tag.getLong("Created"));
        ListTag zoneList = tag.getList("Zones", Tag.TAG_COMPOUND);
        for (int i = 0; i < zoneList.size(); i++) {
            CompoundTag z = zoneList.getCompound(i);
            TownZone zone = TownZone.load(z);
            TownWandMode kind = TownWandMode.byIndex(z.getInt("Kind"));
            town.addZone(kind, zone);
        }
        ListTag blockList = tag.getList("TownBlocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blockList.size(); i++) {
            town.townBlocks.add(TownZone.load(blockList.getCompound(i)));
        }
        town.ownerId = tag.hasUUID("OwnerId") ? tag.getUUID("OwnerId") : null;
        ListTag memberList = tag.getList("Members", Tag.TAG_COMPOUND);
        for (int i = 0; i < memberList.size(); i++) {
            CompoundTag m = memberList.getCompound(i);
            town.members.put(m.getUUID("Id"), m.getString("Name"));
        }
        // 工坊登记表（城镇级）；键名带 Z 前缀的旧实验档（区级版，未发布）自动忽略
        for (long p : tag.getLongArray("WorkshopContainers")) {
            town.workshop.containers.add(p);
        }
        for (long p : tag.getLongArray("WorkshopStations")) {
            town.workshop.stations.add(p);
        }
        for (long p : tag.getLongArray("WorkshopBrewStations")) {
            town.workshop.brewStations.add(p);
        }
        for (long p : tag.getLongArray("WorkshopWaterSources")) {
            town.workshop.waterSources.add(p);
        }
        // 老档自动归块（v0.9.1）：归属为空（区块功能实装前画的功能区）且
        // 几何上完整落在某个追加区块内 → 自动归到那个区块。主外框内的保持主区块。
        if (!town.townBlocks.isEmpty()) {
            for (java.util.Map.Entry<TownWandMode, java.util.List<TownZone>> entry
                    : town.zones.entrySet()) {
                java.util.List<TownZone> list = entry.getValue();
                for (int i = 0; i < list.size(); i++) {
                    TownZone zone = list.get(i);
                    if (zone.parentBlock() != null || zone.inside(town.min, town.max)) {
                        continue; // 已有归属，或本来就在主外框内
                    }
                    for (TownZone block : town.townBlocks) {
                        if (zone.inside(block.min(), block.max())) {
                            list.set(i, new TownZone(zone.id(), zone.name(),
                                    zone.min(), zone.max(), block.id()));
                            break;
                        }
                    }
                }
            }
        }
        return town;
    }
}
