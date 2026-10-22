package com.bmf.bmfstown.entity.profile;

import com.bmf.bmfstown.config.Config;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;

import java.util.UUID;

/**
 * 一个镇民的全部"人格档案"。
 *
 * <h2>为什么把这些属性打包成一个类</h2>
 * 原版实体的 {@code SynchedEntityData} 只支持基本类型（int/float/string/boolean）。
 * 如果把 10 个属性各开一个 {@code EntityDataAccessor}：
 * <ul>
 *   <li>加一个属性要改 4 处代码（定义、NBT 读、NBT 写、同步）</li>
 *   <li>爱好这种东西是<b>结构化</b>的，塞不进 int</li>
 * </ul>
 * 打包成一个不可变 record 之后，加属性只改这个类，同步与存档都自动跟进。
 *
 * <h2>不可变设计</h2>
 * 这个类用 {@code record}（Java 16+），所有字段 final。
 * 修改状态的方式是 {@code withHunger(x)} 生成新实例，而不是原地改字段。
 *
 * <p><b>为什么不用可变类：</b>同步时需要判断"值变了没有"。
 * 不可变对象可以直接 {@code equals()} 比较，可变对象要逐字段比，容易漏。
 *
 * <h2>字段分组（对应需求文档 §4.1.2）</h2>
 * <ul>
 *   <li>A 组 状态：{@code hunger} / {@code mood}</li>
 *   <li>B 组 身份：{@code profession} / {@code xp} / 六个爱好枚举</li>
 *   <li>C 组 归属：{@code townId} / {@code homePos} / {@code housingZoneId}（都可为 null）</li>
 *   <li>E 组 关系：{@code affection}</li>
 * </ul>
 *
 * <h2>数值约定</h2>
 * <ul>
 *   <li>{@code hunger}：0~{@link #MAX_HUNGER}，与玩家一致（20 = 满）</li>
 *   <li>{@code mood}：0~100，初始 100（满）</li>
 *   <li>{@code affection}：0~100，初始 30（中性偏正）</li>
 * </ul>
 */
