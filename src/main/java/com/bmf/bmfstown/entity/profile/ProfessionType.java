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
     * 卫士。守卫城镇，击杀入侵的敌对生物。
     *
     * <p>需要岗位（巡逻点/范围）才会真正开始工作。岗位系统尚未实现，
     * 因此当前版本即使设为卫士也不会主动攻击——这是<b>预期行为</b>。
     */
    GUARD("guard", "卫士", 8);

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

    /** 反序列化。未知名字一律回落到 {@link #NONE}，保证老存档不崩。 */
    public static ProfessionType byName(String name) {
        for (ProfessionType v : values()) {
            if (v.name.equals(name)) {
                return v;
            }
        }
        return NONE;
    }
}
