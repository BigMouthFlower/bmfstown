package com.bmf.bmfstown.entity.domain;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 知识域（v0.11，第八域）：镇民从雕纹书架读书获得的长期知识。
 *
 * <h2>为什么是"装备制"（极乐迪斯科思维阁式）</h2>
 * 特殊书记载的 perk <b>同时只能生效一本</b>（用户定稿的削弱）：
 * 装备的书（{@code equippedBookId}）提供效果，但需要研读——
 * 每本书的习得时长不同（登记表 learnTicks）。
 *
 * <h2>稀疏进度存储（用户定稿）</h2>
 * {@code learnProgress} 只记<b>学过的书</b>的进度——没学过的书进度
 * 天然是 0，不进存档。中断不丢：进度跟着书 id 走，换书再回来继续。
 */
public class KnowledgeState {

    /** 当前装备的书 id（登记表键；空 = 没装备）。 */
    private String equippedBookId = "";

    /** 已习得的书（效果永久在册，可随时切换装备）。 */
    private final Set<String> learned = new HashSet<>();

    /** 稀疏研读进度：书 id → 已研读 tick。 */
    private final Map<String, Long> learnProgress = new HashMap<>();

    // ==========================================================
    //  装备与研读
    // ==========================================================

    /** 装备一本书（书槽唯一：直接替换当前装备）。 */
    public void equip(String bookId) {
        this.equippedBookId = bookId == null ? "" : bookId;
    }

    /** 卸下当前装备。 */
    public void unequip() {
        this.equippedBookId = "";
    }

    public String equippedBookId() {
        return this.equippedBookId;
    }

    public boolean isLearned(String bookId) {
        return this.learned.contains(bookId);
    }

    /** 已习得列表（UI 用，顺序不保证——展示侧排序）。 */
    public Set<String> learnedBooks() {
        return Set.copyOf(this.learned);
    }

    /** 某本书的研读进度（未学过的书返回 0——稀疏语义）。 */
    public long progressOf(String bookId) {
        return this.learnProgress.getOrDefault(bookId, 0L);
    }

    /**
     * 研读累计（镇民 tick 调用，装备期间每 tick +1）：达到习得时长
     * 即进入已学列表。需要知道习得时长 → 从登记表查，查不到不累计。
     */
    public void tick() {
        if (this.equippedBookId.isEmpty() || this.learned.contains(this.equippedBookId)) {
            return;
        }
        var book = com.bmf.bmfstown.book.SpecialBookRegistry.byId(this.equippedBookId);
        if (book == null) {
            return; // 登记表没有（池子换版等）：进度保留但不推进
        }
        long progress = this.learnProgress.merge(this.equippedBookId, 1L, Long::sum);
        if (progress >= book.learnTicks()) {
            this.learned.add(this.equippedBookId);
        }
    }

    /** 已学列表（排序稳定——快照与服务端按同一顺序对齐下标）。 */
    public java.util.List<String> learnedSorted() {
        java.util.List<String> out = new java.util.ArrayList<>(this.learned);
        java.util.Collections.sort(out);
        return out;
    }

    /** 稀疏进度的只读视图（快照用）。 */
    public Map<String, Long> progressView() {
        return Map.copyOf(this.learnProgress);
    }

    /** 按排序下标装备（知识页点击已学行）：越界静默忽略。 */
    public void equipByIndex(int index) {
        java.util.List<String> sorted = learnedSorted();
        if (index >= 0 && index < sorted.size()) {
            equip(sorted.get(index));
        }
    }

    // ==========================================================
    //  落档
    // ==========================================================

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Equipped", this.equippedBookId);
        ListTag learnedTag = new ListTag();
        for (String id : this.learned) {
            learnedTag.add(StringTag.valueOf(id));
        }
        tag.put("Learned", learnedTag);
        ListTag progressTag = new ListTag();
        for (Map.Entry<String, Long> e : this.learnProgress.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putString("Id", e.getKey());
            t.putLong("Ticks", e.getValue());
            progressTag.add(t);
        }
        tag.put("Progress", progressTag);
        return tag;
    }

    public void loadFrom(CompoundTag tag) {
        this.equippedBookId = tag.getString("Equipped");
        this.learned.clear();
        ListTag learnedTag = tag.getList("Learned", Tag.TAG_STRING);
        for (int i = 0; i < learnedTag.size(); i++) {
            this.learned.add(learnedTag.getString(i));
        }
        this.learnProgress.clear();
        ListTag progressTag = tag.getList("Progress", Tag.TAG_COMPOUND);
        for (int i = 0; i < progressTag.size(); i++) {
            CompoundTag t = progressTag.getCompound(i);
            this.learnProgress.put(t.getString("Id"), t.getLong("Ticks"));
        }
    }
}
