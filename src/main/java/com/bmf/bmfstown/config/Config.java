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

    // ==========================================================
    //  战士工作（跟随 / 站岗）
    // ==========================================================

    /** 站岗时的活动半径（格）。战士只能在岗哨位置周围这么大范围里移动/索敌。 */
    public static final ModConfigSpec.IntValue WARRIOR_GUARD_RADIUS = BUILDER
            .comment("站岗活动半径（格）", "Guard post patrol radius (blocks)")
            .defineInRange("warrior.guardRadius", 10, 2, 64);

    /** 跟随玩家时的移速。 */
    public static final ModConfigSpec.DoubleValue WARRIOR_FOLLOW_SPEED = BUILDER
            .comment("跟随玩家时的移速", "Speed when following a player")
            .defineInRange("warrior.followSpeed", 1.15D, 0.05D, 4.0D);

    /** 站岗巡逻的移速。 */
    public static final ModConfigSpec.DoubleValue WARRIOR_GUARD_SPEED = BUILDER
            .comment("站岗巡逻的移速", "Speed when patrolling a guard post")
            .defineInRange("warrior.guardSpeed", 0.9D, 0.05D, 4.0D);

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
