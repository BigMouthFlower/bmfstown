package com.bmf.bmfstown.town;

import com.bmf.bmfstown.calendar.TownCalendar;
import com.bmf.bmfstown.entity.Resident;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 死者名册 + 碑位表（2026-11-28，决策 #139~#141）。
 *
 * <h2>「名册」是死人表，不是人口册</h2>
 * 这个词容易读成"全镇人口登记"，所以先说死：<b>这里只收死掉的人</b>。
 * 活着的居民在 {@code Resident} 实体上，不在这里。墓地（{@code TownWandMode.GRAVEYARD}
 * 选区）是另一件事——它只是"玩家划的一块地"，决定墓碑能不能立。
 *
 * <h2>两个表，各管一件事（这是本类的骨架）</h2>
 * <ul>
 *   <li>{@link #graves}：<b>谁死了</b>（UUID → 记录，按死亡顺序）。写一次，之后只有
 *       {@code deeds} 可追加。<b>永远不删。</b></li>
 *   <li>{@link #slots}：<b>他现在葬在哪</b>（方块位置 → UUID）。
 *       碑被挖掉就删这一条，人还留在名册里。</li>
 * </ul>
 *
 * <p><b>这个分离是"碑可后立、可挖"的全部实现。</b>人死了先进 {@code graves}
 * （这一刻他的实体还在，名字/职业/统计都读得到）；玩家之后才立碑
 * （那一刻才写 {@code slots}）。碑挖掉 = 删 {@code slots} 一条，
 * <b>{@code graves} 一个字不动</b> ⇒ 他回到"待葬"，可以再立。
 *
 * <h2>写入必须在实体消失前</h2>
 * 名字、职业、段位、统计全在 {@code Resident} 实体上，<b>实体一移除就永远查不到</b>
 * （本项目没有任何逝者缓存）。所以 {@link #record} 必须挂在
 * {@code LivingDeathEvent} 里——那时实体还在。拖到 {@code afterDeath}
 * 或下一 tick，名字就是空的（用例 12/21 守这个）。
 */
public final class GraveyardData extends SavedData {

    public static final String DATA_NAME = "bmfstown_graves";

    /**
     * 一条逝者记录。死亡瞬间<b>整表抽干</b>，之后只有 {@code deeds} 可追加。
     *
     * <p>这里每个字段都必须能在"死亡那一刻"读到——凡是读不到的（比如关系账、
     * 外镇出身）就不该进这个结构，否则会有一半永远是空值。
     */
    public record Grave(UUID id,             // 逝者实体 UUID（防重复写入）
                        String name,
                        String profession,   // 职业 id
                        @Nullable UUID townId, // 所属城镇；null = 不属于任何镇（来访者等）
                        long deathDay,       // 死亡日（世界绝对天数）
                        String cause,        // old_age / killed / other（仅命令脚本用的粗分类）
                        String damageId,     // 原版伤害类型 msgId（"mob"/"fall"/"lava"…）；"" = 老档
                        @Nullable net.minecraft.network.chat.Component killer, // 凶手显示名；null = 无凶手/老档
                        long birthDay,       // 生日投影；AgingRolls.UNKNOWN_DAY = 未知（可为负！）
                        long lifespanDays,   // 他的寿命
                        String titleKey,     // 段位头衔 key；"" = 无
                        int skillLevel,      // 段位 0~10
                        long arrivalDay,     // 入镇日；AgingRolls.UNKNOWN_DAY = 未知（老档/刷怪蛋）
                        List<Deed> deeds,    // "大事"；第一版恒空
                        Map<String, Integer> stats) { // 一生统计；空表 = 老档

        /**
         * 享年（镇民历年）。生日未知返回 -1。
         *
         * <p><b>判据是 {@link com.bmf.bmfstown.entity.domain.AgingRolls#UNKNOWN_DAY}，
         * 不是 {@code < 0}</b>：生日是投影，开档早期抽到的人必然投影到第 0 天之前
         * （负数）。初版用 {@code < 0} 判未知 ⇒ 全镇居民碑上都只剩一行死因
         * （2026-11-28 用户实测）。
         */
        public int ageYears() {
            if (this.birthDay == com.bmf.bmfstown.entity.domain.AgingRolls.UNKNOWN_DAY) {
                return -1;
            }
            return (int) ((this.deathDay - this.birthDay) / TownCalendar.DAYS_PER_YEAR);
        }

        /**
         * 在镇几年。{@code arrivalDay} 未知返回 -1。
         *
         * <p><b>老档绝不许拿 today 冒充</b>：那会让所有老居民都变成
         * "享年 47 岁、在此 0 年"。未知就整行不写。
         */
        public int stayYears() {
            if (this.arrivalDay == com.bmf.bmfstown.entity.domain.AgingRolls.UNKNOWN_DAY) {
                return -1;
            }
            return (int) ((this.deathDay - this.arrivalDay) / TownCalendar.DAYS_PER_YEAR);
        }

        /** 在镇天数（碑文精确口径用；未知返回 -1）。 */
        public int stayDays() {
            if (this.arrivalDay == com.bmf.bmfstown.entity.domain.AgingRolls.UNKNOWN_DAY) {
                return -1;
            }
            return (int) (this.deathDay - this.arrivalDay);
        }

        /** 某项统计（没有返回 0）。 */
        public int stat(String key) {
            return this.stats.getOrDefault(key, 0);
        }

        /**
         * 碑上的死因描述——<b>直接复用原版的死亡消息</b>。
         *
         * <h2>为什么不用自己造的死因词（2026-11-28 用户打回）</h2>
         * 用户原话："什么叫横死，原版对于生物不同死法是有描述的，唯一需要新加的
         * 老死的那一批的人的死法，何况我们都没触发那一部分，这么充沛的原版资源，
         * 你来俩字横死。"
         *
         * 原版 {@code death.attack.*} 有 87 条，把"怎么死的 + 谁干的"写全了
         * （"被僵尸杀死" / "摔得太狠了" / "试图在岩浆里游泳"）。
         * 碑文正是最该说清这件事的地方，把它压成"横死/意外"两个词是白白扔掉素材。
         *
         * <h2>为什么这里能拼原版键</h2>
         * 原版死亡消息是 {@code Component.translatable("death.attack." + msgId,
         * 死者名, 凶手名)}。我们不重生成本地化字符串，只拼<b>同一个键</b>，
         * 由客户端渲染成玩家自己的语言——与游戏里看到的死亡提示一模一样。
         *
         * <p>盘古 {@code genericKill}（寿终专用，见 {@code Status.settleOldAge}）
         * 走原版那句"s lain"不对味，所以寿终单独给一句 mod 自己的。
         * 老档没有 {@code damageId}（本字段是后加的）时退回粗分类。
         */
        public net.minecraft.network.chat.Component deathLine() {
            // 寿终：原版没有这个伤害类型，只有"被杀"，得我们补一句
            if ("old_age".equals(this.cause)) {
                return net.minecraft.network.chat.Component.translatable(
                        "grave.bmfstown.death.old_age");
            }
            if (this.damageId != null && !this.damageId.isEmpty()) {
                // 与原版 getLocalizedDeathMessage 同一套键：有凶手就带凶手名
                var args = this.killer == null
                        ? new Object[]{net.minecraft.network.chat.Component.literal(this.name)}
                        : new Object[]{net.minecraft.network.chat.Component.literal(this.name),
                                this.killer};
                return net.minecraft.network.chat.Component.translatable(
                        "death.attack." + this.damageId, args);
            }
            // 老档（#147 之前写下的名册没有 damageId）：只能给粗分类
            return net.minecraft.network.chat.Component.translatable(
                    "death.bmfstown.cause." + this.cause);
        }
    }

    /** 一件"大事"。key 是 lang key，arg 是参数，day 是发生日。第一版表恒空。 */
    public record Deed(String key, String arg, int day) {
    }

    /** 名册：UUID → 记录，按死亡顺序（LinkedHashMap 保序）。 */
    private final Map<UUID, Grave> graves = new LinkedHashMap<>();

    /**
     * 碑位表：方块位置 → 逝者 UUID。
     *
     * <p><b>不在这里的 pos 就是没有碑</b>；<b>不在这里的 uuid 就是待葬</b>。
     * 清位挂 {@code onRemove}（坑80）——{@code playerWillDestroy} 只认玩家挖，
     * 爆炸/活塞/{@code /setblock} 全漏，漏清会让新碑顶着上一个死人的名字。
     */
    private final Map<BlockPos, UUID> slots = new LinkedHashMap<>();

    public static GraveyardData get(ServerLevel anyLevel) {
        return anyLevel.getServer().overworld().getDataStorage()
                .computeIfAbsent(new Factory<>(GraveyardData::new, GraveyardData::load),
                        DATA_NAME);
    }

    // ==========================================================
    //  写入
    // ==========================================================

    /**
     * 记录一个死者（<b>幂等</b>：同 UUID 只写一次）。
     *
     * <p>必须在实体的 {@code LivingDeathEvent} 里调——那时名字/职业/统计都还在。
     *
     * @param sourceMsgId 伤害源的 {@code getMsgId()}（判死因用）
     */
    public void record(Resident dead, ServerLevel level,
            net.minecraft.world.damagesource.DamageSource source) {
        UUID id = dead.getUUID();
        if (this.graves.containsKey(id)) {
            return;
        }
        var identity = dead.getIdentity();
        long today = TownCalendar.absoluteDay(level);

        // ---- 一生统计：整表抄走（人一消失就查不到了）----
        Map<String, Integer> stats = new LinkedHashMap<>();
        dead.getLedger().view().forEach((k, v) -> {
            if (v != null && v > 0) {
                stats.put(k, v);
            }
        });
        // 读过多少本：真相源在 KnowledgeState（不在 ledger 里），单独补
        int read = dead.getKnowledge().totalBooksRead();
        if (read > 0) {
            stats.put(com.bmf.bmfstown.entity.domain.ResidentLedger.Keys.BOOKS_READ, read);
        }
        // 天数：活着时是"算的"（投影），死后必须"存"——名册里没有 today 可言。
        // ⚠ 判据是 UNKNOWN_DAY 而不是 >= 0：生日投影可以是负数（开档早期），
        // 用 >=0 判会把这些人的"活了几天"整项丢掉（2026-11-28 实测 bug）。
        long unknown = com.bmf.bmfstown.entity.domain.AgingRolls.UNKNOWN_DAY;
        if (identity.birthDay() != unknown) {
            stats.put(com.bmf.bmfstown.entity.domain.ResidentLedger.Keys.DAYS_ALIVE,
                    (int) (today - identity.birthDay()));
        }
        if (identity.arrivalDay() != unknown) {
            stats.put(com.bmf.bmfstown.entity.domain.ResidentLedger.Keys.DAYS_IN_TOWN,
                    (int) (today - identity.arrivalDay()));
        }

        this.graves.put(id, new Grave(
                id,
                dead.getName().getString(),
                identity.profession().getSerializedName(),
                identity.townId(),
                today,
                classify(source, dead),
                // 原版伤害类型：碑文靠它翻出"被僵尸杀死"这类原句（#147）
                source == null ? "" : source.getMsgId(),
                // 凶手显示名：可能为空（摔死/淹死）
                killerOf(source),
                identity.birthDay(),
                identity.lifespanDays(),
                dead.getStatus().titleKey(dead),
                dead.getStatus().level(dead),
                identity.arrivalDay(),
                new ArrayList<>(),
                stats));
        this.setDirty();
    }

    /**
     * 凶手显示名（没有凶手返回 null）。
     *
     * <p><b>为什么存 Component 而不是 String</b>：凶手可能是玩家（自定义名）、
     * 可能是生物（原版译名），两者的本地化都该由客户端做。
     * 服务端只存"是哪个实体的名字组件"，不提前 getString 锁死语言。
     *
     * <p><b>为什么优先 causingEntity</b>：与原版
     * {@code DamageSource.getLocalizedDeathMessage} 口径一致——
     * 骷髅射的箭，凶手是骷髅不是箭。
     */
    private static @Nullable net.minecraft.network.chat.Component killerOf(
            net.minecraft.world.damagesource.DamageSource source) {
        if (source == null) {
            return null;
        }
        var who = source.getEntity() != null ? source.getEntity() : source.getDirectEntity();
        return who == null ? null : who.getDisplayName();
    }

    /**
     * 死因分类。<b>按伤害源判，不按"到寿了没有"反推。</b>
     *
     * <p>{@code genericKill} 是寿终专用（{@code Status.settleOldAge}），
     * 但 {@code /kill} 同源 ⇒ 后台命令杀的会被记成老死。这个误差接受
     * （后台命令不是玩法路径），比"另造一个伤害类型"便宜得多。
     *
     * <p><b>不能用"到寿了没有"反推</b>：一个 70 岁的人被僵尸打死时
     * 也"到寿了"，反推会把他记成老死——那就把玩家的战斗结果抹了。
     */
    private static String classify(net.minecraft.world.damagesource.DamageSource source,
            Resident dead) {
        String msgId = source == null ? "" : source.getMsgId();
        if ("genericKill".equals(msgId)) {
            return "old_age";
        }
        if (msgId.startsWith("mob") || msgId.startsWith("player")
                || msgId.startsWith("arrow")) {
            return "killed";
        }
        return "other";
    }

    // ==========================================================
    //  碑位
    // ==========================================================

    /** 立碑：绑定 pos → 逝者。 */
    public void bindSlot(BlockPos pos, UUID who) {
        this.slots.put(pos.immutable(), who);
        this.setDirty();
    }

    /** 清位：碑没了。人还留在名册里（他回到"待葬"）。 */
    public void clearSlot(BlockPos pos) {
        if (this.slots.remove(pos.immutable()) != null) {
            this.setDirty();
        }
    }

    /** 某块碑是谁的；没绑返回 null（无名碑）。 */
    @Nullable
    public Grave graveAt(BlockPos pos) {
        UUID who = this.slots.get(pos);
        return who == null ? null : this.graves.get(who);
    }

    @Nullable
    public Grave find(UUID id) {
        return this.graves.get(id);
    }

    /** 某个镇上所有逝者（按死亡顺序）。 */
    public List<Grave> ofTown(@Nullable UUID townId) {
        List<Grave> out = new ArrayList<>();
        for (Grave g : this.graves.values()) {
            if (townId == null ? g.townId() == null : townId.equals(g.townId())) {
                out.add(g);
            }
        }
        return out;
    }

    /**
     * 某个镇上还<b>没立碑</b>的人（刻碑选人名单的真相源，决策 #144）。
     *
     * <h2>为什么必须抽在这里，而不是在发送方拼过滤条件</h2>
     * 名单的"只列本镇"这条规则如果写在 {@code ModNetworking} 里，
     * <b>用例就只能自己再抄一遍同样的过滤</b>——那样用例测的是它自己抄的那份，
     * 真代码改错了它照样绿（坑72 的原始形态，本项目已经犯过两次）。
     * 抽到这里以后，生产与用例调的是同一个方法。
     *
     * <h2>townId == null 的含义</h2>
     * 表示"这块碑不在任何镇内"（野外立的碑）。此时只列<b>不属于任何镇</b>
     * 的逝者，<b>绝不列出所有镇的人</b>——不然野外一块碑能刻到别镇的人，
     * 坟墓就串门了。
     */
    public List<Grave> unburiedOfTown(@Nullable UUID townId) {
        Set<UUID> buried = new HashSet<>(this.slots.values());
        List<Grave> out = new ArrayList<>();
        for (Grave g : this.graves.values()) {
            if (buried.contains(g.id())) {
                continue;
            }
            boolean sameTown = townId == null
                    ? g.townId() == null
                    : townId.equals(g.townId());
            if (sameTown) {
                out.add(g);
            }
        }
        return out;
    }

    // ==========================================================
    //  待葬（"选一个已经死了但还没立碑的人"）
    // ==========================================================

    /**
     * 还没立碑的人（按死亡顺序，最早的在前）。
     *
     * <p><b>用 Set 过滤 = 一个人只能立一块碑</b>：重复立碑会让
     * "这块碑是谁"变成二义。迁坟的做法是挖掉旧的再立新的
     * （{@code onRemove} 已经把旧指向清了）。
     */
    public List<Grave> unburied() {
        Set<UUID> buried = new HashSet<>(this.slots.values());
        List<Grave> out = new ArrayList<>();
        for (Grave g : this.graves.values()) {
            if (!buried.contains(g.id())) {
                out.add(g);
            }
        }
        return out;
    }

    /**
     * 还没立碑的人里<b>最早死的</b>那个。没有则 null。
     *
     * <p>⚠ <b>2026-11-28 起这不是刻碑入口了</b>：原来"空手潜行右键"直接调它
     * （决策 #143），现已删除——刻碑一律走 {@code GravePickScreen} 由玩家挑人。
     * 本方法保留是因为它是名册的合理查询（"下一个该处理谁"），
     * 但<b>不要再拿它当"自动认领"用</b>：那样玩家没有选择权，
     * 且挖掉最早的碑会让他回到队首 ⇒ 下一次还是他（永远轮不到别人）。
     */
    @Nullable
    public Grave takeNextUnburied() {
        List<Grave> list = unburied();
        return list.isEmpty() ? null : list.get(0);
    }

    /** 追加一件大事（第一版没有调用方，结构先留）。 */
    public void addDeed(UUID who, Deed deed) {
        Grave old = this.graves.get(who);
        if (old == null) {
            return;
        }
        List<Deed> next = new ArrayList<>(old.deeds());
        next.add(deed);
        this.graves.put(who, new Grave(old.id(), old.name(), old.profession(),
                old.townId(), old.deathDay(), old.cause(), old.damageId(),
                old.killer(), old.birthDay(),
                old.lifespanDays(), old.titleKey(), old.skillLevel(),
                old.arrivalDay(), next, old.stats()));
        this.setDirty();
    }

    // ==========================================================
    //  序列化
    // ==========================================================

    public static GraveyardData load(CompoundTag tag,
            net.minecraft.core.HolderLookup.Provider registries) {
        GraveyardData data = new GraveyardData();
        ListTag list = tag.getList("Graves", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag g = list.getCompound(i);
            if (!g.hasUUID("Id")) {
                continue;
            }
            UUID id = g.getUUID("Id");
            Map<String, Integer> stats = new LinkedHashMap<>();
            CompoundTag st = g.getCompound("Stats");
            for (String k : st.getAllKeys()) {
                int v = st.getInt(k);
                if (v > 0) {
                    stats.put(k, v);
                }
            }
            List<Deed> deeds = new ArrayList<>();
            ListTag dl = g.getList("Deeds", Tag.TAG_COMPOUND);
            for (int j = 0; j < dl.size(); j++) {
                CompoundTag d = dl.getCompound(j);
                deeds.add(new Deed(d.getString("Key"), d.getString("Arg"), d.getInt("Day")));
            }
            data.graves.put(id, new Grave(
                    id,
                    g.getString("Name"),
                    g.getString("Profession"),
                    g.hasUUID("TownId") ? g.getUUID("TownId") : null,
                    g.getLong("DeathDay"),
                    g.getString("Cause"),
                    // #147 新字段：老档没有 ⇒ "" / null，deathLine 退回粗分类
                    g.getString("DamageId"),
                    g.contains("Killer")
                            ? net.minecraft.network.chat.ComponentSerialization.CODEC
                                    .parse(net.minecraft.nbt.NbtOps.INSTANCE, g.get("Killer"))
                                    .result().orElse(null)
                            : null,
                    // ⚠ 旧哨兵 -1 → UNKNOWN_DAY（2026-11-28 换哨兵）
                    readDay(g, "BirthDay"),
                    g.getLong("LifespanDays"),
                    g.getString("TitleKey"),
                    g.getInt("SkillLevel"),
                    readDay(g, "ArrivalDay"),
                    deeds, stats));
        }
        ListTag slotList = tag.getList("Slots", Tag.TAG_COMPOUND);
        for (int i = 0; i < slotList.size(); i++) {
            CompoundTag s = slotList.getCompound(i);
            if (s.hasUUID("Who")) {
                data.slots.put(BlockPos.of(s.getLong("Pos")), s.getUUID("Who"));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag,
            net.minecraft.core.HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Grave g : this.graves.values()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("Id", g.id());
            t.putString("Name", g.name());
            t.putString("Profession", g.profession());
            if (g.townId() != null) {
                t.putUUID("TownId", g.townId());
            }
            t.putLong("DeathDay", g.deathDay());
            t.putString("Cause", g.cause());
            t.putString("DamageId", g.damageId() == null ? "" : g.damageId());
            if (g.killer() != null) {
                net.minecraft.network.chat.ComponentSerialization.CODEC
                        .encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, g.killer())
                        .result().ifPresent(k -> t.put("Killer", k));
            }
            t.putLong("BirthDay", g.birthDay());
            t.putLong("LifespanDays", g.lifespanDays());
            t.putString("TitleKey", g.titleKey());
            t.putInt("SkillLevel", g.skillLevel());
            t.putLong("ArrivalDay", g.arrivalDay());
            CompoundTag st = new CompoundTag();
            g.stats().forEach(st::putInt);
            t.put("Stats", st);
            ListTag dl = new ListTag();
            for (Deed d : g.deeds()) {
                CompoundTag dt = new CompoundTag();
                dt.putString("Key", d.key());
                dt.putString("Arg", d.arg());
                dt.putInt("Day", d.day());
                dl.add(dt);
            }
            t.put("Deeds", dl);
            list.add(t);
        }
        tag.put("Graves", list);

        ListTag slotList = new ListTag();
        this.slots.forEach((pos, who) -> {
            CompoundTag s = new CompoundTag();
            s.putLong("Pos", pos.asLong());
            s.putUUID("Who", who);
            slotList.add(s);
        });
        tag.put("Slots", slotList);
        return tag;
    }

    /**
     * 读一个"日期"型 long 键，并把历史 {@code -1} 归一成
     * {@link com.bmf.bmfstown.entity.domain.AgingRolls#UNKNOWN_DAY}。
     *
     * <p><b>为什么必须归一</b>：初版（2026-11-28 上午）用 -1 表示"未生成"，
     * 老档名册里就存着这个 -1；换哨兵后若原样读回来，会被当成合法生日，
     * 于是"享年"算出一个天文数字。<b>只认恰好 -1</b>——真正的负生日
     * （开档早期的人）必须保留。
     */
    private static long readDay(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_ANY_NUMERIC)) {
            return com.bmf.bmfstown.entity.domain.AgingRolls.UNKNOWN_DAY;
        }
        long v = tag.getLong(key);
        return v == -1L
                ? com.bmf.bmfstown.entity.domain.AgingRolls.UNKNOWN_DAY : v;
    }
}