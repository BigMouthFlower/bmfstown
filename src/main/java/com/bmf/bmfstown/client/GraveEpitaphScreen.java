package com.bmf.bmfstown.client;

import com.bmf.bmfstown.network.ModNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 碑文界面（2026-11-28 决策 #146，用户当场打回聊天栏方案）。
 *
 * <h2>为什么必须有这个屏</h2>
 * 用户原话：
 * <blockquote>
 * "刻上之后就没有 ui 了，什么狗屎，而且以后墓志铭会很长，
 * 你也要就这样放聊天框吗，蠢死了，顾头不顾尾。"
 * </blockquote>
 * 聊天栏是<b>流水账</b>：碑文越长越会把对话顶掉、滚走就看不回来。
 * 碑文是"一件东西"，得有一个能停下来读的地方。所以：<b>刻完立刻弹这里</b>，
 * 以后右键已刻的碑也是这里——同一个出口，看到的东西永远一样。
 *
 * <h2>排版：分段是内容，折行是排版（两件事不能混）</h2>
 * 服务端发来的是<b>逻辑分段</b>（{@code GraveText.epitaphLines}：享年一行、
 * 职业一行、死因一行、每条统计一行）。客户端只负责在每段内<b>按像素宽度折行</b>。
 *
 * <p><b>为什么不能在服务端折行</b>：服务端不知道玩家窗口多宽、用的什么语言
 * （中英文宽度差一倍）。<b>为什么不能让客户端 split 一个长字符串</b>：
 * 那等于把"一行是什么"在客户端重新定义一次，与碑文/命令的口径就分家了。
 *
 * <h2>中文必须能断行</h2>
 * 用原版 {@code Font.split(FormattedText, maxWidth)}——它按<b>像素宽度</b>
 * 逐字切，不依赖空格。{@code plainSubstrByWidth} 只能截断不能换行，
 * 用它做正文会把长碑文后半截直接吞掉（正是"顾头不顾尾"）。
 */
public class GraveEpitaphScreen extends Screen {

    private static final int W = 220;
    /** 正文区左右内边距。 */
    private static final int PAD = 10;
    private static final int LINE_H = 11;
    /** 面板顶到章头之间的留白。 */
    private static final int TITLE_H = 20;
    /** 底部提示占的高度。 */
    private static final int FOOT_H = 16;
    /** 正文区最少/最多行数（内容多就滚，面板不无限长高）。 */
    private static final int MIN_LINES = 5;
    private static final int MAX_LINES = 14;

    private final String name;
    /** 已折好行的正文缓存（构造时算一次；窗口缩放会重建屏幕，缓存随之失效）。 */
    private final List<FormattedCharSequence> wrapped;
    private int scroll;

    private GraveEpitaphScreen(String name, List<Component> lines) {
        super(Component.translatable("gui.bmfstown.grave_epitaph.title"));
        this.name = name;
        this.wrapped = wrap(lines);
    }

    /** 入口（网络线程调用）：切主线程开屏。 */
    public static void openWith(ModNetworking.GraveEpitaphPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(
                new GraveEpitaphScreen(payload.name(), payload.lines())));
    }

    /**
     * 把"逻辑分段"折成"屏幕行"。
     *
     * <p>每段之间留一行空行（{@code ""}）：碑文的段是"享年/职业/死因/统计"，
     * 挤在一起读起来是一坨。空行用空组件占位，滚动的行号才连续。
     */
    private static List<FormattedCharSequence> wrap(List<Component> lines) {
        List<FormattedCharSequence> out = new ArrayList<>();
        var font = Minecraft.getInstance().font;
        int width = W - PAD * 2;
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                out.add(FormattedCharSequence.EMPTY);
            }
            out.addAll(font.split(lines.get(i), width));
        }
        return out;
    }

    private int textLines() {
        return Mth.clamp(this.wrapped.size(), MIN_LINES, MAX_LINES);
    }

    private int totalH() {
        return TITLE_H + this.textLines() * LINE_H + FOOT_H;
    }

    private int x0() {
        return (this.width - W) / 2;
    }

    private int y0() {
        return (this.height - totalH()) / 2;
    }

    private int bodyY() {
        return this.y0() + TITLE_H;
    }

    private int maxScroll() {
        return Math.max(0, this.wrapped.size() - this.textLines());
    }

    // ==========================================================
    //  渲染
    // ==========================================================

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(gfx, mouseX, mouseY, partialTick);
        int x0 = this.x0();
        int y0 = this.y0();

        // 面板：深底 + 一条暖色顶棱（与选人界面同一套视觉语言）
        gfx.fill(x0, y0, x0 + W, y0 + totalH(), 0xF018181C);
        gfx.fill(x0, y0, x0 + W, y0 + 1, 0xFF8A7A55);

        // ---- 章头：墓志铭三字 + 死者名 ----
        gfx.drawCenteredString(this.font,
                I18n.get("gui.bmfstown.grave_epitaph.title"),
                this.width / 2, y0 + 6, 0xFFE0C878);
        gfx.drawCenteredString(this.font,
                Component.literal(this.name),
                this.width / 2, y0 + 17, 0xFFFFF0C0);

        // ---- 正文：逐屏幕行画，超出就滚 ----
        this.scroll = Mth.clamp(this.scroll, 0, this.maxScroll());
        int ly = this.bodyY() + 4;
        int shown = this.textLines();
        for (int i = 0; i < shown; i++) {
            int idx = this.scroll + i;
            if (idx >= this.wrapped.size()) {
                break;
            }
            gfx.drawString(this.font, this.wrapped.get(idx),
                    x0 + PAD, ly, 0xFFD8D8D8, false);
            ly += LINE_H;
        }

        // ---- 滚动条：只有内容超过一屏才画（没超还画一条，玩家会以为滚不动是 bug）----
        if (this.maxScroll() > 0) {
            int trackX = x0 + W - 5;
            int trackTop = this.bodyY() + 4;
            int trackH = shown * LINE_H;
            gfx.fill(trackX, trackTop, trackX + 2, trackTop + trackH, 0x40FFFFFF);
            int thumbH = Math.max(8, trackH * shown / this.wrapped.size());
            int thumbY = trackTop + (trackH - thumbH) * this.scroll
                    / Math.max(1, this.maxScroll());
            gfx.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0xFF8A7A55);
        }

        // ---- 底部提示：能滚就说"滚轮翻页"，不能滚就只说怎么关 ----
        String hint = this.maxScroll() > 0
                ? I18n.get("gui.bmfstown.grave_epitaph.scroll")
                        + "　·　" + I18n.get("gui.bmfstown.grave_epitaph.hint")
                : I18n.get("gui.bmfstown.grave_epitaph.hint");
        gfx.drawCenteredString(this.font, hint,
                this.width / 2, y0 + totalH() - 11, 0xFF8A8A8A);
    }

    // ==========================================================
    //  输入
    // ==========================================================

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        this.scroll -= (int) Math.signum(dy);
        this.scroll = Mth.clamp(this.scroll, 0, this.maxScroll());
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_E) {
            onClose();
            return true;
        }
        // 上下键也能滚（长碑文用键盘更顺手，与选人界面一致）
        if (keyCode == GLFW.GLFW_KEY_DOWN || keyCode == GLFW.GLFW_KEY_UP) {
            this.scroll += keyCode == GLFW.GLFW_KEY_DOWN ? 1 : -1;
            this.scroll = Mth.clamp(this.scroll, 0, this.maxScroll());
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