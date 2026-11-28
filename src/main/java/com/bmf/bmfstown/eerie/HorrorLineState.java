package com.bmf.bmfstown.eerie;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 恐怖故事线推进状态（决策 #54 十部制 + #57 触发链，城镇粒度 SavedData）。
 *
 * <h2>状态机（每镇一份）</h2>
 * <pre>
 *   [无锁定] --发现首格--> [锁定线·第1格] --每次命中+1--> [第 N 格写齐]
 *      ^                                                  （N = 该线章数）|
 *      |            解锁（UnlockState 登记 + 随机 2~5 日应验倒计时）  |
 *      +---------------- 应验后该线降极小概率 <----------------------+
 * </pre>
 * <ul>
 *   <li><b>单线锁定</b>（#54）：一旦有锁定线，所有命中全部推进它，
 *       不并发第二条；写齐才释放；</li>
 *   <li><b>章数按线</b>（2026-11-25 用户拍板）：不再固定 10 格——每条线
 *       自己在 {@link HorrorLines.HorrorLine#chapters()} 声明总章数，
 *       写齐即是解锁点（短故事 3 格、长故事 12 格都合法）；</li>
 *   <li><b>每日配额</b>（#57 稀缺池）：全镇每游戏日最多推进 1 格——
 *       闲逛触发可以很频繁，产出被配额按住；</li>
 *   <li><b>应验</b>（#54/#57）：写齐时掷 2~5 游戏日的应验日；应验动作
 *       是"登记应验 + 公告 + 按线的 effectId 挂效果"（空 = 只公告）；
 *       应验后该线从"正常权重"降为"极小概率"（可再次被零星写）。</li>
 * </ul>
 *
 * <p>本类只管<b>状态与推进</b>；概率（要不要命中）与表现（写书/公告/
 * 气泡）都在 {@link WanderEvents}——事件抽取与状态机解耦。
 */
public final class HorrorLineState extends SavedData {

    private static final String DATA_NAME = "bmfstown_horror_lines";

    /** 城镇 id → 状态。 */
    private final Map<UUID, TownState> towns = new HashMap<>();

    /** 单镇推进状态（可变载体；字段注释即不变量）。 */
    private static final class TownState {
        /** 当前锁定的故事线 id（无锁定 = null）。 */
        String lockedLine;
        /** 锁定线已写到的格数（1~该线 chapters；lockedLine 为 null 时无意义）。 */
        int progress;
        /** 已写齐（解锁）的线 id。 */
        final Set<String> unlocked = new HashSet<>();
        /** 已应验的线 id。 */
        final Set<String> verified = new HashSet<>();
        /** 线 id → 应验日（游戏日；应验后移除）。 */
        final Map<String, Long> verifyDueDay = new HashMap<>();
        /** 每日配额记账：上次推进的游戏日。 */
        long lastProgressDay = Long.MIN_VALUE;
        /**
         * 回拨探针：本镇历来看见过的<b>最大</b>游戏日。
         *
         * <p><b>为什么不直接拿"应验日 vs 今天"判断时间被拨过</b>：应验日本来就在未来
         * （{@code today + 2~6 天}），今天小于它是<b>常态</b>。而"差了多少天"
         * 也与回拨无关——正常过了一天，差值同样会变。两个数都比不出真假。
         *
         * <p>真正能分的只有一件事：<b>时间是否曾经倒退</b>。
         * 正常游玩 {@code today} 单调不减，所以这个最大值只增不减；
         * 一旦出现 {@code today < seenMaxDay}，唯一解释就是时间被往回拨了
         * （{@code /time set} 或读了一个更早的存档）。
         */
        long seenMaxDay = Long.MIN_VALUE;
    }

    // ==========================================================
    //  存取入口
    // ==========================================================

    public static HorrorLineState get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(HorrorLineState::new, HorrorLineState::load, null), DATA_NAME);
    }

    // ==========================================================
    //  推进（WanderEvents 命中恐怖事件时调用）
    // ==========================================================

    /** 一次推进的结果（公告与写卷的素材）。 */
    public record Advance(HorrorLines.HorrorLine line, int chapter,
                          boolean unlockedNow, @Nullable Long verifyDueDay) {
    }

    /**
     * 命中一次恐怖事件：按状态机推进一格。
     *
     * @return 推进结果；被每日配额拦住 / 没有可抽的线时返回 null（无事发生）
     */
    @Nullable
    public Advance advance(ServerLevel level, UUID townId, RandomSource random) {
        return advance(level, townId, random, false, null);
    }

    /**
     * 调试台阶：<b>只跳过"每镇每日 1 格"配额</b>，其余状态机一概照旧
     * （单线锁定、按线章数、写齐解锁、掷应验日、释放锁定）。
     *
     * <p><b>为什么必须存在</b>（2026-11-26 用户点名）：线上产出被两道闸按住
     * ——闲逛 10% 抽中 × 全镇每日 1 格，写完 7~9 格要挂机很多个游戏日。
     * 人肉验收"每一本长什么样"根本没法做。所以给一条能逐格推进的通路。
     *
     * <p><b>为什么只跳配额</b>：配额是"节奏"，锁线与章数是"规则"。跳过规则
     * 测出来的状态线上永远不会发生（等于测了个假东西）；只跳节奏，测到的
     * 每一步都是线上真会发生的一步。
     *
     * <p><b>生产路径绝不使用本方法</b>（{@link #advance} 才是）。反向验证守
     * 着这条：{@code debugStepSkipsOnlyDailyCap} 会红。
     */
    @Nullable
    public Advance advanceIgnoringDailyCap(ServerLevel level, UUID townId, RandomSource random) {
        return advance(level, townId, random, true, null);
    }

    /**
     * 调试台阶：<b>把某条线锁上并推进一格</b>（跳过每日配额，也跳过随机抽线）。
     *
     * <p>用于逐本验收某条具体故事线——比如"《雾中人》第 5 本到底长什么样"。
     * 平时 {@code pickLine} 等概率抽线，两条线共存时抽到哪条不由人；调试要的
     * 恰恰是"我就要这条"。
     *
     * <p>其余规则一概照旧，包括<b>正在推进别的线时不许插队</b>（单线锁定是
     * 规则不是节奏）：若已有锁定线且不是 {@code lineId}，返回 null 并让调用方
     * 解释原因——不静默改锁，否则测出来的顺序线上不可能出现。
     *
     * @param lineId 目标线 id（须已登记）
     * @return 推进结果；线不存在 / 已被另一条线锁住 / 该线已写齐时 null
     */
    @Nullable
    public Advance advanceLineIgnoringDailyCap(ServerLevel level, UUID townId,
                                               String lineId, RandomSource random) {
        return advance(level, townId, random, true, lineId);
    }

    @Nullable
    private Advance advance(ServerLevel level, UUID townId, RandomSource random,
                            boolean ignoreDailyCap, @Nullable String forcedLineId) {
        TownState st = this.towns.computeIfAbsent(townId, k -> new TownState());
        long today = level.getDayTime() / 24000L;

        // 每日配额（稀缺池）：全镇每游戏日最多 1 格
        if (!ignoreDailyCap && st.lastProgressDay == today) {
            return null;
        }

        HorrorLines.HorrorLine line;
        int chapter;
        if (st.lockedLine != null) {
            line = HorrorLines.byId(st.lockedLine);
            if (line == null) { // 登记表里没了（线被裁撤）：锁作废，重抽
                st.lockedLine = null;
                st.progress = 0;
                return null;
            }
            // 指定线 ≠ 当前锁线：单线锁定是规则，不插队（调用方负责解释）
            if (forcedLineId != null && !forcedLineId.equals(line.id())) {
                return null;
            }
            st.progress++;
            chapter = st.progress;
        } else {
            if (forcedLineId != null) {
                line = HorrorLines.byId(forcedLineId);
                if (line == null) {
                    return null; // 线 id 写错 / 已裁撤
                }
                if (st.unlocked.contains(line.id())) {
                    return null; // 已写齐：先 reset 再验，或等应验
                }
            } else {
                line = pickLine(st, random);
                if (line == null) {
                    return null; // 没有任何可抽的线
                }
            }
            st.lockedLine = line.id();
            st.progress = 1;
            chapter = 1;
        }

        st.lastProgressDay = today;
        // 探针同步：advance 是每天最早拿到 today 的地方之一，在这里顺手推进
        // 最大值。正常游玩单调不减；真回拨时保持旧值不动（留给
        // pollVerifications 那次比对用）。
        if (today > st.seenMaxDay) {
            st.seenMaxDay = today;
        }

        // 写齐该线的章数（按线声明，不再固定 10）：解锁 + 掷应验日 + 释放锁定
        boolean unlockedNow = chapter >= line.chapters();
        Long verifyDue = null;
        if (unlockedNow) {
            st.unlocked.add(line.id());
            st.lockedLine = null;
            st.progress = 0;
            verifyDue = today + WanderEvents.VERIFY_MIN_DAYS
                    + random.nextInt(WanderEvents.VERIFY_SPAN_DAYS);
            st.verifyDueDay.put(line.id(), verifyDue);
        }
        this.setDirty();
        return new Advance(line, chapter, unlockedNow, verifyDue);
    }

    /**
     * 抽一条线：未解锁的优先（等概率）；全解锁后未应验的不再抽；
     * 只剩已应验线时按"极小概率"抽（权重 1/10，#54 降级口径）。
     */
    @Nullable
    private static HorrorLines.HorrorLine pickLine(TownState st, RandomSource random) {
        var all = HorrorLines.all();
        List<HorrorLines.HorrorLine> fresh = new java.util.ArrayList<>();
        List<HorrorLines.HorrorLine> verified = new java.util.ArrayList<>();
        for (HorrorLines.HorrorLine line : all) {
            if (!st.unlocked.contains(line.id())) {
                fresh.add(line);
            } else if (st.verified.contains(line.id())) {
                verified.add(line); // 应验后：极小概率池
            }
            // 已解锁未应验：等它先应验，不重复抽
        }
        if (!fresh.isEmpty()) {
            return fresh.get(random.nextInt(fresh.size()));
        }
        // 极小概率：1/10 概率才抽已应验线（配额同样生效）
        if (!verified.isEmpty() && random.nextInt(10) == 0) {
            return verified.get(random.nextInt(verified.size()));
        }
        return null;
    }

    /**
     * 懒检查应验（每次事件抽取时顺带跑，零 tick 成本）：
     * 到期的线登记应验并返回，供上层发公告/挂效果。
     */
    public java.util.List<HorrorLines.HorrorLine> pollVerifications(ServerLevel level, UUID townId) {
        TownState st = this.towns.get(townId);
        if (st == null) {
            return java.util.List.of();
        }
        long today = level.getDayTime() / 24000L;
        // ---- 回拨检测（坑97）----
        // 先判"时间是否倒退过"，再谈应验。顺序不能换：探针要在处理之前读，
        // 处理完立刻把它对齐到 today，否则下一次 poll 会把同一段回拨再算一遍。
        if (today < st.seenMaxDay) {
            long rewind = st.seenMaxDay - today;
            // 应验日整体按回拨量平移：等待期原样保留。
            // 例：第 100 天解锁、应验日排到 105；玩家拨回第 50 天 ⇒ 应验日变 55，
            // 还要等 5 天。<b>不选"立刻应验"</b>是因为那等于让玩家拿回拨换免费解锁；
            // 也<b>不选"什么都不做"</b>——那样 105 这个日期再也追不上
            // （除非玩家正好又玩到第 105 天），反复回拨就永久不触发。
            if (!st.verifyDueDay.isEmpty()) {
                st.verifyDueDay.replaceAll((k, dueDay) -> dueDay - rewind);
                this.setDirty();
            }
            st.seenMaxDay = today;
        } else {
            st.seenMaxDay = today;
        }
        if (st.verifyDueDay.isEmpty()) {
            return java.util.List.of();
        }
        List<HorrorLines.HorrorLine> due = new java.util.ArrayList<>();
        for (var it = st.verifyDueDay.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (today >= e.getValue()) {
                it.remove();
                st.verified.add(e.getKey());
                HorrorLines.HorrorLine line = HorrorLines.byId(e.getKey());
                if (line != null) {
                    due.add(line);
                }
            }
        }
        if (!due.isEmpty()) {
            this.setDirty();
        }
        return due;
    }

    // ==========================================================
    //  调试台阶（命令 / 实机验收专用；生产路径不调用）
    // ==========================================================

    /**
     * 把某条线的应验日直接拉到<b>今天</b>（下次 {@link #pollVerifications} 即应验）。
     *
     * <p>为什么不是"立刻执行效果"：效果由 {@code WanderEvents.pollVerifications}
     * 统一派发（公告 + 气泡 + {@code VerificationEffects}）。调试如果自己另开
     * 一条执行路，验的就不是生产那条路——那样"应验能不能跑通"这句话就白问了。
     * 所以这里只动<b>时间</b>，派发照旧走生产代码。
     *
     * @return 是否真把某条线的日期改到今天（该线没在等应验时返回 false）
     */
    public boolean dueNow(ServerLevel level, UUID townId, String lineId) {
        TownState st = this.towns.get(townId);
        if (st == null || !st.verifyDueDay.containsKey(lineId)) {
            return false;
        }
        st.verifyDueDay.put(lineId, level.getDayTime() / 24000L);
        this.setDirty();
        return true;
    }

    /** 本镇当前在等应验的线 → 应验日（只读快照，调试显示用）。 */
    public Map<String, Long> pendingVerifications(UUID townId) {
        TownState st = this.towns.get(townId);
        return st == null ? Map.of() : Map.copyOf(st.verifyDueDay);
    }

    /**
     * 清空本镇的推进状态（锁线 / 格数 / 已解锁 / 已应验 / 应验日），
     * 保留公告栏（那是历史记录，不属于状态机）。
     *
     * <p>用途：同一存档里反复从头验一遍。注意 <b>UnlockState 里的解锁 id
     * 不会被撤回</b>——解锁是全局账本，跨故事线共用；要一起清请另外用
     * {@code /bmfstown lock}。
     */
    public void resetTown(UUID townId) {
        this.towns.remove(townId);
        this.setDirty();
    }

    // ==========================================================
    //  查询（测试/调试）
    // ==========================================================

    /** 锁定线 id（无则 null）。 */
    @Nullable
    public String lockedLineOf(UUID townId) {
        TownState st = this.towns.get(townId);
        return st == null ? null : st.lockedLine;
    }

    /** 锁定线进度（1~该线 chapters）。 */
    public int progressOf(UUID townId) {
        TownState st = this.towns.get(townId);
        return st == null ? 0 : st.progress;
    }

    /** 已解锁线集合（只读）。 */
    public Set<String> unlockedOf(UUID townId) {
        TownState st = this.towns.get(townId);
        return st == null ? Set.of() : Set.copyOf(st.unlocked);
    }

    /** 已应验线集合（只读）。 */
    public Set<String> verifiedOf(UUID townId) {
        TownState st = this.towns.get(townId);
        return st == null ? Set.of() : Set.copyOf(st.verified);
    }

    // ==========================================================
    //  存档往返
    // ==========================================================

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        for (var e : this.towns.entrySet()) {
            CompoundTag t = new CompoundTag();
            TownState st = e.getValue();
            if (st.lockedLine != null) {
                t.putString("Locked", st.lockedLine);
                t.putInt("Progress", st.progress);
            }
            t.put("Unlocked", stringSet(st.unlocked));
            t.put("Verified", stringSet(st.verified));
            ListTag due = new ListTag();
            for (var d : st.verifyDueDay.entrySet()) {
                CompoundTag dt = new CompoundTag();
                dt.putString("Line", d.getKey());
                dt.putLong("Day", d.getValue());
                due.add(dt);
            }
            t.put("VerifyDue", due);
            t.putLong("LastDay", st.lastProgressDay);
            // 探针必须落档：否则每次重进世界都从 MIN_VALUE 起步，
            // 读档发生在时间倒退之后（读了个更早的存档）就再也看不出来。
            t.putLong("SeenMaxDay", st.seenMaxDay);
            tag.put(e.getKey().toString(), t);
        }
        return tag;
    }

    private static ListTag stringSet(Set<String> set) {
        ListTag out = new ListTag();
        for (String s : set) {
            out.add(StringTag.valueOf(s));
        }
        return out;
    }

    public static HorrorLineState load(CompoundTag tag, HolderLookup.Provider registries) {
        HorrorLineState state = new HorrorLineState();
        for (String key : tag.getAllKeys()) {
            CompoundTag t = tag.getCompound(key);
            TownState st = new TownState();
            st.lockedLine = t.contains("Locked") ? t.getString("Locked") : null;
            st.progress = t.getInt("Progress");
            readStringSet(t.getList("Unlocked", Tag.TAG_STRING), st.unlocked);
            readStringSet(t.getList("Verified", Tag.TAG_STRING), st.verified);
            ListTag due = t.getList("VerifyDue", Tag.TAG_COMPOUND);
            for (int i = 0; i < due.size(); i++) {
                CompoundTag dt = due.getCompound(i);
                st.verifyDueDay.put(dt.getString("Line"), dt.getLong("Day"));
            }
            st.lastProgressDay = t.getLong("LastDay");
            st.seenMaxDay = t.contains("SeenMaxDay")
                    ? t.getLong("SeenMaxDay") : st.lastProgressDay;
            try {
                state.towns.put(UUID.fromString(key), st);
            } catch (IllegalArgumentException ignored) {
                // 坏键宁可丢弃也不要崩档
            }
        }
        return state;
    }

    private static void readStringSet(ListTag list, Set<String> out) {
        for (int i = 0; i < list.size(); i++) {
            out.add(list.getString(i));
        }
    }
}
