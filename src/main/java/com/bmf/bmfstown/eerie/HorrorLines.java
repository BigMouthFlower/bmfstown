package com.bmf.bmfstown.eerie;

import java.util.ArrayList;
import java.util.List;

/**
 * 恐怖故事线登记表（决策 #54/#57 恐怖探索线）。
 *
 * <p><b>内容纪律</b>：正式故事线（标题/正文/解锁物/应验效果）全部
 * <b>留空等用户定稿</b>——这里只登记机制验证用的<b>测试线</b>（同
 * 《试炼之卷》先例，lang 键里明确标注测试占位）。用户定稿后往
 * {@link #LINES} 加条目 + 往 lang 填文本即可，机制零改动。
 *
 * <h2>一条故事线的数据形状</h2>
 * <ul>
 *   <li>id：登记键（lang/公告/解锁 id 共用的路由名）；</li>
 *   <li><b>章数按线声明</b>（{@code chapters}）：第 i 格正文在 lang 键
 *       {@code book.bmfstown.horror.<id>.ch<i>}，总名在
 *       {@code book.bmfstown.horror.<id>.name}；</li>
 *   <li><b>应验效果按线声明</b>（{@code effectId}）：解锁后若干日内应验时
 *       由 {@link VerificationEffects} 按 id 查执行器——故事线只管"我要哪种
 *       应验"，不写代码；空串 = 只发公告、无异象（当前的默认行为）；</li>
 *   <li>解锁：最后一格写齐时经 {@code UnlockState.unlockIfNew} 登记
 *       解锁 id {@code horror_<线id>}（隐藏解锁类，机制侧通用）；</li>
 *   <li>应验：解锁后 {@link WanderEvents#VERIFY_MIN_DAYS}~+跨度 游戏日内
 *       随机应验（效果按 {@code effectId} 挂接）。</li>
 * </ul>
 *
 * <h2>为什么章数不再是全局常量（2026-11-25 用户拍板）</h2>
 * 原设计（#54）写死"每线固定 10 格"，实装后变成"故事必须凑十页"的模板——
 * 用户原话：<i>"章节数目无所谓吧，不一定要 10 篇，故事完整最好，强行变长
 * 变短好吗"</i>。故事完整优先于格数整齐，因此改为<b>每条线自带章数</b>：
 * 短故事 6 格、长故事 12 格都合法，机制按 {@code line.chapters()} 判定解锁，
 * 公告栏也按该线的总章数显示进度。
 */
public final class HorrorLines {

    /** 未显式声明章数时的兜底（#54 原口径）。新故事线请显式写章数。 */
    public static final int DEFAULT_CHAPTERS = 10;

    /**
     * 一条故事线的登记条目（内容全在 lang，机制侧只认 id/章数/效果）。
     *
     * @param id       线 id（lang/公告/解锁 id 的路由名）
     * @param chapters 该线的总章数（写齐第 chapters 格即解锁）
     * @param effectId 应验效果 id（{@link VerificationEffects} 注册名；空串=只发公告）
     */
    public record HorrorLine(String id, int chapters, String effectId) {

        public HorrorLine {
            if (chapters < 1) {
                throw new IllegalArgumentException("chapters 必须 >= 1：" + id + " = " + chapters);
            }
            if (effectId == null) {
                effectId = "";
            }
        }

        /** 只声明章数（应验效果 = 只发公告）。 */
        public HorrorLine(String id, int chapters) {
            this(id, chapters, "");
        }

        /** 故事总名 lang 键。 */
        public String titleKey() {
            return "book.bmfstown.horror." + this.id + ".name";
        }

        /** 第 i 格（1 起）正文 lang 键。 */
        public String chapterKey(int chapter) {
            return "book.bmfstown.horror." + this.id + ".ch" + chapter;
        }

        /** 解锁系统用的解锁 id。 */
        public String unlockId() {
            return "horror_" + this.id;
        }

        /** 是否挂接了应验异象（false = 应验时只发公告）。 */
        public boolean hasEffect() {
            return !this.effectId.isEmpty();
        }
    }

    /**
     * 正式登记表。
     *
     * <p><b>内容纪律</b>：这里只有<b>用户已定稿</b>的故事。《岩石之令》正文在
     * lang（{@code book.bmfstown.horror.rocks_order.ch1~ch9}）；章数=9
     * （2026-11-25 用户拍板"故事完整最好，不强行凑十格"；当晚换成重写版
     * 九卷全文，见 {@code .ai/designs/horror_story_yanshi.md}）；应验挂
     * {@link ExileEffect#ID}（流放地）。
     */
    private static final List<HorrorLine> LINES = new ArrayList<>(List.of(
            new HorrorLine("rocks_order", 9, ExileEffect.ID)));

    /** 全部已登记故事线（只读视图）。 */
    public static List<HorrorLine> all() {
        return List.copyOf(LINES);
    }

    /** 按 id 查线；未登记返回 null。 */
    public static HorrorLine byId(String id) {
        for (HorrorLine line : LINES) {
            if (line.id().equals(id)) {
                return line;
            }
        }
        return null;
    }

    // ==========================================================
    //  测试接缝（GameTest 用来验证"按线章数"，勿在生产路径调用）
    // ==========================================================

    /**
     * 临时把登记表换成指定内容，返回原表以便还原（GameTest 专用）。
     *
     * <p><b>为什么不是"追加一条线"</b>：{@code pickLine} 是随机抽线的，
     * 往长表里塞一条短测试线，首次命中可能抽到别的线，用例就变成掷骰子
     * （2026-11-25 首版就这么红了一次）。要可靠验证"3 章线在第 3 格解锁"，
     * 必须让表里<b>只有</b>那一条线。调用方用 {@code try/finally} 配对
     * {@link #restoreAfterTest(List)}，异常路径也不会污染其他用例。
     */
    public static List<HorrorLine> swapForTest(List<HorrorLine> lines) {
        List<HorrorLine> old = List.copyOf(LINES);
        LINES.clear();
        LINES.addAll(lines);
        return old;
    }

    /** 还原 {@link #swapForTest} 换过的登记表（GameTest 专用）。 */
    public static void restoreAfterTest(List<HorrorLine> old) {
        LINES.clear();
        LINES.addAll(old);
    }

    private HorrorLines() {
    }
}