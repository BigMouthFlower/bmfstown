package com.bmf.bmfstown.entity.profile;

import net.minecraft.util.RandomSource;

import java.util.Locale;

/**
 * 性格轴（v0.14 多轴改版，决策 #44）：每个镇民在 10 条轴上各有一个 0~100 的连续值，
 * 出生按正态 μ=50 σ=15 抽取、截断到 [0,100]；40~60 为中性区（无标签、效果居中）。
 *
 * <p>二元性不是抽出来的，是读出来的——极名、文案、系数全部由轴值推导。
 * 数值公式统一在 {@link PersonalityFormulas}（服务端生效与客户端展示共用一份）。
 *
 * <p>首批 10 轴（2026-11-08 用户定稿）与旧 9 条词条的迁移映射：
 * <ul>
 *   <li>心态 HOPE = 乐观/悲观合并（全局心情修正系数）；</li>
 *   <li>好战 WARLIKE = 好战+易怒合并（击杀心情 + 战斗锁敌半径修正）；</li>
 *   <li>坚韧 GRIT = 抗压合并（心情打折线/罢工线位移）；</li>
 *   <li>勇气 COURAGE = 胆小（遇敌心情 + 逃跑/还手/锁敌行为）；</li>
 *   <li>暴力 CRUELTY = 仁慈⇌嗜血（死亡/爆炸/繁殖，吸收和平主义与爆炸主义）；</li>
 *   <li>嫉妒 ENVY = 攀比（评判玩家随身装备）；</li>
 *   <li>社交 SOCIAL = 爱热闹（社交事件概率，系统未建先记心情）；</li>
 *   <li>专注 FOCUS = 工作效率 ±5% + 摸鱼；</li>
 *   <li>好奇 CURIOUS = 闲暇随机事件概率（系统未建，休眠占位）；</li>
 *   <li>晨夜 CHRONO = 作息偏移（夜猫子⇌早鸟）。</li>
 * </ul>
 */
public enum PersonalityAxis {

    /** 心态：悲观 ⇄ 乐观。 */
    HOPE("心态"),
    /** 好战：温和 ⇄ 好战。 */
    WARLIKE("好战"),
    /** 坚韧：脆弱 ⇄ 坚韧。 */
    GRIT("坚韧"),
    /** 勇气：胆小 ⇄ 勇敢。 */
    COURAGE("勇气"),
    /** 暴力：仁慈 ⇄ 嗜血。 */
    CRUELTY("暴力"),
    /** 嫉妒：淡泊 ⇄ 攀比。 */
    ENVY("嫉妒"),
    /** 社交：喜独处 ⇄ 爱扎堆。 */
    SOCIAL("社交"),
    /** 专注：毛躁 ⇄ 专注。 */
    FOCUS("专注"),
    /** 好奇：懒得看 ⇄ 凑热闹。 */
    CURIOUS("好奇"),
    /** 晨夜：夜猫子 ⇄ 早鸟。 */
    CHRONO("晨夜");

    /** 轴数量（快照预留 32 槽位，首批用 10）。 */
    public static final int COUNT = values().length;

    /** 正态中心（中性值）。 */
    public static final int NEUTRAL = 50;
    /** 正态标准差。 */
    public static final int SIGMA = 15;
    /** 中性区下界（含）。 */
    public static final int NEUTRAL_MIN = 40;
    /** 中性区上界（含）。 */
    public static final int NEUTRAL_MAX = 60;

    private final String zhName;

    PersonalityAxis(String zhName) {
        this.zhName = zhName;
    }

    public String zhName() {
        return this.zhName;
    }

    /** 轴名语言 key。 */
    public String displayName() {
        return "personality.bmfstown.axis." + name().toLowerCase(Locale.ROOT);
    }

    /** 轴侧写语言 key（该轴的机制一句话说明）。 */
    public String descKey() {
        return "personality.bmfstown.axis.desc." + name().toLowerCase(Locale.ROOT);
    }

    /** 档位标签语言 key（tier 见 {@link #tierOf(int)}；中性档不显示无需文案）。 */
    public String tagKey(int tier) {
        return "personality.bmfstown.axis.tag." + tier + "." + name().toLowerCase(Locale.ROOT);
    }

    /**
     * 两极名语言 key（面板坐标条悬停提示用）：min=低值端、max=高值端。
     * 两极不是档位——只是告诉玩家轴的哪头是哪头，数值位置仍连续显示。
     */
    public String poleKey(boolean max) {
        return "personality.bmfstown.axis.pole." + (max ? "max." : "min.")
                + name().toLowerCase(Locale.ROOT);
    }

    /**
     * 档位划分（0~6）：0–9 / 10–24 / 25–39 / <b>40–60 中性</b> / 61–75 / 76–90 / 91–100。
     * 与矮人要塞概率表同构（0.4/2/8.5/78/8.5/2/0.4%）。
     */
    public static int tierOf(int v) {
        if (v <= 9) {
            return 0;
        }
        if (v <= 24) {
            return 1;
        }
        if (v <= 39) {
            return 2;
        }
        if (v <= NEUTRAL_MAX) {
            return 3;
        }
        if (v <= 75) {
            return 4;
        }
        if (v <= 90) {
            return 5;
        }
        return 6;
    }

    /**
     * 全轴随机抽取：每轴独立正态 μ=50 σ=15，截断 [0,100]。
     * 用于出生懒生成与旧档迁移（用户拍板：随机重抽，不做语义映射）。
     */
    public static int[] rollAll(RandomSource random) {
        int[] values = new int[COUNT];
        for (int i = 0; i < COUNT; i++) {
            values[i] = clampAxis((int) Math.round(NEUTRAL + SIGMA * random.nextGaussian()));
        }
        coupleAxes(values);
        return values;
    }

    /**
     * 轴间耦合（用户定稿 2026-11-08）：好战端（&gt;50）的人不胆小——
     * 勇气至少抬到还手/逃跑分界线。否则"嗜杀却永远逃跑"的人好战轴
     * 永久休眠（永远不敢动手，killBase 形同虚设）。中位及以下不干预，
     * 保持正态分布的随机性。
     */
    private static void coupleAxes(int[] values) {
        if (values[WARLIKE.ordinal()] > NEUTRAL) {
            values[COURAGE.ordinal()] = Math.max(values[COURAGE.ordinal()],
                    (int) PersonalityFormulas.COURAGE_SPLIT_LINE);
        }
    }

    /** 轴值钳制。 */
    public static int clampAxis(int v) {
        return Math.max(0, Math.min(100, v));
    }
}
