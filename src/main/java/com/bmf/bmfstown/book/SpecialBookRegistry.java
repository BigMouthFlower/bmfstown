package com.bmf.bmfstown.book;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

/**
 * 特殊书登记表（v0.11）：世界上有哪些特殊书，登记了才算存在——
 * 没登记的内容对抽奖系统来说不存在（与解锁登记表同一纪律）。
 *
 * <p><b>当前为空</b>（用户多次强调：特殊书还没想好，先留空——空池与
 * "池子被抽光"是同一个效果：30% 特殊书判定直接回退闲书）。书单定稿后
 * 往 {@link #bootstrap} 里追加即可，机制零改动，老城镇自动入池
 * （奖池补登记机制）。<b>在用户定稿书单之前，不要往这里登记任何书。</b>
 *
 * <p>每条登记 = 一本特殊书的全部定义：id（奖池键）、内容单、
 * 习得时长（极乐迪斯科式"不同书不同长度"）、习得效果表。
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
     * @param effects    习得效果表（装备已学时生效；空表 = 纯收藏）
     */
    public record SpecialBook(String id, BookDraft draft, long learnTicks,
                              java.util.List<SpecialBookEffect> effects) {

        public SpecialBook {
            effects = java.util.List.copyOf(effects); // 防御性不可变
        }
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

    /**
     * 书目登记点（启动时调用一次）。<b>保持为空</b>——特殊书书单用户
     * 还没定稿，空池 = 回退闲书（与"抽光"同一效果，用户定稿）。
     * 效果框架（{@link SpecialBookEffect}）与研读/装备链路已就绪，
     * 书单定稿后在这里 define 即生效。
     */
    public static void bootstrap() {
    }

    private SpecialBookRegistry() {
    }
}
