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

    /** 领地内漫步速度。 */
    public static final ModConfigSpec.DoubleValue STROLL_SPEED = BUILDER
            .comment("镇民在领地内漫步的速度", "Resident stroll speed inside territory")
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

    /** 每级所需基础熟练度（实际曲线乘等级系数）。 */
    public static final ModConfigSpec.IntValue XP_PER_LEVEL_BASE = BUILDER
            .comment("每级所需的基础熟练度", "Base XP required per level")
            .defineInRange("growth.xpPerLevelBase", 100, 1, 100000);

    /** 出生时的心情。 */
    public static final ModConfigSpec.IntValue INITIAL_MOOD = BUILDER
            .comment("镇民出生时的心情", "Initial mood of a new resident")
            .defineInRange("growth.initialMood", 100, 0, 100);

    /** 出生时的好感度。 */
    public static final ModConfigSpec.IntValue INITIAL_AFFECTION = BUILDER
            .comment("镇民出生时的好感度", "Initial affection of a new resident")
            .defineInRange("growth.initialAffection", 30, 0, 100);

    // ==========================================================
    //  镇民 AI
    // ==========================================================

    /** 领地逃逸检查频率（tick）。 */
    public static final ModConfigSpec.IntValue TOWN_CHECK_INTERVAL = BUILDER
            .comment("领地逃逸检查的频率（tick）", "Territory escape check interval (ticks)")
            .defineInRange("ai.townCheckIntervalTicks", 20, 1, 200);

    /** 逃逸后返回领地的速度（比漫步快一点）。 */
    public static final ModConfigSpec.DoubleValue RETURN_HOME_SPEED = BUILDER
            .comment("逃逸后返回领地的速度", "Speed when returning to territory after escaping")
            .defineInRange("ai.returnHomeSpeed", 0.8D, 0.05D, 4.0D);

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

    private Config() {
    }
}
