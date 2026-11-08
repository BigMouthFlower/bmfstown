package com.bmf.bmfstown.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 模组配置（config/bmfstown-common.toml）。
 *
 * <h2>放什么、不放什么</h2>
 * <ul>
 *   <li><b>放</b>：玩法数值——镇民基础属性、饥饿/回血节奏、成长曲线、
 *       AI 行为参数。服主和玩家可能想调的东西。</li>
 *   <li><b>不放</b>：UI 布局坐标、渲染 alpha、网络协议常量、存档格式
 *       耦合值（背包 27 格、饥饿上限 20）。改这些只会改坏东西。</li>
 * </ul>
 *
 * <h2>取值时机</h2>
 * 常量字段（如 {@code Config.MAX_HEALTH.get()}）必须在配置加载后调用——
 * 实体属性注册、实体 tick 都发生在配置加载之后，可以放心用；
 * 不要在 mod 构造函数里读。
 *
 * <h2>默认值即"历史值"</h2>
 * 所有默认值都取自改配置化之前的硬编码值，行为无缝迁移。
 */
public final class Config {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // ==========================================================
    //  镇民基础属性
    // ==========================================================

    /** 最大生命（半心 = 1 点）。 */
    public static final ModConfigSpec.DoubleValue MAX_HEALTH = BUILDER
            .comment("镇民的最大生命值", "Resident max health")
            .defineInRange("residentAttributes.maxHealth", 20.0D, 1.0D, 1024.0D);

    /** 基础移动速度（原版村民约 0.5）。 */
    public static final ModConfigSpec.DoubleValue MOVEMENT_SPEED = BUILDER
            .comment("镇民基础移动速度", "Resident base movement speed")
            .defineInRange("residentAttributes.movementSpeed", 0.5D, 0.05D, 4.0D);

    /** 感知范围（玩家交互、目标索敌距离）。 */
    public static final ModConfigSpec.DoubleValue FOLLOW_RANGE = BUILDER
            .comment("镇民感知范围（方块）", "Resident follow range in blocks")
            .defineInRange("residentAttributes.followRange", 32.0D, 4.0D, 256.0D);

    /**
     * 领地内漫步速度。与睡觉同速（0.6 = 村民步速的 60%）。
     *
     * <p>用户定稿：闲逛压到和回家睡觉一样慢——漫步是常态行为，
     * 太快会显得满地图瞎窜很吵；赶路类的回家（1.15）和跟随（1.3）
     * 保持比这快，速度差本身就是信息（"他在赶路" vs "他在瞎溜达"）。
     */
    public static final ModConfigSpec.DoubleValue STROLL_SPEED = BUILDER
            .comment("镇民在领地内漫步的速度（0.6 = 村民步速的六成，与睡觉同速）",
                    "Resident stroll speed inside territory (0.6 = 60% of villager pace, same as walking to bed)")
            .defineInRange("residentAttributes.strollSpeed", 0.6D, 0.05D, 4.0D);

    // ==========================================================
    //  饥饿与进食
    // ==========================================================

    /** 每掉 1 点饥饿需要的 tick 数（1200 = 1 分钟/点）。 */
    public static final ModConfigSpec.IntValue HUNGER_TICKS_PER_POINT = BUILDER
            .comment("每掉 1 点饥饿的 tick 数（1200 = 每分钟 1 点）",
                    "Ticks per hunger point (1200 = 1 point per minute)")
            .defineInRange("hunger.ticksPerPoint", 1200, 20, 24000);

    /** 两口进食之间的最小间隔（tick）。 */
    public static final ModConfigSpec.IntValue EAT_COOLDOWN = BUILDER
            .comment("两次进食之间的最小间隔（tick）",
                    "Minimum interval between bites (ticks)")
            .defineInRange("hunger.eatCooldownTicks", 20, 0, 600);

    /** 饥饿 ≥ 此值才开始自然回血。 */
    public static final ModConfigSpec.IntValue REGEN_HUNGER_THRESHOLD = BUILDER
            .comment("饥饿值达到多少才开始自然回血",
                    "Hunger level required for natural regeneration")
            .defineInRange("hunger.regenThreshold", 18, 0, 20);

    /** 自然回血的间隔（tick）。 */
    public static final ModConfigSpec.IntValue REGEN_INTERVAL = BUILDER
            .comment("自然回血的间隔（tick）", "Natural regeneration interval (ticks)")
            .defineInRange("hunger.regenIntervalTicks", 80, 10, 24000);

    /** 每次自然回血量（点）。 */
    public static final ModConfigSpec.DoubleValue REGEN_AMOUNT = BUILDER
            .comment("每次自然回血量", "Health restored per regeneration tick")
            .defineInRange("hunger.regenAmount", 1.0D, 0.5D, 20.0D);

    public static final ModConfigSpec.IntValue HUNGER_STARVE_GRACE_TICKS = BUILDER
            .comment("挨饿宽限期（tick）：饱食度归零持续超过此时长才算挨饿（开始心情/记忆判定）。", "默认 1200 = 60 秒 = 一轮觅食的最坏超时上限——给正在觅食/进食/等 CD 的人留足解决时间；", "真正解决不了的人（被困/挑食/跟随战士/没有就餐区）60 秒后开始被判挨饿。",
                    "Grace period (ticks) after hunger reaches 0 before starvation is judged. Default 1200 = 60s = worst-case foraging round, giving hungry-but-solving residents time to eat.")
            .defineInRange("hunger.starveGraceTicks", 1200, 20, 24000);

    // ==========================================================
    //  成长与社交
    // ==========================================================

    /**
     * 旧版"等级"曲线的基数（{@code ResidentProfile.level()} 用）。
     *
     * <p><b>注意：这条不控制当前显示的技能段位。</b> 现在的段位是
     * {@code SkillTiers.XP_THRESHOLDS} 按累计经验<b>推导</b>出来的，
     * 阈值刻意不做成配置项——它是存档语义，改了会让老存档段位跳变
     * （详见 {@code SkillTiers} 类注释）。转职时旧等级会被清零，
     * 这个基数实际上只在旧档案迁移路径上还有意义。
     *
     * <p>想调节成长节奏，调产出侧（如 {@code warrior.xpPerKill}），不要动这里。
     */
    public static final ModConfigSpec.IntValue XP_PER_LEVEL_BASE = BUILDER
            .comment("旧版等级曲线的基数（不影响技能段位，段位阈值固定在代码里）",
                    "Base XP per level of the legacy level curve (does NOT affect skill tiers)")
            .defineInRange("growth.xpPerLevelBase", 100, 1, 100000);

    /** 出生时的心情（v0.8.1 由 100 改 50：出生只是"还行"，让玩家有经营空间）。 */
    public static final ModConfigSpec.IntValue INITIAL_MOOD = BUILDER
            .comment("镇民出生时的心情", "Initial mood of a new resident")
            .defineInRange("growth.initialMood", 50, 0, 100);

    // ==========================================================
    //  心情系统（v0.7）
    // ==========================================================

