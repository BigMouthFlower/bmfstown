package com.bmf.bmfstown.entity.domain;

import com.bmf.bmfstown.config.Config;
import com.bmf.bmfstown.entity.profile.PersonalityAxis;

import java.util.function.IntSupplier;

/**
 * 单条事件声明（Fluent 构建，决策 #116）。
 *
 * <p>描述"这个事件发生时改什么"：心情增量、想法文案 key、色调、节流、限幅、
 * 记忆、气泡。声明在一次静态初始化里完成并 {@link #seal()} 冻结，之后只读。
 *
 * <h2>色调规则（#116 追记，动态计算非硬编码）</h2>
 * <p>想法颜色描述的是"这件事对这位居民**定性**是好/坏/中性/知识"，与心情是否被封顶或
 * 日限截断无关——心情满了喝酒仍是开心事，不能因此变灰。
 * <ul>
 *   <li><b>带 {@code mood(...)} 的事件</b>：颜色 = <b>意图方向</b>（clamp/限幅<b>之前</b>
 *       的意图增量符号，{@link #toneFor(int)}）：+绿 / −红 / 0灰。对手冲型
 *       （{@code swing}）事件，意图增量本身已由性格轴算出，所以"同一事件对不同性格
 *       可好可坏"天然成立。</li>
 *   <li><b>不带 {@code mood(...)} 的事件</b>：颜色 = 声明专属色
 *       （默认 {@link Status#TONE_NEUTRAL}；{@link #knowledge()} 用
 *       {@link Status#TONE_KNOWLEDGE}）。</li>
 * </ul>
 *
 * <h2>限制一律用参数 0 表达</h2>
 * {@link #daily(int) daily(0)} 与 {@link #interval(long) interval(0)} = 不限；
 * {@link #clamp(ClampPolicy)} 的 {@code NONE} 在 {@link #clampCap()} 里解析成 0，
 * 执行层仍是同一条路径，绝不 "if (NONE) 跳过"。
 *
 * <h2>字段状态</h2>
 * {@code interval/memory/bubble} 第二批起由 {@link Events} 统一执行；
 * {@code swing} 型事件色调随意图方向摆动，其余用声明定性。
 */
public final class EventSpec {

    private final EventType type;

    /** 心情增量来源；null = 无心情（想法专属色事件）。 */
    private IntSupplier mood;
    /** 是否允许调用方覆盖心情值（读书/喝酒的批注倍率）。 */
    private boolean moodOverride;

    private String thoughtKey;

    /** 无心情事件的声明色（默认灰）；带 mood 的事件此值仅作预期方向。 */
    private int declaredTone = Status.TONE_NEUTRAL;
    /** 对冲型事件：色调随轴值的负面清单；null = 非对冲。 */
    private PersonalityAxis swingAxis;

    /** 每日触发上限；IntSupplier 延迟读 Config，null = 不限。 */
    private IntSupplier dailyLimit = null;
    /** interval 节流（dayTime tick）；IntSupplier 延迟读 Config，null = 不限。 */
    private IntSupplier interval = null;
    private ClampPolicy clamp = ClampPolicy.DAILY;

    /** 气泡台词组 topic；null = 不冒泡。 */
    private String bubbleTopic;
    private MemoryKind memoryKind;
    private String memoryKey;

    /** 城镇级冷却（dayTime tick）；IntSupplier 延迟读 Config，null = 不限。 */
    private IntSupplier townCooldown = null;

    private boolean sealed;

    public EventSpec(EventType type) {
        this.type = type;
    }

    // ---- 心情 ----

    public EventSpec mood(int value) {
        return mood(() -> value);
    }

    public EventSpec mood(IntSupplier value) {
        mutable();
        this.mood = value;
        return this;
    }

    /** 调用方会在 happen 时传实际心情值（读书/喝酒）。 */
    public EventSpec moodOverride() {
        mutable();
        this.moodOverride = true;
        return this;
    }

    // ---- 想法 ----

    public EventSpec thought(String key) {
        mutable();
        this.thoughtKey = key;
        return this;
    }

    // ---- 色调（预期方向，见类注释）----

    public EventSpec positive() {
        mutable();
        this.declaredTone = Status.TONE_POSITIVE;
        return this;
    }

    public EventSpec negative() {
        mutable();
        this.declaredTone = Status.TONE_NEGATIVE;
        return this;
    }

    public EventSpec neutral() {
        mutable();
        this.declaredTone = Status.TONE_NEUTRAL;
        return this;
    }

    /** 知识专属色（不影响心情但影响知识，如学会一本书）。 */
    public EventSpec knowledge() {
        mutable();
        this.declaredTone = Status.TONE_KNOWLEDGE;
        return this;
    }

    /** 对冲型：色彩随轴值摆动（文案自动分 _pos/_neg）。 */
    public EventSpec swing(PersonalityAxis axis) {
        mutable();
        this.swingAxis = axis;
        return this;
    }

    // ---- 节流 / 限幅 ----

    /** 每日触发上限（写死值），0 = 不限。 */
    public EventSpec daily(int limit) {
        return daily(() -> limit);
    }

    /** 每日触发上限（延迟读 Config 保持可调），null/0 = 不限。 */
    public EventSpec daily(IntSupplier limit) {
        mutable();
        this.dailyLimit = limit;
        return this;
    }

    /** dayTime 间隔限流（tick）；0 = 不限。IntSupplier 延迟读 Config 保持游戏内可调。 */
    public EventSpec interval(IntSupplier ticks) {
        mutable();
        this.interval = ticks;
        return this;
    }

    public EventSpec clamp(ClampPolicy policy) {
        mutable();
        this.clamp = policy;
        return this;
    }

    // ---- 第一批暂不执行的扩展 ----

