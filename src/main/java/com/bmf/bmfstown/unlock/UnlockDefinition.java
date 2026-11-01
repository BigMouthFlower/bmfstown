package com.bmf.bmfstown.unlock;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Predicate;

/**
 * 一条解锁定义（登记表的一行）。
 *
 * <p>结构定稿（v0.10 设计讨论）：
 * <ul>
 *   <li><b>requires 是结构闸门</b>：前置未全部解锁时，本定义"未武装"——
 *       条件发生了也当没看见（不评估不记录）。时序绕过在这里被掐，
 *       不依赖设计者自觉；</li>
 *   <li><b>谓词必须可归因</b>：条件检查行为人/动作，而不是"世界上出现了
 *       什么"——现象可以被前置外的途径伪造（多人交易、捡漏、创造模式）；
 *       设计者登记每条定义时必须回答"这个现象会被前置外的途径伪造吗"；</li>
 *   <li><b>注册顺序保证无环</b>：requires 只能引用已注册的 id
 *       （先注册前置，再注册依赖它的），DAG 无环由结构保证。</li>
 * </ul>
 *
 * @param id              唯一键（如 "unlock.job.fisherman"、"unlock.job.worker"）
 * @param category        类别（profession / codex / secret / system / ui …），命名前缀即路由
 * @param requires        前置解锁 id 列表；无前置传空表
 * @param stateCondition  true = 状态型（武装瞬间立即评估当前状态）；
 *                        false = 事件型（等条件在下一次发生时解锁）
 * @param condition       解锁谓词；null = 手动型（只能由代码显式调用解锁）
 * @param displayNameKey  展示名的语言键（解锁公告的双色高亮部分，带提示音全服广播）；
 *                        null = 静默解锁（动态实例如图鉴食物不刷屏）
 */
public record UnlockDefinition(
        String id,
        String category,
        List<String> requires,
        boolean stateCondition,
        @Nullable Predicate<UnlockContext> condition,
        @Nullable String displayNameKey) {

    public UnlockDefinition {
        requires = List.copyOf(requires); // 防御性不可变
    }

    /** 手动型定义的便捷工厂（condition = null，只能显式解锁；带展示名 → 解锁公告）。 */
    public static UnlockDefinition manual(String id, String category, List<String> requires,
            String displayNameKey) {
        return new UnlockDefinition(id, category, requires, false, null, displayNameKey);
    }
}
