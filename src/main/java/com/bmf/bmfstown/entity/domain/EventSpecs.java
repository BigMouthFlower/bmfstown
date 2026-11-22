package com.bmf.bmfstown.entity.domain;

import com.bmf.bmfstown.config.Config;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 事件声明总表（决策 #116）：所有 {@link EventSpec} 集中登记，
 * {@link Events} 通过 {@link #of(EventType)} 取用。
 *
 * <p>第一批（G2 第一步）只登记 4 条样板；其余 20 条第二批补齐后，
 * 契约测试会改为遍历 {@link EventType#values()} 全量断言"每个成员都有声明"。
 */
public final class EventSpecs {

    private static final Map<EventType, EventSpec> TABLE = new EnumMap<>(EventType.class);

    static {
        // 顺序即声明顺序。心情值用 IntSupplier 延迟读 Config，第一批保持可配不断电。

        register(new EventSpec(EventType.ANIMAL_LIKED)
                .mood(Config.MOOD_ANIMAL_LIKED::get)
                .thought("animal_liked").positive()
                .daily(1));                       // 现状：每天 1 次，走全局每日限幅

        register(new EventSpec(EventType.READING)
                .mood(Config.LEISURE_MOOD_BONUS::get).moodOverride()
                .thought("reading").positive()
                .clamp(ClampPolicy.NONE));        // 现状：绕开每日限幅（保行为）

        register(new EventSpec(EventType.TAVERN)
                .mood(Config.TAVERN_MOOD_BONUS::get).moodOverride()
                .thought("tavern").positive()
                .clamp(ClampPolicy.NONE));        // 现状：绕开每日限幅（保行为）

        register(new EventSpec(EventType.LEARNED)
                .thought("learned").knowledge()); // 无心情 → 知识专属色
    }

    private EventSpecs() {
    }

    private static void register(EventSpec spec) {
        spec.seal();
        EventSpec prev = TABLE.put(spec.type(), spec);
        if (prev != null) {
            throw new IllegalStateException("EventType 重复声明：" + spec.type());
        }
    }

    /** 取声明；未声明的类型直接抛（第一批后其余 20 条必须补全才能通过契约测试）。 */
    public static EventSpec of(EventType type) {
        EventSpec spec = TABLE.get(type);
        if (spec == null) {
            throw new IllegalStateException("EventType 未声明：" + type + "（第二批补齐）");
        }
        return spec;
    }

    public static boolean isDeclared(EventType type) {
        return TABLE.containsKey(type);
    }

    /** 已声明类型集合（第一批 4 条）。 */
    public static Set<EventType> declaredTypes() {
        return Collections.unmodifiableSet(TABLE.keySet());
    }
}