package com.bmf.bmfstown.eerie;

import java.util.ArrayList;
import java.util.List;

/**
 * 恐怖故事线登记表（决策 #54/#57 恐怖探索线）。
 *
 * <p><b>内容纪律</b>：这里只有<b>用户已定稿</b>的故事。加一条新故事 =
 * 往 {@link #LINES} 加一个条目 + 往 lang 填文本，机制零改动。
 *
 * <h2>一条故事线的数据形状</h2>
 * <ul>
 *   <li>id：登记键（lang/公告/解锁 id 共用的路由名）；</li>
 *   <li><b>章数按线声明</b>（{@code chapters}）：第 i 格正文在 lang 键
 *       {@code book.bmfstown.horror.<id>.ch<i>}，总名在
 *       {@code book.bmfstown.horror.<id>.name}；
 *       <b>注意 {@code chapters} 数的是"推进几格"，不是"故事几卷"</b>
 *       ——两者可以不等（见 {@link #LINES} 的《雾中人》）；</li>
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
     * <p><b>内容纪律</b>：这里只有<b>用户已定稿</b>的故事。
     * <ul>
     *   <li>《岩石之令》— 9 卷，应验挂 {@link ExileEffect#ID}（流放地）。
     *       正文在 lang {@code book.bmfstown.horror.rocks_order.ch1~ch9}；
     *       见 {@code .ai/designs/horror_story_yanshi.md}。</li>
     *   <li>《雾中人》— 正文 6 卷，但<b>登记 7 格</b>：第 5 格是"陌生打断"
     *       （HIM 的 {@code Stop.}），机制上它就是一格，与故事卷数无关。
     *       该格物品名不带卷号、正文的暗红/银河字母，全部由 lang 决定
     *       （见 {@code FinishedBookItem.unlockTitle}）；
     *       应验效果留空 = 只发公告。见 {@code .ai/designs/horror_story_man_in_fog.md}。</li>
     *   <li>《小屋》— 5 卷对 5 格，<b>没有</b>"卷数≠格数"的特殊处理，也没有
     *       {@code .untitled} 卷：5 本都规规矩矩带卷号。它和前两条的差别在
     *       正文写法上——全篇没有怪物、没有声音、没有动作，只描述一间房子。
     *       应验效果暂留空（真实木屋方案见 {@code .ai/designs/horror_story_woodcutter_hut.md}）。</li>
     *   <li>《发光地衣》— 6 卷对 6 格。全条线唯一"全镇覆灭"：地衣从井台长到
     *       街道、牲口、人身上。执笔人是<b>外来者</b>（第四卷写"镇上的人"而非
     *       "我们"），不用改字就成立。应验留空——<b>红线：绝不能真杀镇民或
     *       往玩家建筑上糊地衣</b>，镇子是玩家一块砖搭的，毁掉是冒犯不是恐怖。
     *       见 {@code .ai/designs/horror_story_glow_lichen.md}。</li>
     *   <li>《日食》— 6 卷对 6 格。五条线里唯一<b>"物证归零"</b>：日食结束后
     *       火把重燃、水车重转、零改变，所以<b>手记本身是唯一物证</b>。
     *       应验留空不只是"没做"——原版无天空变暗接口（{@code setDayTime} 只让
     *       太阳消失、不会缺角；切雷暴则六项现象全反），真做要动客户端渲染；
     *       更关键是<b>不该做</b>：真黑一次就给了玩家"能再看一次"的东西，
     *       "物证归零"的内核被自己破坏。见 {@code .ai/designs/horror_story_eclipse.md}。</li>
     * </ul>
     *
     * <p><b>volume ≠ chapter</b>：`chapters` 数的是"推进几格"，不是"故事几卷"。
     * 两者不一致是合法且有意的（《雾中人》6 卷 7 格）。
     */
    private static final List<HorrorLine> LINES = new ArrayList<>(List.of(
            new HorrorLine("rocks_order", 9, ExileEffect.ID),
            // 6 卷正文 + 1 页无卷号的打断 = 7 格。别把它"对齐"成 6：
            // 那会让第 5 格的正文永远抽不到，第 7 卷变成没正文的空壳。
            new HorrorLine("man_in_fog", 7, ""),
            // 5 卷 5 格，最规整的一条：没有自带名字的卷，五本都带卷号。
            new HorrorLine("woodcutter_hut", 5, ""),
            // 6 卷 6 格。红线：应验绝不能真杀镇民/糊墙（镇子是玩家搭的）。
            new HorrorLine("glow_lichen", 6, ""),
            // 6 卷 6 格。应验留空是"不该做"不是"没做"：真黑一次就毁了"物证归零"。
            new HorrorLine("eclipse", 6, "")));

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