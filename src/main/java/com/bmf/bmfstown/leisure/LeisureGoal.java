package com.bmf.bmfstown.leisure;

import com.bmf.bmfstown.calendar.TownCalendar;
import com.bmf.bmfstown.config.Config;
import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.entity.WorkMode;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;
import java.util.List;

/**
 * 休闲决策器（v0.10.3）：空闲的镇民从**动态活动池**里随机挑一件事做
 * （闲逛/图书馆看闲书，以后酒吧等直接往池里加）。一段结束 → 随机间歇 →
 * 再决策；优先级链（睡觉 &gt; 吃饭 &gt; 上班 &gt; 休闲）每 tick 复查，
 * 窗口一关立刻 {@link LeisureActivity#abort} 当场收尾。
 *
 * <p><b>窗口口径（全部 dayTime 世界时间）</b>：
 * <ul>
 *   <li>夜间（睡觉窗口）绝不休闲——被叫醒也只会干等或自然游走（用户 23:39
 *       定稿：闲事不得与睡觉竞争）；</li>
 *   <li>无业：白天醒着即可；</li>
 *   <li>簿记员：无班表，白天可休闲（带路差事优先级更高，会打断）；</li>
 *   <li>工人/学者：非工作日全天；工作日仅上班前+下班后；</li>
 *   <li>战士跟随/站岗：不休闲（守夜/守岗）。</li>
 * </ul>
 * 决策随机到看书 → 启动活动；没随机到 → **不启动任何活动**，镇民回到
 * 原有的漫无目的游走（用户 23:40 定稿：闲逛不需要额外赋予行为）。
 * 段倒计时与窗口判定都走世界时间（用户 20:49 定稿）；阅读进度走游戏刻。
 */
public class LeisureGoal extends Goal {

    private final Resident resident;
    @javax.annotation.Nullable
    private LeisureActivity current;
    private int breakCooldown;
    private final RandomSource random = RandomSource.create();

