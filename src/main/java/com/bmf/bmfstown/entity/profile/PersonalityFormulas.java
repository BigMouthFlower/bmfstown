package com.bmf.bmfstown.entity.profile;

import com.bmf.bmfstown.config.Config;

/**
 * 性格轴公式集（v0.14 多轴改版）：每条轴"影响什么"的算式唯一来源，
 * 服务端生效与客户端面板展示共用（显示==生效，沿 #21 口径）。
 *
 * <h2>全局规则（决策 #44 定稿）</h2>
 * <ul>
 *   <li>心情事件基准值沿用 {@code Config.MOOD_TRAIT_VALUE}（默认 ±3）；</li>
 *   <li>轴值只在事件自身基准带内滑动，永不越界；</li>
 *   <li><b>心态乘区在基准值之后应用</b>：实得 = 基准 × 乘数，可越过 ±3
 *       （乐观满格 +3 → +4.5，悲观满格 −3 → −4.5，用户定稿）；</li>
 *   <li>实得四舍五入取整（远离零方向：±0.5 都进位到 ±1）。</li>
 * </ul>
 */
public final class PersonalityFormulas {

    private PersonalityFormulas() {
    }

    // ==========================================================
    //  心态 HOPE：全局心情修正（好事×(1+c)、坏事×(1−c)，c=(v−50)/100）
    // ==========================================================

    /** 心态修正乘数。positiveEvent=true 算好事（乘 1+c），否则坏事（乘 1−c）。 */
    public static double hopeMultiplier(int hope, boolean positiveEvent) {
        double c = (hope - PersonalityAxis.NEUTRAL) / 100.0;
        return positiveEvent ? 1.0 + c : 1.0 - c;
    }

    /**
     * 轴事件基准值 → 实得心情整数。
     * 流程：轴基准（double）→ 心态乘区 → 远离零取整。基准为 0 直接得 0。
     */
    public static int resolve(int hope, double eventBase) {
        if (eventBase == 0) {
            return 0;
        }
        double v = eventBase * hopeMultiplier(hope, eventBase > 0);
        return signRound(v);
    }

    /** 远离零方向的四舍五入（-1.5 → -2，+1.5 → +2）。 */
    private static int signRound(double x) {
        int r = (int) Math.round(Math.abs(x));
        return x < 0 ? -r : r;
    }

    /** 线性过零滑动：base × (v−50)/50，中位为 0，两端 ±base。 */
    private static double throughZero(int v, int base) {
        return base * (v - PersonalityAxis.NEUTRAL) / 50.0;
    }

    // ==========================================================
    //  心情事件基准值（每条返回"未经心态修正的基准"，0 = 该轴对此事件无反应）
    // ==========================================================

    /** 好战·击杀：仅 v>50 生效，v=100 → +base。 */
    public static double killBase(int warlike, int base) {
        return warlike > PersonalityAxis.NEUTRAL ? throughZero(warlike, base) : 0;
    }

    /** 好战·久无击杀：仅 v>50 生效（温和者杀不杀无所谓）。 */
    public static double noKillBase(int warlike, int base) {
        return warlike > PersonalityAxis.NEUTRAL ? -throughZero(warlike, base) : 0;
    }

    /**
     * 勇气·遇敌（用户定稿 2026-11-08）：只有胆小（v&lt;35 分界线）才怕怪，
     * 线性 −base·(35−v)/35——v=0 满额封顶 −base（基准 3 时即 −3），
     * v≥35 完全无感。中位以上也怕怪会吓到太多人。
     */
    public static double scareBase(int courage, int base) {
        if (courage >= (int) COURAGE_SPLIT_LINE) {
            return 0;
        }
        return -base * (COURAGE_SPLIT_LINE - courage) / COURAGE_SPLIT_LINE;
    }

    /** 勇气·整日平安（用户定稿 2026-11-08 同步对齐）：仅 v<35 生效——能被吓到的人，悬着的心才会放下。 */
    public static double safeBase(int courage, int base) {
        if (courage >= (int) COURAGE_SPLIT_LINE) {
            return 0;
        }
        return base * (COURAGE_SPLIT_LINE - courage) / COURAGE_SPLIT_LINE;
    }

    /** 社交·同伴扎堆：对冲型，v=100 → +base，v=0 → −base（人堆让独处者烦躁）。 */
    public static double crowdBase(int social, int base) {
        return throughZero(social, base);
    }

    /** 社交·孤身一人：与扎堆镜像，v=100 → −base，v=0 → +base（独处者自在）。 */
    public static double lonelyBase(int social, int base) {
        return -throughZero(social, base);
    }

    /** 暴力·生物死亡 / 爆炸（烟花除外）：线性过零，嗜血 v=100 → +base，仁慈 v=0 → −base。 */
    public static double violenceBase(int cruelty, int base) {
        return throughZero(cruelty, base);
    }

    /** 暴力·生物繁殖：与死亡镜像（嗜血嫌吵闹，仁慈欣喜）。 */
    public static double birthBase(int cruelty, int base) {
        return -throughZero(cruelty, base);
    }

    /** 嫉妒·玩家好装备心情惩罚：仅 v>80 生效，v=80 → 0，v=100 → −base（2026-11-08 定稿阈值）。 */
    public static double gearMoodBase(int envy, int base) {
        return envy > 80 ? -base * (envy - 80) / 20.0 : 0;
    }

    // ==========================================================
    //  行为旋钮
    // ==========================================================

