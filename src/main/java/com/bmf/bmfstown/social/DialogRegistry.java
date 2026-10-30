package com.bmf.bmfstown.social;

import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.entity.profile.WeatherPref;

import java.util.List;
import java.util.function.Predicate;

/**
 * 台词注册表（v0.9.1 表达层）：静态数据，一条台词 = 触发事件 + 文本 + 节奏。
 *
 * <p>文本走 lang key（支持 {物品} 模板槽），台词本身保持"一句话"纪律
 * （≤20 字，平淡口吻，不喊不叹）。以后条目多了再数据包化。
 */
public final class DialogRegistry {

    private DialogRegistry() {
    }

    /**
     * @param trigger   触发的事件类型
     * @param weather   天气类台词限定的新天气（null = 不限定）
     * @param group     冷却组（同组共享冷却，"eat"/"weather"/…）
     * @param key       lang key；带 %s 的会把物品名填进去
     * @param withItem  true = 把物品显示名作为第一个模板参数
     * @param condition 额外条件（如"不讨厌当前天气"），null = 无
     * @param weight    同组多条时加权抽取
     */
    public record DialogLine(TownEventType trigger, WeatherPref weather, String group,
                             String key, boolean withItem,
                             Predicate<Resident> condition, int weight) {
    }

    private static final List<DialogLine> LINES = List.of(
            // 吃到爱吃的：平淡口吻，带物品名
            new DialogLine(TownEventType.EAT_FAVORED, null, "eat",
                    "dialog.bmfstown.eat_favored", true, null, 3),
            new DialogLine(TownEventType.EAT_FAVORED, null, "eat",
                    "dialog.bmfstown.eat_favored_love", true, null, 2),
            // 天气转晴：不讨厌晴天的镇民
            new DialogLine(TownEventType.WEATHER_CHANGED, WeatherPref.CLEAR, "weather",
                    "dialog.bmfstown.weather_clear", false,
                    r -> r.getPrefs().dislikedWeather() != WeatherPref.CLEAR, 1),
            // 受苦即时线：挨饿（有"挨饿"永久记忆的镇民说带旧事语气的变体——condition 区分）
            new DialogLine(TownEventType.STARVING, null, "starving",
                    "dialog.bmfstown.starving", false,
                    r -> !r.getMemoryState().hasFirmMemory(
                            com.bmf.bmfstown.entity.domain.MemoryState.TYPE_STARVING), 1),
            new DialogLine(TownEventType.STARVING, null, "starving",
                    "dialog.bmfstown.starving_memory", false,
                    r -> r.getMemoryState().hasFirmMemory(
                            com.bmf.bmfstown.entity.domain.MemoryState.TYPE_STARVING), 1),
            // 受苦即时线：没睡好
            new DialogLine(TownEventType.SLEEPLESS, null, "sleepless",
                    "dialog.bmfstown.sleepless", false,
                    r -> !r.getMemoryState().hasFirmMemory(
                            com.bmf.bmfstown.entity.domain.MemoryState.TYPE_SLEEPLESS), 1),
            new DialogLine(TownEventType.SLEEPLESS, null, "sleepless",
                    "dialog.bmfstown.sleepless_memory", false,
                    r -> r.getMemoryState().hasFirmMemory(
                            com.bmf.bmfstown.entity.domain.MemoryState.TYPE_SLEEPLESS), 1)
    );

    /** 某事件可用的全部台词（触发类型匹配 + 天气匹配 + 附加条件通过）。 */
    public static List<DialogLine> matching(TownEvent event) {
        List<DialogLine> result = new java.util.ArrayList<>();
        for (DialogLine line : LINES) {
            if (line.trigger() != event.type) {
                continue;
            }
            if (line.weather() != null && line.weather() != event.weather) {
                continue;
            }
            if (line.condition() != null && !line.condition().test(event.subject)) {
                continue;
            }
            result.add(line);
        }
        return result;
    }

    /** 加权随机抽一条（调用方保证列表非空）。 */
    public static DialogLine pick(List<DialogLine> lines, net.minecraft.util.RandomSource random) {
        int total = 0;
        for (DialogLine line : lines) {
            total += line.weight();
        }
        int point = random.nextInt(total);
        for (DialogLine line : lines) {
            point -= line.weight();
            if (point < 0) {
                return line;
            }
        }
        return lines.get(lines.size() - 1);
    }
}
