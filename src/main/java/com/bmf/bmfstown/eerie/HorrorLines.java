package com.bmf.bmfstown.eerie;

import java.util.ArrayList;
import java.util.List;

/**
 * 恐怖故事线登记表（决策 #54/#57 恐怖探索线）。
 *
 * <p><b>内容纪律</b>：正式故事线（标题/十格正文/解锁物/应验效果）全部
 * <b>留空等用户定稿</b>——这里只登记机制验证用的<b>测试线</b>（同
 * 《试炼之卷》先例，lang 键里明确标注测试占位）。用户定稿后往
 * {@link #LINES} 加条目 + 往 lang 填文本即可，机制零改动。
 *
 * <h2>一条故事线的数据形状</h2>
 * <ul>
 *   <li>id：登记键（lang/公告/解锁 id 共用的路由名）；</li>
 *   <li>十部制：每线固定 10 格（CHAPTERS，#54 拍板），第 i 格正文在
 *       lang 键 {@code book.bmfstown.horror.<id>.ch<i>}，总名在
 *       {@code book.bmfstown.horror.<id>.name}；</li>
 *   <li>解锁：第 10 格写齐时经 {@code UnlockState.unlockIfNew} 登记
 *       解锁 id {@code horror_<线id>}（隐藏解锁类，机制侧通用）；</li>
 *   <li>应验：解锁后 {@link WanderEvents#VERIFY_MIN_DAYS}~+跨度 游戏日内
 *       随机应验（效果按线登记，正式内容定稿前效果为空——只发公告）。</li>
 * </ul>
 */
public final class HorrorLines {

    /** 十部制：每条故事线固定 10 格（#54 用户拍板）。 */
    public static final int CHAPTERS = 10;

    /** 一条故事线的登记条目（内容全在 lang，机制侧只认 id）。 */
    public record HorrorLine(String id) {

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
    }

    /** 登记表（测试线占位；正式线等用户定稿，内容纪律不自行填充）。 */
    private static final List<HorrorLine> LINES = new ArrayList<>(List.of(
            new HorrorLine("test")));

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

    private HorrorLines() {
    }
}
