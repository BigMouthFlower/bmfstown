package com.bmf.bmfstown.client;

import com.bmf.bmfstown.network.ModNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * 刻碑选人界面（2026-11-28 决策 #144，用户当场改口径为"上下两段"）。
 *
 * <h2>交互口径（用户原话，2026-11-28）</h2>
 * > "右键打开一个界面，选择要刻碑的人，然后同时底下也会显示他的生平信息和墓志铭。"
 *
 * <p>所以是<b>一次点击 = 选中</b>、<b>同一个人点第二次 = 刻上</b>。
 * 不搞"点一下就直接刻"——那样玩家没机会看清这个人是谁就已经刻完了，
 * 而他可能只是想确认一下这块碑该刻谁。
 *
 * <h2>版面</h2>
 * <pre>
 *   ┌────────────── 立碑 · 刻谁 ──────────────┐
 *   │ 名字 ▲       死因                       │  ← 表头（可点排序）
 *   │ 张三         寿终      第 41 日         │  ← 名单（滚轮/点选）
 *   │ 李四         被杀      第 39 日  ◀选中  │
 *   ├─────────────────────────────────────────┤
 *   │ 李四                                    │  ← 生平（逐项成行）
 *   │ 享年 47 岁                              │
 *   │ 在此 12 年                              │
 *   │ 职业：战士                              │
 *   │ 被僵尸杀死                              │  ← 原版整句（#147）
 *   │ 击杀  214                               │
 *   ├─────────────────────────────────────────┤
 *   │ 点击选中　再点一次刻上去　ESC / E 关闭   │
 *   └─────────────────────────────────────────┘
 * </pre>
 *
 * <h2>为什么生平走服务端下发而不是客户端现拼</h2>
 * 名册在服务端（{@code GraveyardData}），客户端手里只有这一包数据。
 * 客户端拼就得把整份 {@code Grave} 复制一套到客户端，那是第二个真相源。
 * 现在服务端直接发<b>排好序的组件</b>（{@code ModNetworking.profileOf}），
 * 排序口径与碑文/命令共用 {@code GraveText} 一份。
 */
public class GravePickScreen extends Screen {

    private static final int W = 214;
    private static final int HEADER_H = 10;
    private static final int ROW_H = 14;
    private static final int VISIBLE_ROWS = 5;
    /**
     * 名字列宽；余下宽度给"死因 + 死亡日"。
     *
     * <p>2026-11-28 实测问题：面板首版只有 190 宽、名字占 92，右侧只剩 98px，
     * 右侧那列被 {@code plainSubstrByWidth} 截断，表头也挤成一坨。
     * 现在 214 宽、名字固定 88（姓名字号本就短），右侧留 126。
     *
     * <p>⚠ 右侧死因用 {@code GraveText.causeShortKey} 的<b>短类目</b>
     * （"被杀"/"烧死"），不是碑文那句原版整句（"被僵尸杀死"）——
     * 126px 塞不下整句，截断后比短词更难读（#147）。
     */
    private static final int NAME_W = 88;
    /** 每行空行也要占位（生平分段之间留白），所以用 12 行固定窗口。 */
    private static final int BIO_LINE_H = 11;

    /**
     * 生平区最多显示几行正文（装不下就滚，面板不无限长高）。
     *
     * <p>9 行 ≈ 99px + 名字行与留白，配 5 行名单（70px）+ 表头 + 标题，
     * 整屏约 260px，1080p 下留足上下余量。
     */
    private static final int BIO_MAX_LINES = 9;

    /** 生平滚动位置（px 太碎，按"屏幕行"滚）。 */
    private int bioScroll;

    /** 当前选中那人折好行的生平缓存（换人时重算）。 */
    private List<FormattedCharSequence> bioLines = List.of();
    private java.util.UUID bioFor;

    /** 这块屏幕要刻的那块碑（回包原样带回，点选时再发回去）。 */
    private final BlockPos pos;
    private final List<ModNetworking.GraveCandidate> candidates;

    private int scroll;

    /** 当前选中的行（在 {@link #sorted()} 里的下标）；-1 = 没选。 */
    private int selected = -1;

    /**
     * 已经点过一次、就等这一下确认的那一行（按 UUID，不按下标——
     * 排序一变下标就换人了，会把"确认"确认到别人身上）。
     */
    private java.util.UUID pendingConfirm;

    /** 排序：0 = 按死亡日，1 = 按名字。默认按死亡日升序（最早死的排最前）。 */
    private static int sortCol = 0;
    private static int sortDir = 0;

    private GravePickScreen(BlockPos pos, List<ModNetworking.GraveCandidate> candidates) {
        super(Component.translatable("gui.bmfstown.grave_pick.title"));
        this.pos = pos;
        this.candidates = candidates;
    }

