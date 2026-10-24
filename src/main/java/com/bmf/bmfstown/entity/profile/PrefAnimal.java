package com.bmf.bmfstown.entity.profile;

import net.minecraft.world.entity.EntityType;

import java.util.Locale;

/**
 * 动物偏好白名单（v0.8）。
 *
 * <p>触发：该动物在镇民 8 格内出没 → 正面想法（每天 1 次）。
 * 判定用 {@link EntityType} 直等，不做 instanceof——
 * 原版动物类层级跨版本会变，类型常量最稳。
 */
public enum PrefAnimal {

    WOLF(EntityType.WOLF, "狼", "因为它们忠诚"),
    CAT(EntityType.CAT, "猫", "因为它的呼噜声让人平静"),
    FOX(EntityType.FOX, "狐狸", "因为它机灵的样子"),
    PARROT(EntityType.PARROT, "鹦鹉", "因为羽毛太漂亮了"),
    LLAMA(EntityType.LLAMA, "羊驼", "因为毛茸茸的"),
    RABBIT(EntityType.RABBIT, "兔子", "因为耳朵一动一动很可爱"),
    HORSE(EntityType.HORSE, "马", "因为它们奔跑的姿态"),
    PANDA(EntityType.PANDA, "熊猫", "因为憨态可掬"),
    COW(EntityType.COW, "牛", "因为慢悠悠的样子很安心"),
    PIG(EntityType.PIG, "猪", "因为圆滚滚的"),
    SHEEP(EntityType.SHEEP, "羊", "因为羊毛软绵绵的"),
    CHICKEN(EntityType.CHICKEN, "鸡", "因为啄米的样子很有趣");

    private final EntityType<?> type;
    private final String zhName;
    private final String reason;

    PrefAnimal(EntityType<?> type, String zhName, String reason) {
        this.type = type;
        this.zhName = zhName;
        this.reason = reason;
    }

    public EntityType<?> type() {
        return this.type;
    }

    public String zhName() {
        return this.zhName;
    }

    /** 喜欢理由（DF 风格 PREFSTRING）。 */
    public String reason() {
        return this.reason;
    }

    public String displayName() {
        return "animal.bmfstown." + name().toLowerCase(Locale.ROOT);
    }

    public static PrefAnimal random(net.minecraft.util.RandomSource random) {
        return values()[random.nextInt(values().length)];
    }
}
