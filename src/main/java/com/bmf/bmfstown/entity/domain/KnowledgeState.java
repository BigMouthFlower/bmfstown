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
 * 知识域（第八域）：镇民在图书馆读书积累的长期知识——他的"记忆"。
 *
 * <h2>v0.10.4 学识制（用户定稿，取代研读槽）</h2>
 * <ul>
 *   <li><b>学识（literacy）</b>：读<b>完</b>一本闲书 +1（与心情同一结算点）；
 *       学一门特殊书消耗 {@code leisure.literacyCost}（默认 5）——
 *       闲书是识字教材，技能书用学识换永久效果，货架经济学互锁；</li>
 *   <li><b>技能书学习只在图书馆</b>：阅读累计 learnTicks（游戏刻，跨休闲段
 *       续读，进度按"人 × 书"记账），学成进已学列表；</li>
 *   <li><b>效果单选槽</b>：已学多本共存，但<b>同时只有一本生效</b>（
 *       {@code equippedBookId} = 当前选中），知识页点选更换。</li>
 * </ul>
 *
 * <h2>稀疏进度存储（用户定稿）</h2>
 * {@code learnProgress} 只记<b>读过的技能书</b>的进度——没读过的书进度
 * 天然是 0，不进存档。中断不丢：进度跟着书 id 走，下次取同一本继续。
 */
public class KnowledgeState {

    /** 当前生效的书 id（已学列表中的单选槽；空 = 没选）。 */
    private String equippedBookId = "";

    /** 已习得的书（永久在册，单选生效）。 */
    private final Set<String> learned = new HashSet<>();

    /** 稀疏研读进度：书 id → 已研读 tick。 */
    private final Map<String, Long> learnProgress = new HashMap<>();

    /** 学识点：读完闲书 +1，学特殊书消耗（v0.10.4 学识制）。 */
    private int literacy = 0;

    /** 累计读完的书（闲书 + 技能书，知识页展示用）。 */
    private int totalBooksRead = 0;

    /**
     * 读过的闲书种子（v0.10.4 用户定稿：不读重复的书）。种子即闲书身份
     * （书名/内容都由种子确定性生成，抄本同种子=同一本）；每人各记各的。
     */
    private final Set<Long> readSeeds = new HashSet<>();

    /**
     * 研读小数桶：非整数增益（批注本 1.5 倍速等）积少成多用，随档保存。
     */
    private double learnCarry = 0.0D;

    // ==========================================================
    //  单选槽与已学
    // ==========================================================

    /** 选一本已学的书生效（知识页点击；未学过的 id 拒绝）。 */
    public void equip(String bookId) {
        if (bookId != null && this.learned.contains(bookId)) {
            this.equippedBookId = bookId;
        }
    }

    /** 取消生效（不选任何书）。 */
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

    /** 某本书的研读进度（未读过的书返回 0——稀疏语义）。 */
    public long progressOf(String bookId) {
        return this.learnProgress.getOrDefault(bookId, 0L);
    }

    // ==========================================================
    //  学识与阅读统计
    // ==========================================================

    public int literacy() {
        return this.literacy;
    }

    public int totalBooksRead() {
        return this.totalBooksRead;
    }

    /** 读过这本闲书吗（种子身份；选书时跳过读过的）。 */
    public boolean hasReadSeed(long seed) {
        return this.readSeeds.contains(seed);
    }

    /** 读完一本闲书：学识 +1、总阅读数 +1、记入"读过"（活动结算点调用一次）。 */
    public void onIdleBookFinished(long seed) {
        this.literacy++;
        this.totalBooksRead++;
        this.readSeeds.add(seed);
    }

    /**
     * 学成一门特殊书：进已学列表、扣学识、总阅读数 +1。
     * 返回 false = 学识不足（调用方应在开始读前校验，这里是最后防线）。
     */
    public boolean onSkillBookFinished(String bookId, int cost) {
        if (this.literacy < cost || this.learned.contains(bookId)) {
            return false;
        }
        this.literacy -= cost;
        this.totalBooksRead++;
        this.learned.add(bookId);
        this.learnProgress.remove(bookId); // 学成即清进度（稀疏纪律：无进度不落档）
        return true;
    }

    // ==========================================================
    //  技能书研读进度（图书馆活动驱动，取代旧研读槽 tick）
    // ==========================================================

    /**
     * 技能书阅读累计（图书馆看书的每 tick 调用）：带增速倍率（批注本
     * 1.5 倍速），非整数部分进 {@link #learnCarry} 小数桶不丢进度。
     *
     * @return 本次累计<b>刚好读完</b>时 true（调用方做学成结算）
     */
    public boolean progressSkillBook(String bookId, double gain) {
        if (bookId == null || this.learned.contains(bookId)) {
            return false; // 已学过的书再读没有收益
        }
        var book = com.bmf.bmfstown.book.SpecialBookRegistry.byId(bookId);
        if (book == null) {
            return false; // 登记表没有（池子换版等）：进度保留但不推进
        }
        this.learnCarry += gain;
        long whole = (long) this.learnCarry;
        if (whole <= 0) {
            return false;
        }
        this.learnCarry -= whole;
        long progress = this.learnProgress.merge(bookId, whole, Long::sum);
        return progress >= book.learnTicks();
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

    /** 按排序下标选为生效（知识页点击已学行）：越界静默忽略。 */
    public void equipByIndex(int index) {
        java.util.List<String> sorted = learnedSorted();
        if (index >= 0 && index < sorted.size()) {
            equip(sorted.get(index));
        }
    }

    /**
     * 直接学会一本书（调试命令 {@code /bmfstown booklearn} 用；正式通路是
     * 图书馆阅读累计）。登记表外的 id 静默忽略。不扣学识（调试通路）。
     */
    public void forceLearn(String bookId) {
        if (com.bmf.bmfstown.book.SpecialBookRegistry.byId(bookId) == null) {
            return;
        }
        this.learned.add(bookId);
        this.learnProgress.remove(bookId);
        this.equippedBookId = bookId;
    }

    // ==========================================================
    //  落档
    // ==========================================================

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Equipped", this.equippedBookId);
        tag.putInt("Literacy", this.literacy);
        tag.putInt("TotalRead", this.totalBooksRead);
        ListTag seedsTag = new ListTag();
        for (long seed : this.readSeeds) {
            seedsTag.add(net.minecraft.nbt.LongTag.valueOf(seed));
        }
        tag.put("ReadSeeds", seedsTag);
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
        tag.putDouble("LearnCarry", this.learnCarry);
        return tag;
    }

    public void loadFrom(CompoundTag tag) {
        this.equippedBookId = tag.getString("Equipped");
        this.literacy = tag.getInt("Literacy");
        this.totalBooksRead = tag.getInt("TotalRead");
        this.readSeeds.clear();
        ListTag seedsTag = tag.getList("ReadSeeds", Tag.TAG_LONG);
        for (int i = 0; i < seedsTag.size(); i++) {
            if (seedsTag.get(i) instanceof net.minecraft.nbt.LongTag longTag) {
                this.readSeeds.add(longTag.getAsLong());
            }
        }
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
        // 学过但进度残留（旧档/异常）：清掉（稀疏纪律）
        this.learned.forEach(this.learnProgress::remove);
        this.learnCarry = tag.getDouble("LearnCarry");
    }
}
