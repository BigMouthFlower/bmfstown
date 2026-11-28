package com.bmf.bmfstown.entity.domain;

import net.minecraft.nbt.CompoundTag;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 终身统计账本：一个人<b>这辈子干了多少活</b>（2026-11-28）。
 *
 * <h2>为什么和 {@link Status} 分开</h2>
 * {@code Status} 里那些计数（{@code eventCountToday}、{@code lastKillDay}）是
 * <b>每日清零的节奏量</b>，用来算心情；这里是<b>永不清零的终身量</b>，用来刻碑、
 * 填履历页。同一个字段承担两种语义就是坑65（计划 vs 真持有）的重演——
 * 一旦有人忘了清零，当天心情结算就被"他十年前杀过的怪"污染。
 * <b>两个语义两个字段，绝不复用。</b>
 *
 * <h2>为什么 key 是 String 而不是 enum</h2>
 * 统计项会随职业越做越多（现在 9 种职业，以后还有渔/园/牧三条线），
 * 每加一项都改 enum 要动序列化。String key 让"报一个新统计"变成一行
 * {@code ledger.add("fish_caught", n)}，<b>这类改动服务端零改动</b>。
 * 代价是拼错 key 不会报错——用 {@link Keys} 常量收口，别裸写字符串。
 *
 * <h2>什么不该进来</h2>
 * <b>已经有了的终身量不要抄进来</b>。{@code KnowledgeState.totalBooksRead}
 * 本来就是终身累计（不清零），它是唯一真相源；再抄一份必漂（坑65 同款）。
 * 判据：<b>先 grep 这个量存不存在</b>，存在就直接读，不存在才进 ledger。
 *
 * <p>「活了几天」「在镇几天」这类<b>由日期算出来的量也不进来</b>——
 * 它们能从 {@code birthDay}/{@code arrivalDay} 现算，进 Map 就是第三个真相源
 * （见本类 {@link Keys#DAYS_ALIVE} 的注释）。
 */
public final class ResidentLedger {

    /**
     * 所有已知统计项的 key。
     *
     * <p><b>别裸写字符串</b>——拼错不会报错，只会让这一项永远显示不出来。
     */
    public static final class Keys {
        /** 活了几天。<b>不落档</b>，由 {@code today − birthDay} 现算（活着时）。 */
        public static final String DAYS_ALIVE = "days_alive";
        /** 在镇几天。<b>不落档</b>，由 {@code today − arrivalDay} 现算（活着时）。 */
        public static final String DAYS_IN_TOWN = "days_in_town";
        public static final String MOBS_KILLED = "mobs_killed";
        public static final String ITEMS_CRAFTED = "items_crafted";
        public static final String BOOKS_WRITTEN = "books_written";
        /** 读过的书。<b>真相源在 {@code KnowledgeState}，不许往 ledger 里写</b>。 */
        public static final String BOOKS_READ = "books_read";
        public static final String POTIONS_BREWED = "potions_brewed";
        public static final String FIREWORKS_MADE = "fireworks_made";
        public static final String MATERIALS_SAVED = "materials_salvaged";
        public static final String DRINKS_SERVED = "drinks_served";
        /**
         * 挤的奶 / 采的蜜 / 收成：<b>预留键，当前没有任何挂点</b>。
         *
         * <p>渔·园·牧三线职业还没做（roadmap 待拍板），先占好 key 与 lang，
         * 做的时候只需在结算点加一行 {@code add(...)}，不用动面板和碑文。
         * <b>值为 0 的项不会显示</b>（履历页与碑文都过滤 0），所以占位无副作用。
         */
        public static final String MILK_COLLECTED = "milk_collected";
        public static final String HONEY_COLLECTED = "honey_collected";
        public static final String HARVESTS = "harvests";
        /**
         * 盘库次数：<b>预留键，当前没有挂点</b>。
         *
         * <p>⚠ 别被名字误导——现有的 {@code TownCensusManager} 是<b>镇级</b>扫描
         * （簿记员帮全镇清点物资），不归属于某个居民，所以「这个居民盘了几次库」
         * 现在根本不存在这个量。将来若要做，触发点必须是"簿记员本人发起了一次扫描"。
         */
        public static final String CENSUS_RUNS = "census_runs";

        private Keys() {
        }
    }

    /** 行为 key → 终身累计次数。用 LinkedHashMap 保持登记顺序（便于调试看待）。 */
    private final Map<String, Integer> stats = new LinkedHashMap<>();

    /** 累加（最常用的入口）。n <= 0 直接忽略。 */
    public void add(String key, int n) {
        if (key == null || n <= 0) {
            return;
        }
        this.stats.merge(key, n, Integer::sum);
    }

    /** 直接置值（用于"天数"这类由日期算出来的量，若真要落档时用）。 */
    public void set(String key, int value) {
        if (key == null) {
            return;
        }
        this.stats.put(key, value);
    }

    public int get(String key) {
        return key == null ? 0 : this.stats.getOrDefault(key, 0);
    }

    /** 只读视图（刻碑/发网络包时整表拷出去）。 */
    public Map<String, Integer> view() {
        return Collections.unmodifiableMap(this.stats);
    }

    public boolean isEmpty() {
        return this.stats.isEmpty();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        CompoundTag m = new CompoundTag();
        this.stats.forEach(m::putInt);
        tag.put("Stats", m);
        return tag;
    }

    public void loadFrom(CompoundTag tag) {
        this.stats.clear();
        if (tag == null) {
            return;
        }
        CompoundTag m = tag.getCompound("Stats");
        for (String k : m.getAllKeys()) {
            int v = m.getInt(k);
            if (v > 0) {
                this.stats.put(k, v);
            }
        }
    }
}