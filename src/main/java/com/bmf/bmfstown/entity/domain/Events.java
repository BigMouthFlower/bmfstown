package com.bmf.bmfstown.entity.domain;

import com.bmf.bmfstown.config.Config;
import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.entity.profile.PersonalityAxis;
import com.bmf.bmfstown.entity.profile.PersonalityFormulas;
import com.bmf.bmfstown.social.DialogEngine;
import net.minecraft.server.level.ServerLevel;

/**
 * 事件统一入口（决策 #116；v0.16.8 吸收气泡与印象记忆）：
 * 把声明表里的规则落成对 {@link Status} / {@link MemoryState} / 台词引擎的一次结算。
 *
 * <p>调用方只写 {@code Events.happen(...)}，不再自写"加心情 + 记想法 + 节流 + 记忆 + 气泡"
 * 的散落代码。心情链路统一：
 * <b>意图基准 → HOPE 乘区（全覆盖）→ 每日 clamp → 想法 → 记忆 → 气泡</b>。
 *
 * <p><b>气泡归属</b>（v0.16.8）：气泡是"事件的一个出口"，不是独立事件。
 * 声明 {@code .bubble(topic)} 只表达"值得开口"，具体台词由 {@link DialogEngine}
 * 按 变体 + 条件 + 权重 选，冷却/嘴忙/相位错峰等<b>表达策略</b>全部留在表达层。
 * 因此嘴忙只影响表现、不影响结算——心情照加、想法照记，只是话没说出口。
 */
public final class Events {

    /** 想法语言 key 前缀（与 lang 文件 {@code mood.bmfstown.thought.*} 对齐）。 */
    public static final String THOUGHT_KEY_PREFIX = "mood.bmfstown.thought.";

    /** 记忆固化宣布想法 key 前缀（{@code memory_<type>}）。 */
    public static final String MEMORY_THOUGHT_KEY_PREFIX = "mood.bmfstown.thought.memory_";

    /**
     * 运行时动态参数包（全部可空；{@link #NONE} = 无任何动态参数）。
     *
     * <p>为什么单独成包：声明表是**静态**的，而天气变体、物品名这类参数是
     * **运行时**才知道的，只能由调用方传入。用 record 而不是继续加裸参数，
     * 是为了不让 {@code happen} 长出 9 个形参。
     */
    public record Args(String thoughtKey, String thoughtArg,
                       String bubbleVariant, String bubbleArg,
                       String memoryArg) {

        /** 无任何动态参数。 */
        public static final Args NONE = new Args(null, null, null, null, null);

        /** 气泡变体（如天气 {@code CLEAR/RAIN/THUNDER}）+ 模板参数（如物品名）。 */
        public static Args ofBubble(String variant, String arg) {
            return new Args(null, null, variant, arg, null);
        }

        /** 气泡 + 记忆两条参数（吃到爱吃的：台词带物品名、印象记物品 id）。 */
        public static Args ofBubbleMemory(String variant, String bubbleArg, String memoryArg) {
            return new Args(null, null, variant, bubbleArg, memoryArg);
        }
    }

    private Events() {
    }

    /** 无覆盖心情、无参想法。 */
    public static boolean happen(Resident resident, ServerLevel level, EventType type) {
        return happenWith(resident, level, type, null, Args.NONE);
    }

    /** 仅覆盖心情基准（无参想法）。 */
    public static boolean happen(Resident resident, ServerLevel level, EventType type, double moodBase) {
        return happenWith(resident, level, type, moodBase, Args.NONE);
    }

    /** 覆盖心情基准 + 想法动态参数。 */
    public static boolean happen(Resident resident, ServerLevel level, EventType type,
                                 Double moodBase, String thoughtArg) {
        return happenWith(resident, level, type, moodBase,
                new Args(null, thoughtArg, null, null, null));
    }

    /**
     * @param moodBase   null = 用声明值（仅对声明了 {@code moodOverride} 的事件传非空）；
     *                   是"意图基准"（未经 HOPE、未经 clamp），HOPE 乘区在此之后统一应用。
     * @param thoughtKey 想法 key 后缀覆盖（如 {@code "starving_2"}）；null = 用声明 key。
     * @param thoughtArg 想法动态参数（读书书名种子 / 学会书 id），可空。
     */
    public static boolean happen(Resident resident, ServerLevel level, EventType type,
                                 Double moodBase, String thoughtKey, String thoughtArg) {
        return happenWith(resident, level, type, moodBase,
                new Args(thoughtKey, thoughtArg, null, null, null));
    }

    /**
     * 全参入口：心情基准 + 全部动态参数（想法/气泡/记忆）。
     *
     * <p>刻意与 {@link #happen} 分开命名——否则 {@code happen(..., null)} 会在
     * {@code String} 与 {@link Args} 之间产生重载歧义。
     *
     * @param args 运行时动态参数，不可为 null（用 {@link Args#NONE}）。
     */
    public static boolean happenWith(Resident resident, ServerLevel level, EventType type,
                                     Double moodBase, Args args) {
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
        String key = THOUGHT_KEY_PREFIX + (args.thoughtKey() != null ? args.thoughtKey() : spec.thoughtKey())
                + (spec.isSwing() ? swingSuffix(tone) : "");
        status.addThought(level, tone, key, status.hopeMarker(resident, level, tone), args.thoughtArg());

        // 8. 记忆（FIRM = 铭记进度；IMPRESSION = 印象池，v0.16.8 从 MemoryState 订阅搬回）
        applyMemory(resident, level, status, spec, args);

        // 9. 气泡（只发"该说"意图，台词选句与全部表达策略在 DialogEngine）
        if (spec.hasBubble()) {
            DialogEngine.speak(resident, type, args.bubbleVariant(), args.bubbleArg());
        }
        return true;
    }

    private static void applyMemory(Resident resident, ServerLevel level, Status status,
                                    EventSpec spec, Args args) {
        if (spec.memoryKind() == null) {
            return;
        }
        MemoryState memory = resident.getMemoryState();
        int day = currentDay(level);
        if (spec.memoryKind() == MemoryKind.FIRM) {
            // 铭记：加进度，积满固化则宣布紫色想法（不冒泡）
            if (memory.addProgress(spec.memoryKey(), day,
                    Config.MEMORY_GAIN.get(), Config.MEMORY_THRESHOLD.get())) {
                status.addThought(level, Status.TONE_MEMORY_BAD,
                        MEMORY_THOUGHT_KEY_PREFIX + spec.memoryKey(), 0, null);
            }
        } else {
            // 印象：短期谈资，每日衰减（记忆 key 同时是印象类型）
            memory.addImpression(spec.memoryKey(), day,
                    MemoryState.IMPRESSION_STRENGTH,
                    args.memoryArg() == null ? "" : args.memoryArg());
        }
    }

    private static String swingSuffix(int tone) {
        // 显式 == 比较（坑49：档位常量 TONE_NEGATIVE=2，禁止 "> 0" 假设负=负数）
        return tone == Status.TONE_POSITIVE ? "_pos" : "_neg";
    }

    private static int currentDay(ServerLevel level) {
        return (int) (level.getDayTime() / 24000L);
    }
}