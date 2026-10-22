package com.bmf.bmfstown.client;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.entity.profile.ResidentProfile;
import com.bmf.bmfstown.menu.ResidentMenu;
import com.bmf.bmfstown.network.ModNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;

/**
 * 镇民面板的绘制层。
 *
 * <h2>只画，不判断</h2>
 * 这个类<b>不应该</b>包含任何"能不能放这个物品"的逻辑——
 * 那些必须在 {@link ResidentMenu} 里（服务端也要执行一遍）。
 * Screen 只负责好看。
 *
 * <h2>分页（v0.5）</h2>
 * 一屏塞不下越来越长的内容了，拆成两页：
 * <ul>
 *   <li><b>资料页</b>（{@link #PAGE_INFO}）：装备 + 镇民背包 + 状态文字，
 *       就是 v0.4 之前的那一屏</li>
 *   <li><b>归属页</b>（{@link #PAGE_TOWN}）：城镇列表，点击即绑定/解绑。
 *       第一项固定是"不分配"——想解除归属不用找"解绑按钮"，点它就行</li>
 * </ul>
 * 页签是两个普通按钮（右上角），切页纯客户端状态，不走网络。
 *
 * <h2>归属页的列表协议</h2>
 * 列表第 i 项点击后发菜单按钮 {@code BTN_ASSIGN_BASE + (i-1)}，
 * 第 0 项发 {@code BTN_UNASSIGN}。服务端按下标取镇——两边列表
 * 顺序一致（同源排序 + 同维度过滤），见 {@link ResidentMenu#BTN_ASSIGN_BASE}。
 *
 * <h2>版面（资料页）</h2>
 * <pre>
 *  ┌────────────────────────────────────────────┐  ← 标题：阿柚 的资料  [资料][归属]
 *  │ ╔════════════════════════════════════════╗ │
 *  │ ║ [主手][副手][头][胸][腿][脚]            ║ │  ← 装备行 y=18
 *  │ ║ [0][1][2][3][4][5][6][7][8]            ║ │
 *  │ ║ [9] ...                          [17]  ║ │  ← 27 格背包 y=44
 *  │ ║ [18] ...                         [26]  ║ │
 *  │ ╚════════════════════════════════════════╝ │
 *  │  血量 / 饥饿 / 心情 / 好感… 七行状态文字     │  ← y=106
 *  │  ┌──────────────────────────────────────┐  │
 *  │  │ 物品栏（原版 inventory.png 贴图）      │  │  ← y=186（原版背包 3×9）
 *  │  │ 快捷栏（原版贴图）                     │  │  ← y=244
 *  │  └──────────────────────────────────────┘  │
 *  └────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>为什么下半部分用原版贴图</h2>
 * 之前整个面板都是手绘色块，结果玩家背包和镇民背包长得一模一样，
 * 分不清"我放进去的东西到底进了谁的口袋"。现在下半部分直接裁原版
 * {@code inventory.png}：和按 E 打开的界面<b>像素级一致</b>，一眼就知道那是自己的。
 */
public class ResidentScreen extends AbstractContainerScreen<ResidentMenu> {

    // ==========================================================
    //  分页
    // ==========================================================

    /** 页编号直接用 {@link ResidentMenu} 的常量——两边必须是同一套数字，
     * {@link ResidentMenu#CLIENT_PAGE} 才能对上 isActive 的判断。 */
    private static final int PAGE_INFO = ResidentMenu.PAGE_INFO;
    /** 页编号：归属页（城镇列表）。 */
    private static final int PAGE_TOWN = ResidentMenu.PAGE_TOWN;

    /** 当前页。切页是纯客户端状态，不进网络。 */
    private int page = PAGE_INFO;

    // ==========================================================
    //  归属页列表参数
    // ==========================================================

    /** 一行的高度（px）。 */
    private static final int ROW_H = 13;
    /**
     * 列表可见行数。
     *
     * <p>列表区域从装备行顶（PANEL_Y）画到镇民背包底（BAG_Y + 3×18），
     * 高 80px；6 行 × 13 = 78，刚好铺满且不压到底部分隔线。
     * 城镇多于 6 个时滚轮翻动（见 {@link #mouseScrolled}）。
     */
    private static final int MAX_VISIBLE = 6;

    /** 列表滚动偏移（跳过的行数，≥0）。 */
    private int townScroll = 0;

    // ==========================================================
    //  常量
    // ==========================================================

    /** 文字行高。 */
    private static final int LINE = 10;

    /** 状态文字区距离 GUI 左边的内边距。 */
    private static final int TEXT_PAD = 10;

    /** 下半部分的小标题。用原版 key，中文下显示"物品栏"，英文下显示 Inventory。 */
    private static final Component INVENTORY_LABEL = Component.translatable("container.inventory");

