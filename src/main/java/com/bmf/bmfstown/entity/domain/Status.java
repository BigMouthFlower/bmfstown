package com.bmf.bmfstown.entity.domain;

import com.bmf.bmfstown.config.Config;
import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.entity.WorkMode;
import com.bmf.bmfstown.entity.profile.PersonalityAxis;
import com.bmf.bmfstown.entity.profile.PersonalityFormulas;
import com.bmf.bmfstown.entity.profile.MobPref;
import com.bmf.bmfstown.entity.profile.PrefAnimal;
import com.bmf.bmfstown.entity.profile.ProfessionType;
import com.bmf.bmfstown.entity.profile.SkillTiers;
import com.bmf.bmfstown.entity.profile.WeatherPref;
import com.bmf.bmfstown.town.TownZone;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 状态域：当下的生理/心理数值 + 心情结算引擎。
 *
 * <h2>六域重构（v0.8.2）</h2>
 * 原 MoodState 类名骗人——它装了心情引擎 + 偏好 + 特性 + 工人绑定四摊东西。
 * 重构后本类只保留真正的"状态"：hunger/mood 两个数值、心情事件结算、想法日志、
 * 结算/限流游标。偏好去了 {@link Prefs}，特性去了 {@link TraitState}，
 * 工人绑定去了 {@link JobState}。
 *
 * <h2>心情引擎职责边界</h2>
 * <ul>
 *   <li><b>本类管</b>：心情值的增减、事件限流、每日结算、想法日志、挨饿递增；</li>
 *   <li><b>本类不管</b>：心情的<b>输出</b>（效率乘数、罢工判断由 Resident 读
 *       {@link #mood} 自行决定）、想法的<b>显示</b>（客户端面板经网络包拿数据）。</li>
 * </ul>
 *
 * <h2>时间语义（v0.7 定稿，全部基于 dayTime）</h2>
 * "一天" = 主世界 {@code dayTime / 24000}；每个 NPC 有出生随机、落 NBT 的
 * {@link #settleOffset} 把结算零点摊开在全天，避免翻天瞬间扎堆卡顿。
 *
 * <h2>限流语义</h2>
 * <ul>
 *   <li>按天计数：吃喜（2 次）、动物/音乐（1 次）、好战击杀（上限可配）——每日结算清零；</li>
 *   <li>按 dayTime 间隔：挨饿（半天）、天气（半天且天气变了才触发）、
 *       生物厌恶（5 分钟，无日上限）、勇气遇敌（半天）；</li>
 *   <li>每日结算链：职业喜欢、好战无杀、勇气平安、社交扎堆/独处、
 *       住宅材质/颜色扫描——一天一次，各走 ±3 基准。</li>
 * </ul>
 *
 * <h2>性格轴（v0.14 多轴改版，决策 #44）</h2>
 * 旧 9 条离散词条替换为 10 条 0~100 连续轴（{@link PersonalityAxis}），
 * 公式统一在 {@link PersonalityFormulas}：轴值先算事件基准，再过心态乘区
 * （好事×(1+c)/坏事×(1−c)，可越过 ±3）。轴值落 NBT "Axes"。
 */
public final class Status {

    /** 饥饿上限，与玩家一致（20 点 = 10 个鸡腿）。 */
    public static final int MAX_HUNGER = 20;
    /** 心情上限。 */
    public static final int MAX_MOOD = 100;

    /** 想法日志容量：最多保留最近 30 条，新的把最老的挤掉（用户定稿）。 */
    private static final int MAX_THOUGHTS = 30;

    /** 感知扫描节拍（tick）：动物/唱片机/怪物/天气 5 秒一轮。 */
    private static final int SENSE_INTERVAL = 100;

    /** 动物触发距离（格）。 */
    private static final double ANIMAL_RANGE = 8.0D;

    /** 唱片机扫描距离（格）。 */
    private static final int JUKEBOX_RANGE = 8;

    /** 敌对生物扫描距离（格）：生物厌恶与胆小共用。 */
    private static final double HOSTILE_RANGE = 16.0D;

    /** 爱热闹：身边镇民密度统计半径（格）。 */
    private static final double CROWD_RANGE = 16.0D;

    /**
     * 想法色调（客户端三色渲染用）：中性灰 / 正面绿 / 负面红。
     *
     * <p>必须声明在 {@link EventType} <b>之前</b>——枚举常量的构造参数引用它们是
     * "前向引用"，放在枚举体内会直接编译失败（v0.8 踩过的坑）。
     */
    public static final int TONE_NEUTRAL = 0;
    /** 色调：正面（绿系）。 */
    public static final int TONE_POSITIVE = 1;
    /** 色调：负面（红系）。 */
    public static final int TONE_NEGATIVE = 2;
    /** 色调：坏记忆（淡紫）——记忆专用色，与普通好坏分开（用户 2026-10-31 定稿）。 */
    public static final int TONE_MEMORY_BAD = 3;
    /** 色调：好记忆（淡青）——预留给将来的正面铭记（如纪念日）。 */
    public static final int TONE_MEMORY_GOOD = 4;
    /** 色调：知识（亮金）——学成一门书等"不影响心情但影响知识"的事件（#116 追记）。 */
    public static final int TONE_KNOWLEDGE = 5;

    /** 事件类型已提为顶层类 {@link EventType}（决策 #116），20 个旧事件仍沿用其写死 tone。 */

    /**
     * 一条想法日志。只存翻译 key、发生日、色调、标记与可选参数——不存数值
     * （客户端用 I18n 现译，语言可切换；arg 为书名等动态参数，可空）。
     */
    public record Thought(int day, int tone, String key, int marker, String arg) {

        static final int MARK_NONE = 0;
        static final int MARK_PLUS = 1;
        static final int MARK_MINUS = 2;

        public Thought(int day, int tone, String key, int marker) {
            this(day, tone, key, marker, null); // 无参数想法的兼容构造
        }
    }

    /** 心情档位。 */
    public enum MoodTier {
        GREAT("great", "心情很好", "✨"),
        GOOD("good", "心情不错", "😊"),
        NORMAL("normal", "一般", ""),
        ANNOYED("annoyed", "有点烦", "💢"),
        ANGRY("angry", "很不高兴", "😠");

        private final String name;
        private final String zhName;
        private final String bubble;

        MoodTier(String name, String zhName, String bubble) {
            this.name = name;
            this.zhName = zhName;
            this.bubble = bubble;
        }

        public String zhName() {
            return this.zhName;
        }

        /** 语言文件 key。 */
        public String displayName() {
            return "mood.bmfstown." + this.name;
        }

        /** 头顶气泡用的字符，空字符串表示不显示。 */
        public String bubble() {
            return this.bubble;
        }
    }

    /** 凭数值判心情档（客户端面板显示要用：阈值只在这里写一份）。 */
    public static MoodTier moodTierOf(int mood) {
        if (mood >= 80) {
            return MoodTier.GREAT;
        }
        if (mood >= 60) {
            return MoodTier.GOOD;
        }
        if (mood >= 40) {
            return MoodTier.NORMAL;
        }
        if (mood >= 20) {
            return MoodTier.ANNOYED;
        }
        return MoodTier.ANGRY;
    }

    // ---- 当下数值 ----

    private int hunger = MAX_HUNGER;
    private int mood = Config.INITIAL_MOOD.get();

    /**
     * 性格轴值（v0.14 多轴改版）：10 条轴各一个 0~100 的值，null = 尚未抽取
     * （出生懒生成 / 旧档迁移按用户拍板随机重抽）。持久化在 "Axes" int 数组。
     */
    private int[] axes = null;

    // ---- 心情结算游标 ----

    /** 各事件今天的已触发次数（限流计数器）。随每日结算清零。 */
    private final Map<EventType, Integer> eventCountToday = new EnumMap<>(EventType.class);

    /** 各事件上次触发的 dayTime（interval 限流游标，随事件触发覆写）。 */
    private final Map<EventType, Long> lastIntervalAt = new EnumMap<>(EventType.class);

    /** 今天已经累计的心情变动（每日 clamp 配额）。 */
    private int dailyDelta = 0;

    /** 上次结算发生在（私人零点制的）第几天；-1 = 从未结算。 */
    private int lastSettledDay = -1;

    /** 结算相位偏移：出生随机、落 NBT，与 lastSettledDay 成对存取。 */
    private int settleOffset = RandomSource.create().nextInt(24000);

    /** 上次成功进食是第几天——挨饿递增的基准。 */
    private int lastFedDay = -1;

    /** 上次在酒馆喝到饮品是第几游戏日——每日一杯闸门（决策 #64）。 */
    private long lastTavernDay = -1;

    /** 昨晚结算是否罚了缺觉分（供 tickDaily 的记忆进度衰减判定，每日重置）。 */
    private boolean sleptBadToday = false;

    /** 想法日志，队尾最新。 */
    private final ArrayDeque<Thought> thoughts = new ArrayDeque<>();

    // ---- 性格附加状态 ----

    /** 好战：上次击杀是第几天。 */
    private int lastKillDay = -1;

    /** 胆小：上次被怪物吓到是第几天。 */
    private int lastTimidScareDay = -1;

    /** 感知节拍累加器（不落档）。 */
    private int senseTimer = 0;

    /** 上次天气事件触发的 dayTime。 */
    private long lastWeatherAt = Long.MIN_VALUE;

    /** 上次天气事件记录的天气（变了才允许再触发）。 */
    private WeatherPref lastWeather = null;

    /** 睡眠结算：最后一次睡觉所处的夜序号。 */
    private int lastSleptNightK = Integer.MIN_VALUE;

    /** 睡眠结算：已惩罚到的夜序号（含）。 */
    private int lastChargedNightK = Integer.MIN_VALUE;

    // ==========================================================
    //  读
    // ==========================================================

    public int hunger() {
        return this.hunger;
    }

    public int mood() {
        return this.mood;
    }

    /** 想法日志快照（旧的在前，新的在后）。客户端面板用。 */
    public ArrayDeque<Thought> thoughts() {
        return this.thoughts;
    }

    /** 缺口：距离吃饱还差多少点。 */
    public int hungerMissing() {
        return MAX_HUNGER - this.hunger;
    }

    /** 能不能吃下某个营养值的食物而不浪费（核心规则：只吃吃了不溢出的）。 */
    public boolean canEatWithoutWaste(int nutrition) {
        return this.hunger + nutrition <= MAX_HUNGER;
    }

    /** 心情分档。 */
    public MoodTier moodTier() {
        return moodTierOf(this.mood);
    }

    // ==========================================================
    //  性格轴（v0.14 多轴改版）
    // ==========================================================

    /**
     * 读一条轴的值。首次调用时懒生成（出生/旧档迁移 = 随机重抽，用户拍板）。
     * 调用方必须传服务端 level；事件入口都满足。
     */
    public int axis(Resident resident, ServerLevel level, PersonalityAxis axis) {
        ensureAxes(resident);
        return this.axes[axis.ordinal()];
    }

    /** 全轴快照（ResidentSnapshot 打包用；未生成返回 null）。 */
    public int[] axesView() {
        return this.axes == null ? null : this.axes.clone();
    }

    /** 调试/测试用：直接设轴值（钳制 0~100）；未生成时先随机补全。 */
    public void setAxis(PersonalityAxis axis, int value) {
        ensureAxesRaw();
        this.axes[axis.ordinal()] = PersonalityAxis.clampAxis(value);
    }

    private void ensureAxes(Resident resident) {
        if (this.axes == null) {
            this.axes = PersonalityAxis.rollAll(resident.getRandom());
        }
    }

    /** 无 RandomSource 场景（测试注入）的兜底生成。 */
    private void ensureAxesRaw() {
        if (this.axes == null) {
            this.axes = PersonalityAxis.rollAll(RandomSource.create());
        }
    }

    // ==========================================================
    //  写（调用方负责随后 resident.syncClient()；引擎内部已带）
    // ==========================================================

    public void setHunger(int value) {
        this.hunger = Math.max(0, Math.min(MAX_HUNGER, value));
    }

    public void setMood(int value) {
        this.mood = Math.max(0, Math.min(MAX_MOOD, value));
    }

    /**
     * 当前职业主技能的等级（1 起）——由技能经验实时推导，不单独存。
     * 旧 ResidentProfile.level() 的替代品。
     */
    public int level(Resident resident) {
        var skill = resident.getIdentity().profession().mainSkill();
        if (skill == null) {
            return 0;
        }
        return SkillTiers.levelOf(resident.getGrowth().skillXp(skill));
    }

    /** 称号。用于 UI 显示，不暴露数字。 */
    public String title(Resident resident) {
        int lv = level(resident);
        if (lv <= 0) {
            return "无";
        }
        if (lv <= 2) {
            return "新手";
        }
        if (lv <= 5) {
            return "熟练";
        }
        if (lv <= 8) {
            return "老手";
        }
        return "大师";
    }

    /** 称号的语言文件 key。UI 用这个取译文。 */
    public String titleKey(Resident resident) {
        int lv = level(resident);
        String suffix = lv <= 0 ? "none"
                : lv <= 2 ? "novice"
                : lv <= 5 ? "skilled"
                : lv <= 8 ? "veteran"
                : "master";
        return "title.bmfstown." + suffix;
    }

    // ==========================================================
    //  即时事件入口（Resident 的写入口调用）
    // ==========================================================

    /**
     * 吃到了爱吃的食物（记心情+想法+节流全走声明表 {@link Events}）。
     * 挂在 Resident.settleBite()；印象记忆与台词气泡仍在调用方（带食物参数）。
     */
    public void ateFavored(Resident resident, ServerLevel level) {
        Events.happen(resident, level, EventType.ATE_FAVORED);
    }

    /**
     * 成功进食了（不管是不是爱吃的）：刷新挨饿递增基准日。
     * 旧"贪吃"词条的进食加分已随多轴改版降级到喜好层（决策 #44，二期承接）。
     */
    public void noteFed(Resident resident, ServerLevel level) {
        ensureGenerated(resident, level);
        this.lastFedDay = currentDay(level);
    }

    /**
     * 挨饿：找不到可用的食物来源。间隔限流、记忆累计、气泡、紫色宣布
     * 全走声明表 {@link Events}；这里只算"挨饿天数递增 + 铭记罚分"的意图基准
     * 与三档文案 key（负值 = 受苦）。
     */
    public void starving(Resident resident, ServerLevel level) {
        int today = currentDay(level);
        int daysHungry = this.lastFedDay < 0 ? 1 : Math.max(1, today - this.lastFedDay);
        int base = Config.MOOD_STARVING.get();
        int delta;
        String key;
        if (daysHungry >= 3) {
            delta = base + 5;
            key = "starving_3";
        } else if (daysHungry == 2) {
            delta = base + 2;
            key = "starving_2";
        } else {
            delta = base;
            key = "starving_1";
        }
        // 铭记修正：身上有"挨饿"的永久记忆，旧病复发额外更痛（用户定稿 +10）
        if (resident.getMemoryState().hasFirmMemory(MemoryState.TYPE_STARVING)) {
            delta += Config.MOOD_MEMORY_PENALTY.get();
        }
        Events.happen(resident, level, EventType.STARVING, (double) (-delta), key, null);
    }

    /**
     * 好战轴：击杀生物。基准 = base·(v−50)/50（仅 v>50 生效），HOPE 乘区与
     * 每日上限走声明表；配额用完也刷新 lastKillDay（防连杀意外续上无杀计时）。
     */
    public void noteKill(Resident resident, ServerLevel level) {
        this.lastKillDay = currentDay(level);
        double base = PersonalityFormulas.killBase(
                this.axis(resident, level, PersonalityAxis.WARLIKE), Config.MOOD_TRAIT_VALUE.get());
        if (base == 0) {
            return; // 中位以下杀怪无感
        }
        Events.happen(resident, level, EventType.KILL, base);
    }

    /**
     * 暴力轴：附近有生物死亡。基准 = base·(v−50)/50 线性过零——
     * 仁慈（v<50）难受、嗜血（v>50）亢奋、中位无感（2026-11-08 定稿）。
     * 任何死亡都算（自己杀的也算），死者本人除外。
     */
    public void noteDeathNearby(Resident resident, ServerLevel level) {
        double base = PersonalityFormulas.violenceBase(
                this.axis(resident, level, PersonalityAxis.CRUELTY), Config.MOOD_TRAIT_VALUE.get());
        if (base == 0) {
            return; // 中位镇民对生死无感
        }
        Events.happen(resident, level, EventType.DEATH_NEARBY, base);
    }

    /**
     * 暴力轴：附近发生爆炸（烟花不算爆炸源）。与死亡同一条线性过零曲线。
     */
    public void noteExplosionNearby(Resident resident, ServerLevel level) {
        double base = PersonalityFormulas.violenceBase(
                this.axis(resident, level, PersonalityAxis.CRUELTY), Config.MOOD_TRAIT_VALUE.get());
        if (base == 0) {
            return;
        }
        Events.happen(resident, level, EventType.EXPLOSION_NEARBY, base);
    }

    /**
     * 暴力轴：附近有生物繁殖（新生命诞生）。与死亡镜像：仁慈欣喜、嗜血嫌吵。
     */
    public void noteBirthNearby(Resident resident, ServerLevel level) {
        double base = PersonalityFormulas.birthBase(
                this.axis(resident, level, PersonalityAxis.CRUELTY), Config.MOOD_TRAIT_VALUE.get());
        if (base == 0) {
            return;
        }
        Events.happen(resident, level, EventType.BIRTH_NEARBY, base);
    }

    /**
     * 嫉妒轴：看到玩家的好装备（GearJudgeHandler 判定后回调）。
     * 心情惩罚仅 v>80 生效（v=80→0，v=100→满扣 −base），每天上限见配置。
     */
    public void notePlayerGear(Resident resident, ServerLevel level) {
        double base = PersonalityFormulas.gearMoodBase(
                this.axis(resident, level, PersonalityAxis.ENVY), Config.MOOD_TRAIT_VALUE.get());
        if (base == 0) {
            return;
        }
        Events.happen(resident, level, EventType.GEAR_ENVY, base);
    }

    // ==========================================================
    //  每日结算（Resident.customServerAiStep 每 tick 调，内部自挡）
    // ==========================================================

    /**
     * 每 tick 的换天检查 + 一天一次的结算。
     * 每 tick 开销只有一次除法加一次比较。
     */
    public void tickDaily(Resident resident, ServerLevel level) {
        int today = (int) ((level.getDayTime() + this.settleOffset) / 24000L);
        if (today == this.lastSettledDay) {
            return;
        }
        this.lastSettledDay = today;
        this.eventCountToday.clear();
        this.dailyDelta = 0; // 每日限幅配额的零点（漏了它配额只减不增，心情会冻死）
        ensureGenerated(resident, level);

        settleProfession(resident, level);
        settleWarlike(resident, level);
        settleCourageSafe(resident, level);
        settleSocial(resident, level);
        settleHomeMaterials(resident, level);
        settleSleep(resident, level);
        settleMemory(resident, level);
        resident.getMemoryState().dailyDecay(); // 印象每日衰减：归零即遗忘（v0.9.2 接上钩子）
    }

    /** 结算 1：职业喜欢。判定基准是当前职业（数据驱动，新职业自动参与）。 */
    private void settleProfession(Resident resident, ServerLevel level) {
        ProfessionType current = resident.getIdentity().profession();
        if (current == null || current == ProfessionType.NONE) {
            return;
        }
        if (current == resident.getPrefs().likedProfession()) {
            Events.happen(resident, level, EventType.JOB_LIKED);
        }
    }

    /** 结算 2：好战轴——连续 N 天没击杀生物 → 负面（仅轴值>50 生效）。天数口径用绝对天。 */
    private void settleWarlike(Resident resident, ServerLevel level) {
        double base = PersonalityFormulas.noKillBase(
                this.axis(resident, level, PersonalityAxis.WARLIKE), Config.MOOD_TRAIT_VALUE.get());
        if (base == 0) {
            return;
        }
        int need = Config.MOOD_TRAIT_WARLIKE_NO_KILL_DAYS.get();
        int since = this.lastKillDay < 0 ? Integer.MAX_VALUE : currentDay(level) - this.lastKillDay;
        if (since >= need) {
            Events.happen(resident, level, EventType.KILL_NONE, base);
        }
    }

    /** 结算 3：勇气轴——一整天没被怪物吓过 → 正面（仅轴值<35 生效：能被吓到的人，悬着的心才会放下）。 */
    private void settleCourageSafe(Resident resident, ServerLevel level) {
        double base = PersonalityFormulas.safeBase(
                this.axis(resident, level, PersonalityAxis.COURAGE), Config.MOOD_TRAIT_VALUE.get());
        if (base == 0) {
            return;
        }
        int since = this.lastTimidScareDay < 0
                ? Integer.MAX_VALUE : currentDay(level) - this.lastTimidScareDay;
        if (since >= 1) {
            Events.happen(resident, level, EventType.TIMID_SAFE, base);
        }
    }

    /** 结算 4：社交轴——扎堆/独处的对冲感受（对冲型，两极各有感受）。 */
    private void settleSocial(Resident resident, ServerLevel level) {
        int social = this.axis(resident, level, PersonalityAxis.SOCIAL);
        AABB box = resident.getBoundingBox().inflate(CROWD_RANGE);
        int others = 0;
        for (Resident r : level.getEntitiesOfClass(Resident.class, box)) {
            if (r != resident && r.isAlive()) {
                others++;
            }
        }
        if (others >= 2) {
            double base = PersonalityFormulas.crowdBase(social, Config.MOOD_TRAIT_VALUE.get());
            if (base != 0) {
                Events.happen(resident, level, EventType.SOCIAL_CROWD, base);
            }
        } else if (others == 0) {
            double base = PersonalityFormulas.lonelyBase(social, Config.MOOD_TRAIT_VALUE.get());
            if (base != 0) {
                Events.happen(resident, level, EventType.SOCIAL_LONELY, base);
            }
        }
    }

    /**
     * 结算 7：住宅区里的材质与颜色。扫描交给 HomeMaterialScanManager 摊派执行。
     */
    private void settleHomeMaterials(Resident resident, ServerLevel level) {
        TownZone zone = resident.housingZone();
        if (zone == null) {
            return;
        }
        com.bmf.bmfstown.entity.HomeMaterialScanManager.request(level, zone, resident, this);
    }

    /** 住宅区扫描结果统一结算入口（HomeMaterialScanManager 完成时回调）。 */
    public void applyHomeMaterialResult(Resident resident, ServerLevel level,
                                        boolean blockHit, boolean colorHit) {
        if (blockHit) {
            Events.happen(resident, level, EventType.BLOCK_LIKED);
        }
        if (colorHit) {
            Events.happen(resident, level, EventType.COLOR_LIKED);
        }
    }

    // ==========================================================
    //  睡眠结算
    // ==========================================================

    /** 夜序号：dayTime t 所属夜晚的编号。 */
    private static int nightIndex(long dayTime) {
        return (int) Math.floorDiv(dayTime - 12000L, 24000L);
    }

    /** 躺下睡觉的瞬间登记（Resident.lieDown 调用，不落当天心情账）。 */
    public void noteSlept(ServerLevel level) {
        this.lastSleptNightK = nightIndex(level.getDayTime());
    }

    /**
     * 结算 8：昨晚睡了吗。每个完整结束的夜检查一次，漏夜逐个叠加惩罚；
     * 战士守夜豁免（不罚但账本推进）。
     */
    private void settleSleep(Resident resident, ServerLevel level) {
        int noSleep = Config.MOOD_NO_SLEEP.get();
        if (noSleep <= 0) {
            return;
        }
        long now = level.getDayTime();
        int kNow = nightIndex(now);
        boolean daytime = Math.floorMod(now, 24000L) < 12000L;
        int kLastComplete = daytime ? kNow : kNow - 1;

        // 回拨保护（v0.9.3）：/time set 往回拨后夜序号游标会停在"未来"，
        // missed 恒为负 → 缺觉结算永久卡死（实测同挨饿）。重置到当前：
        // 视为"到昨晚为止都已结算"，不补发巨量扣分。
        if (this.lastChargedNightK != Integer.MIN_VALUE && this.lastChargedNightK > kLastComplete) {
            this.lastChargedNightK = kLastComplete;
        }
        if (this.lastSleptNightK != Integer.MIN_VALUE && this.lastSleptNightK > kNow) {
            this.lastSleptNightK = kNow;
        }
        if (this.lastSleptNightK == Integer.MIN_VALUE) {
            this.lastSleptNightK = kNow;
        }
        if (this.lastChargedNightK == Integer.MIN_VALUE) {
            this.lastChargedNightK = kLastComplete;
            return;
        }

        if (resident.getJobState().workMode() != WorkMode.NONE) {
            this.lastChargedNightK = kLastComplete;
            return;
        }

        int floor = Math.max(this.lastChargedNightK, this.lastSleptNightK);
        int missed = kLastComplete - floor;
        if (missed <= 0) {
            this.lastChargedNightK = Math.max(this.lastChargedNightK, kLastComplete);
            return;
        }
        this.lastChargedNightK = kLastComplete;
        // 铭记修正：身上有"缺觉"的永久记忆，又没睡好额外更痛（用户定稿 +10）
        int memoryPenalty = resident.getMemoryState()
                .hasFirmMemory(com.bmf.bmfstown.entity.domain.MemoryState.TYPE_SLEEPLESS)
                ? Config.MOOD_MEMORY_PENALTY.get() : 0;
        // 缺觉心情/想法/记忆累计/气泡全走声明表（绕过每日 clamp，见 NO_SLEEP 声明）
        Events.happen(resident, level, EventType.NO_SLEEP,
                (double) (-missed * noSleep - memoryPenalty));
        this.sleptBadToday = true; // 供 tickDaily 的记忆进度衰减判定用
    }

    /**
     * 铭记进度的每日结算（tickDaily 末尾）：挨饿/缺觉的累计都在各自
     * 事件点发生（挨饿=饱食度归零且不解决；缺觉=晨间罚分），这里只管
     * "收手了的日子"进度 -5 淡忘（归零清空；已固化的不受影响）。
     */
    private void settleMemory(Resident resident, ServerLevel level) {
        com.bmf.bmfstown.entity.domain.MemoryState memory = resident.getMemoryState();
        // 挨饿：过去一天内有过挨饿事件（事件点已累计过）就不衰减
        long lastStarvingAt = this.lastIntervalAt.getOrDefault(EventType.STARVING, Long.MIN_VALUE);
        if (lastStarvingAt == Long.MIN_VALUE
                || level.getDayTime() - lastStarvingAt >= 24000L) {
            memory.decayProgress(com.bmf.bmfstown.entity.domain.MemoryState.TYPE_STARVING,
                    Config.MEMORY_DECAY.get());
        }
        // 缺觉：昨晚结算没罚分（事件点没累计过）→ 衰减
        if (!this.sleptBadToday) {
            memory.decayProgress(com.bmf.bmfstown.entity.domain.MemoryState.TYPE_SLEEPLESS,
                    Config.MEMORY_DECAY.get());
        }
        this.sleptBadToday = false;
    }

    // ==========================================================
    //  感知扫描（每 100 tick 一轮的周边世界检查）
    // ==========================================================

    /** 每 tick 的感知节拍：内部数到 SENSE_INTERVAL 才真正扫一次。 */
    /** 个人相位偏移（出生随机、落 NBT）：全镇负载均衡共用的"个人节拍"——
     * 每日结算用它错峰，台词触发/将来的轮询系统同样读它，保证同一个
     * NPC 在所有系统里的错峰节拍一致。 */
    public int phaseOffset() {
        return this.settleOffset;
    }

    public void tickSenses(Resident resident, ServerLevel level) {
        if (++this.senseTimer < SENSE_INTERVAL) {
            return;
        }
        this.senseTimer = 0;
        ensureGenerated(resident, level);

        senseAnimal(resident, level);
        senseJukebox(resident, level);
        senseHostiles(resident, level);
        senseWeather(resident, level);
        // 嫉妒轴（v0.14）：评判视野内玩家的随身装备 → 心情/台词
        com.bmf.bmfstown.entity.GearJudgeHandler.sense(resident, level);
        // 职业线索（v0.10）：看见玩家携带特定物品 → 线索台词 → 解锁职业
        com.bmf.bmfstown.unlock.JobHints.sense(resident, level);
    }

    /** 喜欢的动物在 8 格内出没 → 正面（每天 1 次，走声明表）。 */
    private void senseAnimal(Resident resident, ServerLevel level) {
        PrefAnimal likedAnimal = resident.getPrefs().likedAnimal();
        if (likedAnimal == null) {
            return;
        }
        AABB box = resident.getBoundingBox().inflate(ANIMAL_RANGE);
        boolean nearby = false;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (e.isAlive() && e.getType() == likedAnimal.type()) {
                nearby = true;
                break;
            }
        }
        if (nearby) {
            Events.happen(resident, level, EventType.ANIMAL_LIKED);
        }
    }

    /** 喜欢音乐且 8 格内有唱片机在放 → 正面（每天 1 次）。 */
    private void senseJukebox(Resident resident, ServerLevel level) {
        if (!resident.getPrefs().likesMusic()) {
            return;
        }
        BlockPos center = resident.blockPosition();
        boolean playing = false;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-JUKEBOX_RANGE, -JUKEBOX_RANGE, -JUKEBOX_RANGE),
                center.offset(JUKEBOX_RANGE, JUKEBOX_RANGE, JUKEBOX_RANGE))) {
            var state = level.getBlockState(pos);
            if (state.is(Blocks.JUKEBOX) && state.getValue(JukeboxBlock.HAS_RECORD)) {
                playing = true;
                break;
            }
        }
        if (playing) {
            Events.happen(resident, level, EventType.MUSIC_LIKED);
        }
    }

    /**
     * 16 格内的敌对生物：生物厌恶（5 分钟一次）+ 胆小受惊（半天一次）。
     * 类过滤器用 Mob + Enemy 接口（Monster 会漏史莱姆/岩浆怪）。
     */
    private void senseHostiles(Resident resident, ServerLevel level) {
        AABB box = resident.getBoundingBox().inflate(HOSTILE_RANGE);
        List<Mob> hostiles = level.getEntitiesOfClass(Mob.class, box);
        boolean anyAlive = false;
        boolean match = false;
        MobPref dislikedMob = resident.getPrefs().dislikedMob();
        for (Mob m : hostiles) {
            if (!(m instanceof Enemy)) {
                continue;
            }
            if (!m.isAlive()) {
                continue;
            }
            anyAlive = true;
            if (dislikedMob != null && m.getType() == dislikedMob.type()) {
                match = true;
            }
        }
        if (!anyAlive) {
            return;
        }

        if (match) {
            Events.happen(resident, level, EventType.MOB_DISLIKED);
        }

        // 勇气轴·遇敌（用户定稿）：仅胆小（<35）才怕怪——基准 = −base·(35−v)/35，
        // v=0 封顶恐慌、v≥35 无感（中位以上也怕会吓到太多人）。间隔走声明表。
        double scareBase = PersonalityFormulas.scareBase(
                this.axis(resident, level, PersonalityAxis.COURAGE), Config.MOOD_TRAIT_VALUE.get());
        if (scareBase != 0) {
            if (Events.happen(resident, level, EventType.TIMID_SCARED, scareBase)) {
                this.lastTimidScareDay = currentDay(level);
            }
        }
    }

    /** 天气变化：半天内最多一次，且天气变了才触发。 */
    private void senseWeather(Resident resident, ServerLevel level) {
        long now = level.getDayTime();
        if (!elapsed(now, this.lastWeatherAt, Config.MOOD_WEATHER_INTERVAL.get())) {
            return;
        }
        WeatherPref current = WeatherPref.current(level);
        if (current == this.lastWeather) {
            return;
        }
        this.lastWeatherAt = now;
        this.lastWeather = current;
        // 天气变化 → 广播站喊一嗓子（台词引擎/记忆仓自行订阅；v0.9.1 表达层）
        com.bmf.bmfstown.social.TownEventBus.dispatch(
                com.bmf.bmfstown.social.TownEvent.weatherChanged(resident, current));
        if (resident.getPrefs().dislikedWeather() == current) {
            Events.happen(resident, level, EventType.WEATHER_DISLIKED);
        } else {
            Events.happen(resident, level, EventType.WEATHER_NEUTRAL);
        }
    }

    // ==========================================================
    //  偏好懒生成（转发到 Prefs，顺带初始化自己的结算游标）
    // ==========================================================

    /**
     * 第一次用到时抽取偏好并标记已生成。
     * 为什么懒生成：偏好全存 Prefs 域 NBT，出生时生成与首次 tick 生成
     * 对玩家不可见，懒生成还能顺带兼容老存档（读档第一次 tick 补抽）。
     */
    public void ensureGenerated(Resident resident, ServerLevel level) {
        ensureAxes(resident); // 轴先行：抽"喜欢的职业"要按勇气轴过滤（胆小不喜战士）
        if (resident.getPrefs().generated()) {
            reconcileLikedProfession(resident);
            return;
        }
        resident.getPrefs().ensureGenerated(resident.getRandom(), isCowardly(resident));
        // 性格事件游标以"偏好生成那天"为初始值
        int today = currentDay(level);
        this.lastKillDay = today;
        this.lastTimidScareDay = today;
    }

    /**
     * 胆小判定（用户定稿）：勇气轴低于还手/逃跑分界线（35）。
     */
    private boolean isCowardly(Resident resident) {
        return resident.axisValue(PersonalityAxis.COURAGE)
                < (int) PersonalityFormulas.COURAGE_SPLIT_LINE;
    }

    /**
     * 喜好职业对账（用户定稿 2026-11-08：胆小不喜战士）：
     * 旧档（旧规则抽的）/ GATTACA 继承来的"胆小却喜好战士"在此修正。
     * 抽取路径已被 cowardly 参数拦住，这里只兜存量矛盾；幂等——
     * 重摇后不再是战士，下次进来直接跳过。
     */
    private void reconcileLikedProfession(Resident resident) {
        if (isCowardly(resident)
                && resident.getPrefs().likedProfession() == ProfessionType.WARRIOR) {
            resident.getPrefs().rerollLikedProfession(resident.getRandom(), true);
        }
    }

    /**
     * 重摇偏好与性格轴（调试命令 /bmfstown reroll 用）。
     * lastKillDay/lastTimidScareDay 重置到今天，语义是"今天刚出生"。
     */
    public void reroll(Resident resident, ServerLevel level) {
        this.axes = null;
        ensureAxes(resident); // 轴先行：重摇偏好要按新勇气轴过滤
        resident.getPrefs().reroll(resident.getRandom(), isCowardly(resident));
        // 游标重置到今天，语义是"今天刚出生"
        int today = currentDay(level);
        this.lastKillDay = today;
        this.lastTimidScareDay = today;
    }

    // ==========================================================
    //  心情施加（事件统一走 applyEventMood；applyRaw 是其底层直写）
    // ==========================================================

    /** 不带 clamp 的直接施加（applyEventMood 的底层）。 */
    private void applyRaw(Resident resident, int delta) {
        int oldMood = this.mood;
        int newMood = Math.max(0, Math.min(MAX_MOOD, oldMood + delta));
        if (newMood == oldMood) {
            return;
        }
        this.mood = newMood;
        resident.syncClient();
    }

    /**
     * 事件系统的统一心情施加（决策 #116）：{@code cap} 由声明解析而来，
     * {@code cap <= 0} = 不限幅、直加且不记账；否则走每日配额。返回<b>实际落账增量</b>
     * （供契约测试断言 clamp 截断；想法色调看意图方向，不看此返回值）。
     */
    int applyEventMood(Resident resident, int delta, int cap) {
        if (delta == 0) {
            return 0;
        }
        if (cap <= 0) {
            int before = this.mood;
            applyRaw(resident, delta);
            return this.mood - before;
        }
        int room = delta >= 0 ? cap - this.dailyDelta : -cap - this.dailyDelta;
        int actual = delta >= 0 ? Math.min(delta, Math.max(0, room))
                : Math.max(delta, Math.min(0, room));
        if (actual == 0) {
            return 0;
        }
        this.dailyDelta += actual;
        applyRaw(resident, actual);
        return actual;
    }

    /** 每日触发计数：{@code limit <= 0} = 不限，恒放行且不计数（"绕限制用参数 0 表达"）。 */
    boolean consumeDaily(EventType type, int limit) {
        if (limit <= 0) {
            return true;
        }
        int count = this.eventCountToday.getOrDefault(type, 0);
        if (count >= limit) {
            return false;
        }
        this.eventCountToday.put(type, count + 1);
        return true;
    }

    /**
     * dayTime 间隔限流：距该事件上次触发是否已过 {@code intervalTicks}；
     * {@code <= 0} = 不限恒放行；放行时覆写游标（统一收编挨饿/生物/勇气遇敌的旧独立字段）。
     */
    boolean consumeInterval(EventType type, int intervalTicks, long dayTime) {
        if (intervalTicks <= 0) {
            return true;
        }
        long last = this.lastIntervalAt.getOrDefault(type, Long.MIN_VALUE);
        if (!elapsed(dayTime, last, intervalTicks)) {
            return false;
        }
        this.lastIntervalAt.put(type, dayTime);
        return true;
    }

    /** 距 last 是否已过 interval（dayTime 口径；必须走这个 helper 防 long 溢出误判）。 */
    private static boolean elapsed(long now, long last, long interval) {
        // 回拨保护（v0.9.3）：/time set 把 dayTime 往回拨后，游标（last）会停在
        // "未来"，now - last 恒为负 → 节流永久卡死（实测：跳天不再触发挨饿/缺觉）。
        // 视为"节流重置"：放行一次，调用方会把游标覆写回当前时间。
        if (last == Long.MIN_VALUE || now < last) {
            return true;
        }
        return now - last >= interval;
    }

    /** 心态滤镜标记：乐观端 "+"（正面想法）/ 悲观端 "−"（负面想法）。 */
    int hopeMarker(Resident resident, ServerLevel level, int tone) {
        int hope = this.axis(resident, level, PersonalityAxis.HOPE);
        if (tone == TONE_POSITIVE && hope >= 61) {
            return Thought.MARK_PLUS;
        }
        if (tone == TONE_NEGATIVE && hope <= 39) {
            return Thought.MARK_MINUS;
        }
        return Thought.MARK_NONE;
    }

    

    /**
     * 读闲书（v0.11 用户定稿）：心情 +5，想法唯一文案
     * "他读了《XX》，感觉很有意思"（书名走参数，客户端现译）。
     */
    /** 读完闲书：心情加成由调用方按批注倍率折算（moodOverride），想法记书名（种子现译）。 */
    public void readEnjoyed(Resident resident, ServerLevel level, long bookSeed, int moodGain) {
        // arg 协议："idle:<种子>"——客户端按种子现译书名（双语自动）
        Events.happen(resident, level, EventType.READING, (double) moodGain, "idle:" + bookSeed);
    }

    /** 上次在酒馆喝到饮品是第几游戏日（每日一杯闸门读数）。 */
    public long lastTavernDay() {
        return this.lastTavernDay;
    }

    /** 标记"今天喝过了"（喝下那一口时落账）。 */
    public void setLastTavernDay(long day) {
        this.lastTavernDay = day;
    }

    /**
     * 酒馆喝一杯（v0.16 决策 #64）：心情 +gain（默认 3，低于读书的 +5——
     * 读书攒学识有价值，喝酒是纯消遣）+ 少量饥饿回复 + 想法留痕。
     * 药水效果（反胃）由活动侧另加——这里只管状态与心情。
     */
    public void drankAtTavern(Resident resident, ServerLevel level, int moodGain, int foodGain) {
        if (foodGain > 0) {
            this.hunger = Math.min(MAX_HUNGER, this.hunger + foodGain);
        }
        Events.happen(resident, level, EventType.TAVERN, (double) moodGain, null);
    }

    /** 学成一门特殊书（图书馆读完 learnTicks）：想法留名（无心情——收益是效果本身）。 */
    public void skillBookLearned(Resident resident, ServerLevel level, String bookId) {
        // arg 协议："book:<id>"——客户端按登记表 lang 键现译书名
        Events.happen(resident, level, EventType.LEARNED, null, "book:" + bookId);
    }

    /** 带参数的想法（如读书的种子）：客户端渲染时按协议现场生成书名填 %s。 */
    public void addThought(ServerLevel level, int tone, String key, int marker, String arg) {
        this.thoughts.addLast(new Thought(currentDay(level), tone, key, marker, arg));
        while (this.thoughts.size() > MAX_THOUGHTS) {
            this.thoughts.pollFirst();
        }
    }

    private static int currentDay(ServerLevel level) {
        return (int) (level.getDayTime() / 24000L);
    }

    // ==========================================================
    //  序列化（挂在 Resident 的 Domains 子标签下）
    // ==========================================================

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Hunger", this.hunger);
        tag.putInt("Mood", this.mood);
        tag.putInt("LastSettledDay", this.lastSettledDay);
        tag.putInt("SettleOffset", this.settleOffset);
        tag.putInt("LastFedDay", this.lastFedDay);
        tag.putInt("DailyDelta", this.dailyDelta);
        // 限流计数也要落档：不落的话读档即重置，"存读档刷心情"就成立了
        CompoundTag counterTag = new CompoundTag();
        for (Map.Entry<EventType, Integer> e : this.eventCountToday.entrySet()) {
            counterTag.putInt(e.getKey().name(), e.getValue());
        }
        if (!counterTag.isEmpty()) {
            tag.put("EventCounters", counterTag);
        }
        // interval 限流游标也落档（统一表；不落则读档即重置，"存读档刷限流"就成立）
        CompoundTag intervalTag = new CompoundTag();
        for (Map.Entry<EventType, Long> e : this.lastIntervalAt.entrySet()) {
            intervalTag.putLong(e.getKey().name(), e.getValue());
        }
        if (!intervalTag.isEmpty()) {
            tag.put("EventIntervals", intervalTag);
        }
        if (!this.thoughts.isEmpty()) {
            ListTag list = new ListTag();
            for (Thought t : this.thoughts) {
                CompoundTag tTag = new CompoundTag();
                tTag.putInt("Day", t.day());
                tTag.putInt("Tone", t.tone());
                tTag.putInt("Marker", t.marker());
                tTag.putString("Key", t.key());
                if (t.arg() != null) {
                    tTag.putString("Arg", t.arg());
                }
                list.add(tTag);
            }
            tag.put("Thoughts", list);
        }
        tag.putInt("LastKillDay", this.lastKillDay);
        tag.putLong("LastTavernDay", this.lastTavernDay);
        tag.putInt("LastTimidScareDay", this.lastTimidScareDay);
        tag.putLong("LastWeatherAt", this.lastWeatherAt);
        if (this.lastWeather != null) {
            tag.putString("LastWeather", this.lastWeather.name());
        }
        tag.putInt("LastSleptNightK", this.lastSleptNightK);
        tag.putInt("SleepChargedNightK", this.lastChargedNightK);
        // 性格轴（v0.14）：不落档的话读档即重抽，"存读档洗性格"就成立了
        if (this.axes != null) {
            tag.putIntArray("Axes", this.axes);
        }
        return tag;
    }

    /**
     * 从 NBT 读回。
     *
     * @param legacy true = 从旧版两个子标签读：游标/想法在 MoodState 子标签，
     *               hunger/mood 在 ResidentProfile 子标签（由 Resident 层拼好传入）
     */
    public static Status load(CompoundTag tag, CompoundTag legacyProfileTag, boolean legacy) {
        Status s = new Status();
        // Hunger/Mood：先读新格式键（六域 v0.9 起写入），读不到再回退 v0.8 老标签，
        // 最后兜底默认——⚠ v0.9.3 修复：此前只读 legacy 标签，新格式档永远
        // 读不到，导致每次重进世界饥饿回满/心情回初始（实测踩坑）
        s.hunger = tag.contains("Hunger") ? tag.getInt("Hunger")
                : (legacyProfileTag != null && legacyProfileTag.contains("Hunger")
                        ? legacyProfileTag.getInt("Hunger") : MAX_HUNGER);
        s.mood = tag.contains("Mood") ? tag.getInt("Mood")
                : (legacyProfileTag != null && legacyProfileTag.contains("Mood")
                        ? legacyProfileTag.getInt("Mood") : Config.INITIAL_MOOD.get());

        s.lastSettledDay = tag.contains("LastSettledDay") ? tag.getInt("LastSettledDay") : -1;
        // offset 与 lastSettledDay 成对读回——绝不能在读档时重随机（会重复结算 + 刷限流）
        s.settleOffset = tag.contains("SettleOffset")
                ? tag.getInt("SettleOffset") : RandomSource.create().nextInt(24000);
        s.lastFedDay = tag.contains("LastFedDay") ? tag.getInt("LastFedDay") : -1;
        // 每日一杯闸门也要落档：不落的话读档即重置，"存读档多喝一杯"就成立了
        s.lastTavernDay = tag.contains("LastTavernDay") ? tag.getLong("LastTavernDay") : -1;
        s.dailyDelta = tag.contains("DailyDelta") ? tag.getInt("DailyDelta") : 0;
        s.eventCountToday.clear();
        if (tag.contains("EventCounters")) {
            CompoundTag counterTag = tag.getCompound("EventCounters");
            for (EventType type : EventType.values()) {
                if (counterTag.contains(type.name())) {
                    s.eventCountToday.put(type, Math.max(0, counterTag.getInt(type.name())));
                }
            }
        }
        s.lastIntervalAt.clear();
        if (tag.contains("EventIntervals")) {
            CompoundTag intervalTag = tag.getCompound("EventIntervals");
            for (EventType type : EventType.values()) {
                if (intervalTag.contains(type.name())) {
                    s.lastIntervalAt.put(type, intervalTag.getLong(type.name()));
                }
            }
        } else {
            // 老档迁移：v0.16.6 前挨饿/生物/遇敌游标是三个独立键
            if (tag.contains("LastStarvingAt")) {
                s.lastIntervalAt.put(EventType.STARVING, tag.getLong("LastStarvingAt"));
            }
            if (tag.contains("LastMobAt")) {
                s.lastIntervalAt.put(EventType.MOB_DISLIKED, tag.getLong("LastMobAt"));
            }
            if (tag.contains("LastTimidAt")) {
                s.lastIntervalAt.put(EventType.TIMID_SCARED, tag.getLong("LastTimidAt"));
            }
        }
        s.thoughts.clear();
        if (tag.contains("Thoughts")) {
            ListTag list = tag.getList("Thoughts", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag tTag = list.getCompound(i);
                int tone = tTag.contains("Tone")
                        ? tTag.getInt("Tone")
                        : (tTag.getBoolean("Pos") ? TONE_POSITIVE : TONE_NEGATIVE);
                String arg = tTag.contains("Arg") ? tTag.getString("Arg") : null;
                s.thoughts.addLast(new Thought(tTag.getInt("Day"), tone,
                        tTag.getString("Key"), tTag.contains("Marker") ? tTag.getInt("Marker") : 0, arg));
            }
            while (s.thoughts.size() > MAX_THOUGHTS) {
                s.thoughts.pollFirst();
            }
        }
        s.lastKillDay = tag.contains("LastKillDay") ? tag.getInt("LastKillDay") : -1;
        s.lastTimidScareDay = tag.contains("LastTimidScareDay") ? tag.getInt("LastTimidScareDay") : -1;
        s.lastWeatherAt = tag.contains("LastWeatherAt") ? tag.getLong("LastWeatherAt") : Long.MIN_VALUE;
        s.lastWeather = getEnum(tag, "LastWeather", WeatherPref.class, null);
        s.lastSleptNightK = tag.contains("LastSleptNightK") ? tag.getInt("LastSleptNightK") : Integer.MIN_VALUE;
        s.lastChargedNightK = tag.contains("SleepChargedNightK") ? tag.getInt("SleepChargedNightK") : Integer.MIN_VALUE;
        // 性格轴（v0.14）：新档读回；旧档无此键 → 保持 null，首个事件入口懒生成
        // 时随机重抽（用户拍板：迁移不做语义映射，随机重抽）
        s.axes = tag.contains("Axes") ? normalizeAxes(tag.getIntArray("Axes")) : null;
        return s;
    }

    /** 校验轴数组长度与取值范围（长度不符 = 当 null 处理走重抽）。 */
    private static int[] normalizeAxes(int[] raw) {
        if (raw == null || raw.length != PersonalityAxis.COUNT) {
            return null;
        }
        int[] out = new int[raw.length];
        for (int i = 0; i < raw.length; i++) {
            out[i] = PersonalityAxis.clampAxis(raw[i]);
        }
        return out;
    }

    /** 实例级读回（六域常规读档 / GATTACA 快照复原用）。 */
    public void loadFrom(CompoundTag tag) {
        loadFrom(load(tag, null, false));
    }

    /** 从另一个 Status 实例复制全部字段（老档迁移用）。 */
    public void loadFrom(Status other) {
        this.hunger = other.hunger;
        this.mood = other.mood;
        this.mood = other.mood;
        this.lastSettledDay = other.lastSettledDay;
        this.settleOffset = other.settleOffset;
        this.lastFedDay = other.lastFedDay;
        this.lastTavernDay = other.lastTavernDay;
        this.dailyDelta = other.dailyDelta;
        this.eventCountToday.clear();
        this.eventCountToday.putAll(other.eventCountToday);
        this.lastIntervalAt.clear();
        this.lastIntervalAt.putAll(other.lastIntervalAt);
        this.thoughts.clear();
        this.thoughts.addAll(other.thoughts);
        this.lastKillDay = other.lastKillDay;
        this.lastTimidScareDay = other.lastTimidScareDay;
        this.lastWeatherAt = other.lastWeatherAt;
        this.lastWeather = other.lastWeather;
        this.lastSleptNightK = other.lastSleptNightK;
        this.lastChargedNightK = other.lastChargedNightK;
        this.axes = other.axes == null ? null : other.axes.clone();
    }

    private static <E extends Enum<E>> E getEnum(CompoundTag tag, String key,
            Class<E> type, E fallback) {
        if (!tag.contains(key)) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, tag.getString(key));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