    public LeisureGoal(Resident resident) {
        this.resident = resident;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    // ------------------------------------------------------------
    //  窗口判定
    // ------------------------------------------------------------

    /** 是否处于"该上班"状态（有职业且在工作时段的工作日；此时不休闲）。
     *  v0.14：窗口边界带晨夜轴个人偏移（resident.workWindow()/scholarWorkWindow()）。 */
    private boolean onDuty(ServerLevel level) {
        if (!TownCalendar.get(level).isWorkday(level)) {
            return false; // 周末：有职业者也休闲
        }
        return switch (this.resident.getProfession()) {
            case WORKER -> inHours(level, this.resident.workWindow());
            case SCHOLAR -> (this.resident.getWorkMode() == WorkMode.WRITE
                    || this.resident.getWorkMode() == WorkMode.COPY)
                    && inHours(level, this.resident.scholarWorkWindow());
            default -> false; // 无业/簿记员无班表；战士另由模式门排除
        };
    }

    /** 窗口边界判定（支持跨午夜）。start/end 为已含晨夜轴偏移的边界。 */
    private static boolean inHours(ServerLevel level, int[] window) {
        int t = (int) Math.floorMod(level.getDayTime(), 24000L);
        int start = window[0];
        int end = window[1];
        return start <= end ? (t >= start && t < end) : (t >= start || t < end);
    }

    /** 休闲窗口总门（不含冷却）：醒着、没在吃饭、没带路、不值班。
     *  v0.14：摸鱼窗口（专注轴负值触发）放行——摸鱼=上班时间开小差。 */
    private boolean windowOpen(ServerLevel level) {
        // 夜间（睡觉窗口，含晨夜轴偏移）绝不休闲——被叫醒的村民干等或自然游走，不去图书馆
        if (this.resident.isNightForMe(level)) {
            return false;
        }
        if (this.resident.isSleepRoutineActive()
                || this.resident.isForaging()
                || this.resident.getBookkeeperFindTarget() != null) {
            return false;
        }
        WorkMode mode = this.resident.getWorkMode();
        if (mode == WorkMode.FOLLOW || mode == WorkMode.GUARD_POST) {
            return false; // 战士工作中：守夜/守岗
        }
        if (this.resident.isGoofingOff(level)) {
            return true; // 摸鱼：上班时间的小差窗口（WorkerGoal/FireworksmanGoal 触发）
        }
        return !onDuty(level);
    }

    // ------------------------------------------------------------
    //  Goal 生命周期
    // ------------------------------------------------------------

    @Override
    public boolean canUse() {
        ServerLevel level = this.resident.level() instanceof ServerLevel sl ? sl : null;
        if (level == null) {
            return false;
        }
        if (this.breakCooldown > 0) {
            this.breakCooldown--;
            return false;
        }
        if (!windowOpen(level)) {
            return false;
        }
        if (this.current != null) {
            // 上次竞争落选的残留活动：优先级竞争失败后 Goal 没跑起来，
            // 旧的 start 残留（寻路等）在这里补 abort，绝不带着旧状态开新段
            this.current.abort(this.resident, level);
            this.current = null;
        }
        return pick(level);
    }

    @Override
    public boolean canContinueToUse() {
        ServerLevel level = this.resident.level() instanceof ServerLevel sl ? sl : null;
        if (level == null || this.current == null || !windowOpen(level)) {
            return false; // 窗口关闭 → stop() → abort（还书等当场收尾）
        }
        return true;
    }

    /** 活动是否仍在进行（stop 兜底用）。 */
    boolean hasCurrent() {
        return this.current != null;
    }

    /** 是否正在阅读（书在手里）——孤儿书清扫网的守卫。 */
    public boolean isReadingNow() {
        return this.current instanceof LibraryReadActivity a && a.isReading();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        // start 由 canUse 的 pick 选好活动后进入
    }

    @Override
    public void stop() {
        if (this.current != null) {
            ServerLevel level = this.resident.level() instanceof ServerLevel sl ? sl : null;
            if (level != null) {
                this.current.abort(this.resident, level);
            }
            this.current = null;
        }
        this.breakCooldown = this.randomBreak();
    }

    @Override
    public void tick() {
        ServerLevel level = this.resident.level() instanceof ServerLevel sl ? sl : null;
        if (level == null || this.current == null) {
            return;
        }
        if (!this.current.tick(this.resident, level)) {
            // 活动完成：段间歇后重新决策
            this.current = null;
            this.breakCooldown = this.randomBreak();
        }
    }

    // ------------------------------------------------------------
    //  决策：动态活动池随机
    // ------------------------------------------------------------

    /** 随机到看书的概率（%）：没随机到 = 不启动任何活动，自然游走兜底。 */
    private static final int LIBRARY_CHANCE = 50;

    /**
     * 决策：一半概率尝试"图书馆看闲书"，没随机到就什么都不启动——
     * 镇民回到原有的漫无目的游走（自然游走是既有 AI，不需要额外赋予）。
     * 以后加酒吧 = 这里多一个"随机到 X 就启动 X"的分支。
     */
    private boolean pick(ServerLevel level) {
        if (this.random.nextInt(100) >= LIBRARY_CHANCE) {
            this.breakCooldown = this.randomBreak(); // 没随机到：自然游走一阵再说
            return false;
        }
        LeisureActivity activity = new LibraryReadActivity();
        if (activity.canStart(this.resident, level) && activity.start(this.resident, level)) {
            this.current = activity;
            return true;
        }
        this.breakCooldown = 200; // 馆里没闲书可看：短冷却后重摇
        return false;
    }

    private int randomBreak() {
        int min = Config.LEISURE_BREAK_MIN.get();
        int max = Math.max(min, Config.LEISURE_BREAK_MAX.get());
        return Mth.randomBetweenInclusive(this.random, min, max);
    }
}