    /** 镇民背包的小标题，说明上面那 27 格是谁的。 */
    private static final Component RESIDENT_BAG_LABEL = Component.translatable("gui.bmfstown.resident.bag");

    // ==========================================================
    //  原版物品栏贴图参数
    // ==========================================================

    /**
     * 从 inventory.png 里裁"背包 3×9"那一块。
     *
     * <p>原版物品栏的背包区块左上角在贴图坐标 (7, 83)，尺寸 176×54，
     * 里面每个 18×18 格子自带凹槽边框，直接 blit 出来就是原版效果。
     * 镇民背包的格子也用同一块 18×18 小贴图逐格画（见 {@link #blitCell}）。
     */
    private static final int VANILLA_CELL_U = 7;
    private static final int VANILLA_CELL_V = 83;

    /**
     * 从 inventory.png 里裁"快捷栏"那一块，坐标 (7, 141)，尺寸 176×18。
     */
    private static final int VANILLA_HOTBAR_V = 141;

    /** 这两块贴图的横向宽度都是 176，和 GUI 同宽。 */
    private static final int VANILLA_W = 176;

    /**
     * 贴图块的目标 x。
     *
     * <h2>坐标语义（错过一次的地方）</h2>
     * {@code blit(贴图, x, y, u, v, w, h, ...)} 的含义是：
     * <b>把贴图上 (u, v) 处那一块，画到 (x, y)</b>——裁剪起点的像素
     * 落在目标点上，不再有"整张贴图偏移"。
     *
     * <p>背包块从 u=7 裁起，块内第一个格子的凹槽在贴图 x=8，
     * 而我们的格子中心在 {@link ResidentMenu#PANEL_X}=8 ——
     * 所以目标 x = 8 - 1 = 7，凹槽正好套住格子。
     * <b>不是</b>"画整张贴图再平移到 x=1"——按那个模型算会整体错位 7px。
     */
    private static final int VANILLA_DEST_X = ResidentMenu.PANEL_X - 1;

    /** 原版物品栏整块的贴图路径（下半部分直接引用原版资源，不拷贝）。 */
    private static final ResourceLocation INVENTORY_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/gui/container/inventory.png");

    /**
     * 空装备槽的原版"影子"图标（1.21.1 的实际文件路径）。
     *
     * <p>顺序与 {@link ResidentMenu#EQUIP_ORDER} 一一对应。
     * 注意：原版到 1.21.4 才有空主手槽的剑影子（empty_slot_sword），
     * 1.21.1 没有这张图，所以主手给 {@code null}——只画格子不上图标。
     * 副手是盾牌影子——原版常量叫 EMPTY_ARMOR_SLOT_SHIELD，
     * 文件名是 shield 不是 offhand（叫 offhand 那版第一次就找错了，
     * 紫黑棋盘格就是贴图缺失的经典症状）。
     */
    private static final ResourceLocation[] EMPTY_EQUIP_ICONS = {
            null, // 主手：1.21.1 无原版空槽图标
            ResourceLocation.withDefaultNamespace("textures/item/empty_armor_slot_shield.png"),
            ResourceLocation.withDefaultNamespace("textures/item/empty_armor_slot_helmet.png"),
            ResourceLocation.withDefaultNamespace("textures/item/empty_armor_slot_chestplate.png"),
            ResourceLocation.withDefaultNamespace("textures/item/empty_armor_slot_leggings.png"),
            ResourceLocation.withDefaultNamespace("textures/item/empty_armor_slot_boots.png"),
    };

    public ResidentScreen(ResidentMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = ResidentMenu.GUI_WIDTH;
        this.imageHeight = ResidentMenu.GUI_HEIGHT;
        // 面板标题不再画（名字挪进了状态区），但标题字符串仍由
        // ResidentMenuProvider 提供，留着以防 tooltip/崩溃报告用到。
        // 隐藏父类自动画的"物品栏"——位置不对（它会画在 inventoryLabelY 处），
        // 我们在 renderLabels 里按原版位置手动画，见 INVENTORY_LABEL。
        this.inventoryLabelY = -1000;
        // 防御：万一上一次开面板时停在归属页就崩溃/退出，静态页码会残留。
        // 面板永远是资料页开场的，这里把静态状态一并归位。
        ResidentMenu.CLIENT_PAGE = PAGE_INFO;
    }

    // ==========================================================
    //  页签 / 按钮装配
    // ==========================================================

