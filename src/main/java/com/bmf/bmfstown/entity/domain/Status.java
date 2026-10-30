package com.bmf.bmfstown.entity.domain;

import com.bmf.bmfstown.config.Config;
import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.entity.WorkMode;
import com.bmf.bmfstown.entity.profile.PersonalityType;
import com.bmf.bmfstown.entity.profile.MobPref;
import com.bmf.bmfstown.entity.profile.PrefAnimal;
import com.bmf.bmfstown.entity.profile.ProfessionType;
import com.bmf.bmfstown.entity.profile.ResidentInventory;
import com.bmf.bmfstown.entity.profile.SkillTiers;
import com.bmf.bmfstown.entity.profile.WeatherPref;
import com.bmf.bmfstown.town.TownZone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
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
 *   <li>按天计数：吃喜（2 次）、动物/音乐（1 次）、好战击杀（2 次）——每日结算清零；</li>
 *   <li>按 dayTime 间隔：挨饿（半天）、天气（半天且天气变了才触发）、
 *       生物厌恶（5 分钟，无日上限）、胆小（半天）；</li>
 *   <li>每日结算链：职业喜欢、攀比护甲、好战无杀、胆小平安、爱热闹、
 *       乐观/悲观、住宅材质/颜色扫描——一天一次，各走 ±3。</li>
 * </ul>
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

    /** 心情事件类型。每个事件自带：色调（正/负/中性）、语言文件 key 前缀。 */
    public enum EventType {
        /** 吃到了爱吃的食物（即时，挂在 settleBite）。 */
        ATE_FAVORED(TONE_POSITIVE),
        /** 挨饿：找不到也拿不到食物（即时，幅度按连续天数递增）。 */
        STARVING(TONE_NEGATIVE),
        /** 每日结算：今天干的是喜欢的工作。 */
        JOB_LIKED(TONE_POSITIVE),
        /** 每日结算：住宅里有喜欢的建筑材质。 */
        BLOCK_LIKED(TONE_POSITIVE),
        /** 感知：喜欢的动物在身边出没（每天 1 次）。 */
        ANIMAL_LIKED(TONE_POSITIVE),
        /** 每日结算：住宅里有喜欢的颜色方块。 */
        COLOR_LIKED(TONE_POSITIVE),
        /** 感知：附近唱片机在放（每天 1 次）。 */
        MUSIC_LIKED(TONE_POSITIVE),
        /** 感知：遇到讨厌的天气（半天 1 次且天气变了）。 */
        WEATHER_DISLIKED(TONE_NEGATIVE),
        /** 感知：天气变化的中性记录（没有讨厌天气的人也记）。 */
        WEATHER_NEUTRAL(TONE_NEUTRAL),
        /** 感知：讨厌的生物在附近出没（5 分钟 1 次，无日上限）。 */
        MOB_DISLIKED(TONE_NEGATIVE),
        /** 攀比：穿着钻石/下界合金护甲。 */
        ARMOR_HIGH(TONE_POSITIVE),
        /** 攀比：没护甲或只有皮/铁/锁甲。 */
        ARMOR_NONE(TONE_NEGATIVE),
        /** 攀比：金护甲——中性事件，不动心情。 */
        ARMOR_GOLD(TONE_NEUTRAL),
        /** 好战：击杀生物（每天 2 次）。 */
        KILL(TONE_POSITIVE),
        /** 好战：连续多天没击杀。 */
        KILL_NONE(TONE_NEGATIVE),
        /** 胆小：周围有敌对生物。 */
        TIMID_SCARED(TONE_NEGATIVE),
        /** 胆小：一整天平安无事。 */
        TIMID_SAFE(TONE_POSITIVE),
        /** 爱热闹：身边有 2 个以上同伴。 */
        GREGARIOUS_CROWD(TONE_POSITIVE),
        /** 爱热闹：身边一个同伴都没有。 */
        GREGARIOUS_LONELY(TONE_NEGATIVE),
        /** 贪吃：吃到东西了（每天 1 次，不管爱不爱吃）。 */
        GLUTTON_FED(TONE_POSITIVE),
        /** 乐观：每日固定增量。 */
        OPTIMIST_DAILY(TONE_POSITIVE),
        /** 悲观：每日固定减量。 */
        PESSIMIST_DAILY(TONE_NEGATIVE),
        /** 和平主义：附近有生物死亡（含自己杀的）。 */
        PACIFIST_DEATH(TONE_NEGATIVE),
        /** 爆炸主义：附近发生爆炸。 */
        EXPLOSION_LOVED(TONE_POSITIVE),
        /** 没睡好：昨晚整夜没躺过床，每多一夜多扣一次。 */
        NO_SLEEP(TONE_NEGATIVE);

        final int tone;

        EventType(int tone) {
            this.tone = tone;
        }
    }

    /**
     * 一条想法日志。只存翻译 key、发生日、色调、标记——不存数值也不存原文
     * （客户端用 I18n 现译，语言可切换）。
     */
    public record Thought(int day, int tone, String key, int marker) {

        static final int MARK_NONE = 0;
        static final int MARK_PLUS = 1;
        static final int MARK_MINUS = 2;
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

    // ---- 心情结算游标 ----

    /** 各事件今天的已触发次数（限流计数器）。随每日结算清零。 */
    private final Map<EventType, Integer> eventCountToday = new EnumMap<>(EventType.class);

    /** 今天已经累计的心情变动（每日 clamp 配额）。 */
    private int dailyDelta = 0;

    /** 上次结算发生在（私人零点制的）第几天；-1 = 从未结算。 */
    private int lastSettledDay = -1;

    /** 结算相位偏移：出生随机、落 NBT，与 lastSettledDay 成对存取。 */
    private int settleOffset = RandomSource.create().nextInt(24000);

    /** 上次成功进食是第几天——挨饿递增的基准。 */
    private int lastFedDay = -1;

    /** 上次触发挨饿事件的 dayTime 绝对值——挨饿按间隔限流用。 */
    private long lastStarvingAt = Long.MIN_VALUE;

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

    /** 上次生物厌恶事件的 dayTime。 */
    private long lastMobAt = Long.MIN_VALUE;

    /** 上次胆小受惊的 dayTime。 */
    private long lastTimidAt = Long.MIN_VALUE;

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
     * 吃到了爱吃的食物。挂在 Resident.settleBite()。
     * 限流：每天前 MOOD_EAT_FAVORED_DAILY_LIMIT 次有效。
     */
    public void ateFavored(Resident resident, ServerLevel level) {
        ensureGenerated(resident, level);
        EventType type = EventType.ATE_FAVORED;
        int limit = Config.MOOD_EAT_FAVORED_DAILY_LIMIT.get();
        if (this.eventCountToday.getOrDefault(type, 0) >= limit) {
            return;
        }
        this.eventCountToday.put(type, this.eventCountToday.getOrDefault(type, 0) + 1);
        int delta = Config.MOOD_EAT_FAVORED.get();
        this.applyClamped(resident, delta);
        addThought(resident, level, type);
    }

    /**
     * 成功进食了（不管是不是爱吃的）：刷新挨饿递增基准日 + 贪吃性格加分。
     */
    public void noteFed(Resident resident, ServerLevel level) {
        ensureGenerated(resident, level);
        this.lastFedDay = currentDay(level);
        if (resident.getPrefs().personality() == PersonalityType.GLUTTON) {
            EventType type = EventType.GLUTTON_FED;
            if (this.eventCountToday.getOrDefault(type, 0) < 1) {
                this.eventCountToday.put(type, 1);
                this.applyClamped(resident, Config.MOOD_TRAIT_VALUE.get());
                addThought(resident, level, type);
            }
        }
    }

    /**
     * 挨饿：找不到可用的食物来源。间隔限流；幅度按连续挨饿天数递增；
     * 3 天起不受每日 clamp 限制（绝食必须真的难受）。
     */
    public void starving(Resident resident, ServerLevel level) {
        ensureGenerated(resident, level);
        long now = level.getDayTime();
        long interval = Config.MOOD_STARVING_INTERVAL.get();
        if (!elapsed(now, this.lastStarvingAt, interval)) {
            return;
        }
        this.lastStarvingAt = now;

        int today = currentDay(level);
        int daysHungry = this.lastFedDay < 0 ? 1 : Math.max(1, today - this.lastFedDay);
        int base = Config.MOOD_STARVING.get();
        int delta;
        String key;
        if (daysHungry >= 3) {
            delta = base + 5;
            key = "mood.bmfstown.thought.starving_3";
        } else if (daysHungry == 2) {
            delta = base + 2;
            key = "mood.bmfstown.thought.starving_2";
        } else {
            delta = base;
            key = "mood.bmfstown.thought.starving_1";
        }
        if (resident.getPrefs().personality() == PersonalityType.GLUTTON) {
            delta = delta * Config.MOOD_TRAIT_GLUTTON_STARVE_PERCENT.get() / 100;
        }
        // 铭记修正：身上有"挨饿"的永久记忆，旧病复发额外更痛（用户定稿 +10）
        if (resident.getMemoryState().hasFirmMemory(com.bmf.bmfstown.entity.domain.MemoryState.TYPE_STARVING)) {
            delta += Config.MOOD_MEMORY_PENALTY.get();
        }
        this.applyRaw(resident, -delta); // 例外：绕过每日 clamp
        addThought(level, EventType.STARVING.tone, key, Thought.MARK_NONE);
        // 记忆累计：每挨饿事件 +15（事件 = 饱食度归零且不在觅食/进食中，
        // 半日节流）；积满固化 → 想法日志宣布（紫色，不说话——用户定稿）
        if (resident.getMemoryState().addProgress(com.bmf.bmfstown.entity.domain.MemoryState.TYPE_STARVING,
                (int) (level.getDayTime() / 24000L),
                Config.MEMORY_GAIN.get(), Config.MEMORY_THRESHOLD.get())) {
            addThought(level, TONE_MEMORY_BAD,
                    "mood.bmfstown.thought.memory_starving", Thought.MARK_NONE);
        }
        // 台词：挨饿即时线（有没有记忆都喊饿，变体由台词注册表的 condition 区分）
        com.bmf.bmfstown.social.TownEventBus.dispatch(
                com.bmf.bmfstown.social.TownEvent.starving(resident));
    }

    /**
     * 好战性格：击杀生物。每天 2 次；同时刷新 lastKillDay。
     */
    public void noteKill(Resident resident, ServerLevel level) {
        ensureGenerated(resident, level);
        if (resident.getPrefs().personality() != PersonalityType.WARLIKE) {
            return;
        }
        EventType type = EventType.KILL;
        int limit = Config.MOOD_TRAIT_KILL_DAILY_LIMIT.get();
        if (this.eventCountToday.getOrDefault(type, 0) >= limit) {
            // 配额用完也刷新"今天杀过"——否则连杀会意外续上 5 天无杀的计时器
            this.lastKillDay = currentDay(level);
            return;
        }
        this.eventCountToday.put(type, this.eventCountToday.getOrDefault(type, 0) + 1);
        this.lastKillDay = currentDay(level);
        this.applyClamped(resident, Config.MOOD_TRAIT_VALUE.get());
        addThought(resident, level, type);
    }

    /** 和平主义者：附近有生物死亡 → 负面（每天 5 次）。任何死亡都算。 */
    public void noteDeathNearby(Resident resident, ServerLevel level) {
        ensureGenerated(resident, level);
        if (resident.getPrefs().personality() != PersonalityType.PACIFIST) {
            return;
        }
        EventType type = EventType.PACIFIST_DEATH;
        int limit = Config.MOOD_TRAIT_PACIFIST_DAILY_LIMIT.get();
        if (this.eventCountToday.getOrDefault(type, 0) >= limit) {
            return;
        }
        this.eventCountToday.put(type, this.eventCountToday.getOrDefault(type, 0) + 1);
        this.applyClamped(resident, -Config.MOOD_TRAIT_VALUE.get());
        addThought(resident, level, type);
    }

    /** 爆炸主义者：附近发生爆炸 → +3（每天 1 次）。 */
    public void noteExplosionNearby(Resident resident, ServerLevel level) {
        ensureGenerated(resident, level);
        if (resident.getPrefs().personality() != PersonalityType.EXPLOSION_LOVER) {
            return;
        }
        EventType type = EventType.EXPLOSION_LOVED;
        int limit = Config.MOOD_TRAIT_EXPLOSION_DAILY_LIMIT.get();
        if (this.eventCountToday.getOrDefault(type, 0) >= limit) {
            return;
        }
        this.eventCountToday.put(type, this.eventCountToday.getOrDefault(type, 0) + 1);
        this.applyClamped(resident, Config.MOOD_TRAIT_VALUE.get());
        addThought(resident, level, type);
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
        settleEnvy(resident, level);
        settleWarlike(resident, level);
        settleTimid(resident, level);
        settleGregarious(resident, level);
        settleDisposition(resident, level);
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
            this.applyClamped(resident, Config.MOOD_JOB_LIKED.get());
            addThought(resident, level, EventType.JOB_LIKED);
        }
    }

    /** 结算 2：攀比——护甲品质分档。判定走注册名 contains。 */
    private void settleEnvy(Resident resident, ServerLevel level) {
        if (resident.getPrefs().personality() != PersonalityType.ENVY) {
            return;
        }
        boolean high = false;
        boolean gold = false;
        for (EquipmentSlot slot : new EquipmentSlot[]{
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack stack = resident.getItemBySlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            if (path.contains("diamond") || path.contains("netherite")) {
                high = true;
            } else if (path.contains("gold")) {
                gold = true;
            }
        }
        if (high) {
            this.applyClamped(resident, Config.MOOD_TRAIT_VALUE.get());
            addThought(resident, level, EventType.ARMOR_HIGH);
        } else if (gold) {
            addThought(resident, level, EventType.ARMOR_GOLD);
        } else {
            this.applyClamped(resident, -Config.MOOD_TRAIT_VALUE.get());
            addThought(resident, level, EventType.ARMOR_NONE);
        }
    }

    /** 结算 3：好战——连续 N 天没击杀生物 → 负面。天数口径用绝对天。 */
    private void settleWarlike(Resident resident, ServerLevel level) {
        if (resident.getPrefs().personality() != PersonalityType.WARLIKE) {
            return;
        }
        int need = Config.MOOD_TRAIT_WARLIKE_NO_KILL_DAYS.get();
        int since = this.lastKillDay < 0 ? Integer.MAX_VALUE : currentDay(level) - this.lastKillDay;
        if (since >= need) {
            this.applyClamped(resident, -Config.MOOD_TRAIT_VALUE.get());
            addThought(resident, level, EventType.KILL_NONE);
        }
    }

    /** 结算 4：胆小——一整天没被怪物吓过 → 正面。 */
    private void settleTimid(Resident resident, ServerLevel level) {
        if (resident.getPrefs().personality() != PersonalityType.TIMID) {
            return;
        }
        int since = this.lastTimidScareDay < 0
                ? Integer.MAX_VALUE : currentDay(level) - this.lastTimidScareDay;
        if (since >= 1) {
            this.applyClamped(resident, Config.MOOD_TRAIT_VALUE.get());
            addThought(resident, level, EventType.TIMID_SAFE);
        }
    }

    /** 结算 5：爱热闹——身边 2 个以上同伴正面；一个都没有负面。 */
    private void settleGregarious(Resident resident, ServerLevel level) {
        if (resident.getPrefs().personality() != PersonalityType.GREGARIOUS) {
            return;
        }
        AABB box = resident.getBoundingBox().inflate(CROWD_RANGE);
        int others = 0;
        for (Resident r : level.getEntitiesOfClass(Resident.class, box)) {
            if (r != resident && r.isAlive()) {
                others++;
            }
        }
        if (others >= 2) {
            this.applyClamped(resident, Config.MOOD_TRAIT_VALUE.get());
            addThought(resident, level, EventType.GREGARIOUS_CROWD);
        } else if (others == 0) {
            this.applyClamped(resident, -Config.MOOD_TRAIT_VALUE.get());
            addThought(resident, level, EventType.GREGARIOUS_LONELY);
        }
    }

    /** 结算 6：乐观/悲观的每日固定增减。标记在 addThought 里按性格自动打。 */
    private void settleDisposition(Resident resident, ServerLevel level) {
        PersonalityType personality = resident.getPrefs().personality();
        if (personality == PersonalityType.OPTIMIST) {
            this.applyClamped(resident, Config.MOOD_TRAIT_OPTIMIST.get());
            addThought(resident, level, EventType.OPTIMIST_DAILY);
        } else if (personality == PersonalityType.PESSIMIST) {
            this.applyClamped(resident, -Config.MOOD_TRAIT_PESSIMIST.get());
            addThought(resident, level, EventType.PESSIMIST_DAILY);
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
            this.applyClamped(resident, Config.MOOD_BLOCK_LIKED.get());
            addThought(resident, level, EventType.BLOCK_LIKED);
        }
        if (colorHit) {
            this.applyClamped(resident, Config.MOOD_COLOR_LIKED.get());
            addThought(resident, level, EventType.COLOR_LIKED);
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
        this.applyRaw(resident, -missed * noSleep - memoryPenalty);
        addThought(resident, level, EventType.NO_SLEEP);
        // 记忆累计：一夜没睡 +15；积满固化 → 想法日志宣布（紫色，不说话——用户定稿）
        if (resident.getMemoryState().addProgress(com.bmf.bmfstown.entity.domain.MemoryState.TYPE_SLEEPLESS,
                (int) (level.getDayTime() / 24000L),
                Config.MEMORY_GAIN.get(), Config.MEMORY_THRESHOLD.get())) {
            addThought(level, TONE_MEMORY_BAD,
                    "mood.bmfstown.thought.memory_sleepless", Thought.MARK_NONE);
        }
        // 台词：缺觉即时线（变体由台词注册表的 condition 区分）
        com.bmf.bmfstown.social.TownEventBus.dispatch(
                com.bmf.bmfstown.social.TownEvent.sleepless(resident));
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
        if (this.lastStarvingAt == Long.MIN_VALUE
                || level.getDayTime() - this.lastStarvingAt >= 24000L) {
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
    }

    /** 喜欢的动物在 8 格内出没 → 正面（每天 1 次）。 */
    private void senseAnimal(Resident resident, ServerLevel level) {
        PrefAnimal likedAnimal = resident.getPrefs().likedAnimal();
        if (likedAnimal == null) {
            return;
        }
        EventType type = EventType.ANIMAL_LIKED;
        if (this.eventCountToday.getOrDefault(type, 0) >= 1) {
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
            this.eventCountToday.put(type, 1);
            this.applyClamped(resident, Config.MOOD_ANIMAL_LIKED.get());
            addThought(resident, level, type);
        }
    }

    /** 喜欢音乐且 8 格内有唱片机在放 → 正面（每天 1 次）。 */
    private void senseJukebox(Resident resident, ServerLevel level) {
        if (!resident.getPrefs().likesMusic()) {
            return;
        }
        EventType type = EventType.MUSIC_LIKED;
        if (this.eventCountToday.getOrDefault(type, 0) >= 1) {
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
            this.eventCountToday.put(type, 1);
            this.applyClamped(resident, Config.MOOD_MUSIC_LIKED.get());
            addThought(resident, level, type);
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
        long now = level.getDayTime();

        if (match && elapsed(now, this.lastMobAt, Config.MOOD_MOB_DISLIKED_INTERVAL.get())) {
            this.lastMobAt = now;
            this.applyClamped(resident, -Config.MOOD_MOB_DISLIKED.get());
            addThought(resident, level, EventType.MOB_DISLIKED);
        }

        if (resident.getPrefs().personality() == PersonalityType.TIMID
                && elapsed(now, this.lastTimidAt, Config.MOOD_TRAIT_TIMID_INTERVAL.get())) {
            this.lastTimidAt = now;
            this.lastTimidScareDay = currentDay(level);
            this.applyClamped(resident, -Config.MOOD_TRAIT_VALUE.get());
            addThought(resident, level, EventType.TIMID_SCARED);
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
            this.applyClamped(resident, -Config.MOOD_WEATHER_DISLIKED.get());
            addThought(resident, level, EventType.WEATHER_DISLIKED);
        } else {
            addThought(resident, level, EventType.WEATHER_NEUTRAL);
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
        if (resident.getPrefs().generated()) {
            return;
        }
        resident.getPrefs().ensureGenerated(resident.getRandom());
        // 性格事件游标以"偏好生成那天"为初始值
        int today = currentDay(level);
        this.lastKillDay = today;
        this.lastTimidScareDay = today;
    }

    /**
     * 重摇偏好与性格（调试命令 /bmfstown reroll 用）。
     * lastKillDay/lastTimidScareDay 重置到今天，语义是"今天刚出生"。
     */
    public void reroll(Resident resident, ServerLevel level) {
        resident.getPrefs().reroll(resident.getRandom());
        ensureGenerated(resident, level);
    }

    // ==========================================================
    //  心情施加（clamp 逻辑集中在这两个方法里）
    // ==========================================================

    /** 带每日 clamp 的施加：常规事件与慢变量共用每天 ±MOOD_DAILY_CLAMP 的配额。 */
    private void applyClamped(Resident resident, int delta) {
        int clamp = Config.MOOD_DAILY_CLAMP.get();
        if (clamp <= 0) {
            applyRaw(resident, delta);
            return;
        }
        int room = delta >= 0 ? clamp - this.dailyDelta : -clamp - this.dailyDelta;
        int actual = delta >= 0 ? Math.min(delta, Math.max(0, room))
                : Math.max(delta, Math.min(0, room));
        if (actual == 0) {
            return;
        }
        this.dailyDelta += actual;
        applyRaw(resident, actual);
    }

    /** 不带 clamp 的直接施加（挨饿递增例外用）。 */
    private void applyRaw(Resident resident, int delta) {
        int oldMood = this.mood;
        int newMood = Math.max(0, Math.min(MAX_MOOD, oldMood + delta));
        if (newMood == oldMood) {
            return;
        }
        this.mood = newMood;
        resident.syncClient();
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

    /**
     * 记一条想法。30 条封顶，队满挤掉最老的。
     * 标记规则：乐观的正面想法打 "+"，悲观的负面想法打 "−"（性格滤镜提示）。
     */
    private void addThought(Resident resident, ServerLevel level, EventType type) {
        int marker = Thought.MARK_NONE;
        PersonalityType personality = resident.getPrefs().personality();
        if (personality == PersonalityType.OPTIMIST && type.tone == TONE_POSITIVE) {
            marker = Thought.MARK_PLUS;
        } else if (personality == PersonalityType.PESSIMIST && type.tone == TONE_NEGATIVE) {
            marker = Thought.MARK_MINUS;
        }
        addThought(level, type.tone, "mood.bmfstown.thought." + keyOf(type), marker);
    }

    /** EventType → 语言 key 后缀（显式映射，防止 enum 改名静默丢文案）。 */
    private static String keyOf(EventType type) {
        return switch (type) {
            case ATE_FAVORED -> "ate_favored";
            case STARVING -> "starving";
            case JOB_LIKED -> "job_liked";
            case BLOCK_LIKED -> "block_liked";
            case ANIMAL_LIKED -> "animal_liked";
            case COLOR_LIKED -> "color_liked";
            case MUSIC_LIKED -> "music_liked";
            case WEATHER_DISLIKED -> "weather_disliked";
            case WEATHER_NEUTRAL -> "weather_neutral";
            case MOB_DISLIKED -> "mob_disliked";
            case ARMOR_HIGH -> "armor_high";
            case ARMOR_NONE -> "armor_none";
            case ARMOR_GOLD -> "armor_gold";
            case KILL -> "kill";
            case KILL_NONE -> "kill_none";
            case TIMID_SCARED -> "timid_scared";
            case TIMID_SAFE -> "timid_safe";
            case GREGARIOUS_CROWD -> "gregarious_crowd";
            case GREGARIOUS_LONELY -> "gregarious_lonely";
            case GLUTTON_FED -> "glutton_fed";
            case OPTIMIST_DAILY -> "optimist_daily";
            case PESSIMIST_DAILY -> "pessimist_daily";
            case PACIFIST_DEATH -> "pacifist_death";
            case EXPLOSION_LOVED -> "explosion_loved";
            case NO_SLEEP -> "no_sleep";
        };
    }

    private void addThought(ServerLevel level, int tone, String key, int marker) {
        this.thoughts.addLast(new Thought(currentDay(level), tone, key, marker));
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
        tag.putLong("LastStarvingAt", this.lastStarvingAt);
        tag.putInt("DailyDelta", this.dailyDelta);
        // 限流计数也要落档：不落的话读档即重置，"存读档刷心情"就成立了
        CompoundTag counterTag = new CompoundTag();
        for (Map.Entry<EventType, Integer> e : this.eventCountToday.entrySet()) {
            counterTag.putInt(e.getKey().name(), e.getValue());
        }
        if (!counterTag.isEmpty()) {
            tag.put("EventCounters", counterTag);
        }
        if (!this.thoughts.isEmpty()) {
            ListTag list = new ListTag();
            for (Thought t : this.thoughts) {
                CompoundTag tTag = new CompoundTag();
                tTag.putInt("Day", t.day());
                tTag.putInt("Tone", t.tone());
                tTag.putInt("Marker", t.marker());
                tTag.putString("Key", t.key());
                list.add(tTag);
            }
            tag.put("Thoughts", list);
        }
        tag.putInt("LastKillDay", this.lastKillDay);
        tag.putInt("LastTimidScareDay", this.lastTimidScareDay);
        tag.putLong("LastWeatherAt", this.lastWeatherAt);
        if (this.lastWeather != null) {
            tag.putString("LastWeather", this.lastWeather.name());
        }
        tag.putLong("LastMobAt", this.lastMobAt);
        tag.putLong("LastTimidAt", this.lastTimidAt);
        tag.putInt("LastSleptNightK", this.lastSleptNightK);
        tag.putInt("SleepChargedNightK", this.lastChargedNightK);
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
        s.lastStarvingAt = tag.contains("LastStarvingAt") ? tag.getLong("LastStarvingAt") : Long.MIN_VALUE;
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
        s.thoughts.clear();
        if (tag.contains("Thoughts")) {
            ListTag list = tag.getList("Thoughts", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag tTag = list.getCompound(i);
                int tone = tTag.contains("Tone")
                        ? tTag.getInt("Tone")
                        : (tTag.getBoolean("Pos") ? TONE_POSITIVE : TONE_NEGATIVE);
                s.thoughts.addLast(new Thought(tTag.getInt("Day"), tone,
                        tTag.getString("Key"), tTag.contains("Marker") ? tTag.getInt("Marker") : 0));
            }
            while (s.thoughts.size() > MAX_THOUGHTS) {
                s.thoughts.pollFirst();
            }
        }
        s.lastKillDay = tag.contains("LastKillDay") ? tag.getInt("LastKillDay") : -1;
        s.lastTimidScareDay = tag.contains("LastTimidScareDay") ? tag.getInt("LastTimidScareDay") : -1;
        s.lastWeatherAt = tag.contains("LastWeatherAt") ? tag.getLong("LastWeatherAt") : Long.MIN_VALUE;
        s.lastWeather = getEnum(tag, "LastWeather", WeatherPref.class, null);
        s.lastMobAt = tag.contains("LastMobAt") ? tag.getLong("LastMobAt") : Long.MIN_VALUE;
        s.lastTimidAt = tag.contains("LastTimidAt") ? tag.getLong("LastTimidAt") : Long.MIN_VALUE;
        s.lastSleptNightK = tag.contains("LastSleptNightK") ? tag.getInt("LastSleptNightK") : Integer.MIN_VALUE;
        s.lastChargedNightK = tag.contains("SleepChargedNightK") ? tag.getInt("SleepChargedNightK") : Integer.MIN_VALUE;
        return s;
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
        this.lastStarvingAt = other.lastStarvingAt;
        this.dailyDelta = other.dailyDelta;
        this.eventCountToday.clear();
        this.eventCountToday.putAll(other.eventCountToday);
        this.thoughts.clear();
        this.thoughts.addAll(other.thoughts);
        this.lastKillDay = other.lastKillDay;
        this.lastTimidScareDay = other.lastTimidScareDay;
        this.lastWeatherAt = other.lastWeatherAt;
        this.lastWeather = other.lastWeather;
        this.lastMobAt = other.lastMobAt;
        this.lastTimidAt = other.lastTimidAt;
        this.lastSleptNightK = other.lastSleptNightK;
        this.lastChargedNightK = other.lastChargedNightK;
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
