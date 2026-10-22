package com.bmf.bmfstown.entity.profile;

/**
 * 技能类型 —— 每个镇民身上并行的多条独立经验条（矮人要塞式"干中学"）。
 *
 * <h2>与职业的关系</h2>
 * 职业决定"他现在做什么活"，技能决定"他做得多好多快"。一种工作只涨
 * 对应那一条技能的经验：巡逻涨 {@link #VIGILANCE}，战斗涨 {@link #COMBAT}，
 * 互不掺和。职业以后可以换，技能经验永远保留——老卫士转职战士，
 * 他的警戒经验还在档案里。
 *
 * <h2>为什么是枚举而不是字符串 key</h2>
 * 技能集合是封闭的（新增要伴随行为接入），枚举能防手滑拼错；
 * 序列化用 {@code serializedName}，与原版 NBT 风格一致。
 */
public enum SkillType {
    VIGILANCE("vigilance", "警戒"),
    COMBAT("combat", "格斗"),
    ALCHEMY("alchemy", "炼金"),
    FISHING("fishing", "钓鱼"),
    MINING("mining", "采矿"),
    CRAFTING("crafting", "合成"),
    COOKING("cooking", "烹调"),
    GATHERING("gathering", "采集"),
    BOOKKEEPING("bookkeeping", "簿记");

    private final String serializedName;
    private final String zhName;

    SkillType(String serializedName, String zhName) {
        this.serializedName = serializedName;
        this.zhName = zhName;
    }

    /** NBT / 存档用的小写名，发布后不要改。 */
    public String getSerializedName() {
        return this.serializedName;
    }

    /** 中文名（兜底显示）。 */
    public String zhName() {
        return this.zhName;
    }

    /** 语言文件 key（UI 取译文用）。 */
    public String displayName() {
        return "skill.bmfstown." + this.serializedName;
    }

    /** 按 NBT 字符串反查，查不到给 VIGILANCE 兜底（存档兼容基本功）。 */
    public static SkillType byName(String name) {
        for (SkillType type : values()) {
            if (type.serializedName.equals(name)) {
                return type;
            }
        }
        return VIGILANCE;
    }
}
