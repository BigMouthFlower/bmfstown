package com.bmf.bmfstown.town;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * 一个"子区域"——城镇下面的就餐区/住宅/工作区。
 *
 * <h2>为什么是 record + 自己的 UUID</h2>
 * 手册 UI 要对单条区域改名、删除，所以每条区域要有稳定 ID
 * （UUID 随机生成，存档重进后不变）。
 *
 * <h2>坐标约定</h2>
 * {@code min}/{@code max} 是<b>含端点</b>的方块坐标对角
 * （选点时两个对角方块都在区域内），与魔杖预览画的白盒一致：
 * 盒子从 min 画到 max + 1（+1 是因为方块占 [n, n+1) 的空间）。
 */
public record TownZone(UUID id, String name, BlockPos min, BlockPos max) {

    public TownZone {
        // record 的紧凑构造器：保证 min ≤ max，存档和创建两侧统一。
        min = new BlockPos(
                Math.min(min.getX(), max.getX()),
                Math.min(min.getY(), max.getY()),
                Math.min(min.getZ(), max.getZ()));
        max = new BlockPos(
                Math.max(min.getX(), max.getX()),
                Math.max(min.getY(), max.getY()),
                Math.max(min.getZ(), max.getZ()));
    }

    /** 此方块（含整格空间）是否在区域内。 */
    public boolean contains(BlockPos pos) {
        return pos.getX() >= min.getX() && pos.getX() <= max.getX()
                && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }

    /** 是否完全被外框包住（子区域归属校验用）。 */
    public boolean inside(BlockPos outerMin, BlockPos outerMax) {
        return min.getX() >= outerMin.getX() && max.getX() <= outerMax.getX()
                && min.getY() >= outerMin.getY() && max.getY() <= outerMax.getY()
                && min.getZ() >= outerMin.getZ() && max.getZ() <= outerMax.getZ();
    }

    /** "x, y, z" 格式，与魔杖提示一致。 */
    public String minText() {
        return TownWandItem.fmt(min);
    }

    public String maxText() {
        return TownWandItem.fmt(max);
    }

    // ==========================================================
    //  NBT 存档
    // ==========================================================

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("Name", name);
        tag.putLong("Min", min.asLong());
        tag.putLong("Max", max.asLong());
        return tag;
    }

    public static TownZone load(CompoundTag tag) {
        return new TownZone(
                tag.getUUID("Id"),
                tag.getString("Name"),
                BlockPos.of(tag.getLong("Min")),
                BlockPos.of(tag.getLong("Max")));
    }
}
