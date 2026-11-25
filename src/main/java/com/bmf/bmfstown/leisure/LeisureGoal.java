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
 * （闲逛/图书馆看闲书/酒馆喝一杯（决策 #64））。一段结束 → 随机间歇 →
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
            case TAVERNKEEP -> inHours(level, this.resident.workWindow());
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
        // 城外回城期间挂起（2026-11-19 #91b）：散步等活动会跟回城抢导航
        if (this.resident.jobSuspended()) {
            return false;
        }
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
        // 城外回城期间挂起（2026-11-19 #91b）
        if (this.resident.jobSuspended()) {
            return false;
        }
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

    /** 图书馆去不成时的短冷却（保留旧口径：200t=10s 后重摇，不落闲逛）。 */
    private static final int LIBRARY_FAIL_RETRY_TICKS = 200;

    /**
     * 休闲活动池：<b>并行抽取</b>（2026-11-25 用户拍板，推翻串行两段掷骰）。
     *
     * <h2>为什么改（旧实现的三个毛病）</h2>
     * 旧代码是两段串行：先掷酒馆 20%，没成再用<b>另一颗骰子</b>掷图书馆 50%，
     * 都没中就闲逛。用户原话：<i>"他们应该是一个并行的抽取"</i>。旧写法的问题：
     * <ol>
     *   <li><b>酒馆占先手</b>：图书馆实际只有 40%（0.8×0.5），不是标称的 50%；</li>
     *   <li><b>去不成的份额漏掉</b>：酒馆掷中但今日已喝/没酒馆/窖空时，那 20%
     *       会往下漏给图书馆和闲逛，比例随时漂移；</li>
     *   <li><b>加第四件事就得插 if</b>：掷骰顺序本身成了隐性权重，越加越乱。</li>
     * </ol>
     *
     * <h2>现在</h2>
     * 三项各有一个<em>明确的权重</em>，一次掷出，权重和即分母——谁都不占先手：
     * <ul>
     *   <li>酒馆 {@code TAVERN_VISIT_CHANCE}（默认 20）；</li>
     *   <li>图书馆 {@code LEISURE_LIBRARY_CHANCE}（默认 50）；</li>
     *   <li>闲逛 {@code LEISURE_WANDER_CHANCE}（默认 30）。</li>
     * </ul>
     * 抽中但去不成（今日已喝/没酒馆/窖空）→ <b>落回闲逛</b>，不把份额偷偷
     * 补贴给图书馆（那又是变相的顺序优先）。
     *
     * <p>v0.16 沉浸层（#57）：闲逛段开始时掷闲暇随机事件（闲逛→（概率）随机
     * 事件→（子集）恐怖事件，见 {@code WanderEvents}）。<b>只有闲逛会掷</b>
     * ——去酒馆、去图书馆的两段都不掷。
     */
    private boolean pick(ServerLevel level) {
        int tavernWeight = clampChance(Config.TAVERN_VISIT_CHANCE.get());
        int libraryWeight = clampChance(Config.LEISURE_LIBRARY_CHANCE.get());
        int wanderWeight = clampChance(Config.LEISURE_WANDER_CHANCE.get());
        int total = tavernWeight + libraryWeight + wanderWeight;
        if (total <= 0) {
            this.breakCooldown = this.randomBreak(); // 三项全 0：不休闲（纯冷却）
            return false;
        }
        int roll = this.random.nextInt(total);

        // ---- 酒馆 ----
        if (roll < tavernWeight) {
            LeisureActivity tavern = new TavernVisitActivity();
            if (tavern.canStart(this.resident, level) && tavern.start(this.resident, level)) {
                this.current = tavern;
                return true;
            }
            return wanderOff(level); // 抽中但去不成 → 落回闲逛（份额不补贴给别人）
        }
        roll -= tavernWeight;

        // ---- 图书馆 ----
        if (roll < libraryWeight) {
            LeisureActivity library = new LibraryReadActivity();
            if (library.canStart(this.resident, level) && library.start(this.resident, level)) {
                this.current = library;
                return true;
            }
            this.breakCooldown = LIBRARY_FAIL_RETRY_TICKS; // 馆里没闲书可看：短冷却后重摇
            return false;
        }

        // ---- 闲逛 ----
        return wanderOff(level);
    }

    /** 闲逛一段：定长度（{@code LEISURE_WANDER_*}，与段间冷却分开）+ 掷随机事件。 */
    private boolean wanderOff(ServerLevel level) {
        this.breakCooldown = this.randomWander();
        com.bmf.bmfstown.eerie.WanderEvents.onWanderSegmentStart(this.resident, level);
        return false;
    }

    private static int clampChance(int percent) {
        return Math.min(100, Math.max(0, percent));
    }

    /** 段间冷却（活动结束→下次决策的空档）。 */
    private int randomBreak() {
        int min = Config.LEISURE_BREAK_MIN.get();
        int max = Math.max(min, Config.LEISURE_BREAK_MAX.get());
        return Mth.randomBetweenInclusive(this.random, min, max);
    }

    /**
     * 闲逛时长（与冷却分开：冷却只是"没在做事"的空档，闲逛是有长度的活动段）。
     *
     * <p>2026-11-25 用户："闲逛的时间有点短"——旧实现把闲逛时长和段间冷却
     * 共用 {@code LEISURE_BREAK_*}（200~600t=10~30s），比图书馆（1200~2400t）
     * 和酒馆（逗留 600~1200t 加上走位/点单）都短一截。现在单独配。
     */
    private int randomWander() {
        int min = Config.LEISURE_WANDER_MIN.get();
        int max = Math.max(min, Config.LEISURE_WANDER_MAX.get());
        return Mth.randomBetweenInclusive(this.random, min, max);
    }
}
