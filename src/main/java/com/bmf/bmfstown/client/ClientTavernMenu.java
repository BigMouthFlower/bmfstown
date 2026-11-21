package com.bmf.bmfstown.client;

import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 酒吧菜单板·客户端缓存（#86 升格）：服务端算好下发的快照（菜单+库存+豁免
 * 标签）落在静态字段，{@link TavernMenuScreen} 每帧直接读——编辑回包自动刷新
 * 已开面板（listener 回调）。数据不经实体同步快照/菜单数据槽（面板历史坑）。
 */
public final class ClientTavernMenu {

    /** 一行菜单：物品 id + 目标量 + 在手量（排产同源口径）+ 豁免标签。 */
    public record Entry(String itemId, int target, int stock, boolean exempt) {
    }

    private static volatile List<Entry> entries = List.of();
    private static volatile boolean editable;
    private static volatile boolean hasData;
    private static volatile BlockPos boardPos = BlockPos.ZERO;
    /** 已开面板的刷新回调（accept 时若面板开着就推一把）。 */
    private static volatile Runnable refreshListener;

    private ClientTavernMenu() {
    }

    /** S2C 入口：存快照；open=true 开新面板，否则回调刷新已开面板。 */
    public static void accept(boolean editableFlag, boolean open, BlockPos pos,
            List<String> raw) {
        java.util.List<Entry> parsed = new java.util.ArrayList<>(raw.size());
        for (String line : raw) {
            String[] parts = line.split("\\|", -1);
            if (parts.length < 4) {
                continue;
            }
            try {
                parsed.add(new Entry(parts[0], Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]), "1".equals(parts[3])));
            } catch (NumberFormatException ignored) {
                // 野行跳过（协议内部错误不该炸客户端）
            }
        }
        entries = List.copyOf(parsed);
        editable = editableFlag;
        boardPos = pos.immutable();
        hasData = true;
        Runnable l = refreshListener;
        if (open) {
            net.minecraft.client.Minecraft.getInstance().setScreen(new TavernMenuScreen());
        } else if (l != null) {
            l.run();
        }
    }

    public static List<Entry> entries() {
        return entries;
    }

    public static boolean editable() {
        return editable;
    }

    public static boolean hasData() {
        return hasData;
    }

    public static BlockPos boardPos() {
        return boardPos;
    }

    public static void setRefreshListener(@Nullable Runnable listener) {
        refreshListener = listener;
    }
}
