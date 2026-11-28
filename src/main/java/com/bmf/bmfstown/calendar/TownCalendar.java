package com.bmf.bmfstown.calendar;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.saveddata.SavedData;
import com.bmf.bmfstown.network.ModNetworking;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;


/**
 * 世界历（v0.9.2）：mod 自带的纪年体系——记忆与纪念日的"时间坐标系"。
 *
 * <h2>对外叫「公元」（2026-11-28 用户定）</h2>
 * 界面上<b>一律显示为「公元 N 年 N 月 N 日」</b>，不能叫"镇民历"。
 * 代码里这个类仍叫 {@code TownCalendar}（类名/包名是资产，不改），
 * 但<b>任何玩家能看到的字符串都不许再出现"镇民历"</b>。
 *
 * <h2>这套历法是<b>全世界一份</b>，不属于任何城镇</h2>
 * {@link #get} 忽略传进来的 level，恒取 {@code overworld} 的数据仓
 * （{@code TownSaveData} 那套是镇级的，这里不是）。所以：
 * <ul>
 *   <li>不管从哪个维度、替哪个镇取，拿到的都是同一份；</li>
 *   <li><b>镇建了拆、拆了建，历法照走</b>；</li>
 *   <li>一个存档里压根没建过镇，历法也照走。</li>
 * </ul>
 * ⇒ <b>"某个城镇建立"不是这套历法里的任何事件</b>。所以纪年之前绝不能叫
 * "开镇前"——那个词凭空发明了一个不存在的起点（2026-11-28 用户抓出来的
 * 硬错，我把它当事实写进了 #148 决策文档，见坑96）。正确说法是<b>"公元前"</b>。
 *
 * <h2>为什么不用现实历法</h2>
 * MC 原版月相是 8 相位、8 天一循环（现实朔望月约 29.5 天，MC 压缩成了 8 天），
 * 照搬现实 365 天太长。于是顺着 MC 自己的天上时钟走：
 * <ul>
 *   <li><b>月 = 8 天</b>（一个月相周期）——<b>日号直接取原版月相序号</b>
 *       （1 号必是满月、5 号必是新月），玩家抬头看月亮就是看日历，
 *       纪元落在周期哪一天都能自动对齐；</li>
 *   <li><b>年 = 12 月 × 8 = 96 天</b>（现实游玩约 32 小时过一年）；</li>
 *   <li><b>纪年起点（"元旦"）</b>：见 {@link #relDay} 的惰性落锚——实际效果是
 *       <b>装好 mod 后头一天</b>（镇里只要有一个居民在跑，他的工作 Goal 一 tick
 *       就会问"今天是不是工作日"，当场落锚）。</li>
 * </ul>
 *
 * <p><b>内部只存世界绝对天数</b>（{@code dayTime / 24000}），显示时减纪元换算：
 * 已有的记忆条目零迁移，{@code /time set} 乱调时间也不影响（用差值算）。
 * 换算公式：日 = 绝对天数%8＋1（= 月相序号，1 号恒为满月）；
 * 月/年 = 从"纪元所在月的满月"起算的总月数 ÷12／%12。
 */
@EventBusSubscriber
public final class TownCalendar extends SavedData {

    /** 月长 = 月相周期（原版 8 相位每晚一换）。 */
    public static final int DAYS_PER_MONTH = 8;
    /** 一年的月数。 */
    public static final int MONTHS_PER_YEAR = 12;
    /** 一年天数 = 96。 */
    public static final int DAYS_PER_YEAR = DAYS_PER_MONTH * MONTHS_PER_YEAR;

    /**
     * 公元第 1 天（"公元 1 年"元旦）对应的世界绝对天数；
     * {@code Long.MIN_VALUE} = 未初始化（首次访问时落锚）。
     *
     * <p><b>"落锚"是什么意思（说人话）</b>：就是决定"公元 1 年 1 月 1 日是这世界的第几天"。
     *
     * <p><b>为什么是惰性的、而不是立刻定</b>：只在<b>第一次有人问今天几号</b>时
     * 才把当天定成元旦。听起来悬，实际上装好 mod 头一天就会触发——镇里只要有一个
     * 居民在跑，他的工作 Goal 每 tick 都要问"今天是不是工作日"（{@code isWorkday}），
     * 一问就落锚。所以<b>效果上就是"装 mod 那天"</b>，同时不用额外挂一个登录/开档钩子。
     *
     * <p>副作用（要知道，但不用修）：日志里如果<b>一个居民都没有</b>、也没有人敲
     * {@code /bmfstown} 的日期命令，那落锚会一直推迟到第一次真有人问为止。
     * 此时"公元元年"会比装 mod 那天晚几天——但<b>不会影响任何相对时间</b>
     * （生日、享年、在镇年数全是两个绝对天数相减），只是所有人共用的原点平移。
     */
    private long epochDay = Long.MIN_VALUE;


