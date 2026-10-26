package com.bmf.bmfstown.client;

import com.bmf.bmfstown.entity.profile.BlockMaterial;
import com.bmf.bmfstown.entity.profile.MobPref;
import com.bmf.bmfstown.entity.profile.PersonalityType;
import com.bmf.bmfstown.entity.profile.PrefAnimal;
import com.bmf.bmfstown.entity.profile.PrefColor;
import com.bmf.bmfstown.entity.profile.WeatherPref;
import com.bmf.bmfstown.network.ModNetworking.MoodSyncPayload;
import com.bmf.bmfstown.network.ModNetworking.MoodThought;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端的镇民想法日志 + 偏好摘要缓存（"喜好"页与"想法"页的数据源）。
 *
 * <p>与 {@link ClientTauntState} 同一套模式：
 * <ul>
 *   <li><b>服务端权威</b>：打开面板时整包下发一次，客户端只存不算；</li>
 *   <li><b>断线即清</b>：{@code clear} 挂在 {@code ClientPlayerNetworkEvent.LoggingOut}，
 *       防止上个存档的想法串到下个存档。</li>
 * </ul>
 *
 * <p>不做"按居民逐条过期"——列表本来就是最近 30 条的历史记录，
 * 服务端下次同步会整包覆盖，客户端没有任何基于时间的本地状态。
 */
public final class ClientMoodState {

    /** 居民 UUID → 想法列表（旧的在前，新的在后）。 */
    private static final Map<UUID, List<MoodThought>> THOUGHTS = new ConcurrentHashMap<>();

    /** 居民 UUID → 偏好摘要（v0.8）。 */
    private static final Map<UUID, MoodPrefs> PREFS = new ConcurrentHashMap<>();

    /** 居民 UUID → 工人当前订单（产物注册名，空 = 未设置）。 */
    private static final Map<UUID, String> WORKER_ORDER = new ConcurrentHashMap<>();

    /** 居民 UUID → 特性 id（空 = 无）。 */
    private static final Map<UUID, String> TRAITS = new ConcurrentHashMap<>();

    /** 居民 UUID → GATTACA 模仿状态（"名字|traitId"；空 = 未模仿）。 */
    private static final Map<UUID, String> MIMICS = new ConcurrentHashMap<>();

    /** 居民 UUID → 海象展示版想法（每条已换成随机咕咕；与 THOUGHTS 按下标平行）。 */
    private static final Map<UUID, java.util.List<String>> WALRUS_LINES = new ConcurrentHashMap<>();

    /** 收到服务端同步包：整包覆盖该居民的想法与偏好。 */
    public static void handleSync(MoodSyncPayload payload) {
        try {
            UUID id = UUID.fromString(payload.residentId());
            THOUGHTS.put(id, List.copyOf(payload.thoughts()));
            TRAITS.put(id, payload.trait() == null ? "" : payload.trait());
            MIMICS.put(id, payload.mimic() == null ? "" : payload.mimic());
            if ("walrus".equals(payload.trait())) {
                // 海象：同步瞬间逐条随机生成咕咕（每次打开面板都不同，无法反推原文）；
                // 后台真实信息在服务端 NBT，客户端只留展示版
                java.util.List<String> goo = new java.util.ArrayList<>();
                for (int i = 0; i < payload.thoughts().size(); i++) {
                    goo.add(randomGoo());
                }
                WALRUS_LINES.put(id, List.copyOf(goo));
            } else {
                WALRUS_LINES.remove(id);
            }
            PREFS.put(id, MoodPrefs.parse(payload.prefs()));
            WORKER_ORDER.put(id, payload.workerOrder() == null ? "" : payload.workerOrder());
        } catch (IllegalArgumentException ignored) {
            // 非法 UUID：包被篡改/版本串了，忽略
        }
    }

    /** 取某居民的想法列表；没有缓存（还没打开过面板）返回空列表。 */
    public static List<MoodThought> thoughtsOf(UUID residentId) {
        return THOUGHTS.getOrDefault(residentId, List.of());
    }

    /** 取某居民的偏好摘要；没有缓存返回全空偏好（UI 显示"无"）。 */
    public static MoodPrefs prefsOf(UUID residentId) {
        return PREFS.getOrDefault(residentId, MoodPrefs.EMPTY);
    }

