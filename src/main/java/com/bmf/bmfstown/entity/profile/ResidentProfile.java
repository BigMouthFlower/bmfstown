package com.bmf.bmfstown.entity.profile;

import com.bmf.bmfstown.config.Config;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * <h2>字段分组（v0.7 心情系统改版后的偏好区划）</h2>
 * <ul>
 *   <li>A 组 状态：{@code hunger} / {@code mood}</li>
 *   <li>B 组 身份：{@code profession} / {@code xp} / 偏好（食物 + 职业好恶）</li>
 *   <li>C 组 归属：{@code townId} / {@code homePos} / {@code housingZoneId}（都可为 null）</li>
 *   <li>F 组 技能与成长：{@code skills} / {@code perks}</li>
 * </ul>
 *
 * <h2>v0.7 破坏性变更：偏好体系重写</h2>
 * 旧的六维爱好（家中物品/居所环境/氛围/厌恶物品/厌恶环境）与好感度
 * （affection）已整体移除——旧维度从未有任何逻辑读取（纯死数据），
 * 好感度经讨论砍掉（无触发源、与心情重叠）。新偏好按
 * 「食物 / 方块 / 物品 / 天气 / 职业」五大类逐步重建，本版实装
 * 食物（沿用）与职业好恶（新增）。旧 NBT key 直接不再读取，无迁移价值。
 *
 * <h2>数值约定</h2>
 * <ul>
 *   <li>{@code hunger}：0~{@link #MAX_HUNGER}，与玩家一致（20 = 满）</li>
 *   <li>{@code mood}：0~100；真正的心情结算逻辑在 {@link com.bmf.bmfstown.entity.MoodState}</li>
 * </ul>
 */
public record ResidentProfile(

        // ---- A 组：状态 ----
        int hunger,
        int mood,

        // ---- B 组：身份 ----
        ProfessionType profession,
        int xp,
        /** 口味偏好：吃到对胃口的食物会改善心情（判定见 {@link FoodTraits}）。 */
        Hobbies.Food hobbyFood,
        /** 喜欢的工作：被指派这类工作时每日结算心情 +3。 */
        ProfessionType likedProfession,
        /**
         * 讨厌的工作：v0.8 起已退出心情体系（用户定稿：讨厌只留天气与生物两种）。
         * 字段保留是为了不动 record 构造点与老存档格式，不再有任何逻辑读取。
         */
        ProfessionType dislikedProfession,

        // ---- C 组：归属（可为 null） ----
        UUID townId,
        BlockPos homePos,
        /** 绑定的住宅区 ID（{@code TownWandMode.HOUSING} 里的某个 zone）；null = 未分配。 */
        UUID housingZoneId,
        /** 分配住宅的时间戳（毫秒）——床数变少时据此踢出"最新加入的"村民。 */
        long housingAssignedAt,

        // ---- F 组：技能与成长（v0.8 矮人要塞式"干中学"） ----
        /** 每个技能一条独立经验条，只增不减。读写走 {@link #skillXp} / {@link #withSkillXp}。 */
        Map<SkillType, Integer> skills,
        /** 已选的 perk id 列表（perk 系统占位，当前只存不用）。 */
        List<String> perks
) {

    // ==========================================================
    //  常量
    // ==========================================================

    /** 饥饿上限，与玩家一致（20 点 = 10 个鸡腿）。 */
    public static final int MAX_HUNGER = 20;

    /** 心情上限。 */
    public static final int MAX_MOOD = 100;

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
     * 生成一个全新的镇民档案：状态满、偏好随机、无专长、无归属。
     *
     * <p>职业好恶的生成规则（v0.7 定稿）：
     * <ul>
     *   <li>从「真实职业」（非 {@link ProfessionType#NONE}）里均匀抽 1 个喜欢、
     *       1 个讨厌，二者互斥；</li>
     *   <li>抽取按 {@link ProfessionType#isReal()} 数据驱动，新职业自动参与；</li>
     * </ul>
     *
     * @param random 随机源，由实体提供（{@code this.getRandom()}）
     */
    public static ResidentProfile createRandom(RandomSource random) {
        ProfessionType liked = randomRealProfession(random);
        ProfessionType disliked = randomRealProfession(random, liked); // 排除已抽中的
        return new ResidentProfile(
                MAX_HUNGER,                                  // 饥饿满
                Config.INITIAL_MOOD.get(),                   // 心情初值
                ProfessionType.NONE,                         // 无专长
                0,                                           // 经验 0
                Hobbies.Food.random(random),
                liked,
                disliked,
                null,                                        // 无城镇
                null,                                        // 无住所
                null,                                        // 未分配住宅区
                0L,                                          // 无分配时间
                Map.of(),                                    // 技能：全新镇民全 0
                List.of()                                    // 尚未选择任何 perk
        );
    }

    /** 从真实职业里均匀抽一个（数据驱动，随 {@link ProfessionType} 扩充自动覆盖）；NONE 永远不参与。 */
    private static ProfessionType randomRealProfession(RandomSource random) {
        return randomRealProfession(random, null);
    }

    /** 从真实职业里均匀抽一个，排除 {@code exclude}（用于保证好恶互斥）。 */
    private static ProfessionType randomRealProfession(RandomSource random,
            ProfessionType exclude) {
        ProfessionType[] real = java.util.Arrays.stream(ProfessionType.values())
                .filter(ProfessionType::isReal)
                .toArray(ProfessionType[]::new);
        // 真实职业只有一个时，排除后就没有合法候选了——直接返回它。
        // （多职业时代此分支基本不触发；好恶互斥由 exclude 保证。）
        if (real.length <= 1 || real.length == 1 && real[0] == exclude) {
            return real.length == 0 ? ProfessionType.NONE : real[0];
        }
        while (true) {
            ProfessionType pick = real[random.nextInt(real.length)];
            if (pick != exclude) {
                return pick;
            }
        }
    }

    /** 读档兜底：没有有效的 LikedProfession key 时随机补一个真实职业。 */
    private static ProfessionType randomLikedProfession(CompoundTag tag) {
        if (tag.contains("LikedProfession")) {
            ProfessionType p = ProfessionType.byName(tag.getString("LikedProfession"));
            if (p.isReal()) {
                return p;
            }
        }
        return randomRealProfession(net.minecraft.util.RandomSource.create());
    }

    /** 读档兜底：没有有效的 DislikedProfession key 时随机补一个（与喜欢互斥）。 */
    private static ProfessionType randomDislikedProfession(CompoundTag tag) {
        if (tag.contains("DislikedProfession")) {
            ProfessionType p = ProfessionType.byName(tag.getString("DislikedProfession"));
            if (p.isReal()) {
                return p;
            }
        }
        ProfessionType liked = tag.contains("LikedProfession")
                ? ProfessionType.byName(tag.getString("LikedProfession")) : null;
        return randomRealProfession(net.minecraft.util.RandomSource.create(),
                liked != null && liked.isReal() ? liked : null);
    }

    /** 读档兜底所在区域之后的辅助：见上方。 */

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

    // ==========================================================
    //  with-方法：生成修改后的副本
    // ==========================================================

    public ResidentProfile withHunger(int value) {
        return new ResidentProfile(clamp(value, 0, MAX_HUNGER), mood, profession, xp,
                hobbyFood, likedProfession, dislikedProfession, townId, homePos,
                housingZoneId, housingAssignedAt, this.skills, this.perks);
    }

    public ResidentProfile withMood(int value) {
        return new ResidentProfile(hunger, clamp(value, 0, MAX_MOOD), profession, xp,
                hobbyFood, likedProfession, dislikedProfession, townId, homePos,
                housingZoneId, housingAssignedAt, this.skills, this.perks);
    }

    public ResidentProfile withHobbyFood(Hobbies.Food value) {
        return new ResidentProfile(hunger, mood, profession, xp,
                value, likedProfession, dislikedProfession, townId, homePos,
                housingZoneId, housingAssignedAt, this.skills, this.perks);
    }

    public ResidentProfile withLikedProfession(ProfessionType type) {
        return new ResidentProfile(hunger, mood, profession, xp,
                hobbyFood, type, dislikedProfession, townId, homePos,
                housingZoneId, housingAssignedAt, this.skills, this.perks);
    }

    public ResidentProfile withDislikedProfession(ProfessionType type) {
        return new ResidentProfile(hunger, mood, profession, xp,
                hobbyFood, likedProfession, type, townId, homePos,
                housingZoneId, housingAssignedAt, this.skills, this.perks);
    }

    /**
     * 重摇 profile 侧偏好：喜欢的食物 + 职业好恶（互斥对）。
     *
     * <p>调试命令 {@code /bmfstown reroll} 用。这三样存在 {@link ResidentProfile}
     * 而不是 MoodState，旧 reroll 只重摇 MoodState 侧（方块/动物/颜色/音乐/
     * 天气/生物/性格），职业怎么摇都不变——用户实测点名后补齐。
     */
    public ResidentProfile rerollPreferences(RandomSource random) {
        ProfessionType liked = randomRealProfession(random);
        ProfessionType disliked = randomRealProfession(random, liked);
        return withHobbyFood(Hobbies.Food.random(random))
                .withLikedProfession(liked)
                .withDislikedProfession(disliked);
    }

    public ResidentProfile withProfession(ProfessionType type) {
        // 换专长时经验清零：不同专长的经验不可迁移（见需求文档 Q12）
        return new ResidentProfile(hunger, mood, type, 0,
                hobbyFood, likedProfession, dislikedProfession, townId, homePos,
                housingZoneId, housingAssignedAt, this.skills, this.perks);
    }

    public ResidentProfile withXp(int value) {
        return new ResidentProfile(hunger, mood, profession, Math.max(0, value),
                hobbyFood, likedProfession, dislikedProfession, townId, homePos,
                housingZoneId, housingAssignedAt, this.skills, this.perks);
    }

    public ResidentProfile withTownId(UUID value) {
        return new ResidentProfile(hunger, mood, profession, xp,
                hobbyFood, likedProfession, dislikedProfession, value, homePos,
                housingZoneId, housingAssignedAt, this.skills, this.perks);
    }

    public ResidentProfile withHomePos(BlockPos value) {
        return new ResidentProfile(hunger, mood, profession, xp,
                hobbyFood, likedProfession, dislikedProfession, townId, value,
                housingZoneId, housingAssignedAt, this.skills, this.perks);
    }

    /**
     * 绑定/解绑住宅区。
     *
     * <p>解绑（传 null）时顺手把分配时间也清零，避免残留的旧时间戳
     * 影响下次超员踢人时的先后排序。
     */
    public ResidentProfile withHousingZoneId(UUID value) {
        return new ResidentProfile(hunger, mood, profession, xp,
                hobbyFood, likedProfession, dislikedProfession, townId, homePos,
                value, value == null ? 0L : this.housingAssignedAt, this.skills, this.perks);
    }

    /** 只改分配时间戳（一般由 {@link #withHousingZoneId} 配合使用）。 */
    public ResidentProfile withHousingAssignedAt(long value) {
        return new ResidentProfile(hunger, mood, profession, xp,
                hobbyFood, likedProfession, dislikedProfession, townId, homePos,
                housingZoneId, value, this.skills, this.perks);
    }

    /** 一次设置住宅绑定 + 分配时间（服务端分配入口用）。 */
    public ResidentProfile withHousing(UUID zoneId, long assignedAt) {
        return new ResidentProfile(hunger, mood, profession, xp,
                hobbyFood, likedProfession, dislikedProfession, townId, homePos,
                zoneId, zoneId == null ? 0L : assignedAt, this.skills, this.perks);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    // ==========================================================
    //  技能（v0.8：每个技能一条独立经验条，干中学）
    // ==========================================================

    /** 某个技能的累计经验（没涨过返回 0）。 */
    public int skillXp(SkillType type) {
        return this.skills.getOrDefault(type, 0);
    }

    /** 某个技能的当前段位（0 生疏 ~ 9 神话，见 {@link SkillTiers}）。 */
    public int skillTier(SkillType type) {
        return SkillTiers.tierOf(this.skillXp(type));
    }

    /** 增加技能经验并返回新档案（amount 只增不减，负数按 0 处理）。 */
    public ResidentProfile addSkillXp(SkillType type, int amount) {
        if (amount <= 0) {
            return this;
        }
        return withSkillXp(type, this.skillXp(type) + amount);
    }

    /** 直接设置某个技能的经验（一般只有初始化/命令用；常规成长走 {@link #addSkillXp}）。 */
    public ResidentProfile withSkillXp(SkillType type, int value) {
        Map<SkillType, Integer> copy = new HashMap<>(this.skills);
        copy.put(type, Math.max(0, value));
        return new ResidentProfile(hunger, mood, profession, xp,
                hobbyFood, likedProfession, dislikedProfession, townId, homePos,
                housingZoneId, housingAssignedAt, copy, this.perks);
    }

    /** 选择一个 perk（去重）。perk 系统占位——当前没有调用方，数据层先行。 */
    public ResidentProfile withPerk(String perkId) {
        if (perkId == null || this.perks.contains(perkId)) {
            return this;
        }
        List<String> copy = new ArrayList<>(this.perks);
        copy.add(perkId);
        return new ResidentProfile(hunger, mood, profession, xp,
                hobbyFood, likedProfession, dislikedProfession, townId, homePos,
                housingZoneId, housingAssignedAt, this.skills, copy);
    }

    // ---- perk 分支选择（v0.5：每节点 2 选 1，存 "节点:分支" 字符串） ----

    /**
     * 选定某节点的一个分支，覆盖同节点的旧选择（免费改选，不锁死）。
     *
     * <p>存储格式沿用 {@link #perks} 列表：每条为 {@code "节点id:分支id"}，
     * 例如 {@code "warrior.adept:regen"}。<b>老存档不炸</b>——旧数据里没有
     * 带冒号的条目，{@link #perkChoice} 查不到就返回 null，天然兼容。
     */
    public ResidentProfile withPerkChoice(String node, String branch) {
        String prefix = node + ":";
        List<String> copy = new ArrayList<>();
        for (String p : this.perks) {
            if (!p.startsWith(prefix)) {
                copy.add(p);
            }
        }
        copy.add(prefix + branch);
        return new ResidentProfile(hunger, mood, profession, xp,
                hobbyFood, likedProfession, dislikedProfession, townId, homePos,
                housingZoneId, housingAssignedAt, this.skills, List.copyOf(copy));
    }

    /** 某节点当前选中的分支 id；未选返回 null。 */
    public String perkChoice(String node) {
        String prefix = node + ":";
        for (String p : this.perks) {
            if (p.startsWith(prefix)) {
                return p.substring(prefix.length());
            }
        }
        return null;
    }

    // ---- NBT 辅助：老存档没有 Skills/Perks 时给空值 ----

    private static Map<SkillType, Integer> loadSkills(CompoundTag tag) {
        Map<SkillType, Integer> map = new HashMap<>();
        if (tag.contains("Skills")) {
            CompoundTag skillTag = tag.getCompound("Skills");
            for (SkillType type : SkillType.values()) {
                if (skillTag.contains(type.getSerializedName())) {
                    map.put(type, Math.max(0, skillTag.getInt(type.getSerializedName())));
                }
            }
        }
        return map;
    }

    private static List<String> loadPerks(CompoundTag tag) {
        List<String> list = new ArrayList<>();
        if (tag.contains("Perks")) {
            net.minecraft.nbt.ListTag perkTag = tag.getList("Perks", net.minecraft.nbt.Tag.TAG_STRING);
            for (int i = 0; i < perkTag.size(); i++) {
                list.add(perkTag.getString(i));
            }
        }
        return list;
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
        // v0.7：职业好恶。老存档没有这两个 key 时走 byName 兜底（见 load）
        tag.putString("LikedProfession", this.likedProfession.getSerializedName());
        tag.putString("DislikedProfession", this.dislikedProfession.getSerializedName());

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

        // 技能经验（v0.8）：一个复合标签，key = 技能小写名。
        // 没涨过的技能不写，老存档/新档都兼容。
        CompoundTag skillTag = new CompoundTag();
        for (Map.Entry<SkillType, Integer> e : this.skills.entrySet()) {
            if (e.getValue() > 0) {
                skillTag.putInt(e.getKey().getSerializedName(), e.getValue());
            }
        }
        if (!skillTag.isEmpty()) {
            tag.put("Skills", skillTag);
        }
        // perk（占位）：只存已选 id，perk 系统上线后语义不变
        if (!this.perks.isEmpty()) {
            net.minecraft.nbt.ListTag perkTag = new net.minecraft.nbt.ListTag();
            for (String id : this.perks) {
                perkTag.add(net.minecraft.nbt.StringTag.valueOf(id));
            }
            tag.put("Perks", perkTag);
        }
        return tag;
    }

    /**
     * 从 NBT 读取。
     *
     * <p>所有字段都用「缺省 + 兜底」策略：老存档没有这个 key 时，
     * 不会崩，而是给一个合理的默认值。这是存档兼容的基本功。
     *
     * <p>v0.7 起旧的 HobbyHomeItem / HobbyEnvironment / HobbyAmbience /
     * DislikeItem / DislikeEnvironment / Affection key 不再读取——
     * 那些字段已随偏好体系重写移除，旧值没有迁移价值。
     */
    public static ResidentProfile load(CompoundTag tag) {
        return new ResidentProfile(
                tag.contains("Hunger") ? tag.getInt("Hunger") : MAX_HUNGER,
                tag.contains("Mood") ? tag.getInt("Mood") : Config.INITIAL_MOOD.get(),
                ProfessionType.byName(tag.getString("Profession")),
                tag.getInt("Xp"),
                Hobbies.Food.byName(tag.getString("HobbyFood")),
                // 老存档没有职业好恶：随机补一对（好恶互斥由生成逻辑保证）
                randomLikedProfession(tag),
                randomDislikedProfession(tag),
                // hasUUID 在 key 不存在时也返回 false，所以不用先 contains
                tag.hasUUID("TownId") ? tag.getUUID("TownId") : null,
                tag.contains("HomePos") ? BlockPos.of(tag.getLong("HomePos")) : null,
                tag.hasUUID("HousingZoneId") ? tag.getUUID("HousingZoneId") : null,
                tag.getLong("HousingAssignedAt"),
                loadSkills(tag),
                loadPerks(tag)
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
}
