package com.bmf.bmfstown.entity;

import com.bmf.bmfstown.config.Config;
import com.bmf.bmfstown.entity.profile.BlockMaterial;
import com.bmf.bmfstown.entity.profile.MobPref;
import com.bmf.bmfstown.entity.profile.PersonalityType;
import com.bmf.bmfstown.entity.profile.PrefAnimal;
import com.bmf.bmfstown.entity.profile.PrefColor;
import com.bmf.bmfstown.entity.profile.ProfessionType;
import com.bmf.bmfstown.entity.profile.ResidentProfile;
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
import java.util.ArrayList;

/**
 * 镇民的心情状态机（v0.8 心情系统核心：偏好 + 性格）。
 *
 * <h2>职责边界</h2>
 * <ul>
 *   <li><b>本类管</b>：心情值的增减、事件限流、每日结算、想法日志、挨饿递增、
 *       偏好与性格的懒生成/存档；</li>
 *   <li><b>本类不管</b>：心情的<b>输出</b>（效率乘数、罢工判断由 Resident 读
 *       {@link #moodOf} 自行决定）、想法的<b>显示</b>（客户端面板经网络包拿数据）。</li>
 * </ul>
 *
 * <h2>v0.8 偏好体系（用户定稿）</h2>
 * <ul>
 *   <li>必出：喜欢的工作（{@code ResidentProfile.likedProfession}）、口味
 *       （{@code hobbyFood}）、性格（{@link PersonalityType}，9 选 1 无"普通人"）；</li>
 *   <li>各 1/2 概率：建筑方块（最多 2 种）、动物、颜色、音乐；</li>
 *   <li>天气厌恶 1/3（晴 1 / 雨 15 / 雷暴 5，权重和 21）；</li>
 *   <li>敌对生物厌恶 1/4（14 种等权）；</li>
 *   <li>职业厌恶已删除——讨厌只留天气与生物两种。</li>
 * </ul>
 * 偏好不放进 {@code ResidentProfile} record（那要改十多个构造点），
 * 全部存在本类、挂 Resident 的 "MoodState" NBT，客户端经
 * {@code MoodSyncPayload.prefs} 单字符串同步。
 *
 * <h2>性格（v0.8）</h2>
 * 只走心情管线、不改效率公式。数值统一 ±3（挨饿 5/8 例外保留）；
 * 乐观/悲观每日 ±2，且分别在正面/负面想法文案尾部打 "+" / "−" 标记
 * （{@link Thought#marker}，客户端渲染）。
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
public final class MoodState {

    /** 想法日志容量：最多保留最近 30 条，新的把最老的挤掉（用户定稿）。 */
    private static final int MAX_THOUGHTS = 30;

    /** 感知扫描节拍（tick）：动物/唱片机/怪物/天气 5 秒一轮。 */
    private static final int SENSE_INTERVAL = 100;

    /** 动物触发距离（格）。 */
    private static final double ANIMAL_RANGE = 8.0D;

    /** 唱片机扫描距离（格）。int：直接喂给 {@link BlockPos#offset}，省一次窄化转换。 */
    private static final int JUKEBOX_RANGE = 8;

    /** 敌对生物扫描距离（格）：生物厌恶与胆小共用。 */
    private static final double HOSTILE_RANGE = 16.0D;

    /** 爱热闹：身边镇民密度统计半径（格）。 */
    private static final double CROWD_RANGE = 16.0D;

    /**
     * 想法色调（客户端三色渲染用）：中性灰 / 正面绿 / 负面红。
     *
     * <p>必须声明在 {@link EventType} <b>之前</b>——枚举常量的构造参数引用它们是
     * "前向引用"，放在枚举体内会直接编译失败（v0.8 交付后本地编译踩坑）。
     * 且只在本类内部使用，不随网络包走（网络包里 Thought 自带 tone 字段）。
     */
    public static final int TONE_NEUTRAL = 0;
    /** 色调：正面（绿系）。 */
    public static final int TONE_POSITIVE = 1;
    /** 色调：负面（红系）。 */
    public static final int TONE_NEGATIVE = 2;

    /** 心情事件类型。每个事件自带：色调（正/负/中性）、语言文件 key 前缀。 */
    public enum EventType {
        /** 吃到了爱吃的食物（即时，挂在 {@code settleBite()}）。 */
        ATE_FAVORED(TONE_POSITIVE),
        /** 挨饿：找不到也拿不到食物（即时，挂在觅食失败分支；幅度按连续天数递增）。 */
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
        /** 和平主义（v0.8.1）：附近有生物死亡（含自己杀的）。 */
        PACIFIST_DEATH(TONE_NEGATIVE),
        /** 爆炸主义（v0.8.1）：附近发生爆炸。 */
        EXPLOSION_LOVED(TONE_POSITIVE),
        /** 没睡好（v0.8.1）：昨晚整夜没躺过床，每多一夜多扣一次。 */
        NO_SLEEP(TONE_NEGATIVE);

        final int tone;

        EventType(int tone) {
            this.tone = tone;
        }
    }

    /**
     * 一条想法日志。只存<b>翻译 key、发生日、色调、标记</b>——
     * 不存数值（用户定稿：为沉浸感，面板不显示心情增减的具体数字，
     * 程度靠措辞表达）；也不存原文，客户端用 I18n 现译，语言可切换。
     *
     * @param day    发生时的一天序号（{@code dayTime / 24000}）
     * @param tone   {@link EventType#TONE_NEUTRAL} / {@link EventType#TONE_POSITIVE} /
     *               {@link EventType#TONE_NEGATIVE}
     * @param key    语言文件 key（如 {@code mood.bmfstown.thought.ate_favored}）
     * @param marker 0 = 无；1 = 乐观正面（文案尾部打 "+"）；2 = 悲观负面（打 "−"）
     */
    public record Thought(int day, int tone, String key, int marker) {

        static final int MARK_NONE = 0;
        static final int MARK_PLUS = 1;
        static final int MARK_MINUS = 2;
    }

    // ---- 心情本体在 ResidentProfile.mood（走 DATA_PROFILE 同步），这里只存"周边状态" ----

    /** 各事件今天的已触发次数（限流计数器）。随每日结算清零。 */
    private final Map<EventType, Integer> eventCountToday = new EnumMap<>(EventType.class);

    /** 今天已经累计的心情变动（所有事件 + 慢变量共用一个配额），用于每日 clamp。 */
    private int dailyDelta = 0;

    /** 上次结算发生在（私人零点制的）第几天；-1 = 从未结算。 */
    private int lastSettledDay = -1;

    /** 结算相位偏移：出生随机、落 NBT，与 lastSettledDay 成对存取。 */
    private int settleOffset = RandomSource.create().nextInt(24000);

    /** 上次成功进食是第几天——挨饿递增的基准。 */
    private int lastFedDay = -1;

    /** 上次触发挨饿事件的 dayTime 绝对值——挨饿按间隔限流用。 */
    private long lastStarvingAt = Long.MIN_VALUE;

    /** 想法日志，队尾最新。 */
    private final ArrayDeque<Thought> thoughts = new ArrayDeque<>();

    // ---- v0.8 偏好（懒生成 + 落 NBT；不同步进 ResidentProfile，避免动 record 构造点） ----

    /** 偏好是否已生成。false = 下次 ensureGenerated 时抽取并落档。 */
    private boolean prefsGenerated = false;

    /** 轻浮：漂浮检定计时器。 */
    private int flightyTimer;

    /** 喜欢的建筑材质（0~2 种，各 1/2 概率刷出，去重）。 */
    private List<BlockMaterial> likedBlocks = List.of();

    /** 喜欢的动物（1/2 概率）。 */
    private PrefAnimal likedAnimal = null;

    /** 喜欢的颜色（1/2 概率）。 */
    private PrefColor likedColor = null;

    /** 喜欢音乐（1/2 概率）：附近唱片机在放时开心。 */
    private boolean likesMusic = false;

    /** 讨厌的天气（1/3 概率，权重 晴1/雨15/雷暴5）。 */
    private WeatherPref dislikedWeather = null;

    /** 讨厌的敌对生物（1/4 概率）。 */
    private MobPref dislikedMob = null;

    /** 性格标签（必出 1 条，9 选 1）。 */
    private PersonalityType personality = PersonalityType.OPTIMIST;

    /** 特性（v0.8.1 第三类人格词条；null = 老档未抽，首 tick 补抽）。 */
    private com.bmf.bmfstown.entity.profile.TraitType trait;

    // ---- v0.8 性格附加状态 ----

    /** 好战：上次击杀是第几天（初始 = 偏好生成那天）。 */
    private int lastKillDay = -1;

    /** 胆小：上次被怪物吓到是第几天（初始 = 偏好生成那天）。 */
    private int lastTimidScareDay = -1;

    /** 感知节拍累加器（不落档：读档后最多晚 100 tick 扫描，无副作用）。 */
    private int senseTimer = 0;

    /** 上次天气事件触发的 dayTime。 */
    private long lastWeatherAt = Long.MIN_VALUE;

    /** 上次天气事件记录的天气（变了才允许再触发，防连续触发）。 */
    private WeatherPref lastWeather = null;

    /** 上次生物厌恶事件的 dayTime。 */
    private long lastMobAt = Long.MIN_VALUE;

    /** 上次胆小受惊的 dayTime。 */
    private long lastTimidAt = Long.MIN_VALUE;

    /**
     * 睡眠结算：最后一次睡觉所处的夜序号（{@link #nightIndex} 口径，
     * 躺下时由 {@code Resident.lieDown} 登记）。
     */
    private int lastSleptNightK = Integer.MIN_VALUE;

    /**
     * 睡眠结算：已惩罚到的夜序号（含）。之后结束的夜在下次结算时处理——
     * 保证每个"没睡的夜"只罚一次（v0.8.1：连续没睡逐夜叠加、不封顶）。
     */
    private int lastChargedNightK = Integer.MIN_VALUE;

    // ---- 工人工坊绑定（v0.8.1）：合成台/原料箱/产物箱三个方块坐标 ----
    // 0 = 未绑定（BlockPos.asLong 对合法方块不会是 0）

    /** 工人绑定的合成台（干活站位）。 */
    private long craftTablePos = 0L;
    /** 工人绑定的原料箱（取料）。 */
    private long inputChestPos = 0L;
    /** 工人绑定的产物箱（入库）。 */
    private long outputChestPos = 0L;
    /** 工人当前订单：产物注册名（"minecraft:repeater"）；空 = 未设订单。 */
    private String orderItem = "";
    /** 工人开工开关（用户定稿：绑定好了也可以手动停工）。 */
    private boolean workerEnabled = true;

    /** 工人绑定的合成台（{@code BlockPos.asLong}；0 = 未绑定）。 */
    public long craftTablePos() {
        return this.craftTablePos;
    }

    /** 工人绑定的原料箱（0 = 未绑定）。 */
    public long inputChestPos() {
        return this.inputChestPos;
    }

    /** 工人绑定的产物箱（0 = 未绑定）。 */
    public long outputChestPos() {
        return this.outputChestPos;
    }

    /** 当前订单（产物注册名；空 = 未设订单）。 */
    public String orderItem() {
        return this.orderItem;
    }

    /** 一键落账工坊三件套（绑定流程完成时服务端调用）。 */
    public void setWorkshop(long table, long input, long output) {
        this.craftTablePos = table;
        this.inputChestPos = input;
        this.outputChestPos = output;
    }

    /** 设置当前订单（产物注册名；服务端已复核白名单与线别）。 */
    public void setOrderItem(String product) {
        this.orderItem = product == null ? "" : product;
    }

    /** 工人开工开关（false = 已停工，Goal 不激活）。 */
    public boolean workerEnabled() {
        return this.workerEnabled;
    }

    public void setWorkerEnabled(boolean enabled) {
        this.workerEnabled = enabled;
    }

    // ==========================================================
    //  读接口
    // ==========================================================

    /** 心情值（0~100）。本体存在档案里，这里只是转发。 */
    public int moodOf(Resident resident) {
        return resident.getProfile().mood();
    }

    /** 想法日志快照（旧的在前，新的在后）。客户端面板用。 */
    public ArrayDeque<Thought> thoughts() {
        return this.thoughts;
    }

    // ---- 偏好读接口（UI 同步用；调用前应先 ensureGenerated） ----

    public List<BlockMaterial> likedBlocks() {
        return this.likedBlocks;
    }

    public PrefAnimal likedAnimal() {
        return this.likedAnimal;
    }

    public PrefColor likedColor() {
        return this.likedColor;
    }

    public boolean likesMusic() {
        return this.likesMusic;
    }

    public WeatherPref dislikedWeather() {
        return this.dislikedWeather;
    }

    public MobPref dislikedMob() {
        return this.dislikedMob;
    }

    public PersonalityType personality() {
        return this.personality;
    }

    /**
     * 偏好的紧凑网络编码（MoodSyncPayload 用，1.21.1 没有 list codec 重载）。
     *
     * <p>格式：{@code blocks|animal|color|music|weather|mob|personality}，
     * blocks 段内用逗号分隔多种材质；空段 = 没有这项偏好。
     */
    public String prefsString() {
        StringBuilder blocks = new StringBuilder();
        for (BlockMaterial m : this.likedBlocks) {
            if (blocks.length() > 0) {
                blocks.append(',');
            }
            blocks.append(m.name());
        }
        return String.join("|",
                blocks.toString(),
                this.likedAnimal != null ? this.likedAnimal.name() : "",
                this.likedColor != null ? this.likedColor.name() : "",
                this.likesMusic ? "1" : "0",
                this.dislikedWeather != null ? this.dislikedWeather.name() : "",
                this.dislikedMob != null ? this.dislikedMob.name() : "",
                this.personality.name());
    }

    // ==========================================================
    //  即时事件入口（Resident 的写入口调用）
    // ==========================================================

    /**
     * 吃到了爱吃的食物。挂在 {@code Resident.settleBite()}。
     *
     * <p>限流：每天前 {@link Config#MOOD_EAT_FAVORED_DAILY_LIMIT} 次有效，
     * 之后静默跳过——不加分、不加想法（用户定稿：不合并文案，直接不计）。
     */
    public void ateFavored(Resident resident, ServerLevel level) {
        ensureGenerated(resident, level);
        EventType type = EventType.ATE_FAVORED;
        int limit = Config.MOOD_EAT_FAVORED_DAILY_LIMIT.get();
        if (this.eventCountToday.getOrDefault(type, 0) >= limit) {
            return; // 今天吃够本了，饱了，不记了
        }
        this.eventCountToday.put(type, this.eventCountToday.getOrDefault(type, 0) + 1);
        int delta = Config.MOOD_EAT_FAVORED.get();
        this.applyClamped(resident, delta);
        addThought(level, type);
    }

    /**
     * 成功进食了（不管是不是爱吃的）。
     *
     * <p>两个作用：① 刷新挨饿递增的基准日——吃任何食物都算"今天有饭吃"，
     * 不能只在吃到爱吃的时刷新，否则顿顿吃面包也会被误判成挨饿；
     * ② 贪吃性格：任何进食 +3（每天 1 次）。
     */
    public void noteFed(Resident resident, ServerLevel level) {
        ensureGenerated(resident, level);
        this.lastFedDay = currentDay(level);
        if (this.personality == PersonalityType.GLUTTON) {
            EventType type = EventType.GLUTTON_FED;
            if (this.eventCountToday.getOrDefault(type, 0) < 1) {
                this.eventCountToday.put(type, 1);
                this.applyClamped(resident, Config.MOOD_TRAIT_VALUE.get());
                addThought(level, type);
            }
        }
    }

    /**
     * 挨饿：找不到可用的食物来源。挂在觅食失败分支。
     *
     * <p>限流：按 dayTime 间隔（{@link Config#MOOD_STARVING_INTERVAL}，默认半天）
     * ——觅食失败会反复发生，不挡住的话想法列表会被刷屏。
     * 幅度按连续挨饿天数递增（用户定稿）：
     * 1 天 = 基准；2 天 = 基准 + 2；3 天起 = 基准 + 5，且<b>不受每日 clamp 限制</b>
     * ——挨饿是唯一例外，否则绝食三天都掉不了多少心情，玩家感知不到严重性。
     * 贪吃性格额外乘 {@link Config#MOOD_TRAIT_GLUTTON_STARVE_PERCENT}（默认 ×1.5）。
     */
    public void starving(Resident resident, ServerLevel level) {
        ensureGenerated(resident, level);
        long now = level.getDayTime();
        long interval = Config.MOOD_STARVING_INTERVAL.get();
        if (!elapsed(now, this.lastStarvingAt, interval)) {
            return; // 半天内已经难过过了，不重复记
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
        if (this.personality == PersonalityType.GLUTTON) {
            delta = delta * Config.MOOD_TRAIT_GLUTTON_STARVE_PERCENT.get() / 100;
        }
        this.applyRaw(resident, -delta); // 例外：绕过每日 clamp
        addThought(level, EventType.STARVING.tone, key, Thought.MARK_NONE);
    }

    /**
     * 好战性格：击杀生物。挂在 {@code Resident.doHurtTarget()} 击杀判定之后。
     *
     * <p>限流：每天前 {@link Config#MOOD_TRAIT_KILL_DAILY_LIMIT} 次有效；
     * 同时把 {@link #lastKillDay} 刷到今天——每日结算的"连续 5 天没杀"
     * 负面判定以它为准。
     */
    public void noteKill(Resident resident, ServerLevel level) {
        ensureGenerated(resident, level);
        if (this.personality != PersonalityType.WARLIKE) {
            return; // 不好战，杀什么都不动心
        }
        EventType type = EventType.KILL;
        int limit = Config.MOOD_TRAIT_KILL_DAILY_LIMIT.get();
        if (this.eventCountToday.getOrDefault(type, 0) >= limit) {
            // 击杀配额用完了也刷新"今天杀过"——否则连着杀 3 只，
            // 第 3 只虽然不加心情，却把 5 天无杀的计时器续上了，体感不一致
            this.lastKillDay = currentDay(level);
            return;
        }
        this.eventCountToday.put(type, this.eventCountToday.getOrDefault(type, 0) + 1);
        this.lastKillDay = currentDay(level);
        this.applyClamped(resident, Config.MOOD_TRAIT_VALUE.get());
        addThought(level, type);
    }

    /**
     * 和平主义者（v0.8.1）：附近有生物死亡 → −3（每天 5 次）。
     *
     * <p>挂在 {@code PersonalityEventHandler} 的 {@code LivingDeathEvent} 上。
     * <b>任何</b>死亡都算——被怪杀的、被玩家杀的、甚至自己作为战士击杀的，
     * 用户定稿："自己杀人也是不行的，就看不了死人"。
     * 由事件处理器遍历死亡点周围的镇民逐个调用。
     */
    public void noteDeathNearby(Resident resident, ServerLevel level) {
        ensureGenerated(resident, level);
        if (this.personality != PersonalityType.PACIFIST) {
            return;
        }
        EventType type = EventType.PACIFIST_DEATH;
        int limit = Config.MOOD_TRAIT_PACIFIST_DAILY_LIMIT.get();
        if (this.eventCountToday.getOrDefault(type, 0) >= limit) {
            return;
        }
        this.eventCountToday.put(type, this.eventCountToday.getOrDefault(type, 0) + 1);
        this.applyClamped(resident, -Config.MOOD_TRAIT_VALUE.get());
        addThought(level, type);
    }

    /**
     * 爆炸主义者（v0.8.1）：附近发生爆炸 → +3（每天 1 次）。
     *
     * <p>挂在 {@code PersonalityEventHandler} 的 {@code ExplosionEvent.Detonate} 上。
     * 苦力怕自爆、TNT、床/重生锚在下半区爆炸——凡是爆炸都算。
     * 由事件处理器遍历爆炸点周围的镇民逐个调用。
     */
    public void noteExplosionNearby(Resident resident, ServerLevel level) {
        ensureGenerated(resident, level);
        if (this.personality != PersonalityType.EXPLOSION_LOVER) {
            return;
        }
        EventType type = EventType.EXPLOSION_LOVED;
        int limit = Config.MOOD_TRAIT_EXPLOSION_DAILY_LIMIT.get();
        if (this.eventCountToday.getOrDefault(type, 0) >= limit) {
            return;
        }
        this.eventCountToday.put(type, this.eventCountToday.getOrDefault(type, 0) + 1);
        this.applyClamped(resident, Config.MOOD_TRAIT_VALUE.get());
        addThought(level, type);
    }

    // ==========================================================
    //  每日结算（Resident.customServerAiStep 每 tick 调，内部自挡）
    // ==========================================================

    /**
     * 每 tick 的换天检查 + 一天一次的结算。
     *
     * <p><b>每 tick 的开销只有一次除法加一次比较</b>——绝大多数 tick
     * 在第一个 if 就返回，真正的结算一天只发生一次，且因
     * {@link #settleOffset} 的存在被摊开在不同 tick（负载均衡）。
     */
    public void tickDaily(Resident resident, ServerLevel level) {
        int today = (int) ((level.getDayTime() + this.settleOffset) / 24000L);
        if (today == this.lastSettledDay) {
            return; // 今天已经结算过了——99.99% 的 tick 走到这就结束
        }
        this.lastSettledDay = today;
        this.eventCountToday.clear(); // 限流闸门重置（这就是"按天限流"的零点）
        ensureGenerated(resident, level);

        settleProfession(resident, level);
        settleEnvy(resident, level);
        settleWarlike(resident, level);
        settleTimid(resident, level);
        settleGregarious(resident, level);
        settleDisposition(resident, level);
        settleHomeMaterials(resident, level);
        settleSleep(resident, level);
    }

    /**
     * 结算 1：职业喜欢（v0.8 起只剩"喜欢"，职业厌恶已删除）。
     *
     * <p>判定基准是<b>指派</b>而不是专长（用户定稿）：被派去干活就是干那个职业的活。
     * 当前职业体系里唯一的真实职业是战士，两种 WorkMode（跟随/岗哨）都算"干战士的活"，
     * {@code NONE} = 无业（不结算）。以后新增职业时，这里要扩展
     * "指派模式 → 职业"的映射。
     */
    private void settleProfession(Resident resident, ServerLevel level) {
        // 数据驱动（v0.8.1 重构）：按"当前职业"结算，不走指派模式查表——
        // 旧实现硬编码只有战士（ProfessionAssignment 枚举），
        // 喜欢工人/簿记员的镇民被指派后永远拿不到加成（用户点名纠正）。
        // 以后新增职业（农夫/厨师…）自动参与，此处零改动。
        var profile = resident.getProfile();
        ProfessionType current = resident.getProfession();
        if (current == null || current == ProfessionType.NONE) {
            return; // 无业可评（用户定稿：无业不算一种工作）
        }
        if (current == profile.likedProfession()) {
            this.applyClamped(resident, Config.MOOD_JOB_LIKED.get());
            addThought(level, EventType.JOB_LIKED);
        }
    }

    /**
     * 结算 2：攀比——护甲品质分档（用户定稿）。
     *
     * <p>钻/下界合金 → +3；无甲或皮/铁/锁甲 → −3；金 → 中性事件不动心情。
     * 判定走注册名 contains，不认 mod 自定义材质的"品质"——自定义护甲
     * 一律落进低档（保守：不会因为不认识某种甲就白给 +3）。
     */
    private void settleEnvy(Resident resident, ServerLevel level) {
        if (this.personality != PersonalityType.ENVY) {
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
            addThought(level, EventType.ARMOR_HIGH);
        } else if (gold) {
            addThought(level, EventType.ARMOR_GOLD);
        } else {
            this.applyClamped(resident, -Config.MOOD_TRAIT_VALUE.get());
            addThought(level, EventType.ARMOR_NONE);
        }
    }

    /**
     * 结算 3：好战——连续 {@link Config#MOOD_TRAIT_WARLIKE_NO_KILL_DAYS} 天
     * 没击杀生物 → −3。击杀侧走 {@link #noteKill}（每天 2 次）。
     *
     * <p>天数口径用 {@code currentDay}（绝对天）而不是结算相位天——
     * lastKillDay 由 noteKill/ensureGenerated 用绝对天写入，两边必须同口径，
     * 否则相位差会让"5 天无杀"变成 4 或 6 天。
     */
    private void settleWarlike(Resident resident, ServerLevel level) {
        if (this.personality != PersonalityType.WARLIKE) {
            return;
        }
        int need = Config.MOOD_TRAIT_WARLIKE_NO_KILL_DAYS.get();
        // lastKillDay < 0 只可能是"没跑过 ensureGenerated 的老档"，
        // 此时按"很久没杀"处理，让负面正常触发而不是永远豁免
        int since = this.lastKillDay < 0 ? Integer.MAX_VALUE : currentDay(level) - this.lastKillDay;
        if (since >= need) {
            this.applyClamped(resident, -Config.MOOD_TRAIT_VALUE.get());
            addThought(level, EventType.KILL_NONE);
        }
    }

    /**
     * 结算 4：胆小——一整天没被怪物吓过 → +3。受惊侧走
     * {@code tickSenses} 的怪物扫描（间隔半天）。
     */
    private void settleTimid(Resident resident, ServerLevel level) {
        if (this.personality != PersonalityType.TIMID) {
            return;
        }
        int since = this.lastTimidScareDay < 0
                ? Integer.MAX_VALUE : currentDay(level) - this.lastTimidScareDay;
        if (since >= 1) {
            this.applyClamped(resident, Config.MOOD_TRAIT_VALUE.get());
            addThought(level, EventType.TIMID_SAFE);
        }
    }

    /**
     * 结算 5：爱热闹——身边 2 个以上同伴 → +3；一个都没有 → −3。
     * 1 个同伴算"刚好"，不动（用户定稿的三档语义取中间档）。
     */
    private void settleGregarious(Resident resident, ServerLevel level) {
        if (this.personality != PersonalityType.GREGARIOUS) {
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
            addThought(level, EventType.GREGARIOUS_CROWD);
        } else if (others == 0) {
            this.applyClamped(resident, -Config.MOOD_TRAIT_VALUE.get());
            addThought(level, EventType.GREGARIOUS_LONELY);
        }
    }

    /**
     * 结算 6：乐观/悲观的每日固定增减（±2）。
     * 标记（+ / −）在 {@link #addThought} 里按性格自动打。
     */
    private void settleDisposition(Resident resident, ServerLevel level) {
        if (this.personality == PersonalityType.OPTIMIST) {
            this.applyClamped(resident, Config.MOOD_TRAIT_OPTIMIST.get());
            addThought(level, EventType.OPTIMIST_DAILY);
        } else if (this.personality == PersonalityType.PESSIMIST) {
            this.applyClamped(resident, -Config.MOOD_TRAIT_PESSIMIST.get());
            addThought(level, EventType.PESSIMIST_DAILY);
        }
    }

    /**
     * 结算 7：住宅区里的材质与颜色（用户定稿：住宅区<b>整体</b>扫描，v0.8.1）。
     *
     * <p>扫描交给 {@link HomeMaterialScanManager} 摊派执行（每 tick 全服限量
     * 4096 格、命中即停、跨天弃扫），完成后回调
     * {@link #applyHomeMaterialResult} 统一结算心情。
     * 没分配住宅区就不结算——不能拿镇广场当自家装修。
     */
    private void settleHomeMaterials(Resident resident, ServerLevel level) {
        TownZone zone = resident.housingZone();
        if (zone == null) {
            return;
        }
        HomeMaterialScanManager.request(level, zone, resident, this);
    }

    /**
     * 这个注册名是否命中他喜欢的建材（住宅区扫描器逐格询问用）。
     */
    public boolean likesBlock(String registryName) {
        for (BlockMaterial m : this.likedBlocks) {
            if (m.matches(registryName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这个注册名是否命中他喜欢的颜色（住宅区扫描器逐格询问用）。
     */
    public boolean likesColor(String registryName) {
        return this.likedColor != null && this.likedColor.matches(registryName);
    }

    /**
     * 住宅区扫描结果统一结算入口（{@link HomeMaterialScanManager} 完成时回调）。
     *
     * <p>不在此刻检查"是否已结算过"——委托方保证每镇民每天只有一次请求。
     */
    public void applyHomeMaterialResult(Resident resident, ServerLevel level,
                                        boolean blockHit, boolean colorHit) {
        if (blockHit) {
            this.applyClamped(resident, Config.MOOD_BLOCK_LIKED.get());
            addThought(level, EventType.BLOCK_LIKED);
        }
        if (colorHit) {
            this.applyClamped(resident, Config.MOOD_COLOR_LIKED.get());
            addThought(level, EventType.COLOR_LIKED);
        }
    }

    // ==========================================================
    //  睡眠结算（v0.8.1）
    // ==========================================================

    /**
     * 夜序号：dayTime {@code t} 所属夜晚的编号。夜 k 从日落
     * {@code 12000 + k*24000} 开始，到日出 {@code (k+1)*24000} 结束。
     */
    private static int nightIndex(long dayTime) {
        return (int) Math.floorDiv(dayTime - 12000L, 24000L);
    }

    /**
     * 躺下睡觉的瞬间登记（{@code Resident.lieDown} 调用，不落当天心情账）。
     */
    public void noteSlept(ServerLevel level) {
        this.lastSleptNightK = nightIndex(level.getDayTime());
    }

    /**
     * 结算 8：昨晚睡了吗（v0.8.1 用户定稿）。
     *
     * <p>规则：每个"完整结束的夜"检查一次——昨晚躺过床就没事，
     * 没躺就 {@code mood.noSleep}（默认 3），<b>连续没睡逐夜叠加、不封顶</b>
     * （漏两夜扣 6，漏三夜扣 9……）。每个夜只罚一次，靠
     * {@link #lastChargedNightK} 账本防双计。
     *
     * <p><b>战士守夜豁免</b>：结算时正在跟随时/站岗中（workMode ≠ NONE）
     * → 不罚，但账本照样推进——否则转职成农民那天会把守夜期间的
     * 夜全追溯扣一遍。
     *
     * <p>惩罚走 {@link #applyRaw}（绕过 clamp，与挨饿同一口径：
     * 惩罚必须真实，不能被温柔化）。
     */
    private void settleSleep(Resident resident, ServerLevel level) {
        int noSleep = Config.MOOD_NO_SLEEP.get();
        if (noSleep <= 0) {
            return; // 0 = 关闭
        }
        long now = level.getDayTime();
        int kNow = nightIndex(now);
        // 当前夜已经完整结束了吗？白天（0~12000）→ 今晚刚结束的那夜可结算；
        // 夜里（12000~24000）→ 当前夜还没过完，只结算到昨夜
        boolean daytime = Math.floorMod(now, 24000L) < 12000L;
        int kLastComplete = daytime ? kNow : kNow - 1;

        // 老档/新居民首次结算：不追溯历史，从现在起记账
        if (this.lastSleptNightK == Integer.MIN_VALUE) {
            this.lastSleptNightK = kNow;
        }
        if (this.lastChargedNightK == Integer.MIN_VALUE) {
            this.lastChargedNightK = kLastComplete;
            return;
        }

        // 战士守夜豁免：不罚，但账本推进（防止卸职后追溯扣守夜期间）
        if (resident.getWorkMode() != WorkMode.NONE) {
            this.lastChargedNightK = kLastComplete;
            return;
        }

        // 漏掉的夜 = 已结算账本之后、且在最后一次睡觉之后的完整夜数
        int floor = Math.max(this.lastChargedNightK, this.lastSleptNightK);
        int missed = kLastComplete - floor;
        if (missed <= 0) {
            this.lastChargedNightK = Math.max(this.lastChargedNightK, kLastComplete);
            return;
        }
        this.lastChargedNightK = kLastComplete;
        this.applyRaw(resident, -missed * noSleep);
        addThought(level, EventType.NO_SLEEP);
    }

    // ==========================================================
    //  感知扫描（tickSenses：每 100 tick 一轮的周边世界检查）
    // ==========================================================

    /**
     * 每 tick 的感知节拍：内部数到 {@link #SENSE_INTERVAL} 才真正扫一次。
     *
     * <p>扫四样东西：喜欢的动物（8 格）、唱片机（8 格）、
     * 敌对生物（16 格：生物厌恶 + 胆小受惊）、天气变化（半天节流）。
     */
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

    /** 喜欢的动物在 8 格内出没 → +3（每天 1 次）。 */
    private void senseAnimal(Resident resident, ServerLevel level) {
        if (this.likedAnimal == null) {
            return;
        }
        EventType type = EventType.ANIMAL_LIKED;
        if (this.eventCountToday.getOrDefault(type, 0) >= 1) {
            return; // 今天已经撸到了
        }
        AABB box = resident.getBoundingBox().inflate(ANIMAL_RANGE);
        boolean nearby = false;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (e.isAlive() && e.getType() == this.likedAnimal.type()) {
                nearby = true;
                break;
            }
        }
        if (nearby) {
            this.eventCountToday.put(type, 1);
            this.applyClamped(resident, Config.MOOD_ANIMAL_LIKED.get());
            addThought(level, type);
        }
    }

    /** 喜欢音乐且 8 格内有唱片机在放 → +3（每天 1 次）。 */
    private void senseJukebox(Resident resident, ServerLevel level) {
        if (!this.likesMusic) {
            return;
        }
        EventType type = EventType.MUSIC_LIKED;
        if (this.eventCountToday.getOrDefault(type, 0) >= 1) {
            return; // 今天已经听过了
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
            addThought(level, type);
        }
    }

    /**
     * 16 格内的敌对生物：生物厌恶（5 分钟一次无日上限）+
     * 胆小受惊（半天一次，并刷新"上次受惊日"）。
     *
     * <p>类过滤器用 {@link Mob} + {@link Enemy} 接口过滤——
     * {@code getEntitiesOfClass} 只收实体类不收接口，而 {@code Monster}
     * 漏掉了"Mob 直接实现 Enemy"的敌对生物：史莱姆/岩浆怪（v0.8.1 实测
     * 史莱姆不触发讨厌想法）。Enemy 覆盖全部原版敌对生物；
     * 原版敌对生物的厌恶判定走"类型直等"，只覆盖 MobPref 表内 14 种，够用。
     */
    private void senseHostiles(Resident resident, ServerLevel level) {
        AABB box = resident.getBoundingBox().inflate(HOSTILE_RANGE);
        List<Mob> hostiles = level.getEntitiesOfClass(Mob.class, box);
        boolean anyAlive = false;
        boolean match = false;
        for (Mob m : hostiles) {
            if (!(m instanceof Enemy)) {
                continue; // 只看敌对生物；Mob.class 会捞到村民/动物，全靠这刀筛掉
            }
            if (!m.isAlive()) {
                continue;
            }
            anyAlive = true;
            if (this.dislikedMob != null && m.getType() == this.dislikedMob.type()) {
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
            addThought(level, EventType.MOB_DISLIKED);
        }

        if (this.personality == PersonalityType.TIMID
                && elapsed(now, this.lastTimidAt, Config.MOOD_TRAIT_TIMID_INTERVAL.get())) {
            this.lastTimidAt = now;
            this.lastTimidScareDay = currentDay(level);
            this.applyClamped(resident, -Config.MOOD_TRAIT_VALUE.get());
            addThought(level, EventType.TIMID_SCARED);
        }
    }

    /**
     * 天气变化：半天内最多一次，且天气与上次记录的不同才触发（用户定稿：
     * "不要连续触发"）。讨厌这种天气 → −3；否则（包括没有讨厌天气的人）
     * 记一条中性想法。
     */
    private void senseWeather(Resident resident, ServerLevel level) {
        long now = level.getDayTime();
        if (!elapsed(now, this.lastWeatherAt, Config.MOOD_WEATHER_INTERVAL.get())) {
            return; // 半天内已经感慨过了
        }
        WeatherPref current = WeatherPref.current(level);
        if (current == this.lastWeather) {
            return; // 天气没变，不重复感慨
        }
        this.lastWeatherAt = now;
        this.lastWeather = current;
        if (this.dislikedWeather == current) {
            this.applyClamped(resident, -Config.MOOD_WEATHER_DISLIKED.get());
            addThought(level, EventType.WEATHER_DISLIKED);
        } else {
            addThought(level, EventType.WEATHER_NEUTRAL);
        }
    }

    // ==========================================================
    //  偏好懒生成
    // ==========================================================

    /** 特性（null = 老档未抽）。 */
    @org.jetbrains.annotations.Nullable
    public com.bmf.bmfstown.entity.profile.TraitType trait() {
        return this.trait;
    }

    /** 调试命令直设特性（{@code /bmfstown trait}；置 null 再补抽等价于重抽）。 */
    public void setTraitForCommand(com.bmf.bmfstown.entity.profile.TraitType trait) {
        this.trait = trait;
    }

    /**
     * 特性补抽：特性随 v0.8.1 新增，老档 {@link #prefsGenerated} 已为 true、
     * 不会重走 {@link #ensureGenerated}，靠每 tick 的 null 判断补抽（幂等、廉价）。
     */
    public void ensureTrait(RandomSource random) {
        if (this.trait == null) {
            this.trait = com.bmf.bmfstown.entity.profile.TraitType.random(random);
        }
    }

    /**
     * 特性逐 tick 行为（每 tick 从 {@code customServerAiStep} 调入，内部自持计时器）。
     * 当前只挂轻浮的漂浮检定；后续特性（末影步/自毁倾向…）实装时在这里加分支。
     */
    public void tickTraits(Resident resident) {
        if (this.trait == com.bmf.bmfstown.entity.profile.TraitType.FLIGHTY) {
            // 每 30 秒检定一次 1/8 概率 → 平均约 4 分钟浮一次，3 秒（60 tick）
            if (++this.flightyTimer >= 600) {
                this.flightyTimer = 0;
                if (resident.getRandom().nextInt(8) == 0) {
                    resident.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                            net.minecraft.world.effect.MobEffects.LEVITATION, 60, 0,
                            false, true));
                }
            }
        }
    }

    /**
     * 重摇偏好与性格（调试命令 {@code /bmfstown reroll} 用，正式玩法没有入口）。
     *
     * <p>把 {@link #prefsGenerated} 打回 false 再走一遍 {@link #ensureGenerated}——
     * 新的抽取下一 tick 存进 NBT，客户端重开面板即可看到。
     * lastKillDay/lastTimidScareDay 会重置到今天（ensureGenerated 的初始值语义），
     * 测试时可当作"这个镇民今天刚出生"。
     */
    public void reroll(Resident resident, ServerLevel level) {
        this.prefsGenerated = false;
        ensureGenerated(resident, level);
        // 职业好恶与喜欢的食物存在 ResidentProfile（record），MoodState 侧重摇
        // 够不到它们——旧实现只摇 MoodState 侧，职业偏好怎么 reroll 都不变
        //（用户实测点名："不能 roll 的字段不止职业"）。
        resident.updateProfile(p -> p.rerollPreferences(resident.getRandom()));
    }

    /**
     * 第一次用到时抽取偏好并标记已生成。
     *
     * <p>为什么懒生成而不是出生时：{@code ResidentProfile.createRandom}
     * 拿不到 ServerLevel，而"上次击杀日"这类初始值需要当天序号；
     * 更重要的是偏好全存 MoodState 的 NBT，出生时生成与首次 tick 生成
     * 对玩家不可见，懒生成还能顺带兼容老存档（读档第一次 tick 补抽）。
     *
     * <p>随机源用 {@code resident.getRandom()}（实体自带随机），不新建——
     * 世界种子同款实体随机流，行为可复现。
     */
    public void ensureGenerated(Resident resident, ServerLevel level) {
        if (this.prefsGenerated) {
            return;
        }
        this.prefsGenerated = true;
        RandomSource random = resident.getRandom();

        // 建筑方块：最多 2 种，每种 1/2 概率，去重
        List<BlockMaterial> blocks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            if (random.nextBoolean()) {
                BlockMaterial pick = BlockMaterial.random(random);
                if (!blocks.contains(pick)) {
                    blocks.add(pick);
                }
            }
        }
        this.likedBlocks = List.copyOf(blocks);

        this.likedAnimal = random.nextBoolean() ? PrefAnimal.random(random) : null;
        this.likedColor = random.nextBoolean() ? PrefColor.random(random) : null;
        this.likesMusic = random.nextBoolean();
        this.dislikedWeather = random.nextInt(3) == 0 ? WeatherPref.randomDisliked(random) : null;
        this.dislikedMob = random.nextInt(4) == 0 ? MobPref.random(random) : null;
        this.personality = PersonalityType.random(random);
        this.ensureTrait(random);

        int today = currentDay(level);
        this.lastKillDay = today;
        this.lastTimidScareDay = today;
    }

    // ==========================================================
    //  心情施加（clamp 逻辑集中在这两个方法里）
    // ==========================================================

    /** 带每日 clamp 的施加：所有常规事件与慢变量共用每天 ±MOOD_DAILY_CLAMP 的配额。 */
    private void applyClamped(Resident resident, int delta) {
        int clamp = Config.MOOD_DAILY_CLAMP.get();
        if (clamp <= 0) {
            // 0 = 不限幅（v0.8.1 用户拍板取消每日 clamp），数值原样生效
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
        int oldMood = resident.getProfile().mood();
        int newMood = Math.max(0, Math.min(ResidentProfile.MAX_MOOD, oldMood + delta));
        if (newMood == oldMood) {
            return;
        }
        resident.updateProfile(p -> p.withMood(newMood));
    }

    /**
     * 距 {@code last} 是否已过 {@code interval}（dayTime 口径）。
     *
     * <p>{@code last == Long.MIN_VALUE}（从未触发）算"已过"。
     * <b>必须走这个helper</b>：{@code now - Long.MIN_VALUE} 会 long 溢出成负数，
     * 直接写减法比较会让"从未触发"被误判成"刚刚触发过"，事件永远不出现
     * （v0.8 新加的天气/生物/胆小三个间隔字段都栽在这个坑里）。
     */
    private static boolean elapsed(long now, long last, long interval) {
        return last == Long.MIN_VALUE || now - last >= interval;
    }

    /**
     * 记一条想法。30 条封顶，队满挤掉最老的（用户定稿：不合并同类事件）。
     *
     * <p>标记规则（用户定稿）：乐观的正面想法尾部打 "+"，悲观的负面想法
     * 打 "−"，给玩家一个"这人的性格滤镜"提示。中性事件不打标记。
     */
    private void addThought(ServerLevel level, EventType type) {
        int marker = Thought.MARK_NONE;
        if (this.personality == PersonalityType.OPTIMIST && type.tone == TONE_POSITIVE) {
            marker = Thought.MARK_PLUS;
        } else if (this.personality == PersonalityType.PESSIMIST && type.tone == TONE_NEGATIVE) {
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
    //  序列化（在 Resident 的 save/read 里挂）
    // ==========================================================

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
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

        // ---- v0.8 偏好与性格 ----
        tag.putBoolean("PrefsGenerated", this.prefsGenerated);
        if (this.prefsGenerated) {
            ListTag blocks = new ListTag();
            for (BlockMaterial m : this.likedBlocks) {
                blocks.add(net.minecraft.nbt.StringTag.valueOf(m.name()));
            }
            tag.put("LikedBlocks", blocks);
            putEnum(tag, "LikedAnimal", this.likedAnimal);
            putEnum(tag, "LikedColor", this.likedColor);
            tag.putBoolean("LikesMusic", this.likesMusic);
            putEnum(tag, "DislikedWeather", this.dislikedWeather);
            putEnum(tag, "DislikedMob", this.dislikedMob);
            putEnum(tag, "Personality", this.personality);
            tag.putString("TraitId", this.trait == null ? "" : this.trait.id());
            tag.putInt("LastKillDay", this.lastKillDay);
            tag.putInt("LastTimidScareDay", this.lastTimidScareDay);
            tag.putLong("LastWeatherAt", this.lastWeatherAt);
            putEnum(tag, "LastWeather", this.lastWeather);
            tag.putLong("LastMobAt", this.lastMobAt);
            tag.putLong("LastTimidAt", this.lastTimidAt);
            tag.putInt("LastSleptNightK", this.lastSleptNightK);
            tag.putInt("SleepChargedNightK", this.lastChargedNightK);
            tag.putLong("CraftTablePos", this.craftTablePos);
            tag.putLong("InputChestPos", this.inputChestPos);
            tag.putLong("OutputChestPos", this.outputChestPos);
            tag.putString("OrderItem", this.orderItem);
            tag.putBoolean("WorkerEnabled", this.workerEnabled);
        }
        return tag;
    }

    private static void putEnum(CompoundTag tag, String key, Enum<?> value) {
        if (value != null) {
            tag.putString(key, value.name());
        }
    }

    private static <E extends Enum<E>> E getEnum(CompoundTag tag, String key, Class<E> type, E fallback) {
        if (!tag.contains(key)) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, tag.getString(key));
        } catch (IllegalArgumentException ignored) {
            return fallback; // 枚举被改名/删值时的兜底
        }
    }

    public void load(CompoundTag tag) {
        this.lastSettledDay = tag.contains("LastSettledDay") ? tag.getInt("LastSettledDay") : -1;
        // offset 与 lastSettledDay 成对读回——绝不能在读档时重随机（会重复结算 + 刷限流）
        this.settleOffset = tag.contains("SettleOffset") ? tag.getInt("SettleOffset") : RandomSource.create().nextInt(24000);
        this.lastFedDay = tag.contains("LastFedDay") ? tag.getInt("LastFedDay") : -1;
        this.lastStarvingAt = tag.contains("LastStarvingAt") ? tag.getLong("LastStarvingAt") : Long.MIN_VALUE;
        this.dailyDelta = tag.contains("DailyDelta") ? tag.getInt("DailyDelta") : 0;
        this.eventCountToday.clear();
        if (tag.contains("EventCounters")) {
            CompoundTag counterTag = tag.getCompound("EventCounters");
            for (EventType type : EventType.values()) {
                if (counterTag.contains(type.name())) {
                    this.eventCountToday.put(type, Math.max(0, counterTag.getInt(type.name())));
                }
            }
        }
        this.thoughts.clear();
        if (tag.contains("Thoughts")) {
            ListTag list = tag.getList("Thoughts", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag tTag = list.getCompound(i);
                // v0.7 老档没有 Marker/Tone：Tone 缺省按旧 Pos 布尔推，Marker 缺省 0
                int tone = tTag.contains("Tone")
                        ? tTag.getInt("Tone")
                        : (tTag.getBoolean("Pos") ? TONE_POSITIVE : TONE_NEGATIVE);
                this.thoughts.addLast(new Thought(tTag.getInt("Day"), tone,
                        tTag.getString("Key"), tTag.contains("Marker") ? tTag.getInt("Marker") : 0));
            }
            while (this.thoughts.size() > MAX_THOUGHTS) {
                this.thoughts.pollFirst();
            }
        }

        // ---- v0.8 偏好与性格（老存档没有 → 保持未生成，首次 tick 懒生成补抽） ----
        this.prefsGenerated = tag.getBoolean("PrefsGenerated");
        if (this.prefsGenerated) {
            this.likedBlocks = List.of();
            if (tag.contains("LikedBlocks")) {
                ListTag blocks = tag.getList("LikedBlocks", Tag.TAG_STRING);
                List<BlockMaterial> parsed = new ArrayList<>();
                for (int i = 0; i < blocks.size(); i++) {
                    try {
                        BlockMaterial m = BlockMaterial.valueOf(blocks.getString(i));
                        if (!parsed.contains(m)) {
                            parsed.add(m);
                        }
                    } catch (IllegalArgumentException ignored) {
                        // 单个材质失效就跳过，不影响其他偏好
                    }
                }
                this.likedBlocks = List.copyOf(parsed);
            }
            this.likedAnimal = getEnum(tag, "LikedAnimal", PrefAnimal.class, null);
            this.likedColor = getEnum(tag, "LikedColor", PrefColor.class, null);
            this.likesMusic = tag.getBoolean("LikesMusic");
            this.dislikedWeather = getEnum(tag, "DislikedWeather", WeatherPref.class, null);
            this.dislikedMob = getEnum(tag, "DislikedMob", MobPref.class, null);
            this.personality = getEnum(tag, "Personality", PersonalityType.class,
                    PersonalityType.OPTIMIST);
            String traitId = tag.contains("TraitId") ? tag.getString("TraitId") : "";
            this.trait = traitId.isEmpty() ? null
                    : com.bmf.bmfstown.entity.profile.TraitType.byId(traitId);
            this.lastKillDay = tag.contains("LastKillDay") ? tag.getInt("LastKillDay") : -1;
            this.lastTimidScareDay = tag.contains("LastTimidScareDay") ? tag.getInt("LastTimidScareDay") : -1;
            this.lastWeatherAt = tag.contains("LastWeatherAt") ? tag.getLong("LastWeatherAt") : Long.MIN_VALUE;
            this.lastWeather = getEnum(tag, "LastWeather", WeatherPref.class, null);
            this.lastMobAt = tag.contains("LastMobAt") ? tag.getLong("LastMobAt") : Long.MIN_VALUE;
            this.lastTimidAt = tag.contains("LastTimidAt") ? tag.getLong("LastTimidAt") : Long.MIN_VALUE;
            // 夜序号缺省保持 MIN_VALUE：settleSleep 首次结算时懒初始化为"当前"，不追溯
            this.lastSleptNightK = tag.contains("LastSleptNightK") ? tag.getInt("LastSleptNightK") : Integer.MIN_VALUE;
            this.lastChargedNightK = tag.contains("SleepChargedNightK") ? tag.getInt("SleepChargedNightK") : Integer.MIN_VALUE;
            this.craftTablePos = tag.contains("CraftTablePos") ? tag.getLong("CraftTablePos") : 0L;
            this.inputChestPos = tag.contains("InputChestPos") ? tag.getLong("InputChestPos") : 0L;
            this.outputChestPos = tag.contains("OutputChestPos") ? tag.getLong("OutputChestPos") : 0L;
            this.orderItem = tag.contains("OrderItem") ? tag.getString("OrderItem") : "";
            this.workerEnabled = !tag.contains("WorkerEnabled") || tag.getBoolean("WorkerEnabled");
        }
    }
}
