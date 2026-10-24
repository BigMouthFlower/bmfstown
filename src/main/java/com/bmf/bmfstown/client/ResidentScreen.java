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
    /** 页编号：工作页（职业分配 + 战士工作模式）。 */
    private static final int PAGE_WORK = ResidentMenu.PAGE_WORK;
    /** 页编号：喜好页（偏好一览 + 想法日志）。 */
    private static final int PAGE_HOBBY = ResidentMenu.PAGE_HOBBY;
    private static final int PAGE_THOUGHTS = ResidentMenu.PAGE_THOUGHTS;

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

    /**
     * 归属页的"分节偏移"：0 = 城镇列表分节，1 = 住宅列表分节。
     *
     * <p>两段共用同一块列表区域（6 行），点顶部的分节标题切换。
     * 这样不用把面板加高就能同时管理"归属哪个城镇"和"住哪个住宅区"。
     */
    private int section = 0;

    /** 城镇分节。 */
    private static final int SECTION_TOWN = 0;
    /** 住宅分节。 */
    private static final int SECTION_HOUSING = 1;
    /** 分节总数。 */
    private static final int SECTION_COUNT = 2;

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
        // ---- 按页决定面板高度（必须在 super.init() 之前：leftPos/topPos 由它算） ----
        // 归属/工作页不再有玩家背包，下半段收掉——归属页只留列表，
        // 工作页留出 perk 节点区（按"三节点全展开"的最大高度取值，展开/收起不跳动）。
        this.imageWidth = ResidentMenu.GUI_WIDTH;
        this.imageHeight = switch (this.page) {
            case PAGE_TOWN -> TOWN_GUI_HEIGHT;
            case PAGE_WORK -> WORK_GUI_HEIGHT;
            case PAGE_HOBBY -> HOBBY_GUI_HEIGHT;
            case PAGE_THOUGHTS -> THOUGHTS_GUI_HEIGHT;
            default -> ResidentMenu.GUI_HEIGHT;
        };
        super.init();

        // 城镇列表缓存可能还是空的（本档没开过手册）——要一份，
        // 否则归属页只能显示"同步中"。open=false，不会把手册界面弹出来。
        ModNetworking.sendToServer(new ModNetworking.HandbookRequestPayload());

        BmfTown.LOGGER.debug("[BmfTown] 打开镇民面板，实体={}，页={}",
                this.menu.getResident() == null ? "null" : this.menu.getResident().getName().getString(),
                this.page);
    }

    /** 归属页面板高：6 行列表 + 底边距（背包区已收起）。 */
    private static final int TOWN_GUI_HEIGHT = ResidentMenu.PANEL_Y + MAX_VISIBLE * ROW_H + 8;

    /**
     * 喜好页面板高：v0.8 偏好一览 10 行（名字/口味/工作/建材/动物/颜色/
     * 音乐/天气/生物/性格），行距 LINE，高度按 ROW_H 预留（与想法页同构）。
     */
    private static final int HOBBY_GUI_HEIGHT = ResidentMenu.PANEL_Y + 10 * ROW_H + 6;

    /** 想法日志可见条数（滚轮/滚动条翻更早的）。 */
    private static final int THOUGHTS_VISIBLE = 10;

    /** 想法页面板高：整页想法列表（可见 THOUGHTS_VISIBLE 条 + 滚动条）。 */
    // THOUGHTS_VISIBLE 必须声明在它前面——静态字段初始化器引用"后面才声明"的
    // 字段是非法前向引用，编译直接失败（v0.8 交付后本地编译踩坑，同 MoodState.TONE_*）。
    private static final int THOUGHTS_GUI_HEIGHT = ResidentMenu.PANEL_Y + THOUGHTS_VISIBLE * ROW_H + 6;

    /** 想法页当前滚动位置（0 = 最新的在最上面）。 */
    private int thoughtScroll = 0;

    /**
     * 工作页面板高：工作控件 + 经验条 + 效率行 + perk 节点区。
     * perk 区按"3 节点 + 1 个展开的分支组"的最大高度预留，展开/收起时面板不跳动。
     */
    private static final int WORK_GUI_HEIGHT = ResidentMenu.STATS_Y + 32 + 6 * ROW_H + 10;

    private void switchPage(int newPage) {
        this.page = newPage;
        this.townScroll = 0; // 每次进归属页都回到列表头，别让玩家一进来就对着列表中段发呆
        this.workScroll = 0; // 工作页职业列表同理归零
        this.thoughtScroll = 0; // 想法页回到最新一条
        this.thoughtScrollbarDragging = false; // 切页时结束拖拽，别把状态带到别的页
        this.perkExpandedNode = -1; // perk 节点全部收起

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

        // 页高随页变化：leftPos/topPos 依赖 imageHeight，必须重建一次布局
        this.rebuildWidgets();
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
        } else if (this.page == PAGE_WORK) {
            renderWorkPageBg(gfx, mouseX, mouseY);
        } else if (this.page == PAGE_HOBBY) {
            renderHobbyPageBg(gfx);
        } else if (this.page == PAGE_THOUGHTS) {
            renderThoughtsPageBg(gfx);
        } else {
            renderInfoPageBg(gfx);
        }

        // ---- 下半部分：原版物品栏贴图（仅资料页——归属/工作页已收起背包） ----
        //
        // blit 语义：把贴图上 (u,v) 处的块画到 (x,y)。背包块从 (7,83) 裁起，
        // 块内第一个格子凹槽在贴图 x=8 —— 目标 x 取 PANEL_X-1=7，
        // 凹槽正好落在我们的格子上；y 直接取 PLAYER_INV_Y-1（块顶对格子顶，
        // 格子顶在块内 1px 处）。之前按"整张贴图平移"的老模型减了 83，
        // 整块背包被抬高到状态文字区——就是那次截图里"贴图跑到上面"的错位。
        if (this.page == PAGE_INFO) {
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
        gfx.fill(x + 4, y + listTop(), x + this.imageWidth - 4, y + listBottom(), 0xFF373737);

        // ---- 顶部分节标签：城镇 / 住宅 ----
        drawSectionTabs(gfx, mouseX, mouseY);

        List<ListRow> rows = currentRows();
        UUID current = currentSectionTargetId();
        for (int visible = 0; visible < MAX_VISIBLE; visible++) {
            int index = this.townScroll + visible;
            if (index >= rows.size()) {
                break;
            }
            int rowY = y + rowsTop() + visible * ROW_H;
            boolean hovered = mouseX >= x + 5 && mouseX < x + this.imageWidth - 5
                    && mouseY >= rowY && mouseY < rowY + ROW_H;
            ListRow row = rows.get(index);
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

    /** 分节标签条的高度（两行：城镇 / 住宅）。 */
    private static final int SECTION_TAB_H = ROW_H;

    /** 行区起始 y（GUI 相对）：列表顶 + 分节标签条。 */
    private int rowsTop() {
        return listTop() + SECTION_TAB_H;
    }

    /** 归属页菜单的底（GUI 相对坐标）。6 行用完镇民背包区的高度。 */
    private int listBottom() {
        return ResidentMenu.PANEL_Y + MAX_VISIBLE * ROW_H;
    }

    /**
     * 画顶部两个分节标签（城镇 / 住宅）。
     *
     * <p>横向摊开、等宽：选中的那个底色更亮、字更白，与下面对应的列表
     * 视觉上连成一体（和容器页签同一套语言）。
     */
    private void drawSectionTabs(GuiGraphics gfx, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        int inner = this.imageWidth - 10;          // 左右各留 5px
        int halfW = inner / 2;
        String[] labels = {
                I18n.get("gui.bmfstown.resident.section.town"),
                I18n.get("gui.bmfstown.resident.section.housing")
        };
        for (int i = 0; i < SECTION_COUNT; i++) {
            int tx = x + 5 + i * halfW;
            int ty = y + listTop();
            boolean sel = this.section == i;
            boolean hovered = !sel && mouseX >= tx && mouseX < tx + halfW
                    && mouseY >= ty && mouseY < ty + SECTION_TAB_H;
            int fill = sel ? 0xFF5A5A5A : hovered ? 0xFF4C4C4C : 0xFF424242;
            gfx.fill(tx, ty, tx + halfW, ty + SECTION_TAB_H, fill);
            if (sel) {
                // 选中标签下面压一条亮线，暗示"当前列表属于这个分节"
                gfx.fill(tx, ty + SECTION_TAB_H - 1, tx + halfW, ty + SECTION_TAB_H, 0xFFE0E0E0);
            }
            String text = labels[i];
            int textX = tx + (halfW - this.font.width(text)) / 2;
            gfx.drawString(this.font, text, textX, ty + 3,
                    sel ? 0xFFFFFF : 0xB0B0B0, false);
        }
    }

    /** 分节标签命中判定。返回 true = 点中了某个分节（已切换）。 */
    private boolean clickSectionTab(double mouseX, double mouseY) {
        int y = this.topPos + listTop();
        if (mouseY < y || mouseY >= y + SECTION_TAB_H) {
            return false;
        }
        int inner = this.imageWidth - 10;
        int halfW = inner / 2;
        int rel = (int) (mouseX - (this.leftPos + 5));
        if (rel < 0 || rel >= halfW * SECTION_COUNT) {
            return false;
        }
        int clicked = rel / halfW;
        if (clicked != this.section) {
            this.section = clicked;
            this.townScroll = 0; // 换分节后列表内容不同，滚动位置必须归零
        }
        return true;
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
        // 工作页同样用列表顶替了镇民格子，一并隐藏
        if (this.page == PAGE_WORK && slot.y < ResidentMenu.PLAYER_INV_Y) {
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
        } else if (this.page == PAGE_WORK) {
            renderWorkPageText(gfx);
        } else if (this.page == PAGE_HOBBY) {
            renderHobbyPageText(gfx);
        } else if (this.page == PAGE_THOUGHTS) {
            renderThoughtsPageText(gfx);
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

        // 4. 专长 + 主技能段位（与工作页经验条同一数据源：技能经验体系）。
        //    旧的全局 xp/level 已休眠，这里若还读 p.title()/p.level()，
        //    就会出现"工作页显示老练、资料页显示新手"的两套口径打架。
        //    （v0.7：原第 4 行好感度随好感度系统删除，后续行上移一位。）
        ResidentProfile p = resident.getProfile();
        // 职业行 = 职业名 + 段位（如"战士 Lv.5（专家）"）。
        // 段位从该职业主技能的累计经验实时推导（SkillTiers.tierOf）。
        String profLine = I18n.get(p.profession().displayName());
        var mainSkill = p.profession().mainSkill();
        int profTier = -1;
        if (mainSkill != null) {
            int xp = p.skillXp(mainSkill);
            profTier = com.bmf.bmfstown.entity.profile.SkillTiers.tierOf(xp);
            String lv = I18n.get("gui.bmfstown.resident.stat.level",
                    com.bmf.bmfstown.entity.profile.SkillTiers.levelOf(xp));
            String tierName = I18n.get("gui.bmfstown.resident.tier." + profTier);
            profLine += " " + lv + I18n.get("gui.bmfstown.resident.stat.tier", tierName);
        }
        // 段位部分随等级变色（传奇为动态彩虹）；无职业保持灰色
        drawStat(gfx, textY + LINE * 3, I18n.get("gui.bmfstown.resident.stat.profession"),
                profLine, profTier >= 0 ? tierColor(profTier) : 0x555555);

        // 5. 归属（摘要）。口味与好恶职业去"喜好"页看，资料页不再重复。
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
        drawStat(gfx, textY + LINE * 4, I18n.get("gui.bmfstown.resident.stat.town"), townText, townId == null ? 0x808080 : 0x8A5A00);
    }

    /**
     * 喜好页背景：拆页后只剩偏好一览（浅色，无需背景元素）。
     */
    private void renderHobbyPageBg(GuiGraphics gfx) {
        // 内容全在文字层，背景无需额外绘制
    }

    /** 想法列表区顶部（相对面板）——整页列表。 */
    private int thoughtListTop() {
        return ResidentMenu.PANEL_Y + 1;
    }

    /** 想法列表区底部（相对面板）。 */
    private int thoughtListBottom() {
        return ResidentMenu.PANEL_Y + THOUGHTS_VISIBLE * ROW_H;
    }

    /** 滚动条几何：贴想法区右缘的一条窄轨道。 */
    private static final int SCROLLBAR_WIDTH = 4;

    private int scrollbarX() {
        return this.leftPos + this.imageWidth - 5 - SCROLLBAR_WIDTH;
    }

    private int thoughtMaxScroll() {
        Resident r = this.menu.getResident();
        int size = r == null ? 0 : ClientMoodState.thoughtsOf(r.getUUID()).size();
        return Math.max(0, size - THOUGHTS_VISIBLE);
    }

    /** 画滚动条：轨道常驻，条目不够一屏时滑块铺满整个轨道（即无可滚）。 */
    private void drawThoughtScrollbar(GuiGraphics gfx) {
        int trackX = scrollbarX();
        int trackY = this.topPos + thoughtListTop() + 1;
        int trackH = thoughtListBottom() - thoughtListTop() - 2;
        gfx.fill(trackX, trackY, trackX + SCROLLBAR_WIDTH, trackY + trackH, 0xFF1E1E1E);
        int max = thoughtMaxScroll();
        if (max <= 0) {
            return; // 内容不足一屏，滑块不画（轨道留着提示这里是滚动区）
        }
        int thumbH = Math.max(8, trackH * THOUGHTS_VISIBLE
                / (THOUGHTS_VISIBLE + max));
        int thumbY = trackY + (trackH - thumbH) * this.thoughtScroll / max;
        gfx.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbH, 0xFFC6C6C6);
    }

    /**
     * 滚动条拖拽状态：按下滑块/轨道时记录，拖动期间按鼠标 Y 换算滚动位置。
     * 记录"按点相对滑块顶的偏移"，点轨道上任意处都能直接跳过去接着拖。
     */
    private boolean thoughtScrollbarDragging = false;
    private int thoughtDragGrabOffset = 0;

    /**
     * 喜好页内容：v0.8 偏好 + 性格一览（想法去"想法"页看）。
     *
     * <p>数据来源是打开面板时服务端下发的 {@link ClientMoodState.MoodPrefs}
     * （{@code MoodSyncPayload.prefs} 紧凑串解析而来）——客户端只存不算，
     * 没同步过时显示"无"。
     *
     * <p>标签列比资料页宽（52px）："喜欢的建材"这类 5 字标签按 32px 会被
     * 值压字，所以本页走 {@link #drawPrefRow} 而不是 {@link #drawStat}。
     */
    private void renderHobbyPageText(GuiGraphics gfx) {
        Resident resident = this.menu.getResident();
        if (resident == null) {
            return;
        }
        ResidentProfile p = resident.getProfile();
        ClientMoodState.MoodPrefs prefs = ClientMoodState.prefsOf(resident.getUUID());
        int textY = ResidentMenu.PANEL_Y;
        gfx.drawString(this.font, resident.getName(), TEXT_PAD, textY, 0x303030, false);

        String none = I18n.get("gui.bmfstown.resident.stat.value_none");

        drawPrefRow(gfx, textY + LINE, I18n.get("gui.bmfstown.resident.stat.hobby"),
                I18n.get(p.hobbyFood().displayName()), 0x2E7D2E);

        drawPrefRow(gfx, textY + LINE * 2, I18n.get("gui.bmfstown.resident.stat.job_liked"),
                I18n.get(p.likedProfession().displayName()), 0x8A5A00);

        String blocks = prefs.blocks().isEmpty() ? none
                : String.join("、", prefs.blocks().stream()
                        .map(m -> I18n.get(m.displayName())).toList());
        drawPrefRow(gfx, textY + LINE * 3, I18n.get("gui.bmfstown.resident.stat.blocks"),
                blocks, 0x8A5A00);

        drawPrefRow(gfx, textY + LINE * 4, I18n.get("gui.bmfstown.resident.stat.animal"),
                prefs.animal() != null ? I18n.get(prefs.animal().displayName()) : none, 0x2E7D2E);

        drawPrefRow(gfx, textY + LINE * 5, I18n.get("gui.bmfstown.resident.stat.color"),
                prefs.color() != null ? I18n.get(prefs.color().displayName()) : none, 0x2E7D2E);

        drawPrefRow(gfx, textY + LINE * 6, I18n.get("gui.bmfstown.resident.stat.music"),
                prefs.music()
                        ? I18n.get("gui.bmfstown.resident.stat.value_yes")
                        : I18n.get("gui.bmfstown.resident.stat.value_no"),
                prefs.music() ? 0x2E7D2E : 0x808080);

        drawPrefRow(gfx, textY + LINE * 7, I18n.get("gui.bmfstown.resident.stat.weather"),
                prefs.weather() != null ? I18n.get(prefs.weather().displayName()) : none,
                prefs.weather() != null ? 0xA03030 : 0x808080);

        drawPrefRow(gfx, textY + LINE * 8, I18n.get("gui.bmfstown.resident.stat.mob"),
                prefs.mob() != null ? I18n.get(prefs.mob().displayName()) : none,
                prefs.mob() != null ? 0xA03030 : 0x808080);

        // 性格：名字 + 一句话侧写（同步失败时为 null → 显示"无"）。
        // 不带括号——"攀比（很在意装备好不好）"按 CJK 字宽算会溢出面板右缘
        if (prefs.personality() != null) {
            drawPrefRow(gfx, textY + LINE * 9,
                    I18n.get("gui.bmfstown.resident.stat.personality"),
                    I18n.get(prefs.personality().displayName())
                            + " " + I18n.get(prefs.personality().descKey()),
                    0x7B3FA0);
        } else {
            drawPrefRow(gfx, textY + LINE * 9,
                    I18n.get("gui.bmfstown.resident.stat.personality"), none, 0x808080);
        }
    }

    /**
     * 画一行"标签：值"（喜好页专用：标签列 52px 宽，容纳 5 字标签）。
     */
    private void drawPrefRow(GuiGraphics gfx, int y, String label, String value, int color) {
        gfx.drawString(this.font, label, TEXT_PAD, y, 0x707070, false);
        gfx.drawString(this.font, value, TEXT_PAD + 52, y, color, false);
    }

    /** 想法页背景：整页深灰列表底 + 右缘滚动条。 */
    private void renderThoughtsPageBg(GuiGraphics gfx) {
        gfx.fill(this.leftPos + 4, this.topPos + thoughtListTop(),
                this.leftPos + this.imageWidth - 4, this.topPos + thoughtListBottom(),
                0xFF373737);
        drawThoughtScrollbar(gfx);
    }

    /**
     * 想法页内容：整页想法日志（新在上，旧在下）。
     *
     * <p><b>沉浸感规则（用户定稿）</b>：想法条目只显示措辞不显示数值——
     * 程度靠文案表达，色调用 {@code tone} 标记分绿系/红系/中性灰，
     * 性格滤镜在文案尾部追加 + / − 标记（v0.8）。
     */
    private void renderThoughtsPageText(GuiGraphics gfx) {
        Resident resident = this.menu.getResident();
        if (resident == null) {
            return;
        }
        List<ModNetworking.MoodThought> thoughts =
                ClientMoodState.thoughtsOf(resident.getUUID());
        // 底层数组按"老→新"存；显示时倒过来：新想法在最上。
        // thoughtScroll=0 显示最末尾（最新）的 10 条，向下滚 start 前移看更早的。
        // 滚动方向与滚动条滑块一致：scroll 越大看得越早，滑块也越往下。
        int max = Math.max(0, thoughts.size() - THOUGHTS_VISIBLE);
        int start = Mth.clamp(thoughts.size() - THOUGHTS_VISIBLE - Mth.clamp(this.thoughtScroll, 0, max),
                0, Math.max(0, thoughts.size() - THOUGHTS_VISIBLE));
        int end = Math.min(thoughts.size(), start + THOUGHTS_VISIBLE);
        int rowY = ResidentMenu.PANEL_Y + 1;
        for (int i = end - 1; i >= start; i--) {
            ModNetworking.MoodThought t = thoughts.get(i);
            String line = I18n.get(t.key());
            // 性格滤镜标记（乐观"+" / 悲观"−"）已按用户 2026-10-25 决定不再显示；
            // Thought.marker 字段保留（同步协议与存档兼容），将来要恢复只改这里。
            // 深底上用原版聊天的亮绿/亮红/灰，暗色调在 0xFF373737 上看不清；
            // 中性事件（如金护甲）用灰色，不误导玩家往好坏两边猜
            int color = switch (t.tone()) {
                case 1 -> 0x55FF55;
                case 2 -> 0xFF5555;
                default -> 0xB0B0B0;
            };
            gfx.drawString(this.font, line, TEXT_PAD, rowY, color, false);
            rowY += ROW_H;
        }
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

        List<ListRow> rows = currentRows();
        UUID current = currentSectionTargetId();

        for (int visible = 0; visible < MAX_VISIBLE; visible++) {
            int index = this.townScroll + visible;
            if (index >= rows.size()) {
                break;
            }
            ListRow row = rows.get(index);
            // 注意：renderLabels 的画笔已被平移到 GUI 原点，
            // 这里用 GUI 相对坐标——再叠加 topPos 就会双重偏移，
            // 文字和行背景错位（第一次实现就栽在这）。
            int rowY = rowsTop() + visible * ROW_H + 3; // +3：文字在 13px 行内垂直居中
            int color = row.full() ? 0x707070
                    : row.isCurrent(current) ? 0xFFFFFF : 0xE0E0E0;
            String prefix = row.isCurrent(current) ? "✔ " : "  ";
            String suffix = row.full() ? I18n.get("gui.bmfstown.resident.housing.full") : "";
            gfx.drawString(this.font, prefix + row.label() + suffix, 8, rowY, color, false);
        }

        // 底部提示行：菜单下方（浅灰底上用深灰字）
        String hint;
        if (this.section == SECTION_HOUSING && currentTownId() == null) {
            // 住宅分节但还没绑城镇：说明为什么列表是空的
            hint = I18n.get("gui.bmfstown.resident.housing.need_town");
        } else if (rows.size() > MAX_VISIBLE) {
            hint = I18n.get("gui.bmfstown.resident.town.hint_scroll");
        } else {
            hint = I18n.get("gui.bmfstown.resident.town.hint_click");
        }
        gfx.drawString(this.font, hint, 8, listBottom() + 3, 0x555555, false);
    }

    /** 下半部分的固定标签。只在资料页画——归属/工作页背包区已收起。 */
    private void drawBottomLabels(GuiGraphics gfx, boolean infoPage) {
        if (!infoPage) {
            return;
        }
        // 现在底板是原版同款浅灰，直接用原版的深灰字，视觉一致
        gfx.drawString(this.font, INVENTORY_LABEL, 8,
                ResidentMenu.PLAYER_INV_Y - 9, 0x404040, false);
        gfx.drawString(this.font, RESIDENT_BAG_LABEL, ResidentMenu.PANEL_X,
                ResidentMenu.BAG_Y - 9, 0x8A5A00, false);
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

    // ---- 段位配色（泰拉瑞亚稀有度风格） ----

    /**
     * 段位 0~8 的显示色（下标 = 段位）。
     * 白 → 灰绿 → 绿 → 青 → 蓝 → 紫 → 橙 → 红 → 洋红，传奇(9)另行走彩虹。
     */
    private static final int[] TIER_COLORS = {
            0xFFFFFF, 0xB8C8A0, 0x5FD35F, 0x4FC3E8, 0x7B7BFF,
            0xB266E8, 0xFFA028, 0xFF5050, 0xFF60B8,
    };

    /**
     * 段位对应的文字颜色。
     *
     * <p>传奇（最高段位）用<b>持续渐变彩虹</b>：HSL 色相 2 秒转一圈，
     * 饱和度/亮度固定（0.85/1.0），只变色相——有流动感但不刺眼。
     * 每帧重算所以面板开着就会一直流动。
     */
    private int tierColor(int tier) {
        if (tier >= com.bmf.bmfstown.entity.profile.SkillTiers.MAX_TIER) {
            float hue = (System.currentTimeMillis() % 2000L) / 2000.0F;
            return Mth.hsvToRgb(hue, 0.85F, 1.0F);
        }
        return TIER_COLORS[Math.max(0, Math.min(TIER_COLORS.length - 1, tier))];
    }

    /** 画一段文字并返回结束 x（一行内分段染色用）。 */
    private int drawSegment(GuiGraphics gfx, int x, int y, String text, int color) {
        if (!text.isEmpty()) {
            gfx.drawString(this.font, text, x, y, color, false);
        }
        return x + this.font.width(text);
    }

    // ==========================================================
    //  工作页（职业分配 + 战士工作模式）
    // ==========================================================

    /** 工作页按钮高度。 */
    private static final int WORK_BTN_H = 14;
    /** 工作页按钮之间的间距。 */
    private static final int WORK_BTN_GAP = 3;
    /** 工作页职业列表可见行数。职业再多，列表高度也固定，其余滚轮翻。 */
    private static final int VISIBLE_PROF_ROWS = 3;
    /** 职业列表行数。客户端与服务端都按 {@code ProfessionType.values()} 原顺序列，下标天然一致。 */
    private static final int PROFESSION_ROW_COUNT = com.bmf.bmfstown.entity.profile.ProfessionType.values().length;
    /** 工作页职业列表的滚动偏移。 */
    private int workScroll = 0;

    // ---- 工作页布局（v0.5 修订）：职业选框只包住可见行，按钮/经验条在框下方 ----
    // 旧布局沿用了归属页的 6 行框，按钮画进了选框内部且与经验条文字重叠。

    /** 工作页职业行起始 y（GUI 相对）：选框顶 + 内边距。 */
    private int workRowTop() {
        return listTop() + 2;
    }

    /** 工作页职业选框的底（GUI 相对）：行区 + 内边距。 */
    private int workListBottom() {
        return workRowTop() + VISIBLE_PROF_ROWS * ROW_H + 2;
    }

    /** 工作模式按钮区的起始 y（GUI 相对）：选框下方留 4px 空隙。 */
    private int workButtonsTop() {
        return workListBottom() + 4;
    }

    // ---- perk 节点区（工作页下半段，背包收起后腾出的空间） ----

    /** 展开的 perk 节点下标（-1 = 全部收起）。选择结果走 PerkChoosePayload 落档。 */
    private int perkExpandedNode = -1;

    /** 节点/分支定义表（{@link com.bmf.bmfstown.entity.profile.Perks}，下标对齐）。 */
    private static final String[] PERK_NODE_IDS = com.bmf.bmfstown.entity.profile.Perks.NODES;
    private static final String[][] PERK_BRANCH_IDS = com.bmf.bmfstown.entity.profile.Perks.BRANCHES;

    /** 节点解锁所需段位（与 {@code Perks.UNLOCK_TIER} 一致，避免每次跨类引用）。 */
    private static final int[] PERK_NODE_TIERS = com.bmf.bmfstown.entity.profile.Perks.UNLOCK_TIER;

    /** perk 区顶（GUI 相对）：效率行下方留 2px。 */
    private int perkAreaTop() {
        return ResidentMenu.STATS_Y + 32;
    }

    /** perk 区总行数（3 节点 + 展开的分支组）。 */
    private int perkRowCount() {
        return PERK_NODE_IDS.length + (this.perkExpandedNode >= 0
                ? PERK_BRANCH_IDS[this.perkExpandedNode].length : 0);
    }

    /** 某节点当前选中的分支序号；未选返回 -1。 */
    private int perkSelectedBranch(int node) {
        Resident r = this.menu.getResident();
        if (r == null) {
            return -1;
        }
        String choice = r.getProfile().perkChoice(PERK_NODE_IDS[node]);
        if (choice == null) {
            return -1;
        }
        for (int b = 0; b < PERK_BRANCH_IDS[node].length; b++) {
            if (PERK_BRANCH_IDS[node][b].equals(choice)) {
                return b;
            }
        }
        return -1;
    }

    /** perk 区底（GUI 相对）。 */
    private int perkAreaBottom() {
        return perkAreaTop() + perkRowCount() * ROW_H + 4;
    }

    /** 当前职业主技能的段位；无职业/无主技能返回 -1（perk 区全锁定）。 */
    private int mainSkillTier() {
        var current = currentProfession();
        var skill = current == null ? null : current.mainSkill();
        if (skill == null || this.menu.getResident() == null) {
            return -1;
        }
        return com.bmf.bmfstown.entity.profile.SkillTiers.tierOf(
                this.menu.getResident().getProfile().skillXp(skill));
    }

    /** 当前镇民的职业（客户端档案已同步；镇民不在时返回 null）。 */
    private com.bmf.bmfstown.entity.profile.ProfessionType currentProfession() {
        Resident r = this.menu.getResident();
        return r == null ? null : r.getProfile().profession();
    }

    /** 当前工作模式序号（读实体同步数据；镇民不在/数据未到时按无工作处理）。 */
    private int currentWorkMode() {
        Resident r = this.menu.getResident();
        return r == null ? 0 : r.getWorkMode().ordinal();
    }

    /**
     * 工作页背景：深灰列表（职业）+ 两个工作模式按钮。
     * 视觉语言与归属页一致（深底 / 悬停提亮 / 选中加深 + 亮边）。
     */
    private void renderWorkPageBg(GuiGraphics gfx, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        gfx.fill(x + 4, y + listTop(), x + this.imageWidth - 4, y + workListBottom(), 0xFF373737);

        // ---- 职业列表（滚动窗口：可见 3 行，其余滚轮翻） ----
        var professions = com.bmf.bmfstown.entity.profile.ProfessionType.values();
        var current = currentProfession();
        for (int v = 0; v < VISIBLE_PROF_ROWS; v++) {
            int i = this.workScroll + v;
            if (i >= professions.length) {
                break;
            }
            int rowY = y + workRowTop() + v * ROW_H;
            boolean cur = current != null && current.ordinal() == i;
            boolean hovered = !cur && mouseX >= x + 5 && mouseX < x + this.imageWidth - 5
                    && mouseY >= rowY && mouseY < rowY + ROW_H;
            if (cur) {
                gfx.fill(x + 5, rowY, x + this.imageWidth - 5, rowY + ROW_H, 0xFF141414);
                gfx.renderOutline(x + 5, rowY, this.imageWidth - 10, ROW_H, 0xFFE0E0E0);
            } else if (hovered) {
                gfx.fill(x + 5, rowY, x + this.imageWidth - 5, rowY + ROW_H, 0xFF4C4C4C);
            }
        }

        // ---- 工作模式按钮（互斥：激活的那个亮起并描边）----
        // 只有当前职业有"工作方式"时才画（无业 = 什么都没有）。
        // 将来别的职业在这里按职业画各自的控件——这块区域不属于战士专属。
        if (workControlsVisible()) {
            int workMode = currentWorkMode();
            int bx = x + 5;
            int bw = this.imageWidth - 10;
            int followY = y + workButtonsTop();
            int guardY = followY + WORK_BTN_H + WORK_BTN_GAP;
            drawWorkButton(gfx, bx, bw, followY, workMode == 1, mouseX, mouseY);
            drawWorkButton(gfx, bx, bw, guardY, workMode == 2, mouseX, mouseY);
        }

        // ---- perk 节点区（占位）：深灰框，行背景与文字在文字层画 ----
        gfx.fill(x + 4, y + perkAreaTop(), x + this.imageWidth - 4, y + perkAreaBottom(), 0xFF373737);
        int perkTier = mainSkillTier();
        int perkRow = 0;
        for (int n = 0; n < PERK_NODE_TIERS.length; n++) {
            int rowY = y + perkAreaTop() + perkRow * ROW_H;
            boolean unlocked = perkTier >= PERK_NODE_TIERS[n];
            boolean expandedHere = n == this.perkExpandedNode && unlocked;
            boolean hovered = mouseX >= x + 5 && mouseX < x + this.imageWidth - 5
                    && mouseY >= rowY && mouseY < rowY + ROW_H;
            if (expandedHere) {
                // 展开的节点行：深底 + 亮边（归属页选中行同款语言）
                gfx.fill(x + 5, rowY, x + this.imageWidth - 5, rowY + ROW_H, 0xFF141414);
                gfx.renderOutline(x + 5, rowY, this.imageWidth - 10, ROW_H, 0xFFE0E0E0);
                perkRow++;
                for (int b = 0; b < PERK_BRANCH_IDS[n].length; b++) {
                    int branchY = y + perkAreaTop() + perkRow * ROW_H;
                    boolean bHovered = mouseX >= x + 5 && mouseX < x + this.imageWidth - 5
                            && mouseY >= branchY && mouseY < branchY + ROW_H;
                    if (bHovered) {
                        gfx.fill(x + 5, branchY, x + this.imageWidth - 5, branchY + ROW_H, 0xFF4C4C4C);
                    }
                    perkRow++;
                }
            } else {
                // 收起的节点行：解锁的可悬停提亮，锁定的死灰
                if (hovered && unlocked) {
                    gfx.fill(x + 5, rowY, x + this.imageWidth - 5, rowY + ROW_H, 0xFF4C4C4C);
                }
                perkRow++;
            }
        }
    }

    /**
     * 当前职业是否渲染"工作方式"控件（按钮/经验条等）。
     * 判据 = 该职业有主技能（有技能才有成长与工作产出可展示）。
     * 无业 → false：列表下面留白，控件区不属于任何一个职业专属。
     */
    private boolean workControlsVisible() {
        var current = currentProfession();
        return current != null && current.mainSkill() != null;
    }

    /**
     * 画一个工作模式按钮。
     *
     * <h2>开/关的视觉语言（拟物按压）</h2>
     * <ul>
     *   <li><b>开（激活）</b>：整体下沉变暗（凹陷），上/左暗棱、下/右亮棱
     *       ——像被按进去的实体按钮</li>
     *   <li><b>关</b>：正常凸起，上/左亮棱、下/右暗棱，悬停提亮暗示可点</li>
     * </ul>
     */
    private void drawWorkButton(GuiGraphics gfx, int x, int w, int y,
                                boolean active, int mouseX, int mouseY) {
        boolean hovered = !active && mouseX >= x && mouseX < x + w
                && mouseY >= y && mouseY < y + WORK_BTN_H;
        if (active) {
            // 按下去：深底 + 上/左暗棱 + 下/右亮棱（凹陷感）
            gfx.fill(x, y, x + w, y + WORK_BTN_H, 0xFF2E2E2E);
            gfx.fill(x, y, x + w, y + 1, 0xFF161616);
            gfx.fill(x, y, x + 1, y + WORK_BTN_H, 0xFF161616);
            gfx.fill(x, y + WORK_BTN_H - 1, x + w, y + WORK_BTN_H, 0xFF8A8A8A);
            gfx.fill(x + w - 1, y, x + w, y + WORK_BTN_H, 0xFF8A8A8A);
        } else {
            // 起来：亮底 + 上/左亮棱 + 下/右暗棱（凸起感）
            gfx.fill(x, y, x + w, y + WORK_BTN_H, hovered ? 0xFF767676 : 0xFF666666);
            gfx.fill(x, y, x + w, y + 1, 0xFF9E9E9E);
            gfx.fill(x, y, x + 1, y + WORK_BTN_H, 0xFF9E9E9E);
            gfx.fill(x, y + WORK_BTN_H - 1, x + w, y + WORK_BTN_H, 0xFF3A3A3A);
            gfx.fill(x + w - 1, y, x + w, y + WORK_BTN_H, 0xFF3A3A3A);
        }
    }

    /** 工作页文字：页名、职业行（✔ 选中）、按钮文字、底部提示。 */
    private void renderWorkPageText(GuiGraphics gfx) {
        Resident resident = this.menu.getResident();
        if (resident != null) {
            String name = resident.getName().getString();
            gfx.drawString(this.font, name,
                    this.imageWidth - 6 - this.font.width(name), 4, 0x303030, false);
        }

        // ---- 职业行文字（滚动窗口，与背景层同一偏移） ----
        var professions = com.bmf.bmfstown.entity.profile.ProfessionType.values();
        var current = currentProfession();
        for (int v = 0; v < VISIBLE_PROF_ROWS; v++) {
            int i = this.workScroll + v;
            if (i >= professions.length) {
                break;
            }
            int rowY = workRowTop() + v * ROW_H + 3;
            boolean cur = current != null && current.ordinal() == i;
            String label = I18n.get(professions[i].displayName());
            int color = cur ? 0xFFFFFF : 0xE0E0E0;
            gfx.drawString(this.font, (cur ? "✔ " : "  ") + label, 8, rowY, color, false);
        }

        // ---- 按钮文字（水平居中）——仅当前职业有工作控件时渲染 ----
        if (!workControlsVisible()) {
            return;
        }
        int workMode = currentWorkMode();
        int bw = this.imageWidth - 10;
        int followY = workButtonsTop();
        int guardY = followY + WORK_BTN_H + WORK_BTN_GAP;
        String followLabel = I18n.get(workMode == 1
                ? "gui.bmfstown.resident.work.follow_btn_on"
                : "gui.bmfstown.resident.work.follow_btn_off");
        String guardLabel = I18n.get(workMode == 2
                ? "gui.bmfstown.resident.work.guard_btn_on"
                : "gui.bmfstown.resident.work.guard_btn");
        // 开启态文字用亮绿（配合凹陷底），关闭态普通亮灰——一眼分清开关
        drawCenteredInPanel(gfx, followLabel, 5, bw, followY + 3, workMode == 1 ? 0xA8FFA8 : 0xE0E0E0);
        drawCenteredInPanel(gfx, guardLabel, 5, bw, guardY + 3, workMode == 2 ? 0xA8FFA8 : 0xE0E0E0);

        // ---- 经验条（原版风格：灰底空槽 + 原版经验绿的进度，黑色外框）----
        // 文字只报段位与进度（如"老练 · 1470/1500"）——职业名在上方列表，
        // 技能名对战士这种"一职业一技能"的结构是冗余信息，不显示。
        if (resident != null) {
            var skill = current.mainSkill();
            int xp = resident.getProfile().skillXp(skill);
            int tier = com.bmf.bmfstown.entity.profile.SkillTiers.tierOf(xp);
            String tierName = I18n.get("gui.bmfstown.resident.tier." + tier);
            String lv = I18n.get("gui.bmfstown.resident.stat.level",
                    com.bmf.bmfstown.entity.profile.SkillTiers.levelOf(xp));
            String suffix = tier >= com.bmf.bmfstown.entity.profile.SkillTiers.MAX_TIER
                    ? I18n.get("gui.bmfstown.resident.work.xp_max_suffix")
                    : I18n.get("gui.bmfstown.resident.work.xp_suffix", xp,
                            com.bmf.bmfstown.entity.profile.SkillTiers.xpToNext(xp) + xp);
            // 分段染色：Lv 与段位名随稀有度变色（传奇为动态彩虹），后缀数字保持深灰
            int textY = ResidentMenu.STATS_Y + 1;
            int cx = 8;
            cx = drawSegment(gfx, cx, textY, lv, tierColor(tier));
            cx = drawSegment(gfx, cx, textY, " ", tierColor(tier));
            cx = drawSegment(gfx, cx, textY, tierName, tierColor(tier));
            drawSegment(gfx, cx, textY, " " + suffix, 0x303030);

            int barX = 8;
            int barY = textY + 11;
            int barW = this.imageWidth - 16;
            int barH = 5;
            gfx.fill(barX - 1, barY - 1, barX + barW + 1, barY + barH + 1, 0xFF000000);
            gfx.fill(barX, barY, barX + barW, barY + barH, 0xFF3D3D3D);
            // 满级特例：整条填满绿色，避免"空条被误读成 0 经验"
            // （progress() 对最高段位返回 0，因为已经没有"下一段"可比较）
            int filled;
            if (tier >= com.bmf.bmfstown.entity.profile.SkillTiers.MAX_TIER) {
                filled = barW;
            } else {
                filled = (int) Math.round(barW * com.bmf.bmfstown.entity.profile.SkillTiers.progress(xp));
            }
            if (filled > 0) {
                gfx.fill(barX, barY, barX + filled, barY + barH, 0xFF7EFC20);
            }

            // ---- 效率行：职业效率随等级线性变化（SkillTiers A 方案）。
            //      抽象参数的具体效果由各职业自行定义（战士=伤害倍率），
            //      这里只做展示：让玩家直观看到"练级有用"。
            double eff = com.bmf.bmfstown.entity.profile.SkillTiers.efficiencyOf(xp);
            int effPct = (int) Math.round(eff * 100);
            int effColor = eff >= 1.0 ? 0x2E7D2E : 0xB02020;   // 回本点 Lv.5=100%
            int effY = textY + 20;
            int ex = 8;
            ex = drawSegment(gfx, ex, effY, I18n.get("gui.bmfstown.resident.work.efficiency_label"), 0x555555);
            ex = drawSegment(gfx, ex, effY, " ", 0x555555);
            drawSegment(gfx, ex, effY, effPct + "%", effColor);

            // ---- perk 节点区（占位文字） ----
            renderPerkText(gfx);
        }
    }

    /** 在面板横向范围内居中画一行字。 */
    private void drawCenteredInPanel(GuiGraphics gfx, String text, int x, int w, int y, int color) {
        gfx.drawString(this.font, text, x + (w - this.font.width(text)) / 2, y, color, false);
    }

    /**
     * perk 节点区文字（占位阶段）。
     *
     * <p>行结构：3 个节点行，展开的节点下插分支行——与背景层、
     * 点击层的行计数逻辑必须严格一致（都用同一套 row 递增）。
     */
    private void renderPerkText(GuiGraphics gfx) {
        int tier = mainSkillTier();
        int row = 0;
        for (int n = 0; n < PERK_NODE_IDS.length; n++) {
            boolean unlocked = tier >= PERK_NODE_TIERS[n];
            int textY = perkAreaTop() + row * ROW_H + 3;
            row++;
            int selected = unlocked ? perkSelectedBranch(n) : -1;
            String status;
            if (!unlocked) {
                status = I18n.get("gui.bmfstown.resident.perk.locked", PERK_NODE_TIERS[n] + 1);
            } else if (selected >= 0) {
                status = I18n.get("gui.bmfstown.resident.perk.chosen",
                        I18n.get("gui.bmfstown.resident.perk.bname." + n + "." + selected));
            } else if (this.perkExpandedNode == n) {
                status = "▾";
            } else {
                status = I18n.get("gui.bmfstown.resident.perk.ready");
            }
            int color = unlocked ? 0xFFFFFF : 0x808080;
            gfx.drawString(this.font, "◆ " + I18n.get("gui.bmfstown.resident.perk.node." + n)
                    + "  " + status, 8, textY, color, false);
            if (this.perkExpandedNode == n && unlocked) {
                for (int b = 0; b < PERK_BRANCH_IDS[n].length; b++) {
                    int branchY = perkAreaTop() + row * ROW_H + 3;
                    row++;
                    String name = I18n.get("gui.bmfstown.resident.perk.bname." + n + "." + b);
                    // 描述文本带 %s 占位，数值从配置现读——改 toml 文案跟着变
                    String desc = I18n.get("gui.bmfstown.resident.perk.bdesc." + n + "." + b,
                            perkDescArgs(n, b));
                    String prefix = selected == b ? "✔ " : "   ";
                    // 选中分支亮白，未选灰白；锁定描述不额外变色（父节点已置灰）
                    gfx.drawString(this.font, prefix + name + " · " + desc,
                            8, branchY, selected == b ? 0xFFFFFF : 0xC8C8C8, false);
                }
            }
        }
    }

    /**
     * perk 描述的动态数值参数（与语言文件里 %s 占位一一对应）。
     *
     * <p><b>数据源是客户端本地的 common 配置</b>：单人游戏与服务器同文件，
     * 永远一致；专用服务器上如果服主改了 toml 而客户端没同步文件，
     * 文案数值可能与服务端实际行为有出入——这是 common 配置不下发的
     * 已知限制，当前按"数值以服务端为准，文案尽力而为"处理。
     */
    private Object[] perkDescArgs(int node, int branch) {
        return switch (node) {
            case 0 -> branch == 0
                    ? new Object[]{Math.round((com.bmf.bmfstown.config.Config.PERK_REGEN_MULTIPLIER.get()
                            - 1.0D) * 100)}
                    : new Object[]{com.bmf.bmfstown.config.Config.PERK_STRONG_BONUS_PERCENT.get()};
            case 1 -> branch == 0
                    ? new Object[]{fmtNum(com.bmf.bmfstown.config.Config.PERK_BULWARK_RADIUS.get()),
                            com.bmf.bmfstown.config.Config.PERK_BULWARK_DURATION_SECONDS.get()}
                    : new Object[]{fmtNum(com.bmf.bmfstown.config.Config.PERK_HUNT_RADIUS.get()),
                            com.bmf.bmfstown.config.Config.PERK_HUNT_DURATION_SECONDS.get()};
            default -> branch == 0
                    ? new Object[]{fmtNum(com.bmf.bmfstown.config.Config.PERK_TAUNT_RADIUS.get()),
                            com.bmf.bmfstown.config.Config.PERK_TAUNT_DURATION_SECONDS.get()}
                    : new Object[]{com.bmf.bmfstown.config.Config.PERK_EXECUTE_CHANCE_PERCENT.get(),
                            fmtNum(com.bmf.bmfstown.config.Config.PERK_EXECUTE_HEALTH_THRESHOLD.get())};
        };
    }

    /** 整数值不带小数点显示（10.0 → "10"，7.5 → "7.5"）。 */
    private static String fmtNum(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    // ==========================================================
    //  归属页：数据与交互
    // ==========================================================

    /**
     * 列表一行的展示数据。
     *
     * <p>城镇分节和住宅分节共用这个结构：{@code id} 为 null 的那行是
     * "不分配"（解绑入口），其余行的 {@code id} 是要绑定的目标。
     *
     * @param label  显示的文本
     * @param id     目标的 UUID；null = "不分配"
     * @param button 点击后要发的菜单按钮号
     * @param full   true = 目标已满（置灰、不可点）
     */
    private record ListRow(String label, UUID id, int button, boolean full) {

        boolean isCurrent(UUID currentId) {
            // "不分配"行的 id 是 null：只在镇民本来就未绑定时算"当前"
            if (this.id == null) {
                return currentId == null;
            }
            return this.id.equals(currentId);
        }
    }

    /** 当前归属的城镇（镇民档案里的 townId；镇民不在时 null）。 */
    private UUID currentTownId() {
        Resident r = this.menu.getResident();
        return r == null ? null : r.getProfile().townId();
    }

    /** 当前绑定的住宅区（镇民档案里的 housingZoneId；镇民不在时 null）。 */
    private UUID currentHousingId() {
        Resident r = this.menu.getResident();
        return r == null ? null : r.getProfile().housingZoneId();
    }

    /**
     * 组装城镇分节的列表：不分配 + 本维度城镇。
     *
     * <p>维度取镇民所在维度（镇民不在时退回玩家维度）——跨维度的城镇
     * 锁不住镇民，列表里出现只会误导。
     */
    private List<ListRow> townRows() {
        java.util.List<ListRow> rows = new java.util.ArrayList<>();
        rows.add(new ListRow(I18n.get("gui.bmfstown.resident.town.unassigned"),
                null, ResidentMenu.BTN_UNASSIGN, false));
        List<TownHandbookClient.TownView> towns = TownHandbookClient.townsInDimension(residentDimension());
        for (int i = 0; i < towns.size(); i++) {
            TownHandbookClient.TownView t = towns.get(i);
            rows.add(new ListRow(
                    I18n.get("gui.bmfstown.resident.town.row", t.name(), t.owner()),
                    t.id(), ResidentMenu.BTN_ASSIGN_BASE + i, false));
        }
        return rows;
    }

    /**
     * 组装住宅分节的列表：不分配 + 当前城镇的所有住宅区。
     *
     * <p>只列"当前归属城镇"的住宅——住宅区隶属于城镇，跨城镇分配没有意义。
     * 没绑城镇时列表里只有"不分配"一行，并给一句提示（见
     * {@link #renderTownPageText}）。
     *
     * <p>满员的住宅项 {@code full = true}：文字置灰、点击无效，
     * 避免"点了才知道满了"的挫败感。
     */
    private List<ListRow> housingRows() {
        java.util.List<ListRow> rows = new java.util.ArrayList<>();
        rows.add(new ListRow(I18n.get("gui.bmfstown.resident.housing.unassigned"),
                null, ResidentMenu.BTN_HOUSING_UNASSIGN, false));

        UUID townId = currentTownId();
        if (townId == null) {
            return rows; // 没绑城镇：只有"不分配"
        }
        List<TownHandbookClient.ZoneView> zones = new java.util.ArrayList<>();
        for (TownHandbookClient.TownView t : TownHandbookClient.townsInDimension(residentDimension())) {
            if (!t.id().equals(townId)) {
                continue;
            }
            for (TownHandbookClient.ZoneView z : t.zones()) {
                if (z.kind() == com.bmf.bmfstown.town.TownWandMode.HOUSING.ordinal()) {
                    zones.add(z);
                }
            }
            break;
        }
        // 手册同步的 Z 行带"床数|入住数"（TownSaveData.toBlob 服务端实算），
        // 这里直接显示占用、满员置灰；服务端分配时仍会再校验一次（双保险）。
        for (int i = 0; i < zones.size(); i++) {
            TownHandbookClient.ZoneView z = zones.get(i);
            boolean full = z.beds() > 0 && z.occupants() >= z.beds();
            String label = I18n.get("gui.bmfstown.resident.housing.row",
                    z.name(), z.occupants(), z.beds());
            rows.add(new ListRow(label, z.id(), ResidentMenu.BTN_HOUSING_BASE + i, full));
        }
        return rows;
    }

    /** 当前分节对应的行列表。 */
    private List<ListRow> currentRows() {
        return this.section == SECTION_HOUSING ? housingRows() : townRows();
    }

    /** 当前分节的"已选中"目标 id。 */
    private UUID currentSectionTargetId() {
        return this.section == SECTION_HOUSING ? currentHousingId() : currentTownId();
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
            "gui.bmfstown.resident.tab.info",
            "gui.bmfstown.resident.tab.town",
            "gui.bmfstown.resident.tab.work",
            "gui.bmfstown.resident.tab.hobby",
            "gui.bmfstown.resident.tab.thoughts"};
    /** 页签文字（每次绘制时从 lang 取，语言切换即时生效）。 */
    private static String[] tabNames() {
        return new String[]{I18n.get(TAB_KEYS[0]), I18n.get(TAB_KEYS[1]),
                I18n.get(TAB_KEYS[2]), I18n.get(TAB_KEYS[3]), I18n.get(TAB_KEYS[4])};
    }
    /** 页签宽度（px），与 TAB_KEYS 一一对应。五个页签总宽不得超面板 176px。 */
    private static final int[] TAB_WIDTHS = {34, 30, 30, 30, 30};

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
        // ---- 工作页点击：职业行 + 两个工作模式按钮 ----
        if (this.page == PAGE_WORK && button == 0) {
            int listX = this.leftPos + 5;
            int listW = this.imageWidth - 10;

            // 职业行（滚动窗口：可见行 v → 实际下标 workScroll + v）
            int rowTop = this.topPos + workRowTop();
            int rel = (int) (mouseY - rowTop);
            if (mouseX >= listX && mouseX < listX + listW
                    && rel >= 0 && rel < VISIBLE_PROF_ROWS * ROW_H) {
                int index = this.workScroll + rel / ROW_H;
                if (index < PROFESSION_ROW_COUNT) {
                    clickMenu(ResidentMenu.BTN_PROFESSION_BASE + index);
                }
                return true;
            }

            // 跟随按钮（切换开/关，服务端落账并互斥清除站岗）
            // 无业的职业下不渲染按钮，点击区也一并失效——
            // 这块区域按职业动态分配，不属于战士专属。
            int followY = this.topPos + workButtonsTop();
            int guardY = followY + WORK_BTN_H + WORK_BTN_GAP;
            if (workControlsVisible() && mouseX >= listX && mouseX < listX + listW) {
                if (mouseY >= followY && mouseY < followY + WORK_BTN_H) {
                    // 走自定义包（与站岗同链路），菜单按钮协议留作兜底
                    Resident r = this.menu.getResident();
                    if (r != null) {
                        ModNetworking.sendToServer(new ModNetworking.WorkFollowPayload(r.getId()));
                    }
                    return true;
                }
                // 站岗按钮：关界面 → 客户端进入红色预览，右键确认/Shift 取消
                if (mouseY >= guardY && mouseY < guardY + WORK_BTN_H) {
                    Resident r = this.menu.getResident();
                    if (r != null && r.isWarrior()) {
                        clickMenu(ResidentMenu.BTN_GUARD_POST);
                        if (this.minecraft != null) {
                            this.minecraft.setScreen(null);
                        }
                        GuardPostClient.startPick(r.getId());
                    }
                    return true; // 非战士点也吞掉（服务端会给提示）
                }
            }

            // ---- perk 节点区点击（占位：展开/收起节点；分支选择等分支表定稿后接入） ----
            if (mouseX >= this.leftPos + 4 && mouseX < this.leftPos + this.imageWidth - 4
                    && mouseY >= this.topPos + perkAreaTop() && mouseY < this.topPos + perkAreaBottom()) {
                int perkTier = mainSkillTier();
                int relPerk = (int) (mouseY - (this.topPos + perkAreaTop()));
                int perkRow = 0;
                for (int n = 0; n < PERK_NODE_IDS.length; n++) {
                    int nodeRow = perkRow;
                    perkRow++;
                    boolean expandedHere = n == this.perkExpandedNode && perkTier >= PERK_NODE_TIERS[n];
                    if (expandedHere) {
                        // 分支行：点中就发选档包（服务端复核段位后落档，免费改选）
                        if (relPerk >= (nodeRow + 1) * ROW_H
                                && relPerk < (nodeRow + 1 + PERK_BRANCH_IDS[n].length) * ROW_H) {
                            int b = (relPerk - (nodeRow + 1) * ROW_H) / ROW_H;
                            Resident r = this.menu.getResident();
                            if (r != null) {
                                ModNetworking.sendToServer(new ModNetworking.PerkChoosePayload(
                                        r.getId(), PERK_NODE_IDS[n], PERK_BRANCH_IDS[n][b]));
                            }
                            return true;
                        }
                        perkRow += PERK_BRANCH_IDS[n].length;
                    }
                    if (relPerk >= nodeRow * ROW_H && relPerk < (nodeRow + 1) * ROW_H) {
                        // 已解锁节点：展开/收起切换；锁定的吞掉点击给死反馈
                        if (perkTier >= PERK_NODE_TIERS[n]) {
                            this.perkExpandedNode = this.perkExpandedNode == n ? -1 : n;
                        }
                        return true;
                    }
                }
                return true;
            }

            // 上板其余区域是死区（下面躺着被隐藏的格子）
            if (mouseX >= this.leftPos && mouseX < this.leftPos + this.imageWidth
                    && mouseY >= this.topPos && mouseY < this.topPos + ResidentMenu.PLAYER_INV_Y - 4) {
                return true;
            }
        }

        if (this.page == PAGE_TOWN && button == 0) {
            // ---- 分节标签点击（城镇 / 住宅）----
            if (clickSectionTab(mouseX, mouseY)) {
                return true;
            }
            // ---- 列表行点击 ----
            int rowTop = this.topPos + rowsTop();
            int rowBottom = this.topPos + listBottom();
            boolean inList = mouseX >= this.leftPos + 4 && mouseX < this.leftPos + this.imageWidth - 4
                    && mouseY >= rowTop && mouseY < rowBottom;
            if (inList) {
                int visible = (int) ((mouseY - rowTop) / ROW_H);
                int index = this.townScroll + visible;
                List<ListRow> rows = currentRows();
                if (index < rows.size()) {
                    ListRow row = rows.get(index);
                    if (!row.full()) { // 满员的住宅项直接吞掉点击
                        this.clickMenu(row.button());
                    }
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
        // 喜好页滚动条：按下滑块或轨道即开始拖拽（点轨道任意处直接跳过去）
        if (this.page == PAGE_THOUGHTS && button == 0 && thoughtMaxScroll() > 0) {
            int trackX = scrollbarX();
            int trackY = this.topPos + thoughtListTop() + 1;
            int trackH = thoughtListBottom() - thoughtListTop() - 2;
            if (mouseX >= trackX && mouseX < trackX + SCROLLBAR_WIDTH
                    && mouseY >= trackY && mouseY < trackY + trackH) {
                int max = thoughtMaxScroll();
                int thumbH = Math.max(8, trackH * THOUGHTS_VISIBLE
                        / (THOUGHTS_VISIBLE + max));
                int thumbY = trackY + (trackH - thumbH) * this.thoughtScroll / max;
                // 按在滑块上：记住抓取点偏移；按在轨道空白处：滑块顶直接吸到鼠标
                this.thoughtDragGrabOffset = Mth.clamp((int) mouseY - thumbY, 0, thumbH);
                this.thoughtScrollbarDragging = true;
                updateHobbyScrollFromMouse((int) mouseY, trackY, trackH, thumbH, max);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 按鼠标 Y 反推滚动位置（拖拽滚动条时用）。 */
    private void updateHobbyScrollFromMouse(int mouseY, int trackY, int trackH, int thumbH, int max) {
        int target = (mouseY - this.thoughtDragGrabOffset - trackY) * max / (trackH - thumbH);
        this.thoughtScroll = Mth.clamp(target, 0, max);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.thoughtScrollbarDragging && this.page == PAGE_THOUGHTS) {
            int max = thoughtMaxScroll();
            if (max <= 0) {
                this.thoughtScrollbarDragging = false;
            } else {
                int trackY = this.topPos + thoughtListTop() + 1;
                int trackH = thoughtListBottom() - thoughtListTop() - 2;
                int thumbH = Math.max(8, trackH * THOUGHTS_VISIBLE
                        / (THOUGHTS_VISIBLE + max));
                updateHobbyScrollFromMouse((int) mouseY, trackY, trackH, thumbH, max);
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            this.thoughtScrollbarDragging = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
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
            int max = Math.max(0, currentRows().size() - MAX_VISIBLE);
            this.townScroll = Mth.clamp(this.townScroll - (int) Math.signum(scrollY), 0, max);
            return true;
        }
        if (this.page == PAGE_WORK) {
            // 职业列表滚轮：可见 3 行，翻到列表尾为止
            int max = Math.max(0, PROFESSION_ROW_COUNT - VISIBLE_PROF_ROWS);
            this.workScroll = Mth.clamp(this.workScroll - (int) Math.signum(scrollY), 0, max);
            return true;
        }
        if (this.page == PAGE_THOUGHTS) {
            // 想法日志滚轮：新想法在上，向下滚 = 看更早的（滑块同步往下走）
            int max = thoughtMaxScroll();
            this.thoughtScroll = Mth.clamp(this.thoughtScroll - (int) Math.signum(scrollY), 0, max);
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
