package com.bmf.bmfstown.entity.profile;

import net.minecraft.util.RandomSource;

import java.util.Locale;

/**
 * 性格标签（v0.8）：每个镇民出生必抽 1 条、终生不变（用户定稿，无"普通人"）。
 *
 * <p>性格与偏好的区别：偏好是"遇到喜欢的东西才触发"，
 * 性格是"无时无刻不在影响判定"的心情滤镜。
 *
 * <p>v0.8 只走心情管线，不直接改效率公式——
 * 心情低本来就会降效率/罢工，等于间接影响，数值面不会一下失控。
 */
public enum PersonalityType {

    /** 攀比：护甲品质分档（钻/下界合金 → +3；无甲或皮/铁 → −3；金 = 中性事件）。 */
    ENVY("攀比", "很在意装备好不好"),
    /** 好战：击杀生物 +3（每天 2 次）；连续 5 天没击杀 −3。 */
    WARLIKE("好战", "闲不住，总想找点什么打"),
    /** 胆小：周围有敌对生物 −3；长时间平安无事 +3。 */
    TIMID("胆小", "见不得怪物靠近"),
    /** 爱热闹：身边有 2 个以上同伴 +3；独处半天以上 −3。 */
    GREGARIOUS("爱热闹", "一个人待不住"),
    /** 贪吃：任何进食 +3（每天 1 次）；挨饿时扣得更狠（×1.5）。 */
    GLUTTON("贪吃", "能吃是福"),
    /** 乐观：每日固定 +2；正面想法文案带 "+" 提示。 */
    OPTIMIST("乐观", "看什么都往好处想"),
    /** 悲观：每日固定 −2；负面想法文案带 "−" 提示。 */
    PESSIMIST("悲观", "看什么都往坏处想"),
    /** 和平主义者（v0.8.1）：附近有生物死亡（含自己杀的）→ −3，每天 5 次。 */
    PACIFIST("和平主义者", "见不得任何生命逝去"),
    /** 爆炸主义者（v0.8.1）：附近发生爆炸 → +3，每天 1 次。 */
    EXPLOSION_LOVER("爆炸主义者", "轰的一声就是浪漫");

    private final String zhName;
    private final String desc;

    PersonalityType(String zhName, String desc) {
        this.zhName = zhName;
        this.desc = desc;
    }

    public String zhName() {
        return this.zhName;
    }

    /** 一句话侧写（喜好页展示用）。 */
    public String desc() {
        return this.desc;
    }

    public String displayName() {
        return "personality.bmfstown." + name().toLowerCase(Locale.ROOT);
    }

    /** 一句话侧写的语言文件 key（UI 用 I18n 现译）。 */
    public String descKey() {
        return "personality.bmfstown.desc." + name().toLowerCase(Locale.ROOT);
    }

    public static PersonalityType random(RandomSource random) {
        return values()[random.nextInt(values().length)];
    }
}
