package com.bmf.bmfstown.skin;

import com.bmf.bmfstown.BmfTown;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 皮肤目录（v0.15 皮肤系统）：从数据包 {@code data/<ns>/skins/*.json} 读皮肤清单，
 * 维护「全局皮肤 id → 贴图/slim」映射，供建模指派（服务端）与渲染（客户端经网络同步）。
 *
 * <h2>为什么清单放 data/ 而不是 assets/</h2>
 * 指派发生在服务端，而专用服务器读不到 assets/（资源包只在客户端加载）。
 * 清单放 data/ 走标准数据包重载（{@link SimpleJsonResourceReloadListener}），
 * 客户端目录由服务端推送（同炼金目录的登录+数据包同步双触发模式）。
 * 贴图本体仍在 assets/——所以"自制皮肤包"= 数据包（清单）+ 资源包（贴图）两件套。
 *
 * <h2>清单格式</h2>
 * <pre>{@code
 * {
 *   "pack": "townsfolk",
 *   "skins": [
 *     {
 *       "id": "townsfolk_01",
 *       "texture": "bmfstown:textures/entity/resident_skins/townsfolk/townsfolk_01.png",
 *       "slim": false,
 *       "author": "某人",
 *       "tags": []
 *     }
 *   ]
 * }
 * }</pre>
 * 全局唯一 id = {@code pack + "/" + 皮肤id}。坏条目（缺字段/贴图路径非法/重 id）
 * 记 warn 跳过，绝不让一张坏皮肤炸掉整个目录。
 *
 * <h2>目录为空是合法状态</h2>
 * mod 本体不内置任何皮肤（内容等用户收集，硬规矩 3）。目录空时
 * {@link #randomId} 返回 null，镇民 skinId 保持空串 → 客户端回退占位贴图。
 */
public final class SkinCatalog extends SimpleJsonResourceReloadListener {

    private static final Gson GSON = new Gson();

    private static volatile List<SkinEntry> entries = List.of();
    private static volatile Map<String, SkinEntry> byId = Map.of();

    public SkinCatalog() {
        super(GSON, "skins");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files,
                         ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<String, SkinEntry> out = new LinkedHashMap<>();
        int bad = 0;
        for (Map.Entry<ResourceLocation, JsonElement> file : files.entrySet()) {
            if (!(file.getValue() instanceof JsonObject root)) {
                continue;
            }
            // 包名优先取清单里的 "pack"，缺省回落到文件路径（skins/<path>.json）
            String pack = root.has("pack") && root.get("pack").isJsonPrimitive()
                    ? root.get("pack").getAsString() : file.getKey().getPath();
            if (!(root.get("skins") instanceof JsonArray skins)) {
                BmfTown.LOGGER.warn("[BmfTown][皮肤] 清单 {} 缺少 skins 数组，跳过", file.getKey());
                continue;
            }
            for (JsonElement el : skins) {
                if (!(el instanceof JsonObject obj)) {
                    bad++;
                    continue;
                }
                SkinEntry entry = parseEntry(pack, obj);
                if (entry == null) {
                    bad++;
                    continue;
                }
                if (out.containsKey(entry.id())) {
                    BmfTown.LOGGER.warn("[BmfTown][皮肤] 皮肤 id 重复：{}（后者丢弃）", entry.id());
                    bad++;
                    continue;
                }
                out.put(entry.id(), entry);
            }
        }
        entries = List.copyOf(out.values());
        byId = out;
        BmfTown.LOGGER.info("[BmfTown][皮肤] 目录已加载：{} 张皮肤{}",
                entries.size(), bad > 0 ? "（跳过 " + bad + " 条坏条目）" : "");
    }

    /** 解析单条皮肤；字段缺失/贴图路径非法返回 null。 */
    private static SkinEntry parseEntry(String pack, JsonObject obj) {
        if (!obj.has("id") || !obj.get("id").isJsonPrimitive()
                || !obj.has("texture") || !obj.get("texture").isJsonPrimitive()) {
            BmfTown.LOGGER.warn("[BmfTown][皮肤] 条目缺 id/texture 字段：{}", obj);
            return null;
        }
        String id = pack + "/" + obj.get("id").getAsString();
        ResourceLocation texture;
        try {
            texture = ResourceLocation.parse(obj.get("texture").getAsString());
        } catch (Exception e) {
            BmfTown.LOGGER.warn("[BmfTown][皮肤] 贴图路径非法（{}）：{}", obj.get("texture"), id);
            return null;
        }
        boolean slim = obj.has("slim") && obj.get("slim").isJsonPrimitive()
                && obj.get("slim").getAsBoolean();
        String author = obj.has("author") && obj.get("author").isJsonPrimitive()
                ? obj.get("author").getAsString() : "";
        List<String> tags = new ArrayList<>();
        if (obj.get("tags") instanceof JsonArray arr) {
            for (JsonElement t : arr) {
                if (t.isJsonPrimitive()) {
                    tags.add(t.getAsString());
                }
            }
        }
        return new SkinEntry(id, texture, slim, author, List.copyOf(tags));
    }

    // ==========================================================
    //  静态查询（服务端指派 / 客户端经同步后的同款数据结构各查各的）
    // ==========================================================

    /** 当前目录（不可变；重载后换新引用）。 */
    public static List<SkinEntry> entries() {
        return entries;
    }

    /** 按 id 查皮肤；目录里没有返回 null。 */
    public static SkinEntry get(String id) {
        return byId.get(id);
    }

    /**
     * 随机抽一张皮肤的 id（生成时指派用）。
     *
     * @return 皮肤 id；目录为空返回 null（调用方应保持 skinId 空串）
     */
    public static String randomId(RandomSource random) {
        List<SkinEntry> list = entries;
        if (list.isEmpty()) {
            return null;
        }
        return list.get(random.nextInt(list.size())).id();
    }

    // ==========================================================
    //  测试专用（GameTest 注入假目录；生产路径绝不调用）
    // ==========================================================

    /** GameTest 用：临时加载假目录，测指派/查询逻辑（GameTest 环境无皮肤清单）。 */
    public static void testOnlyLoad(List<SkinEntry> testEntries) {
        Map<String, SkinEntry> out = new LinkedHashMap<>();
        for (SkinEntry e : testEntries) {
            out.put(e.id(), e);
        }
        entries = List.copyOf(testEntries);
        byId = out;
    }
}