    /** 打开界面时请求城镇数据（归属页的列表来源）。 */
    @Override
    protected void init() {
        super.init();

        // 城镇列表缓存可能还是空的（本档没开过手册）——要一份，
        // 否则归属页只能显示"同步中"。open=false，不会把手册界面弹出来。
        ModNetworking.sendToServer(new ModNetworking.HandbookRequestPayload());

        BmfTown.LOGGER.debug("[BmfTown] 打开镇民面板，实体={}，页={}",
                this.menu.getResident() == null ? "null" : this.menu.getResident().getName().getString(),
                this.page);
    }

    private void switchPage(int newPage) {
        this.page = newPage;
        this.townScroll = 0; // 每次进归属页都回到列表头，别让玩家一进来就对着列表中段发呆

        // 关键一步：把页码写回 menu 的静态状态，驱动 Slot.isActive()。
        //
        // 之前只在 Screen 里记页码、渲染时跳过上板槽位——那只是"不画"，
        // Slot 还活着：vanilla 的 hoveredSlot 判定和 mouseClicked 都不走
        // renderSlot，于是归属页照样能对着看不见的格子拿放物品（隐形搬
        // 面包事件的根因）。vanilla 在渲染格子、高亮、tooltip 和点击分发
        // 前都会检查 isActive()，把它关掉才是真正的"关闭第一页"，
        // 而不是"把第一页盖在第二页下面"。
        //
        // 服务端不受影响：服务端没有 Screen，CLIENT_PAGE 恒为 PAGE_INFO，
        // isActive 服务端恒真，点击校验逻辑照常。
        ResidentMenu.CLIENT_PAGE = newPage;
    }

    // ==========================================================
    //  背景
    // ==========================================================

    @Override
    protected void renderBg(GuiGraphics gfx, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;

        // ---- 整体底板：原版对话框同款浅灰 + 明暗棱边 ----
        // 之前上半部分是 0xC0101010 的黑板，下半部分是原版浅灰贴图，
        // 一屏两种质感，看起来像两块 UI 拼的。现在整个面板统一成
        // 原版物品栏那种浅灰（C6C6C6）+ 上左亮边/下右暗边的 3D 棱边，
        // 镇民背包的格子贴图本来就是从原版抠的，颜色严丝合缝。
        gfx.fill(x, y, x + this.imageWidth, y + this.imageHeight, 0xFFC6C6C6);
        gfx.fill(x, y, x + this.imageWidth, y + 1, 0xFFFFFFFF);             // 上棱：亮
        gfx.fill(x, y, x + 1, y + this.imageHeight, 0xFFFFFFFF);            // 左棱：亮
        gfx.fill(x, y + this.imageHeight - 1, x + this.imageWidth, y + this.imageHeight, 0xFF555555); // 下棱：暗
        gfx.fill(x + this.imageWidth - 1, y, x + this.imageWidth, y + this.imageHeight, 0xFF555555);  // 右棱：暗

        // ---- 左上角页签（创造模式物品栏那种"标签页"） ----
        drawTabs(gfx, mouseX, mouseY);

        if (this.page == PAGE_TOWN) {
            renderTownPageBg(gfx, mouseX, mouseY);
        } else {
            renderInfoPageBg(gfx);
        }

        // ---- 下半部分：原版物品栏贴图（两页共用，玩家的背包不该因为翻页消失） ----
        //
        // blit 语义：把贴图上 (u,v) 处的块画到 (x,y)。背包块从 (7,83) 裁起，
        // 块内第一个格子凹槽在贴图 x=8 —— 目标 x 取 PANEL_X-1=7，
        // 凹槽正好落在我们的格子上；y 直接取 PLAYER_INV_Y-1（块顶对格子顶，
        // 格子顶在块内 1px 处）。之前按"整张贴图平移"的老模型减了 83，
        // 整块背包被抬高到状态文字区——就是那次截图里"贴图跑到上面"的错位。
        gfx.blit(INVENTORY_TEXTURE,
                x + VANILLA_DEST_X, y + ResidentMenu.PLAYER_INV_Y - 1,
                VANILLA_CELL_U, VANILLA_CELL_V,
                VANILLA_W, 3 * ResidentMenu.SLOT_SIZE,
                256, 256);

        gfx.blit(INVENTORY_TEXTURE,
                x + VANILLA_DEST_X, y + ResidentMenu.HOTBAR_Y - 1,
                VANILLA_CELL_U, VANILLA_HOTBAR_V,
                VANILLA_W, ResidentMenu.SLOT_SIZE,
                256, 256);
    }