    /**
     * 每日结算的变动上限；<b>0 = 不限幅</b>（v0.8.1 默认关闭）。
     * 设为 5 即恢复"一天内所有慢变量加起来最多 ±5"的旧行为。
     */
    public static final ModConfigSpec.IntValue MOOD_DAILY_CLAMP = BUILDER
            .comment("每日结算时心情最多变动多少（正负皆计）；0 = 不限幅",
                    "Max mood change per day from daily settlement; 0 = uncapped")
            .defineInRange("mood.dailyClamp", 0, 0, 100);

    /** 吃到爱吃的食物的心情增量。 */
    public static final ModConfigSpec.IntValue MOOD_EAT_FAVORED = BUILDER
            .comment("吃到爱吃的食物的心情增量", "Mood gain from eating a favored food")
            .defineInRange("mood.eatFavored", 3, 0, 100);

    /** 「吃到爱吃的」每天最多有效几次（之后吃了不记心情也不记想法）。 */
    public static final ModConfigSpec.IntValue MOOD_EAT_FAVORED_DAILY_LIMIT = BUILDER
            .comment("每天最多记几次「吃到爱吃的」（限流，防刷屏）",
                    "Daily cap for favored-food mood events")
            .defineInRange("mood.eatFavoredDailyLimit", 2, 1, 100);

    /** 挨饿（找不到食物）的心情减量基准。 */
    public static final ModConfigSpec.IntValue MOOD_STARVING = BUILDER
            .comment("挨饿的心情减量基准（按挨饿天数递增：1天=基准，2天=基准+2，3天起=基准+5）",
                    "Base mood loss from starving (scales with consecutive hungry days)")
            .defineInRange("mood.starving", 3, 0, 100);

    /** 挨饿事件每次有效间隔（半天 = 12000 dayTime）。 */
    public static final ModConfigSpec.IntValue MOOD_STARVING_INTERVAL = BUILDER
            .comment("挨饿心情事件的触发间隔（dayTime，12000 = 半天）",
                    "Interval between starving mood events (dayTime ticks; 12000 = half day)")
            .defineInRange("mood.starvingInterval", 12000, 100, 24000);

    /** 当前工作 = 喜欢的职业时，每日结算的心情增量。 */
    public static final ModConfigSpec.IntValue MOOD_JOB_LIKED = BUILDER
            .comment("每天结算时，正在做喜欢的工作的心情增量",
                    "Daily mood gain when working the liked profession")
            .defineInRange("mood.jobLiked", 3, 0, 100);

    // ---- v0.8 偏好扩展（建筑方块/动物/颜色/音乐/天气/生物） ----

    /** 住宅里有喜欢的建筑材质时的心情增量（每天 1 次）。 */
    public static final ModConfigSpec.IntValue MOOD_BLOCK_LIKED = BUILDER
            .comment("住宅里有喜欢的建筑材质时的心情增量（每天 1 次）",
                    "Daily mood gain when liked building material is present in the home")
            .defineInRange("mood.blockLiked", 3, 0, 100);

    /** 身边出现喜欢的动物时的心情增量（每天 1 次）。 */
    public static final ModConfigSpec.IntValue MOOD_ANIMAL_LIKED = BUILDER
            .comment("身边出现喜欢的动物时的心情增量（每天 1 次）",
                    "Daily mood gain when a liked animal is nearby")
            .defineInRange("mood.animalLiked", 3, 0, 100);

    /** 住宅里有喜欢的颜色方块时的心情增量（每天 1 次）。 */
    public static final ModConfigSpec.IntValue MOOD_COLOR_LIKED = BUILDER
            .comment("住宅里有喜欢的颜色方块时的心情增量（每天 1 次）",
                    "Daily mood gain when liked color blocks are present in the home")
            .defineInRange("mood.colorLiked", 3, 0, 100);

    /** 附近唱片机在放时的心情增量（每天 1 次）。 */
    public static final ModConfigSpec.IntValue MOOD_MUSIC_LIKED = BUILDER
            .comment("附近有唱片机在放时的心情增量（每天 1 次）",
                    "Daily mood gain when a jukebox is playing nearby")
            .defineInRange("mood.musicLiked", 3, 0, 100);

    /** 遇到讨厌的天气时的心情减量。 */
    public static final ModConfigSpec.IntValue MOOD_WEATHER_DISLIKED = BUILDER
            .comment("遇到讨厌的天气时的心情减量",
                    "Daily mood loss when exposed to disliked weather")
            .defineInRange("mood.weatherDisliked", 3, 0, 100);

    /** 天气事件的触发间隔（半天 = 12000 dayTime；天气与上次不同才触发）。 */
    public static final ModConfigSpec.IntValue MOOD_WEATHER_INTERVAL = BUILDER
            .comment("天气心情事件的触发间隔（dayTime，12000 = 半天）",
                    "Interval between weather mood events (dayTime ticks; 12000 = half day)")
            .defineInRange("mood.weatherInterval", 12000, 100, 24000);

    /** 讨厌的生物在附近时的心情减量。 */
    public static final ModConfigSpec.IntValue MOOD_MOB_DISLIKED = BUILDER
            .comment("讨厌的生物在附近出没时的心情减量",
                    "Mood loss when a disliked mob is nearby")
            .defineInRange("mood.mobDisliked", 3, 0, 100);

    /** 讨厌生物事件的触发间隔（5 分钟 = 6000 dayTime；无日上限，用户定稿）。 */
    public static final ModConfigSpec.IntValue MOOD_MOB_DISLIKED_INTERVAL = BUILDER
            .comment("讨厌生物心情事件的触发间隔（dayTime，6000 = 5 分钟；无日上限）",
                    "Interval between disliked-mob mood events (dayTime ticks; 6000 = 5 min; no daily cap)")
            .defineInRange("mood.mobDislikedInterval", 6000, 200, 24000);

    // ---- v0.8 性格标签 ----

    /** 性格轴类事件的基础数值（心态乘区之前的基准，统一 ±3）。 */
    public static final ModConfigSpec.IntValue MOOD_TRAIT_VALUE = BUILDER
            .comment("性格轴类事件的基础心情数值（统一 ±3；心态乘区在其后应用，可越过此值）",
                    "Base mood value for personality-axis events (unified ±3; the HOPE multiplier applies after and may exceed it)")
            .defineInRange("mood.traitValue", 3, 0, 100);

    /** 好战轴：连续多少天没击杀就出负面想法。 */
    public static final ModConfigSpec.IntValue MOOD_TRAIT_WARLIKE_NO_KILL_DAYS = BUILDER
            .comment("好战轴连续多少天没击杀生物会心情下降（仅轴值>50 生效）",
                    "Days without a kill before the warlike axis starts losing mood (only when axis value > 50)")
            .defineInRange("mood.traitWarlikeNoKillDays", 5, 1, 100);

