package com.bmf.bmfstown.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 成书翻页（客户端专属）：把确定性重放的正文页直接塞进原版
 * {@link BookViewScreen}。
 *
 * <h2>为什么不走原版 WrittenBookItem 的链路</h2>
 * 原版 {@code ServerPlayer.openItemGui} 写死 {@code is(Items.WRITTEN_BOOK)}，
 * 自定义物品过不去；而客户端 {@code BookAccess.fromItem} 只认组件——
 * 且 {@code BookAccess} 是可直接构造的 record。所以干脆不发包、不往物品上
 * 注入 {@code written_book_content} 组件：本地按内容单重放正文页，
 * 直接构造 BookAccess 打开界面。服务端零参与，老档成书/抄本天然支持。
 */
public final class BookViewOpener {

    private BookViewOpener() {
    }

    /** 用给定的正文页打开原版翻书界面（仅客户端调用）。 */
    public static void open(List<Component> pages) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) {
            return;
        }
        mc.setScreen(new BookViewScreen(new BookViewScreen.BookAccess(List.copyOf(pages))));
    }
}
