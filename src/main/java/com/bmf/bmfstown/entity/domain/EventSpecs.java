package com.bmf.bmfstown.entity.domain;

import com.bmf.bmfstown.config.Config;
import com.bmf.bmfstown.entity.profile.PersonalityAxis;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 事件声明总表（决策 #116）：所有 {@link EventSpec} 集中登记，
 * {@link Events} 通过 {@link #of(EventType)} 取用。
 *
 * <p>第二批起 24 条全量登记（每个 {@link EventType} 成员一条）；心情值 / 限流值
 * 均用 {@link java.util.function.IntSupplier} 延迟读 Config，保持游戏内可调不断电。
 * 契约测试遍历 {@link EventType#values()} 断言"每个成员都有声明"。
 */
public final class EventSpecs {

    private static final Map<EventType, EventSpec> TABLE = new EnumMap<>(EventType.class);

    static {
        // 顺序即声明顺序。mood 声明的是"意图基准"（未经 HOPE、未经 clamp）；
        // HOPE 乘区由 Events.happen 统一在基准之后应用（全覆盖，决策 #116 第二批）。

        // ---- 偏好 / 生理（固定方向）----

        register(new EventSpec(EventType.ATE_FAVORED)
                .mood(Config.MOOD_EAT_FAVORED::get)
                .thought("ate_favored").positive()
                .daily(Config.MOOD_EAT_FAVORED_DAILY_LIMIT::get)
                // v0.16.8：气泡与印象记忆从广播层搬回本表（原来分居 DialogEngine / MemoryState）
                .bubble("eat_favored")
                .memory(MemoryKind.IMPRESSION, "eat_favored"));

        register(new EventSpec(EventType.STARVING)
                .mood(Config.MOOD_STARVING::get).moodOverride()
                // 声明兜底键 starving_1；实际按连续天数由调用方覆盖 starving_1/2/3
                .thought("starving_1").negative()
                .interval(Config.MOOD_STARVING_INTERVAL::get)
                .clamp(ClampPolicy.NONE)
                .memory(MemoryKind.FIRM, "starving")
                .bubble("starving"));

        register(new EventSpec(EventType.JOB_LIKED)
                .mood(Config.MOOD_JOB_LIKED::get)
                .thought("job_liked").positive());

        register(new EventSpec(EventType.BLOCK_LIKED)
                .mood(Config.MOOD_BLOCK_LIKED::get)
                .thought("block_liked").positive());

        register(new EventSpec(EventType.ANIMAL_LIKED)
                .mood(Config.MOOD_ANIMAL_LIKED::get)
                .thought("animal_liked").positive()
                .daily(1));

        register(new EventSpec(EventType.COLOR_LIKED)
                .mood(Config.MOOD_COLOR_LIKED::get)
                .thought("color_liked").positive());

        register(new EventSpec(EventType.MUSIC_LIKED)
                .mood(Config.MOOD_MUSIC_LIKED::get)
                .thought("music_liked").positive()
                .daily(1));

        register(new EventSpec(EventType.WEATHER_DISLIKED)
                // 负面事件：Config 存的是幅度（正数），声明取负
                .mood(() -> -Config.MOOD_WEATHER_DISLIKED.get())
                .thought("weather_disliked").negative());

        register(new EventSpec(EventType.WEATHER_NEUTRAL)
                .thought("weather_neutral").neutral()
                // v0.16.8：天气台词搬回本表；变体（晴/雨/雷）由调用方运行时传入
                .bubble("weather"));

        register(new EventSpec(EventType.MOB_DISLIKED)
                // 负面事件：Config 存的是幅度（正数），声明取负
                .mood(() -> -Config.MOOD_MOB_DISLIKED.get())
                .thought("mob_disliked").negative()
                .interval(Config.MOOD_MOB_DISLIKED_INTERVAL::get));

        // ---- 轴事件（数值由调用方按轴公式算好，base 经 moodOverride 传入）----

        register(new EventSpec(EventType.KILL)
                .mood(Config.MOOD_TRAIT_VALUE::get).moodOverride()
                .thought("kill").positive()
                .daily(Config.MOOD_TRAIT_KILL_DAILY_LIMIT::get));

        register(new EventSpec(EventType.KILL_NONE)
                .mood(Config.MOOD_TRAIT_VALUE::get).moodOverride()
                .thought("kill_none").negative());

        register(new EventSpec(EventType.TIMID_SCARED)
                .mood(Config.MOOD_TRAIT_VALUE::get).moodOverride()
                .thought("timid_scared").negative()
                .interval(Config.MOOD_TRAIT_TIMID_INTERVAL::get));

        register(new EventSpec(EventType.TIMID_SAFE)
                .mood(Config.MOOD_TRAIT_VALUE::get).moodOverride()
                .thought("timid_safe").positive());

        // 社交对冲（决策 #116 第二批备忘：轴向具体影响待做）——本批保持现状写死色调，
        // 不做 swing 文案（不补 _pos/_neg）。
        register(new EventSpec(EventType.SOCIAL_CROWD)
                .mood(Config.MOOD_TRAIT_VALUE::get).moodOverride()
                .thought("social_crowd").positive());

        register(new EventSpec(EventType.SOCIAL_LONELY)
                .mood(Config.MOOD_TRAIT_VALUE::get).moodOverride()
                .thought("social_lonely").negative());

        register(new EventSpec(EventType.DEATH_NEARBY)
                .mood(Config.MOOD_TRAIT_VALUE::get).moodOverride()
                .thought("death_nearby").swing(PersonalityAxis.CRUELTY)
                .daily(Config.AXIS_DEATH_DAILY_LIMIT::get));

        register(new EventSpec(EventType.EXPLOSION_NEARBY)
                .mood(Config.MOOD_TRAIT_VALUE::get).moodOverride()
                .thought("explosion_nearby").swing(PersonalityAxis.CRUELTY)
                .daily(Config.AXIS_EXPLOSION_DAILY_LIMIT::get));

        register(new EventSpec(EventType.BIRTH_NEARBY)
                .mood(Config.MOOD_TRAIT_VALUE::get).moodOverride()
                .thought("birth_nearby").swing(PersonalityAxis.CRUELTY)
                .daily(Config.AXIS_BIRTH_DAILY_LIMIT::get));

        register(new EventSpec(EventType.GEAR_ENVY)
                .mood(Config.MOOD_TRAIT_VALUE::get).moodOverride()
                .thought("gear_envy").negative()
                .daily(Config.AXIS_GEAR_MOOD_DAILY_LIMIT::get));

        register(new EventSpec(EventType.NO_SLEEP)
                .mood(Config.MOOD_NO_SLEEP::get).moodOverride()
                .thought("no_sleep").negative()
                .clamp(ClampPolicy.NONE)
                .memory(MemoryKind.FIRM, "sleepless")
                .bubble("sleepless"));

        // ---- 休闲 / 知识（第一批升格）----

        register(new EventSpec(EventType.READING)
                .mood(Config.LEISURE_MOOD_BONUS::get).moodOverride()
                .thought("reading").positive()
                .clamp(ClampPolicy.NONE));

        register(new EventSpec(EventType.TAVERN)
                .mood(Config.TAVERN_MOOD_BONUS::get).moodOverride()
                .thought("tavern").positive()
                .clamp(ClampPolicy.NONE));

        register(new EventSpec(EventType.LEARNED)
                .thought("learned").knowledge());
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

    /** 取声明；未声明的类型直接抛。 */
    public static EventSpec of(EventType type) {
        EventSpec spec = TABLE.get(type);
        if (spec == null) {
            throw new IllegalStateException("EventType 未声明：" + type);
        }
        return spec;
    }

    public static boolean isDeclared(EventType type) {
        return TABLE.containsKey(type);
    }

    /** 已声明类型集合。 */
    public static Set<EventType> declaredTypes() {
        return Collections.unmodifiableSet(TABLE.keySet());
    }
}