    /** 好战轴：击杀带来的正面想法每天最多几次。 */
    public static final ModConfigSpec.IntValue MOOD_TRAIT_KILL_DAILY_LIMIT = BUILDER
            .comment("好战轴的击杀正面想法每天最多几次",
                    "Daily cap on kill-related positive thoughts for the warlike axis")
            .defineInRange("mood.traitKillDailyLimit", 2, 1, 100);

    /** 勇气轴：敌对生物在附近的负面想法触发间隔（dayTime）。 */
    public static final ModConfigSpec.IntValue MOOD_TRAIT_TIMID_INTERVAL = BUILDER
            .comment("勇气轴遇敌负面想法的触发间隔（dayTime，12000 = 半天）",
                    "Interval between hostile-seen negative thoughts for the courage axis (dayTime ticks)")
            .defineInRange("mood.traitTimidInterval", 12000, 100, 24000);

    // ---- v0.14 性格轴（决策 #44）----

    /** 暴力轴：附近生物死亡事件的每天上限。 */
    public static final ModConfigSpec.IntValue AXIS_DEATH_DAILY_LIMIT = BUILDER
            .comment("暴力轴：附近有生物死亡事件的每天上限",
                    "Daily cap on nearby-death events for the cruelty axis")
            .defineInRange("personality.deathDailyLimit", 5, 1, 100);

    /** 暴力轴：附近爆炸事件的每天上限（烟花不算爆炸源）。 */
    public static final ModConfigSpec.IntValue AXIS_EXPLOSION_DAILY_LIMIT = BUILDER
            .comment("暴力轴：附近爆炸事件的每天上限（烟花爆炸不算）",
                    "Daily cap on nearby-explosion events for the cruelty axis (fireworks excluded)")
            .defineInRange("personality.explosionDailyLimit", 1, 1, 100);

    /** 暴力轴：附近生物繁殖事件的每天上限。 */
    public static final ModConfigSpec.IntValue AXIS_BIRTH_DAILY_LIMIT = BUILDER
            .comment("暴力轴：附近有生物繁殖事件的每天上限",
                    "Daily cap on nearby-birth events for the cruelty axis")
            .defineInRange("personality.birthDailyLimit", 2, 1, 100);

    /** 勇气轴：好战端战士锁敌半径的正向修正上限（格）。 */
    public static final ModConfigSpec.DoubleValue AXIS_AGGRO_BONUS = BUILDER
            .comment("勇气轴：锁敌半径正向修正上限（格，轴值 50→100 线性到满）",
                    "Courage axis: max positive aggro-radius correction in blocks (linear from value 50 to 100)")
            .defineInRange("personality.aggroBonusBlocks", 10.0D, 0.0D, 64.0D);

    /** 勇气轴：胆小端战士锁敌半径的负向修正上限（格）。 */
    public static final ModConfigSpec.DoubleValue AXIS_AGGRO_PENALTY = BUILDER
            .comment("勇气轴：锁敌半径负向修正上限（格，轴值 50→0 线性到满；实施时钳下限≥2 防跌破）",
                    "Courage axis: max negative aggro-radius correction in blocks (linear from value 50 to 0; clamped to min 2 at runtime)")
            .defineInRange("personality.aggroPenaltyBlocks", 5.0D, 0.0D, 32.0D);

    /** 勇气轴：逃跑触发距离上限（格，轴值 0 时最远）。 */
    public static final ModConfigSpec.DoubleValue AXIS_FLEE_MAX_DIST = BUILDER
            .comment("勇气轴：逃跑触发距离上限（格，轴值 0 时最远；轴值≥35 不逃跑）",
                    "Courage axis: max flee trigger distance in blocks (furthest at axis value 0; no fleeing at value >= 35)")
            .defineInRange("personality.fleeMaxDist", 8.0D, 0.0D, 32.0D);

    /** 勇气轴：胆小端攻击伤害的折损上限（百分比，轴值 0 时最多打七折）。 */
    public static final ModConfigSpec.IntValue AXIS_ATTACK_PENALTY_PERCENT = BUILDER
            .comment("勇气轴：胆小端攻击伤害折损上限（百分比，轴值 0 时最多 −30；中位以上不增伤）",
                    "Courage axis: max attack-damage penalty percent for the timid end (at value 0; no bonus above 50)")
            .defineInRange("personality.attackPenaltyPercent", 30, 0, 100);

    /** 坚韧轴：心情打折线/罢工线的极端位移幅度（心情点）。 */
    public static final ModConfigSpec.IntValue AXIS_GRIT_SHIFT = BUILDER
            .comment("坚韧轴：打折线/罢工线的极端位移幅度（心情点，坚韧高线下移更能扛，脆弱上移更容易崩）",
                    "Grit axis: extreme shift of the slow/strike mood thresholds in mood points")
            .defineInRange("personality.gritShift", 10, 0, 50);

    /** 专注轴：工作效率公式的修正段（百分比，±5 = 0.95~1.05）。 */
    public static final ModConfigSpec.IntValue AXIS_FOCUS_PERCENT = BUILDER
            .comment("专注轴：工作效率公式的修正段（百分比，±5 = ×0.95~×1.05，进逐段连乘并展示）",
                    "Focus axis: work-efficiency formula segment in percent (±5 => x0.95~x1.05)")
            .defineInRange("personality.focusPercent", 5, 0, 50);

    /** 晨夜轴：作息窗口的偏移上限（tick，两端对称）。 */
    public static final ModConfigSpec.IntValue AXIS_CHRONO_OFFSET = BUILDER
            .comment("晨夜轴：作息窗口偏移上限（tick，早鸟提前/夜猫子推迟，默认 ±1005）",
                    "Chrono axis: max schedule-window offset in ticks (early birds earlier, night owls later; default ±1005)")
            .defineInRange("personality.chronoOffsetTicks", 1005, 0, 6000);

    /** 专注轴：摸鱼概率上限（百分比，轴值 0 时每工作段最多四成概率开小差）。 */
    public static final ModConfigSpec.IntValue AXIS_GOOF_PERCENT = BUILDER
            .comment("专注轴：摸鱼概率上限（百分比，轴值 0 时最多；轴值≥40 不摸鱼；窗口=正常闲暇×1/3）",
                    "Focus axis: max goof-off chance percent per work segment (at axis value 0; disabled at value >= 40)")
            .defineInRange("personality.goofPercent", 40, 0, 100);

    /** 专注轴：摸鱼窗口下限（tick，短于正常闲暇）。 */
    public static final ModConfigSpec.IntValue AXIS_GOOF_SESSION_MIN = BUILDER
            .comment("专注轴：摸鱼窗口下限（tick，必须短于正常闲暇段）",
                    "Focus axis: minimum goof-off window in ticks (must be shorter than normal leisure)")
            .defineInRange("personality.goofSessionMin", 400, 100, 24000);

    /** 专注轴：摸鱼窗口上限（tick）。 */
    public static final ModConfigSpec.IntValue AXIS_GOOF_SESSION_MAX = BUILDER
            .comment("专注轴：摸鱼窗口上限（tick）",
                    "Focus axis: maximum goof-off window in ticks")
            .defineInRange("personality.goofSessionMax", 800, 100, 24000);

