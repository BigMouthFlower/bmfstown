package com.bmf.bmfstown.town;

import com.bmf.bmfstown.config.Balance;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 分帧扫描调度器：把"不能在 1 tick 内扫完"的批量工作按每 tick 预算摊派。
 *
 * <h2>为什么存在</h2>
 * 建材扫描与物资普查原本各写了一份"队列 + 每 tick 预算 + 跨天丢弃 + tick 去重"，
 * 两处代码几乎逐行相同（连注释都写着"与建材扫描同款"）。本类把它们共用的
 * 四件事收成一份，业务只实现 {@link Task#advance} 与 {@link Task#onDone}。
 *
 * <h2>共性</h2>
 * <ul>
 *   <li><b>每 tick 限量</b>：全服所有任务合计最多 {@link Balance#SCAN_BLOCKS_PER_TICK} 格；</li>
 *   <li><b>按天过期</b>：任务带结算日戳，跨天自动丢弃并回调 {@link Task#onExpired}；</li>
 *   <li><b>tick 去重</b>：N 个镇民同 tick 各调一次 {@link #tick}，只执行第一次。</li>
 * </ul>
 *
 * <h2>驱动方式</h2>
 * 由 {@code Resident.customServerAiStep} 每 tick 调一次 {@link #tick(ServerLevel)}。
 * 没有镇民加载时任务暂停，恢复加载后继续，不丢结果。
 */
public final class ScanScheduler {

    /** 每 tick 全服扫描总量（格）。 */
    private static final int BUDGET_PER_TICK = Balance.SCAN_BLOCKS_PER_TICK;

    /** 进行中的任务，按维度分桶。 */
    private static final Map<ResourceKey<Level>, List<Task>> JOBS = new HashMap<>();

    /** tick 去重：同维度同 gameTime 只执行一次。 */
    private static ServerLevel lastTickedLevel;
    private static long lastTickedGameTime = Long.MIN_VALUE;

    private ScanScheduler() {
    }

    /** 一个可摊派的扫描任务。业务实现 advance / done / onDone。 */
    public interface Task {

        /** 任务的结算日（跨天自动丢弃）。 */
        int day();

        /**
         * 推进至多 {@code budget} 格。
         *
         * @return 实际消耗的格数；返回 {@code <= 0} 视为无法推进，任务会被移除（防死循环）
         */
        int advance(ServerLevel level, int budget);

        /** 是否已完成。 */
        boolean done();

        /** 完成回调（在 tick 内、任务移除前调用）。 */
        void onDone(ServerLevel level);

        /** 跨天被丢弃时的回调，默认忽略。 */
        default void onExpired() {
        }
    }

    /** 提交任务。同日同 key 的幂等由调用方用 {@link #find} 自行判断。 */
    public static void submit(ServerLevel level, Task task) {
        JOBS.computeIfAbsent(level.dimension(), k -> new ArrayList<>()).add(task);
    }

    /**
     * 查找本维度、<b>当天</b>、满足条件的在途任务。
     *
     * <p>只返回同日任务：跨天的旧任务即使还没被 tick 清理，也不该被当成
     * "今天已经委托过"而复用结果。
     */
    @SuppressWarnings("unchecked")
    public static <T extends Task> T find(ServerLevel level, Class<T> type, Predicate<T> filter) {
        List<Task> list = JOBS.get(level.dimension());
        if (list == null) {
            return null;
        }
        int today = currentDay(level);
        for (Task t : list) {
            if (t.day() == today && type.isInstance(t) && filter.test((T) t)) {
                return (T) t;
            }
        }
        return null;
    }

    /** 每 tick 推进。由镇民 AI tick 顺手驱动（内部去重）。 */
    public static void tick(ServerLevel level) {
        if (level == lastTickedLevel && level.getGameTime() == lastTickedGameTime) {
            return; // 同一 tick 里 N 个镇民各调了一次，只执行第一次
        }
        lastTickedLevel = level;
        lastTickedGameTime = level.getGameTime();

        List<Task> list = JOBS.get(level.dimension());
        if (list == null || list.isEmpty()) {
            return;
        }

        int budget = BUDGET_PER_TICK;
        int today = currentDay(level);
        Iterator<Task> it = list.iterator();
        while (it.hasNext() && budget > 0) {
            Task task = it.next();
            if (task.day() != today) {
                it.remove(); // 跨天弃扫：今天的结算会重新委托
                task.onExpired();
                continue;
            }
            int used = task.advance(level, budget);
            if (used <= 0 && !task.done()) {
                it.remove(); // 无法推进，丢弃防死循环
                task.onExpired();
                continue;
            }
            budget -= used;
            if (task.done()) {
                it.remove();
                task.onDone(level);
            }
        }
    }

    /** 世界绝对天数（与各业务原有的 dayTime/24000 口径一致）。 */
    public static int currentDay(ServerLevel level) {
        return (int) (level.getDayTime() / 24000L);
    }
}