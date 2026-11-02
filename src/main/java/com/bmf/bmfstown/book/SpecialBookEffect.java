package com.bmf.bmfstown.book;

/**
 * 特殊书的习得效果（v0.10.3）：装备中的<b>已学</b>书提供效果
 * （极乐迪斯科"内化后才起效"口径），加成查询入口在
 * {@code Resident.learnedEffectBonus}。
 */
public record SpecialBookEffect(Type type, int amount) {

    public enum Type {
        /** 近战伤害 +%（战士书）。挂在 {@code Resident.doHurtTarget} 的职业效率乘区。 */
        MELEE_DAMAGE,
        /** 合成速度 +%（工人书）。挂在 {@code WorkerGoal.craftTicks} 的效率乘区。 */
        CRAFT_SPEED,
        /** 写书速度 +%（学者书）。挂在 {@code ScholarGoal.efficiency} 的乘区。 */
        WRITE_SPEED,
        /** 全技能经验获取 +%（通用）。挂在 {@code Resident.addSkillXp}。 */
        XP_BONUS
    }
}
