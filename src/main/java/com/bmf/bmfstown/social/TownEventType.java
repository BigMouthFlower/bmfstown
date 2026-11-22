package com.bmf.bmfstown.social;

import com.bmf.bmfstown.entity.domain.SpeakTrigger;

/**
 * 世界事件类型（v0.9.1 表达层；v0.16.8 收缩为"纯表达"）。
 *
 * <p>v0.16.8 起，<b>带结算的气泡</b>（吃到爱吃的、天气变化、挨饿、失眠）已全部
 * 搬进心情事件声明表（{@code EventType} + {@code EventSpecs.bubble}），由
 * {@code Events.happen} 直接驱动台词引擎。本枚举只剩<b>纯表达</b>成员——
 * 它们不产生任何心情/想法/记忆，只为"说一句话"而存在。
 *
 * <p>与 {@code EventType} 通过 {@link SpeakTrigger} 共用台词表，匹配用引用相等，
 * 不会串号（见接口注释）。
 */
public enum TownEventType implements SpeakTrigger {

    /** 职业解锁线索（subject = 线索镇民；items = 看到的元件种类，已去重）。 */
    JOB_HINT,
    /** 职业引导线（可重复，不解锁；subject = 说话的镇民，item = 看到的红石块/矿）。 */
    REDSTONE_AMBIENT;

    @Override
    public String id() {
        return name();
    }
}