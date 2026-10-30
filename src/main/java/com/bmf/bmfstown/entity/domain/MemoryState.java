package com.bmf.bmfstown.entity.domain;

import com.bmf.bmfstown.social.TownEvent;
import com.bmf.bmfstown.social.TownEventType;
import com.bmf.bmfstown.social.TownEventBus;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆仓（第七域，v0.9.1 表达层）：镇民的个人记忆，只来自亲历。
 *
 * <p>两层结构（v0.9.3 定稿）：
 * <ul>
 *   <li><b>印象池</b>（短期）：事件按重要性表记下、每日衰减、归零遗忘——
 *       台词素材与闲聊池；</li>
 *   <li><b>铭记进度 + 铭记库</b>（长期）：特定类型的受苦事件（挨饿/缺觉）
 *       每发生一次累计进度（用户定稿：挨饿一次 +15），积满阈值
 *       （100）固化为<b>永久记忆</b>——永不衰减、不再进印象池；
 *       中途收手则进度每日衰减（-5）。固化的表达只有一处：
 *       <b>想法日志印一句</b>（"他记住了挨饿的滋味"）——记忆不挂嘴边，
 *       没有回味台词（用户明确砍掉）；永久记忆的实际作用是
 *       同类事件心情额外扣减（见 Status 的 memoryPenalty）与台词变体。</li>
 * </ul>
 *
 * <p>归属铁律：记忆永远个人制——谁亲历谁记，没有"城镇集体记忆"；
 * 图鉴/职业解锁表才是世界级共享（SavedData）。
 */
public class MemoryState {

    /** 受苦类型：挨饿。 */
    public static final String TYPE_STARVING = "starving";
    /** 受苦类型：缺觉。 */
    public static final String TYPE_SLEEPLESS = "sleepless";

    /** 印象条目（P1 形态；铭记条目 P3 引入时并列扩展）。 */
    public record Impression(String type, int day, int strength, String itemId) {
    }

    /** 铭记（永久记忆）：type + 固化时记录的最后受苦日（镇民历坐标系）。 */
    public record FirmMemory(String type, int day) {
    }

    /** 印象池上限：满了淘汰最旧的（P3 铭记引入后此池只保留谈资职能）。 */
    private static final int MAX_IMPRESSIONS = 16;

    /** 印象的初始强度（P1 常量；重要性表 P2 配置化）。 */
    private static final int IMPRESSION_STRENGTH = 35;
    /** 印象每日衰减量。 */
    private static final int DAILY_DECAY = 5;

    private final List<Impression> impressions = new ArrayList<>();

