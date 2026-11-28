package com.bmf.bmfstown.entity.domain;

import com.bmf.bmfstown.calendar.TownCalendar;
import com.bmf.bmfstown.config.Config;

import net.minecraft.util.RandomSource;

/**
 * 年龄与寿命的抽样（2026-11-28，决策 #139 / #140）。
 *
 * <p><b>纯函数</b>：只依赖 {@link RandomSource}，不碰世界、不碰实体。
 * 这样整条分布可以单测（5000 抽验均值/方差/分位），不必开 GameTestServer。
 *
 * <h2>三个量，两种性质（这是本类最要紧的约定）</h2>
 * <ul>
 *   <li>{@link #rollAgeYears} / {@link #rollDayOffset}：<b>投影</b>。
 *       它们不是"发生过的事件"，只是为了让"他 29 岁"这件事有个说得过去的来历。
 *       不要单独存字段——生日一算就都有了（见 {@code Identity}）。</li>
 *   <li>{@link #rollLifespanYears}：<b>设定值</b>。虽然也是抽的，但它必须落档：
 *       重抽会让"他 68 岁那年死"变成随机翻牌。</li>
 * </ul>
 *
 * <h2>为什么先抽寿命再抽年龄</h2>
 * 年龄的上限由 {@link #lifespanCapYears} 从寿命倒推（"至少还能活 N 年"）。
 * 顺序反了就得先抽年龄再重抽寿命，或者接受"刚来就要死"。
 */
public final class AgingRolls {

    /** 出现年龄的硬下限。居民都是成年人，没有婴儿（§1.4「出现」不是「出生」）。 */
    public static final int MIN_AGE_YEARS = 18;

    /**
     * 年龄分布的尺度参数（Erlang(2) 的尺度，年）。
     *
     * <p><b>不进配置</b>：它是形状参数，调它等于换一种人群构成，
     * 属于设计决定而不是玩家口味。写死在这里。
     */
    public static final double AGE_SCALE_YEARS = 6.0;

    /** 一天多少 tick。换算"日"与"tick"用（过客时间戳是 dayTime）。 */
    public static final long TICKS_PER_DAY = 24000L;

    /**
     * "这个日期未知"的哨兵值。
     *
     * <h2>为什么不能用 {@code -1}（2026-11-28 实测 bug 的根因）</h2>
     * 生日是<b>投影</b>：{@code birthDay = today − (age×96 + offset)}。
     * 世界绝对天数从 0 开始，一个 29 岁的人在开档第 12 天被投影到
     * {@code 12 − 2824 = −2812}——<b>负数是完全合法的生日</b>。
     * 而旧实现用 {@code birthDay < 0} 判"未知"，于是：
     * <ul>
     *   <li>所有开档前约 {年龄}年的居民，享年/年龄全部被判成"未知"；</li>
     *   <li>履历页"活了/在镇/年龄"三行一起消失；</li>
     *   <li>碑文只剩一行死因（用户实测："显示他的生平信息什么都没有，
     *       还是只有一个死法"）。</li>
     * </ul>
     * 哨兵必须落在<b>投影永远撞不到</b>的区间。年龄上限 {@code AGE_CAP_YEARS}
     * 与年内偏移 {@code <DAYS_PER_YEAR} 一起界定了下界，用
     * {@link Long#MIN_VALUE} 绝对安全。
     *
     * <p><b>老档兼容</b>：旧实现缺键时落的默认值就是 {@code -1}，那些档里
     * {@code LifespanDays} 同样无效——{@code Identity.load} 用这一条把历史脏值
     * 一并归为未知（见那里的注释）。
     */
    public static final long UNKNOWN_DAY = Long.MIN_VALUE;

    private AgingRolls() {
    }

    // ==========================================================
    //  寿命
    // ==========================================================

