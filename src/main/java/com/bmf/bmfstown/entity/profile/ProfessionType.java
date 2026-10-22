package com.bmf.bmfstown.entity.profile;

import net.minecraft.util.StringRepresentable;

/**
 * 专长类型（职业）。
 *
 * <h2>专长 ≠ 工作</h2>
 * 这是本项目最容易搞混的一对概念，必须分清楚：
 *
 * <table border="1">
 *   <tr><th></th><th>专长 {@code ProfessionType}</th><th>工作指派 {@code Assignment}</th></tr>
 *   <tr><td>回答</td><td>他<b>会</b>干什么</td><td>他现在<b>在做</b>什么</td></tr>
 *   <tr><td>能为空</td><td>可以（{@link #NONE}）</td><td>可以（没派活就闲逛）</td></tr>
 *   <tr><td>例子</td><td>"这人是卫士"</td><td>"他在东门站岗"</td></tr>
 * </table>
 *
 * <p><b>只有专长不会触发任何行为。</b>一个专长是卫士的镇民，如果没有被指派岗位，
 * 他照样吃饭、睡觉、溜达——和普通镇民没有任何区别。
 * 行为由「专长 + 岗位」共同决定，缺一不可。
 */
public enum ProfessionType implements StringRepresentable {

    /** 无专长。生成时的默认值。 */
    NONE("none", "无", 0),

    /**
     * 战士。两种工作模式：跟随玩家战斗（可离开领地）/ 在岗哨位置周围站岗。
     *
     * <p>历史注记：最初这个职业叫"卫士"（guard），与"战士"讨论后合并——
     * 老存档里序列化的 "guard" 会在 {@link #byName} 中自动映射到本职业。
     */
    WARRIOR("warrior", "战士", 10);

    private final String name;
    private final String zhName;
    /** 该专长的熟练度上限（软上限）。见 {@link ResidentProfile#level()}。 */
    private final int maxLevel;

    ProfessionType(String name, String zhName, int maxLevel) {
        this.name = name;
        this.zhName = zhName;
        this.maxLevel = maxLevel;
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }

    public String zhName() {
        return this.zhName;
    }

    /** 语言文件 key，UI 用 {@code Component.translatable(displayName())}。 */
    public String displayName() {
        return "profession.bmfstown." + this.name;
    }

    public int maxLevel() {
        return this.maxLevel;
    }

    /** 是否是"真正的"专长（不是 NONE）。 */
    public boolean isReal() {
        return this != NONE;
    }

    /**
     * 该职业的主技能（UI 经验条显示用）。
     * 无业没有经验条；战士主修格斗（警戒是副修，入账但不在此显示）。
     * 以后新职业在这里补映射。
     */
    public SkillType mainSkill() {
        return this == WARRIOR ? SkillType.COMBAT : null;
    }

    /**
     * 反序列化。未知名字一律回落到 {@link #NONE}，保证老存档不崩。
     * 兼容映射：老存档的 "guard"（卫士）合并进战士。
     */
    public static ProfessionType byName(String name) {
        if ("guard".equals(name)) {
            return WARRIOR;
        }
        for (ProfessionType v : values()) {
            if (v.name.equals(name)) {
                return v;
            }
        }
        return NONE;
    }
}
