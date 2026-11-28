package com.bmf.bmfstown.entity.domain;

import com.bmf.bmfstown.entity.profile.ProfessionType;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.UUID;

/**
 * 身份域：这个镇民"是谁、属于谁"。
 *
 * <p>六域重构（v0.8.2）后 ResidentProfile 解散，归属四件套与职业落在这里。
 * 变动极少——只有建镇入镇、分房退房、指派职业时写。
 *
 * <p><b>设计约束</b>：本类只存数据不存逻辑；职业怎么"干活"是 Goal 层的事，
 * 这里只有"他是什么职业"这一个事实。
 */
public final class Identity {

    /** 职业。NONE = 无业。指派职业在面板/命令改这里。 */
    private ProfessionType profession = ProfessionType.NONE;

    /** 所属城镇；null = 无城镇（野镇民）。 */
    private UUID townId;
    /** 绑定的床（HEAD 坐标）；null = 没绑床。 */
    private BlockPos homePos;
    /** 绑定的住宅区 ID；null = 未分配。 */
    private UUID housingZoneId;
    /** 分配住宅的时间戳（毫秒）——床数变少时据此踢出"最新加入的"。 */
    private long housingAssignedAt;

    /**
     * 过客标记（决策 #61）：野外随机刷出的未入籍镇民 = true。
     *
     * <p><b>判据是本标记而非 {@code townId == null}</b>（用户明确纠正的坑）：
     * 已领养镇民即使被手动清成无归属（退镇/指令），标记早已删除，绝不会
     * 被当过客刷掉——除死外不自动消失。标记是<b>单向</b>的：只有
     * {@link #clearWanderer()}（赋归属时）能删，{@link #clearOwnership()}
     * 不碰它。
     */
    private boolean wanderer;

    /** 成为过客时的世界时间（dayTime）——三天期限的计时基准。 */
    private long wandererSince;

    // ---- 年龄与寿命（2026-11-28，决策 #139/#140）----

    /**
     * 寿命（游戏日）。-1 = 老档未生成。
     *
     * <p><b>生成时抽一次，永不重抽。</b>重抽会让"他 68 岁那年死"变成随机翻牌，
     * 而且老档每次读档都变一个死期。这是"设定值"不是"节奏值"。
     *
     * <p>这里<b>可以</b>用 {@code -1}：寿命永远是正数投影（{@code 寿命年×96}），
     * 撞不到负数。生日不同——那是从 today 往前推的，能落进负数区间（见 {@link #birthDay}）。
     */
    private long lifespanDays = -1L;

    /**
     * 生日（世界绝对天数）。{@link AgingRolls#UNKNOWN_DAY} = 未生成。
     *
     * <p><b>这是投影，不是出生事件</b>（见 {@link AgingRolls}）：
     * 居民不是出生的，是"出现"的；这个生日只是为了让他有个"29 岁"的来历。
     *
     * <p><b>⚠ 它可以是负数</b>：投影是 {@code today − (age×96 + offset)}，
     * 开档早期一个 29 岁的人必然落在第 0 天之前。负数<b>不是"未知"</b>——
     * 初版用 {@code < 0} 判未知，导致所有居民的年龄/享年/履历天数全被吞掉
     * （2026-11-28 用户实测"年龄什么的要啥啥没有"）。判未知只认
     * {@link AgingRolls#UNKNOWN_DAY}。
     *
     * <p>年内偏移 {@code dayOffset} <b>不单独存</b>——它就在这个数的低几位里
     * （{@code floorMod(birthDay, DAYS_PER_YEAR)}），存两份就是两个真相源。
     */
    private long birthDay = AgingRolls.UNKNOWN_DAY;

    /**
     * 入镇日（世界绝对天数）。{@link AgingRolls#UNKNOWN_DAY} = 老档补不出 / 从未入镇。
     *
     * <p>和 {@link #birthDay} 的性质<b>正好相反</b>：生日是投影（能反推），
     * 入镇日是<b>真实事件</b>——谁也推不出他哪天走到镇上的。所以必须存。
     *
     * <p><b>为什么叫"入镇"不叫"出现"</b>：过客在野外能游荡 2~8 天才被领养，
     * 按刷出日算，履历上会凭空多出他不在镇上的那些天（§3.8.6）。
     */
    private long arrivalDay = AgingRolls.UNKNOWN_DAY;

