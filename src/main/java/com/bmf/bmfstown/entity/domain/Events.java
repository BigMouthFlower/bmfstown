package com.bmf.bmfstown.entity.domain;

import com.bmf.bmfstown.config.Config;
import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.entity.profile.PersonalityAxis;
import com.bmf.bmfstown.entity.profile.PersonalityFormulas;
import com.bmf.bmfstown.social.TownEvent;
import com.bmf.bmfstown.social.TownEventBus;
import net.minecraft.server.level.ServerLevel;

/**
 * 事件统一入口（决策 #116）：把声明表里的规则落成对 {@link Status} /
 * {@link MemoryState} 的一次结算。
 *
 * <p>调用方只写 {@code Events.happen(resident, level, type[, moodBase[, thoughtArg]])}，
 * 不再自写"加心情 + 记想法 + 节流 + 记忆 + 气泡"的散落代码。心情链路统一：
 * <b>意图基准 → HOPE 乘区（全覆盖）→ 每日 clamp → 想法 → 记忆 → 气泡</b>。
 */
public final class Events {

    /** 想法语言 key 前缀（与 lang 文件 {@code mood.bmfstown.thought.*} 对齐）。 */
    public static final String THOUGHT_KEY_PREFIX = "mood.bmfstown.thought.";

    /** 记忆固化宣布想法 key 前缀（{@code memory_<type>}）。 */
    public static final String MEMORY_THOUGHT_KEY_PREFIX = "mood.bmfstown.thought.memory_";

    private Events() {
    }

    /** 无覆盖心情、无参想法。 */
    public static boolean happen(Resident resident, ServerLevel level, EventType type) {
        return happen(resident, level, type, null, null, null);
    }

    /** 仅覆盖心情基准（无参想法）。 */
    public static boolean happen(Resident resident, ServerLevel level, EventType type, double moodBase) {
        return happen(resident, level, type, moodBase, null, null);
    }

    /**
     * @param moodBase   null = 用声明值（仅对声明了 {@code moodOverride} 的事件传非空）；
     *                   是"意图基准"（未经 HOPE、未经 clamp），HOPE 乘区在此之后统一应用。
     * @param thoughtArg 想法动态参数（读书书名种子 / 学会书 id），可空。
     */
    public static boolean happen(Resident resident, ServerLevel level, EventType type,
                                 Double moodBase, String thoughtArg) {
        return happen(resident, level, type, moodBase, null, thoughtArg);
    }

    /**
     * @param thoughtKey 想法 key 后缀覆盖（如 {@code "starving_2"}）；null = 用声明 key。
     */
    public static boolean happen(Resident resident, ServerLevel level, EventType type,
                                 Double moodBase, String thoughtKey, String thoughtArg) {
        EventSpec spec = EventSpecs.of(type);
        Status status = resident.getStatus();
        status.ensureGenerated(resident, level); // 轴/偏好懒生成（幂等），色标/文案/HOPE 需要

        // 1. interval 节流（0 = 不限；WEATHER 的"天气变化"闸门留在调用方，声明为 0）
        if (!status.consumeInterval(type, spec.intervalTicks(), level.getDayTime())) {
            return false;
        }
        // 2. daily 节流（0 = 不限；consumeDaily 内部恒放行，同一条路径不写 if 跳过）
        if (!status.consumeDaily(type, spec.dailyLimit())) {
            return false;
        }

        // 3. 意图基准：调用方覆盖值 或 声明值（HOPE/clamp 之前）
        double base = moodBase != null ? moodBase : (spec.hasMood() ? spec.moodValue() : 0.0);

        // 4. HOPE 乘区（全覆盖，决策 #116 第二批）：基准 → 心态乘区 → 远离零取整。
        //    乘数恒正，符号不变，故色调拿 HOPE 前的 base 符号，不受缩放影响。
        int intended = Config.MOOD_HOPE_APPLY.get()
                ? PersonalityFormulas.resolve(status.axis(resident, level, PersonalityAxis.HOPE), base)
                : PersonalityFormulas.resolve(PersonalityAxis.NEUTRAL, base);

        // 5. clamp（单一路径：cap<=0 直加不记账，否则走每日配额）
        status.applyEventMood(resident, intended, spec.clampCap());

        // 6. 色调：swing 型随意图方向摆动，其余用声明定性。
        int tone = spec.toneFor(base);

        // 7. 想法（swing 自动分 _pos/_neg）
        String key = THOUGHT_KEY_PREFIX + (thoughtKey != null ? thoughtKey : spec.thoughtKey())
                + (spec.isSwing() ? swingSuffix(tone) : "");
        status.addThought(level, tone, key, status.hopeMarker(resident, level, tone), thoughtArg);

        // 8. 记忆（仅 FIRM 铭记：加进度，积满固化则宣布紫色想法，不说话）
        if (spec.memoryKind() == MemoryKind.FIRM) {
            MemoryState memory = resident.getMemoryState();
            if (memory.addProgress(spec.memoryKey(), currentDay(level),
                    Config.MEMORY_GAIN.get(), Config.MEMORY_THRESHOLD.get())) {
                status.addThought(level, Status.TONE_MEMORY_BAD,
                        MEMORY_THOUGHT_KEY_PREFIX + spec.memoryKey(), 0, null);
            }
        }

        // 9. 气泡（无参台词；带参/广播类气泡仍留在调用方 dispatch）
        if (spec.hasBubble()) {
            emitBubble(resident, type);
        }
        return true;
    }

    private static String swingSuffix(int tone) {
        // 显式 == 比较（坑49：档位常量 TONE_NEGATIVE=2，禁止 "> 0" 假设负=负数）
        return tone == Status.TONE_POSITIVE ? "_pos" : "_neg";
    }

    private static void emitBubble(Resident resident, EventType type) {
        switch (type) {
            case STARVING -> TownEventBus.dispatch(TownEvent.starving(resident));
            case NO_SLEEP -> TownEventBus.dispatch(TownEvent.sleepless(resident));
            default -> throw new IllegalStateException("无参气泡未接线：" + type);
        }
    }

    private static int currentDay(ServerLevel level) {
        return (int) (level.getDayTime() / 24000L);
    }
}