    /** 嫉妒轴：评判玩家装备的扫描距离（格）。 */
    public static final ModConfigSpec.DoubleValue AXIS_GEAR_RANGE = BUILDER
            .comment("嫉妒轴：评判玩家随身装备的扫描距离（格，需视线可达）",
                    "Envy axis: scan range in blocks for judging player equipment (line of sight required)")
            .defineInRange("personality.gearJudgeRange", 5.0D, 1.0D, 32.0D);

    /** 嫉妒轴：装备评判台词的全镇级冷却（dayTime）。 */
    public static final ModConfigSpec.IntValue AXIS_GEAR_TOWN_COOLDOWN = BUILDER
            .comment("嫉妒轴：装备评判台词的全镇级冷却（dayTime，6000 = 5 分钟）",
                    "Envy axis: town-wide cooldown for gear-judgement dialogue (dayTime ticks; 6000 = 5 min)")
            .defineInRange("personality.gearTownCooldown", 6000, 200, 24000);

    /** 嫉妒轴：装备评判的心情惩罚每天最多几次。 */
    public static final ModConfigSpec.IntValue AXIS_GEAR_MOOD_DAILY_LIMIT = BUILDER
            .comment("嫉妒轴：看到玩家好装备的心情惩罚每天最多几次（仅轴值>80 生效）",
                    "Daily cap on gear-envy mood penalties (only when axis value > 80)")
            .defineInRange("personality.gearMoodDailyLimit", 1, 1, 100);

    /** 昨晚没睡的每日心情减量；连续没睡逐夜叠加、不封顶；0 = 关闭（v0.8.1）。 */
    public static final ModConfigSpec.IntValue MOOD_NO_SLEEP = BUILDER
            .comment("昨晚没睡的每日心情减量（连续没睡逐夜叠加不封顶；战士守夜豁免）；0 = 关闭",
                    "Daily mood loss for not sleeping (stacks per missed night, uncapped; warriors on duty exempt); 0 = disabled")
            .defineInRange("mood.noSleep", 3, 0, 100);

    public static final ModConfigSpec.IntValue MOOD_MEMORY_PENALTY = BUILDER
            .comment("已有同类永久记忆时，挨饿/缺觉心情事件的额外扣减（叠加在原减量上）",
                    "Extra mood loss for starving/sleep events when the matching firm memory exists")
            .defineInRange("mood.memoryPenalty", 10, 0, 100);

    /** 心情低于此值时干活效率打折。 */
    public static final ModConfigSpec.IntValue MOOD_SLOW_THRESHOLD = BUILDER
            .comment("心情低于此值时干活效率打折（0 = 关闭）",
                    "Work efficiency reduced when mood below this (0 = disabled)")
            .defineInRange("mood.slowThreshold", 40, 0, 100);

    /** 心情打折档的效率乘数（百分比）。 */
    public static final ModConfigSpec.IntValue MOOD_SLOW_MULTIPLIER_PERCENT = BUILDER
            .comment("心情打折档的效率乘数（百分比，70 = 70%）",
                    "Work efficiency multiplier (percent) in the slowed mood band")
            .defineInRange("mood.slowMultiplierPercent", 70, 1, 100);

    /** 心情低于此值时罢工（不干活，战斗不受影响）。 */
    public static final ModConfigSpec.IntValue MOOD_STRIKE_THRESHOLD = BUILDER
            .comment("心情低于此值时罢工（0 = 关闭；战士的战斗行为不受影响）",
                    "Strike (stop working) when mood below this (0 = disabled; combat unaffected)")
            .defineInRange("mood.strikeThreshold", 20, 0, 100);

    // ==========================================================
    //  镇民 AI
    // ==========================================================

    /** 领地逃逸检查频率（tick）。 */
    public static final ModConfigSpec.IntValue TOWN_CHECK_INTERVAL = BUILDER
            .comment("领地逃逸检查的频率（tick）", "Territory escape check interval (ticks)")
            .defineInRange("ai.townCheckIntervalTicks", 20, 1, 200);

    /** 逃逸后返回领地的速度（比漫步快一点）。 */
    public static final ModConfigSpec.DoubleValue RETURN_HOME_SPEED = BUILDER
            .comment("逃逸后返回领地的速度（介于走路与疾跑之间的赶路速度）",
                    "Speed when returning to territory after escaping (between walk and sprint)")
            .defineInRange("ai.returnHomeSpeed", 1.15D, 0.05D, 4.0D);

    /** 觅食一次从箱子里拿几个食物。 */
    public static final ModConfigSpec.IntValue FORAGE_TAKE_COUNT = BUILDER
            .comment("觅食一次从箱子里拿几个食物", "How many food items a resident takes per foraging trip")
            .defineInRange("ai.forageTakeCount", 3, 1, 64);

    /** 找不到箱子/箱子空了后的重试间隔（tick）。 */
    public static final ModConfigSpec.IntValue FORAGE_RETRY_TICKS = BUILDER
            .comment("找不到可用的就餐箱后多久重试（tick）",
                    "Retry delay after failing to find a usable dining chest (ticks)")
            .defineInRange("ai.forageRetryTicks", 600, 20, 24000);

    // ==========================================================
    //  睡觉（住宅区）
    // ==========================================================

    /** 一天的哪个 tick 开始回家睡觉（12000 = 日落）。 */
    public static final ModConfigSpec.IntValue SLEEP_START_TIME = BUILDER
            .comment("开始回家睡觉的时刻（0~24000，12000 是日落）",
                    "Day time when residents start going home to sleep (12000 = sunset)")
            .defineInRange("sleep.startTime", 12000, 0, 23999);

    /** 一天的哪个 tick 起床（23000 是日出前）。 */
    public static final ModConfigSpec.IntValue SLEEP_END_TIME = BUILDER
            .comment("起床的时刻（0~24000，23000 是日出前）",
                    "Day time when residents wake up (23000 = just before sunrise)")
            .defineInRange("sleep.endTime", 23000, 1, 24000);

    /** 回家睡觉的走路速度。 */
    public static final ModConfigSpec.DoubleValue SLEEP_WALK_SPEED = BUILDER
            .comment("回家睡觉的走路速度", "Speed when walking home to sleep")
            .defineInRange("sleep.walkSpeed", 0.6D, 0.05D, 4.0D);

    /** 床扫描结果缓存时长（tick）。住宅内床的变化最多延迟这么久才被察觉。 */
    public static final ModConfigSpec.IntValue SLEEP_BED_CACHE_TICKS = BUILDER
            .comment("住宅内床位扫描结果的缓存时间（tick）",
                    "How long the bed list of a housing zone is cached (ticks)")
            .defineInRange("sleep.bedCacheTicks", 100, 1, 1200);

