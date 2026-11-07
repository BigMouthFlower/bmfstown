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
     * 书目登记点（启动时调用一次）。正式书单<b>用户还没定稿</b>——
     * 目前只有一本 ⚠ <b>测试书</b>（用户认可：用于打通学者线实测，
     * 书单定稿后删除）。空池 = 回退闲书（与"抽光"同一效果）。
     * 效果框架（{@link SpecialBookEffect}）与研读/单选链路已就绪，
     * 正式书目在这里 define 即生效。
     */
    public static void bootstrap() {
        // ---- ⚠ 测试书（测完即删）：战士书，有观察挂点，全链路可测 ----
        // 覆盖：写书抽取 → 50% 卡点 → 跟随见习 → 观战计数解除 → 写完
        //       → 架上阅读学成 → 效果单选生效 → 近战伤害 +15% 验数
        define(new SpecialBook("test_warrior",
                new BookDraft.Perk("test_warrior", "warrior", 0, 30),
                6000L, // 研读 6000t（游戏 6 小时 = 现实 5 分钟），几个休闲段可学完
                java.util.List.of(new SpecialBookEffect(
                        SpecialBookEffect.Type.MELEE_DAMAGE, 15))));
    }

    static {
        // ---- 秘传配方书（v0.12 炼金，用户 2026-11-05 定稿十条全上；Unlock 类，无卡点） ----
        // 成书即 unlockIfNew（全局酿造门禁开放）+ 可打开阅读（书页写底药+催化剂）。
        String[][] secretRecipes = {
                {"alchemy_haste", "unlock.alchemy.haste"},
                {"alchemy_resistance", "unlock.alchemy.resistance"},
                {"alchemy_absorption", "unlock.alchemy.absorption"},
                {"alchemy_health_boost", "unlock.alchemy.health_boost"},
                {"alchemy_luck", "unlock.alchemy.luck"},
                {"alchemy_wither", "unlock.alchemy.wither"},
                {"alchemy_blindness", "unlock.alchemy.blindness"},
                {"alchemy_darkness", "unlock.alchemy.darkness"},
                {"alchemy_nausea", "unlock.alchemy.nausea"},
                {"alchemy_mining_fatigue", "unlock.alchemy.mining_fatigue"},
                {"alchemy_levitation", "unlock.alchemy.levitation"},
        };
        for (String[] r : secretRecipes) {
            BOOKS.put(r[0], new SpecialBook(r[0],
                    new BookDraft.Unlock(r[1]), 2000L, java.util.List.of()));
        }
    }

    private SpecialBookRegistry() {
    }
}
