package com.bmf.bmfstown.entity.profile;

import com.bmf.bmfstown.config.Config;

/**
 * 职业效率的统一算式（v0.8.1）：客户端展示与服务端生效共用这一份。
 *
 * <p>公式（按顺序连乘）：
 * <pre>效率 = 基础(熟练度) × 强壮perk(选中才乘) × 心情(低于阈值才乘)</pre>
 *
 * <h2>为什么收拢成一个类</h2>
 * 服务端算实际效果、客户端画面板公式，历史上是两处各算各的——
 * 展示与实际生效一旦改了一边忘一边，玩家看到的数字就是假的。
 * 现在两边都调这里，改公式只动一个文件。
 */
public final class EfficiencyMath {

    private EfficiencyMath() {
    }

    /** 基础段：熟练度段位决定（定稿 A 方案曲线 {@code 0.6 + 0.1×(Lv-1)}）。 */
    public static double baseOf(int xp) {
        return SkillTiers.efficiencyOf(xp);
    }

    /** 强壮 perk 段：乘法加成（默认 ×1.1，{@code perk.strongBonusPercent} 可配）。 */
    public static double strongMultiplier() {
        return 1.0 + Config.PERK_STRONG_BONUS_PERCENT.get() / 100.0;
    }

    /** 心情段：低于阈值（默认 40）时打折（默认 ×0.7），否则 ×1.0（坚韧轴中位口径）。 */
    public static double moodMultiplier(double mood) {
        return moodMultiplier(mood, PersonalityAxis.NEUTRAL);
    }

    /**
     * 心情段（v0.14 坚韧轴版）：阈值随坚韧轴位移——
     * {@code PersonalityFormulas.slowThreshold(grit)}：坚韧高→线下移更能扛，脆弱→线上移更易崩。
     */
    public static double moodMultiplier(double mood, int grit) {
        int threshold = PersonalityFormulas.slowThreshold(grit);
        if (threshold > 0 && mood < threshold) {
            return Config.MOOD_SLOW_MULTIPLIER_PERCENT.get() / 100.0;
        }
        return 1.0;
    }

    /**
     * 工人精通分支的合成速度乘数（效率 1.2 / 节能 0.8 / 未选 1.0）。
     * 速度语义：+20% 速度 = 耗时 ÷1.2。
     */
    public static double workerSpeedMultiplier(String adeptChoice) {
        if ("efficiency".equals(adeptChoice)) {
            return 1.2;
        }
        if ("frugal".equals(adeptChoice)) {
            return 0.8;
        }
        return 1.0;
    }

    /** 完整计算（全中性轴口径）：基础 × 强壮(选中才乘) × 心情 × 专注。 */
    public static double total(int xp, double mood, boolean strongPerk) {
        return total(xp, mood, strongPerk, PersonalityAxis.NEUTRAL, PersonalityAxis.NEUTRAL);
    }

    /**
     * 完整计算（v0.14 性格轴版）：
     * <pre>效率 = 基础(熟练度) × 强壮perk × 心情打折（坚韧轴位移阈值） × 专注段(±5%)</pre>
     * 专注段：{@code PersonalityFormulas.focusMultiplier(focus)}，v=50 时 ×1.0。
     */
    public static double total(int xp, double mood, boolean strongPerk, int grit, int focus) {
        double eff = baseOf(xp);
        if (strongPerk) {
            eff *= strongMultiplier();
        }
        eff *= moodMultiplier(mood, grit);
        eff *= PersonalityFormulas.focusMultiplier(focus);
        return eff;
    }
}