    // ---- 读 ----

    public ProfessionType profession() {
        return this.profession;
    }

    public UUID townId() {
        return this.townId;
    }

    public BlockPos homePos() {
        return this.homePos;
    }

    public UUID housingZoneId() {
        return this.housingZoneId;
    }

    public long housingAssignedAt() {
        return this.housingAssignedAt;
    }

    /** 是否还是未入籍的过客。 */
    public boolean isWanderer() {
        return this.wanderer;
    }

    /** 成为过客时的世界时间（dayTime）。 */
    public long wandererSince() {
        return this.wandererSince;
    }

    // ---- 年龄与寿命（读） ----

    /** 寿命（游戏日）；{@link AgingRolls#UNKNOWN_DAY} = 未生成。 */
    public long lifespanDays() {
        return this.lifespanDays;
    }

    /** 生日（世界绝对天数，可负）；{@link AgingRolls#UNKNOWN_DAY} = 未生成。 */
    public long birthDay() {
        return this.birthDay;
    }

    /** 入镇日（世界绝对天数）；{@link AgingRolls#UNKNOWN_DAY} = 未知/从未入镇。 */
    public long arrivalDay() {
        return this.arrivalDay;
    }

    /** 当前整岁（<b>到生日才 +1</b>）。未生成时返回 -1。 */
    public int ageYears(long today) {
        return AgingRolls.ageAt(today, this.birthDay);
    }

    /** 是否已经抽过年龄与寿命（幂等闸门用）。 */
    public boolean hasAging() {
        return this.lifespanDays > 0 && this.birthDay != AgingRolls.UNKNOWN_DAY;
    }

    // ---- 写（调用方负责随后 syncClient） ----

    public void setProfession(ProfessionType type) {
        this.profession = type == null ? ProfessionType.NONE : type;
    }

    public void setTownId(UUID value) {
        this.townId = value;
    }

    public void setHomePos(BlockPos value) {
        this.homePos = value;
    }

    /** 绑定/解绑住宅区。解绑（传 null）时顺手清分配时间戳。 */
    public void setHousingZoneId(UUID value) {
        this.housingZoneId = value;
        if (value == null) {
            this.housingAssignedAt = 0L;
        }
    }

    public void setHousingAssignedAt(long value) {
        this.housingAssignedAt = value;
    }

    /** 一次设置住宅绑定 + 分配时间（服务端分配入口用）。 */
    public void setHousing(UUID zoneId, long assignedAt) {
        this.housingZoneId = zoneId;
        this.housingAssignedAt = zoneId == null ? 0L : assignedAt;
    }

    /** 打过客标记（野外刷出时调用一次，since = 当时 dayTime）。 */
    public void markWanderer(long since) {
        this.wanderer = true;
        this.wandererSince = since;
    }

    /** 删除过客标记（领养赋归属时调用；单向操作，没有任何方法能恢复）。 */
    public void clearWanderer() {
        this.wanderer = false;
        this.wandererSince = 0L;
    }

    /** 退镇：清空全部归属（镇被删/被踢时调用）。 */
    public void clearOwnership() {
        this.townId = null;
        this.homePos = null;
        this.housingZoneId = null;
        this.housingAssignedAt = 0L;
    }

    // ---- 年龄与寿命（写） ----

