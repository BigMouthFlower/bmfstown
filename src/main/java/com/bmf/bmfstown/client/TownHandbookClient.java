package com.bmf.bmfstown.client;

import com.bmf.bmfstown.network.ModNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 手册的客户端侧：数据缓存 + 打开屏幕。
 *
 * <h2>为什么客户端要缓存一份城镇数据</h2>
 * SavedData 是服务端专属，客户端世界里没有。手册要展示什么，
 * 只能由服务端推过来。这里存的就是最近一次
 * {@code HandbookSyncPayload} 解析出来的展示数据——
 * 服务端每次改数据都推全量，客户端这份永远是最新的。
 *
 * <h2>这份缓存还有第二个用途</h2>
 * 拿着魔杖时在世界里把已建城镇/区域画出来（白框 = 城镇，
 * 彩框 = 子区域），让玩家不翻手册也能看见地盘边界。
 */
public final class TownHandbookClient {

    /** 一条子区域的展示数据。 */
    /**
     * 一个子区域的展示数据。
     *
     * @param beds      住宅区专用：区域内床数；其他类型恒为 0
     * @param occupants 住宅区专用：已入住村民数；其他类型恒为 0
     */
    public record ZoneView(int kind, UUID id, String name, BlockPos min, BlockPos max,
                           int beds, int occupants) {
    }

    /** 一个城镇的展示数据（含它下面的子区域）。 */
    public record TownView(UUID id, String name, String dimension, String owner,
                           BlockPos min, BlockPos max, List<ZoneView> zones,
                           List<MemberView> members) {
    }

    /** 一条居民展示数据（手册"居民列表"用）。 */
    public record MemberView(int entityId, String name, String professionId) {
    }

    private static volatile List<TownView> towns = List.of();
    private static TownHandbookScreen openScreen;

    private TownHandbookClient() {
    }

    // ==========================================================
    //  对外查询（手册 UI 和世界渲染都从这读）
    // ==========================================================

    public static List<TownView> towns() {
        return towns;
    }

    /** 魔杖世界渲染用：本维度内、有内容的城镇。 */
    public static List<TownView> townsInDimension(String dimension) {
        List<TownView> out = new ArrayList<>();
        for (TownView town : towns) {
            if (town.dimension().equals(dimension)) {
                out.add(town);
            }
        }
        return out;
    }

    // ==========================================================
    //  服务端同步入口
    // ==========================================================

    public static void handleSync(com.bmf.bmfstown.network.ModNetworking.HandbookSyncPayload payload) {
        List<TownView> oldTowns = towns;
        List<TownView> newTowns = parseBlob(payload.blob());
        towns = newTowns;
        Minecraft mc = Minecraft.getInstance();

        // 预览图文件与城镇数据保持一致（改名 → 改文件名；删除 → 删文件）
        TownPreviewStore.syncFiles(mc, oldTowns, newTowns);

        if (payload.open()) {
            // 新建的城镇：趁手册还没盖上（画面还是世界）截一张主视角
            java.util.Set<UUID> oldIds = new java.util.HashSet<>();
            for (TownView t : oldTowns) {
                oldIds.add(t.id());
            }
            for (TownView t : newTowns) {
                if (!oldIds.contains(t.id())) {
                    TownPreviewStore.capture(mc, t);
                }
            }
            mc.setScreen(new TownHandbookScreen());
        } else if (openScreen != null) {
            openScreen.refresh(); // 手册开着：原地刷新列表
        }

        // 已存在城镇的预览图懒加载（进档第一次打开手册时从这里读文件）
        for (TownView t : newTowns) {
            TownPreviewStore.ensureLoaded(mc, t);
        }
    }

    /** 解析服务端发来的文本行（格式见 TownSaveData.toBlob）。 */
    private static List<TownView> parseBlob(String blob) {
        Map<UUID, Builder> byId = new HashMap<>();
        List<Builder> order = new ArrayList<>();
        for (String line : blob.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split("\\|", -1);
            try {
                if (f[0].equals("T") && f.length == 7) {
                    Builder b = new Builder(
                            UUID.fromString(f[1]), f[2], f[3], f[4],
                            parsePos(f[5]), parsePos(f[6]));
                    byId.put(b.id, b);
                    order.add(b);
                } else if (f[0].equals("M") && f.length >= 5) {
                    Builder b = byId.get(UUID.fromString(f[1]));
                    if (b != null) {
                        // 名字在最后一段（玩家可重命名，可能带 |）
                        String[] limited = line.split("\\|", 5);
                        b.members.add(new MemberView(Integer.parseInt(limited[2]),
                                limited[4], limited[3]));
                    }
                } else if (f[0].equals("Z") && (f.length == 7 || f.length == 9)) {
                    Builder b = byId.get(UUID.fromString(f[1]));
                    if (b != null) {
                        // 末尾两个数字是住宅区的床数/入住数；老格式（7 字段）补 0
                        int beds = f.length >= 9 ? Integer.parseInt(f[7]) : 0;
                        int occ = f.length >= 9 ? Integer.parseInt(f[8]) : 0;
                        b.zones.add(new ZoneView(Integer.parseInt(f[2]),
                                UUID.fromString(f[3]), f[4],
                                parsePos(f[5]), parsePos(f[6]), beds, occ));
                    }
                }
            } catch (RuntimeException e) {
                // 单行坏了不炸整个手册，跳过
            }
        }
        List<TownView> out = new ArrayList<>();
        for (Builder b : order) {
            out.add(b.build());
        }
        return out;
    }

    private static BlockPos parsePos(String text) {
        String[] p = text.split(",");
        return new BlockPos(Integer.parseInt(p[0].trim()),
                Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
    }

    /** 中转结构：先收城镇行和区域行，最后一次成型。 */
    static final class Builder {
        final UUID id;
        final String name;
        final String dimension;
        final String owner;
        final BlockPos min;
        final BlockPos max;
        final List<ZoneView> zones = new ArrayList<>();
        final List<MemberView> members = new ArrayList<>();

        Builder(UUID id, String name, String dimension, String owner,
                BlockPos min, BlockPos max) {
            this.id = id;
            this.name = name;
            this.dimension = dimension;
            this.owner = owner;
            this.min = min;
            this.max = max;
        }

        TownView build() {
            return new TownView(id, name, dimension, owner, min, max,
                    List.copyOf(zones), List.copyOf(members));
        }
    }

    // ==========================================================
    //  打开 / 关闭
    // ==========================================================

    /** 手册右键：开屏幕 + 向服务端要最新数据。 */
    public static void open() {
        Minecraft.getInstance().setScreen(new TownHandbookScreen());
        ModNetworking.sendToServer(new ModNetworking.HandbookRequestPayload());
    }

    static void screenOpened(TownHandbookScreen screen) {
        openScreen = screen;
    }

    static void screenClosed() {
        openScreen = null;
    }
}
