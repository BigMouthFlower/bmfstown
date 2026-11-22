package com.bmf.bmfstown.entity.domain;

/**
 * 可触发台词的"事件键"（气泡路由用，v0.16.8）。
 *
 * <p>两套事件枚举通过实现本接口共用一个台词表：
 * <ul>
 *   <li>{@link EventType} —— 心情事件（结算层声明表）；</li>
 *   <li>{@code com.bmf.bmfstown.social.TownEventType} —— 世界事件（职业线索等纯表达）。</li>
 * </ul>
 *
 * <p><b>匹配一律用引用相等</b>：{@code EventType.STARVING != TownEventType.STARVING}
 * （不同枚举引用永不相等），天然隔离，不会串号。
 * <b>禁止用 {@link #id()} 作匹配键</b>——两套枚举存在同名成员（STARVING/SLEEPLESS…），
 * 字符串比较会把两边混在一起。{@code id()} 只供日志与调试使用。
 */
public interface SpeakTrigger {

    /** 稳定的调试标识；<b>不可用于匹配</b>（见类注释）。 */
    String id();
}