package com.bmf.bmfstown.book;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

/**
 * 特殊书登记表（v0.11）：世界上有哪些特殊书，登记了才算存在——
 * 没登记的内容对抽奖系统来说不存在（与解锁登记表同一纪律）。
 *
 * <p><b>当前为空</b>（用户定稿：先把系统做出来，perk 池之后往里添）。
 * 抽奖机制对空池天然正确：{@link BookPoolState#draw} 返回 null，
 * 学者回退写闲书——登记表填上即生效，机制零改动。
 *
 * <p>每条登记 = 一本特殊书的全部定义：id（奖池键）、内容单、
 * 习得时长（极乐迪斯科式"不同书不同长度"）。
 */
public final class SpecialBookRegistry {

    /** id → 书定义。LinkedHashMap 保持登记顺序（调试/图鉴展示顺序稳定）。 */
    private static final Map<String, SpecialBook> BOOKS = new LinkedHashMap<>();

    /**
     * 一本特殊书的定义。
     *
     * @param id         唯一键（奖池键 / 书 NBT 里的 perkId / unlockId）
     * @param draft      内容单（职业强化书或隐藏解锁书）
     * @param learnTicks 装备后研读习得所需 tick（不同书不同长度）
     */
    public record SpecialBook(String id, BookDraft draft, long learnTicks) {
    }

    /** 登记一本特殊书。重复 id 拒收（早失败，防手滑写重）。 */
    public static void define(SpecialBook book) {
        if (BOOKS.containsKey(book.id())) {
            throw new IllegalStateException("特殊书重复登记: " + book.id());
        }
        BOOKS.put(book.id(), book);
    }

    public static SpecialBook byId(String id) {
        return BOOKS.get(id);
    }

    /** 全部登记的书（只读视图）。 */
    public static Map<String, SpecialBook> all() {
        return Collections.unmodifiableMap(BOOKS);
    }

    private SpecialBookRegistry() {
    }
}
