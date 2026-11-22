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
 * <p>{@code tone} 是旧路径写死的想法色调，仅剩 20 个未迁移事件还在用（见
 * {@link Status#keyOf(EventType)} 与 {@code Status.addThought}）；第一批不删，
 * 第二批全量迁移后移除。走 {@link Events} 的新事件颜色由实算增量动态计算。
 */
public enum EventType {
    /** 吃到了爱吃的食物（即时，挂在 settleBite）。 */
    ATE_FAVORED(Status.TONE_POSITIVE),
    /** 挨饿：找不到也拿不到食物（即时，幅度按连续天数递增）。 */
    STARVING(Status.TONE_NEGATIVE),
    /** 每日结算：今天干的是喜欢的工作。 */
    JOB_LIKED(Status.TONE_POSITIVE),
    /** 每日结算：住宅里有喜欢的建筑材质。 */
    BLOCK_LIKED(Status.TONE_POSITIVE),
    /** 感知：喜欢的动物在身边出没（每天 1 次）。 */
    ANIMAL_LIKED(Status.TONE_POSITIVE),
    /** 每日结算：住宅里有喜欢的颜色方块。 */
    COLOR_LIKED(Status.TONE_POSITIVE),
    /** 感知：附近唱片机在放（每天 1 次）。 */
    MUSIC_LIKED(Status.TONE_POSITIVE),
    /** 感知：遇到讨厌的天气（半天 1 次且天气变了）。 */
    WEATHER_DISLIKED(Status.TONE_NEGATIVE),
    /** 感知：天气变化的中性记录（没有讨厌天气的人也记）。 */
    WEATHER_NEUTRAL(Status.TONE_NEUTRAL),
    /** 感知：讨厌的生物在附近出没（5 分钟 1 次，无日上限）。 */
    MOB_DISLIKED(Status.TONE_NEGATIVE),
    /** 好战：击杀生物（每天上限见配置）。 */
    KILL(Status.TONE_POSITIVE),
    /** 好战：连续多天没击杀。 */
    KILL_NONE(Status.TONE_NEGATIVE),
    /** 勇气：周围有敌对生物。 */
    TIMID_SCARED(Status.TONE_NEGATIVE),
    /** 勇气：一整天平安无事（仅轴值<50 生效）。 */
    TIMID_SAFE(Status.TONE_POSITIVE),
    /** 社交：身边有 2 个以上同伴（对冲型，色调随轴值）。 */
    SOCIAL_CROWD(Status.TONE_POSITIVE),
    /** 社交：身边一个同伴都没有（对冲型，色调随轴值）。 */
    SOCIAL_LONELY(Status.TONE_NEGATIVE),
    /** 暴力：附近有生物死亡（色调随轴值：嗜血+仁慈−）。 */
    DEATH_NEARBY(Status.TONE_NEGATIVE),
    /** 暴力：附近发生爆炸（烟花除外；色调随轴值）。 */
    EXPLOSION_NEARBY(Status.TONE_POSITIVE),
    /** 暴力：附近有生物繁殖（色调随轴值）。 */
    BIRTH_NEARBY(Status.TONE_POSITIVE),
    /** 嫉妒：看到玩家的好装备（仅轴值>80 掉心情）。 */
    GEAR_ENVY(Status.TONE_NEGATIVE),
    /** 没睡好：昨晚整夜没躺过床，每多一夜多扣一次。 */
    NO_SLEEP(Status.TONE_NEGATIVE),

    // —— 第一批升格的裸字符串（决策 #116）——
    /** 读闲书（原 "reading"）：心情由批注倍率折算，差额走 moodOverride。 */
    READING(Status.TONE_POSITIVE),
    /** 酒馆喝一杯（原 "tavern"）：心情 + 少量饥饿回复。 */
    TAVERN(Status.TONE_POSITIVE),
    /** 学会一门特殊书（原 "learned"）：不影响心情 → 占位中性，实际走知识专属色。 */
    LEARNED(Status.TONE_NEUTRAL);

    /** 旧路径写死的想法色调（20 个未迁移事件仍走这里；第二批随全量迁移删除）。 */
    final int tone;

    EventType(int tone) {
        this.tone = tone;
    }
}