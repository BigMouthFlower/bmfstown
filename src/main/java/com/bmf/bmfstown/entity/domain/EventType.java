package com.bmf.bmfstown.entity.domain;

/**
 * 心情事件类型（v0.16 事件系统·声明表，决策 #116）。
 *
 * <p>由 {@link Status} 内部枚举提为顶层类，供 {@link EventSpec}/{@link EventSpecs}
 * 声明表引用。成员名即存档键（{@code EventCounters} 与 {@link #name()} 双向绑定），
 * 21 个旧成员名一个都不改——改名会破存档。
 *
 * <p>{@code READING}/{@code TAVERN}/{@code LEARNED} 三个新成员由裸字符串升格
 * （原 "reading"/"tavern"/"learned"）。
 *
 * <p>旧路径写死的 {@code tone} 已于第二批删除：色调改由 {@link EventSpecs} 声明表
 * （swing 型随意图方向摆动，其余用声明定性）+ {@link Events#happen} 动态结算得出。
 */
public enum EventType implements SpeakTrigger {
    /** 吃到了爱吃的食物（即时，挂在 settleBite）。气泡与印象记忆均在本声明表执行。 */
    ATE_FAVORED,
    /** 挨饿：找不到也拿不到食物（即时，幅度按连续天数递增）。 */
    STARVING,
    /** 每日结算：今天干的是喜欢的工作。 */
    JOB_LIKED,
    /** 每日结算：住宅里有喜欢的建筑材质。 */
    BLOCK_LIKED,
    /** 感知：喜欢的动物在身边出没（每天 1 次）。 */
    ANIMAL_LIKED,
    /** 每日结算：住宅里有喜欢的颜色方块。 */
    COLOR_LIKED,
    /** 感知：附近唱片机在放（每天 1 次）。 */
    MUSIC_LIKED,
    /** 感知：遇到讨厌的天气（半天 1 次且天气变了）。 */
    WEATHER_DISLIKED,
    /** 感知：天气变化的中性记录（没有讨厌天气的人也记）。 */
    WEATHER_NEUTRAL,
    /** 感知：讨厌的生物在附近出没（5 分钟 1 次，无日上限）。 */
    MOB_DISLIKED,
    /** 好战：击杀生物（每天上限见配置）。 */
    KILL,
    /** 好战：连续多天没击杀。 */
    KILL_NONE,
    /** 勇气：周围有敌对生物。 */
    TIMID_SCARED,
    /** 勇气：一整天平安无事（仅轴值<50 生效）。 */
    TIMID_SAFE,
    /** 社交：身边有 2 个以上同伴（对冲型，色调随轴值）。 */
    SOCIAL_CROWD,
    /** 社交：身边一个同伴都没有（对冲型，色调随轴值）。 */
    SOCIAL_LONELY,
    /** 暴力：附近有生物死亡（色调随轴值：嗜血+仁慈−）。 */
    DEATH_NEARBY,
    /** 暴力：附近发生爆炸（烟花除外；色调随轴值）。 */
    EXPLOSION_NEARBY,
    /** 暴力：附近有生物繁殖（色调随轴值）。 */
    BIRTH_NEARBY,
    /** 嫉妒：看到玩家的好装备（仅轴值>80 掉心情）。 */
    GEAR_ENVY,
    /** 没睡好：昨晚整夜没躺过床，每多一夜多扣一次。 */
    NO_SLEEP,

    // —— 第一批升格的裸字符串（决策 #116）——
    /** 读闲书（原 "reading"）：心情由批注倍率折算，差额走 moodOverride。 */
    READING,
    /** 酒馆喝一杯（原 "tavern"）：心情 + 少量饥饿回复。 */
    TAVERN,
    /** 学会一门特殊书（原 "learned"）：不影响心情 → 知识专属色。 */
    LEARNED,

    /**
     * 评价玩家：看见玩家身上的好装备 → 说一句（与嫉妒轴无关，嗤/夸分档在台词条件里）。
     *
     * <p>声明只含气泡 + 个人每日 1 次 + 城镇冷却，不产生心情/想法；
     * 只有嫉妒 &gt;80 的那一路才另走 {@link #GEAR_ENVY}（扣心情、记想法）。
     * 选句在 {@code DialogRegistry}，两者是两条独立声明。
     */
    GEAR_SEEN,

    /**
     * 战士看见玩家：说"我以前也是个冒险家，直到膝盖中箭"（老滚 5 彩蛋）。
     *
     * <p>不要求玩家中箭，也不需要任何物品——就是战士的常驻感慨。
     * 限流走个人每日 1 次 + 城镇冷却，同样只开口不改心情。
     */
    KNEE_ARROW,

    /**
     * 玩家睡醒时，身边有比他早起的人 → "你终于醒了"（老滚 5 开场）。
     *
     * <p>玩家睡床后时间被推到当天边界，比玩家早起的人此刻已经在外活动；
     * 全镇一天一次（声明表 {@code townCooldown = 24000}），无意向心情/想法。
     */
    WOKE_UP;

    @Override
    public String id() {
        return name();
    }
}