    /** 资料页背景：镇民区域的格子逐格画上原版凹槽 + 空装备槽的影子图标。 */
    private void renderInfoPageBg(GuiGraphics gfx) {
        int x = this.leftPos;
        int y = this.topPos;
        int areaBottom = ResidentMenu.BAG_Y + 3 * ResidentMenu.SLOT_SIZE;
        // 分隔线：镇民区域和状态文字之间（浅灰底上用中灰，别抢格子）
        gfx.fill(x + 6, y + areaBottom + 3, x + this.imageWidth - 6, y + areaBottom + 4, 0xFF8B8B8B);

        // ---- 镇民的装备 + 背包：逐格画原版凹槽（和玩家背包同一种格子） ----
        // 之前这里是整块 0x60FFFFFF 的亮色板 + renderSlot 里的半透明黑，
        // 一片灰、和原版风格脱节。现在每格 blit 一块 inventory.png 里的
        // 18×18 格子贴图，视觉上和玩家背包完全一致。
        for (int i = ResidentMenu.EQUIP_START; i < ResidentMenu.PLAYER_INV_START; i++) {
            Slot slot = this.menu.slots.get(i);
            blitCell(gfx, slot);
        }

        // ---- 空装备槽的"影子"图标（盔甲/盾牌的深色剪影，原版同款） ----
        for (int i = ResidentMenu.EQUIP_START; i < ResidentMenu.EQUIP_START + ResidentMenu.EQUIP_ORDER.length; i++) {
            Slot slot = this.menu.slots.get(i);
            if (slot.hasItem()) {
                continue;
            }
            ResourceLocation icon = EMPTY_EQUIP_ICONS[i - ResidentMenu.EQUIP_START];
            if (icon == null) {
                continue; // 主手：1.21.1 无原版图标
            }
            gfx.blit(icon, x + slot.x, y + slot.y, 0, 0, 16, 16, 16, 16);
        }
    }

    /** 画一个原版样式的格子（18×18，含凹槽边框），对齐到槽位坐标。 */
    private void blitCell(GuiGraphics gfx, Slot slot) {
        // 槽位坐标是 16×16 物品区的左上角；格子贴图（含 1px 边框）要往外扩 1px
        gfx.blit(INVENTORY_TEXTURE,
                this.leftPos + slot.x - 1, this.topPos + slot.y - 1,
                VANILLA_CELL_U, VANILLA_CELL_V,
                ResidentMenu.SLOT_SIZE, ResidentMenu.SLOT_SIZE,
                256, 256);
    }

    /**
     * 归属页背景：整块上板换成城镇列表。
     *
     * <p>行的高亮画在背景层（在物品/文字之下），行文字画在
     * {@link #renderLabels}（在背景之上）——和原版容器"槽底 + 物品"一个分层思路。
     */
    private void renderTownPageBg(GuiGraphics gfx, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;

        // ---- 菜单底：整块深灰，和周围浅灰面板区分开（学原版语言列表） ----
        gfx.fill(x + 4, listTop(), x + this.imageWidth - 4, listBottom(), 0xFF373737);

        List<TownRow> rows = townRows();
        UUID current = currentTownId();
        for (int visible = 0; visible < MAX_VISIBLE; visible++) {
            int index = this.townScroll + visible;
            if (index >= rows.size()) {
                break;
            }
            int rowY = y + ResidentMenu.PANEL_Y + visible * ROW_H;
            boolean hovered = mouseX >= x + 5 && mouseX < x + this.imageWidth - 5
                    && mouseY >= rowY && mouseY < rowY + ROW_H;
            TownRow row = rows.get(index);
            if (row.isCurrent(current)) {
                // 选中行：更深的底 + 一圈亮边框（原版语言列表的选中样式）
                gfx.fill(x + 5, rowY, x + this.imageWidth - 5, rowY + ROW_H, 0xFF141414);
                gfx.renderOutline(x + 5, rowY,
                        this.imageWidth - 10, ROW_H, 0xFFE0E0E0);
            } else if (hovered) {
                gfx.fill(x + 5, rowY, x + this.imageWidth - 5, rowY + ROW_H, 0xFF4C4C4C);
            }
            // 普通行不画底——直接坐在深灰菜单上
        }
    }

    /** 归属页菜单的顶（GUI 相对坐标）。与 PANEL_Y 对齐，正好占满原背包区。 */
    private int listTop() {
        return ResidentMenu.PANEL_Y;
    }

    /** 归属页菜单的底（GUI 相对坐标）。6 行用完镇民背包区的高度。 */
    private int listBottom() {
        return ResidentMenu.PANEL_Y + MAX_VISIBLE * ROW_H;
    }

    @Override
    public void renderSlot(GuiGraphics gfx, Slot slot) {
        // 归属页：只隐藏"镇民自己的格子"（装备行 + 镇民背包）——
        // 它们是第一页的内容，归属页的菜单区域顶替了它们的位置。
        //
        // 为什么按 y 坐标划界而不是 slot.index：Slot.index 是"物品在它自己
        // 容器里的下标"，镇民背包是 0~26，玩家背包也是 9~35——两者重叠！
        // 用 index 判断会把玩家背包也误隐藏（v0.5 第一版就栽在这）。
        // 按 y 划界则干净利落：上板（y < PLAYER_INV_Y）= 第一页专属，
        // 下板（玩家物品栏 + 快捷栏）= 两页共用。
        if (this.page == PAGE_TOWN && slot.y < ResidentMenu.PLAYER_INV_Y) {
            return;
        }
        super.renderSlot(gfx, slot);
    }

