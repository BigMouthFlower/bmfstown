package com.bmf.bmfstown.client;

import com.bmf.bmfstown.network.ModNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 酒吧菜单板 UI（#86 升格，2026-11-21 拍板）。两段弹层（常规商店 UI 共识：
 * 列表页 + "+ 添加"选择页 + 阶梯步进设量）：
 * <ul>
 *   <li><b>段一·总览</b>：菜单逐行（图标+酒名+存量/目标+−5/−1/+1/+5+移除；
 *       闭包豁免项=只读暗行"豁免·按需"标签）；底部 添加酒品/清空(二次确认)/完成；</li>
 *   <li><b>段二·添加</b>：{@code #bmfstown:tavern_drinks} 全成员网格，点选回段一
 *       （默认目标 4，重复添加=改量合并）；</li>
 * </ul>
 * 每次操作立即发包（C2S 编辑→服务端落账→回快照刷新，无"暂存+确认"两段提交）。
 * 只读模式（非城镇成员）：所有编辑钮隐藏。数据源 {@link ClientTavernMenu}。
 */
public class TavernMenuScreen extends Screen {

    private static final int W = 232;
    private static final int ROW_H = 18;
    private static final int VISIBLE_ROWS = 7;
    private static final int CHROME_H = 46; // 标题+底部按钮带
    private static final TagKey<Item> DRINKS_TAG = TagKey.create(BuiltInRegistries.ITEM.key(),
            ResourceLocation.fromNamespaceAndPath("bmfstown", "tavern_drinks"));

    /** 0=总览 1=添加酒品。 */
    private int stage;
    private int scroll;
    private boolean confirmClear;

    public TavernMenuScreen() {
        super(Component.translatable("gui.bmfstown.menu_board.title"));
    }

    @Override
    protected void init() {
        ClientTavernMenu.setRefreshListener(this::refresh);
        this.confirmClear = false;
    }

    /** 编辑回包刷新（清确认态/钳滚动）。 */
    private void refresh() {
        this.confirmClear = false;
        clampScroll();
    }

    private int listH() {
        return VISIBLE_ROWS * ROW_H;
    }

    private int totalH() {
        return CHROME_H + listH();
    }

    private void clampScroll() {
        this.scroll = Mth.clamp(this.scroll, 0, Math.max(0, rows() - VISIBLE_ROWS));
    }

    /** 当前段要渲染的行数（总览=菜单+豁免；添加页=tag 成员）。 */
    private int rows() {
        return this.stage == 0
                ? ClientTavernMenu.entries().size()
                : Math.max(1, drinks().size());
    }

    /** 段二候选：tavern_drinks tag 全成员（BAC 不在=只剩原版三样）。 */
    private static List<Item> drinks() {
        List<Item> out = new ArrayList<>();
        var holders = BuiltInRegistries.ITEM.getTag(DRINKS_TAG);
        holders.ifPresent(named -> {
            for (var h : named) {
                out.add(h.value());
            }
        });
        return out;
    }

    private boolean editable() {
        return ClientTavernMenu.editable();
    }

    private void sendEdit(int op, String itemId, int target) {
        ModNetworking.sendToServer(new ModNetworking.TavernMenuEditPayload(
                op, itemId, target, ClientTavernMenu.boardPos()));
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(gfx, mouseX, mouseY, partialTick);
        int x0 = (this.width - W) / 2;
        int y0 = (this.height - totalH()) / 2;
        gfx.fill(x0, y0, x0 + W, y0 + totalH(), 0xF0202028);
        gfx.fill(x0, y0, x0 + W, y0 + 1, 0xFF8A7A55);
        gfx.drawCenteredString(this.font, this.title, this.width / 2, y0 + 6, 0xFFE0C878);

        if (this.stage == 0) {
            renderMenuList(gfx, x0, y0, mouseX, mouseY);
        } else {
            renderDrinkPicker(gfx, x0, y0, mouseX, mouseY);
        }
        renderBottomBar(gfx, x0, y0, mouseX, mouseY);
    }

    private void renderMenuList(GuiGraphics gfx, int x0, int y0, int mx, int my) {
        var entries = ClientTavernMenu.entries();
        int listY = y0 + 20;
        if (entries.isEmpty()) {
            gfx.drawCenteredString(this.font, I18n.get("gui.bmfstown.menu_board.empty"),
                    this.width / 2, listY + 6, 0xFF9A9A9A);
            return;
        }
        clampScroll();
        for (int v = 0; v < VISIBLE_ROWS; v++) {
            int idx = this.scroll + v;
            if (idx >= entries.size()) {
                break;
            }
            ClientTavernMenu.Entry e = entries.get(idx);
            int ry = listY + v * ROW_H;
            boolean hov = mx >= x0 && mx < x0 + W && my >= ry && my < ry + ROW_H;
            gfx.fill(x0 + 1, ry, x0 + W - 1, ry + ROW_H - 1,
                    hov ? 0x40C0A070 : 0x00000000);
            Item item = BuiltInRegistries.ITEM.get(
                    ResourceLocation.tryParse(e.itemId()));
            gfx.renderItem(new ItemStack(item), x0 + 3, ry + 1);
            String label = new ItemStack(item).getHoverName().getString()
                    + (e.exempt() ? " " + I18n.get("gui.bmfstown.menu_board.exempt") : "");
            gfx.drawString(this.font, this.font.plainSubstrByWidth(label, 84),
                    x0 + 22, ry + 5, e.exempt() ? 0xFF707070 : 0xFFE8E8E8, false);
            String stock = e.stock() + "/" + e.target();
            gfx.drawString(this.font, stock, x0 + 110, ry + 5,
                    e.stock() >= e.target() ? 0xFF70C070 : 0xFFE0C878, false);
            if (editable() && !e.exempt()) {
                button(gfx, x0 + 134, ry + 2, 14, "−5", mx, my);
                button(gfx, x0 + 150, ry + 2, 14, "−1", mx, my);
                button(gfx, x0 + 166, ry + 2, 14, "+1", mx, my);
                button(gfx, x0 + 182, ry + 2, 14, "+5", mx, my);
                button(gfx, x0 + 200, ry + 2, 14, "✕", mx, my);
            }
        }
    }

    private void renderDrinkPicker(GuiGraphics gfx, int x0, int y0, int mx, int my) {
        List<Item> drinks = drinks();
        int listY = y0 + 20;
        if (drinks.isEmpty()) {
            gfx.drawCenteredString(this.font, I18n.get("gui.bmfstown.menu_board.no_drinks"),
                    this.width / 2, listY + 6, 0xFF9A9A9A);
            return;
        }
        clampScroll();
        for (int v = 0; v < VISIBLE_ROWS; v++) {
            int idx = this.scroll + v;
            if (idx >= drinks.size()) {
                break;
            }
            Item item = drinks.get(idx);
            int ry = listY + v * ROW_H;
            boolean hov = mx >= x0 && mx < x0 + W && my >= ry && my < ry + ROW_H;
            gfx.fill(x0 + 1, ry, x0 + W - 1, ry + ROW_H - 1,
                    hov ? 0x40C0A070 : 0x00000000);
            gfx.renderItem(new ItemStack(item), x0 + 3, ry + 1);
            gfx.drawString(this.font,
                    this.font.plainSubstrByWidth(new ItemStack(item).getHoverName().getString(), 190),
                    x0 + 22, ry + 5, 0xFFE8E8E8, false);
        }
    }

    /** 底部按钮带（两段共用坐标，标签随段切换）。 */
    private void renderBottomBar(GuiGraphics gfx, int x0, int y0, int mx, int my) {
        int by = y0 + totalH() - 16;
        if (this.stage == 0) {
            String clearLabel = I18n.get(this.confirmClear
                    ? "gui.bmfstown.menu_board.confirm_clear" : "gui.bmfstown.menu_board.clear");
            int color = this.confirmClear ? 0xFFC05050 : 0xFF403828;
            wideButton(gfx, x0 + 4, by, 64, I18n.get("gui.bmfstown.menu_board.add"), mx, my,
                    0xFF403828, editable());
            wideButton(gfx, x0 + 72, by, 78, clearLabel, mx, my, color, editable());
            wideButton(gfx, x0 + W - 52, by, 48, I18n.get("gui.bmfstown.menu_board.done"),
                    mx, my, 0xFF403828, true);
            if (!editable()) {
                gfx.drawCenteredString(this.font, I18n.get("gui.bmfstown.menu_board.not_editable"),
                        x0 + 110, y0 + totalH() - 26, 0xFFC05050);
            }
        } else {
            wideButton(gfx, x0 + 4, by, 48, I18n.get("gui.bmfstown.menu_board.back"), mx, my,
                    0xFF403828, true);
        }
    }

    private void button(GuiGraphics gfx, int x, int y, int w, String label, int mx, int my) {
        boolean hov = mx >= x && mx < x + w && my >= y && my < y + 14;
        gfx.fill(x, y, x + w, y + 14, hov ? 0xFF6A5A40 : 0xFF403828);
        gfx.drawCenteredString(this.font, label, x + w / 2, y + 3, 0xFFE0C878);
    }

    private void wideButton(GuiGraphics gfx, int x, int y, int w, String label,
            int mx, int my, int color, boolean enabled) {
        boolean hov = enabled && mx >= x && mx < x + w && my >= y && my < y + 14;
        gfx.fill(x, y, x + w, y + 14, hov ? 0xFF6A5A40 : color);
        gfx.drawCenteredString(this.font, this.font.plainSubstrByWidth(label, w - 4),
                x + w / 2, y + 3, enabled ? 0xFFE0C878 : 0xFF707070);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        int x0 = (this.width - W) / 2;
        int y0 = (this.height - totalH()) / 2;
        int listY = y0 + 20;
        int by = y0 + totalH() - 16;
        int mxi = (int) mouseX;
        int myi = (int) mouseY;

        // 底部按钮带
        if (myi >= by && myi < by + 14) {
            if (this.stage == 0) {
                if (mxi >= x0 + 4 && mxi < x0 + 68) {
                    if (editable()) {
                        this.stage = 1;
                        this.scroll = 0;
                    }
                    return true;
                }
                if (mxi >= x0 + 72 && mxi < x0 + 150 && editable()) {
                    if (this.confirmClear) {
                        sendEdit(2, "", 0);
                        this.confirmClear = false;
                    } else {
                        this.confirmClear = true; // 二次点击确认（设计稿 §3）
                    }
                    return true;
                }
                if (mxi >= x0 + W - 52 && mxi < x0 + W - 4) {
                    onClose();
                    return true;
                }
            } else {
                if (mxi >= x0 + 4 && mxi < x0 + 52) {
                    this.stage = 0;
                    this.scroll = 0;
                    return true;
                }
            }
        }

        // 列表行
        if (myi >= listY && myi < listY + listH()) {
            int v = (int) ((myi - listY) / ROW_H);
            if (this.stage == 0) {
                var entries = ClientTavernMenu.entries();
                int idx = this.scroll + v;
                if (idx < entries.size() && editable() && !entries.get(idx).exempt()) {
                    ClientTavernMenu.Entry e = entries.get(idx);
                    int step = hasShiftDown() ? 5 : 1;
                    if (mxi >= x0 + 134 && mxi < x0 + 148) {
                        sendEdit(0, e.itemId(), Math.max(1, e.target() - step));
                        return true;
                    }
                    if (mxi >= x0 + 150 && mxi < x0 + 164) {
                        sendEdit(0, e.itemId(), Math.max(1, e.target() - 1));
                        return true;
                    }
                    if (mxi >= x0 + 166 && mxi < x0 + 180) {
                        sendEdit(0, e.itemId(), Math.min(32, e.target() + 1));
                        return true;
                    }
                    if (mxi >= x0 + 182 && mxi < x0 + 196) {
                        sendEdit(0, e.itemId(), Math.min(32, e.target() + step));
                        return true;
                    }
                    if (mxi >= x0 + 200 && mxi < x0 + 214) {
                        sendEdit(1, e.itemId(), 0);
                        return true;
                    }
                }
            } else {
                List<Item> drinks = drinks();
                int idx = this.scroll + v;
                if (idx < drinks.size() && editable()) {
                    // 常规设计：重复添加=改量合并；默认目标 4（≈一周的量）
                    sendEdit(0, BuiltInRegistries.ITEM.getKey(drinks.get(idx)).toString(), 4);
                    this.stage = 0;
                    this.scroll = 0;
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        this.scroll -= (int) Math.signum(dy);
        clampScroll();
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_E) {
            if (this.stage == 1) {
                this.stage = 0;
                return true;
            }
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        ClientTavernMenu.setRefreshListener(null);
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