    /** 从出发到躺下允许的总时长（tick），超时就放弃本轮。 */
    public static final ModConfigSpec.IntValue SLEEP_TRAVEL_TIMEOUT_TICKS = BUILDER
            .comment("回家睡觉的总超时（tick），超时放弃本轮",
                    "Total timeout for walking home to sleep (ticks)")
            .defineInRange("sleep.travelTimeoutTicks", 1200, 20, 24000);

    /** 睡觉失败后的重试间隔（tick）。 */
    public static final ModConfigSpec.IntValue SLEEP_RETRY_TICKS = BUILDER
            .comment("睡觉失败后多久重试（tick）", "Retry delay after failing to sleep (ticks)")
            .defineInRange("sleep.retryTicks", 600, 20, 24000);

    /** "该不该睡了"的检查频率（tick）。 */
    public static final ModConfigSpec.IntValue SLEEP_CHECK_INTERVAL = BUILDER
            .comment("检查是否该睡觉的频率（tick）", "Sleep check interval (ticks)")
            .defineInRange("sleep.checkIntervalTicks", 100, 1, 1200);

    // ==========================================================
    //  战士工作（跟随 / 站岗）
    // ==========================================================

    /** 站岗时的活动半径（格）。战士只能在岗哨位置周围这么大范围里移动/索敌。 */
    public static final ModConfigSpec.IntValue WARRIOR_GUARD_RADIUS = BUILDER
            .comment("站岗活动半径（格）", "Guard post patrol radius (blocks)")
            .defineInRange("warrior.guardRadius", 10, 2, 64);

    /** 跟随玩家时的移速。 */
    public static final ModConfigSpec.DoubleValue WARRIOR_FOLLOW_SPEED = BUILDER
            .comment("跟随玩家时的移速（1.3 ≈ 玩家疾跑速度 5.6 格/秒）",
                    "Speed when following a player (1.3 ~ player sprint, 5.6 m/s)")
            .defineInRange("warrior.followSpeed", 1.3D, 0.05D, 4.0D);

    /** 站岗巡逻的移速。 */
    public static final ModConfigSpec.DoubleValue WARRIOR_GUARD_SPEED = BUILDER
            .comment("站岗巡逻的移速（1.0 = 村民快步走）",
                    "Speed when patrolling a guard post (1.0 = villager walking pace)")
            .defineInRange("warrior.guardSpeed", 1.0D, 0.05D, 4.0D);

    /** 离跟随目标超过这个距离（格）时直接传送归队。 */
    public static final ModConfigSpec.IntValue WARRIOR_TELEPORT_DIST = BUILDER
            .comment("离跟随目标超过这个距离（格）时传送归队",
                    "Teleport back to the followed player beyond this distance (blocks)")
            .defineInRange("warrior.teleportDistance", 14, 4, 128);

    /** 战士的基础攻击力（属性注册早于配置加载，读取走 {@link #attackDamageOrDefault()}）。 */
    public static final ModConfigSpec.DoubleValue WARRIOR_ATTACK_DAMAGE = BUILDER
            .comment("战士的攻击力", "Warrior attack damage")
            .defineInRange("warrior.attackDamage", 3.0D, 0.5D, 40.0D);

    /** 是否把苦力怕纳入索敌。默认否——近战打苦力怕会触发自爆，得不偿失。 */
    public static final ModConfigSpec.BooleanValue WARRIOR_ATTACK_CREEPERS = BUILDER
            .comment("战士是否攻击苦力怕（近战打它容易触发自爆）",
                    "Whether warriors target creepers (meleeing them usually triggers the explosion)")
            .define("warrior.attackCreepers", false);

    /** 击杀敌人一次涨多少格斗经验。 */
    public static final ModConfigSpec.IntValue WARRIOR_XP_PER_KILL = BUILDER
            .comment("击杀敌人获得的格斗经验（v0.14 起全民入账，不只战士）",
                    "Combat xp per kill (all residents since v0.14, not just warriors)")
            .defineInRange("warrior.xpPerKill", 30, 0, 1000);

    /**
     * 战士出手距离（格，从自己碰撞箱边缘算起）。
     *
     * <p>原版 {@code Mob} 的默认出手距离是 {@code bbWidth * 2}，
     * 村民体型（宽 0.6）只有约 1.2 格——僵尸的移动速度、攻击冷却都不输他，
     * 结果就是双方贴脸互换伤害，战士打不出"先手击退"。
     *
     * <p>剑和斧在原版里<b>攻击距离完全相同</b>（它们只差伤害/攻速/横扫，
     * 距离由实体决定，不由武器决定），所以要拉开距离只能从实体这边调。
     */
    public static final ModConfigSpec.DoubleValue WARRIOR_ATTACK_REACH = BUILDER
            .comment("战士出手距离（格，从自身碰撞箱边缘算起）",
                    "Warrior attack reach in blocks, measured from its own hitbox edge")
            .defineInRange("warrior.attackReach", 2.5D, 1.0D, 6.0D);

    // 经验口径（定稿）：经验只来自工作成功的过程（如击杀 +30 格斗），
    // 不做"在岗时间挂机涨经验"——该设定已移除。

    // ==========================================================
    //  perk（工作页成长分支）
    // ==========================================================

    /** 主动 perk（坚盾/追猎/嘲讽）共用冷却（秒）。 */
    public static final ModConfigSpec.IntValue PERK_ACTIVE_COOLDOWN_SECONDS = BUILDER
            .comment("主动 perk（坚盾/追猎/嘲讽）的施放冷却（秒）",
                    "Shared cooldown of active perks in seconds")
            .defineInRange("perk.activeCooldownSeconds", 30, 1, 600);

    /** 坚盾友军半径（格）。 */
    public static final ModConfigSpec.DoubleValue PERK_BULWARK_RADIUS = BUILDER
            .comment("坚盾：友军抗性提升的作用半径（格）",
                    "Bulwark: ally resistance radius in blocks")
            .defineInRange("perk.bulwarkRadius", 5.0D, 1.0D, 64.0D);

    /** 坚盾持续时间（秒）。 */
    public static final ModConfigSpec.IntValue PERK_BULWARK_DURATION_SECONDS = BUILDER
            .comment("坚盾：抗性提升的持续时间（秒）",
                    "Bulwark: resistance duration in seconds")
            .defineInRange("perk.bulwarkDurationSeconds", 15, 1, 600);

    /** 追猎标记半径（格）。 */
    public static final ModConfigSpec.DoubleValue PERK_HUNT_RADIUS = BUILDER
            .comment("追猎：敌人发光标记的作用半径（格）",
                    "Hunt: enemy glowing radius in blocks")
            .defineInRange("perk.huntRadius", 20.0D, 1.0D, 128.0D);

    /** 追猎标记持续时间（秒）。 */
    public static final ModConfigSpec.IntValue PERK_HUNT_DURATION_SECONDS = BUILDER
            .comment("追猎：发光标记的持续时间（秒）",
                    "Hunt: glowing duration in seconds")
            .defineInRange("perk.huntDurationSeconds", 20, 1, 600);

