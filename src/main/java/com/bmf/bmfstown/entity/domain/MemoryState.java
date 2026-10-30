package com.bmf.bmfstown.entity.domain;

import com.bmf.bmfstown.social.TownEvent;
import com.bmf.bmfstown.social.TownEventType;
import com.bmf.bmfstown.social.TownEventBus;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * 记忆仓（第七域，v0.9.1 表达层）：镇民的个人记忆，只来自亲历。
 *
 * <p>P1 只实现**印象池**（短期）：事件按重要性表记下、每日衰减、
 * 归零遗忘——用途是台词素材与闲聊池。**铭记库**（永久固化 + 回味
 * 触发器）按卷〇定稿在 P3 落地，届时与印象池并列。
 *
 * <p>归属铁律：记忆永远个人制——谁亲历谁记，没有"城镇集体记忆"；
 * 图鉴/职业解锁表才是世界级共享（SavedData）。
 *
 * <p>作为事件总线订阅者自动采集：事件 ≠ 记忆，只有登记过重要性的
 * 类型才会晋升为印象（P1：吃到爱吃的）。
 */
public class MemoryState {

    /** 印象条目（P1 形态；铭记条目 P3 引入时并列扩展）。 */
    public record Impression(String type, int day, int strength, String itemId) {
    }

    /** 印象池上限：满了淘汰最旧的（P3 铭记引入后此池只保留谈资职能）。 */
    private static final int MAX_IMPRESSIONS = 16;

    /** 印象的初始强度（P1 常量；重要性表 P2 配置化）。 */
    private static final int IMPRESSION_STRENGTH = 35;
    /** 每日衰减量。 */
    private static final int DAILY_DECAY = 5;

    private final List<Impression> impressions = new ArrayList<>();

    static {
        // 静态注册一次（本类随 Resident 类加载）：构造器注册会每实例重复登记
        TownEventBus.register(MemoryState::recordEvent);
    }

    public MemoryState() {
    }

    // ------------------------------------------------------------
    //  采集（事件总线订阅者）
    // ------------------------------------------------------------

    private static void recordEvent(TownEvent event) {
        switch (event.type) {
            case EAT_FAVORED -> {
                String itemId = event.item.isEmpty() ? ""
                        : net.minecraft.core.registries.BuiltInRegistries.ITEM
                                .getKey(event.item.getItem()).toString();
                event.subject.getMemoryState().addImpression(
                        "eat_favored", IMPRESSION_STRENGTH, itemId);
            }
            default -> {
                // P1 只采集进食；其他事件的记忆价值待重要性表（P2）定义
            }
        }
    }

    /**
     * 记一条印象：同类型+同物品 → 刷新强度与日期（"天天在他面前钓鱼，
     * 印象越来越深"的语义）；否则新增，超上限挤掉最旧。
     */
    public void addImpression(String type, int strength, String itemId) {
        for (int i = 0; i < this.impressions.size(); i++) {
            Impression old = this.impressions.get(i);
            if (old.type().equals(type) && old.itemId().equals(itemId)) {
                this.impressions.set(i, new Impression(type, old.day(),
                        Math.max(old.strength(), strength), itemId));
                return;
            }
        }
        int day = 0; // 日志粒度 P1 不重要，P2 随记忆系统完整化补天号
        this.impressions.add(new Impression(type, day, strength, itemId));
        if (this.impressions.size() > MAX_IMPRESSIONS) {
            this.impressions.remove(0); // 最旧的先走
        }
    }

    /** 每日衰减（由 Status.tickDaily 换天钩子调用）：归零即遗忘。 */
    public void dailyDecay() {
        this.impressions.removeIf(imp -> {
            int left = imp.strength() - DAILY_DECAY;
            if (left <= 0) {
                return true;
            }
            this.impressions.set(this.impressions.indexOf(imp),
                    new Impression(imp.type(), imp.day(), left, imp.itemId()));
            return false;
        });
    }

    public List<Impression> impressions() {
        return List.copyOf(this.impressions);
    }

    // ------------------------------------------------------------
    //  序列化（新域：老档无此键 = 空仓默认，零迁移成本）
    // ------------------------------------------------------------

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (Impression imp : this.impressions) {
            CompoundTag t = new CompoundTag();
            t.putString("Type", imp.type());
            t.putInt("Day", imp.day());
            t.putInt("Strength", imp.strength());
            t.putString("Item", imp.itemId());
            list.add(t);
        }
        tag.put("Impressions", list);
        return tag;
    }

    public void loadFrom(CompoundTag tag) {
        this.impressions.clear();
        ListTag list = tag.getList("Impressions", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            this.impressions.add(new Impression(
                    t.getString("Type"), t.getInt("Day"),
                    t.getInt("Strength"), t.getString("Item")));
        }
    }
}
