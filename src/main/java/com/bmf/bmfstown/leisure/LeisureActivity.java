package com.bmf.bmfstown.leisure;

import com.bmf.bmfstown.entity.Resident;
import net.minecraft.server.level.ServerLevel;

/**
 * 闲暇活动（v0.10.3）：镇民空闲时随机挑一件事做——活动池动态登记，
 * 有多少种就从多少种里随机（用户定稿：不硬编码数量，以后酒吧等直接加）。
 *
 * <p><b>两条铁律</b>：
 * <ul>
 *   <li>优先级链每 tick 生效——睡觉 &gt; 吃饭 &gt; 上班 &gt; 休闲：窗口一关
 *       （{@code LeisureGoal.canContinueToUse} 判定失败）立刻 {@link #abort}，
 *       活动必须当场收尾（如把借的书还回书架），绝不占着东西过夜；</li>
 *   <li>段倒计时与窗口判定走 dayTime 世界时间；活动内部的"做事进度"
 *       （阅读累计等）走 gameTime 游戏刻——与写书进度同口径。</li>
 * </ul>
 */
public interface LeisureActivity {

    /** 能否现在开始（位置/物资条件；不含作息窗口判定——那是决策器的职责）。 */
    boolean canStart(Resident resident, ServerLevel level);

    /** 开始（寻路/取物/换手等准备）。返回 false = 开始失败，决策器换下一个活动。 */
    boolean start(Resident resident, ServerLevel level);

    /**
     * 每 tick 推进。
     *
     * @return true = 还在进行；false = 已完成（决策器记段间歇后重新决策）
     */
    boolean tick(Resident resident, ServerLevel level);

    /**
     * 被打断（天黑/开饭/上班）：当场收尾，必须幂等、绝不丢东西
     * （借的书还架、主手物品换回）。
     */
    void abort(Resident resident, ServerLevel level);
}
