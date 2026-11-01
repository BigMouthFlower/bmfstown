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
 * 镇民历（v0.9.2）：mod 自带的纪年体系——记忆与纪念日的"时间坐标系"。
 *
 * <p><b>为什么不用现实历法</b>：MC 原版月相是 8 相位、8 天一循环
 * （现实朔望月约 29.5 天，MC 压缩成了 8 天），照搬现实 365 天太长。
 * 于是顺着 MC 自己的天上时钟走：
 * <ul>
 *   <li><b>月 = 8 天</b>（一个月相周期）——<b>日号直接取原版月相序号</b>
 *       （1 号必是满月、5 号必是新月），玩家抬头看月亮就是看日历，
 *       纪元落在周期哪一天都能自动对齐；</li>
 *   <li><b>年 = 12 月 × 8 = 96 天</b>（现实游玩约 32 小时过一年）；</li>
 *   <li><b>纪元</b>：mod 首次在某世界启用那天记为镇民历第 0 天
 *       （老世界中途装 mod = 当天就是元年元旦），落存档，此后不变。</li>
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

    /** 镇民历第 0 天对应的世界绝对天数；{@code Long.MIN_VALUE} = 未初始化（首次访问时落锚）。 */
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
        long rel = Math.max(0L, relDay(level));
        // 日号直接取原版月相序号（1 号 = 满月）：与天上的月亮一一对应，
        // 纪元落在周期哪一天都无所谓——启用当天是残月就显示 4 号（用户定稿：
        // 月相从游戏里读，不乱编）
        int day = (int) (abs % DAYS_PER_MONTH) + 1;
        // 年月从"启用当天所在的满月"起算：把纪元回退到当月 1 号（满月日）
        long epochAligned = this.epochDay - Math.floorMod(this.epochDay, DAYS_PER_MONTH);
        long monthStart = abs - Math.floorMod(abs, DAYS_PER_MONTH);
        long totalMonths = (monthStart - epochAligned) / DAYS_PER_MONTH;
        int year = (int) (totalMonths / MONTHS_PER_YEAR) + 1;
        int month = (int) (Math.floorMod(totalMonths, MONTHS_PER_YEAR)) + 1;
        // 月相直接对齐原版口径（绝对天数 % 8），与天上月亮永远一致
        int phase = (int) (abs % DAYS_PER_MONTH);
        return new CalendarDate(year, month, day, phase);
    }

    /** 原版月相 0~7 的中文名（0 = 满月）。 */
    public static Component moonPhaseName(int phase) {
        return Component.translatable("calendar.bmfstown.moon." + (phase & 7));
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