    /**
     * 勇气 → 战士锁敌半径修正（格，加算）：
     * v>50 正向最高 +{@code Config.AXIS_AGGRO_BONUS}（默认 10）、
     * v≤50 负向最高 −{@code Config.AXIS_AGGRO_PENALTY}（默认 5），线性。
     */
    public static double aggroRadiusCorrection(int courage) {
        if (courage > PersonalityAxis.NEUTRAL) {
            return Config.AXIS_AGGRO_BONUS.get() * (courage - PersonalityAxis.NEUTRAL) / 50.0;
        }
        return -Config.AXIS_AGGRO_PENALTY.get() * (PersonalityAxis.NEUTRAL - courage) / 50.0;
    }

    /** 勇气 → 逃跑触发距离（格）：仅 v<50 会逃，v=0 最远 {@code Config.AXIS_FLEE_MAX_DIST}（默认 8）。 */
    /**
     * 勇气分界线（用户 2026-11-08 定稿：35）。
     *
     * <p>还手/逃跑/助战共用这条线：轴值 ≥35 不胆小（还手、响应邻居呼救），
     * &lt;35 胆小（见怪就逃，不还手不参战）。取 35 而不是中位——
     * 用户实测反馈：50 一刀切时一半镇民见死不救，太灾难。
     */
    public static final double COURAGE_SPLIT_LINE = 35.0D;

    public static double fleeDistance(int courage) {
        if (courage >= COURAGE_SPLIT_LINE) {
            return 0; // 分界线以上不逃跑（2026-11-08 定稿：35）
        }
        return Config.AXIS_FLEE_MAX_DIST.get() * (COURAGE_SPLIT_LINE - courage) / COURAGE_SPLIT_LINE;
    }

    /**
     * 勇气/胆量 → 攻击伤害乘数：v<50 线性降到 v=0 时 ×(1−30%)（2026-11-08 定稿）；
     * <b>中位以上不增伤</b>（恒 ×1.0，用户明确"不然太厉害了"）。
     */
    public static double attackMultiplier(int courage) {
        if (courage >= PersonalityAxis.NEUTRAL) {
            return 1.0;
        }
        return 1.0 - Config.AXIS_ATTACK_PENALTY_PERCENT.get() / 100.0
                * (PersonalityAxis.NEUTRAL - courage) / 50.0;
    }

    /**
     * 坚韧 → 心情打折线位移：居中值=现行阈值（默认 40），极端位移 ±{@code Config.AXIS_GRIT_SHIFT}（默认 10）。
     * 坚韧高 → 线下移（更能扛），脆弱 → 线上移（更容易崩）。
     */
    public static int slowThreshold(int grit) {
        return (int) Math.round(Config.MOOD_SLOW_THRESHOLD.get()
                - Config.AXIS_GRIT_SHIFT.get() * (grit - PersonalityAxis.NEUTRAL) / 50.0);
    }

    /** 坚韧 → 罢工线位移（与打折线平行，间距不变）。居中值=现行阈值（默认 20）。 */
    public static int strikeThreshold(int grit) {
        return (int) Math.round(Config.MOOD_STRIKE_THRESHOLD.get()
                - Config.AXIS_GRIT_SHIFT.get() * (grit - PersonalityAxis.NEUTRAL) / 50.0);
    }

    /** 专注 → 工作效率段：±{@code Config.AXIS_FOCUS_PERCENT}（默认 5%），v=50 时 ×1.0。 */
    public static double focusMultiplier(int focus) {
        return 1.0 + Config.AXIS_FOCUS_PERCENT.get() / 100.0
                * (focus - PersonalityAxis.NEUTRAL) / 50.0;
    }

    /**
     * 晨夜 → 作息窗口偏移（tick）：早鸟（v>50）全窗口提前、夜猫子推迟。
     * 返回值直接加到各窗口边界上：早鸟为负（更早），最高 ±{@code Config.AXIS_CHRONO_OFFSET}（默认 1005t）。
     */
    public static int chronoShift(int chrono) {
        return -(int) Math.round(Config.AXIS_CHRONO_OFFSET.get()
                * (chrono - PersonalityAxis.NEUTRAL) / 50.0);
    }

    /** 专注 → 摸鱼概率：仅 v<40，v=0 最高 {@code Config.AXIS_GOOF_PERCENT}（默认 40%），每工作段判一次。 */
    public static double goofChance(int focus) {
        if (focus >= 40) {
            return 0;
        }
        return Config.AXIS_GOOF_PERCENT.get() / 100.0 * (40 - focus) / 40.0;
    }

    // ==========================================================
    //  展示辅助
    // ==========================================================

    /**
     * 出中性区的轴的档位标签（客户端面板用）：中性轴不出现。
     * 返回形如 "心态 乐天派" 的可见字符串列表。
     */
    public static java.util.List<String> visibleTags(
            java.util.function.Function<String, String> i18n, int[] axes) {
        java.util.List<String> tags = new java.util.ArrayList<>();
        if (axes == null || axes.length != PersonalityAxis.COUNT) {
            return tags;
        }
        for (PersonalityAxis axis : PersonalityAxis.values()) {
            int v = axes[axis.ordinal()];
            int tier = PersonalityAxis.tierOf(v);
            if (tier == 3) {
                continue; // 中性区无标签
            }
            tags.add(i18n.apply(axis.displayName()) + " " + i18n.apply(axis.tagKey(tier)));
        }
        return tags;
    }
}