    /**
     * 一次性写入年龄与寿命（<b>幂等</b>：已有值就不动）。
     *
     * <p><b>必须先抽寿命再抽年龄</b>：年龄的上限由寿命倒推（{@link AgingRolls#lifespanCapYears}），
     * 顺序反了就只能接受"刚来就要死"或者重抽寿命。
     *
     * <p><b>只能在服务端调</b>：客户端的 {@code RandomSource} 与服务端不同源，
     * 两边各抽一次就分叉。客户端每帧重建实体、不落档，所以不会写坏档，
     * 但面板会显示一个和服务端不一致的年龄——所以调用点必须有
     * {@code level instanceof ServerLevel} 的门。
     *
     * @param today 世界绝对天数（{@code TownCalendar.absoluteDay}）
     * @return <b>本次是否真的生成了</b>（原本没有、这次补上）。调用方据此
     *         决定要不要 {@code syncClient()}——不推的话，客户端快照里
     *         的 {@code ageYears} 会一直是 -1，面板上就是不画年龄那一行，
     *         直到下一次别的原因触发同步（2026-11-28 用户实测"年龄要啥啥没有"）。
     */
    public boolean ensureAging(long today, net.minecraft.util.RandomSource random) {
        if (hasAging()) {
            return false;
        }
        double lifespanYears = AgingRolls.rollLifespanYears(random);
        int age = AgingRolls.rollAgeYears(random, lifespanYears);
        // 年内偏移：独立抽（坑78）；上限由寿命倒推（极短寿时逼让）
        int dayOffset = AgingRolls.rollDayOffset(random,
                AgingRolls.maxDayOffset(lifespanYears, age));
        this.lifespanDays = (long) (lifespanYears * com.bmf.bmfstown.calendar.TownCalendar.DAYS_PER_YEAR);
        this.birthDay = AgingRolls.birthDayFrom(today, age, dayOffset);
        return true;
    }

    /**
     * 记录入镇（<b>只写一次</b>，之后换镇不重写）。
     *
     * <p>唯一调用点：{@code Wanderers.onAdopted}（过人被领养的那一刻）。
     * 刷怪蛋/命令刷出的居民不走那里，{@code arrivalDay} 会留在 -1——
     * 这时履历显示"未知"、碑文不写"在此 N 年"，<b>不许拿 today 冒充</b>
     * （会让所有老居民变成"享年 47 岁、在此 0 年"）。
     */
    public void markArrival(long today) {
        if (this.arrivalDay == AgingRolls.UNKNOWN_DAY) {
            this.arrivalDay = today;
        }
    }

    /**
     * 从过客标记里把入镇日补出来（{@code clearWanderer()} <b>之前</b>调用）。
     *
     * <p><b>为什么不用现成的 {@code wandererSince}</b>：它记的是 dayTime
     * （{@code Wanderers:206} 就是拿 dayTime 和 {@code 24000×天数} 比），
     * 而 {@code arrivalDay} 要的是天。这里统一换算，别在两个口径之间来回。
     *
     * <p><b>必须在 {@code clearWanderer()} 之前</b>：那句会把
     * {@code wandererSince} 清零，顺序反了就拿不到时间了（用例 32 守）。
     */
    public void markArrivalFromWanderer() {
        if (this.arrivalDay == AgingRolls.UNKNOWN_DAY && this.wandererSince > 0) {
            this.arrivalDay = this.wandererSince / AgingRolls.TICKS_PER_DAY;
        }
    }

    /** 实例级读回（GATTACA 快照复原用）。 */
    public void loadFrom(CompoundTag tag) {
        Identity loaded = load(tag, false);
        this.profession = loaded.profession;
        this.townId = loaded.townId;
        this.homePos = loaded.homePos;
        this.housingZoneId = loaded.housingZoneId;
        this.housingAssignedAt = loaded.housingAssignedAt;
        this.wanderer = loaded.wanderer;
        this.wandererSince = loaded.wandererSince;
        this.lifespanDays = loaded.lifespanDays;
        this.birthDay = loaded.birthDay;
        this.arrivalDay = loaded.arrivalDay;
    }

