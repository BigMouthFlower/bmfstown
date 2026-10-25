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

    /** 性格类事件的基础数值（攀比/好战/胆小/爱热闹/贪吃统一用这个值）。 */
    public static final ModConfigSpec.IntValue MOOD_TRAIT_VALUE = BUILDER
            .comment("性格类事件的基础心情数值（统一 ±3）",
                    "Base mood value for personality-trait events (unified ±3)")
            .defineInRange("mood.traitValue", 3, 0, 100);

    /** 乐观性格每日固定增量；正面想法文案带 "+"。 */
    public static final ModConfigSpec.IntValue MOOD_TRAIT_OPTIMIST = BUILDER
            .comment("乐观性格每日固定的心情增量（正面想法带 + 标记）",
                    "Daily fixed mood gain for the Optimist trait (positive thoughts get a + marker)")
            .defineInRange("mood.traitOptimist", 2, 0, 100);

    /** 悲观性格每日固定减量；负面想法文案带 "−"。 */
    public static final ModConfigSpec.IntValue MOOD_TRAIT_PESSIMIST = BUILDER
            .comment("悲观性格每日固定的心情减量（负面想法带 − 标记）",
                    "Daily fixed mood loss for the Pessimist trait (negative thoughts get a − marker)")
            .defineInRange("mood.traitPessimist", 2, 0, 100);

    /** 好战性格：连续多少天没击杀就出负面想法。 */
    public static final ModConfigSpec.IntValue MOOD_TRAIT_WARLIKE_NO_KILL_DAYS = BUILDER
            .comment("好战性格连续多少天没击杀生物会心情下降",
                    "Days without a kill before the Warlike trait starts losing mood")
            .defineInRange("mood.traitWarlikeNoKillDays", 5, 1, 100);

    /** 好战性格：击杀带来的正面想法每天最多几次。 */
    public static final ModConfigSpec.IntValue MOOD_TRAIT_KILL_DAILY_LIMIT = BUILDER
            .comment("好战性格的击杀正面想法每天最多几次",
                    "Daily cap on kill-related positive thoughts for the Warlike trait")
            .defineInRange("mood.traitKillDailyLimit", 2, 1, 100);

    /** 胆小性格： hostile 生物在附近的负面想法触发间隔（dayTime）。 */
    public static final ModConfigSpec.IntValue MOOD_TRAIT_TIMID_INTERVAL = BUILDER
            .comment("胆小性格的负面想法触发间隔（dayTime，12000 = 半天）",
                    "Interval between Timid trait negative thoughts (dayTime ticks)")
            .defineInRange("mood.traitTimidInterval", 12000, 100, 24000);

    /** 贪吃性格：挨饿时的心情减量乘数（百分比，150 = ×1.5）。 */
    public static final ModConfigSpec.IntValue MOOD_TRAIT_GLUTTON_STARVE_PERCENT = BUILDER
            .comment("贪吃性格挨饿时的心情减量乘数（百分比，150 = ×1.5）",
                    "Starvation mood-loss multiplier (percent) for the Glutton trait")
            .defineInRange("mood.traitGluttonStarvePercent", 150, 100, 300);

    /** 和平主义性格：附近死亡的负面想法每天最多几次（v0.8.1）。 */
    public static final ModConfigSpec.IntValue MOOD_TRAIT_PACIFIST_DAILY_LIMIT = BUILDER
            .comment("和平主义性格的'附近有生物死亡'负面想法每天最多几次",
                    "Daily cap on nearby-death negative thoughts for the Pacifist trait")
            .defineInRange("mood.traitPacifistDailyLimit", 5, 1, 100);

    /** 爆炸主义性格：附近爆炸的正面想法每天最多几次（v0.8.1）。 */
    public static final ModConfigSpec.IntValue MOOD_TRAIT_EXPLOSION_DAILY_LIMIT = BUILDER
            .comment("爆炸主义性格的'附近发生爆炸'正面想法每天最多几次",
                    "Daily cap on nearby-explosion positive thoughts for the Explosion Lover trait")
            .defineInRange("mood.traitExplosionDailyLimit", 1, 1, 100);

    /** 昨晚没睡的每日心情减量；连续没睡逐夜叠加、不封顶；0 = 关闭（v0.8.1）。 */
    public static final ModConfigSpec.IntValue MOOD_NO_SLEEP = BUILDER
            .comment("昨晚没睡的每日心情减量（连续没睡逐夜叠加不封顶；战士守夜豁免）；0 = 关闭",
                    "Daily mood loss for not sleeping (stacks per missed night, uncapped; warriors on duty exempt); 0 = disabled")
            .defineInRange("mood.noSleep", 3, 0, 100);

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
            .comment("战士击杀敌人获得的格斗经验", "Combat xp per kill for warriors")
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

    /** 每件合成涨多少合成经验。 */
    public static final ModConfigSpec.IntValue CRAFT_XP_PER_ITEM = BUILDER
            .comment("工人每合成一件获得的合成经验", "Crafting xp per item for the worker")
            .defineInRange("craft.xpPerCraft", 10, 0, 1000);

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
