package com.bmf.bmfstown.social;

import com.bmf.bmfstown.entity.Resident;
import net.minecraft.world.item.ItemStack;

/**
 * 一条世界事件：发生源只负责"喊一嗓子"，不需要知道谁在听。
 *
 * <p>v0.16.8 起只承载<b>纯表达</b>事件（职业线索/引导线）——带结算的气泡
 * 已搬进 {@code EventSpecs} 的 {@code bubble} 声明，不再经过本类。
 *
 * <p>字段按事件类型部分为空（解锁线没有单个物品、引导线没有种类列表）——
 * 用静态工厂保证语义，不暴露半初始化对象。
 */
public final class TownEvent {

    public final TownEventType type;
    /** 事件的亲历者（谁看到的/谁在说）。 */
    public final Resident subject;
    /** 相关物品（引导线：看到的红石块/矿）；无则 EMPTY。 */
    public final ItemStack item;
    /** 相关物品的**种类列表**（解锁线：看到的元件按背包顺序去重）；无则 null。 */
    public final java.util.List<ItemStack> items;

    private TownEvent(TownEventType type, Resident subject, ItemStack item,
                      java.util.List<ItemStack> items) {
        this.type = type;
        this.subject = subject;
        this.item = item;
        this.items = items;
    }

    /** 职业线索：镇民看到了玩家携带的这些种类（按背包顺序去重）。 */
    public static TownEvent jobHint(Resident subject, java.util.List<ItemStack> kinds) {
        return new TownEvent(TownEventType.JOB_HINT, subject, ItemStack.EMPTY,
                java.util.List.copyOf(kinds));
    }

    /** 职业引导线：镇民看到了玩家的红石块/矿（可重复，不解锁）。 */
    public static TownEvent redstoneAmbient(Resident subject, ItemStack ore) {
        return new TownEvent(TownEventType.REDSTONE_AMBIENT, subject, ore, null);
    }
}