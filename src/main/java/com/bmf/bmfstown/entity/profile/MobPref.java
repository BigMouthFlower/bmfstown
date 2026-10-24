package com.bmf.bmfstown.entity.profile;

import net.minecraft.world.entity.EntityType;

import java.util.Locale;

/**
 * 敌对生物厌恶表（v0.8）：每人 1/4 概率讨厌一种。
 *
 * <p>触发：该怪在镇民 16 格内出没（或与他发生战斗）→ 负面想法，
 * 每 5 分钟一次、无日上限——设计目的就是逼玩家给镇民
 * 营造安全环境（用户定稿）。
 *
 * <p>权重暂定全同等概率（用户拍板）；实测体感后再按
 * "苦力怕/僵尸常见 → 权重高"调整，只改这里。
 */
public enum MobPref {

    ZOMBIE(EntityType.ZOMBIE, "僵尸"),
    HUSK(EntityType.HUSK, "尸壳"),
    DROWNED(EntityType.DROWNED, "溺尸"),
    SKELETON(EntityType.SKELETON, "骷髅"),
    STRAY(EntityType.STRAY, "流浪者"),
    CREEPER(EntityType.CREEPER, "苦力怕"),
    SPIDER(EntityType.SPIDER, "蜘蛛"),
    CAVE_SPIDER(EntityType.CAVE_SPIDER, "洞穴蜘蛛"),
    WITCH(EntityType.WITCH, "女巫"),
    ENDERMAN(EntityType.ENDERMAN, "末影人"),
    SLIME(EntityType.SLIME, "史莱姆"),
    PHANTOM(EntityType.PHANTOM, "幻翼"),
    BLAZE(EntityType.BLAZE, "烈焰人"),
    SILVERFISH(EntityType.SILVERFISH, "蠹虫");

    private final EntityType<?> type;
    private final String zhName;

    MobPref(EntityType<?> type, String zhName) {
        this.type = type;
        this.zhName = zhName;
    }

    public EntityType<?> type() {
        return this.type;
    }

    public String zhName() {
        return this.zhName;
    }

    public String displayName() {
        return "mob.bmfstown." + name().toLowerCase(Locale.ROOT);
    }

    public static MobPref random(net.minecraft.util.RandomSource random) {
        return values()[random.nextInt(values().length)];
    }
}