    // ==========================================================
    //  序列化
    // ==========================================================

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Profession", this.profession.getSerializedName());
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
        tag.putBoolean("Wanderer", this.wanderer);
        tag.putLong("WandererSince", this.wandererSince);
        tag.putLong("LifespanDays", this.lifespanDays);
        tag.putLong("BirthDay", this.birthDay);
        tag.putLong("ArrivalDay", this.arrivalDay);
        return tag;
    }

    /**
     * 从 NBT 读回。
     *
     * @param legacy true = 从旧版 {@code ResidentProfile} 子标签读（键名相同，
     *               只是外壳不同），兼容老存档
     */
    public static Identity load(CompoundTag tag, boolean legacy) {
        Identity id = new Identity();
        id.profession = ProfessionType.byName(tag.getString("Profession"));
        id.townId = tag.hasUUID("TownId") ? tag.getUUID("TownId") : null;
        id.homePos = tag.contains("HomePos", Tag.TAG_ANY_NUMERIC)
                ? BlockPos.of(tag.getLong("HomePos")) : null;
        id.housingZoneId = tag.hasUUID("HousingZoneId") ? tag.getUUID("HousingZoneId") : null;
        id.housingAssignedAt = tag.getLong("HousingAssignedAt");
        id.wanderer = tag.getBoolean("Wanderer");
        id.wandererSince = tag.getLong("WandererSince");
        // ---- 三个日期键：缺键 / 旧版 -1 一律读成"未知"（2026-11-28）----
        //
        // ⚠ <b>不能只做 {@code contains} 判存在</b>：初版缺键时落的默认值就是 -1，
        // 那些档里这个 -1 会原样留在 NBT 里，读回来若当成"合法生日"，
        // 就会算出一个"活了 20 亿天"的居民。所以旧哨兵要认。
        //
        // ⚠ <b>也不能统一把负数读成未知</b>：生日投影本来就可能为负
        // （开档第 12 天的 29 岁居民 = -2812）。只有"恰好 -1"是历史脏值——
        // 真正的 -1 生日意味着"今天他正好出生"，那是乱纪元才会发生的事，
        // 现实里不存在；宁可把它当未知，也不要留一个人天天过生日。
        id.lifespanDays = readLifespan(tag);
        id.birthDay = readDate(tag, "BirthDay");
        id.arrivalDay = readDate(tag, "ArrivalDay");
        return id;
    }

    /**
     * 读寿命：缺键是 {@code -1}。
     *
     * <p><b>寿命不参与 {@link AgingRolls#UNKNOWN_DAY} 那套</b>：它永远是
     * 正数投影（{@code 寿命年 × 96}），撞不到负数区间，所以用 -1 当未知
     * 既安全又与 {@code hasAging()} 的 {@code lifespanDays > 0} 判据天然一致。
     * 把两类字段塞进同一个 readDate 会让寿命也变成 Long.MIN_VALUE，
     * 于是 {@code hasAging()} 永远为真、{@code settleOldAge} 拿它算死期会溢出。
     */
    private static long readLifespan(CompoundTag tag) {
        if (!tag.contains("LifespanDays", Tag.TAG_ANY_NUMERIC)) {
            return -1L;
        }
        long v = tag.getLong("LifespanDays");
        // 初版缺键落 -1；读到非正数一律当"未生成"
        return v > 0 ? v : -1L;
    }

    /**
     * 读一个"日期"型 long 键，未知统一成 {@link AgingRolls#UNKNOWN_DAY}。
     *
     * <h2>为什么这里只把<b>恰好 -1</b> 当脏值</h2>
     * 初版（2026-11-28 上午）缺键落的默认值就是 -1，那些档里这个 -1 会原样
     * 留在 NBT 里。换哨兵后若原样读回来，会被当成合法生日 ⇒ 算出"活了天文数字天"。
     *
     * <h2>为什么不能统一把负数当未知</h2>
     * 生日是<b>投影</b>（{@code today − age×96}），开档早期必然为负——
     * 一个世界第 12 天的 29 岁居民生日是 -2812，那是<b>合法值</b>。
     * 统一按负数判会把全镇人的年龄吞掉（这正是本轮要修的 bug）。
     */
    private static long readDate(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_ANY_NUMERIC)) {
            return AgingRolls.UNKNOWN_DAY;
        }
        long v = tag.getLong(key);
        return v == -1L ? AgingRolls.UNKNOWN_DAY : v;
    }
}
