package com.bmf.bmfstown.entity.domain;

/**
 * 事件心情的每日限幅策略（决策 #116）。
 *
 * <p>原则（#116 追记）："绕过限制"用参数 0 表达，不在执行层写 "if (NONE) 跳过"。
 * 本枚举只是声明层的语义糖，执行时统一解析成 {@code cap} 整数、走同一条
 * {@code applyEventMood(resident, delta, cap)} 路径：
 * <ul>
 *   <li>{@link #DAILY} → {@code Config.MOOD_DAILY_CLAMP.get()}（全局每日配额）；</li>
 *   <li>{@link #NONE} → {@code 0}（不限额，直加不记账）。</li>
 * </ul>
 */
public enum ClampPolicy {
    DAILY,
    NONE
}