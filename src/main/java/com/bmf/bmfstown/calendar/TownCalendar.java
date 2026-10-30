package com.bmf.bmfstown.calendar;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

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
