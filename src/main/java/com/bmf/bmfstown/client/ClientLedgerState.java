package com.bmf.bmfstown.client;

import com.bmf.bmfstown.network.ModNetworking.LedgerDataPayload;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端的履历缓存（"履历"页的数据源，2026-11-28）。
 *
 * <p>与 {@link ClientMoodState} 同一套模式：
 * <ul>
 *   <li><b>服务端权威</b>：打开履历页时按需拉一次，客户端只存不算；</li>
 *   <li><b>断线即清</b>：{@code clear} 挂在 {@code ClientPlayerNetworkEvent.LoggingOut}，
 *       防止上个存档的履历串到下个存档。</li>
 * </ul>
 *
 * <h2>为什么是"打开时拉一次"而不是随实体同步</h2>
 * 统计表会随职业越做越长（每加一种职业就多几条），塞进
 * {@code ResidentSnapshot} 等于让每次名字/心情同步都拖一整张统计表走。
 * 履历是<b>打开时才需要</b>的读数，不是随帧变化的活状态——
 * 和簿记员盘库（{@code CensusDataPayload}）完全同构。
 *
 * <h2>为什么缓存里存的是"服务端算好的天数"</h2>
 * 客户端没有 {@code ServerLevel}，{@code TownCalendar.absoluteDay} 拿不到
 * 权威天数；自己用本地时间算年龄会跳变（这个坑已经在年龄面板上踩过一次）。
 * <b>凡是"今天"相关的量一律由服务端定稿。</b>
 */
public final class ClientLedgerState {

    /** 居民实体 id → 履历数据。用实体 id 不用 UUID：主键和面板同一套，不会串。 */
    private static final Map<Integer, LedgerDataPayload> LEDGERS = new ConcurrentHashMap<>();

    /** 收到服务端下发的履历。 */
    public static void accept(LedgerDataPayload payload) {
        LEDGERS.put(payload.entityId(), payload);
    }

    /** 取某居民的履历（按实体 id，Screen 直接拿得到）。没拉到过返回 null。 */
    public static LedgerDataPayload of(int entityId) {
        return LEDGERS.get(entityId);
    }

    /** 断线清空：不然后一个存档的居民履历会串到前一个。 */
    public static void clear() {
        LEDGERS.clear();
    }

    private ClientLedgerState() {
    }
}