    /**
     * 触发台词气泡，并绑定台词组 topic。
     *
     * <p>topic 是"选哪一组台词"的键；具体说哪一句仍由台词表按
     * <b>变体 + 条件 + 权重</b>决定，声明表只管"这件事值得开口"。
     * 变体（天气种类、物品名）在 {@code Events.happen} 运行时传入，不写死在声明里。
     */
    public EventSpec bubble(String topic) {
        mutable();
        if (topic == null || topic.isBlank()) {
            throw new IllegalStateException("EventSpec " + this.type + " bubble topic 不能为空");
        }
        this.bubbleTopic = topic;
        return this;
    }

    /**
     * <b>城镇级</b>冷却：镇内任何一人触发过，全镇静默这么久（dayTime tick）。
     *
     * <p>与 {@link #daily} 的分工：{@code daily} 是"每个人每天几次"，
     * 本项是"这个镇多久才能再发生一次"——单位是镇，不是人。到点之前
     * {@code Events} 直接整条 return，心情/想法/气泡一起不发生。
     *
     * <p>0 = 不限。IntSupplier 延迟读 Config 保持可调。
     */
    public EventSpec townCooldown(IntSupplier ticks) {
        mutable();
        this.townCooldown = ticks;
        return this;
    }

    /** 城镇级冷却（写死值），0 = 不限。 */
    public EventSpec townCooldown(int ticks) {
        return townCooldown(() -> ticks);
    }

    /**
     * 记忆（铭记/印象）+ 记忆 key。
     *
     * <p>{@link MemoryKind#FIRM} 走铭记进度（积满固化并宣布想法）；
     * {@link MemoryKind#IMPRESSION} 走印象池（v0.16.8 从 {@code MemoryState} 订阅搬回），
     * 印象的物品 id 由 {@code Events.Args.memoryArg} 运行时传入。
     */
    public EventSpec memory(MemoryKind kind, String key) {
        mutable();
        this.memoryKind = kind;
        this.memoryKey = key;
        return this;
    }

    // ---- 只读访问器 ----

    public EventType type() {
        return this.type;
    }

    public String thoughtKey() {
        return this.thoughtKey;
    }

    public boolean hasMood() {
        return this.mood != null;
    }

    public int moodValue() {
        return this.mood.getAsInt();
    }

    public boolean allowsOverride() {
        return this.moodOverride;
    }

    /** 每日触发上限值；未声明 = 0（不限）。 */
    public int dailyLimit() {
        return this.dailyLimit == null ? 0 : this.dailyLimit.getAsInt();
    }

    /** interval 节流值（tick）；未声明 = 0（不限）。 */
    public int intervalTicks() {
        return this.interval == null ? 0 : this.interval.getAsInt();
    }

    public boolean isSwing() {
        return this.swingAxis != null;
    }

    public PersonalityAxis swingAxis() {
        return this.swingAxis;
    }

    /** 无心情事件的颜色。 */
    public int declaredTone() {
        return this.declaredTone;
    }

    public boolean hasBubble() {
        return this.bubbleTopic != null;
    }

    /** 是否声明了想法（没声明的就是纯气泡/纯心情事件）。 */
    public boolean hasThought() {
        return this.thoughtKey != null;
    }

    /** 城镇级冷却值（tick）；未声明 = 0（不限）。 */
    public int townCooldownTicks() {
        return this.townCooldown == null ? 0 : this.townCooldown.getAsInt();
    }

    /** 气泡台词组 topic；未声明 = null。 */
    public String bubbleTopic() {
        return this.bubbleTopic;
    }

    public MemoryKind memoryKind() {
        return this.memoryKind;
    }

    public String memoryKey() {
        return this.memoryKey;
    }

    /** 执行层限幅 cap：DAILY → 全局每日配额；NONE → 0（不限额）。 */
    public int clampCap() {
        return this.clamp == ClampPolicy.DAILY ? Config.MOOD_DAILY_CLAMP.get() : 0;
    }

    /**
     * 由<b>意图增量</b>求想法色调（clamp/限幅之前的意图方向，非实算落账值）。
     * 心情满了/日限截断都不会改变事件的定性——喝酒即使 +0 到账也仍是绿。
     * 只有 swing（对冲型）事件按意图符号摆动；其余用声明定性
     * （positive/negative/neutral/knowledge），SOCIAL 等遗留事件由此保留现状写死色调。
     */
    public int toneFor(double intended) {
        if (this.swingAxis != null) {
            return intended > 0 ? Status.TONE_POSITIVE
                    : intended < 0 ? Status.TONE_NEGATIVE
                    : Status.TONE_NEUTRAL;
        }
        return this.declaredTone;
    }

    // ---- 冻结 ----

    void seal() {
        // 至少有一个出口（心情/想法/记忆/气泡）——不强制必须有想法：
        // "只开口"（观察类）与"只调心情"都是合法声明，只要不是空声明。
        if (!hasMood() && !hasThought() && this.memoryKind == null && !hasBubble()) {
            throw new IllegalStateException("EventSpec " + this.type + " 没有任何出口");
        }
        if (hasThought() && this.thoughtKey.isBlank()) {
            throw new IllegalStateException("EventSpec " + this.type + " thought key 空白");
        }
        if (this.moodOverride && !hasMood()) {
            throw new IllegalStateException("EventSpec " + this.type + " moodOverride 但无 mood");
        }
        if ((this.memoryKind == null) != (this.memoryKey == null)) {
            throw new IllegalStateException("EventSpec " + this.type + " memory 需 kind+key 成对");
        }
        this.sealed = true;
    }

    private void mutable() {
        if (this.sealed) {
            throw new IllegalStateException("EventSpec " + this.type + " 已冻结不可再改");
        }
    }
}