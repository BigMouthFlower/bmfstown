package com.bmf.bmfstown.social;

/**
 * 世界事件类型（v0.9.1 表达层）。
 *
 * <p>**刻意不与心情系统的 {@code EventType} 复用**：那边是"这件事对心情的
 * 影响"，这边是"世界上发生了这件事"——同一个触发在两边的语义和订阅者
 * 完全不同。心情结算产生事件后由映射关系各自处理。
 */
public enum TownEventType {
    /** 吃到了爱吃的食物（subject = 吃的镇民，item = 吃到的东西）。 */
    EAT_FAVORED,
    /** 天气发生变化（subject = 观察到变化的镇民，weather = 新天气）。 */
    WEATHER_CHANGED,
    /** 镇民正在挨饿（subject = 挨饿的镇民；挨饿心情事件同拍，半日至多一次）。 */
    STARVING,
    /** 镇民昨晚没睡好（subject = 没睡的镇民；晨间结算同拍，一晚至多一次）。 */
    SLEEPLESS
}