    /** 嘲讽作用半径（格）。 */
    public static final ModConfigSpec.DoubleValue PERK_TAUNT_RADIUS = BUILDER
            .comment("嘲讽：强制索敌的作用半径（格）",
                    "Taunt: forced-targeting radius in blocks")
            .defineInRange("perk.tauntRadius", 10.0D, 1.0D, 64.0D);

    /** 嘲讽持续时间（秒）。 */
    public static final ModConfigSpec.IntValue PERK_TAUNT_DURATION_SECONDS = BUILDER
            .comment("嘲讽：强制敌军攻击自己的持续时间（秒）",
                    "Taunt: forced-targeting duration in seconds")
            .defineInRange("perk.tauntDurationSeconds", 10, 1, 600);

    /** 再生：自然回血量倍率（选了精通节点"再生"分支时）。 */
    public static final ModConfigSpec.DoubleValue PERK_REGEN_MULTIPLIER = BUILDER
            .comment("再生：自然回血量的倍率",
                    "Regeneration: multiplier applied to natural healing amount")
            .defineInRange("perk.regenMultiplier", 1.5D, 1.0D, 10.0D);

    /** 强壮：效率加成（百分点，选了精通节点"强壮"分支时）。 */
    public static final ModConfigSpec.IntValue PERK_STRONG_BONUS_PERCENT = BUILDER
            .comment("强壮：职业效率的固定加成（百分点，10 = 效率 +10%）",
                    "Strong: flat efficiency bonus in percentage points")
            .defineInRange("perk.strongBonusPercent", 10, 0, 100);

    /** 斩杀：可秒杀的目标生命阈值。 */
    public static final ModConfigSpec.DoubleValue PERK_EXECUTE_HEALTH_THRESHOLD = BUILDER
            .comment("斩杀：可触发秒杀的目标生命值上限",
                    "Execute: max target health eligible for instant kill")
            .defineInRange("perk.executeHealthThreshold", 50.0D, 1.0D, 1024.0D);

    /** 斩杀：触发概率（百分点）。 */
    public static final ModConfigSpec.IntValue PERK_EXECUTE_CHANCE_PERCENT = BUILDER
            .comment("斩杀：秒杀触发概率（百分点）",
                    "Execute: instant-kill chance in percentage points")
            .defineInRange("perk.executeChancePercent", 25, 0, 100);

    // ==========================================================
    //  工人（工坊合成，v0.8.1）
    // ==========================================================

    /** 每件合成的基础耗时（tick；再除以职业效率、乘工人效率 perk 的缩减）。 */
    public static final ModConfigSpec.IntValue CRAFT_TICKS_PER_ITEM = BUILDER
            .comment("工人每合成一件的基础耗时（tick，受熟练度/心情效率缩放）",
                    "Base ticks for the worker to craft one item (scaled by proficiency/mood efficiency)")
            .defineInRange("craft.ticksPerItem", 100, 10, 24000);

    /** 每件合成涨多少合成经验（用户定稿削弱：一批 3 件 = 15 点）。 */
    public static final ModConfigSpec.IntValue CRAFT_XP_PER_ITEM = BUILDER
            .comment("工人每合成一件获得的合成经验（一批 3 件 = 3 倍）",
                    "Crafting xp per item for the worker")
            .defineInRange("craft.xpPerCraft", 5, 0, 1000);

    /** 炼金每一步涨多少经验（决策 #33 及其修订 2026-11-07：按生产链长结算——
     *  地狱疣/中间底药/主料/红石荧石/火药/龙息各算一步，收瓶入库时
     *  按 步数×每步经验 一次结清；步是整批劳动，不乘瓶数）。
     *  默认 10：一炉一步 10（2 步药水=20/批，5 步滞留强化=50/批）。 */
    public static final ModConfigSpec.IntValue CRAFT_XP_PER_BREW_STEP = BUILDER
            .comment("炼金术士每一步酿造获得的经验（按链长整批结算一次，不乘瓶数：地狱疣/中间底药/主料/修饰各一步）",
                    "Alchemist xp per brewing step (charged once per batch by chain length, not per bottle)")
            .defineInRange("craft.xpPerBrewStep", 10, 0, 1000);

    /** 开源 perk（传奇分支解锁）：每件产出 +1 的概率（百分比）。 */
    public static final ModConfigSpec.IntValue CRAFT_OPEN_SOURCE_PERCENT = BUILDER
            .comment("工人'开源'perk（Lv6 解锁）：每件产出 +1 的概率（百分比）",
                    "Worker 'Open Source' perk (unlocked at Lv6): chance for +1 output per craft (percent)")
            .defineInRange("craft.openSourcePercent", 10, 0, 100);

    /** 节流 perk（传奇分支解锁）：整次合成不消耗原料的概率（百分比）。 */
    public static final ModConfigSpec.IntValue CRAFT_THRIFT_PERCENT = BUILDER
            .comment("工人'节流'perk（传奇分支解锁）：整次合成不消耗原料的概率（百分比）",
                    "Worker 'Thrift' perk (legend branch): chance for a craft to consume no materials (percent)")
            .defineInRange("craft.thriftPercent", 10, 0, 100);

    /** 工人开工时刻（dayTime 0~24000；起床 23000 后留一段空闲，默认次日凌晨 2000）。 */
    public static final ModConfigSpec.IntValue CRAFT_WORK_START = BUILDER
            .comment("工人开工时刻（dayTime 0~24000；起床后要留一段空闲）",
                    "Day time when the worker starts working")
            .defineInRange("craft.workStartTick", 2000, 0, 23999);

    /** 工人收工时刻（默认 10000，日落前留一段空闲）。 */
    public static final ModConfigSpec.IntValue CRAFT_WORK_END = BUILDER
            .comment("工人收工时刻（dayTime 0~24000；睡觉前要留一段空闲）",
                    "Day time when the worker stops working")
            .defineInRange("craft.workEndTick", 10000, 1, 24000);

    // ==========================================================
    //  烟火工匠（v0.13，firework.*）
    // ==========================================================

    /** 烟火工匠每件（每次组装=一套）基础耗时（tick；与工人同构除以效率）。 */
    public static final ModConfigSpec.IntValue FIREWORK_TICKS_PER_ITEM = BUILDER
            .comment("烟火工匠每造一颗星/每组装一套烟花的基础耗时（tick，受熟练度/心情效率缩放）",
                    "Base ticks for the fireworksman per star or per rocket batch (scaled by efficiency)")
            .defineInRange("firework.ticksPerItem", 100, 10, 24000);

    /** 烟火经验：每件成品 +30（决策 #40 用户定稿，注册键 firework.xpPerItem）。 */
    public static final ModConfigSpec.IntValue FIREWORK_XP_PER_ITEM = BUILDER
            .comment("烟火工匠每件成品（每次组装 3 支为一件）获得的合成经验（决策 #40）",
                    "Fireworksman crafting xp per finished item (one batch of 3 rockets)")
            .defineInRange("firework.xpPerItem", 30, 0, 1000);

