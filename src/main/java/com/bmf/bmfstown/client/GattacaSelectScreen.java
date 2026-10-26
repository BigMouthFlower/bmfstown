package com.bmf.bmfstown.client;

import com.bmf.bmfstown.network.ModNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * GATTACA 模仿选择列表（用户定稿）：复用手册居民列表的交互
 * （表头名字/职业循环排序，首点降序；行点击打开对方面板查看），
 * 但只开这一个列表屏，不打开整个手册。
 *
 * <p>约束与手册居民列表一致：免交互锁（NPC 该干啥干啥）、免距离；
 * 对方面板里"传送"位置是"伪装"按钮——点了复制数据并回到观察者自己的 UI。
 * 退出：ESC / E。
 */
public class GattacaSelectScreen extends Screen {

    private static final int W = 110;
    private static final int HEADER_H = 10;
    private static final int ROW_H = 14;
    private static final int VISIBLE_ROWS = 7;
    private static final int COL_W = 52;

    /** 观察者（GATTACA 本尊）实体 ID。 */
    private final int observerId;
    private static int sortCol = 0;
    private static int sortDir = 0;
    private int scroll;
    private int listY;

    private GattacaSelectScreen(int observerId) {
        super(Component.translatable("gui.bmfstown.gattaca.title"));
        this.observerId = observerId;
    }

    /** 打开列表（用后即取返回值 setScreen）。 */
    public static GattacaSelectScreen open(int observerId) {
        return new GattacaSelectScreen(observerId);
    }

    @Override
    protected void init() {
        this.listY = (this.height - totalH()) / 2 + 20;
    }

    private int totalH() {
        return 20 + HEADER_H + VISIBLE_ROWS * ROW_H + 8;
    }

    /** 观察者所在城镇的居民（复用手册同步数据；找不到城镇就空列表）。模仿对象排除自己。 */
    private List<TownHandbookClient.MemberView> members() {
        for (TownHandbookClient.TownView town : TownHandbookClient.towns()) {
            boolean containsObserver = false;
            List<TownHandbookClient.MemberView> others = new ArrayList<>();
            for (TownHandbookClient.MemberView m : town.members()) {
                if (m.entityId() == this.observerId) {
                    containsObserver = true;
                } else {
                    others.add(m);
                }
            }
            if (containsObserver) {
                return sorted(others);
            }
        }
        return List.of();
    }

    private List<TownHandbookClient.MemberView> sorted(List<TownHandbookClient.MemberView> src) {
        List<TownHandbookClient.MemberView> list = new ArrayList<>(src);
        list.sort((a, b) -> {
            int cmp;
            if (sortCol == 0) {
                cmp = a.name().compareToIgnoreCase(b.name());
            } else {
                cmp = I18n.get("profession.bmfstown." + a.professionId())
                        .compareToIgnoreCase(I18n.get("profession.bmfstown." + b.professionId()));
            }
            return sortDir == 0 ? cmp : -cmp;
        });
        return list;
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(gfx, mouseX, mouseY, partialTick);
        int x0 = (this.width - W) / 2;
        int y0 = (this.height - totalH()) / 2;
        gfx.fill(x0, y0, x0 + W, y0 + totalH(), 0xF0202028);
        gfx.fill(x0, y0, x0 + W, y0 + 1, 0xFF8A7A55);
        gfx.drawCenteredString(this.font, this.title, this.width / 2, y0 + 6, 0xFFE0C878);

        // 表头
        int headerY = this.listY;
        for (int col = 0; col < 2; col++) {
            int cx = x0 + col * COL_W;
            boolean hov = mouseX >= cx && mouseX < cx + COL_W && mouseY >= headerY
                    && mouseY < headerY + HEADER_H;
            gfx.fill(cx, headerY, cx + COL_W, headerY + HEADER_H, hov ? 0xFF6A5A40 : 0xFF403828);
            String label = I18n.get(col == 0
                    ? "gui.bmfstown.handbook.member_name"
                    : "gui.bmfstown.handbook.member_prof");
            if (sortCol == col) {
                label += sortDir == 0 ? " ▲" : " ▼";
            }
            gfx.drawString(this.font, label, cx + 3, headerY + 1, 0xFFE0C878, false);
        }

        // 行
        var list = members();
        int max = Math.max(0, list.size() - VISIBLE_ROWS);
        this.scroll = Mth.clamp(this.scroll, 0, max);
        for (int v = 0; v < VISIBLE_ROWS; v++) {
            int idx = this.scroll + v;
            if (idx >= list.size()) {
                break;
            }
            var m = list.get(idx);
            int ry = headerY + HEADER_H + v * ROW_H;
            boolean hov = mouseX >= x0 && mouseX < x0 + W && mouseY >= ry && mouseY < ry + ROW_H;
            gfx.fill(x0, ry, x0 + W, ry + ROW_H, hov ? 0x40C0A070 : 0x00000000);
            gfx.drawString(this.font,
                    this.font.plainSubstrByWidth(m.name(), COL_W - 4), x0 + 3, ry + 3, 0xFFE8E8E8, false);
            gfx.drawString(this.font,
                    this.font.plainSubstrByWidth(I18n.get("profession.bmfstown." + m.professionId()),
                            COL_W - 4), x0 + COL_W + 3, ry + 3, 0xFF9A9A9A, false);
        }

        gfx.drawCenteredString(this.font, I18n.get("gui.bmfstown.gattaca.hint",
                I18n.get(sortDir == 0 ? "gui.bmfstown.gattaca.hint_asc" : "gui.bmfstown.gattaca.hint_desc")),
                this.width / 2, y0 + totalH() - 12, 0xFF8A8A8A);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int x0 = (this.width - W) / 2;
            int headerY = this.listY;
            // 表头排序
            if (mouseY >= headerY && mouseY < headerY + HEADER_H) {
                for (int col = 0; col < 2; col++) {
                    int cx = x0 + col * COL_W;
                    if (mouseX >= cx && mouseX < cx + COL_W) {
                        if (sortCol == col) {
                            sortDir ^= 1;
                        } else {
                            sortCol = col;
                            sortDir = 1;
                        }
                        return true;
                    }
                }
            }
            // 行：查看对方面板（回跳回本列表，由 payload mode=2 指定）
            var list = members();
            int max = Math.max(0, list.size() - VISIBLE_ROWS);
            int sc = Mth.clamp(this.scroll, 0, max);
            if (mouseX >= x0 && mouseX < x0 + W && mouseY >= headerY + HEADER_H
                    && mouseY < headerY + HEADER_H + VISIBLE_ROWS * ROW_H) {
                int v = (int) ((mouseY - headerY - HEADER_H) / ROW_H);
                int idx = sc + v;
                if (idx >= 0 && idx < list.size()) {
                    // 记录观察者：对方面板的"伪装"按钮判定和 mimic 指令都靠它
                    ResidentScreen.GATTACA_VIEWER_ID = this.observerId;
                    ModNetworking.sendToServer(new ModNetworking.OpenResidentFromHandbookPayload(
                            list.get(idx).entityId(), 2));
                    onClose();
                    return true;
                }
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
        // ESC / E 关闭（用户定稿退出机制）
        if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_E) {
            onClose();
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

    /** 占位：让编译器保留 EditBox import（列表屏无输入框，实际未用）。 */
    private static final class EditBoxless {
    }
}
