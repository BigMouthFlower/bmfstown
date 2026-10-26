package com.bmf.bmfstown.client;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.network.ModNetworking;
import com.bmf.bmfstown.town.TownWandMode;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.util.Mth;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 城镇手册屏幕——宽开大账本，一页一镇，可翻页。
 *
 * <h2>页面模型</h2>
 * 一页 = 一个城镇：左页第一行就是城镇本身（可选中改名/删除），
 * 下面是它的子区域；右页显示详情——不选中任何记录时默认显示
 * 当前城镇的信息（翻到这页就能看到，不用点）。
 *
 * <h2>交互</h2>
 * <ul>
 *   <li>右上角墨字 ✕ 关闭（无框，就是纸面上一枚手写的叉）</li>
 *   <li>重命名/删除对称放在两页下缘中央</li>
 *   <li>左下/右下书页卷角：有上一镇才画左卷角、有下一镇才画右卷角，
 *       弧形弯角贴着纸页角，点击翻页（悬停提亮）</li>
 * </ul>
 */
public class TownHandbookScreen extends Screen {

    private static final ResourceLocation BOOK_BG =
            ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "textures/gui/town_ledger.png");
    private static final int BOOK_W = 256;
    private static final int BOOK_H = 180;

    private static final int TEXT_W = 92;
    private static final int ROW_H = 14;
    private static final int BTN_W = 46;
    private static final int BTN_H = 14;
    /** 卷角大小（弧形弯角的半径）。 */
    private static final int CURL = 14;

    private static final int INK = 0x2B2B2B;
    private static final int INK_FADED = 0x6E5B40;
    private static final int INK_HEADER = 0x8A5A2B;
    private static final int BTN_FILL = 0x20805030;
    private static final int BTN_FILL_HOVER = 0x45805030;
    private static final int BTN_EDGE = 0xFF8A5A2B;
    /** 折起的纸角颜色（比纸面更亮，表现翻折面朝光）。 */
    private static final int CURL_FILL = 0xFFFCF5DF;
    private static final int CURL_FILL_HOVER = 0xFFFFF8E8;
    private static final int CURL_SHADOW = 0xFF9A8050;
    private static final int CURL_PAGE_SHADOW = 0x50604830;

    private record Row(String name, String bounds, String targetId, boolean isTown, int kind) {
    }

    private record InkButton(int x, int y, String label, Runnable action,
                             int edge, int text) {
        /** 普通墨色按钮。 */
        InkButton(int x, int y, String label, Runnable action) {
            this(x, y, label, action, BTN_EDGE, INK);
        }

        /** 危险操作按钮（红字红框，比如"永久删除"）。 */
        static InkButton danger(int x, int y, String label, Runnable action) {
            return new InkButton(x, y, label, action, 0xFFC03020, 0xFFD04030);
        }
    }

    private List<Row> rows = List.of();
    private final List<InkButton> buttons = new ArrayList<>();

    // ---- 居民列表（v0.8.1：城镇页左页，用户定稿）----
    /** 排序列：0=名字 1=职业（跨打开保留，回跳时也是这个状态）。 */
    private static int memberSortCol = 0;
    /** 排序方向：0=升序 1=降序。 */
    private static int memberSortDir = 0;
    private static int memberScroll;
    private static final int MEMBER_HEADER_H = 10;
    private static final int MEMBER_ROW_H = 12;
    private static final int MEMBER_VISIBLE = 6;
    private static final int MEMBER_COL_W = 46;
    /** 列表首行 y（渲染时更新，点击判定用）。 */
    private int memberListY;
    /** 当前显示第几个城镇。 */
    private int pageIndex = 0;

    /** 回跳恢复：镇民面板关闭回跳手册时，恢复到离开前的城镇页（-1 = 不恢复）。 */
    public static int restorePageIndex = -1;
    private int selected = -1;
    private int scroll = 0;

    /**
     * 区域管理子页（v0.6）。
     *
     * <p>-1 = 城镇页；≥0 = 正在管理某类区域（值为 {@link TownWandMode} 序号，
     * 只会是 DINING/HOUSING/WORK）。视觉上还是这本书，逻辑上是压在
     * 城镇页上面的一个子页——左页列该城镇该类型的所有区域，
     * 右页显示选中区域的详情。数据来源始终是城镇的三张区域表，
     * 子页只是"换个视图看它们"。
     */
    private int zonePage = -1;
    /** 城镇页右下"区域管理"入口列表的顶行 y（GUI 绝对坐标）。 */
    private int manageTop;

    private boolean renameMode = false;
    private EditBox nameBox;
    /** 删除确认状态：第一次点"删除"只是警告，第二次才真删。 */
    private boolean confirmDelete = false;

    /** 右上角 ✕ 的点击热区（无框，只画字符）。 */
    private int closeX, closeY;

    private int bookX, bookY, lx, rx, listTop;

    public TownHandbookScreen() {
        super(Component.translatable("gui.bmfstown.handbook.title"));
    }

    // ==========================================================
    //  生命周期
    // ==========================================================

    @Override
    protected void init() {
        TownHandbookClient.screenOpened(this);
        if (restorePageIndex >= 0) {
            this.pageIndex = Mth.clamp(restorePageIndex, 0,
                    Math.max(0, TownHandbookClient.towns().size() - 1));
            restorePageIndex = -1; // 用后即清
        }
        bookX = (this.width - BOOK_W) / 2;
        bookY = Math.max(2, (this.height - BOOK_H) / 2);
        lx = bookX + 24;
        rx = bookX + 140;
        listTop = bookY + 84;
        // ✕ 贴着右上纸角内侧
        closeX = bookX + BOOK_W - 24;
        closeY = bookY + 16;

        buttons.clear();
        if (zonePage >= 0) {
            // ---- 区域管理子页：左上角返回按钮（压在城镇页上的"后退"） ----
            buttons.add(new InkButton(lx, bookY + 14,
                    I18n.get("gui.bmfstown.handbook.back"), this::closeZonePage));
            if (renameMode) {
                nameBox = new EditBox(this.font, rx, bookY + 100, TEXT_W, 14,
                        Component.translatable("gui.bmfstown.handbook.name_label"));
                String current = selectedTargetName();
                nameBox.setValue(current == null ? "" : current);
                nameBox.setMaxLength(24);
                nameBox.setHint(Component.translatable("gui.bmfstown.handbook.name_hint"));
                addRenderableWidget(nameBox);
                buttons.add(new InkButton(rx, bookY + 124, I18n.get("gui.bmfstown.handbook.ok"), this::confirmRename));
                buttons.add(new InkButton(rx + 52, bookY + 124, I18n.get("gui.bmfstown.handbook.cancel"), this::endRename));
            } else if (confirmDelete) {
                buttons.add(InkButton.danger(bookX + 58, bookY + 148, I18n.get("gui.bmfstown.handbook.delete_forever"), this::deleteConfirmed));
                buttons.add(new InkButton(bookX + 152, bookY + 148, I18n.get("gui.bmfstown.handbook.delete_cancel"), this::cancelDelete));
            } else if (selected >= 0 && selected < rows.size()) {
                // 子页里没选中区域时不给改名/删除——currentTarget 会退回城镇本身，
                // 那样"在区域页里改了城镇名"太容易误操作
                buttons.add(new InkButton(bookX + 58, bookY + 148, I18n.get("gui.bmfstown.handbook.rename"), this::beginRename));
                buttons.add(new InkButton(bookX + 152, bookY + 148, I18n.get("gui.bmfstown.handbook.delete"), this::beginDelete));
            }
            rebuildRows();
            return;
        }
        if (renameMode) {
            // 右页上方是预览图，改名输入区放到预览图下面
            nameBox = new EditBox(this.font, rx, bookY + 100, TEXT_W, 14,
                    Component.translatable("gui.bmfstown.handbook.name_label"));
            String current = selectedTargetName();
            nameBox.setValue(current == null ? "" : current);
            nameBox.setMaxLength(24);
            nameBox.setHint(Component.translatable("gui.bmfstown.handbook.name_hint"));
            addRenderableWidget(nameBox);
            buttons.add(new InkButton(rx, bookY + 124, I18n.get("gui.bmfstown.handbook.ok"), this::confirmRename));
            buttons.add(new InkButton(rx + 52, bookY + 124, I18n.get("gui.bmfstown.handbook.cancel"), this::endRename));
        } else if (confirmDelete) {
            // 删除确认：真删的按钮红色醒目，旁边给个后悔药
            buttons.add(InkButton.danger(bookX + 58, bookY + 148, "永久删除", this::deleteConfirmed));
            buttons.add(new InkButton(bookX + 152, bookY + 148, I18n.get("gui.bmfstown.handbook.delete_cancel"), this::cancelDelete));
        } else {
            buttons.add(new InkButton(bookX + 58, bookY + 148, I18n.get("gui.bmfstown.handbook.rename"), this::beginRename));
            buttons.add(new InkButton(bookX + 152, bookY + 148, I18n.get("gui.bmfstown.handbook.delete"), this::beginDelete));
        }
        rebuildRows();
    }

    @Override
    public void onClose() {
        TownHandbookClient.screenClosed();
        super.onClose();
    }

    void refresh() {
        if (pageIndex >= TownHandbookClient.towns().size()) {
            pageIndex = Math.max(0, TownHandbookClient.towns().size() - 1);
        }
        // 服务端推来新数据 = 一次删除/改名已落地，确认状态必须复位，
        // 否则按钮停在"永久删除"，容易连删下一个领地。
        // 注意：按钮是在 init() 里生成的，光改状态不够，必须重建控件。
        confirmDelete = false;
        renameMode = false;
        nameBox = null;
        this.clearWidgets();
        this.init();
    }

    // ==========================================================
    //  行数据（一页 = 一个城镇；第一行 = 城镇本身）
    // ==========================================================

    private TownHandbookClient.TownView currentTown() {
        List<TownHandbookClient.TownView> towns = TownHandbookClient.towns();
        if (pageIndex < 0 || pageIndex >= towns.size()) {
            return null;
        }
        return towns.get(pageIndex);
    }

    private void rebuildRows() {
        List<Row> list = new ArrayList<>();
        TownHandbookClient.TownView town = currentTown();
        if (town != null && zonePage >= 0) {
            // 子页：只列当前城镇、当前类型的区域，名字不带类型前缀。
            // 城镇页（zonePage < 0）不再铺区域列表——子区域的浏览/编辑
            // 统一走右侧"区域管理"入口，避免两页混在一起。
            for (TownHandbookClient.ZoneView zone : town.zones()) {
                if (zone.kind() != zonePage) {
                    continue;
                }
                list.add(new Row(zone.name(),
                        fmt(zone.min()) + "~" + fmt(zone.max()),
                        "Z:" + zone.id(), false, zone.kind()));
            }
        }
        this.rows = list;
        if (selected >= rows.size()) {
            selected = rows.isEmpty() ? -1 : rows.size() - 1;
        }
        clampScroll();
    }

    /** 当前操作目标：选中了子区域就是它；没选中就是城镇本身。 */
    private Row currentTarget() {
        TownHandbookClient.TownView town = currentTown();
        if (town == null) {
            return null;
        }
        if (selected >= 0 && selected < rows.size()) {
            return rows.get(selected);
        }
        return new Row(town.name(), fmt(town.min()) + "~" + fmt(town.max()),
                "T:" + town.id(), true, 0);
    }

    private static String fmt(net.minecraft.core.BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    private String fit(String text) {
        return this.font.plainSubstrByWidth(text, TEXT_W);
    }

    private String selectedTargetId() {
        Row row = currentTarget();
        return row == null ? null : row.targetId();
    }

    private String selectedTargetName() {
        Row row = currentTarget();
        return row == null ? null : row.name();
    }

    private int visibleRows() {
        return Math.max(1, (bookY + 140 - listTop) / ROW_H);
    }

    private void clampScroll() {
        int maxScroll = Math.max(0, rows.size() - visibleRows());
        if (scroll > maxScroll) {
            scroll = maxScroll;
        }
        if (scroll < 0) {
            scroll = 0;
        }
        if (selected >= 0) {
            if (selected < scroll) {
                scroll = selected;
            } else if (selected >= scroll + visibleRows()) {
                scroll = selected - visibleRows() + 1;
            }
        }
    }

    // ==========================================================
    //  翻页
    // ==========================================================

    private boolean hasPrev() {
        return pageIndex > 0;
    }

    private boolean hasNext() {
        return pageIndex < TownHandbookClient.towns().size() - 1;
    }

    private void flip(int delta) {
        pageIndex = Math.max(0, Math.min(TownHandbookClient.towns().size() - 1,
                pageIndex + delta));
        zonePage = -1; // 换城镇 = 整页翻过去，子页跟着合上
        selected = -1;
        scroll = 0;
        renameMode = false;
        confirmDelete = false;
        nameBox = null;
        this.clearWidgets();
        this.init();
    }

    // ==========================================================
    //  区域管理子页的开合
    // ==========================================================

    /** 从城镇页右下列表点进来：打开某类区域的管理子页。 */
    private void openZonePage(int kind) {
        this.zonePage = kind;
        this.selected = -1;
        this.scroll = 0;
        this.confirmDelete = false;
        this.renameMode = false;
        this.clearWidgets();
        this.init();
    }

    /** 返回城镇页。选中态清掉——回去看的是城镇信息，不是刚才那条区域。 */
    private void closeZonePage() {
        this.zonePage = -1;
        this.selected = -1;
        this.scroll = 0;
        this.confirmDelete = false;
        this.renameMode = false;
        this.clearWidgets();
        this.init();
    }

    // ==========================================================
    //  操作
    // ==========================================================

    private void beginRename() {
        if (selectedTargetId() == null) {
            return;
        }
        renameMode = true;
        confirmDelete = false;
        this.clearWidgets();
        this.init();
    }

    private void endRename() {
        renameMode = false;
        nameBox = null;
        this.clearWidgets();
        this.init();
    }

    private void confirmRename() {
        String id = selectedTargetId();
        String name = nameBox == null ? "" : nameBox.getValue().trim();
        if (id != null && !name.isEmpty()) {
            ModNetworking.sendToServer(new ModNetworking.HandbookRenamePayload(id, name));
        }
        endRename();
    }

    private void deleteSelected() {
        // 已废弃直接删除：改为两段确认（beginDelete / deleteConfirmed）
    }

    /** 第一次点"删除"：只进确认模式，不真删。 */
    private void beginDelete() {
        if (selectedTargetId() == null) {
            return;
        }
        confirmDelete = true;
        this.clearWidgets();
        this.init();
    }

    /** 后悔了。 */
    private void cancelDelete() {
        confirmDelete = false;
        this.clearWidgets();
        this.init();
    }

    /** 第二次确认：真的删了。 */
    private void deleteConfirmed() {
        String id = selectedTargetId();
        confirmDelete = false;
        if (id != null) {
            ModNetworking.sendToServer(new ModNetworking.HandbookDeletePayload(id));
            selected = -1;
        }
    }

    // ==========================================================
    //  渲染
    // ==========================================================

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, this.width, this.height, 0x90101418, 0x90101418);

        graphics.blit(BOOK_BG, bookX, bookY, 0, 0, BOOK_W, BOOK_H, BOOK_W, BOOK_H);

        TownHandbookClient.TownView town = currentTown();
        if (town != null) {
            if (zonePage >= 0) {
                renderZonePage(graphics, town, mouseX, mouseY);
            } else {
                renderTownPage(graphics, town, mouseX, mouseY);
            }
        } else {
            graphics.drawWordWrap(this.font, Component.translatable(
                    "gui.bmfstown.handbook.empty"),
                    lx, bookY + 26, TEXT_W, INK_FADED);
        }

        for (InkButton btn : buttons) {
            drawInkButton(graphics, btn.x(), btn.y(), btn.label(), mouseX, mouseY,
                    btn.edge(), btn.text());
        }

        // 右上角 ✕：只有字符本身，无框；悬停变红提示
        boolean closeHover = isOverClose(mouseX, mouseY);
        graphics.drawString(this.font, "✕", closeX, closeY,
                closeHover ? 0xA03030 : INK_FADED, false);

        // 书页卷角（贴纸页角；有上/下页才画对应侧）
        // 纸页范围 y: bookY+14 ~ bookY+164；卷角底行必须与纸底边重合
        if (hasPrev()) {
            drawPageCurl(graphics, bookX + 14, bookY + BOOK_H - 29, true, mouseX, mouseY);
        }
        if (hasNext()) {
            drawPageCurl(graphics, bookX + BOOK_W - 15, bookY + BOOK_H - 29, false, mouseX, mouseY);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * 一页 = 一个城镇。左页：城镇信息 + 子区域目录；
     * 右页：留白（选中子区域时才显示它的详情），为后续功能预留。
     */
    private void renderTownPage(GuiGraphics graphics, TownHandbookClient.TownView town,
                                int mouseX, int mouseY) {
        // ---- 左页：城镇信息（常驻）----
        graphics.drawString(this.font, fit("■ " + town.name()), lx, bookY + 16, INK, false);
        graphics.drawString(this.font, I18n.get("gui.bmfstown.handbook.owner", town.owner()), lx, bookY + 30, INK_FADED, false);
        graphics.drawString(this.font, I18n.get("gui.bmfstown.handbook.dimension", shortDim(town.dimension())),
                lx, bookY + 40, INK_FADED, false);
        graphics.drawWordWrap(this.font,
                Component.literal(I18n.get("gui.bmfstown.handbook.range",
                        fmt(town.min()), fmt(town.max()))),
                lx, bookY + 50, TEXT_W, INK_FADED);

        // ---- 左页居民列表（红框区域，用户定稿）----
        if (!confirmDelete && !renameMode) {
            drawMemberList(graphics, town, mouseX, mouseY);
        }

        // 页码
        graphics.drawCenteredString(this.font, (pageIndex + 1) + " / "
                + TownHandbookClient.towns().size(), bookX + BOOK_W / 2, bookY + 150, INK_FADED);

        // ---- 右页：领地预览图（上）+ 改名模式 / 区域详情（下）----
        TownPreviewStore.Preview preview = TownPreviewStore.preview(town.id());
        if (preview != null) {
            // 等比例绘制：宽固定，高按图的比例
            int w = TownPreviewStore.DRAW_WIDTH;
            int h = Math.max(1, (int) Math.round((double) w * preview.height() / preview.width()));
            graphics.blit(preview.texture(), rx, bookY + 16, w, h,
                    0.0F, 0.0F, preview.width(), preview.height(),
                    preview.width(), preview.height());
        } else {
            graphics.drawString(this.font, I18n.get("gui.bmfstown.handbook.no_preview"), rx, bookY + 20, INK_FADED, false);
        }

        // ---- 右页：区域管理入口（三类区域的跳转列表） ----
        // 风格学镇民面板的归属列表：整块深色底，悬停提亮。
        // 每行显示类型名 + 数量，点击开对应的管理子页。
        // 注意：删除确认/改名模式下不画（右页被警告/输入框占用）。
        if (!confirmDelete && !renameMode) {
            drawManageList(graphics, mouseX, mouseY);
        }

        // ---- 右页：删除确认警告（红色，仿原版删存档的口气）----
        if (confirmDelete) {
            String target = selectedTargetName();
            graphics.drawWordWrap(this.font, Component.literal(
                            I18n.get("gui.bmfstown.handbook.delete_warn1",
                                    target == null ? "?" : target)),
                    rx, bookY + 88, TEXT_W, 0xFFC03020);
            graphics.drawWordWrap(this.font, Component.literal(
                            I18n.get("gui.bmfstown.handbook.delete_warn2")),
                    rx, bookY + 106, TEXT_W, 0xFFC03020);
            graphics.drawWordWrap(this.font, Component.literal(
                            I18n.get("gui.bmfstown.handbook.delete_warn3")),
                    rx, bookY + 124, TEXT_W, INK_FADED);
            return;
        }

        if (renameMode) {
            graphics.drawString(this.font, I18n.get("gui.bmfstown.handbook.rename_header"), rx, bookY + 84, INK_HEADER, false);
            if (currentTarget() != null) {
                graphics.drawString(this.font, fit(I18n.get("gui.bmfstown.handbook.rename_original", selectedTargetName())),
                        rx, bookY + 96, INK_FADED, false);
            }
            return; // EditBox 由 super.render() 画
        }
        // 城镇页右页：预览图 + 区域管理入口，不再显示"选中子区域"详情——
        // 子区域的选择和详情都在对应的管理子页里进行。
    }

    /** 按当前表头排序状态返回排序后的居民副本。 */
    private java.util.List<TownHandbookClient.MemberView> sortedMembers(
            TownHandbookClient.TownView town) {
        java.util.List<TownHandbookClient.MemberView> list =
                new java.util.ArrayList<>(town.members());
        list.sort((a, b) -> {
            int cmp;
            if (memberSortCol == 0) {
                cmp = a.name().compareToIgnoreCase(b.name());
            } else {
                cmp = I18n.get("profession.bmfstown." + a.professionId())
                        .compareToIgnoreCase(I18n.get("profession.bmfstown." + b.professionId()));
            }
            return memberSortDir == 0 ? cmp : -cmp;
        });
        return list;
    }

    /** 居民列表：表头（名字/职业，点击排序带箭头）+ 滚动行（点击开面板）。 */
    private void drawMemberList(GuiGraphics graphics, TownHandbookClient.TownView town,
                                int mouseX, int mouseY) {
        var list = sortedMembers(town);
        int headerY = bookY + 64;
        this.memberListY = headerY + MEMBER_HEADER_H;

        for (int col = 0; col < 2; col++) {
            int cx = lx + col * MEMBER_COL_W;
            boolean hov = mouseX >= cx && mouseX < cx + MEMBER_COL_W - 2
                    && mouseY >= headerY && mouseY < headerY + MEMBER_HEADER_H;
            graphics.fill(cx, headerY, cx + MEMBER_COL_W - 2, headerY + MEMBER_HEADER_H,
                    hov ? 0xFFDCCEA6 : 0xFFEDE2C2);
            String label = I18n.get(col == 0
                    ? "gui.bmfstown.handbook.member_name"
                    : "gui.bmfstown.handbook.member_prof");
            if (memberSortCol == col) {
                label += memberSortDir == 0 ? " ▲" : " ▼";
            }
            graphics.drawString(this.font, label, cx + 2, headerY + 2, INK_HEADER, false);
        }

        int max = Math.max(0, list.size() - MEMBER_VISIBLE);
        this.memberScroll = Mth.clamp(this.memberScroll, 0, max);
        for (int v = 0; v < MEMBER_VISIBLE; v++) {
            int idx = this.memberScroll + v;
            if (idx >= list.size()) {
                break;
            }
            var m = list.get(idx);
            int ry = this.memberListY + v * MEMBER_ROW_H;
            if (mouseX >= lx && mouseX < lx + TEXT_W && mouseY >= ry && mouseY < ry + MEMBER_ROW_H) {
                graphics.fill(lx, ry, lx + TEXT_W, ry + MEMBER_ROW_H, 0x30A08050);
            }
            graphics.drawString(this.font,
                    this.font.plainSubstrByWidth(m.name(), MEMBER_COL_W - 5),
                    lx + 1, ry + 2, INK, false);
            graphics.drawString(this.font,
                    this.font.plainSubstrByWidth(I18n.get("profession.bmfstown." + m.professionId()),
                            MEMBER_COL_W - 5),
                    lx + MEMBER_COL_W + 1, ry + 2, INK_FADED, false);
        }
    }

    /**
     * 管理入口列表涵盖的区域类型：枚举里除 TOWN（城镇本体）之外的全部。
     *
     * <p>用方法而非静态字段：避免"本类静态初始化 → 触发枚举静态初始化"
     * 这种跨类初始化顺序的坑，也方便将来动态扩展。
     *
     * <p>按枚举动态取，将来新增区域类型（如"农田区"）只加枚举值即可，
     * 手册的入口列表、数量统计、点击判定自动跟上，不会漏改。
     */
    private static java.util.List<TownWandMode> manageKinds() {
        return java.util.Arrays.stream(TownWandMode.values())
                .filter(m -> m != TownWandMode.TOWN)
                .toList();
    }

    /**
     * 城镇页右页的"区域管理"入口列表。
     *
     * <p>深灰底块 + 悬停提亮（借鉴镇民面板归属列表的层级语言，
     * 但配色压暗以融进书页）。行 = 区域类型 + 该城镇内数量。
     */
    private void drawManageList(GuiGraphics graphics, int mouseX, int mouseY) {
        TownHandbookClient.TownView town = currentTown();
        if (town == null) {
            return;
        }
        graphics.drawString(this.font, I18n.get("gui.bmfstown.handbook.manage_header"),
                rx, bookY + 88, INK_HEADER, false);

        java.util.List<TownWandMode> kinds = manageKinds();
        manageTop = bookY + 100;
        // 深色底块：包住所有行，和周围纸面区分开
        graphics.fill(rx - 4, manageTop - 2, rx + TEXT_W + 2,
                manageTop + kinds.size() * ROW_H + 2, 0x28201508);
        for (int i = 0; i < kinds.size(); i++) {
            TownWandMode mode = kinds.get(i);
            int kind = mode.ordinal();
            int count = 0;
            for (TownHandbookClient.ZoneView zone : town.zones()) {
                if (zone.kind() == kind) {
                    count++;
                }
            }
            int y = manageTop + i * ROW_H;
            boolean hover = mouseX >= rx - 4 && mouseX < rx + TEXT_W + 2
                    && mouseY >= y && mouseY < y + ROW_H;
            if (hover) {
                graphics.fill(rx - 4, y - 1, rx + TEXT_W + 2, y + ROW_H - 2, 0x40201508);
            }
            String label = fit(I18n.get(mode.displayName()) + " ×" + count);
            graphics.drawString(this.font, label, rx, y,
                    hover ? INK : INK_FADED, false);
        }
    }

    /** 入口列表的命中判定（城镇页鼠标点击用）。返回点中的 kind，没点中返回 -1。 */
    private int manageListClick(double mouseX, double mouseY) {
        if (zonePage >= 0 || manageTop <= 0) {
            return -1;
        }
        if (mouseX < rx - 4 || mouseX > rx + TEXT_W + 2) {
            return -1;
        }
        java.util.List<TownWandMode> kinds = manageKinds();
        int row = (int) ((mouseY - manageTop) / ROW_H);
        if (row < 0 || row >= kinds.size() || mouseY < manageTop) {
            return -1;
        }
        return kinds.get(row).ordinal();
    }

    /**
     * 区域管理子页：视觉上还是这本书，逻辑上压在城镇页上面。
     *
     * <p>左页 = 该城镇该类型区域列表（可选中）；右页 = 选中区域的
     * 详情 + 改名/删除。返回按钮由 init() 装配（左上角墨字按钮）。
     */
    private void renderZonePage(GuiGraphics graphics, TownHandbookClient.TownView town,
                                int mouseX, int mouseY) {
        TownWandMode mode = TownWandMode.byIndex(zonePage);
        int count = rows.size();

        // ---- 左页：类型标题 + 数量 + 区域列表 ----
        graphics.drawString(this.font, fit(I18n.get(mode.displayName())),
                lx, bookY + 34, INK_HEADER, false);
        graphics.drawString(this.font, I18n.get("gui.bmfstown.handbook.zone_count", count),
                lx, bookY + 46, INK_FADED, false);

        if (rows.isEmpty()) {
            graphics.drawString(this.font, I18n.get("gui.bmfstown.handbook.zones_empty"),
                    lx, listTop, INK_FADED, false);
        } else {
            // 与城镇页"区域管理"同款风格：整块深色底 + 悬停提亮 + 选中加深
            int shown = Math.min(rows.size() - scroll, visibleRows());
            graphics.fill(lx - 4, listTop - 2, lx + TEXT_W + 2,
                    listTop + shown * ROW_H + 2, 0x28201508);
        }
        for (int i = 0; i < visibleRows(); i++) {
            int index = scroll + i;
            if (index >= rows.size()) {
                break;
            }
            Row row = rows.get(index);
            int y = listTop + i * ROW_H;
            boolean hover = mouseX >= lx - 4 && mouseX < lx + TEXT_W + 2
                    && mouseY >= y && mouseY < y + ROW_H;
            if (index == selected) {
                graphics.fill(lx - 4, y - 2, lx + TEXT_W + 2, y + ROW_H - 3, 0x40000000);
            } else if (hover) {
                graphics.fill(lx - 4, y - 1, lx + TEXT_W + 2, y + ROW_H - 2, 0x40201508);
            }
            graphics.drawString(this.font, fit("· " + row.name()), lx, y,
                    hover || index == selected ? INK : INK_FADED, false);
        }

        // ---- 右页 ----
        if (confirmDelete) {
            String target = selectedTargetName();
            graphics.drawWordWrap(this.font, Component.literal(
                            I18n.get("gui.bmfstown.handbook.delete_warn1",
                                    target == null ? "?" : target)),
                    rx, bookY + 88, TEXT_W, 0xFFC03020);
            graphics.drawWordWrap(this.font, Component.literal(
                            I18n.get("gui.bmfstown.handbook.delete_warn2")),
                    rx, bookY + 106, TEXT_W, 0xFFC03020);
            graphics.drawWordWrap(this.font, Component.literal(
                            I18n.get("gui.bmfstown.handbook.delete_warn3")),
                    rx, bookY + 124, TEXT_W, INK_FADED);
            return;
        }
        if (renameMode) {
            graphics.drawString(this.font, I18n.get("gui.bmfstown.handbook.rename_header"),
                    rx, bookY + 84, INK_HEADER, false);
            if (currentTarget() != null) {
                graphics.drawString(this.font, fit(I18n.get("gui.bmfstown.handbook.rename_original",
                                selectedTargetName())),
                        rx, bookY + 96, INK_FADED, false);
            }
            return; // EditBox 由 super.render() 画
        }
        if (selected >= 0 && selected < rows.size()) {
            Row sel = rows.get(selected);
            graphics.drawString(this.font, fit(sel.name()), rx, bookY + 84, INK, false);
            graphics.drawString(this.font, I18n.get("gui.bmfstown.handbook.type",
                            I18n.get(mode.displayName())),
                    rx, bookY + 96, INK_FADED, false);
            graphics.drawWordWrap(this.font, Component.literal(
                            I18n.get("gui.bmfstown.handbook.bounds", sel.bounds())),
                    rx, bookY + 108, TEXT_W, INK_FADED);
        } else {
            graphics.drawWordWrap(this.font, Component.translatable(
                            "gui.bmfstown.handbook.zone_select_hint"),
                    rx, bookY + 88, TEXT_W, INK_FADED);
        }
    }

    private String shortDim(String dimension) {
        int idx = dimension.indexOf(':');
        return idx >= 0 ? dimension.substring(idx + 1) : dimension;
    }

    private void drawInkButton(GuiGraphics graphics, int x, int y, String label,
                               int mouseX, int mouseY, int edgeColor, int textColor) {
        boolean hover = mouseX >= x && mouseX < x + BTN_W
                && mouseY >= y && mouseY < y + BTN_H;
        graphics.fill(x, y, x + BTN_W, y + BTN_H, hover ? BTN_FILL_HOVER : BTN_FILL);
        graphics.renderOutline(x, y, BTN_W, BTN_H, edgeColor);
        graphics.drawCenteredString(this.font, label,
                x + BTN_W / 2, y + (BTN_H - 8) / 2, textColor);
    }

    /**
     * 书页卷角：仿原版书的折角效果。
     *
     * <p>形状是<b>凹弧折角</b>：折起的纸角很窄（顶端是折痕尖），
     * 向下沿凹弧（w = r − √(r²−dy²)）逐渐展宽贴回纸边——正是原版书
     * 右下角那个"纸角翻起来"的样子。折面用更亮的纸色，凹弧边缘描
     * 深色阴影，页面上再垫一条淡影，出立体感。
     *
     * @param cornerX 纸页角的 x（左角=纸左缘，右角=纸右缘）
     * @param cornerY 弯角顶行（折痕尖）的 y
     * @param left    true = 左下角（翻上一页）
     */
    private void drawPageCurl(GuiGraphics graphics, int cornerX, int cornerY,
                              boolean left, int mouseX, int mouseY) {
        boolean hover = isOverCurl(mouseX, mouseY, cornerX, cornerY);
        int fill = hover ? CURL_FILL_HOVER : CURL_FILL;
        for (int i = 0; i < CURL; i++) {
            // 凹弧：越往下越宽，边缘内凹（区别于外凸的圆弧）
            double dy = i + 1;
            int w = CURL - (int) Math.round(Math.sqrt(
                    (double) CURL * CURL - dy * dy));
            if (w < 1) {
                w = 1; // 折痕尖至少 1px
            }
            int y = cornerY + i;
            int x0 = left ? cornerX : cornerX - w;
            // 折面（亮纸色）
            graphics.fill(x0, y, x0 + w, y + 1, fill);
            // 凹弧边缘的阴影
            int sx = left ? x0 + w - 1 : x0;
            graphics.fill(sx, y, sx + 1, y + 1, CURL_SHADOW);
        }
        // 折角在页面上的落影：贴着折痕左侧/右侧一条淡影
        int shadowX = left ? cornerX + CURL : cornerX - CURL - 1;
        graphics.fill(shadowX, cornerY + CURL / 2, shadowX + 1,
                cornerY + CURL, CURL_PAGE_SHADOW);
    }

    private boolean isOverCurl(double mouseX, double mouseY, int cornerX, int cornerY) {
        return mouseX >= cornerX - CURL && mouseX <= cornerX + CURL
                && mouseY >= cornerY - 2 && mouseY <= cornerY + CURL;
    }

    private boolean isOverClose(double mouseX, double mouseY) {
        return mouseX >= closeX - 3 && mouseX <= closeX + 12
                && mouseY >= closeY - 2 && mouseY <= closeY + 12;
    }

    // ==========================================================
    //  输入
    // ==========================================================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (isOverClose(mouseX, mouseY)) {
                onClose();
                return true;
            }
            if (hasPrev() && isOverCurl(mouseX, mouseY, bookX + 14, bookY + BOOK_H - 29)) {
                flip(-1);
                return true;
            }
            if (hasNext() && isOverCurl(mouseX, mouseY, bookX + BOOK_W - 15, bookY + BOOK_H - 29)) {
                flip(1);
                return true;
            }
            for (InkButton btn : buttons) {
                if (mouseX >= btn.x() && mouseX < btn.x() + BTN_W
                        && mouseY >= btn.y() && mouseY < btn.y() + BTN_H) {
                    btn.action().run();
                    return true;
                }
            }
            // 城镇页：居民列表（表头排序 / 行打开镇民面板，用户定稿）
            if (zonePage < 0 && !renameMode && !confirmDelete) {
                int headerY = bookY + 64;
                if (mouseY >= headerY && mouseY < headerY + MEMBER_HEADER_H) {
                    for (int col = 0; col < 2; col++) {
                        int cx = lx + col * MEMBER_COL_W;
                        if (mouseX >= cx && mouseX < cx + MEMBER_COL_W - 2) {
                            if (memberSortCol == col) {
                                memberSortDir ^= 1; // 再点反转（降↔升循环）
                            } else {
                                memberSortCol = col;
                                memberSortDir = 1;  // 首点该列=降序（用户定稿）
                            }
                            return true;
                        }
                    }
                }
                var town = currentTown();
                if (town != null && mouseY >= this.memberListY
                        && mouseY < this.memberListY + MEMBER_VISIBLE * MEMBER_ROW_H) {
                    var members = sortedMembers(town);
                    int max = Math.max(0, members.size() - MEMBER_VISIBLE);
                    int sc = Mth.clamp(this.memberScroll, 0, max);
                    int v = (int) ((mouseY - this.memberListY) / MEMBER_ROW_H);
                    int idx = sc + v;
                    if (idx >= 0 && idx < members.size()
                            && mouseX >= lx && mouseX < lx + TEXT_W) {
                        ModNetworking.sendToServer(new ModNetworking.OpenResidentFromHandbookPayload(
                                members.get(idx).entityId(), 1));
                        return true;
                    }
                }
            }
            // 城镇页：点"区域管理"入口行 → 开对应子页
            if (zonePage < 0 && !renameMode) {
                int kind = manageListClick(mouseX, mouseY);
                if (kind >= 0) {
                    openZonePage(kind);
                    return true;
                }
            }
            if (!renameMode && mouseX >= lx - 4 && mouseX <= lx + TEXT_W + 2) {
                int i = (int) ((mouseY - listTop) / ROW_H);
                int index = scroll + i;
                if (index >= 0 && index < rows.size() && mouseY >= listTop) {
                    selected = index;
                    clampScroll();
                    // 选中变化 = 重命名/删除按钮的目标变了，必须重建控件
                    //（按钮只在 init() 里生成，不重建就永远不出现）
                    this.clearWidgets();
                    this.init();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        // 居民列表区域内滚轮：滚居民行（不抢城镇翻页/列表滚动）
        if (this.zonePage < 0 && !renameMode && mouseY >= this.memberListY
                && mouseY < this.memberListY + MEMBER_VISIBLE * MEMBER_ROW_H
                && mouseX >= lx && mouseX < lx + TEXT_W) {
            this.memberScroll -= (int) Math.signum(dy);
            return true;
        }
        if (!renameMode) {
            if (this.hasShiftDown()) {
                if (dy > 0 && hasPrev()) {
                    flip(-1);
                } else if (dy < 0 && hasNext()) {
                    flip(1);
                }
            } else {
                scroll -= (int) Math.signum(dy);
                clampScroll();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, dx, dy);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // E 关闭手册（用户定稿；改名模式排除——那时 E 是输入）
        if (!renameMode && keyCode == 69 /* GLFW_KEY_E */) {
            onClose();
            return true;
        }
        if (renameMode && keyCode == 257 /* Enter */ && nameBox != null
                && nameBox.isFocused()) {
            confirmRename();
            return true;
        }
        if (!renameMode && keyCode == 263 /* Left */ && hasPrev()) {
            flip(-1);
            return true;
        }
        if (!renameMode && keyCode == 262 /* Right */ && hasNext()) {
            flip(1);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * 关键：1.21 起 {@code Screen.render()} 开头会自动调一次
     * {@code renderBackground()}——模糊+半透明变暗层会盖在书本和按钮
     * 上面（UI 发虚）。必须重写成空。
     */
    @Override
    public void renderBackground(net.minecraft.client.gui.GuiGraphics graphics,
                                 int mouseX, int mouseY, float partialTick) {
        // 故意留空，见方法注释
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
