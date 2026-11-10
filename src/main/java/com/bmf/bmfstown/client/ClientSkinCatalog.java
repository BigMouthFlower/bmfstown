package com.bmf.bmfstown.client;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端皮肤目录缓存（v0.15 皮肤系统）：服务端在登录/数据包同步时推送
 * （{@code ModNetworking.SkinCatalogSyncPayload}），这里只存"皮肤 id → 贴图/slim"。
 * 与 {@link ClientAlchemyCatalog} 同款模式：客户端不读清单，只消费服务端同步值。
 *
 * <p>同值语义：skinId 查不到（未指派/目录为空/皮肤被删）→ {@code null}/false，
 * 渲染器据此回退占位贴图与经典模型。
 */
public final class ClientSkinCatalog {

    /** 一条皮肤：id + 贴图路径 + 是否纤细模型。 */
    public record Entry(String id, ResourceLocation texture, boolean slim) {
    }

    private static volatile List<Entry> entries = List.of();
    private static volatile Map<String, Entry> byId = Map.of();

    private ClientSkinCatalog() {
    }

    /** 接收服务端目录（条目 "id|texture|slim(0/1)"）。 */
    public static void accept(List<String> raw) {
        Map<String, Entry> out = new LinkedHashMap<>();
        for (String line : raw) {
            String[] parts = line.split("\\|");
            if (parts.length != 3) {
                continue;
            }
            try {
                Entry e = new Entry(parts[0], ResourceLocation.parse(parts[1]), parts[2].equals("1"));
                out.put(e.id(), e);
            } catch (Exception ignored) {
                // 贴图路径非法：跳过（服务端已过滤过，这里纯防御）
            }
        }
        entries = List.copyOf(out.values());
        byId = out;
        com.bmf.bmfstown.BmfTown.LOGGER.debug("[BmfTown][皮肤] 客户端目录已接收：{} 张", out.size());
    }

    /** 皮肤 id → 贴图；查不到返回 null（渲染器回退占位贴图）。 */
    @Nullable
    public static ResourceLocation locationOf(String skinId) {
        Entry e = byId.get(skinId);
        return e == null ? null : e.texture();
    }

    /** 皮肤是否纤细模型；查不到默认经典（与占位回退一致）。 */
    public static boolean isSlim(String skinId) {
        Entry e = byId.get(skinId);
        return e != null && e.slim();
    }

    /** 断线/换世界清空缓存，防止串档残留（同 ClientMoodState 惯例）。 */
    public static void clear() {
        entries = List.of();
        byId = Map.of();
    }
}