    // ==========================================================
    //  文字
    // ==========================================================

    @Override
    protected void renderLabels(GuiGraphics gfx, int mouseX, int mouseY) {
        // 面板标题（"老周 的资料"）已删除：名字作为状态区第一行和数据显示
        // 在一起（见 renderInfoPageText），左上角让给创造模式风格的页签。

        if (this.menu.getResident() == null) {
            // 客户端没拿到实体（比如走远了）——显示提示而不是崩
            gfx.drawString(this.font, I18n.get("gui.bmfstown.resident.syncing"), TEXT_PAD, ResidentMenu.STATS_Y + 10, 0x555555, false);
            drawBottomLabels(gfx, false);
            return;
        }

        if (this.page == PAGE_TOWN) {
            renderTownPageText(gfx);
        } else {
            renderInfoPageText(gfx);
        }
        drawBottomLabels(gfx, this.page == PAGE_INFO);
    }

    /** 资料页的状态文字（名字 + 七行状态）。浅灰底上用深色字。 */
    private void renderInfoPageText(GuiGraphics gfx) {
        Resident resident = this.menu.getResident();
        int textY = ResidentMenu.STATS_Y;

        // ---- 第 0 行：名字。和数据显示在一起，代替原来的面板标题。 ----
        gfx.drawString(this.font, resident.getName(), TEXT_PAD, textY, 0x303030, false);
        textY += LINE;
        //
        // 数值一律从 menu 的数据槽读，不从实体档案读。
        // 数据槽由服务端 broadcastChanges 每 tick 比对推送，是实时的；
        // 实体档案（SynchedEntityData）虽然也会同步，但那是"变了就发"，
        // 而数据槽是"面板开着才轮询"，两者时序不同——面板要的是前者。
        // 专长/喜好/归属三行是低频信息，从档案读没有实时性问题。
        int healthHalf = this.menu.getStat(ResidentMenu.DATA_HEALTH);
        int hunger = this.menu.getStat(ResidentMenu.DATA_HUNGER);
        int mood = this.menu.getStat(ResidentMenu.DATA_MOOD);
        int affection = this.menu.getStat(ResidentMenu.DATA_AFFECTION);

        // 1. 血量（数据槽存的是半颗心数，÷2 还原）。浅灰底配色：原版聊天色的深变体
        drawStat(gfx, textY, I18n.get("gui.bmfstown.resident.stat.health"),
                healthHalf / 2 + " / " + (int) resident.getMaxHealth(), 0xB02020);
        // 2. 饥饿（20 点 = 10 个鸡腿，显示成 "当前/20"）
        drawStat(gfx, textY + LINE, I18n.get("gui.bmfstown.resident.stat.hunger"),
                hunger + " / " + ResidentProfile.MAX_HUNGER, 0xA87300);
        // 3. 心情（带表情符号，档位凭数值现算，阈值只有一份）
        ResidentProfile.MoodTier moodTier = ResidentProfile.moodTierOf(mood);
        drawStat(gfx, textY + LINE * 2, I18n.get("gui.bmfstown.resident.stat.mood"),
                I18n.get("gui.bmfstown.resident.stat.mood_value",
                        I18n.get(moodTier.displayName()), moodTier.bubble(), mood), 0x2E7D2E);
        // 4. 好感度
        drawStat(gfx, textY + LINE * 3, I18n.get("gui.bmfstown.resident.stat.affection"),
                I18n.get(ResidentProfile.affectionTierOf(affection).displayName())
                        + " (" + affection + ")", 0x3B3BC4);

        // 5. 专长 + 等级（静态信息，从档案读）
        ResidentProfile p = resident.getProfile();
        String profLine = I18n.get(p.profession().displayName())
                + (p.profession().isReal() ? " · " + p.title() + " Lv" + p.level() : "");
        drawStat(gfx, textY + LINE * 4, I18n.get("gui.bmfstown.resident.stat.profession"), profLine, 0x555555);

        // 6. 爱好一行（压缩显示，UI 空间有限）
        String hobby = I18n.get(p.hobbyFood().displayName()) + "/" + I18n.get(p.hobbyHomeItem().displayName())
                + " · " + I18n.get("gui.bmfstown.resident.stat.hobby_dislike", I18n.get(p.dislikeItem().displayName()));
        drawStat(gfx, textY + LINE * 5, I18n.get("gui.bmfstown.resident.stat.hobby"), hobby, 0x555555);

        // 7. 归属（摘要）。详细的绑定/解绑去"归属"页做。
        UUID townId = p.townId();
        String townText;
        if (townId == null) {
            townText = I18n.get("gui.bmfstown.resident.town.free");
        } else {
            String found = null;
            for (TownHandbookClient.TownView t : TownHandbookClient.towns()) {
                if (t.id().equals(townId)) {
                    found = t.name();
                    break;
                }
            }
            townText = found != null
                    ? I18n.get("gui.bmfstown.resident.town.bound", found)
                    : I18n.get("gui.bmfstown.resident.town.syncing");
        }
        drawStat(gfx, textY + LINE * 6, I18n.get("gui.bmfstown.resident.stat.town"), townText, townId == null ? 0x808080 : 0x8A5A00);
    }