    /** 烟火'惜色'perk：造星掷一次，命中该星染料（主色+渐变）全免消耗（百分比）。 */
    public static final ModConfigSpec.IntValue FIREWORK_DYE_THRIFT_PERCENT = BUILDER
            .comment("烟火工匠'惜色'perk：造星时整星染料免消耗的概率（百分比）",
                    "Fireworksman 'Dye Thrift' perk: chance per star for free dyes (percent)")
            .defineInRange("firework.dyeThriftPercent", 10, 0, 100);

    /** 烟火'惜辉'perk：造星掷一次，命中该星钻石+荧石全免消耗（百分比）。 */
    public static final ModConfigSpec.IntValue FIREWORK_MOD_THRIFT_PERCENT = BUILDER
            .comment("烟火工匠'惜辉'perk：造星时钻石与荧石免消耗的概率（百分比）",
                    "Fireworksman 'Spark Thrift' perk: chance per star for free diamond & glowstone (percent)")
            .defineInRange("firework.modThriftPercent", 20, 0, 100);

    /** '回响'perk：二段爆炸的延迟（tick）。 */
    public static final ModConfigSpec.IntValue FIREWORK_ECHO_DELAY = BUILDER
            .comment("'回响'perk：首爆后延迟多少 tick 再爆一次（伤害=第一次的一半）",
                    "'Echo' perk: delay in ticks before the second explosion (half damage)")
            .defineInRange("firework.echoDelayTicks", 10, 1, 200);

    // ---- v0.9.1 特性权重 ----

    /**
     * 特性抽取的权重覆盖表（条目格式 {@code "特性id:权重"}，如 {@code "gattaca:3"}）。
     *
     * <p><b>未列出的特性默认权重 1</b>——所以默认（空表）= 14 条等概率，与旧版行为完全一致；
     * 将来词条扩到 50 条时，只想调谁就写谁，不用把全表搬进配置。
     * 权重 0 = 该特性禁用（永不抽出）；全部为 0 时兜底回等概率。
     */
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> TRAITS_WEIGHT_OVERRIDES =
            BUILDER.comment("特性抽取权重覆盖表，条目格式 \"特性id:权重\"（如 \"gattaca:3\"）",
                    "未列出的特性默认权重 1（默认空表 = 全部等概率）；权重 0 = 该特性禁用",
                    "Trait pick weight overrides, entries like \"gattaca:3\"",
                    "Unlisted traits default to weight 1 (empty list = uniform); weight 0 disables a trait")
            .defineList("traits.weightOverrides", java.util.List.of(),
                    o -> o instanceof String str && str.contains(":"));

    // ---- 台词（v0.9.1 表达层）----

    public static final ModConfigSpec.IntValue DIALOG_EAT_COOLDOWN = BUILDER
            .comment("吃到爱吃的台词的冷却（秒）；一顿饭只说一次", "Cooldown of the 'tasty food' line (seconds)")
            .defineInRange("dialog.eatCooldownSeconds", 90, 5, 3600);

    public static final ModConfigSpec.IntValue DIALOG_WEATHER_COOLDOWN = BUILDER
            .comment("天气台词的冷却（秒）", "Cooldown of weather lines (seconds)")
            .defineInRange("dialog.weatherCooldownSeconds", 300, 30, 7200);

    public static final ModConfigSpec.IntValue DIALOG_STAGGER_TICKS = BUILDER
            .comment("天气变化后全镇台词的错峰窗口（tick）；每个镇民按个人相位延迟 0~该值 再说",
                    "Stagger window in ticks after a weather change")
            .defineInRange("dialog.staggerTicks", 600, 0, 24000);

    public static final ModConfigSpec.IntValue DIALOG_WEATHER_ONE_IN_N = BUILDER
            .comment("天气变化时只有 1/N 的镇民开口（按个人相位筛选），避免全员齐喊",
                    "Only 1 in N villagers speak on a weather change")
            .defineInRange("dialog.weatherOneInN", 3, 1, 20);

    public static final ModConfigSpec.IntValue DIALOG_SUFFER_COOLDOWN = BUILDER
            .comment("受苦台词（挨饿/没睡）的冷却（秒），按人按类各记各的",
                    "Cooldown of suffering lines (starving/sleepless, per resident per type, seconds)")
            .defineInRange("dialog.sufferCooldownSeconds", 600, 30, 7200);

    // ---- 学者（v0.11 写书）----

    public static final ModConfigSpec.LongValue SCHOLAR_WRITE_TICKS = BUILDER
            .comment("写一本书的在岗基准时长（gameTime tick；90 现实分钟=108000）。 perk/解锁书 ×1.5，闲书 ×0.4。进度按实际写作 tick 累计，效率作乘数，/time 系命令完全影响不了",
                    "Base on-duty ticks to write one book (90 real minutes). Perk/unlock x1.5, idle x0.4. Progress accumulates only while actually writing")
            .defineInRange("scholar.writeTicks", 108000L, 1200L, 24000000L);

    public static final ModConfigSpec.LongValue SCHOLAR_COPY_TICKS = BUILDER
            .comment("抄一本书的在岗基准时长（gameTime tick；60 现实分钟=72000）",
                    "Base on-duty ticks to copy one book (60 real minutes)")
            .defineInRange("scholar.copyTicks", 72000L, 1200L, 24000000L);

    /** 成书经验（决策 #40：原硬编码 30 提配置，用户定稿削弱→加强——写书很慢，量该上来）。 */
    public static final ModConfigSpec.IntValue SCHOLAR_XP_PER_IDLE_BOOK = BUILDER
            .comment("学者写成一本闲书获得的主技能经验",
                    "Scholar main-skill xp for finishing an idle book")
            .defineInRange("scholar.xpPerIdleBook", 100, 0, 10000);

    public static final ModConfigSpec.IntValue SCHOLAR_XP_PER_SPECIAL_BOOK = BUILDER
            .comment("学者写成一本特殊书（perk 技能书/隐藏解锁书）获得的主技能经验；辩证法 perk 的对应职业双涨同用此值",
                    "Scholar main-skill xp for finishing a special book (perk/unlock); dialectics secondary gain uses the same value")
            .defineInRange("scholar.xpPerSpecialBook", 200, 0, 10000);

    public static final ModConfigSpec.IntValue SCHOLAR_PERK_BOOK_CHANCE = BUILDER
            .comment("写书命中特殊书（perk 池）的概率（%）；未命中写闲书。特殊书不放回：抽中即从该镇池子移除",
                    "Chance (%) that a written book is a special perk book (drawn without replacement)")
            .defineInRange("scholar.perkBookChance", 30, 0, 100);

    public static final ModConfigSpec.IntValue SCHOLAR_WORK_START = BUILDER
            .comment("学者开始写书的时刻（dayTime，0~24000）",
                    "Time the scholar starts writing (dayTime)")
            .defineInRange("scholar.workStart", 2000, 0, 23999);

