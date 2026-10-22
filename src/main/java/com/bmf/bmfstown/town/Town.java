package com.bmf.bmfstown.town;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

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
    /** 创建时间戳——手册里"新的排在后面"就靠它排序。 */
    private final long createdAt;

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

    /** 方块（含整格）是否在外框内。 */
    public boolean contains(BlockPos pos) {
        return pos.getX() >= min.getX() && pos.getX() <= max.getX()
                && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }

    /** 外框（两城镇不能重叠的判定）——与另一个城镇的外框在 AABB 上相交。 */
    public boolean overlaps(Town other) {
        return min.getX() <= other.max.getX() && max.getX() >= other.min.getX()
                && min.getY() <= other.max.getY() && max.getY() >= other.min.getY()
                && min.getZ() <= other.max.getZ() && max.getZ() >= other.min.getZ();
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
        return town;
    }
}