    public TownCalendar() {
    }

    // ------------------------------------------------------------
    //  存取入口（历法是全世界的：只挂主世界的数据仓）
    // ------------------------------------------------------------

    private static final String DATA_NAME = "bmfstown_calendar";

    public static TownCalendar get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(TownCalendar::new, TownCalendar::load, null),
                DATA_NAME);
    }

    /** 世界绝对天数（dayTime/24000 整除）。 */
    public static long absoluteDay(ServerLevel level) {
        return level.getDayTime() / 24000L;
    }

    /** 自纪元起的天数（首次访问时落锚纪元并落档）。 */
    public long relDay(ServerLevel level) {
        long abs = absoluteDay(level);
        if (this.epochDay == Long.MIN_VALUE) {
            this.epochDay = abs;
            this.setDirty();
        }
        return abs - this.epochDay;
    }

    // ------------------------------------------------------------
    //  冻结模式的睡觉跳晨（v0.10：doDaylightCycle=false 时原版跳夜被
    //  gamerule 门槛挡住——这里补上，根治"停表数据包"的睡眠缺陷）
    // ------------------------------------------------------------

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return; // 只关心服务端关卡
        }
        if (level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)) {
            return; // 正常流速：跳夜由原版原生处理（含唤醒与止雨）
        }
        // 冻结模式：全员入睡 → 跳到次日清晨 + 唤醒（模仿原版 wakeUpAllPlayers）
        var players = level.players();
        if (players.isEmpty() || !players.stream().allMatch(p -> p.isSleeping())) {
            return;
        }
        long day = level.getDayTime();
        long toMorning = 24000L - Math.floorMod(day, 24000L);
        if (toMorning > 0) {
            level.setDayTime(day + toMorning);
        }
        for (var p : players) {
            if (p instanceof net.minecraft.server.level.ServerPlayer sp) {
                sp.stopSleepInBed(false, false); // 原版 wakeUpAllPlayers 同款
            }
        }
    }

    /** 当前昼夜流速倍率（gameTime 口径的原生字段；未设置 = 1.0）。 */
    public static double speedOf(ServerLevel level) {
        float perTick = level.getDayTimePerTick();
        return perTick < 0 ? 1.0 : perTick;
    }

    /**
     * 流速上限 1000 倍 = 一天 1.2 现实秒（延时摄影级）。
     * ⚠ 不能设为"类型最大值"：perTick 是 float（最大 ~3.4e38）比 dayTime
     * 的 long（~9.2e18）还大——超限后一 tick 就让世界时间溢出变负，
     * 历法/睡眠账本/记忆时间戳全部崩坏。1000 倍已覆盖全部合理用法，
     * 纯跳时间请用 /time add（可精确指定落点）。
     */
    public static final double MAX_SPEED = 1000.0;

    /**
     * 设置昼夜流速（原生可变昼夜：ServerLevel.setDayTimePerTick——
     * 自动落档、自动强制时间同步；客户端经 TimeSpeedSyncPayload 镜像）。
     * 0 = 冻结（gamerule doDaylightCycle=false；睡觉跳晨仍可用）。
     */
    public static void setSpeed(ServerLevel level, double value) {
        double v = Math.max(0.0, Math.min(MAX_SPEED, value));
        GameRules rules = level.getGameRules();
        if (v == 0.0) {
            rules.getRule(GameRules.RULE_DAYLIGHT).set(false, level.getServer());
        } else {
            rules.getRule(GameRules.RULE_DAYLIGHT).set(true, level.getServer());
            level.setDayTimePerTick((float) v);
        }
        syncSpeed(level);
    }

    /** 把当前流速镜像到客户端（客户端 ClientLevel 据此平滑插值太阳动画）。 */
    public static void syncSpeed(ServerLevel level) {
        ModNetworking.sendTimeSpeedSync(level, (float) speedOf(level));
    }

    /** 玩家登录补发流速镜像。 */
    @SubscribeEvent
    public static void onPlayerLoggedIn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            syncSpeed(player.serverLevel());
        }
    }

    // ------------------------------------------------------------
    //  换算
    // ------------------------------------------------------------

    /** 一次换算齐全的日期（记忆条目只记"年月日"，不记时辰——用户定稿）。 */
    public record CalendarDate(int year, int month, int day, int moonPhase) {
    }

    public CalendarDate today(ServerLevel level) {
        long abs = absoluteDay(level);
        // 先走一次 relDay：首次访问的落锚副作用在这里，不能省。
        relDay(level);
        return dateOf(abs);
    }

    /**
     * 世界绝对天数 → 公元日期（用本历法的纪元）。
     *
     * <p>⚠ 读 {@code epochDay} 前必须已经落锚。要"可能还没落锚的那一刻"也安全，
     * 用 {@link #dateOf(long, long)} 并自己传 {@link #epochDay(ServerLevel)}。
     */
    public CalendarDate dateOf(long absoluteDay) {
        return dateOf(this.epochDay, absoluteDay);
    }

    /**
     * 纪元（"公元 1 年"元旦）的世界绝对天数；首次访问时先落锚，再返回。
     *
     * <p>给需要"把某个绝对天数换算成日期、但那一天可能<b>早于纪元</b>"的地方用
     * （履历页的生日：{@code today − 年龄×96 − 偏移}）。直接读字段可能是
     * {@code Long.MIN_VALUE} 这个未初始化哨兵——所以必须走这里。
     */
    public long epochDay(ServerLevel level) {
        relDay(level); // 副作用：首次访问落锚
        return this.epochDay;
    }

    /**
     * 世界绝对天数 → 公元日期（纪元显式传入，纯函数）。
     *
     * <h2>为什么允许 {@code absoluteDay < epochDay}（"公元前"）</h2>
     * 生日是<b>从今天往前推</b>的投影（{@code today − (年龄×96 + 偏移)}），
     * 一个 29 岁的居民生日在 2784 天前，远早于公元元年。
     * 所以这里必须能算公元前，返回的 {@code year} 会是 <b>0 或负数</b>
     * （1 = 公元元年，0 = 公元前 1 年，-1 = 公元前 2 年…）。
     *
     * <p><b>⚠ 这里不叫"开镇前"</b>：这套历法全世界一份、不属于任何城镇
     * （见类注释），"建镇"根本不是历法里的事件。用"开镇前"等于凭空发明
     * 一个不存在的起点（2026-11-28 用户抓出来的硬错，坑96）。
     * 显示给玩家时用 {@link #formatDate} / {@link #formatYear}。
     *
     * <p>⚠ <b>这里必须全程 {@code floorMod} / {@code floorDiv}</b>：绝对天数
     * 可为负，而 Java 的 {@code %} 和 {@code /} 对负数<b>向零截断</b>——
     * {@code -1 % 8 == -1}（日号变 0 号）、{@code -1 / 12 == 0}（年份跳一年）。
     * 用错就是"公元前 28 年"写成"公元前 27 年 12 月 0 日"。
     */
    public static CalendarDate dateOf(long epochDay, long absoluteDay) {
        // 日号直接取原版月相序号（1 号 = 满月）：与天上的月亮一一对应，
        // 纪元落在周期哪一天都无所谓——启用当天是残月就显示 4 号（用户定稿：
        // 月相从游戏里读，不乱编）
        int day = (int) Math.floorMod(absoluteDay, DAYS_PER_MONTH) + 1;
        // 年月从"启用当天所在的满月"起算：把纪元回退到当月 1 号（满月日）
        long epochAligned = epochDay - Math.floorMod(epochDay, DAYS_PER_MONTH);
        long monthStart = absoluteDay - Math.floorMod(absoluteDay, DAYS_PER_MONTH);
        long totalMonths = (monthStart - epochAligned) / DAYS_PER_MONTH;
        int year = (int) Math.floorDiv(totalMonths, MONTHS_PER_YEAR) + 1;
        int month = (int) Math.floorMod(totalMonths, MONTHS_PER_YEAR) + 1;
        // 月相直接对齐原版口径（绝对天数 % 8），与天上月亮永远一致
        int phase = (int) Math.floorMod(absoluteDay, DAYS_PER_MONTH);
        return new CalendarDate(year, month, day, phase);
    }

    /**
     * 整段日期的显示文本：<b>"公元 3 年 5 月 12 日"</b> / <b>"公元前 28 年 5 月 12 日"</b>。
     *
     * <h2>为什么这一个法子必须是唯一出口</h2>
     * 同一套措辞有四处要显示：日历方块右键、{@code /bmfstown} 的日期命令、
     * 履历页生日、<b>墓碑上的死亡日期</b>。要是各拼一套，迟早出现
     * "碑上讲公元前、命令讲第 -28 年"。所以格式只在 lang 里定义两份
     * （{@code calendar.bmfstown.date.ce} / {@code .bce}），这里只做分流。
     *
     * @param date 由 {@link #dateOf(long, long)} 算出
     */
    public static Component formatDate(CalendarDate date) {
        return formatYear(date.year())
                .copy()
                .append(Component.translatable("calendar.bmfstown.date.md",
                        date.month(), date.day()));
    }

    /**
     * 只给年份：<b>"公元 3 年"</b> / <b>"公元前 28 年"</b>。
     *
     * <p>{@code year <= 0} 是<b>常态</b>（公元前的年份），不是错误值：
     * {@code 0} ⇒ 公元前 1 年、{@code -27} ⇒ 公元前 28 年。
     * 换算就是 {@code 1 - year}——公元前 1 年紧挨着公元 1 年，中间没有"公元 0 年"。
     */
    public static Component formatYear(int year) {
        return year <= 0
                ? Component.translatable("calendar.bmfstown.date.bce", 1 - year)
                : Component.translatable("calendar.bmfstown.date.ce", year);
    }

    /** 原版月相 0~7 的中文名（0 = 满月）。 */
    public static Component moonPhaseName(int phase) {
        return Component.translatable("calendar.bmfstown.moon." + (phase & 7));
    }

    /**
     * 一行完整的"今天是几号"：<b>"公元 3 年 5 月 12 日 · 满月"</b>。
     *
     * <p>给日历方块右键与 {@code /bmfstown} 的日期命令共用——两处各拼一遍就会漂
     * （一处改了措辞另一处没改），所以统一从这里出。
     */
    public static Component describeToday(ServerLevel level) {
        TownCalendar calendar = get(level);
        CalendarDate date = calendar.today(level);
        return Component.translatable("msg.bmfstown.calendar.today",
                formatYear(date.year()), date.month(), date.day(),
                moonPhaseName(date.moonPhase()));
    }

    // ------------------------------------------------------------
    //  星期（v0.10.3）：纪元 1 月 1 日 = 周日，往后顺延。
    //  0 = 周日 … 6 = 周六；周一~五（1~5）为工作日，周末有职业者休闲。
    // ------------------------------------------------------------

    public static final int WEEKDAY_SUNDAY = 0;
    public static final int WEEKDAY_SATURDAY = 6;

    /** 星期几（0 = 周日 … 6 = 周六）：纪元日起按天顺延。 */
    public int weekday(ServerLevel level) {
        return (int) Math.floorMod(relDay(level), 7L);
    }

    /** 星期几是不是工作日（周一~五；周六日有职业者也休闲）。 */
    public boolean isWorkday(ServerLevel level) {
        int wd = weekday(level);
        return wd != WEEKDAY_SUNDAY && wd != WEEKDAY_SATURDAY;
    }

    /** 星期几的名字（lang：calendar.bmfstown.weekday.0~6）。 */
    public static Component weekdayName(int weekday) {
        return Component.translatable("calendar.bmfstown.weekday." + Math.floorMod(weekday, 7));
    }

    // ------------------------------------------------------------
    //  序列化
    // ------------------------------------------------------------

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("EpochDay", this.epochDay);
        return tag;
    }

    private static TownCalendar load(CompoundTag tag, HolderLookup.Provider registries) {
        TownCalendar data = new TownCalendar();
        data.epochDay = tag.getLong("EpochDay");
        return data;
    }
}