    public static final ModConfigSpec.IntValue SCHOLAR_WORK_END = BUILDER
            .comment("学者停止写书的时刻（dayTime，0~24000）",
                    "Time the scholar stops writing (dayTime)")
            .defineInRange("scholar.workEnd", 10000, 1, 24000);

    public static final ModConfigSpec.IntValue SCHOLAR_DIVISION_BONUS = BUILDER
            .comment("分工 perk：写书效率加成（%）。代价：非学者职业经验永久锁涨（不限当前职业）",
                    "Division perk: writing speed bonus (%). Cost: all non-scholar skill xp locked permanently")
            .defineInRange("scholar.divisionBonusPercent", 20, 0, 200);

    // ---- 休闲（v0.10.3 闲暇活动：闲逛/图书馆看闲书，动态活动池）----

    public static final ModConfigSpec.IntValue LEISURE_SESSION_MIN = BUILDER
            .comment("单次休闲的最短时长（gameTime tick，默认 1200 = 游戏 1.2 小时）",
                    "Minimum leisure session length (gameTime ticks)")
            .defineInRange("leisure.sessionMinTicks", 1200, 100, 24000);

    public static final ModConfigSpec.IntValue LEISURE_SESSION_MAX = BUILDER
            .comment("单次休闲的最长时长（gameTime tick，默认 2400 = 游戏 2.4 小时）",
                    "Maximum leisure session length (gameTime ticks)")
            .defineInRange("leisure.sessionMaxTicks", 2400, 100, 24000);

    public static final ModConfigSpec.IntValue LEISURE_BREAK_MIN = BUILDER
            .comment("两段休闲之间的最短间隔（gameTime tick）",
                    "Minimum break between leisure sessions (gameTime ticks)")
            .defineInRange("leisure.breakMinTicks", 200, 0, 24000);

    public static final ModConfigSpec.IntValue LEISURE_BREAK_MAX = BUILDER
            .comment("两段休闲之间的最长间隔（gameTime tick）",
                    "Maximum break between leisure sessions (gameTime ticks)")
            .defineInRange("leisure.breakMaxTicks", 600, 0, 24000);

    public static final ModConfigSpec.IntValue LEISURE_IDLE_BOOK_READ = BUILDER
            .comment("读完一本闲书所需的连续阅读时长（gameTime tick，一次休闲段内完成）",
                    "Reading time to finish one idle book (gameTime ticks, finishes within one session)")
            .defineInRange("leisure.idleBookReadTicks", 600, 100, 24000);

    public static final ModConfigSpec.IntValue LEISURE_LITERACY_COST = BUILDER
            .comment("学一门特殊书（技能书）需要消耗的学识点（学识来自读完闲书）",
                    "Literacy points consumed to learn one special book (earned by reading idle books)")
            .defineInRange("leisure.literacyCost", 5, 1, 100);

    public static final ModConfigSpec.IntValue LEISURE_MOOD_BONUS = BUILDER
            .comment("读完一本闲书的心情加成（批注本 ×1.5）",
                    "Mood gain from finishing an idle book (annotated copies give 1.5x)")
            .defineInRange("leisure.readMoodBonus", 5, 1, 100);

    public static final ModConfigSpec.IntValue SCHOLAR_OVERFIT_BONUS = BUILDER
            .comment("过拟合 perk：每掌握一门知识的写书效率加成（%，加算叠乘）。代价：抽特殊书 50% 走全量池可能出重复书",
                    "Overfit perk: writing speed bonus per learned book (%). Cost: 50% draws come from the full pool (duplicates possible)")
            .defineInRange("scholar.overfitBonusPercent", 10, 0, 100);

    // ---- 簿记员（在职成长；决策 #40：+1/5 秒提配置，数值不变）----

    public static final ModConfigSpec.IntValue BOOKKEEPER_XP_PER_GAIN = BUILDER
            .comment("簿记员每次在职成长获得的簿记经验（每 5 秒结算一次）",
                    "Bookkeeping xp per on-duty gain (settled every 5 seconds)")
            .defineInRange("bookkeeper.xpPerGain", 1, 0, 100);

    // ---- 记忆（v0.9.3 铭记）----

    public static final ModConfigSpec.IntValue MEMORY_THRESHOLD = BUILDER
            .comment("铭记阈值：进度积满即固化为永久记忆（想法日志宣布，不说话）",
                    "Threshold: progress reaching it firms a permanent memory (announced in thought log only)")
            .defineInRange("memory.threshold", 100, 10, 10000);

    public static final ModConfigSpec.IntValue MEMORY_GAIN = BUILDER
            .comment("每次受苦事件（挨饿一次/一夜没睡）的进度增量",
                    "Progress gain per suffering event (one starving tick / one missed night)")
            .defineInRange("memory.gain", 15, 1, 1000);

    public static final ModConfigSpec.IntValue MEMORY_DECAY = BUILDER
            .comment("不再受苦时铭记进度的每日衰减量（归零清空；已固化的不受影响）",
                    "Daily decay of unfirmed memory progress when the suffering stops; firm memories unaffected")
            .defineInRange("memory.decay", 5, 0, 1000);

    public static final ModConfigSpec SPEC = BUILDER.build();

    // ==========================================================
    //  带兜底的读取（给"可能早于配置加载"的时机用）
    // ==========================================================

    /**
     * 实体属性注册（EntityAttributeCreationEvent）发生在 common 配置
     * 加载<b>之前</b>——直接 {@code get()} 会抛
     * "Cannot get config value before config is loaded"。
     * 属性表在 mod 加载期就要定稿，改配置后需要重启才生效（可接受）。
     * 其余时机（实体 tick 等）配置必然已加载，直接用字段即可。
     */
    private static boolean loaded() {
        return SPEC.isLoaded();
    }

    /** 最大生命；配置未加载时回落到默认值。 */
    public static double maxHealthOrDefault() {
        return loaded() ? MAX_HEALTH.get() : 20.0D;
    }

    /** 基础移速；配置未加载时回落到默认值。 */
    public static double movementSpeedOrDefault() {
        return loaded() ? MOVEMENT_SPEED.get() : 0.5D;
    }

    /** 感知范围；配置未加载时回落到默认值。 */
    public static double followRangeOrDefault() {
        return loaded() ? FOLLOW_RANGE.get() : 32.0D;
    }

    /** 战士攻击力；配置未加载时回落到默认值。 */
    public static double attackDamageOrDefault() {
        return loaded() ? WARRIOR_ATTACK_DAMAGE.get() : 3.0D;
    }

    /**
     * 战士出手距离（格）；配置未加载时回落到默认值。
     *
     * <p>实体 tick 里读属性比较频繁，这个方法包了一层 {@code loaded()}，
     * 避免"改配置热重载时属性还没跟上"导致 {@code get()} 抛异常。
     */
    public static double attackReachOrDefault() {
        return loaded() ? WARRIOR_ATTACK_REACH.get() : 2.5D;
    }

    private Config() {
    }
}
