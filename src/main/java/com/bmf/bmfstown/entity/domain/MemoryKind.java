package com.bmf.bmfstown.entity.domain;

/**
 * 记忆两套（决策 #116 ⑥）：事件声明里显式区分"铭记"与"印象"。
 *
 * <ul>
 *   <li>{@link #FIRM}：累计→固化→永久（如挨饿/缺觉），走 {@code MemoryState.addProgress}；</li>
 *   <li>{@link #IMPRESSION}：随日衰减的浅层印象（如吃到爱吃的），走 {@code MemoryState.addImpression}。</li>
 * </ul>
 *
 * <p>第一批只在 {@link EventSpec} 里落地字段类型，执行器尚未接入（设计稿 §6 中间状态）。
 */
public enum MemoryKind {
    FIRM,
    IMPRESSION
}