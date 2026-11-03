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
 * 跟随见习选人列表（v0.10.3）：复用 GATTACA 模仿选择列表的交互
 * （表头排序 / 行点击打开对方面板 / 对方面板"传送"位置 = 动作按钮），
 * 差异只有两点：
 * <ul>
 *   <li>**按学科过滤**——只列与卡点 perk 书对应职业相同的居民
 *       （用户定稿：不对口的人跟了也白跟）；</li>
 *   <li>对方面板按钮 = "跟随见习"，点击让学者跟随此人实践（不再是模仿）。</li>
 * </ul>
 * 免交互锁、免距离；退出 ESC / E。
 */
public class PracticeSelectScreen extends Screen {

    private static final int W = 110;
    private static final int HEADER_H = 10;
    private static final int ROW_H = 14;
    private static final int VISIBLE_ROWS = 7;
    private static final int COL_W = 52;

    /** 卡点学者（要出门见习的人）实体 ID。 */
    private final int observerId;
    /** 卡点 perk 书对应的职业 id（清单只列这个职业的人）。 */
    private final String requiredProfessionId;
    private static int sortCol = 0;
    private static int sortDir = 0;
    private int scroll;
    private int listY;

    private PracticeSelectScreen(int observerId, String requiredProfessionId) {
        super(Component.translatable("gui.bmfstown.practice.title"));
        this.observerId = observerId;
        this.requiredProfessionId = requiredProfessionId;
    }

    public static PracticeSelectScreen open(int observerId, String requiredProfessionId) {
        return new PracticeSelectScreen(observerId, requiredProfessionId);
    }

    @Override
    protected void init() {
        this.listY = (this.height - totalH()) / 2 + 20;
    }

    private int totalH() {
        return 20 + HEADER_H + VISIBLE_ROWS * ROW_H + 8;
    }

    /** 观察者所在城镇的居民，过滤：对口职业 + 排除学者本人。 */
    private List<TownHandbookClient.MemberView> members() {
        for (TownHandbookClient.TownView town : TownHandbookClient.towns()) {
            boolean containsObserver = false;
            List<TownHandbookClient.MemberView> matched = new ArrayList<>();
            for (TownHandbookClient.MemberView m : town.members()) {
                if (m.entityId() == this.observerId) {
                    containsObserver = true;
                } else if (m.professionId().equals(this.requiredProfessionId)) {
                    matched.add(m);
                }
            }
            if (containsObserver) {
                return sorted(matched);
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
        gfx.fill(x0, y0, x0 + W, y0 + 1, 0xFF557788);
        gfx.drawCenteredString(this.font, this.title, this.width / 2, y0 + 6, 0xFFC8E0F0);

        int headerY = this.listY;
        for (int col = 0; col < 2; col++) {
            int cx = x0 + col * COL_W;
            boolean hov = mouseX >= cx && mouseX < cx + COL_W && mouseY >= headerY
                    && mouseY < headerY + HEADER_H;
            gfx.fill(cx, headerY, cx + COL_W, headerY + HEADER_H, hov ? 0xFF40606A : 0xFF283840);
            String label = I18n.get(col == 0
                    ? "gui.bmfstown.handbook.member_name"
                    : "gui.bmfstown.handbook.member_prof");
            if (sortCol == col) {
                label += sortDir == 0 ? " ▲" : " ▼";
            }
            gfx.drawString(this.font, label, cx + 3, headerY + 1, 0xFFC8E0F0, false);
        }

        var list = members();
        int max = Math.max(0, list.size() - VISIBLE_ROWS);
        this.scroll = Mth.clamp(this.scroll, 0, max);
        if (list.isEmpty()) {
            gfx.drawCenteredString(this.font,
                    I18n.get("gui.bmfstown.practice.empty"),
                    this.width / 2, headerY + HEADER_H + 40, 0xFF8A8A8A);
        }
        for (int v = 0; v < VISIBLE_ROWS; v++) {
            int idx = this.scroll + v;
            if (idx >= list.size()) {
                break;
            }
            var m = list.get(idx);
            int ry = headerY + HEADER_H + v * ROW_H;
            boolean hov = mouseX >= x0 && mouseX < x0 + W && mouseY >= ry && mouseY < ry + ROW_H;
            gfx.fill(x0, ry, x0 + W, ry + ROW_H, hov ? 0x4070A0C0 : 0x00000000);
            gfx.drawString(this.font,
                    this.font.plainSubstrByWidth(m.name(), COL_W - 4), x0 + 3, ry + 3, 0xFFE8E8E8, false);
            gfx.drawString(this.font,
                    this.font.plainSubstrByWidth(I18n.get("profession.bmfstown." + m.professionId()),
                            COL_W - 4), x0 + COL_W + 3, ry + 3, 0xFF9ABAC8, false);
        }

        gfx.drawCenteredString(this.font, I18n.get("gui.bmfstown.practice.hint",
                I18n.get(sortDir == 0 ? "gui.bmfstown.gattaca.hint_asc" : "gui.bmfstown.gattaca.hint_desc")),
                this.width / 2, y0 + totalH() - 12, 0xFF8A8A8A);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int x0 = (this.width - W) / 2;
            int headerY = this.listY;
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
            // 行：打开对方面板（回跳回本列表，payload mode=4 指定）
            var list = members();
            int max = Math.max(0, list.size() - VISIBLE_ROWS);
            int sc = Mth.clamp(this.scroll, 0, max);
            if (mouseX >= x0 && mouseX < x0 + W && mouseY >= headerY + HEADER_H
                    && mouseY < headerY + HEADER_H + VISIBLE_ROWS * ROW_H) {
                int v = (int) ((mouseY - headerY - HEADER_H) / ROW_H);
                int idx = sc + v;
                if (idx >= 0 && idx < list.size()) {
                    // 记录卡点学者：对方面板的"跟随见习"按钮判定和 follow 指令都靠它
                    ResidentScreen.PRACTICE_VIEWER_ID = this.observerId;
                    ModNetworking.sendToServer(new ModNetworking.OpenResidentFromHandbookPayload(
                            list.get(idx).entityId(), 4));
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
}
