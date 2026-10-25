package com.bmf.bmfstown.client;

import com.bmf.bmfstown.network.ModNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 簿记员"城镇物资"搜索界面（v0.8.1）。
 *
 * <h2>交互（用户定稿：学原版创造模式搜索）</h2>
 * <ul>
 *   <li>顶部文本框：输入后<b>回车</b>过滤一次（物品名子串匹配，忽略大小写）；</li>
 *   <li>下方网格：城镇统计到的所有物品，滚轮滚动；</li>
 *   <li>数量角标按簿记员段位三级精度：生疏/入门 = 只显示物品（有/无）；
 *       熟练/老练 = 数量级（≤10 / &gt;10 / &gt;100 / 1000+）；专家以上 = 精确数字；</li>
 *   <li>点击物品 → 发寻物包 → 关界面 → 簿记员带路（到达后绿粒子 10 秒）。</li>
 * </ul>
 *
 * <p>数据来源：服务端 {@code CensusDataPayload}（打开时整包下发，统计是
 * 低频快照，不做实时推送）。
 */
public class BookkeeperSearchScreen extends Screen {

    /** 网格列数。 */
    private static final int COLS = 9;

    /** 可见行数。 */
    private static final int VISIBLE_ROWS = 5;

    /** 面板尺寸。 */
    private static final int W = COLS * 20 + 12;

    private static final int H = 230;

    /** 一条统计条目。 */
    public record Entry(ItemStack stack, int count) {

        public String itemId() {
            var item = this.stack.getItem();
            return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
        }
    }