    /**
     * 归属页的列表文字。
     *
     * <p>行背景已在 {@link #renderTownPageBg} 画好，这里只画文字：
     * 第 0 行固定是"不分配"（解绑入口），其余是本维度的城镇，
     * 当前归属的行前面画 ✔。
     */
    private void renderTownPageText(GuiGraphics gfx) {
        // 右上角带个名字——归属页没有状态区，名字别跟着消失
        Resident resident = this.menu.getResident();
        if (resident != null) {
            String name = resident.getName().getString();
            gfx.drawString(this.font, name,
                    this.imageWidth - 6 - this.font.width(name), 4, 0x303030, false);
        }

        List<TownRow> rows = townRows();
        UUID current = currentTownId();

        for (int visible = 0; visible < MAX_VISIBLE; visible++) {
            int index = this.townScroll + visible;
            if (index >= rows.size()) {
                break;
            }
            TownRow row = rows.get(index);
            // 注意：renderLabels 的画笔已被平移到 GUI 原点，
            // 这里用 GUI 相对坐标——再叠加 topPos 就会双重偏移，
            // 文字和行背景错位（第一次实现就栽在这）。
            int rowY = listTop() + visible * ROW_H + 3; // +3：文字在 13px 行内垂直居中
            int color = row.isCurrent(current) ? 0xFFFFFF : 0xE0E0E0;
            String prefix = row.isCurrent(current) ? "✔ " : "  ";
            gfx.drawString(this.font, prefix + row.label(), 8, rowY, color, false);
        }

        // 底部提示行：菜单下方（浅灰底上用深灰字）
        String hint = rows.size() > MAX_VISIBLE
                ? I18n.get("gui.bmfstown.resident.town.hint_scroll")
                : I18n.get("gui.bmfstown.resident.town.hint_click");
        gfx.drawString(this.font, hint, 8, ResidentMenu.STATS_Y + LINE, 0x555555, false);
    }

    /** 下半部分的固定标签。镇民背包标题只在资料页画（归属页那块被列表盖了）。 */
    private void drawBottomLabels(GuiGraphics gfx, boolean infoPage) {
        // 现在底板是原版同款浅灰，直接用原版的深灰字，视觉一致
        gfx.drawString(this.font, INVENTORY_LABEL, 8,
                ResidentMenu.PLAYER_INV_Y - 9, 0x404040, false);
        if (infoPage) {
            gfx.drawString(this.font, RESIDENT_BAG_LABEL, ResidentMenu.PANEL_X,
                    ResidentMenu.BAG_Y - 9, 0x8A5A00, false);
        }
    }

    /**
     * 画一行"标签：值"。
     *
     * <p>标签用灰色固定宽度，值用彩色——这样多行对齐，读起来不费劲。
     */
    private void drawStat(GuiGraphics gfx, int y, String label, String value, int color) {
        gfx.drawString(this.font, label, TEXT_PAD, y, 0x707070, false);
        gfx.drawString(this.font, value, TEXT_PAD + 32, y, color, false);
    }

    // ==========================================================
    //  归属页：数据与交互
    // ==========================================================

    /** 列表一行的展示数据：第 0 项固定"不分配"，其余是城镇。 */
    private record TownRow(String label, UUID townId) {

        boolean isCurrent(UUID currentId) {
            // "不分配"行的 townId 是 null：只在镇民本来就自由身时算"当前"
            if (this.townId == null) {
                return currentId == null;
            }
            return this.townId.equals(currentId);
        }
    }

    /** 当前归属（镇民档案里的 townId；镇民不在时 null）。 */
    private UUID currentTownId() {
        Resident r = this.menu.getResident();
        return r == null ? null : r.getProfile().townId();
    }

