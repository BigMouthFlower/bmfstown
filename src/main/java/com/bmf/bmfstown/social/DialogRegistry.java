package com.bmf.bmfstown.social;

import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.entity.domain.EventType;
import com.bmf.bmfstown.entity.domain.SpeakTrigger;
import com.bmf.bmfstown.entity.profile.WeatherPref;

import java.util.List;
import java.util.function.Predicate;

/**
 * 台词注册表（v0.9.1 表达层）：静态数据，一条台词 = 触发事件 + 文本 + 节奏。
 *
 * <p>文本走 lang key（支持 {物品} 模板槽），台词本身保持"一句话"纪律
 * （≤20 字，平淡口吻，不喊不叹）。以后条目多了再数据包化。
 *
 * <p>v0.16.8：{@code trigger} 由 {@code TownEventType} 泛化为 {@link SpeakTrigger}，
 * 心情事件（{@link EventType}）与纯表达世界事件共用本表。
 * <b>匹配用引用相等</b>，两套枚举的同名成员不会串号。
 */
public final class DialogRegistry {

    private DialogRegistry() {
    }

    /**
     * @param trigger   触发的事件键（心情事件或纯表达世界事件）
     * @param weather   变体限定（天气类台词限定具体天气；null = 不限）
     * @param group     冷却组（同组共享冷却，"eat"/"weather"/…）
     * @param key       lang key；带 %s 的会把物品名填进去
     * @param withItem  true = 把物品显示名作为第一个模板参数
     * @param condition 额外条件（如"不讨厌当前天气"），null = 无
     * @param weight    同组多条时加权抽取
     */
    public record DialogLine(SpeakTrigger trigger, WeatherPref weather, String group,
                             String key, boolean withItem,
                             Predicate<Resident> condition, int weight) {
    }

    private static final List<DialogLine> LINES = List.of(
            // 吃到爱吃的：平淡口吻，带物品名
            new DialogLine(EventType.ATE_FAVORED, null, "eat",
                    "dialog.bmfstown.eat_favored", true, null, 3),
            new DialogLine(EventType.ATE_FAVORED, null, "eat",
                    "dialog.bmfstown.eat_favored_love", true, null, 2),
            // 天气转晴：不讨厌晴天的镇民
            new DialogLine(EventType.WEATHER_NEUTRAL, WeatherPref.CLEAR, "weather",
                    "dialog.bmfstown.weather_clear", false,
                    r -> r.getPrefs().dislikedWeather() != WeatherPref.CLEAR, 1),
            // 受苦即时线：挨饿（有"挨饿"永久记忆的镇民说带旧事语气的变体——condition 区分）
            new DialogLine(EventType.STARVING, null, "starving",
                    "dialog.bmfstown.starving", false,
                    r -> !r.getMemoryState().hasFirmMemory(
                            com.bmf.bmfstown.entity.domain.MemoryState.TYPE_STARVING), 1),
            new DialogLine(EventType.STARVING, null, "starving",
                    "dialog.bmfstown.starving_memory", false,
                    r -> r.getMemoryState().hasFirmMemory(
                            com.bmf.bmfstown.entity.domain.MemoryState.TYPE_STARVING), 1),
            // 受苦即时线：没睡好
            new DialogLine(EventType.NO_SLEEP, null, "sleepless",
                    "dialog.bmfstown.sleepless", false,
                    r -> !r.getMemoryState().hasFirmMemory(
                            com.bmf.bmfstown.entity.domain.MemoryState.TYPE_SLEEPLESS), 1),
            new DialogLine(EventType.NO_SLEEP, null, "sleepless",
                    "dialog.bmfstown.sleepless_memory", false,
                    r -> r.getMemoryState().hasFirmMemory(
                            com.bmf.bmfstown.entity.domain.MemoryState.TYPE_SLEEPLESS), 1),
            // 职业解锁线：镇民看见玩家携带 ≥3 种红石元件 → 复述 → 解锁工人职业
            new DialogLine(TownEventType.JOB_HINT, null, "jobhint",
                    "dialog.bmfstown.jobhint.worker", true, null, 1),
            // 职业引导线（可重复，不解锁）：看见红石块/矿 → "这东西能加工哦"
            new DialogLine(TownEventType.REDSTONE_AMBIENT, null, "jobhint_ambient",
                    "dialog.bmfstown.jobhint.redstone_ambient", true, null, 1)
    );

    /**
     * 某事件可用的全部台词：触发键匹配（引用相等）+ 变体匹配 + 附加条件通过。
     *
     * @param trigger 事件键；null 视作不匹配
     * @param variant 变体（天气 = {@link WeatherPref#name()}，如 {@code "CLEAR"}）；null = 无变体
     * @param subject 说话人（{@code condition} 谓词的输入）
     */
    public static List<DialogLine> matching(SpeakTrigger trigger, String variant, Resident subject) {
        List<DialogLine> result = new java.util.ArrayList<>();
        if (trigger == null) {
            return result;
        }
        for (DialogLine line : LINES) {
            if (line.trigger() != trigger) {
                continue; // 引用相等：不同枚举的同名成员天然不相交
            }
            if (line.weather() != null && !line.weather().name().equals(variant)) {
                continue;
            }
            if (line.condition() != null && !line.condition().test(subject)) {
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

    /** 台词表的触发键集合（契约测试用：声明了 bubble 的事件必须能查到台词）。 */
    public static boolean hasLineFor(SpeakTrigger trigger) {
        for (DialogLine line : LINES) {
            if (line.trigger() == trigger) {
                return true;
            }
        }
        return false;
    }
}