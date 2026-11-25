package com.bmf.bmfstown.eerie;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 镇公告栏数据（决策 #57：表达层第三出口——气泡/手册之外的世界内公示板）。
 *
 * <h2>口径（#57 拍板）</h2>
 * <ul>
 *   <li>记载范围（当前）：<b>闲逛随机发现的非常规事件</b>——目前只有
 *       恐怖线索（发现一格 / 应验各一条），随机事件通用层其他类型
 *       以后加 kind 即可；</li>
 *   <li><b>与写书同源</b>：公告只在 {@link WanderEvents} 命中并推进
 *       故事线时写入——发现了才公示、才写得到，公告栏是玩家侧唯一
 *       进度窗口；</li>
 *   <li>结构化存储（游戏日/时段/居民/线/格），显示时才拼 translatable
 *       ——玩家各自语言看到各自文案；</li>
 *   <li>滚动列表：只留最近 {@value #KEEP} 条。</li>
 * </ul>
 *
 * <p>方块本体 {@code BulletinBoardBlock} 只负责展示（右键打印最近条目）；
 * 美化/面板化（F13 后议项）不改动本数据层。
 */
public final class TownBulletinData extends SavedData {

    private static final String DATA_NAME = "bmfstown_bulletin";

    /** 滚动窗口：只保留最近 N 条。 */
    public static final int KEEP = 30;

    /** 公告种类（新增随机事件类型 = 加 kind + 加显示分支）。 */
    public static final String KIND_SPOT = "spot";     // 发现线索（推进一格）
    public static final String KIND_VERIFY = "verify"; // 异象应验

    /** 一条公告（结构化，显示时才本地化拼装）。 */
    public record Entry(long day, boolean night, String kind,
                        String resident, String lineId, int chapter) {

        /** 拼显示组件（玩家各自语言）。 */
        public Component display() {
            String phaseKey = this.night ? "bulletin.bmfstown.phase.night" : "bulletin.bmfstown.phase.day";
            HorrorLines.HorrorLine line = HorrorLines.byId(this.lineId);
            Component story = line == null
                    ? Component.literal(this.lineId)
                    : Component.translatable(line.titleKey());
            // 进度分母按该线自己的章数（2026-11-25 起章数不再是全局常量）
            int total = line == null ? HorrorLines.DEFAULT_CHAPTERS : line.chapters();
            return switch (this.kind) {
                case KIND_SPOT -> Component.translatable("bulletin.bmfstown.horror.spot",
                        this.resident, Component.translatable(phaseKey), story,
                        this.chapter, total);
                case KIND_VERIFY -> Component.translatable("bulletin.bmfstown.horror.verify",
                        this.resident, Component.translatable(phaseKey), story);
                default -> Component.literal(this.kind);
            };
        }
    }

    /** 城镇 id → 滚动公告列表（旧→新）。 */
    private final Map<UUID, List<Entry>> towns = new HashMap<>();

    // ==========================================================
    //  存取入口
    // ==========================================================

    public static TownBulletinData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(TownBulletinData::new, TownBulletinData::load, null), DATA_NAME);
    }

    /** 追加一条公告（超出窗口挤掉最旧的）。 */
    public void post(ServerLevel level, UUID townId, Entry entry) {
        List<Entry> list = this.towns.computeIfAbsent(townId, k -> new ArrayList<>());
        list.add(entry);
        while (list.size() > KEEP) {
            list.remove(0);
        }
        this.setDirty();
    }

    /** 最近 n 条（旧→新）；无公告返回空表。 */
    public List<Entry> latest(UUID townId, int n) {
        List<Entry> list = this.towns.get(townId);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        return List.copyOf(list.subList(Math.max(0, list.size() - n), list.size()));
    }

    // ==========================================================
    //  存档往返
    // ==========================================================

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        for (var e : this.towns.entrySet()) {
            ListTag list = new ListTag();
            for (Entry entry : e.getValue()) {
                CompoundTag t = new CompoundTag();
                t.putLong("Day", entry.day());
                t.putBoolean("Night", entry.night());
                t.putString("Kind", entry.kind());
                t.putString("Resident", entry.resident());
                t.putString("Line", entry.lineId());
                t.putInt("Chapter", entry.chapter());
                list.add(t);
            }
            tag.put(e.getKey().toString(), list);
        }
        return tag;
    }

    public static TownBulletinData load(CompoundTag tag, HolderLookup.Provider registries) {
        TownBulletinData state = new TownBulletinData();
        for (String key : tag.getAllKeys()) {
            ListTag list = tag.getList(key, Tag.TAG_COMPOUND);
            List<Entry> out = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                CompoundTag t = list.getCompound(i);
                out.add(new Entry(t.getLong("Day"), t.getBoolean("Night"),
                        t.getString("Kind"), t.getString("Resident"),
                        t.getString("Line"), t.getInt("Chapter")));
            }
            try {
                state.towns.put(UUID.fromString(key), out);
            } catch (IllegalArgumentException ignored) {
                // 坏键丢弃
            }
        }
        return state;
    }

    /** @hidden 测试/调试：镇是否已有公告。 */
    public boolean isEmpty(UUID townId) {
        List<Entry> list = this.towns.get(townId);
        return list == null || list.isEmpty();
    }

    @Nullable
    Entry lastOf(UUID townId) {
        List<Entry> list = this.towns.get(townId);
        return list == null || list.isEmpty() ? null : list.get(list.size() - 1);
    }
}