    /**
     * 组装归属页的完整列表：不分配 + 本维度城镇。
     *
     * <p>维度取镇民所在维度（镇民不在时退回玩家维度）——跨维度的城镇
     * 锁不住镇民，列表里出现只会误导。
     */
    private List<TownRow> townRows() {
        java.util.List<TownRow> rows = new java.util.ArrayList<>();
        rows.add(new TownRow(I18n.get("gui.bmfstown.resident.town.unassigned"), null));
        for (TownHandbookClient.TownView t : TownHandbookClient.townsInDimension(residentDimension())) {
            rows.add(new TownRow(I18n.get("gui.bmfstown.resident.town.row", t.name(), t.owner()), t.id()));
        }
        return rows;
    }

    /** 镇民所在维度；镇民不在（客户端没同步到实体）时退回玩家维度。 */
    private String residentDimension() {
        Resident r = this.menu.getResident();
        Level level = r != null ? r.level()
                : this.minecraft != null ? this.minecraft.level : null;
        return level == null ? "" : level.dimension().location().toString();
    }

    /**
     * 鼠标点击：归属页处理列表行，资料页放行给原版（槽位拖拽）。
     *
     * <p>归属页要"吞掉"列表区域的点击——那块下面躺着被隐藏的
     * 装备/背包格子，点了虽然看不见物品，但会真的搬东西（隐形搬箱子
     * 是最恶性的那种 bug）。所以列表区域内的点击永远不传给 super。
     */
    // ==========================================================
    //  页签（创造模式物品栏风格）
    // ==========================================================

    /** 页签文字的语言文件 key，下标即页编号。 */
    private static final String[] TAB_KEYS = {
            "gui.bmfstown.resident.tab.info", "gui.bmfstown.resident.tab.town"};
    /** 页签文字（每次绘制时从 lang 取，语言切换即时生效）。 */
    private static String[] tabNames() {
        return new String[]{I18n.get(TAB_KEYS[0]), I18n.get(TAB_KEYS[1])};
    }
    /** 页签宽度（px），与 TAB_KEYS 一一对应。 */
    private static final int[] TAB_WIDTHS = {38, 32};

    /**
     * 画左上角的页签。
     *
     * <h2>选中和未选中的视觉差（参考创造模式物品栏）</h2>
     * <ul>
     *   <li><b>选中</b>：更高（上缘更高、下缘直接连进面板，没有下边框——
     *       "这个标签和下面的内容是一个整体"）、底色更亮、字更深</li>
     *   <li><b>未选中</b>：更矮更低（整体下沉）、底色更暗、带一条下边框——
     *       "陷在后面"。悬停时稍微提亮，暗示可以点</li>
     * </ul>
     */
    private void drawTabs(GuiGraphics gfx, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        int tx = 6;
        String[] names = tabNames();
        for (int i = 0; i < TAB_KEYS.length; i++) {
            int w = TAB_WIDTHS[i];
            boolean sel = this.page == i;
            boolean hovered = !sel
                    && mouseX >= x + tx && mouseX < x + tx + w
                    && mouseY >= y + 2 && mouseY < y + 15;
            int top = sel ? y + 2 : y + 5;
            int bottom = sel ? y + ResidentMenu.PANEL_Y : y + 15;
            int fill = sel ? 0xFFDCDCDC : hovered ? 0xFFB8B8B8 : 0xFFABABAB;
            gfx.fill(x + tx, top, x + tx + w, bottom, fill);
            // 上/左亮棱、右暗棱——和面板同一套 3D 语言
            gfx.fill(x + tx, top, x + tx + w, top + 1, sel ? 0xFFFFFFFF : 0xFFC8C8C8);
            gfx.fill(x + tx, top, x + tx + 1, bottom, sel ? 0xFFFFFFFF : 0xFFC8C8C8);
            gfx.fill(x + tx + w - 1, top, x + tx + w, bottom, sel ? 0xFF9A9A9A : 0xFF7A7A7A);
            if (!sel) {
                gfx.fill(x + tx, bottom - 1, x + tx + w, bottom, 0xFF555555);
            }
            String name = names[i];
            int textX = x + tx + (w - this.font.width(name)) / 2;
            int textY = top + (bottom - top - 8) / 2;
            gfx.drawString(this.font, name, textX, textY, sel ? 0x303030 : 0x505050, false);
            tx += w + 2;
        }
    }