    /**
     * 抽寿命（公元纪年）。<b>纯正态，不截断。</b>
     *
     * <p>用户 2026-11-28 明确否掉了截断："正态分布有小概率事件发生，
     * 让他们发生呗。我觉得这不是一个问题吧。"
     * 所以这里<b>没有</b>任何 {@code clamp} / 拒绝采样 —— 一行搞定。
     *
     * <p>被否掉的那个版本的教训记在坑77：分布要"掐头去尾"必须用拒绝采样，
     * 用 {@code clamp} 会在两端堆出一根柱子（0.6% 的样本挤在同一个值上）。
     */
    public static double rollLifespanYears(RandomSource random) {
        double mean = Config.LIFESPAN_MEAN_YEARS.get();
        double sd = Config.LIFESPAN_SD_YEARS.get();
        if (sd <= 0.0D) {
            return mean;
        }
        return mean + sd * random.nextGaussian();
    }

    /**
     * 出现年龄的上限（岁）：寿命倒推，留出"至少还能活 N 年"和一年余量。
     *
     * <p>那个 <b>{@code -1.0}</b> 是给"生日还没到"留的余量：
     * 年龄是整岁，实际可能已经过了 0~95 天，最坏情况要多吃掉将近一整年。
     * 去掉它会让"最少还能活 5 年"变成"最少还能活 4.01 年"（用例会红）。
     */
    public static int lifespanCapYears(double lifespanYears) {
        int minRemaining = Config.MIN_REMAINING_YEARS.get();
        double cap = Math.floor(lifespanYears - minRemaining - 1.0D);
        return (int) Math.max(MIN_AGE_YEARS, Math.min(Config.AGE_CAP_YEARS.get(), cap));
    }

    // ==========================================================
    //  年龄
    // ==========================================================

    /**
     * 抽出现年龄（整岁）。
     *
     * <p>分布 = <b>Erlang(2) 平移 18</b>：两个独立的指数分布相加再 +18，
     * 均值 18 + 2×6 = <b>29.0 岁</b>，sd ≈ 8.5（截断后 7.5），
     * 右偏、集中青年、硬下限 18。这符合"游荡到镇上的多半是年轻人"。
     *
     * <p><b>为什么是拒绝采样而不是 clamp</b>：年龄要压在上限下，
     * {@code Math.min(age, cap)} 会让所有超限的都堆在 cap 上（一根柱子），
     * 而且年龄分布会变成一个尖峰。这里用循环重抽，直到落在 cap 以内——
     * 上限通常远高于均值，实测平均 1.02 次就中，代价可忽略。
     *
     * @param lifespanYears 先抽好的寿命（必须 > ageCap，否则会被压到下限）
     */
    public static int rollAgeYears(RandomSource random, double lifespanYears) {
        int cap = lifespanCapYears(lifespanYears);
        if (cap <= MIN_AGE_YEARS) {
            return MIN_AGE_YEARS;
        }
        for (int i = 0; i < 32; i++) {
            double u1 = 1.0D - random.nextDouble();
            double u2 = 1.0D - random.nextDouble();
            // Erlang(2) = 两个指数之和；-ln(u) 是尺度 1 的指数
            double raw = MIN_AGE_YEARS - AGE_SCALE_YEARS * (Math.log(u1) + Math.log(u2));
            int whole = (int) raw; // 截断，不四舍五入（年龄是"满了多少岁"）
            if (whole <= cap) {
                return whole;
            }
        }
        // 理论上到不了（cap 远大于均值）。但和 lifespanCapYears 的 max 一样，
        // 这是"到不了也必须写"的兜底——宁可给一个偏大的年龄，也不能死循环。
        return cap;
    }

    /**
     * 当前整岁：到生日才 +1（整数除法天然实现，不许用浮点再截断）。
     *
     * <p>{@code birthDay} 可能是负数（投影到开档之前），**负数是合法值**，
     * 判未知只认 {@link #UNKNOWN_DAY}（见该常量的注释）。
     */
    public static int ageAt(long today, long birthDay) {
        if (birthDay == UNKNOWN_DAY || today < birthDay) {
            return -1;
        }
        return (int) ((today - birthDay) / TownCalendar.DAYS_PER_YEAR);
    }