    /** 铭记进度（未固化）：type → 0~阈值。 */
    private final Map<String, Integer> forming = new HashMap<>();
    /** 铭记进度的最后受苦日（世界绝对天数）。 */
    private final Map<String, Integer> formingLastDay = new HashMap<>();
    /** 铭记库（永久记忆）：固化后永不衰减。 */
    private final Map<String, FirmMemory> firm = new HashMap<>();

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
                // 记忆时间戳：世界绝对天数（镇民历显示时减纪元换算年月日，见 TownCalendar）
                int day = (int) (event.subject.level().getDayTime() / 24000L);
                event.subject.getMemoryState().addImpression(
                        "eat_favored", day, IMPRESSION_STRENGTH, itemId);
            }
            default -> {
                // P1 只采集进食；其他事件的记忆价值待重要性表（P2）定义
            }
        }
    }

    /**
     * 记一条印象：同类型+同物品 → 强度取高、日期取新（"今天又吃到了，
     * 印象刷新到今天"的语义）；否则新增，超上限挤掉最旧。
     */
    public void addImpression(String type, int day, int strength, String itemId) {
        for (int i = 0; i < this.impressions.size(); i++) {
            Impression old = this.impressions.get(i);
            if (old.type().equals(type) && old.itemId().equals(itemId)) {
                this.impressions.set(i, new Impression(type,
                        Math.max(old.day(), day),
                        Math.max(old.strength(), strength), itemId));
                return;
            }
        }
        this.impressions.add(new Impression(type, day, strength, itemId));
        if (this.impressions.size() > MAX_IMPRESSIONS) {
            this.impressions.remove(0); // 最旧的先走
        }
    }

    /** 每日衰减（由 Status.tickDaily 换天钩子调用）：归零即遗忘。 */
    public void dailyDecay() {
        if (this.impressions.isEmpty()) {
            return;
        }
        // 重建列表（原 removeIf 内 set 的写法既绕又依赖 record equals，平铺直叙更好）
        List<Impression> survivors = new ArrayList<>(this.impressions.size());
        for (Impression imp : this.impressions) {
            int left = imp.strength() - DAILY_DECAY;
            if (left > 0) {
                survivors.add(new Impression(imp.type(), imp.day(), left, imp.itemId()));
            }
        }
        this.impressions.clear();
        this.impressions.addAll(survivors);
    }

    // ------------------------------------------------------------
    //  铭记进度 + 铭记库（v0.9.3：挨饿/缺觉两类先行）
    // ------------------------------------------------------------

    /**
     * 受苦累计：该类型进度 +gain；积满阈值 → 固化为永久记忆并返回 true
     * （调用方负责固化表达——想法日志，不说话）。同日重复累计无害，
     * 调用方（Status 每日结算/事件点）自行保证节流。
     */
    public boolean addProgress(String type, int day, int gain, int threshold) {
        if (this.firm.containsKey(type)) {
            return false; // 已固化：永久记忆不再积累
        }
        int progress = this.forming.getOrDefault(type, 0) + gain;
        int lastDay = Math.max(this.formingLastDay.getOrDefault(type, day), day);
        if (progress >= threshold) {
            this.firm.put(type, new FirmMemory(type, lastDay));
            this.forming.remove(type);
            this.formingLastDay.remove(type);
            return true;
        }
        this.forming.put(type, progress);
        this.formingLastDay.put(type, lastDay);
        return false;
    }

    /** 铭记进度衰减（收手了慢慢淡忘）：归零清空；已固化的不受影响。 */
    public void decayProgress(String type, int decay) {
        if (this.firm.containsKey(type) || !this.forming.containsKey(type)) {
            return;
        }
        int left = this.forming.get(type) - decay;
        if (left <= 0) {
            this.forming.remove(type);
            this.formingLastDay.remove(type);
        } else {
            this.forming.put(type, left);
        }
    }

    /** 有没有该类型的永久记忆。 */
    public boolean hasFirmMemory(String type) {
        return this.firm.containsKey(type);
    }

    /** 该类型永久记忆固化的日期（世界绝对天数）；无则 -1。 */
    public int firmMemoryDay(String type) {
        FirmMemory memory = this.firm.get(type);
        return memory == null ? -1 : memory.day();
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
        ListTag firmList = new ListTag();
        for (FirmMemory memory : this.firm.values()) {
            CompoundTag t = new CompoundTag();
            t.putString("Type", memory.type());
            t.putInt("Day", memory.day());
            firmList.add(t);
        }
        tag.put("Firm", firmList);
        ListTag formingList = new ListTag();
        for (Map.Entry<String, Integer> e : this.forming.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putString("Type", e.getKey());
            t.putInt("Progress", e.getValue());
            t.putInt("Day", this.formingLastDay.getOrDefault(e.getKey(), 0));
            formingList.add(t);
        }
        tag.put("Forming", formingList);
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
        this.firm.clear();
        ListTag firmList = tag.getList("Firm", Tag.TAG_COMPOUND);
        for (int i = 0; i < firmList.size(); i++) {
            CompoundTag t = firmList.getCompound(i);
            FirmMemory memory = new FirmMemory(t.getString("Type"), t.getInt("Day"));
            this.firm.put(memory.type(), memory);
        }
        this.forming.clear();
        this.formingLastDay.clear();
        ListTag formingList = tag.getList("Forming", Tag.TAG_COMPOUND);
        for (int i = 0; i < formingList.size(); i++) {
            CompoundTag t = formingList.getCompound(i);
            String type = t.getString("Type");
            this.forming.put(type, t.getInt("Progress"));
            this.formingLastDay.put(type, t.getInt("Day"));
        }
    }
}
