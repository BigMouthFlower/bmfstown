package com.bmf.bmfstown.weather;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;

/**
 * 魔法降雪的存档数据（每维度一份）。
 *
 * <p>三段状态：
 * <ol>
 *   <li>**active**：正在下雪（center/radius/endGameTime 有效）；</li>
 *   <li>**melting**：降雪结束后的消融队列——只融化**我们自己铺的**雪层，
 *       玩家手搭的雪不受影响（每个位置在消融时先校验当前方块仍是雪层）；</li>
 *   <li>空置：什么都没有。</li>
 * </ol>
 *
 * <p>placed 用 {@code List<Long>}（BlockPos.asLong 打包）而不是 Set：
 * 同一列被多次采样记录是允许的，消融时对已消失的雪层自动跳过即可。
 */
public class SnowfallSavedData extends SavedData {

    /** 无活动降雪时的 center 占位值（真实坐标打包值不可能等于它）。 */
    public static final long INACTIVE = Long.MAX_VALUE;

    private long centerPacked = INACTIVE;
    private int radius;
    private long endGameTime;

    /** 降雪期间铺下的雪层位置（打包 BlockPos），降雪结束转交消融队列。 */
    private final List<Long> placed = new ArrayList<>();
    /** 消融队列：每 tick 移除若干层，直到清空。 */
    private final List<Long> melting = new ArrayList<>();

    // ------------------------------------------------------------

    public boolean isActive() {
        return this.centerPacked != INACTIVE;
    }

    public long centerPacked() {
        return this.centerPacked;
    }

    public int radius() {
        return this.radius;
    }

    public long endGameTime() {
        return this.endGameTime;
    }

    public boolean hasMelting() {
        return !this.melting.isEmpty();
    }

    public void start(BlockPos center, int radius, long endGameTime) {
        this.centerPacked = center.asLong();
        this.radius = radius;
        this.endGameTime = endGameTime;
        this.setDirty();
    }

    /** 降雪结束：铺下的位置整体转入消融队列。 */
    public void beginMelt() {
        this.melting.addAll(this.placed);
        this.placed.clear();
        this.centerPacked = INACTIVE;
        this.setDirty();
    }

    public void recordPlaced(BlockPos pos) {
        this.placed.add(pos.asLong());
        this.setDirty();
    }

    /** 消融队列取一批（最多 {@code count}）待处理的位置；取即删。 */
    public List<Long> popMelting(int count) {
        if (this.melting.isEmpty()) {
            return List.of();
        }
        int n = Math.min(count, this.melting.size());
        List<Long> batch = new ArrayList<>(this.melting.subList(0, n));
        this.melting.subList(0, n).clear();
        this.setDirty();
        return batch;
    }

    /** 消融中减了一层但还没化完：重新排队（多层雪逐层化）。 */
    public void requeueMelting(BlockPos pos) {
        this.melting.add(pos.asLong());
        this.setDirty();
    }

    // ------------------------------------------------------------
    //  序列化
    // ------------------------------------------------------------

    private static final String DATA_NAME = "bmfstown_snowfall";

    public static SnowfallSavedData get(net.minecraft.server.level.ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(SnowfallSavedData::new, SnowfallSavedData::load, null),
                DATA_NAME);
    }

    /** 只读查询：从未创建过返回 null（tick 热路径用，避免给所有维度造空档）。 */
    public static SnowfallSavedData peek(net.minecraft.server.level.ServerLevel level) {
        return level.getDataStorage().get(
                new SavedData.Factory<>(SnowfallSavedData::new, SnowfallSavedData::load),
                DATA_NAME);
    }

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        tag.putLong("Center", this.centerPacked);
        tag.putInt("Radius", this.radius);
        tag.putLong("EndGameTime", this.endGameTime);
        tag.put("Placed", new LongArrayTag(this.placed.stream().mapToLong(Long::longValue).toArray()));
        tag.put("Melting", new LongArrayTag(this.melting.stream().mapToLong(Long::longValue).toArray()));
        return tag;
    }

    private static SnowfallSavedData load(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        SnowfallSavedData data = new SnowfallSavedData();
        data.centerPacked = tag.getLong("Center");
        data.radius = tag.getInt("Radius");
        data.endGameTime = tag.getLong("EndGameTime");
        if (tag.contains("Placed", Tag.TAG_LONG_ARRAY)) {
            for (long v : tag.getLongArray("Placed")) {
                data.placed.add(v);
            }
        }
        if (tag.contains("Melting", Tag.TAG_LONG_ARRAY)) {
            for (long v : tag.getLongArray("Melting")) {
                data.melting.add(v);
            }
        }
        return data;
    }
}
