package com.bmf.bmfstown.town;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 过客刷新开关（决策 #61）：每镇一个"过客刷新"开关，公告栏潜行右键切换。
 *
 * <h2>状态语义</h2>
 * <ul>
 *   <li>默认<b>开</b>（表里没有条目 = 开）——镇子建好就有人来，符合
 *       "镇民从哪来"的/bootstrap 语义；</li>
 *   <li>镇民满 {@link Wanderers#POP_CAP} 人时由 {@link Wanderers#checkCap}
 *       自动关（播报一次）；玩家可从公告栏手动重开——上限是提醒不是铁闸
 *       （用户拍板）。</li>
 * </ul>
 *
 * <p>持久化为独立 SavedData（挂主世界，与 {@link TownSaveData} 同款通道），
 * 不塞进 Town 类——开关是行为旋钮不是城镇事实，分开存省得 Town 每加一个
 * 开关都要动序列化。
 */
public final class WandererState extends SavedData {

    public static final String DATA_NAME = "bmfstown_wanderers";

    /** 城镇 UUID → 开关状态。缺条目 = 开（默认值不落盘）。 */
    private final Map<UUID, Boolean> spawning = new HashMap<>();

    /**
     * 下次过客路过的游戏日（{@code dayTime / 24000}）。-1 = 未排（新世界
     * 第一次进档时由 {@link Wanderers} 排一个开局 3 日内的首来日期）。
     * 持久化——节奏是预约制（决策 #61 补记），重启不能重置。
     */
    private long nextArrivalDay = -1L;

    /** 取当前世界的过客开关状态（挂主世界）。 */
    public static WandererState get(ServerLevel anyLevel) {
        return anyLevel.getServer().overworld().getDataStorage()
                .computeIfAbsent(
                        new Factory<>(WandererState::new, WandererState::load),
                        DATA_NAME);
    }

    /** 该镇过客刷新是否开着（缺条目 = 默认开）。 */
    public boolean isEnabled(UUID townId) {
        return this.spawning.getOrDefault(townId, Boolean.TRUE);
    }

    /** 设置开关并标脏。 */
    public void setEnabled(UUID townId, boolean enabled) {
        this.spawning.put(townId, enabled);
        this.setDirty();
    }

    /** 下次路过日（-1 = 未排）。 */
    public long nextArrivalDay() {
        return this.nextArrivalDay;
    }

    /** 排下次路过日并标脏。 */
    public void setNextArrivalDay(long day) {
        this.nextArrivalDay = day;
        this.setDirty();
    }

    // ==========================================================
    //  序列化
    // ==========================================================

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        CompoundTag root = new CompoundTag();
        for (Map.Entry<UUID, Boolean> entry : this.spawning.entrySet()) {
            root.putBoolean(entry.getKey().toString(), entry.getValue());
        }
        tag.put("Switches", root);
        tag.putLong("NextArrivalDay", this.nextArrivalDay);
        return tag;
    }

    public static WandererState load(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        WandererState state = new WandererState();
        CompoundTag root = tag.getCompound("Switches");
        for (String key : root.getAllKeys()) {
            try {
                state.spawning.put(UUID.fromString(key), root.getBoolean(key));
            } catch (IllegalArgumentException ignored) {
                // 损坏的键直接丢弃——开关丢了只是回到默认开，无害
            }
        }
        state.nextArrivalDay = tag.contains("NextArrivalDay")
                ? tag.getLong("NextArrivalDay") : -1L;
        return state;
    }
}