    // ==========================================================
    //  生日（两个颗粒度）
    // ==========================================================

    /**
     * 抽年内偏移（0 ~ 一年天数−1）。<b>必须独立抽</b>，不能从年龄推。
     *
     * <p>这是坑78 的正面写法。原稿让年龄是 {@code double}，
     * {@code birthDay = today − age×96} 的小数部分"碰巧"散开了生日；
     * 年龄改成整岁后，{@code birthDay % 96} 恒为 0 ⇒ <b>全镇同一天生日</b>。
     * 同一天刷出来的 12 个人全在同一天过生日，这不是"巧合"是 bug。
     *
     * <p>生日撞车本身是真实的（12 人里撞一对的概率 51%，生日悖论），
     * **判据是"不能全部相同"**，不是"不能相同"。
     */
    public static int rollDayOffset(RandomSource random, int maxExclusive) {
        int bound = Math.max(1, Math.min(TownCalendar.DAYS_PER_YEAR, maxExclusive));
        return random.nextInt(bound);
    }

    /**
     * 年内偏移的上限：由寿命倒推出的年龄上限决定，防"短寿 + 大偏移"跌破保险。
     *
     * <p>正常寿命下 {@code lifespanCapYears} 远大于实际年龄，这里恒等于
     * 一年天数（不压缩）。只有抽到 {@code 寿命 < 24 年} 这种极端样本、
     * cap 被 {@code max(18,…)} 兜在 18 岁，才需要压偏移——
     * 实测 100 万次触发 <b>0</b> 次。但这一行不能靠概率省掉：
     * 它和 {@code max(MIN_AGE_YEARS, …)} 是同一类"理论到不了但必须写"的兜底。
     *
     * @return 允许的最大偏移（不含）；正常情况 = 一年天数
     */
    public static int maxDayOffset(double lifespanYears, int ageYears) {
        double remainingYears = lifespanYears - ageYears
                - Config.MIN_REMAINING_YEARS.get();
        double allowedDays = remainingYears * TownCalendar.DAYS_PER_YEAR - 1.0D;
        if (allowedDays >= TownCalendar.DAYS_PER_YEAR - 1) {
            return TownCalendar.DAYS_PER_YEAR;
        }
        return (int) Math.max(1, Math.min(TownCalendar.DAYS_PER_YEAR, allowedDays + 1));
    }

    /** 由"今天 + 整岁 + 年内偏移"反推生日（<b>投影，不是出生事件</b>）。 */
    public static long birthDayFrom(long today, int ageYears, int dayOffset) {
        long yearDays = TownCalendar.DAYS_PER_YEAR;
        return today - (ageYears * yearDays + dayOffset);
    }

    // ==========================================================
    //  天数（"活了几天 / 在镇几天"）
    // ==========================================================

    /**
     * 从某个"起点日"算到今天多少天。<b>这是"算"，不是"记"。</b>
     *
     * <p><b>为什么不维护一个每日 ++ 的计数器</b>（这一条必须写下来）：
     * 计数器是"每天必须准点加一次"的活数据，会遇到四类问题——
     * 服务器停机几天没人补、{@code /time set} 倒退变负数、跨存档复制错位、
     * 老档没有起点不知从哪天算。<b>起点日一次写死，这四个问题一个都没有。</b>
     *
     * <p>判据（§3.2、§3.8.5）：<b>能不能从已有字段推出来？</b>
     * 能推 ⇒ 不存；推不出 ⇒ 必须存。天数能推，所以不存成计数。
     *
     * <p><b>但它死的时候必须落成数字</b>：名册里没有"今天"可言，
     * 所以 {@code GraveyardData.record} 在死亡瞬间把天数算好写进 stats。
     * <b>同一个量，活着是投影（算），死了是事实（存）。</b>
     *
     * @return 天数；起点未知（{@link #UNKNOWN_DAY}）返回 -1
     */
    public static int daysSince(long today, long startDay) {
        if (startDay == UNKNOWN_DAY) {
            return -1;
        }
        return (int) (today - startDay);
    }
}