    /** 打开入口（网络线程调用）：切主线程开屏。 */
    public static void openWith(ModNetworking.GravePickListPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(
                new GravePickScreen(payload.pos(), payload.candidates())));
    }

    // ==========================================================
    //  布局
    // ==========================================================

    /**
     * 生平区高度（含"名字行 + 若干正文行"）。
     *
     * <p>高度必须是 {@code 名字行 + 可见正文行 × 行高 + 上下留白} 的**完整账**，
     * 而且必须与 {@code render} 里那个绘制循环用<b>同一个</b>
     * {@link #visibleBioLines()}——两处各算一遍就会"画到框外"或"框里大片空白"
     * （坑83 的纵向版：面板高度是各段的账，不是拍脑袋）。
     */
    private int bioH() {
        // 13 = 名字行占的高度；+8 = 上下各 4px 留白
        return 13 + this.visibleBioLines() * BIO_LINE_H + 8;
    }

    /** 生平区一次能显示几行正文（未选中 = 1 行提示；选中后按内容夹上限）。 */
    private int visibleBioLines() {
        if (this.bioLines.isEmpty()) {
            return 1;
        }
        return Mth.clamp(this.bioLines.size(), 1, BIO_MAX_LINES);
    }

    private int totalH() {
        return 22 + HEADER_H + VISIBLE_ROWS * ROW_H + this.bioH() + 14;
    }

    private int x0() {
        return (this.width - W) / 2;
    }

    private int y0() {
        return (this.height - totalH()) / 2;
    }

    /** 列表（含表头）顶部 y；表头下面才是第一行。 */
    private int listY() {
        return this.y0() + 22;
    }

    /** 生平区顶部 y（列表底部 + 一条分隔线）。 */
    private int bioY() {
        return this.listY() + HEADER_H + VISIBLE_ROWS * ROW_H + 6;
    }

    // ==========================================================
    //  排序
    // ==========================================================

    private List<ModNetworking.GraveCandidate> sorted() {
        List<ModNetworking.GraveCandidate> list = new java.util.ArrayList<>(this.candidates);
        list.sort((a, b) -> {
            int cmp = sortCol == 0
                    ? Long.compare(a.deathDay(), b.deathDay())
                    : a.name().compareToIgnoreCase(b.name());
            return sortDir == 0 ? cmp : -cmp;
        });
        return list;
    }

    /** 当前选中的那个人（没选/越界返回 null）。 */
    private ModNetworking.GraveCandidate selectedCandidate() {
        var list = this.sorted();
        return this.selected >= 0 && this.selected < list.size()
                ? list.get(this.selected) : null;
    }

    /** 生平可用宽度（面板宽减左右内边距与滚动条槽）。 */
    private int bioWrapWidth() {
        return W - 16;
    }

    /**
     * 刷新生平折行缓存（选中的人变了才重算）。
     *
     * <p><b>为什么缓存</b>：折行是逐字测宽的（{@code Font.split} 要量每个字形），
     * 每帧重算一遍对一屏 5~9 行是白烧 CPU；而选中的人一帧里只会变一次。
     *
     * <p><b>为什么按 UUID 判变化而不是按下标</b>：排序一变下标就指向别人了，
     * 只比下标会出现"显示的是 A 的生平、选中的其实是 B"。
     */
    private void refreshBio() {
        var chosen = selectedCandidate();
        java.util.UUID key = chosen == null ? null : chosen.id();
        if (java.util.Objects.equals(key, this.bioFor)) {
            return;
        }
        this.bioFor = key;
        this.bioScroll = 0;
        if (chosen == null) {
            this.bioLines = List.of();
            return;
        }
        List<FormattedCharSequence> out = new java.util.ArrayList<>();
        var list = chosen.profile();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                out.add(FormattedCharSequence.EMPTY); // 分段之间留一行
            }
            out.addAll(this.font.split(list.get(i), bioWrapWidth()));
        }
        this.bioLines = out;
    }

    // ==========================================================
    //  渲染
    // ==========================================================

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(gfx, mouseX, mouseY, partialTick);
        this.refreshBio(); // 选中的人变了就重算折行（否则高度/滚动都是错的）
        int x0 = this.x0();
        int y0 = this.y0();
        gfx.fill(x0, y0, x0 + W, y0 + totalH(), 0xF0202028);
        gfx.fill(x0, y0, x0 + W, y0 + 1, 0xFF8A7A55);
        gfx.drawCenteredString(this.font, this.title, this.width / 2, y0 + 6, 0xFFE0C878);

        // ---- 名单为空：直接说清楚，不画一个空列表 ----
        if (this.candidates.isEmpty()) {
            gfx.drawCenteredString(this.font,
                    I18n.get("gui.bmfstown.grave_pick.none"),
                    this.width / 2, y0 + totalH() / 2, 0xFFB0B0B0);
            gfx.drawCenteredString(this.font,
                    I18n.get("gui.bmfstown.grave_pick.none_hint"),
                    this.width / 2, y0 + totalH() / 2 + 12, 0xFF7A7A7A);
            return;
        }

        // ---- 表头 ----
        // ⚠ 表头键里**不许有 %s**（2026-11-28 实测 "Format error: 死因 / 第 %s 日"）：
        // I18n.get(key) 无参调用走 String.format，值里带占位符就抛
        // IllegalFormatException，原版把它 catch 成 "Format error: <原文>"。
        // 表头是"列名"，本来就不该带"第 N 日"这种每行各异的值——
        // 那是数据行的事，见下面的行绘制。
        int headerY = this.listY();
        int[] colX = {x0, x0 + NAME_W};
        int[] colW = {NAME_W, W - NAME_W};
        String[] labels = {
                I18n.get("gui.bmfstown.grave_pick.col_name"),
                I18n.get("gui.bmfstown.grave_pick.col_death")};
        for (int col = 0; col < 2; col++) {
            int cx = colX[col];
            boolean hov = mouseX >= cx && mouseX < cx + colW[col]
                    && mouseY >= headerY && mouseY < headerY + HEADER_H;
            gfx.fill(cx, headerY, cx + colW[col], headerY + HEADER_H,
                    hov ? 0xFF6A5A40 : 0xFF403828);
            String label = labels[col];
            if (sortCol == col) {
                label += sortDir == 0 ? " ▲" : " ▼";
            }
            gfx.drawString(this.font, label, cx + 3, headerY + 1, 0xFFE0C878, false);
        }

        // ---- 行 ----
        var list = sorted();
        int max = Math.max(0, list.size() - VISIBLE_ROWS);
        this.scroll = Mth.clamp(this.scroll, 0, max);
        if (this.selected >= list.size()) {
            this.selected = list.size() - 1;
        }
        for (int v = 0; v < VISIBLE_ROWS; v++) {
            int idx = this.scroll + v;
            if (idx >= list.size()) {
                break;
            }
            var c = list.get(idx);
            int ry = headerY + HEADER_H + v * ROW_H;
            boolean hov = mouseX >= x0 && mouseX < x0 + W
                    && mouseY >= ry && mouseY < ry + ROW_H;
            boolean sel = idx == this.selected;
            // 选中行常亮，悬停行淡亮：两种状态不能长得一样，否则玩家不知道点了没点上
            gfx.fill(x0, ry, x0 + W, ry + ROW_H,
                    sel ? 0x60C0A070 : (hov ? 0x30C0A070 : 0x00000000));
            String name = this.font.plainSubstrByWidth(c.name(), NAME_W - 6);
            gfx.drawString(this.font, name, x0 + 3, ry + 3,
                    sel ? 0xFFFFE8B0 : 0xFFE8E8E8, false);
            // 右侧：死因短标签 + 死亡日。标签走 {@code GraveText.causeShortKey}
            // （"被杀"/"老死"…），**不是**原版整句——整句在下面的生平区里。
            //
            // ⚠ 死亡日必须**参数化**（I18n.get(key, arg)）——这是每行各异的数据。
            // 表头是列名、不带数字，两者分工别混（混了就是用户截图里
            // "Format error: 死因 / 第 %s 日" 的来源）。
            String right = I18n.get(c.causeKey())
                    + "　" + I18n.get("gui.bmfstown.grave_pick.day", c.deathDay());
            gfx.drawString(this.font,
                    this.font.plainSubstrByWidth(right, W - NAME_W - 6),
                    x0 + NAME_W + 3, ry + 3, 0xFF9A9A9A, false);
        }

        // ---- 生平区 ----
        int bioY = this.bioY();
        gfx.fill(x0 + 4, bioY - 4, x0 + W - 4, bioY - 3, 0xFF5A5A5A);
        var chosen = selectedCandidate();
        if (chosen == null) {
            gfx.drawCenteredString(this.font,
                    I18n.get("gui.bmfstown.grave_pick.pick_hint"),
                    this.width / 2, bioY + 6, 0xFF7A7A7A);
        } else {
            gfx.drawString(this.font, chosen.name(), x0 + 6, bioY, 0xFFE0C878, false);
            // 生平 = 折好行的 FormattedCharSequence 列表（refreshBio 算好的）。
            // ⚠ 不能再 split("\n")：中文没有空格，靠 "\n" 分段的话每一段
            // 都会超宽被截断，玩家看到的就是"只有一个死因"（2026-11-28 实测）。
            int shown = this.visibleBioLines();
            int maxScroll = Math.max(0, this.bioLines.size() - shown);
            this.bioScroll = Mth.clamp(this.bioScroll, 0, maxScroll);
            int ly = bioY + 13;
            int bottom = bioY + this.bioH() - 4;
            for (int i = 0; i < shown; i++) {
                int idx = this.bioScroll + i;
                if (idx >= this.bioLines.size() || ly + 9 > bottom) {
                    break;
                }
                gfx.drawString(this.font, this.bioLines.get(idx),
                        x0 + 8, ly, 0xFFB8B8B8, false);
                ly += BIO_LINE_H;
            }
            // 生平也能滚（人多、统计多时超一屏）：右缘一条窄轨道
            if (maxScroll > 0) {
                int trackX = x0 + W - 5;
                int trackTop = bioY + 13;
                int trackH = shown * BIO_LINE_H;
                gfx.fill(trackX, trackTop, trackX + 2, trackTop + trackH, 0x40FFFFFF);
                int thumbH = Math.max(8, trackH * shown / this.bioLines.size());
                int thumbY = trackTop + (trackH - thumbH) * this.bioScroll
                        / Math.max(1, maxScroll);
                gfx.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0xFF8A7A55);
            }
        }

        // ---- 底部提示：按"选中了没"换一句，玩家才知道下一步要点第二下 ----
        // ⚠ hint_confirm 带 %s（要点的那个人名）。这里**不用 I18n.get(key, args)**：
        // 那走 String.format，而居民名字是玩家可改的，名字里出现 "%" 会抛异常/错位。
        // 改成把名字当一个独立组件拼进去，名字里有任何字符都安全。
        Component hint = chosen == null
                ? Component.translatable("gui.bmfstown.grave_pick.hint")
                : Component.translatable("gui.bmfstown.grave_pick.hint_confirm",
                        chosen.name());
        gfx.drawCenteredString(this.font, hint,
                this.width / 2, y0 + totalH() - 11, 0xFF8A8A8A);
    }

    // ==========================================================
    //  输入
    // ==========================================================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        int x0 = this.x0();
        int headerY = this.listY();
        // ---- 表头排序 ----
        if (mouseY >= headerY && mouseY < headerY + HEADER_H) {
            int[] colX = {x0, x0 + NAME_W};
            int[] colW = {NAME_W, W - NAME_W};
            for (int col = 0; col < 2; col++) {
                if (mouseX >= colX[col] && mouseX < colX[col] + colW[col]) {
                    if (sortCol == col) {
                        sortDir ^= 1;
                    } else {
                        sortCol = col;
                        // 首点降序（Gattaca 列表同款手感）：点"名字"先看到 Z→A，
                        // 点"死亡日"先看到最近死的——按最常用的意图取
                        sortDir = 1;
                    }
                    // 排序变了，pendingConfirm 按下标确认就错了 ⇒ 清掉，重新点
                    this.pendingConfirm = null;
                    return true;
                }
            }
            return true;
        }
        // ---- 行：第一次点选中，第二次点（同一人）刻上 ----
        var list = sorted();
        int max = Math.max(0, list.size() - VISIBLE_ROWS);
        int sc = Mth.clamp(this.scroll, 0, max);
        if (mouseX >= x0 && mouseX < x0 + W && mouseY >= headerY + HEADER_H
                && mouseY < headerY + HEADER_H + VISIBLE_ROWS * ROW_H) {
            int v = (int) ((mouseY - headerY - HEADER_H) / ROW_H);
            int idx = sc + v;
            if (idx >= 0 && idx < list.size()) {
                var c = list.get(idx);
                if (this.selected != idx) {
                    // 换人 = 只看不刻（这一下是"选中"）
                    this.selected = idx;
                    this.pendingConfirm = c.id();
                    return true;
                }
                // 同一人再点一次 = 确认刻上
                ModNetworking.sendToServer(
                        new ModNetworking.GraveCarvePayload(this.pos, c.id()));
                onClose();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        this.scroll -= (int) Math.signum(dy);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ESC / E 关闭（与手册/列表屏一致）
        if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_E) {
            onClose();
            return true;
        }
        // 上下键换选中行，回车确认（键盘也能走完整个流程）
        if (keyCode == GLFW.GLFW_KEY_DOWN || keyCode == GLFW.GLFW_KEY_UP) {
            var list = sorted();
            if (list.isEmpty()) {
                return true;
            }
            int delta = keyCode == GLFW.GLFW_KEY_DOWN ? 1 : -1;
            this.selected = Mth.clamp(this.selected + delta, 0, list.size() - 1);
            // 保证选中行在可视范围内
            if (this.selected < this.scroll) {
                this.scroll = this.selected;
            } else if (this.selected >= this.scroll + VISIBLE_ROWS) {
                this.scroll = this.selected - VISIBLE_ROWS + 1;
            }
            this.pendingConfirm = null;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            var chosen = selectedCandidate();
            if (chosen != null) {
                ModNetworking.sendToServer(
                        new ModNetworking.GraveCarvePayload(this.pos, chosen.id()));
                onClose();
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}