    /** 特性 id（空 = 无/未同步）。 */
    public static String traitOf(UUID residentId) {
        return TRAITS.getOrDefault(residentId, "");
    }

    /** GATTACA 模仿状态（"被模仿者名字|模仿中的特性id"；空 = 未模仿）。 */
    public static String mimicOf(UUID residentId) {
        return MIMICS.getOrDefault(residentId, "");
    }

    /** 海象展示版想法（与 thoughtsOf 下标平行；null = 该镇民不是海象）。 */
    public static java.util.List<String> walrusLinesOf(UUID residentId) {
        return WALRUS_LINES.get(residentId);
    }

    /**
     * 随机生成一句"咕咕咕"：1~14 个"咕"，每条后面随机挂标点或空格。
     * 用 ThreadLocalRandom（不可复现），杜绝从长度/标点分布反推原文。
     */
    private static String randomGoo() {
        java.util.Random rng = java.util.concurrent.ThreadLocalRandom.current();
        String[] puncts = {"，", "。", "！", "？", "…", " ", ""};
        int len = 1 + rng.nextInt(14);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append('咕').append(puncts[rng.nextInt(puncts.length)]);
        }
        return sb.toString();
    }

    /** 工人当前订单（产物注册名；没有缓存返回空 = 未设置）。 */
    public static String workerOrderOf(UUID residentId) {
        return WORKER_ORDER.getOrDefault(residentId, "");
    }

    /** 本地点击下单后的乐观更新（服务端为权威，失败会被服务端消息纠正）。 */
    public static void setWorkerOrder(UUID residentId, String product) {
        WORKER_ORDER.put(residentId, product);
    }

    /** 断线清空。 */
    public static void clear() {
        THOUGHTS.clear();
        PREFS.clear();
        WORKER_ORDER.clear();
        TRAITS.clear();
        WALRUS_LINES.clear();
        MIMICS.clear();
    }

    private ClientMoodState() {
    }

    // ==========================================================
    //  偏好摘要（v0.8）
    // ==========================================================

    /**
     * 一个镇民的喜好/讨厌/性格摘要（客户端渲染用，全部枚举直存）。
     *
     * <p>对应服务端 {@code MoodState.prefsString()} 的紧凑编码：
     * {@code blocks|animal|color|music|weather|mob|personality}，
     * blocks 段内逗号分隔；空段 = 没有这项偏好。
     *
     * <p>解析宽容：任何一段坏了整条退回 {@link #EMPTY}——
     * 面板显示"无"比崩溃好（串版本/改表后老包都能优雅降级）。
     */
    public record MoodPrefs(List<BlockMaterial> blocks, PrefAnimal animal, PrefColor color,
            boolean music, WeatherPref weather, MobPref mob, PersonalityType personality) {

        public static final MoodPrefs EMPTY =
                new MoodPrefs(List.of(), null, null, false, null, null, null);

        public static MoodPrefs parse(String encoded) {
            if (encoded == null || encoded.isEmpty()) {
                return EMPTY;
            }
            String[] parts = encoded.split("\\|", -1);
            if (parts.length != 7) {
                return EMPTY;
            }
            List<BlockMaterial> blocks = new ArrayList<>();
            if (!parts[0].isEmpty()) {
                for (String name : parts[0].split(",")) {
                    try {
                        BlockMaterial m = BlockMaterial.valueOf(name.toUpperCase(Locale.ROOT));
                        if (!blocks.contains(m)) {
                            blocks.add(m);
                        }
                    } catch (IllegalArgumentException ignored) {
                        // 单个材质坏了跳过，不影响其他段
                    }
                }
            }
            try {
                return new MoodPrefs(
                        List.copyOf(blocks),
                        enumOf(parts[1], PrefAnimal.class),
                        enumOf(parts[2], PrefColor.class),
                        "1".equals(parts[3]),
                        enumOf(parts[4], WeatherPref.class),
                        enumOf(parts[5], MobPref.class),
                        enumOf(parts[6], PersonalityType.class));
            } catch (IllegalArgumentException ignored) {
                return EMPTY;
            }
        }

        private static <E extends Enum<E>> E enumOf(String name, Class<E> type) {
            if (name == null || name.isEmpty()) {
                return null;
            }
            try {
                return Enum.valueOf(type, name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }
}
