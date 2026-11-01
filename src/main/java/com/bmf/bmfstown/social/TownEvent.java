package com.bmf.bmfstown.social;

import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.entity.profile.WeatherPref;
import net.minecraft.world.item.ItemStack;

/**
 * 一条世界事件：发生源只负责"喊一嗓子"，不需要知道谁在听。
 *
 * <p>字段按事件类型部分为空（天气事件没有物品、进食事件没有天气）——
 * 用静态工厂保证语义，不暴露半初始化对象。
 */
public final class TownEvent {

    public final TownEventType type;
    /** 事件的亲历者（谁吃的/谁看到的）。天气事件 = 观察到变化的那个镇民。 */
    public final Resident subject;
    /** 相关物品（进食类）；无则 EMPTY。 */
    public final ItemStack item;
    /** 相关物品的**种类列表**（职业线索类：看到的元件按背包顺序去重）；无则空表。 */
    public final java.util.List<ItemStack> items;
    /** 新天气（天气类）；无则 null。 */
    public final WeatherPref weather;

    private TownEvent(TownEventType type, Resident subject, ItemStack item,
                      java.util.List<ItemStack> items, WeatherPref weather) {
        this.type = type;
        this.subject = subject;
        this.item = item;
        this.items = items;
        this.weather = weather;
    }

    public static TownEvent eatFavored(Resident subject, ItemStack item) {
        return new TownEvent(TownEventType.EAT_FAVORED, subject, item, null, null);
    }

    public static TownEvent weatherChanged(Resident observer, WeatherPref newWeather) {
        return new TownEvent(TownEventType.WEATHER_CHANGED, observer,
                ItemStack.EMPTY, null, newWeather);
    }

    public static TownEvent starving(Resident subject) {
        return new TownEvent(TownEventType.STARVING, subject, ItemStack.EMPTY, null, null);
    }

    public static TownEvent sleepless(Resident subject) {
        return new TownEvent(TownEventType.SLEEPLESS, subject, ItemStack.EMPTY, null, null);
    }

    /** 职业线索：镇民看到了玩家携带的这些种类（按背包顺序去重）。 */
    public static TownEvent jobHint(Resident subject, java.util.List<ItemStack> kinds) {
        return new TownEvent(TownEventType.JOB_HINT, subject, ItemStack.EMPTY,
                java.util.List.copyOf(kinds), null);
    }

    /** 职业引导线：镇民看到了玩家的红石块/矿（可重复，不解锁）。 */
    public static TownEvent redstoneAmbient(Resident subject, ItemStack ore) {
        return new TownEvent(TownEventType.REDSTONE_AMBIENT, subject, ore, null, null);
    }
}
