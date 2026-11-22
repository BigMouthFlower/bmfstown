package com.bmf.bmfstown.entity.domain;

import com.bmf.bmfstown.entity.Resident;
import net.minecraft.server.level.ServerLevel;

/**
 * 事件统一入口（决策 #116）：把声明表里的规则落成对 {@link Status} /
 * {@link MemoryState} 的一次结算。
 *
 * <p>调用方只写 {@code Events.happen(resident, level, type[, moodOverride[, thoughtArg]])}，
 * 不再自写"加心情 + 记想法 + 节流"的散落代码。
 */
public final class Events {

    /** 想法语言 key 前缀（与 lang 文件 {@code mood.bmfstown.thought.*} 对齐）。 */
    public static final String THOUGHT_KEY_PREFIX = "mood.bmfstown.thought.";

    private Events() {
    }

    /** 无覆盖值、无参想法。 */
    public static boolean happen(Resident resident, ServerLevel level, EventType type) {
        return happen(resident, level, type, null, null);
    }

    /**
     * @param moodOverride null = 用声明值（仅对声明了 {@code moodOverride} 的事件传非空）；
     *                     否则忽略。
     * @param thoughtArg   想法动态参数（读书书名种子 / 学会书 id），可空。
     */
    public static boolean happen(Resident resident, ServerLevel level, EventType type,
                                 Integer moodOverride, String thoughtArg) {
        EventSpec spec = EventSpecs.of(type);
        Status status = resident.getStatus();
        status.ensureGenerated(resident, level); // 轴/偏好懒生成（幂等），色标与文案需要

        // 第一批只接 mood/thought/daily/clamp；其余声明到就抛，绝不静默丢功能（坑49 教训）
        if (spec.intervalTicks() > 0) {
            throw new IllegalStateException("interval 执行器未接（第二批）：" + type);
        }
        if (spec.memoryKind() != null) {
            throw new IllegalStateException("memory 执行器未接（第二批）：" + type);
        }
        if (spec.hasBubble()) {
            throw new IllegalStateException("bubble 执行器未接（第二批）：" + type);
        }

        // 节流：daily(0)=不限，consumeDaily 内部恒放行；仍是同一条路径，不写 if 跳过
        if (!status.consumeDaily(type, spec.dailyLimit())) {
            return false;
        }

        int requested = moodOverride != null ? moodOverride : (spec.hasMood() ? spec.moodValue() : 0);
        // 限幅：clampCap() 把 NONE 解析成 0，applyEventMood 单一路径（返回实际落账增量）
        status.applyEventMood(resident, requested, spec.clampCap());

        // 色调看意图方向（clamp 前），不看实算落账：心情满了喝酒仍是正面，不能变灰
        int tone = spec.toneFor(requested);
        String key = THOUGHT_KEY_PREFIX + spec.thoughtKey()
                + (spec.isSwing() ? swingSuffix(tone) : "");

        status.addThought(level, tone, key, status.hopeMarker(resident, level, tone), thoughtArg);
        return true;
    }

    private static String swingSuffix(int tone) {
        // 显式 == 比较（坑49：档位常量 TONE_NEGATIVE=2，禁止 "> 0" 假设负=负数）
        return tone == Status.TONE_POSITIVE ? "_pos" : "_neg";
    }
}