    /** 页签命中判定。返回 true = 点中了某个页签（已切换）。 */
    private boolean clickTab(double mouseX, double mouseY) {
        int tx = 6;
        for (int i = 0; i < TAB_KEYS.length; i++) {
            int w = TAB_WIDTHS[i];
            if (mouseX >= this.leftPos + tx && mouseX < this.leftPos + tx + w
                    && mouseY >= this.topPos + 2 && mouseY < this.topPos + 15) {
                if (this.page != i) {
                    switchPage(i);
                }
                return true;
            }
            tx += w + 2;
        }
        return false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 页签在最上层：两页都要先判，点中就吞掉（下面不会有什么可点的东西）
        if (button == 0 && clickTab(mouseX, mouseY)) {
            return true;
        }
        if (this.page == PAGE_TOWN && button == 0) {
            // ---- 列表行点击 ----
            int rowTop = this.topPos + listTop();
            int rowBottom = this.topPos + listBottom();
            boolean inList = mouseX >= this.leftPos + 4 && mouseX < this.leftPos + this.imageWidth - 4
                    && mouseY >= rowTop && mouseY < rowBottom;
            if (inList) {
                int visible = (int) ((mouseY - rowTop) / ROW_H);
                int index = this.townScroll + visible;
                List<TownRow> rows = townRows();
                if (index < rows.size()) {
                    TownRow row = rows.get(index);
                    int buttonId = row.townId() == null
                            ? ResidentMenu.BTN_UNASSIGN
                            : ResidentMenu.BTN_ASSIGN_BASE + (index - 1);
                    this.clickMenu(buttonId);
                }
                return true; // 点在菜单里（哪怕没点中行）都吞掉
            }

            // ---- 上板其余区域也是死区 ----
            // 归属页的装备/背包格子被列表替换了，但 Slot 还躺在容器里——
            // 不吞掉点击的话，玩家会"对着看不见的格子拿放物品"
            //（截图里那块悬空的面包就是这么来的：点了隐藏格子把面包拿在了光标上）。
            boolean inPanel = mouseX >= this.leftPos && mouseX < this.leftPos + this.imageWidth
                    && mouseY >= this.topPos && mouseY < this.topPos + ResidentMenu.PLAYER_INV_Y - 4;
            if (inPanel) {
                return true;
            }
            // 上板之外（玩家背包、快捷栏）照常走原版逻辑
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * 归属页滚轮翻列表。资料页的滚轮不管（没有可滚的东西）。
     *
     * <p>1.21 的签名带双向增量，我们只用纵向 {@code scrollY}
     * （鼠标向前推为正，列表向上滚）。
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.page == PAGE_TOWN) {
            int max = Math.max(0, townRows().size() - MAX_VISIBLE);
            this.townScroll = Mth.clamp(this.townScroll - (int) Math.signum(scrollY), 0, max);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /**
     * 把按钮点击转成原版容器按钮包。
     *
     * <p>{@code handleInventoryButtonClick} 是原版 {@code MultiPlayerGameMode}
     * 的方法，走的就是熔炉按钮同一套协议——不用自建 payload。
     */
    private void clickMenu(int buttonId) {
        if (this.minecraft != null && this.minecraft.gameMode != null) {
            this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, buttonId);
        }
    }

    // ==========================================================
    //  悬停提示
    // ==========================================================

    /**
     * 鼠标移到装备槽上时，除了显示物品本身的 tooltip，
     * 再补一行"这是哪个槽"的说明。
     *
     * <p>原版 {@code AbstractContainerScreen} 已经处理了物品 tooltip，
     * 这里只在<b>空槽</b>时补充说明，避免和原版重复。
     */
    @Override
    protected void renderTooltip(GuiGraphics gfx, int mouseX, int mouseY) {
        super.renderTooltip(gfx, mouseX, mouseY);

        if (this.page != PAGE_INFO) {
            return; // 归属页没有可见槽位，不补提示
        }

        Slot hovered = this.hoveredSlot;
        if (hovered == null || hovered.hasItem()) {
            return;
        }
        if (hovered.index >= ResidentMenu.EQUIP_START && hovered.index < ResidentMenu.BAG_START) {
            int i = hovered.index - ResidentMenu.EQUIP_START;
            EquipmentSlot type = ResidentMenu.EQUIP_ORDER[i];
            gfx.renderTooltip(this.font,
                    Component.translatable(equipSlotKey(type)).withStyle(ChatFormatting.GRAY),
                    mouseX, mouseY);
        }
    }

    /** 装备槽说明的语言文件 key。 */
    private static String equipSlotKey(EquipmentSlot type) {
        return switch (type) {
            case MAINHAND -> "gui.bmfstown.resident.slot.mainhand";
            case OFFHAND -> "gui.bmfstown.resident.slot.offhand";
            case HEAD -> "gui.bmfstown.resident.slot.head";
            case CHEST -> "gui.bmfstown.resident.slot.chest";
            case LEGS -> "gui.bmfstown.resident.slot.legs";
            case FEET -> "gui.bmfstown.resident.slot.feet";
            default -> "gui.bmfstown.resident.slot.other";
        };
    }
}