public record ResidentProfile(

        // ---- A 组：状态 ----
        int hunger,
        int mood,

        // ---- B 组：身份 ----
        ProfessionType profession,
        int xp,
        Hobbies.Food hobbyFood,
        Hobbies.HomeItem hobbyHomeItem,
        Hobbies.Environment hobbyEnvironment,
        Hobbies.Ambience hobbyAmbience,
        Hobbies.DislikedItem dislikeItem,
        Hobbies.DislikedEnvironment dislikeEnvironment,

        // ---- C 组：归属（可为 null） ----
        UUID townId,
        BlockPos homePos,
        /** 绑定的住宅区 ID（{@code TownWandMode.HOUSING} 里的某个 zone）；null = 未分配。 */
        UUID housingZoneId,
        /** 分配住宅的时间戳（毫秒）——床数变少时据此踢出"最新加入的"村民。 */
        long housingAssignedAt,

        // ---- E 组：关系 ----
        int affection
) {

    // ==========================================================
    //  常量
    // ==========================================================

    /** 饥饿上限，与玩家一致（20 点 = 10 个鸡腿）。 */
    public static final int MAX_HUNGER = 20;

    /** 心情上限。 */
    public static final int MAX_MOOD = 100;

    /** 好感度上限。 */
    public static final int MAX_AFFECTION = 100;

    /** 初始心情：满。 */
    /** 出生心情的默认值与配置项：{@link com.bmf.bmfstown.config.Config#INITIAL_MOOD}。 */

    /** 初始好感度：中性偏正（"认得"档的起点）。 */
    /** 出生好感的默认值与配置项：{@link com.bmf.bmfstown.config.Config#INITIAL_AFFECTION}。 */

    /**
     * 每升一级需要的经验值。
     *
     * <p>用 {@code 100 * 等级} 的累加：1→2 需要 100，2→3 需要 200……
     * 10 级累计约 4500 xp。这个曲线故意做得偏慢，因为熟练度是"长期养成轴"。
     */
    /** 升级曲线的基数与配置项：{@link com.bmf.bmfstown.config.Config#XP_PER_LEVEL_BASE}。 */

    // ==========================================================
    //  构造
    // ==========================================================

    /**
     * 生成一个全新的镇民档案：状态满、爱好随机、无专长、无归属。
     *
     * @param random 随机源，由实体提供（{@code this.getRandom()}）
     */
    public static ResidentProfile createRandom(RandomSource random) {
        return new ResidentProfile(
                MAX_HUNGER,                                  // 饥饿满
                Config.INITIAL_MOOD.get(),                   // 心情满
                ProfessionType.NONE,                         // 无专长
                0,                                           // 经验 0
                Hobbies.Food.random(random),
                Hobbies.HomeItem.random(random),
                Hobbies.Environment.random(random),
                Hobbies.Ambience.random(random),
                Hobbies.DislikedItem.random(random),         // 厌恶必须有值
                Hobbies.DislikedEnvironment.random(random),
                null,                                        // 无城镇
                null,                                        // 无住所
                null,                                        // 未分配住宅区
                0L,                                          // 无分配时间
                Config.INITIAL_AFFECTION.get()               // 好感（可配置）
        );
    }

    // ==========================================================
    //  等级换算（由 xp 推导，不单独存）
    // ==========================================================

    /**
     * 当前等级，从 {@link #xp} 算出来。
     *
     * <p><b>为什么不直接存等级：</b>等级是经验的函数，存两份就有了"两者不一致"的可能。
     * 只存经验，等级永远自洽；以后调曲线也不用迁移存档。
     *
     * <p>{@link ProfessionType#NONE} 永远返回 0 级。
     */
    public int level() {
        if (this.profession == null || !this.profession.isReal()) {
            return 0;
        }
        int level = 1;
        int need = Config.XP_PER_LEVEL_BASE.get();
        int remain = this.xp;
        while (remain >= need && level < this.profession.maxLevel()) {
            remain -= need;
            level++;
            need += Config.XP_PER_LEVEL_BASE.get();
        }
        return level;
    }

    /** 当前等级已累积的经验（距离下一级的进度）。 */
    public int xpIntoLevel() {
        if (this.profession == null || !this.profession.isReal()) {
            return 0;
        }
        int consumed = 0;
        int need = Config.XP_PER_LEVEL_BASE.get();
        int level = 1;
        while (consumed + need <= this.xp && level < this.profession.maxLevel()) {
            consumed += need;
            level++;
            need += Config.XP_PER_LEVEL_BASE.get();
        }
        return this.xp - consumed;
    }

    /** 升到下一级还需要多少经验。已满级 / 无专长返回 0。 */
    public int xpToNextLevel() {
        int lv = level();
        if (!this.profession.isReal() || lv >= this.profession.maxLevel()) {
            return 0;
        }
        // 1 级 → 2 级需要 100，2 级 → 3 级需要 200…… 即 100 * 当前等级
        return Config.XP_PER_LEVEL_BASE.get() * lv - xpIntoLevel();
    }

    /**
     * 当前等级的经验进度条比例（0.0 ~ 1.0）。UI 画进度条用。
     *
     * <p>无专长或满级时返回 0。
     */
    public float xpProgress() {
        int total = xpIntoLevel() + xpToNextLevel();
        if (total <= 0) {
            return 0.0F;
        }
        return (float) xpIntoLevel() / total;
    }

    /** 是否已达该专长的上限。 */
    public boolean isMaxLevel() {
        return this.profession != null && this.profession.isReal()
                && level() >= this.profession.maxLevel();
    }

    /** 称号。用于 UI 显示，不暴露数字。 */
    public String title() {
        int lv = level();
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

    /** 称号的语言文件 key。UI 用这个取译文，{@link #title()} 只作兜底。 */
    public String titleKey() {
        int lv = level();
        String suffix = lv <= 0 ? "none"
                : lv <= 2 ? "novice"
                : lv <= 5 ? "skilled"
                : lv <= 8 ? "veteran"
                : "master";
        return "title.bmfstown." + suffix;
    }

    // ==========================================================
    //  状态查询（供 AI / UI 使用）
    // ==========================================================

    /** 缺口：距离吃饱还差多少点。 */
    public int hungerMissing() {
        return MAX_HUNGER - this.hunger;
    }

    /**
     * 能不能吃下某个营养值的食物<b>而不浪费</b>。
     *
     * <p>核心规则（用户定义）：只吃"吃了不会溢出"的食物。
     * 因此饥饿 20 时什么都不吃，饥饿 18 时只能吃 nutrition ≤ 2 的。
     */
    public boolean canEatWithoutWaste(int nutrition) {
        return this.hunger + nutrition <= MAX_HUNGER;
    }

    /** 心情分档，用于气泡与 UI。 */
    public MoodTier moodTier() {
        return moodTierOf(this.mood);
    }

    /**
     * 凭数值判心情档。
     *
     * <p>客户端面板显示要用它：客户端拿到的是数据槽里的<b>裸数值</b>
     * （实时），而不是完整档案对象，所以分档必须能脱离实例做。
     * 阈值只在这里写一份，实例方法与客户端共用。
     */
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

    /** 好感度分档。 */
    public AffectionTier affectionTier() {
        return affectionTierOf(this.affection);
    }

    /** 凭数值判好感档，理由同 {@link #moodTierOf(int)}。 */
    public static AffectionTier affectionTierOf(int affection) {
        if (affection >= 80) {
            return AffectionTier.TRUSTED;
        }
        if (affection >= 60) {
            return AffectionTier.CLOSE;
        }
        if (affection >= 40) {
            return AffectionTier.FAMILIAR;
        }
        if (affection >= 20) {
            return AffectionTier.KNOWN;
        }
        return AffectionTier.STRANGER;
    }

    // ==========================================================
    //  with-方法：生成修改后的副本
    // ==========================================================

    public ResidentProfile withHunger(int value) {
        return new ResidentProfile(clamp(value, 0, MAX_HUNGER), mood, profession, xp,
                hobbyFood, hobbyHomeItem, hobbyEnvironment, hobbyAmbience,
                dislikeItem, dislikeEnvironment, townId, homePos,
                housingZoneId, housingAssignedAt, affection);
    }

    public ResidentProfile withMood(int value) {
        return new ResidentProfile(hunger, clamp(value, 0, MAX_MOOD), profession, xp,
                hobbyFood, hobbyHomeItem, hobbyEnvironment, hobbyAmbience,
                dislikeItem, dislikeEnvironment, townId, homePos,
                housingZoneId, housingAssignedAt, affection);
    }

    public ResidentProfile withAffection(int value) {
        return new ResidentProfile(hunger, mood, profession, xp,
                hobbyFood, hobbyHomeItem, hobbyEnvironment, hobbyAmbience,
                dislikeItem, dislikeEnvironment, townId, homePos,
                housingZoneId, housingAssignedAt, clamp(value, 0, MAX_AFFECTION));
    }

    public ResidentProfile withProfession(ProfessionType type) {
        // 换专长时经验清零：不同专长的经验不可迁移（见需求文档 Q12）
        return new ResidentProfile(hunger, mood, type, 0,
                hobbyFood, hobbyHomeItem, hobbyEnvironment, hobbyAmbience,
                dislikeItem, dislikeEnvironment, townId, homePos,
                housingZoneId, housingAssignedAt, affection);
    }

    public ResidentProfile withXp(int value) {
        return new ResidentProfile(hunger, mood, profession, Math.max(0, value),
                hobbyFood, hobbyHomeItem, hobbyEnvironment, hobbyAmbience,
                dislikeItem, dislikeEnvironment, townId, homePos,
                housingZoneId, housingAssignedAt, affection);
    }

    public ResidentProfile withTownId(UUID value) {
        return new ResidentProfile(hunger, mood, profession, xp,
                hobbyFood, hobbyHomeItem, hobbyEnvironment, hobbyAmbience,
                dislikeItem, dislikeEnvironment, value, homePos,
                housingZoneId, housingAssignedAt, affection);
    }

    public ResidentProfile withHomePos(BlockPos value) {
        return new ResidentProfile(hunger, mood, profession, xp,
                hobbyFood, hobbyHomeItem, hobbyEnvironment, hobbyAmbience,
                dislikeItem, dislikeEnvironment, townId, value,
                housingZoneId, housingAssignedAt, affection);
    }

    /**
     * 绑定/解绑住宅区。
     *
     * <p>解绑（传 null）时顺手把分配时间也清零，避免残留的旧时间戳
     * 影响下次超员踢人时的先后排序。
     */
    public ResidentProfile withHousingZoneId(UUID value) {
        return new ResidentProfile(hunger, mood, profession, xp,
                hobbyFood, hobbyHomeItem, hobbyEnvironment, hobbyAmbience,
                dislikeItem, dislikeEnvironment, townId, homePos,
                value, value == null ? 0L : this.housingAssignedAt, affection);
    }

    /** 只改分配时间戳（一般由 {@link #withHousingZoneId} 配合使用）。 */
    public ResidentProfile withHousingAssignedAt(long value) {
        return new ResidentProfile(hunger, mood, profession, xp,
                hobbyFood, hobbyHomeItem, hobbyEnvironment, hobbyAmbience,
                dislikeItem, dislikeEnvironment, townId, homePos,
                housingZoneId, value, affection);
    }

    /** 一次设置住宅绑定 + 分配时间（服务端分配入口用）。 */
    public ResidentProfile withHousing(UUID zoneId, long assignedAt) {
        return new ResidentProfile(hunger, mood, profession, xp,
                hobbyFood, hobbyHomeItem, hobbyEnvironment, hobbyAmbience,
                dislikeItem, dislikeEnvironment, townId, homePos,
                zoneId, zoneId == null ? 0L : assignedAt, affection);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    // ==========================================================
    //  序列化
    // ==========================================================

    /**
     * 写入 NBT。
     *
     * <p><b>约定</b>：字段名用 PascalCase（与原版 NBT 风格一致），
     * 一旦发布<b>不要改名</b>，否则老存档读不回来。
     * 新增字段时给一个默认值，保证老存档能读。
     */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Hunger", this.hunger);
        tag.putInt("Mood", this.mood);
        // 兜底：万一有人用反射塞了 null 进来，也别让存档写崩
        tag.putString("Profession", this.profession != null ? this.profession.getSerializedName() : "none");
        tag.putInt("Xp", this.xp);
        tag.putString("HobbyFood", this.hobbyFood.getSerializedName());
        tag.putString("HobbyHomeItem", this.hobbyHomeItem.getSerializedName());
        tag.putString("HobbyEnvironment", this.hobbyEnvironment.getSerializedName());
        tag.putString("HobbyAmbience", this.hobbyAmbience.getSerializedName());
        tag.putString("DislikeItem", this.dislikeItem.getSerializedName());
        tag.putString("DislikeEnvironment", this.dislikeEnvironment.getSerializedName());
        tag.putInt("Affection", this.affection);

        // 可空字段：null 就不写 key，读的时候用 contains 判断
        if (this.townId != null) {
            tag.putUUID("TownId", this.townId);
        }
        if (this.homePos != null) {
            tag.putLong("HomePos", this.homePos.asLong());
        }
        if (this.housingZoneId != null) {
            tag.putUUID("HousingZoneId", this.housingZoneId);
        }
        tag.putLong("HousingAssignedAt", this.housingAssignedAt);
        return tag;
    }

    /**
     * 从 NBT 读取。
     *
     * <p>所有字段都用「缺省 + 兜底」策略：老存档没有这个 key 时，
     * 不会崩，而是给一个合理的默认值。这是存档兼容的基本功。
     */
    public static ResidentProfile load(CompoundTag tag) {
        return new ResidentProfile(
                tag.contains("Hunger") ? tag.getInt("Hunger") : MAX_HUNGER,
                tag.contains("Mood") ? tag.getInt("Mood") : Config.INITIAL_MOOD.get(),
                ProfessionType.byName(tag.getString("Profession")),
                tag.getInt("Xp"),
                Hobbies.Food.byName(tag.getString("HobbyFood")),
                Hobbies.HomeItem.byName(tag.getString("HobbyHomeItem")),
                Hobbies.Environment.byName(tag.getString("HobbyEnvironment")),
                Hobbies.Ambience.byName(tag.getString("HobbyAmbience")),
                Hobbies.DislikedItem.byName(tag.getString("DislikeItem")),
                Hobbies.DislikedEnvironment.byName(tag.getString("DislikeEnvironment")),
                // hasUUID 在 key 不存在时也返回 false，所以不用先 contains
                tag.hasUUID("TownId") ? tag.getUUID("TownId") : null,
                tag.contains("HomePos") ? BlockPos.of(tag.getLong("HomePos")) : null,
                tag.hasUUID("HousingZoneId") ? tag.getUUID("HousingZoneId") : null,
                tag.getLong("HousingAssignedAt"),
                tag.contains("Affection") ? tag.getInt("Affection") : Config.INITIAL_AFFECTION.get()
        );
    }

    // ==========================================================
    //  分档枚举
    // ==========================================================

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

    /** 好感度档位。 */
    public enum AffectionTier {
        STRANGER("stranger", "陌生"),
        KNOWN("known", "认得"),
        FAMILIAR("familiar", "熟悉"),
        CLOSE("close", "亲近"),
        TRUSTED("trusted", "信任");

        private final String name;
        private final String zhName;

        AffectionTier(String name, String zhName) {
            this.name = name;
            this.zhName = zhName;
        }

        public String zhName() {
            return this.zhName;
        }

        /** 语言文件 key。 */
        public String displayName() {
            return "affection.bmfstown." + this.name;
        }
    }
}
