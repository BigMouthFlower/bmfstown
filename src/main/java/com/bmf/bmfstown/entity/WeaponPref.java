package com.bmf.bmfstown.entity;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.function.Predicate;

/**
 * 战士武器类别偏好（决策 #49 手装导演，2026-11-09 用户拍板）。
 *
 * <h2>语义：偏好是过滤器，不是锁死</h2>
 * 战斗换装时先只在偏好类别里挑"对当前目标期望伤害最高"的一把；
 * 背包里没有该类别的武器 → <b>直接回落统一比较器</b>（全背包最高伤害，
 * 含工具——用户原话："工具类也算，肯定比空手伤害高"）。
 * {@link #NONE} = 无偏好，跳过过滤直接走统一比较器。
 *
 * <h2>类别口径（1.21.1）</h2>
 * 剑/斧走原版物品标签（{@code #c:swords} 一族的原版 {@code #minecraft:swords}）；
 * 重锤 1.21.1 没有独立标签（只有 {@code enchantable/mace}），直接判 {@link Items#MACE}。
 */
public enum WeaponPref {

    SWORD("swords", stack -> stack.is(ItemTags.SWORDS)),
    AXE("axes", stack -> stack.is(ItemTags.AXES)),
    MACE("mace", stack -> stack.is(Items.MACE)),
    /** 无偏好：不过滤，全背包按期望伤害取最高。 */
    NONE(null, stack -> false);

    private final String key;
    private final Predicate<ItemStack> filter;

    WeaponPref(String key, Predicate<ItemStack> filter) {
        this.key = key;
        this.filter = filter;
    }

    /** 该偏好的物品过滤谓词；NONE 返回恒 false（调用方应先判 NONE 跳过）。 */
    public boolean matches(ItemStack stack) {
        return this.filter.test(stack);
    }

    /** 语言键尾段（gui.bmfstown.resident.work.weapon.*）。 */
    public String key() {
        return this.key;
    }

    /** 工作页按钮点击时循环到下一档（SWORD → AXE → MACE → NONE → SWORD）。 */
    public WeaponPref next() {
        return values()[(this.ordinal() + 1) % values().length];
    }

    /**
     * 从存档字符串解析（非法/空 = NONE——旧档与手改 NBT 的兜底）。
     */
    public static WeaponPref parse(@Nullable String name) {
        if (name == null || name.isEmpty()) {
            return NONE;
        }
        try {
            return valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return NONE;
        }
    }
}
