package com.bmf.bmfstown.eerie;

import com.bmf.bmfstown.BmfTown;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 恐怖线应验效果注册表（决策 #129，2026-11-25 用户拍板"声明式"）。
 *
 * <h2>要解决的问题</h2>
 * 用户原话：<i>"这个每个事件具体触发好像都需要特制，挺麻烦"</i>——如果每条
 * 故事线的应验都得在 {@code WanderEvents} 里加一段专属代码，加到第三条线
 * 就变成一坨 {@code if (line.equals(...))}。所以效果与故事线<b>解耦</b>：
 * <ul>
 *   <li>故事线只在自己的登记条目里写 {@code effectId}
 *       （{@link HorrorLines.HorrorLine#effectId()}），不写任何代码；</li>
 *   <li>效果实现进本类 {@link #EFFECTS} 表（id → 执行器）；</li>
 *   <li>两条故事线可以指向<b>同一个效果</b>（共享场景，无需重写）；</li>
 *   <li>没声明 effectId 的线 = 只发公告（测试线当前的行为）。</li>
 * </ul>
 *
 * <h2>新增一种效果的完整步骤（三步，不加分支）</h2>
 * <ol>
 *   <li>写一个 {@link Effect} 实现（自己决定传谁、做什么、如何收尾）；</li>
 *   <li>往 {@link #EFFECTS} 加一行 {@code put("你的id", 你的实现)}；</li>
 *   <li>故事线登记时写 {@code new HorrorLine("线id", 章数, "你的id")}。</li>
 * </ol>
 *
 * <h2>效果契约</h2>
 * {@link Effect#apply} 在<b>主线程</b>被调用（应验发生在服务端 tick/事件
 * 上下文里）。实现**必须自行处理**"目标玩家不在线 / 找不到人 / 世界不合法"
 * 等情形，静默返回即可——应验失败不该打断游戏。异常由 {@link #run} 兜底吞掉
 * 并记 error 日志，绝不冒泡到 tick。
 *
 * <h2>当前登记的效果</h2>
 * <ul>
 *   <li>{@code exile} — {@link ExileEffect}（虚空命令方块房，待一分钟送回）；</li>
 *   <li>空串 — 只发公告（不是"未登记"，是显式的合法配置）。</li>
 * </ul>
 */
public final class VerificationEffects {

    /**
     * 一种应验效果。
     *
     * @see VerificationEffects 类注释的"三步新增"
     */
    public interface Effect {
        /**
         * 执行应验。
         *
         * @param level    应验发生所在的服务器世界
         * @param townId   应验发生的城镇 id
         * @param resident 触发应验的居民（可为 null——调用方不保证）
         */
        void apply(ServerLevel level, UUID townId,
                   @Nullable com.bmf.bmfstown.entity.Resident resident);
    }

    /** 效果注册表（id → 执行器）。新增效果只在这里加一行。 */
    private static final Map<String, Effect> EFFECTS = new HashMap<>();

    static {
        EFFECTS.put(ExileEffect.ID, new ExileEffect());
    }

    // ==========================================================
    //  对外入口（WanderEvents 应验时调用）
    // ==========================================================

    /**
     * 按 id 执行应验效果。
     *
     * <p>id 为空 / 未登记 → 什么都不做。空 id 是合法的"只发公告"配置；
     * 非空但未登记 → 打 warn，说明故事线登记表的 id 写错了（配置错误，
     * 不是运行时故障）。
     */
    public static void run(String effectId, ServerLevel level, UUID townId,
                           @Nullable com.bmf.bmfstown.entity.Resident resident) {
        if (effectId == null || effectId.isEmpty()) {
            return; // 显式"只发公告"
        }
        Effect effect = EFFECTS.get(effectId);
        if (effect == null) {
            BmfTown.LOGGER.warn("[BmfTown][恐怖线] 未登记的应验效果 id：{}（故事线登记表写错了？）", effectId);
            return;
        }
        try {
            effect.apply(level, townId, resident);
        } catch (RuntimeException e) {
            // 应验是"锦上添花"，绝不允许因一个效果崩掉 tick
            BmfTown.LOGGER.error("[BmfTown][恐怖线] 应验效果 {} 执行异常（已吞，不影响游戏）", effectId, e);
        }
    }

    /** 该 id 是否已登记（测试/诊断用）。 */
    public static boolean isRegistered(String effectId) {
        return EFFECTS.containsKey(effectId);
    }

    /** 已登记的效果 id 集合（测试/诊断用，只读）。 */
    public static java.util.Set<String> registeredIds() {
        return java.util.Set.copyOf(EFFECTS.keySet());
    }

    private VerificationEffects() {
    }
}