    /** 打开入口（网络线程调用）：存数据后切主线程开屏。 */
    public static void openWith(ModNetworking.CensusDataPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            List<Entry> entries = new ArrayList<>();
            for (var e : payload.entries()) {
                var item = com.bmf.bmfstown.entity.profile.WorkshopCrafts.BuiltInItemKey
                        .item(e.item());
                if (!item.getDefaultInstance().isEmpty()) {
                    entries.add(new Entry(new ItemStack(item), e.count()));
                }
            }
            // 按数量降序：常用的排前面
            entries.sort((a, b) -> Integer.compare(b.count(), a.count()));
            mc.setScreen(new BookkeeperSearchScreen(payload.entityId(), payload.townName(),
                    payload.precision(), List.copyOf(entries)));
        });
    }

    private final int entityId;
    private final String townName;
    /** 精度：0 有/无，1 数量级，2 精确。 */
    private final int precision;
    private final List<Entry> all;

    private EditBox searchBox;
    private String filter = "";
    private int scrollRow;
    private double lastMouseX;
    private double lastMouseY;

    private BookkeeperSearchScreen(int entityId, String townName, int precision,
            List<Entry> all) {
        super(Component.translatable("gui.bmfstown.bookkeeper.title", townName));
        this.entityId = entityId;
        this.townName = townName;
        this.precision = precision;
        this.all = all;
    }

    // ==========================================================
    //  生命周期
    // ==========================================================

    @Override
    protected void init() {
        int x0 = (this.width - W) / 2;
        this.searchBox = new EditBox(this.font, x0 + 8, y0() + 22, W - 16, 16,
                Component.translatable("gui.bmfstown.bookkeeper.search"));
        this.searchBox.setMaxLength(60);
        this.searchBox.setHint(Component.translatable("gui.bmfstown.bookkeeper.search_hint"));
        this.searchBox.setFocused(true);
        this.searchBox.setResponder(text -> {
            // 输入变化即时清滚动；过滤本身按回车触发（用户定稿）
            this.scrollRow = 0;
        });
        this.addRenderableWidget(this.searchBox);
    }

    /** 网格区左上角。 */
    private int y0() {
        return (this.height - H) / 2;
    }

    // ==========================================================
    //  过滤与滚动
    // ==========================================================

    /** 回车后按当前文本过滤（物品名子串，忽略大小写）。 */
    private void applyFilter() {
        this.filter = this.searchBox.getValue().trim().toLowerCase(java.util.Locale.ROOT);
        this.scrollRow = 0;
    }

    private boolean matches(Entry e) {
        if (this.filter.isEmpty()) {
            return true;
        }
        String name = e.stack().getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
        if (name.contains(this.filter)) {
            return true;
        }
        // 注册名/英文名也参与匹配（搜"oak"能找到橡木）
        var item = e.stack().getItem();
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(item).getPath();
        return id.contains(this.filter);
    }

    private List<Entry> filtered() {
        if (this.filter.isEmpty()) {
            return this.all;
        }
        List<Entry> out = new ArrayList<>();
        for (Entry e : this.all) {
            if (matches(e)) {
                out.add(e);
            }
        }
        return out;
    }

    // ==========================================================
    //  渲染
    // ==========================================================

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        int x0 = (this.width - W) / 2;
        int y0 = y0();
        // 底板
        gfx.fill(x0, y0, x0 + W, y0 + H, 0xF0202028);
        gfx.fill(x0, y0, x0 + W, y0 + 1, 0xFF8A7A55);
        gfx.drawCenteredString(this.font, this.title, x0 + W / 2, y0 + 8, 0xFFE0C878);

        this.searchBox.render(gfx, mouseX, mouseY, partialTick);

        // 网格
        List<Entry> list = filtered();
        int gridY = y0() + 46;
        int maxFirstRow = Math.max(0, (list.size() + COLS - 1) / COLS - VISIBLE_ROWS);
        this.scrollRow = Math.min(this.scrollRow, maxFirstRow);
        int hoverIdx = -1;
        for (int v = 0; v < VISIBLE_ROWS; v++) {
            for (int c = 0; c < COLS; c++) {
                int idx = (this.scrollRow + v) * COLS + c;
                if (idx >= list.size()) {
                    continue;
                }
                Entry e = list.get(idx);
                int cx = x0 + 6 + c * 20;
                int cy = gridY + v * 20;
                gfx.fill(cx, cy, cx + 18, cy + 18, 0xFF3D3D46);
                gfx.renderItem(e.stack(), cx + 1, cy + 1);
                // 数量角标（按精度）
                String label = fmtCount(e.count());
                if (!label.isEmpty()) {
                    gfx.drawString(this.font, label, cx + 18 - this.font.width(label),
                            cy + 10, 0xFFFFFF, true);
                }
                if (mouseX >= cx && mouseX < cx + 18 && mouseY >= cy && mouseY < cy + 18) {
                    gfx.fill(cx, cy, cx + 18, cy + 1, 0xFFFFFFFF);
                    gfx.fill(cx, cy + 17, cx + 18, cy + 18, 0xFFFFFFFF);
                    gfx.fill(cx, cy, cx + 1, cy + 18, 0xFFFFFFFF);
                    gfx.fill(cx + 17, cy, cx + 18, cy + 18, 0xFFFFFFFF);
                    hoverIdx = idx;
                }
            }
        }

        // 页脚：滚动提示 + 悬停 tooltip
        String footer = I18nFooter(list.size());
        gfx.drawCenteredString(this.font, footer, x0 + W / 2, y0 + H - 12, 0xFF909090);
        if (hoverIdx >= 0 && hoverIdx < list.size()) {
            Entry e = list.get(hoverIdx);
            List<Component> tip = new ArrayList<>();
            tip.add(e.stack().getHoverName());
            String count = fmtCount(e.count());
            if (this.precision == 0) {
                tip.add(Component.translatable("gui.bmfstown.bookkeeper.tip_presence"));
            } else {
                tip.add(Component.translatable("gui.bmfstown.bookkeeper.tip_count", count));
            }
            gfx.renderComponentTooltip(this.font, tip, mouseX, mouseY + 10);
        }
    }

    private String I18nFooter(int size) {
        return Component.translatable("gui.bmfstown.bookkeeper.footer",
                this.filter.isEmpty()
                        ? Component.translatable("gui.bmfstown.bookkeeper.footer_all").getString()
                        : this.filter,
                size).getString();
    }

    /** 数量显示（按精度）。 */
    private String fmtCount(int count) {
        return switch (this.precision) {
            case 0 -> "";
            case 1 -> count <= 10 ? "≤10" : count <= 100 ? ">10"
                    : count <= 1000 ? ">100" : "1000+";
            default -> String.valueOf(count);
        };
    }

    // ==========================================================
    //  输入
    // ==========================================================

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) { // 回车/小键盘回车：应用过滤
            applyFilter();
            return true;
        }
        if (this.searchBox.keyPressed(keyCode, scanCode, modifiers)
                || this.searchBox.canConsumeInput()) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.searchBox.charTyped(codePoint, modifiers)) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double xAmount, double yAmount) {
        List<Entry> list = filtered();
        int maxFirstRow = Math.max(0, (list.size() + COLS - 1) / COLS - VISIBLE_ROWS);
        this.scrollRow = Math.max(0, Math.min(maxFirstRow,
                this.scrollRow - (int) Math.signum(yAmount)));
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            List<Entry> list = filtered();
            int x0 = (this.width - W) / 2;
            int gridY = y0() + 46;
            int c = (int) ((mouseX - (x0 + 6)) / 20);
            int v = (int) ((mouseY - gridY) / 20);
            boolean inGrid = mouseX >= x0 + 6 && mouseX < x0 + 6 + COLS * 20
                    && mouseY >= gridY && mouseY < gridY + VISIBLE_ROWS * 20;
            if (inGrid && c >= 0 && c < COLS && v >= 0 && v < VISIBLE_ROWS) {
                int idx = (this.scrollRow + v) * COLS + c;
                if (idx >= 0 && idx < list.size()) {
                    // 下单寻物：发产物注册名，关界面，簿记员带路
                    ModNetworking.sendToServer(new ModNetworking.BookkeeperFindPayload(
                            this.entityId, list.get(idx).itemId()));
                    this.onClose();
                    return true;
                }
            }
            // 点底板空白不算关（避免误触）；点面板外才关
            boolean insidePanel = mouseX >= x0 && mouseX < x0 + W
                    && mouseY >= y0() && mouseY < y0() + H;
            if (!insidePanel) {
                this.onClose();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
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
