package com.bmf.bmfstown.client;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.entity.domain.ResidentSnapshot;
import com.bmf.bmfstown.entity.domain.Status;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
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
    private static final int PAGE_KNOWLEDGE = ResidentMenu.PAGE_KNOWLEDGE;

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

    /**
     * 关闭：若本面板从城镇手册打开（用户定稿），回跳手册的城镇页
     * （恢复到关闭前的城镇页码），而不是退到游戏画面。
     */
    @Override
    public void onClose() {
        int mode = REOPEN_TARGET;
        int viewer = GATTACA_VIEWER_ID;
        int practiceViewer = PRACTICE_VIEWER_ID;
        REOPEN_TARGET = 0;
        GATTACA_VIEWER_ID = -1;
        PRACTICE_VIEWER_ID = -1;
        super.onClose();
        if (this.minecraft == null) {
            return;
        }
        switch (mode) {
            case 1 -> this.minecraft.setScreen(new TownHandbookScreen());
            case 2 -> this.minecraft.setScreen(GattacaSelectScreen.open(viewer));
            case 3 -> ModNetworking.sendToServer(
                    new ModNetworking.OpenResidentFromHandbookPayload(
                            viewer >= 0 ? viewer : practiceViewer, 0));
            default -> { }
        }
    }

    // ==========================================================
    //  页签 / 按钮装配
    // ==========================================================

    /** 打开界面时请求城镇数据（归属页的列表来源）。 */
    @Override
    protected void init() {
        // ---- 按页决定面板高度（必须在 super.init() 之前：leftPos/topPos 由它算） ----
        // 按页决定面板高度（必须在 super.init() 之前：leftPos/topPos 由它算）。
        // 归属页收掉下半段只留列表；工作页与资料页同高——底部显示玩家
        // 背包（拿取学者书稿等，v0.11），perk 区改固定高滚动菜单不与背包抢地盘。
        this.imageWidth = ResidentMenu.GUI_WIDTH;
        this.imageHeight = switch (this.page) {
            case PAGE_TOWN -> TOWN_GUI_HEIGHT;
            case PAGE_WORK -> WORK_GUI_HEIGHT;
            case PAGE_HOBBY -> HOBBY_GUI_HEIGHT;
            case PAGE_THOUGHTS -> THOUGHTS_GUI_HEIGHT;
            case PAGE_KNOWLEDGE -> KNOWLEDGE_GUI_HEIGHT;
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
    private static final int KNOWLEDGE_GUI_HEIGHT = ResidentMenu.PANEL_Y + 9 * ROW_H + 6;

    /** 想法页当前滚动位置（0 = 最新的在最上面）。 */
    private int thoughtScroll = 0;

    /**
     * 工作页面板高：与资料页同高（玩家背包/快捷栏都在固定位置显示）。
     * perk 节点区改固定高滚动菜单（{@link #PERK_VISIBLE_ROWS} 行可见，
     * 分支再多也只滚动不撑高），塞在效率行与玩家背包之间的空档里。
     */
    private static final int WORK_GUI_HEIGHT = ResidentMenu.GUI_HEIGHT;

    private void switchPage(int newPage) {
        this.page = newPage;
        this.townScroll = 0; // 每次进归属页都回到列表头，别让玩家一进来就对着列表中段发呆
        this.workScroll = 0; // 工作页职业列表同理归零
        this.thoughtScroll = 0; // 想法页回到最新一条
        this.thoughtScrollbarDragging = false; // 切页时结束拖拽，别把状态带到别的页
        this.perkExpandedNode = -1; // perk 节点全部收起
        this.perkScroll = 0; // perk 滚动回树顶（换职业树也靠这里兜底）
        this.perkScrollbarDragging = false; // 切页结束 perk 滚动条拖拽

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
        } else if (this.page == PAGE_KNOWLEDGE) {
            renderKnowledgePageBg(gfx);
        } else {
            renderInfoPageBg(gfx);
        }

        // ---- 下半部分：原版物品栏贴图（资料页 + 工作页——其余页背包已收起） ----
        //
        // blit 语义：把贴图上 (u,v) 处的块画到 (x,y)。背包块从 (7,83) 裁起，
        // 块内第一个格子凹槽在贴图 x=8 —— 目标 x 取 PANEL_X-1=7，
        // 凹槽正好落在我们的格子上；y 直接取 PLAYER_INV_Y-1（块顶对格子顶，
        // 格子顶在块内 1px 处）。之前按"整张贴图平移"的老模型减了 83，
        // 整块背包被抬高到状态文字区——就是那次截图里"贴图跑到上面"的错位。
        // 工作页仅学者显示背包（卡点书转交需要，用户 11-06 定稿）；
        // 工人/炼金等工作页收起底座——选产物/选药水弹层落在这一带，底座不撤必重叠
        boolean invVisible = this.page == PAGE_INFO
                || (this.page == PAGE_WORK
                        && this.menu.getResident() != null
                        && this.menu.getResident().getProfession()
                                == com.bmf.bmfstown.entity.profile.ProfessionType.SCHOLAR);
        if (invVisible) {
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
        // 工作页同样用列表顶替了镇民格子，一并隐藏——但书稿槽是工作页自己的
        // 内容，必须放行（v0.11：真槽位，原版渲染物品）
        if (this.page == PAGE_WORK && slot.y < ResidentMenu.PLAYER_INV_Y
                && !ResidentMenu.isWipSlot(slot)) {
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
            renderWorkPageText(gfx, mouseX, mouseY);
        } else if (this.page == PAGE_HOBBY) {
            renderHobbyPageText(gfx, mouseX, mouseY);
        } else if (this.page == PAGE_THOUGHTS) {
            renderThoughtsPageText(gfx);
        } else if (this.page == PAGE_KNOWLEDGE) {
            renderKnowledgePageText(gfx, mouseX, mouseY);
        } else {
            renderInfoPageText(gfx, mouseX, mouseY);
        }
        drawBottomLabels(gfx, this.page == PAGE_INFO);
    }

    /** 资料页的状态文字（名字 + 七行状态）。浅灰底上用深色字。 */
    private void renderInfoPageText(GuiGraphics gfx, int mouseX, int mouseY) {
        Resident resident = this.menu.getResident();
        int textY = ResidentMenu.STATS_Y;

        // ---- 第 0 行：名字。和数据显示在一起，代替原来的面板标题。 ----
        gfx.drawString(this.font, resident.getName(), TEXT_PAD, textY, 0x303030, false);

        // ---- 名字右边的"重命名"按钮（v0.8.1 用户需求） ----
        // renderLabels 的画笔已平移到面板原点：绘制用相对坐标，
        // hover/点击判定用绝对坐标（renderLabels 收到绝对鼠标坐标，1.21.1 实测口径）
        int nameW = this.font.width(resident.getName());
        int btnX = TEXT_PAD + nameW + 8;
        int btnW = this.font.width(RENAME_BTN_LABEL) + 8;
        int btnH = 11;
        this.renameBtnRel = new int[]{btnX, textY - 1, btnW, btnH};
        boolean btnHover = mouseX >= this.leftPos + btnX && mouseX < this.leftPos + btnX + btnW
                && mouseY >= this.topPos + textY - 1 && mouseY < this.topPos + textY - 1 + btnH;
        int btnY = textY - 1;
        gfx.fill(btnX, btnY, btnX + btnW, btnY + btnH, btnHover ? 0xFF707070 : 0xFF5A5A5A);
        gfx.fill(btnX, btnY, btnX + 1, btnY + btnH, 0xFF909090);
        gfx.fill(btnX, btnY, btnX + btnW, btnY + 1, 0xFF909090);
        gfx.fill(btnX, btnY + btnH - 1, btnX + btnW, btnY + btnH, 0xFF2E2E2E);
        gfx.fill(btnX + btnW - 1, btnY, btnX + btnW, btnY + btnH, 0xFF2E2E2E);
        gfx.drawString(this.font, RENAME_BTN_LABEL, btnX + 4, textY, 0xFFE8E8E8, false);

        // ---- "传送"/"伪装"按钮（重命名右侧，用户定稿）----
        // 只在手册入口（1=传送）和 GATTACA 查看入口（2=伪装）出现；
        // 右键脸贴脸打开时没有意义，不渲染（用户实测反馈）
        String tpLabel = REOPEN_TARGET == 2 ? I18n.get("gui.bmfstown.resident.disguise_btn")
                : REOPEN_TARGET == 4 ? I18n.get("gui.bmfstown.resident.practice_btn")
                : REOPEN_TARGET == 1 ? I18n.get("gui.bmfstown.resident.tp_btn")
                : null;
        if (tpLabel == null) {
            this.teleportBtnRel = null;
        } else {
        int tpX = btnX + btnW + 4;
        int tpW = this.font.width(tpLabel) + 8;
        this.teleportBtnRel = new int[]{tpX, btnY, tpW, btnH};
        boolean tpHover = mouseX >= this.leftPos + tpX && mouseX < this.leftPos + tpX + tpW
                && mouseY >= this.topPos + btnY && mouseY < this.topPos + btnY + btnH;
        gfx.fill(tpX, btnY, tpX + tpW, btnY + btnH, tpHover ? 0xFF707070 : 0xFF5A5A5A);
        gfx.fill(tpX, btnY, tpX + 1, btnY + btnH, 0xFF909090);
        gfx.fill(tpX, btnY, tpX + tpW, btnY + 1, 0xFF909090);
        gfx.fill(tpX, btnY + btnH - 1, tpX + tpW, btnY + btnH, 0xFF2E2E2E);
        gfx.fill(tpX + tpW - 1, btnY, tpX + tpW, btnY + btnH, 0xFF2E2E2E);
        gfx.drawString(this.font, tpLabel, tpX + 4, textY, 0xFFE8E8E8, false);
        } // 传送/伪装按钮三态渲染结束

        textY += LINE;
        //
        // 数值通道（v0.9.1 实测纠正）：菜单数据槽（ContainerData）实测客户端
        // 收不到增量更新——工作模式按钮卡死、工人状态行不刷新是同一坑
        // （项目纪律两次踩坑），心情"永远 50"是第三次。改走已验证可靠通道：
        //   血量 = 原版 LivingEntity 客户端同步（vanilla 自带）；
        //   饥饿/心情 = 六域快照（DATA_ARCHIVE，服务端每次变更 syncClient() 推送，
        //   applyRaw/setHunger 的每个写入点都带）。
        int healthHalf = (int) (resident.getHealth() * 2);
        int hunger = resident.getArchive().hunger();
        int mood = resident.getArchive().mood();

        // 1. 血量（数据槽存的是半颗心数，÷2 还原）。浅灰底配色：原版聊天色的深变体
        drawStat(gfx, textY, I18n.get("gui.bmfstown.resident.stat.health"),
                healthHalf / 2 + " / " + (int) resident.getMaxHealth(), 0xB02020);
        // 2. 饥饿（20 点 = 10 个鸡腿，显示成 "当前/20"）
        drawStat(gfx, textY + LINE, I18n.get("gui.bmfstown.resident.stat.hunger"),
                hunger + " / " + Status.MAX_HUNGER, 0xA87300);
        // 3. 心情（带表情符号，档位凭数值现算，阈值只有一份）
        Status.MoodTier moodTier = Status.moodTierOf(mood);
        drawStat(gfx, textY + LINE * 2, I18n.get("gui.bmfstown.resident.stat.mood"),
                I18n.get("gui.bmfstown.resident.stat.mood_value",
                        I18n.get(moodTier.displayName()), moodTier.bubble(), mood), 0x2E7D2E);

        // 4. 专长 + 主技能段位（与工作页经验条同一数据源：技能经验体系）。
        //    旧的全局 xp/level 已休眠，这里若还读 p.title()/p.level()，
        //    就会出现"工作页显示老练、资料页显示新手"的两套口径打架。
        //    （v0.7：原第 4 行好感度随好感度系统删除，后续行上移一位。）
        ResidentSnapshot p = resident.getArchive();
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

        // ---- 特性行（用户定稿：特性属"资料"不在"喜好"；GATTACA 伪装按钮在此）----
        String trait = com.bmf.bmfstown.client.ClientMoodState.traitOf(resident.getUUID());
        String mimic = com.bmf.bmfstown.client.ClientMoodState.mimicOf(resident.getUUID());
        String traitValue;
        boolean mimicking = !mimic.isEmpty();
        if (mimicking) {
            String[] parts = mimic.split("\\|", 2);
            traitValue = I18n.get("trait.bmfstown." + parts[1]) + "（GATTACA）";
        } else {
            traitValue = trait.isEmpty() ? I18n.get("gui.bmfstown.resident.stat.value_none")
                    : I18n.get("trait.bmfstown." + trait);
        }
        drawStat(gfx, textY + LINE * 5, I18n.get("gui.bmfstown.resident.stat.trait"),
                traitValue, 0xB06A10);

        // GATTACA 本尊（非查看模式）：特性行右侧"伪装"按钮；模仿中灰字"正在模仿 xxx"
        if (trait.equals("gattaca") && GATTACA_VIEWER_ID < 0) {
            int gBtnH = 11;
            if (mimicking) {
                String label = I18n.get("gui.bmfstown.resident.gattaca_mimicking",
                        mimic.split("\\|", 2)[0]);
                this.gattacaBtnRel = null; // 模仿中不可用
                gfx.drawString(this.font, label, this.imageWidth - 6
                        - this.font.width(label), textY + LINE * 5, 0x8A8A8A, false);
            } else {
                String label = I18n.get("gui.bmfstown.resident.gattaca_btn");
                int gW = this.font.width(label) + 8;
                int gX = this.imageWidth - 6 - gW;
                int gY = textY + LINE * 5 - 1;
                this.gattacaBtnRel = new int[]{gX, gY, gW, gBtnH};
                boolean gHov = mouseX >= this.leftPos + gX && mouseX < this.leftPos + gX + gW
                        && mouseY >= this.topPos + gY && mouseY < this.topPos + gY + gBtnH;
                gfx.fill(gX, gY, gX + gW, gY + gBtnH, gHov ? 0xFF707070 : 0xFF5A5A5A);
                gfx.fill(gX, gY, gX + 1, gY + gBtnH, 0xFF909090);
                gfx.fill(gX, gY, gX + gW, gY + 1, 0xFF909090);
                gfx.fill(gX, gY + gBtnH - 1, gX + gW, gY + gBtnH, 0xFF2E2E2E);
                gfx.fill(gX + gW - 1, gY, gX + gW, gY + gBtnH, 0xFF2E2E2E);
                gfx.drawString(this.font, label, gX + 4, textY + LINE * 5, 0xFFE8E8E8, false);
            }
        } else {
            this.gattacaBtnRel = null;
        }
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
    private void renderHobbyPageText(GuiGraphics gfx, int mouseX, int mouseY) {
        Resident resident = this.menu.getResident();
        if (resident == null) {
            return;
        }
        ResidentSnapshot p = resident.getArchive();
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
            // 海象：显示同步瞬间随机生成的"咕咕咕"展示版（完全随机无法反推原文；
            // 真实信息在服务端）。非海象照常翻译。
            java.util.List<String> goo = com.bmf.bmfstown.client.ClientMoodState
                    .walrusLinesOf(resident.getUUID());
            String line = goo != null && i < goo.size() ? goo.get(i)
                    : renderThoughtLine(t);
            // 性格滤镜标记（乐观"+" / 悲观"−"）已按用户 2026-10-25 决定不再显示；
            // Thought.marker 字段保留（同步协议与存档兼容），将来要恢复只改这里。
            // 深底上用原版聊天的亮绿/亮红/灰，暗色调在 0xFF373737 上看不清；
            // 中性事件（如金护甲）用灰色，不误导玩家往好坏两边猜；
            // 记忆专用色（v0.9.3）：坏记忆淡紫 / 好记忆淡青，与普通好坏分开
            int color = switch (t.tone()) {
                case 1 -> 0x55FF55;
                case 2 -> 0xFF5555;
                case 3 -> 0xD087FF;
                case 4 -> 0x7FDDFF;
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

    /**
     * 下半部分的固定标签。资料页画"物品栏"+"镇民袋"两个；工作页背包
     * 也在（拿书稿用），只画"物品栏"——镇民袋那一块在工作页不存在。
     */
    private void drawBottomLabels(GuiGraphics gfx, boolean infoPage) {
        boolean invVisible = this.page == PAGE_INFO
                || (this.page == PAGE_WORK
                        && this.menu.getResident() != null
                        && this.menu.getResident().getProfession()
                                == com.bmf.bmfstown.entity.profile.ProfessionType.SCHOLAR);
        if (!invVisible) {
            return;
        }
        // 现在底板是原版同款浅灰，直接用原版的深灰字，视觉一致
        gfx.drawString(this.font, INVENTORY_LABEL, 8,
                ResidentMenu.PLAYER_INV_Y - 9, 0x404040, false);
        if (!infoPage) {
            return;
        }
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

    /** 效率公式的一个可悬停片段：热区（x 范围）+ 悬停理由（多行）。 */
    private record EffPart(int xStart, int xEnd, List<Component> tooltip) {
    }

    /** 效率公式片段的悬停热区（每帧渲染时重建）。 */
    private final List<EffPart> effParts = new ArrayList<>();

    /** 工人产物选择弹层是否打开（点产物格开，选完/点外面收）。 */
    private boolean orderPickerOpen;
    /** 炼金药水选择弹层（v0.12 炼金）：文字行列表 + 延时/强化互斥芯片。 */
    private boolean alchPickerOpen;
    /** 弹层内当前选中的修饰档（none/long/strong；点药水行时一并下单）。 */
    /** 药水修饰档（组 A）：none|long|strong——与组 B 自由组合。 */
    private String alchModPotion = "none";
    /** 容器修饰档（组 B）：none|splash|lingering——与组 A 自由组合。 */
    private String alchModContainer = "none";
    /** 炼金弹层药水列表滚动行（固定显示 2 行 + 滚动条）。 */
    private int alchScroll;

    /** 工人订单物品格：20×20（含边框），空 = 未设置；有单显示产物图标。 */
    private void drawOrderSlot(GuiGraphics gfx, int x, int y, ItemStack orderStack,
            int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + 20 && mouseY >= y && mouseY < y + 20;
        gfx.fill(x, y, x + 20, y + 20, hovered ? 0xFF5A5A5A : 0xFF3D3D3D);
        gfx.fill(x, y, x + 20, y + 1, 0xFF161616);
        gfx.fill(x, y, x + 1, y + 20, 0xFF161616);
        gfx.fill(x + 19, y, x + 20, y + 20, 0xFF8A8A8A);
        gfx.fill(x, y + 19, x + 20, y + 20, 0xFF8A8A8A);
        if (!orderStack.isEmpty()) {
            gfx.renderItem(orderStack, x + 2, y + 2);
        }
    }

    /** 画一段效率公式并登记悬停热区。返回结束 x。 */
    private int addEffPart(GuiGraphics gfx, int x, int y, String text, int color,
                           List<Component> tooltip) {
        int end = drawSegment(gfx, x, y, text, color);
        this.effParts.add(new EffPart(x, end, tooltip));
        return end;
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
    /** 工作页职业列表的滚动偏移。 */
    private int workScroll = 0;

    /**
     * 可选职业列表（数据层过滤，v0.10）：未解锁的职业根本不进列表——
     * 当前职业永远保留（否则看不出他现在是谁）。访问空间 = 该镇民所在
     * 城镇（快照同步的 townId；无城镇 = 只有初始职业）。渲染/点击/滚轮共用。
     */
    private java.util.List<com.bmf.bmfstown.entity.profile.ProfessionType> selectableProfessions() {
        Resident r = this.menu.getResident();
        java.util.UUID townId = r == null ? null : r.getArchive().townId();
        return com.bmf.bmfstown.unlock.JobUnlocks.selectableClient(townId, currentProfession());
    }

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

    // ---- perk 节点区（工作页效率行与玩家背包之间的固定高滚动菜单） ----

    /** 产物选择弹层的行数（每行 5 格）。 */
    private static int pickerRows(int count) {
        return (count + 4) / 5;
    }

    /** 展开的 perk 节点下标（-1 = 全部收起）。选择结果走 PerkChoosePayload 落档。 */
    private int perkExpandedNode = -1;

    /**
     * perk 区当前滚动位置（逻辑行下标，0 = 树顶）。
     * 分支展开/职业切换都要 clamp（内容变短后窗口别悬在空档上）。
     */
    private int perkScroll = 0;

    /** perk 滚动条拖拽状态（想法页同款：记按点相对滑块顶的偏移）。 */
    private boolean perkScrollbarDragging = false;
    private int perkDragGrabOffset = 0;

    /** perk 区可见行数（固定高——分支再多只滚动不撑高，给玩家背包让位）。 */
    private static final int PERK_VISIBLE_ROWS = 3;

    /** 节点/分支定义表（{@link com.bmf.bmfstown.entity.profile.Perks}，下标对齐）。 */
    /** 当前职业的 perk 节点/分支 id（战士/工人/学者各一棵树）。 */
    private String[] perkNodeIds() {
        var prof = currentProfession();
        if (prof == com.bmf.bmfstown.entity.profile.ProfessionType.WORKER) {
            return com.bmf.bmfstown.entity.profile.Perks.WORKER_NODES;
        }
        if (prof == com.bmf.bmfstown.entity.profile.ProfessionType.SCHOLAR) {
            return com.bmf.bmfstown.entity.profile.Perks.SCHOLAR_NODES;
        }
        if (prof == com.bmf.bmfstown.entity.profile.ProfessionType.ALCHEMIST) {
            return com.bmf.bmfstown.entity.profile.Perks.ALCHEMIST_NODES;
        }
        return com.bmf.bmfstown.entity.profile.Perks.NODES;
    }

    private String[][] perkBranchIds() {
        var prof = currentProfession();
        if (prof == com.bmf.bmfstown.entity.profile.ProfessionType.WORKER) {
            return com.bmf.bmfstown.entity.profile.Perks.WORKER_BRANCHES;
        }
        if (prof == com.bmf.bmfstown.entity.profile.ProfessionType.SCHOLAR) {
            return com.bmf.bmfstown.entity.profile.Perks.SCHOLAR_BRANCHES;
        }
        if (prof == com.bmf.bmfstown.entity.profile.ProfessionType.ALCHEMIST) {
            return com.bmf.bmfstown.entity.profile.Perks.ALCHEMIST_BRANCHES;
        }
        return com.bmf.bmfstown.entity.profile.Perks.BRANCHES;
    }

    /** 分支名/描述的语言键前缀（战士/工人/学者各一套文案）。 */
    private String branchKeyPrefix() {
        var prof = currentProfession();
        if (prof == com.bmf.bmfstown.entity.profile.ProfessionType.WORKER) {
            return "gui.bmfstown.resident.perk.wb";
        }
        if (prof == com.bmf.bmfstown.entity.profile.ProfessionType.SCHOLAR) {
            return "gui.bmfstown.resident.perk.sb";
        }
        if (prof == com.bmf.bmfstown.entity.profile.ProfessionType.ALCHEMIST) {
            return "gui.bmfstown.resident.perk.ab";
        }
        return "gui.bmfstown.resident.perk.b";
    }

    /** 节点解锁所需段位（与 {@code Perks.UNLOCK_TIER} 一致，避免每次跨类引用）。 */
    private static final int[] PERK_NODE_TIERS = com.bmf.bmfstown.entity.profile.Perks.UNLOCK_TIER;

    /** perk 区顶（GUI 相对）：效率行下方留 2px。 */
    private int perkAreaTop() {
        return ResidentMenu.STATS_Y + 32;
    }

    /**
     * perk 树的"逻辑行"：节点行 + 展开节点下插的分支行，扁平化成一列。
     * 渲染/点击/滚动条三方共用这一份展开顺序，行号永远对得上。
     *
     * @param node 节点下标
     * @param branch 分支下标；-1 = 节点行本身
     */
    private record PerkRow(int node, int branch) {
    }

    /** 当前 perk 树的全部逻辑行（含展开分支）。 */
    private java.util.List<PerkRow> perkLogicalRows() {
        int tier = mainSkillTier();
        java.util.List<PerkRow> rows = new java.util.ArrayList<>();
        for (int n = 0; n < perkNodeIds().length; n++) {
            rows.add(new PerkRow(n, -1));
            if (n == this.perkExpandedNode && tier >= PERK_NODE_TIERS[n]) {
                for (int b = 0; b < perkBranchIds()[n].length; b++) {
                    rows.add(new PerkRow(n, b));
                }
            }
        }
        return rows;
    }

    /** perk 滚动上限（内容行数 - 可见行数，不够一屏为 0）。 */
    private int perkMaxScroll() {
        return Math.max(0, perkLogicalRows().size() - PERK_VISIBLE_ROWS);
    }

    /** 滚动位置收敛到合法区间（内容变短/切职业后调用）。 */
    private void clampPerkScroll() {
        this.perkScroll = Mth.clamp(this.perkScroll, 0, perkMaxScroll());
    }

    /** 某节点当前选中的分支序号；未选返回 -1。 */
    private int perkSelectedBranch(int node) {
        Resident r = this.menu.getResident();
        if (r == null) {
            return -1;
        }
        String choice = r.getArchive().perkChoice(currentProfession(), perkNodeIds()[node]);
        if (choice == null) {
            return -1;
        }
        for (int b = 0; b < perkBranchIds()[node].length; b++) {
            if (perkBranchIds()[node][b].equals(choice)) {
                return b;
            }
        }
        return -1;
    }

    /** perk 区底（GUI 相对）——固定高，不随展开/收起变化。 */
    private int perkAreaBottom() {
        return perkAreaTop() + PERK_VISIBLE_ROWS * ROW_H + 4;
    }

    /** 当前职业主技能的段位；无职业/无主技能返回 -1（perk 区全锁定）。 */
    private int mainSkillTier() {
        var current = currentProfession();
        var skill = current == null ? null : current.mainSkill();
        if (skill == null || this.menu.getResident() == null) {
            return -1;
        }
        return com.bmf.bmfstown.entity.profile.SkillTiers.tierOf(
                this.menu.getResident().getArchive().skillXp(skill));
    }

    /** 当前镇民的职业（客户端档案已同步；镇民不在时返回 null）。 */
    private com.bmf.bmfstown.entity.profile.ProfessionType currentProfession() {
        Resident r = this.menu.getResident();
        return r == null ? null : r.getArchive().profession();
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

        // ---- 职业列表（滚动窗口：可见 3 行，其余滚轮翻；数据层过滤锁定职业） ----
        var professions = selectableProfessions();
        var current = currentProfession();
        for (int v = 0; v < VISIBLE_PROF_ROWS; v++) {
            int i = this.workScroll + v;
            if (i >= professions.size()) {
                break;
            }
            int rowY = y + workRowTop() + v * ROW_H;
            boolean cur = current != null && professions.get(i) == current;
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
        if (current == com.bmf.bmfstown.entity.profile.ProfessionType.SCHOLAR) {
            // 学者：写作槽 + 抄写槽并排（物品由 vanilla 渲染），右侧写书/抄书
            // 切换按钮，下方进度条（当前模式的 WIP）
            int slotY = y + ResidentMenu.WIP_SLOT_Y;
            int slotX = x + ResidentMenu.WIP_SLOT_X;
            int copyX = x + ResidentMenu.COPY_SLOT_X;
            gfx.fill(slotX - 1, slotY - 1, slotX + 19, slotY + 19, 0xFF8B8B8B);
            gfx.fill(slotX, slotY, slotX + 18, slotY + 18, 0xFF373737);
            gfx.fill(copyX - 1, slotY - 1, copyX + 19, slotY + 19, 0xFF8B8B8B);
            gfx.fill(copyX, slotY, copyX + 18, slotY + 18, 0xFF373737);
            // 切换按钮（按下的样子 = 当前处于抄书模式）
            boolean copyMode = currentWorkMode() == com.bmf.bmfstown.entity.WorkMode.COPY.ordinal();
            drawWorkButton(gfx, x + 80, this.imageWidth - 90, y + workButtonsTop(),
                    copyMode, mouseX, mouseY);
            int barY = y + workButtonsTop() + WORK_BTN_H + WORK_BTN_GAP;
            gfx.fill(x + 5, barY, x + 5 + (this.imageWidth - 10), barY + WORK_BTN_H, 0xFF2E2E2E);
        } else if (workControlsVisible()) {
            int workMode = currentWorkMode();
            int bx = x + 5;
            int bw = this.imageWidth - 10;
            int followY = y + workButtonsTop();
            int guardY = followY + WORK_BTN_H + WORK_BTN_GAP;
            drawWorkButton(gfx, bx, bw, followY, workMode == 1, mouseX, mouseY);
            drawWorkButton(gfx, bx, bw, guardY, workMode == 2, mouseX, mouseY);
        }

        // ---- perk 节点区（固定高滚动菜单）：深灰框，行背景与文字在文字层画 ----
        gfx.fill(x + 4, y + perkAreaTop(), x + this.imageWidth - 4, y + perkAreaBottom(), 0xFF373737);
        var perkRows = perkLogicalRows();
        int perkTier = mainSkillTier();
        for (int v = 0; v < PERK_VISIBLE_ROWS; v++) {
            int i = this.perkScroll + v;
            if (i >= perkRows.size()) {
                break;
            }
            PerkRow row = perkRows.get(i);
            boolean unlocked = perkTier >= PERK_NODE_TIERS[row.node()];
            int rowY = y + perkAreaTop() + v * ROW_H;
            boolean hovered = mouseX >= x + 5 && mouseX < x + this.imageWidth - 5
                    && mouseY >= rowY && mouseY < rowY + ROW_H;
            if (row.branch() < 0) {
                boolean expandedHere = row.node() == this.perkExpandedNode && unlocked;
                if (expandedHere) {
                    // 展开的节点行：深底 + 亮边（归属页选中行同款语言）
                    gfx.fill(x + 5, rowY, x + this.imageWidth - 5, rowY + ROW_H, 0xFF141414);
                    gfx.renderOutline(x + 5, rowY, this.imageWidth - 10, ROW_H, 0xFFE0E0E0);
                } else if (hovered && unlocked) {
                    // 收起的节点行：解锁的可悬停提亮，锁定的死灰
                    gfx.fill(x + 5, rowY, x + this.imageWidth - 5, rowY + ROW_H, 0xFF4C4C4C);
                }
            } else if (hovered) {
                // 分支行：悬停提亮
                gfx.fill(x + 5, rowY, x + this.imageWidth - 5, rowY + ROW_H, 0xFF4C4C4C);
            }
        }
        drawPerkScrollbar(gfx);
    }

    /** perk 滚动条：轨道常驻，内容不足一屏时只有轨道（想法页同款语言）。 */
    private void drawPerkScrollbar(GuiGraphics gfx) {
        int trackX = scrollbarX();
        int trackY = this.topPos + perkAreaTop() + 1;
        int trackH = perkAreaBottom() - perkAreaTop() - 2;
        gfx.fill(trackX, trackY, trackX + SCROLLBAR_WIDTH, trackY + trackH, 0xFF1E1E1E);
        int max = perkMaxScroll();
        if (max <= 0) {
            return;
        }
        int total = perkLogicalRows().size();
        int thumbH = Math.max(8, trackH * PERK_VISIBLE_ROWS / total);
        int thumbY = trackY + (trackH - thumbH) * this.perkScroll / max;
        gfx.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbH, 0xFFC6C6C6);
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
    private void renderWorkPageText(GuiGraphics gfx, int mouseX, int mouseY) {
        Resident resident = this.menu.getResident();
        // ---- 职业行文字（滚动窗口，与背景层同一偏移；数据层过滤锁定职业） ----
        var professions = selectableProfessions();
        var current = currentProfession();
        for (int v = 0; v < VISIBLE_PROF_ROWS; v++) {
            int i = this.workScroll + v;
            if (i >= professions.size()) {
                break;
            }
            int rowY = workRowTop() + v * ROW_H + 3;
            boolean cur = current != null && professions.get(i) == current;
            String label = I18n.get(professions.get(i).displayName());
            int color = cur ? 0xFFFFFF : 0xE0E0E0;
            gfx.drawString(this.font, (cur ? "✔ " : "  ") + label, 8, rowY, color, false);
        }

        // ---- 按钮文字（水平居中）——仅当前职业有工作控件时渲染 ----
        if (!workControlsVisible()) {
            return;
        }
        int bw = this.imageWidth - 10;
        int followY = workButtonsTop();
        int guardY = followY + WORK_BTN_H + WORK_BTN_GAP;
        if (current == com.bmf.bmfstown.entity.profile.ProfessionType.SCHOLAR) {
            // 学者：书稿槽物品与悬停 tooltip 由 vanilla（真槽位）渲染——
            // 这里画切换按钮文案 + 当前模式的进度条（含百分比）
            boolean copyMode = currentWorkMode() == com.bmf.bmfstown.entity.WorkMode.COPY.ordinal();
            int writing = resident.getArchive().writingPercent();
            int copying = resident.getArchive().copyingPercent();
            int activePercent = copyMode ? copying : writing;
            boolean hasWip = activePercent >= 0;
            // 切换按钮文案：当前模式（按下的那个）
            String modeLabel = I18n.get(copyMode
                    ? "gui.bmfstown.resident.work.scholar_mode_copy"
                    : "gui.bmfstown.resident.work.scholar_mode_write");
            drawCenteredInPanel(gfx, modeLabel, 80, this.imageWidth - 90,
                    workButtonsTop() + 3, copyMode ? 0xA8FFA8 : 0xE0E0E0);
            // 进度条：深槽 + 亮绿填充；槽内居中"研究/抄写进度 · N%"（白字带影）
            int barX = 5;
            int barW = bw - 4;
            int barY = guardY + (WORK_BTN_H - 6) / 2;
            gfx.fill(barX, barY, barX + barW, barY + 6, 0xFF1E1E1E);
            if (hasWip && activePercent > 0) {
                int fillW = Math.max(1, (barW - 2) * activePercent / 100);
                gfx.fill(barX + 1, barY + 1, barX + 1 + fillW, barY + 5, 0xFF7FE07F);
            }
            String researchLabel = hasWip
                    ? I18n.get(copyMode
                            ? "gui.bmfstown.resident.work.scholar_copy_label_percent"
                            : "gui.bmfstown.resident.work.scholar_label_percent", activePercent)
                    : I18n.get(copyMode
                            ? "gui.bmfstown.resident.work.scholar_copy_label"
                            : "gui.bmfstown.resident.work.scholar_label");
            gfx.drawString(this.font, researchLabel,
                    barX + (barW - this.font.width(researchLabel)) / 2,
                    barY - 2, 0xFFFFFF, true);
            // 不提前 return——段位名+经验栏是各职业共用的尾段骨架
        }
        if (current == com.bmf.bmfstown.entity.profile.ProfessionType.WARRIOR) {
            // 战士：跟随/站岗两枚按钮
            int workMode = currentWorkMode();
            String followLabel = I18n.get(workMode == 1
                    ? "gui.bmfstown.resident.work.follow_btn_on"
                    : "gui.bmfstown.resident.work.follow_btn_off");
            String guardLabel = I18n.get(workMode == 2
                    ? "gui.bmfstown.resident.work.guard_btn_on"
                    : "gui.bmfstown.resident.work.guard_btn");
            // 开启态文字用亮绿（配合凹陷底），关闭态普通亮灰——一眼分清开关
            drawCenteredInPanel(gfx, followLabel, 5, bw, followY + 3, workMode == 1 ? 0xA8FFA8 : 0xE0E0E0);
            drawCenteredInPanel(gfx, guardLabel, 5, bw, guardY + 3, workMode == 2 ? 0xA8FFA8 : 0xE0E0E0);
        } else if (current == com.bmf.bmfstown.entity.profile.ProfessionType.BOOKKEEPER) {
            // 簿记员：查询物资按钮（统计+寻物都从这里进）
            drawCenteredInPanel(gfx, I18n.get("gui.bmfstown.resident.work.census_btn"),
                    5, bw, followY + 3, 0xE0E0E0);
            drawCenteredInPanel(gfx, I18n.get("gui.bmfstown.resident.work.census_hint"),
                    5, bw, guardY + 3, 0x707070);
        } else if (current == com.bmf.bmfstown.entity.profile.ProfessionType.WORKER) {
            // 工人：开工开关（全宽，v0.12 工坊区自动认领后无"设置工坊"）；
            // 岗位从绑定城镇的登记工坊区自动认领，玩家只管登记方块和下订单
            boolean enabled = resident.workerEnabledSynced();
            drawCenteredInPanel(gfx, I18n.get(enabled
                            ? "gui.bmfstown.resident.work.ws_toggle_on"
                            : "gui.bmfstown.resident.work.ws_toggle_off"),
                    5, bw, followY + 3, enabled ? 0xA8FFA8 : 0xE0E0E0);
            drawCenteredInPanel(gfx, I18n.get("gui.bmfstown.resident.work.product_label"),
                    5, bw, guardY + 3, 0xE0E0E0);
            int bound = resident.workerClaimedSynced() ? 1 : 0;
            int phase = resident.workerPhaseSynced();
            String orderItem = com.bmf.bmfstown.client.ClientMoodState.workerOrderOf(
                    resident.getUUID());
            var orderStack = new ItemStack(com.bmf.bmfstown.entity.profile.WorkshopCrafts
                    .BuiltInItemKey.item(orderItem));
            drawOrderSlot(gfx, this.imageWidth - 29, guardY - 2, orderStack, mouseX, mouseY);
            // ---- 状态行（实体同步通道，实时）：岗位 / 订单 / 阶段 ----
            String boundTxt = I18n.get(bound == 1
                    ? "gui.bmfstown.resident.work.ws_bound"
                    : "gui.bmfstown.resident.work.ws_unbound");
            String orderTxt = orderStack.isEmpty()
                    ? I18n.get("gui.bmfstown.resident.work.ws_no_order")
                    : orderStack.getHoverName().getString();
            // 面板打开时镇民必然被玩家优先交互（不工作中），阶段只在干活时出现
            String line = I18n.get("gui.bmfstown.resident.work.ws_line", boundTxt, orderTxt);
            if (phase >= 0) {
                line += " · " + I18n.get("gui.bmfstown.resident.work.wphase." + phase);
            }
            // perk 区下方现在是玩家背包（v0.11），状态行改画在 perk 区上方空档
            drawCenteredInPanel(gfx, line, 5, bw, perkAreaTop() - 10, 0x9E9E9E);
        } else if (current == com.bmf.bmfstown.entity.profile.ProfessionType.ALCHEMIST) {
            // 炼金术士：开工开关（酿造台登记池自动认领）+ 药水选择弹层入口
            boolean enabled = resident.workerEnabledSynced();
            drawCenteredInPanel(gfx, I18n.get(enabled
                            ? "gui.bmfstown.resident.work.ws_toggle_on"
                            : "gui.bmfstown.resident.work.ws_toggle_off"),
                    5, bw, followY + 3, enabled ? 0xA8FFA8 : 0xE0E0E0);
            drawCenteredInPanel(gfx, I18n.get("gui.bmfstown.resident.work.alch_potion_label"),
                    5, bw, guardY + 3, 0xE0E0E0);
            // ---- 当前订单预览（工人同款：右上物品槽 + perk 区上方状态行）----
            String alchOrderKey = com.bmf.bmfstown.client.ClientMoodState.workerOrderOf(
                    resident.getUUID());
            var alchOrderStack = com.bmf.bmfstown.client.ClientAlchemyCatalog
                    .displayStackForKey(alchOrderKey);
            drawOrderSlot(gfx, this.imageWidth - 29, guardY - 2, alchOrderStack, mouseX, mouseY);
            String alchOrderTxt = alchOrderStack.isEmpty()
                    ? I18n.get("gui.bmfstown.resident.work.ws_no_order")
                    : alchOrderStack.getHoverName().getString();
            drawCenteredInPanel(gfx,
                    I18n.get("gui.bmfstown.resident.work.alch_order_line", alchOrderTxt),
                    5, bw, perkAreaTop() - 10, 0x9E9E9E);
        }

        // ---- 经验条（原版风格：灰底空槽 + 原版经验绿的进度，黑色外框）----
        // 文字只报段位与进度（如"老练 · 1470/1500"）——职业名在上方列表，
        // 技能名对战士这种"一职业一技能"的结构是冗余信息，不显示。
        if (resident != null) {
            var skill = current.mainSkill();
            int xp = resident.getArchive().skillXp(skill);
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

            // ---- 效率行（v0.8.1 建立，v0.10.4 全因素化）：实时公式逐段连乘，
            //      每段可悬停看"这一段为什么是这个数"。算式与服务端
            //      （Resident.professionEfficiency / ScholarGoal.efficiency /
            //      WorkerGoal.craftTicks / tick 层活字印刷）逐段对齐——
            //      面板展示的就是实际生效的数值，没生效的因素不显示。
            this.effParts.clear();
            var profile = resident.getArchive();
            int effY = textY + 20;
            int ex = 8;
            ex = drawSegment(gfx, ex, effY, I18n.get("gui.bmfstown.resident.work.efficiency_label"), 0x555555);
            ex = drawSegment(gfx, ex, effY, " ", 0x555555);

            double base = com.bmf.bmfstown.entity.profile.EfficiencyMath.baseOf(xp);
            int basePct = (int) Math.round(base * 100);
            // 基础段染段位稀有度色，与上方 Lv/段位名一致
            ex = addEffPart(gfx, ex, effY, basePct + "%", tierColor(tier),
                    List.of(Component.translatable("gui.bmfstown.resident.work.eff_base",
                            lv, I18n.get("gui.bmfstown.resident.tier." + tier), basePct)));
            double eff = base;

            double mood = profile.mood();
            if (hasPerk(profile, com.bmf.bmfstown.entity.profile.Perks.NODE_ADEPT, "strong")) {
                double sm = com.bmf.bmfstown.entity.profile.EfficiencyMath.strongMultiplier();
                int strongPct = (int) Math.round((sm - 1.0) * 100);
                ex = drawSegment(gfx, ex, effY, " ", 0x555555);
                ex = addEffPart(gfx, ex, effY, "× " + sm, 0x2E7D2E,
                        List.of(Component.translatable("gui.bmfstown.resident.work.eff_strong", strongPct)));
                eff *= sm;
            }

            double mm = com.bmf.bmfstown.entity.profile.EfficiencyMath.moodMultiplier(mood);
            if (mm < 1.0) {
                int mmPct = (int) Math.round(mm * 100);
                int threshold = com.bmf.bmfstown.config.Config.MOOD_SLOW_THRESHOLD.get();
                ex = drawSegment(gfx, ex, effY, " ", 0x555555);
                ex = addEffPart(gfx, ex, effY, "× " + mm, 0xB02020,
                        List.of(Component.translatable("gui.bmfstown.resident.work.eff_mood", threshold, mmPct)));
                eff *= mm;
            }

            double ws = 1.0;
            if (current == com.bmf.bmfstown.entity.profile.ProfessionType.WORKER) {
                // 工人精通分支：效率（速度 +20%）/ 节能（速度 -20%）→ 公式可见
                ws = com.bmf.bmfstown.entity.profile.EfficiencyMath.workerSpeedMultiplier(
                        resident.getArchive().perkChoice(
                                com.bmf.bmfstown.entity.profile.ProfessionType.WORKER,
                                com.bmf.bmfstown.entity.profile.Perks.NODE_W_ADEPT));
                if (ws != 1.0) {
                    int wsPct = (int) Math.round(ws * 100);
                    ex = drawSegment(gfx, ex, effY, " ", 0x555555);
                    ex = addEffPart(gfx, ex, effY, "× " + ws, ws > 1.0 ? 0x2E7D2E : 0xB02020,
                            List.of(Component.translatable(
                                    "gui.bmfstown.resident.work.eff_wspeed", wsPct)));
                    eff *= ws;
                }
                // 特殊书研读增益（天工开物/考工记：合成速度）
                int craftBonus = equippedLearnedBonus(profile,
                        com.bmf.bmfstown.book.SpecialBookEffect.Type.CRAFT_SPEED);
                if (craftBonus != 0) {
                    ex = drawSegment(gfx, ex, effY, " ", 0x555555);
                    ex = addEffPart(gfx, ex, effY, "× " + fmtFactor(1 + craftBonus / 100.0),
                            0x2E7D2E,
                            List.of(Component.translatable(
                                    "gui.bmfstown.resident.work.eff_craftspeed",
                                    specialBookName(profile.equippedBookId()), craftBonus)));
                    eff *= 1 + craftBonus / 100.0;
                }
            } else if (current == com.bmf.bmfstown.entity.profile.ProfessionType.SCHOLAR) {
                // ---- 学者全因素（v0.10.4）：与服务端 ScholarGoal.efficiency
                //      + tick 层活字印刷逐段对齐 ----
                var wipContent = activeWipContent();
                // 辩证法：写强化书时对应学科的效率二次乘入（含该学科自身心情系数）
                if (hasPerk(profile, com.bmf.bmfstown.entity.profile.Perks.NODE_S_ADEPT, "dialectics")
                        && wipContent != null
                        && wipContent.draft() instanceof com.bmf.bmfstown.book.BookDraft.Perk p) {
                    var prof = com.bmf.bmfstown.entity.profile.ProfessionType.byName(p.professionId());
                    if (prof.isReal() && prof.mainSkill() != null) {
                        double discipline = com.bmf.bmfstown.entity.profile.EfficiencyMath.total(
                                profile.skillXp(prof.mainSkill()), mood, false);
                        eff *= discipline;
                        ex = drawSegment(gfx, ex, effY, " ", 0x555555);
                        ex = addEffPart(gfx, ex, effY, "× " + fmtFactor(discipline),
                                discipline >= 1.0 ? 0x2E7D2E : 0xB02020,
                                List.of(Component.translatable(
                                        "gui.bmfstown.resident.work.eff_dialectics",
                                        I18n.get("profession.bmfstown." + prof.getSerializedName()),
                                        (int) Math.round(discipline * 100))));
                    }
                }
                // 分工：写书效率 +20%（可配）
                if (hasPerk(profile, com.bmf.bmfstown.entity.profile.Perks.NODE_S_ADEPT, "division")) {
                    double dv = 1.0 + com.bmf.bmfstown.config.Config.SCHOLAR_DIVISION_BONUS.get() / 100.0;
                    eff *= dv;
                    ex = drawSegment(gfx, ex, effY, " ", 0x555555);
                    ex = addEffPart(gfx, ex, effY, "× " + fmtFactor(dv), 0x2E7D2E,
                            List.of(Component.translatable(
                                    "gui.bmfstown.resident.work.eff_division",
                                    com.bmf.bmfstown.config.Config.SCHOLAR_DIVISION_BONUS.get())));
                }
                // 过拟合：每门已学知识 +10%（可配）
                if (hasPerk(profile, com.bmf.bmfstown.entity.profile.Perks.NODE_S_MASTER, "overfit")) {
                    int learnedCount = profile.learnedBooks().size();
                    double ov = 1.0 + learnedCount
                            * com.bmf.bmfstown.config.Config.SCHOLAR_OVERFIT_BONUS.get() / 100.0;
                    eff *= ov;
                    ex = drawSegment(gfx, ex, effY, " ", 0x555555);
                    ex = addEffPart(gfx, ex, effY, "× " + fmtFactor(ov), 0x2E7D2E,
                            List.of(Component.translatable(
                                    "gui.bmfstown.resident.work.eff_overfit", learnedCount,
                                    com.bmf.bmfstown.config.Config.SCHOLAR_OVERFIT_BONUS.get())));
                }
                // 特殊书研读增益（梦溪笔谈：写书速度）
                int writeBonus = equippedLearnedBonus(profile,
                        com.bmf.bmfstown.book.SpecialBookEffect.Type.WRITE_SPEED);
                if (writeBonus != 0) {
                    eff *= 1 + writeBonus / 100.0;
                    ex = drawSegment(gfx, ex, effY, " ", 0x555555);
                    ex = addEffPart(gfx, ex, effY, "× " + fmtFactor(1 + writeBonus / 100.0),
                            0x2E7D2E,
                            List.of(Component.translatable(
                                    "gui.bmfstown.resident.work.eff_writespeed",
                                    specialBookName(profile.equippedBookId()), writeBonus)));
                }
                // 活字印刷术：抄书效率 +50%（tick 层乘区，仅抄书模式）
                if (resident.getWorkMode() == com.bmf.bmfstown.entity.WorkMode.COPY
                        && hasPerk(profile,
                                com.bmf.bmfstown.entity.profile.Perks.NODE_S_LEGEND, "movabletype")) {
                    eff *= 1.5;
                    ex = drawSegment(gfx, ex, effY, " ", 0x555555);
                    ex = addEffPart(gfx, ex, effY, "× 1.5", 0x2E7D2E,
                            List.of(Component.translatable(
                                    "gui.bmfstown.resident.work.eff_movabletype")));
                }
            }

            eff = Math.max(0.1, eff); // 与服务端同一下限
            int effPct = (int) Math.round(eff * 100);
            int effColor = eff >= 1.0 ? 0x2E7D2E : 0xB02020;   // 回本点 Lv.5=100%
            ex = drawSegment(gfx, ex, effY, " ", 0x555555);
            ex = addEffPart(gfx, ex, effY, "= " + effPct + "%", effColor,
                    List.of(Component.translatable("gui.bmfstown.resident.work.eff_total", effPct)));
            // 信息段（**不乘进工作效率**）：战士的特殊书近战伤害作用于攻击力，
            // 与"工作效率"是两个维度——只展示，不参与连乘（用户 21:14 要求可见）
            if (current == com.bmf.bmfstown.entity.profile.ProfessionType.WARRIOR) {
                int melee = equippedLearnedBonus(profile,
                        com.bmf.bmfstown.book.SpecialBookEffect.Type.MELEE_DAMAGE);
                if (melee != 0) {
                    ex = drawSegment(gfx, ex, effY, " ", 0x555555);
                    addEffPart(gfx, ex, effY, "⚔+" + melee + "%", 0xC08040,
                            List.of(Component.translatable(
                                    "gui.bmfstown.resident.work.eff_melee_info",
                                    specialBookName(profile.equippedBookId()), melee)));
                }
            }

            // 悬停提示：鼠标落在哪段公式上，就显示"这一段为什么是这个数"。
            // 坐标口径（1.21.1 实测）：renderLabels 收到的是绝对鼠标坐标，
            // 而本方法所有绘制都发生在 translate(leftPos, topPos) 平移后的
            // 面板相对坐标系里——判定与提示都要换算回相对坐标。
            int relX = mouseX - this.leftPos;
            int relY = mouseY - this.topPos;
            for (EffPart part : this.effParts) {
                if (relX >= part.xStart() && relX < part.xEnd()
                        && relY >= effY && relY < effY + 10) {
                    gfx.renderComponentTooltip(this.font, part.tooltip(), relX, relY + 12);
                    break;
                }
            }

            // ---- perk 节点区：按职业动态渲染（簿记员暂无天赋） ----
            if (current != com.bmf.bmfstown.entity.profile.ProfessionType.BOOKKEEPER) {
                renderPerkText(gfx);
            }

            // ---- 产物选择弹层（合成书式格子网格，最后画保证盖在内容上）----
            if (this.orderPickerOpen
                    && current == com.bmf.bmfstown.entity.profile.ProfessionType.WORKER) {
                // 白名单按线别过滤：基础档人人可见，布线/机关看天赋分支
                java.util.List<Item> unlocked = new java.util.ArrayList<>();
                for (var entry : com.bmf.bmfstown.entity.profile.WorkshopCrafts.whitelist()) {
                    if (com.bmf.bmfstown.entity.profile.WorkshopCrafts.lineAllowed(
                            resident.getArchive(), entry.getKey())) {
                        unlocked.add(entry.getKey());
                    }
                }
                int cols = 5;
                int rows = (unlocked.size() + cols - 1) / cols;
                int pw = cols * 20 + 8;
                int ph = 16 + rows * 20 + 6;
                int px = 4;
                int py = perkAreaTop() - 2;
                // 米白纸面板（合成书质感）+ 顶部深线
                gfx.fill(px, py, px + pw, py + ph, 0xFFE9E3D5);
                gfx.fill(px, py, px + pw, py + 1, 0xFF8A7A55);
                drawCenteredInPanel(gfx, I18n.get("gui.bmfstown.resident.work.picker_title"),
                        px, pw, py + 3, 0x4A3B22);
                int hoverIdx = -1;
                Item hoverProduct = null;
                int hoverCellX = -1;
                int hoverCellY = -1;
                for (int k = 0; k < unlocked.size(); k++) {
                    int cellX = px + 4 + (k % cols) * 20;
                    int cellY = py + 16 + (k / cols) * 20;
                    boolean hov = mouseX >= this.leftPos + cellX && mouseX < this.leftPos + cellX + 18
                            && mouseY >= this.topPos + cellY && mouseY < this.topPos + cellY + 18;
                    gfx.fill(cellX, cellY, cellX + 18, cellY + 18, hov ? 0xFFCFC5A2 : 0xFFD8D0B8);
                    gfx.renderItem(new ItemStack(unlocked.get(k)), cellX + 1, cellY + 1);
                    if (hov) {
                        hoverIdx = k;
                        hoverProduct = unlocked.get(k);
                        hoverCellX = cellX;
                        hoverCellY = cellY;
                    }
                }
                if (hoverProduct != null) {
                    // 鼠标换算成面板相对坐标给 tooltip（renderLabels 在平移姿势里）
                    gfx.renderComponentTooltip(this.font, List.of(Component.translatable(
                            hoverProduct.getDescriptionId())),
                            mouseX - this.leftPos + 8, mouseY - this.topPos + 8);
                }
                // 悬停高亮的白框最后描一遍
                if (hoverIdx >= 0) {
                    gfx.fill(hoverCellX, hoverCellY, hoverCellX + 18, hoverCellY + 1, 0xFFFFFFFF);
                    gfx.fill(hoverCellX, hoverCellY + 17, hoverCellX + 18, hoverCellY + 18, 0xFFFFFFFF);
                    gfx.fill(hoverCellX, hoverCellY, hoverCellX + 1, hoverCellY + 18, 0xFFFFFFFF);
                    gfx.fill(hoverCellX + 17, hoverCellY, hoverCellX + 18, hoverCellY + 18, 0xFFFFFFFF);
                }
            } else if (this.alchPickerOpen
                    && current == com.bmf.bmfstown.entity.profile.ProfessionType.ALCHEMIST) {
                // 炼金药水选择弹层（工人产物选择同款：真实物品图标格子网格）。
                // 修饰分两组：药水档（延时/强化，互斥可全不选）× 容器档（喷溅/滞留，
                // 互斥可全不选），组间自由组合（延时+喷溅 = 延时喷溅药）。
                // 位置 = 背包区（炼金页背包已收起），不与 perk 区重叠（用户 11-06 定稿）。
                var potions = com.bmf.bmfstown.client.ClientAlchemyCatalog.entries();
                int cols = 8;
                int visRows = 2; // 固定显示 2 行，超出滚动（用户 11-06 定稿加滚动条）
                int arows = (potions.size() + cols - 1) / cols;
                int maxScroll = Math.max(0, arows - visRows);
                this.alchScroll = Mth.clamp(this.alchScroll, 0, maxScroll);
                int pw = this.imageWidth - 8;
                int ph = 46 + visRows * 20 + 2;
                int px = 4;
                int py = perkAreaBottom() + 2;
                gfx.fill(px, py, px + pw, py + ph, 0xFFE9E3D5);
                gfx.fill(px, py, px + pw, py + 1, 0xFF8A7A55);
                drawCenteredInPanel(gfx, I18n.get("gui.bmfstown.resident.work.alch_picker_title"),
                        px, pw, py + 3, 0x4A3B22);
                // 修饰芯片：两行两组（药效行 / 容器行），行内互斥可全不选，组间自由组合。
                // 按钮 = 1px 深棕边框 + 内芯填充（选中棕底白字），文案完整居中显示
                String[] chipMods = {"long", "strong", "splash", "lingering"};
                String[] chipLabels = {I18n.get("gui.bmfstown.resident.work.alch_chip_long"),
                        I18n.get("gui.bmfstown.resident.work.alch_chip_strong"),
                        I18n.get("gui.bmfstown.resident.work.alch_chip_splash"),
                        I18n.get("gui.bmfstown.resident.work.alch_chip_lingering")};
                String[] groupLabels = {I18n.get("gui.bmfstown.resident.work.alch_group_potion"),
                        I18n.get("gui.bmfstown.resident.work.alch_group_container")};
                int chipW = (pw - 8 - 26 - 4) / 2;
                for (int g = 0; g < 2; g++) {
                    int rowY = py + 14 + g * 14;
                    gfx.drawString(this.font, groupLabels[g], px + 4, rowY + 2, 0x6B5A38, false);
                    for (int c = 0; c < 2; c++) {
                        int idx = g * 2 + c;
                        int chipX = px + 30 + c * (chipW + 2);
                        String cur = g == 0 ? this.alchModPotion : this.alchModContainer;
                        boolean sel = cur.equals(chipMods[idx]);
                        gfx.fill(chipX, rowY, chipX + chipW, rowY + 12, 0xFF8A7A55);
                        gfx.fill(chipX + 1, rowY + 1, chipX + chipW - 1, rowY + 11,
                                sel ? 0xFF8A7A55 : 0xFFEFE9D8);
                        String t = this.font.plainSubstrByWidth(chipLabels[idx], chipW - 4);
                        gfx.drawString(this.font, t,
                                chipX + (chipW - this.font.width(t)) / 2, rowY + 2,
                                sel ? 0xFFFFFF : 0x4A3B22, false);
                    }
                }
                if (potions.isEmpty()) {
                    gfx.drawString(this.font,
                            I18n.get("gui.bmfstown.resident.work.alch_sync_hint"),
                            px + 6, py + 49, 0x8A7A55, false);
                }
                // 药水格子（真实瓶图标 + 悬停白框 + tooltip，工人同款）。
                // 图标 = 按当前芯片实时算出的**最终产物**（延时+喷溅 → 喷溅型延时瓶）——
                // 格子本身就是预览，悬停 tooltip 同名（用户 11-06 定稿）
                int hoverIdx = -1;
                int hoverCellX = -1;
                int hoverCellY = -1;
                net.minecraft.world.item.ItemStack hoverStack = null;
                int start = this.alchScroll * cols;
                for (int k = start; k < Math.min(potions.size(), start + visRows * cols); k++) {
                    var entry = potions.get(k);
                    int cellX = px + 4 + (k % cols) * 20;
                    int cellY = py + 46 + (k / cols - this.alchScroll) * 20;
                    boolean hov = mouseX >= this.leftPos + cellX && mouseX < this.leftPos + cellX + 18
                            && mouseY >= this.topPos + cellY && mouseY < this.topPos + cellY + 18;
                    gfx.fill(cellX, cellY, cellX + 18, cellY + 18, hov ? 0xFFCFC5A2 : 0xFFD8D0B8);
                    String pp = this.alchModPotion;
                    String cc = this.alchModContainer;
                    if (pp.equals("long") && !entry.hasLong()) {
                        pp = "none";
                    }
                    if (pp.equals("strong") && !entry.hasStrong()) {
                        pp = "none";
                    }
                    if (cc.equals("splash") && !entry.hasSplash()) {
                        cc = "none";
                    }
                    if (cc.equals("lingering") && !entry.hasLingering()) {
                        cc = "none";
                    }
                    var icon = com.bmf.bmfstown.client.ClientAlchemyCatalog
                            .displayStack(entry.potionId(), pp, cc);
                    gfx.renderItem(icon, cellX + 1, cellY + 1);
                    if (hov) {
                        hoverIdx = k;
                        hoverCellX = cellX;
                        hoverCellY = cellY;
                        hoverStack = icon;
                    }
                }
                if (hoverStack != null) {
                    gfx.renderComponentTooltip(this.font, List.of(hoverStack.getHoverName()),
                            mouseX - this.leftPos + 8, mouseY - this.topPos + 8);
                }
                // 滚动条（超出 2 行才画）
                if (maxScroll > 0) {
                    int trackY = py + 46;
                    int trackH = visRows * 20;
                    gfx.fill(px + pw - 3, trackY, px + pw - 1, trackY + trackH, 0xFFD8D0B8);
                    int thumbH = Math.max(8, trackH * visRows / arows);
                    int thumbY = trackY + (trackH - thumbH) * this.alchScroll / maxScroll;
                    gfx.fill(px + pw - 3, thumbY, px + pw - 1, thumbY + thumbH, 0xFF8A7A55);
                }
                if (hoverIdx >= 0) {
                    gfx.fill(hoverCellX, hoverCellY, hoverCellX + 18, hoverCellY + 1, 0xFFFFFFFF);
                    gfx.fill(hoverCellX, hoverCellY + 17, hoverCellX + 18, hoverCellY + 18, 0xFFFFFFFF);
                    gfx.fill(hoverCellX, hoverCellY, hoverCellX + 1, hoverCellY + 18, 0xFFFFFFFF);
                    gfx.fill(hoverCellX + 17, hoverCellY, hoverCellX + 18, hoverCellY + 18, 0xFFFFFFFF);
                }
            }
        }
    }

    /** 资料页"重命名"按钮的文案。 */
    private static final String RENAME_BTN_LABEL = I18n.get("gui.bmfstown.resident.rename_btn");

    /** 资料页"重命名"按钮的面板相对区域 {x, y, w, h}（渲染时更新；null = 本帧无按钮）。 */
    private int[] renameBtnRel;

    /** 资料页"传送"按钮的面板相对区域（同上）。 */
    private int[] teleportBtnRel;

    /** 爱好页 GATTACA"伪装"按钮热区（渲染时更新；null = 本帧无/不可用）。 */
    private int[] gattacaBtnRel;

    /** 工作页"跟随见习"按钮（perk 书卡点时出现；rel 坐标，非空 = 可点）。 */
    private int[] practiceBtnRel;

    /** 关闭回跳目标：0=游戏 1=城镇手册 2=GATTACA 选择列表 3=回观察者自己的 UI。 */
    public static int REOPEN_TARGET = 0;
    /** GATTACA 查看模式：观察者（GATTACA 本尊）实体 ID；-1 = 非查看模式。 */
    public static int GATTACA_VIEWER_ID = -1;

    /**
     * 跟随见习查看链：当前正在被玩家指定跟随对象的<b>卡点学者</b>实体 id。
     * 从跟随见习选人列表点人后设置（对方面板的"跟随见习"按钮判定与
     * follow 指令都靠它）；与 GATTACA_VIEWER_ID 同一套生命周期。
     */
    public static int PRACTICE_VIEWER_ID = -1;

    /**
     * 切换到非容器 Screen 前调用：显式关闭容器菜单。
     *
     * <p>直接 {@code setScreen} 替换容器屏<b>不会</b>触发 closeContainer，
     * 服务端交互锁（interactingPlayer + FaceInteractingPlayerGoal 占用 MOVE/LOOK）
     * 会一直残留，冻结带路/工作等 Goal——簿记员"选完物品不走，重开面板才走"
     * 的实测根因（v0.8.1）。重开面板"治好"它纯属巧合（openMenu 内部先关旧容器）。
     */
    private void closeContainerBeforeSwitch() {
        if (this.minecraft != null && this.minecraft.player != null
                && this.minecraft.player.containerMenu != null
                && this.minecraft.player.containerMenu != this.minecraft.player.inventoryMenu) {
            this.minecraft.player.closeContainer();
        }
    }

    /** 在面板横向范围内居中画一行字。 */
    private void drawCenteredInPanel(GuiGraphics gfx, String text, int x, int w, int y, int color) {
        gfx.drawString(this.font, text, x + (w - this.font.width(text)) / 2, y, color, false);
    }

    /**
     * perk 节点区文字。
     *
     * <p>与背景层、点击层共用 {@link #perkLogicalRows()} 这一份展开顺序
     * 加 {@link #perkScroll} 滚动窗口——三方行号永远对得上。
     */
    private void renderPerkText(GuiGraphics gfx) {
        clampPerkScroll(); // 职业切换/分支收起后内容行数变了，窗口别悬在空档上
        int tier = mainSkillTier();
        var rows = perkLogicalRows();
        for (int v = 0; v < PERK_VISIBLE_ROWS; v++) {
            int i = this.perkScroll + v;
            if (i >= rows.size()) {
                break;
            }
            PerkRow row = rows.get(i);
            int n = row.node();
            boolean unlocked = tier >= PERK_NODE_TIERS[n];
            int textY = perkAreaTop() + v * ROW_H + 3;
            if (row.branch() < 0) {
                int selected = unlocked ? perkSelectedBranch(n) : -1;
                String status;
                if (!unlocked) {
                    status = I18n.get("gui.bmfstown.resident.perk.locked", PERK_NODE_TIERS[n] + 1);
                } else if (selected >= 0) {
                    status = I18n.get("gui.bmfstown.resident.perk.chosen",
                            I18n.get(branchKeyPrefix() + "name." + n + "." + selected));
                } else if (this.perkExpandedNode == n) {
                    status = "▾";
                } else {
                    status = I18n.get("gui.bmfstown.resident.perk.ready");
                }
                int color = unlocked ? 0xFFFFFF : 0x808080;
                // 节点名：职业专属键优先（炼金=火候/净化/宗师），没有回落共享键
                String nodeKey = net.minecraft.client.resources.language.I18n
                        .exists(branchKeyPrefix() + "node." + n)
                        ? branchKeyPrefix() + "node." + n
                        : "gui.bmfstown.resident.perk.node." + n;
                gfx.drawString(this.font, "◆ " + I18n.get(nodeKey)
                        + "  " + status, 8, textY, color, false);
            } else {
                int b = row.branch();
                String name = I18n.get(branchKeyPrefix() + "name." + n + "." + b);
                // 描述文本带 %s 占位，数值从配置现读——改 toml 文案跟着变
                String desc = I18n.get(branchKeyPrefix() + "desc." + n + "." + b,
                        perkDescArgs(n, b));
                int selected = perkSelectedBranch(n);
                String prefix = selected == b ? "✔ " : "   ";
                // 选中分支亮白，未选灰白；锁定描述不额外变色（父节点已置灰）
                gfx.drawString(this.font, prefix + name + " · " + desc,
                        8, textY, selected == b ? 0xFFFFFF : 0xC8C8C8, false);
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
        // 学者树（v0.10.3）：数值全部来自 common 配置
        if (currentProfession() == com.bmf.bmfstown.entity.profile.ProfessionType.SCHOLAR) {
            return switch (node) {
                case 0 -> branch == 0
                        ? new Object[]{} // 辩证法：静态描述
                        : new Object[]{com.bmf.bmfstown.config.Config.SCHOLAR_DIVISION_BONUS.get()};
                case 1 -> branch == 0
                        ? new Object[]{com.bmf.bmfstown.config.Config.SCHOLAR_OVERFIT_BONUS.get()}
                        : new Object[]{}; // 批注：静态描述（文案里不含 % 号）
                // 三门问题 / 活字印刷：静态文案但含字面 %%（需 ≥1 参走格式化才显示成 %），
                // 传一个空串参数撑起格式化管道
                default -> new Object[]{""};
            };
        }
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
        return r == null ? null : r.getArchive().townId();
    }

    /** 当前绑定的住宅区（镇民档案里的 housingZoneId；镇民不在时 null）。 */
    private UUID currentHousingId() {
        Resident r = this.menu.getResident();
        return r == null ? null : r.getArchive().housingZoneId();
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
            "gui.bmfstown.resident.tab.thoughts",
            "gui.bmfstown.resident.tab.knowledge"};
    /** 页签文字（每次绘制时从 lang 取，语言切换即时生效）。 */
    private static String[] tabNames() {
        return new String[]{I18n.get(TAB_KEYS[0]), I18n.get(TAB_KEYS[1]),
                I18n.get(TAB_KEYS[2]), I18n.get(TAB_KEYS[3]), I18n.get(TAB_KEYS[4]),
                I18n.get(TAB_KEYS[5])};
    }
    /** 页签宽度（px），与 TAB_KEYS 一一对应。五个页签总宽不得超面板 176px。 */
    private static final int[] TAB_WIDTHS = {34, 30, 30, 30, 30, 30};

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

    /** 点击是否落在工作页上板某个槽位（GUI 相对坐标 → 绝对判定）。 */
    private boolean inRect(double mouseX, double mouseY, int relX, int relY) {
        return mouseX >= this.leftPos + relX && mouseX < this.leftPos + relX + 18
                && mouseY >= this.topPos + relY && mouseY < this.topPos + relY + 18;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 页签在最上层：两页都要先判，点中就吞掉（下面不会有什么可点的东西）
        if (button == 0 && clickTab(mouseX, mouseY)) {
            return true;
        }
        // ---- 资料页：名字右侧的"重命名"按钮 ----
        if (this.page == PAGE_INFO && button == 0 && this.renameBtnRel != null) {
            int[] b = this.renameBtnRel;
            if (mouseX >= this.leftPos + b[0] && mouseX < this.leftPos + b[0] + b[2]
                    && mouseY >= this.topPos + b[1] && mouseY < this.topPos + b[1] + b[3]) {
                Resident r = this.menu.getResident();
                if (r != null && this.minecraft != null) {
                    // 交互锁教训：切到非容器屏前必须显式关容器，
                    // 否则服务端交互锁残留冻结镇民 AI
                    closeContainerBeforeSwitch();
                    RenameResidentScreen.open(r.getId(), r.getName().getString());
                }
                return true;
            }
        }
        // ---- 爱好页 GATTACA"伪装"按钮：打开模仿选择列表 ----
        // （必须独立于资料页分支：之前嵌在 PAGE_INFO 块内，爱好页永远走不到，
        //   表现为"点伪装没反应"——v0.8.1 实测教训）
        if (this.page == PAGE_INFO && button == 0 && this.gattacaBtnRel != null) {
            int[] gb = this.gattacaBtnRel;
            if (mouseX >= this.leftPos + gb[0] && mouseX < this.leftPos + gb[0] + gb[2]
                    && mouseY >= this.topPos + gb[1] && mouseY < this.topPos + gb[1] + gb[3]) {
                Resident r = this.menu.getResident();
                if (r != null && this.minecraft != null) {
                    closeContainerBeforeSwitch();
                    this.minecraft.setScreen(GattacaSelectScreen.open(r.getId()));
                }
                return true;
            }
        }
        // ---- 传送 / GATTACA 伪装按钮 ----
        // （只在手册/GATTACA 查看入口出现：右键脸贴脸打开时没有意义，不渲染）
        if (button == 0 && this.teleportBtnRel != null) {
            int[] tb = this.teleportBtnRel;
            if (mouseX >= this.leftPos + tb[0] && mouseX < this.leftPos + tb[0] + tb[2]
                    && mouseY >= this.topPos + tb[1] && mouseY < this.topPos + tb[1] + tb[3]) {
                Resident r = this.menu.getResident();
                if (r != null) {
                    if (REOPEN_TARGET == 4 && PRACTICE_VIEWER_ID >= 0) {
                        // 跟随见习：指定卡点学者的跟随对象，回学者自己的面板
                        ModNetworking.sendToServer(new ModNetworking.PracticeFollowPayload(
                                PRACTICE_VIEWER_ID, r.getId()));
                        REOPEN_TARGET = 3;
                        onClose();
                    } else if (REOPEN_TARGET == 2 && GATTACA_VIEWER_ID >= 0) {
                        // 伪装：复制对方数据，关当前查看回观察者自己的 UI
                        ModNetworking.sendToServer(new ModNetworking.GattacaMimicPayload(
                                GATTACA_VIEWER_ID, r.getId()));
                        REOPEN_TARGET = 3;
                        onClose();
                    } else {
                        ModNetworking.sendToServer(new ModNetworking.TeleportToResidentPayload(
                                r.getId()));
                    }
                }
                return true;
            }
        }
        // ---- 工作页点击：职业行 + 工作控件区 ----
        if (this.page == PAGE_WORK && button == 0) {
            int listX = this.leftPos + 5;
            int listW = this.imageWidth - 10;

            var clickedProf = currentProfession();

            // ---- 产物选择弹层打开：点击优先归弹层（格子下单 / 点外面收起） ----
            if (this.orderPickerOpen
                    && clickedProf == com.bmf.bmfstown.entity.profile.ProfessionType.WORKER) {
                Resident r = this.menu.getResident();
                int relX = (int) (mouseX - this.leftPos);
                int relY = (int) (mouseY - this.topPos);
                int px = 4;
                int py = perkAreaTop() - 2;
                var catalog = com.bmf.bmfstown.entity.profile.WorkshopCrafts.whitelist();
                int cols = 5;
                boolean insidePopup = relX >= px && relX < px + cols * 20 + 8
                        && relY >= py && relY < py + 16 + pickerRows(catalog.size()) * 20 + 6;
                if (insidePopup && r != null
                        && relY >= py + 16 && relX >= px + 4 && relX < px + 4 + cols * 20) {
                    int row = (relY - (py + 16)) / 20;
                    int col = (relX - (px + 4)) / 20;
                    java.util.List<Item> unlocked = new java.util.ArrayList<>();
                    for (var entry : catalog) {
                        if (com.bmf.bmfstown.entity.profile.WorkshopCrafts.lineAllowed(
                                r.getArchive(), entry.getKey())) {
                            unlocked.add(entry.getKey());
                        }
                    }
                    int cellIndex = row * cols + col;
                    if (row >= 0 && cellIndex >= 0 && cellIndex < unlocked.size()) {
                        Item product = unlocked.get(cellIndex);
                        String productId = com.bmf.bmfstown.entity.profile.WorkshopCrafts
                                .BuiltInItemKey.of(product);
                        ModNetworking.sendToServer(new ModNetworking.WorkerOrderPayload(
                                r.getId(), productId));
                        com.bmf.bmfstown.client.ClientMoodState.setWorkerOrder(
                                r.getUUID(), productId);
                        this.orderPickerOpen = false;
                        return true;
                    }
                }
                this.orderPickerOpen = false; // 点弹层外：收起
                return true;
            }

            // ---- 炼金弹层打开：芯片切换 + 格子点击下单 / 点外面收起 ----
            if (this.alchPickerOpen
                    && clickedProf == com.bmf.bmfstown.entity.profile.ProfessionType.ALCHEMIST) {
                Resident r = this.menu.getResident();
                int relX = (int) (mouseX - this.leftPos);
                int relY = (int) (mouseY - this.topPos);
                int px = 4;
                int py = perkAreaBottom() + 2;
                int pw = this.imageWidth - 8;
                int cols = 8;
                String[] chipMods = {"long", "strong", "splash", "lingering"};
                var potions = com.bmf.bmfstown.client.ClientAlchemyCatalog.entries();
                int arows = (potions.size() + cols - 1) / cols;
                int visRows = 2;
                boolean insidePopup = relX >= px && relX < px + pw
                        && relY >= py && relY < py + 46 + visRows * 20 + 2;
                if (insidePopup && r != null) {
                    // 芯片带：两行两组（药效/容器），行内互斥可全不选
                    int chipW = (pw - 8 - 26 - 4) / 2;
                    if (relY >= py + 14 && relY < py + 40
                            && relX >= px + 4 && relX < px + 30 + 2 * chipW + 2) {
                        int g = relY >= py + 28 ? 1 : 0;
                        int rowY = py + 14 + g * 14;
                        if (relY >= rowY && relY < rowY + 12) {
                            int col = relX >= px + 30 + chipW + 2 ? 1 : 0;
                            int idx = g * 2 + col;
                            if (g == 0) {
                                this.alchModPotion = this.alchModPotion.equals(chipMods[idx])
                                        ? "none" : chipMods[idx];
                            } else {
                                this.alchModContainer = this.alchModContainer.equals(chipMods[idx])
                                        ? "none" : chipMods[idx];
                            }
                        }
                        return true;
                    }
                    // 药水格子（工人同款 20px 网格；滚动偏移）
                    if (relY >= py + 46 && relY < py + 46 + visRows * 20
                            && relX >= px + 4 && relX < px + 4 + cols * 20) {
                        int row = (relY - (py + 46)) / 20;
                        int col = (relX - (px + 4)) / 20;
                        int k = (this.alchScroll + row) * cols + col;
                        if (row >= 0 && k >= 0 && k < potions.size()) {
                            var entry = potions.get(k);
                            // 档位不存在 → 回落基础档（该药无延时/强化/喷溅/滞留变体时）
                            String potionPart = this.alchModPotion;
                            String contPart = this.alchModContainer;
                            if (potionPart.equals("long") && !entry.hasLong()) {
                                potionPart = "none";
                            }
                            if (potionPart.equals("strong") && !entry.hasStrong()) {
                                potionPart = "none";
                            }
                            if (contPart.equals("splash") && !entry.hasSplash()) {
                                contPart = "none";
                            }
                            if (contPart.equals("lingering") && !entry.hasLingering()) {
                                contPart = "none";
                            }
                            String mod;
                            if (potionPart.equals("none") && contPart.equals("none")) {
                                mod = "none";
                            } else if (contPart.equals("none")) {
                                mod = potionPart;
                            } else if (potionPart.equals("none")) {
                                mod = contPart;
                            } else {
                                mod = potionPart + "+" + contPart;
                            }
                            String key = "alch|" + entry.potionId() + "|" + mod;
                            ModNetworking.sendToServer(new ModNetworking.AlchemistOrderPayload(
                                    r.getId(), key));
                            // 乐观更新订单缓存（服务端为权威）——订单预览立刻可见
                            com.bmf.bmfstown.client.ClientMoodState.setWorkerOrder(
                                    r.getUUID(), key);
                            this.alchPickerOpen = false;
                            return true;
                        }
                    }
                }
                this.alchPickerOpen = false; // 点弹层外：收起
                return true;
            }

            // 职业行（滚动窗口：可见行 v → 实际下标 workScroll + v；下标=过滤列表位置）
            int rowTop = this.topPos + workRowTop();
            int rel = (int) (mouseY - rowTop);
            if (mouseX >= listX && mouseX < listX + listW
                    && rel >= 0 && rel < VISIBLE_PROF_ROWS * ROW_H) {
                int index = this.workScroll + rel / ROW_H;
                if (index < selectableProfessions().size()) {
                    clickMenu(ResidentMenu.BTN_PROFESSION_BASE + index);
                }
                return true;
            }

            // 跟随按钮（切换开/关，服务端落账并互斥清除站岗）
            // 无业的职业下不渲染按钮，点击区也一并失效——
            // 这块区域按职业动态分配，不属于战士专属。
            int followY = this.topPos + workButtonsTop();
            int guardY = followY + WORK_BTN_H + WORK_BTN_GAP;
            // ---- 工人：设置工坊（三步绑定）+ 切换产物 ----
            //      只吞两个按钮带；按钮区以外的点击（perk 区等）必须放行，
            //      否则 perk 区点击会被这里吞掉（v0.8.1 踩过：工人 perk 点不动）。
            if (workControlsVisible() && mouseX >= listX && mouseX < listX + listW
                    && clickedProf == com.bmf.bmfstown.entity.profile.ProfessionType.BOOKKEEPER) {
                // 簿记员：查询物资按钮带（吞掉本带即可，perk 区放行）
                if (mouseY >= followY && mouseY < followY + WORK_BTN_H) {
                    Resident r = this.menu.getResident();
                    if (r != null) {
                        ModNetworking.sendToServer(new ModNetworking.CensusRequestPayload(
                                r.getId()));
                    }
                    return true;
                }
                if (mouseY >= guardY && mouseY < guardY + WORK_BTN_H) {
                    return true; // 提示行，无点击
                }
                // 按钮带以外放行
            }
            if (workControlsVisible() && mouseX >= listX && mouseX < listX + listW
                    && clickedProf == com.bmf.bmfstown.entity.profile.ProfessionType.WORKER) {
                Resident r = this.menu.getResident();
                if (r == null) {
                    return true;
                }
                if (mouseY >= followY && mouseY < followY + WORK_BTN_H) {
                    // 全宽 = 开工/停工切换（v0.12：工坊由登记表供给，无绑定按钮）
                    ModNetworking.sendToServer(new ModNetworking.WorkerTogglePayload(r.getId()));
                    return true;
                }
                if (mouseY >= guardY && mouseY < guardY + WORK_BTN_H) {
                    // 打开产物选择弹层（合成书式格子）
                    this.orderPickerOpen = true;
                    return true;
                }
                // 不在按钮上：掉出本块，交给后面的 perk 区点击
            }
            // ---- 炼金术士：开工开关 + 药水选择弹层入口 ----
            if (workControlsVisible() && mouseX >= listX && mouseX < listX + listW
                    && clickedProf == com.bmf.bmfstown.entity.profile.ProfessionType.ALCHEMIST) {
                Resident r = this.menu.getResident();
                if (r == null) {
                    return true;
                }
                if (mouseY >= followY && mouseY < followY + WORK_BTN_H) {
                    ModNetworking.sendToServer(new ModNetworking.WorkerTogglePayload(r.getId()));
                    return true;
                }
                if (mouseY >= guardY && mouseY < guardY + WORK_BTN_H) {
                    this.alchModPotion = "none"; // 每次打开重置修饰档
                    this.alchModContainer = "none";
                    this.alchScroll = 0;
                    this.alchPickerOpen = true;
                    // 自愈式拉取：目录不靠登录时的一次性推送，开弹层即向服务端要
                    ModNetworking.sendToServer(new ModNetworking.AlchemyCatalogRequestPayload());
                    return true;
                }
            }
            // ---- 学者：写书/抄书模式切换按钮（v0.10.3）----
            if (workControlsVisible() && clickedProf == com.bmf.bmfstown.entity.profile.ProfessionType.SCHOLAR) {
                int toggleX = this.leftPos + 80;
                int toggleY = this.topPos + workButtonsTop();
                if (mouseX >= toggleX && mouseX < toggleX + this.imageWidth - 90
                        && mouseY >= toggleY && mouseY < toggleY + WORK_BTN_H) {
                    clickMenu(ResidentMenu.BTN_TOGGLE_COPY);
                    return true;
                }
            }
            // ---- 学者：跟随见习按钮（perk 书卡点时出现）→ 打开对口选人列表 ----
            if (this.practiceBtnRel != null) {
                int[] pb = this.practiceBtnRel;
                if (mouseX >= this.leftPos + pb[0] && mouseX < this.leftPos + pb[0] + pb[2]
                        && mouseY >= this.topPos + pb[1] && mouseY < this.topPos + pb[1] + pb[3]) {
                    Resident r = this.menu.getResident();
                    var wipContent = this.menu.getSlot(ResidentMenu.WIP_SLOT_INDEX).getItem()
                            .get(com.bmf.bmfstown.ModDataComponents.BOOK_CONTENT.get());
                    if (r != null && this.minecraft != null && wipContent != null
                            && wipContent.draft() instanceof com.bmf.bmfstown.book.BookDraft.Perk p) {
                        closeContainerBeforeSwitch();
                        this.minecraft.setScreen(PracticeSelectScreen.open(r.getId(), p.professionId()));
                    }
                    return true;
                }
            }
            // ---- 学者的书稿交互已升级为真槽位（ResidentMenu.WipSlot）：
            //      光标拿起/放回/Shift 搬运全走 vanilla，这里不再拦截 ----
            // ---- 战士：跟随 / 站岗 ----
            if (workControlsVisible() && clickedProf == com.bmf.bmfstown.entity.profile.ProfessionType.WARRIOR
                    && mouseX >= listX && mouseX < listX + listW) {
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
                            closeContainerBeforeSwitch();
                            this.minecraft.setScreen(null);
                        }
                        GuardPostClient.startPick(r.getId());
                    }
                    return true; // 非战士点也吞掉（服务端会给提示）
                }
            }

            // ---- perk 节点区点击（固定高滚动菜单：滚动条 + 节点/分支命中） ----
            int perkTrackX = scrollbarX();
            int perkTrackY = this.topPos + perkAreaTop() + 1;
            int perkTrackH = perkAreaBottom() - perkAreaTop() - 2;
            if (perkMaxScroll() > 0 && mouseX >= perkTrackX && mouseX < perkTrackX + SCROLLBAR_WIDTH
                    && mouseY >= perkTrackY && mouseY < perkTrackY + perkTrackH) {
                // 滚动条：按滑块记抓取偏移，按轨道直接跳过去接着拖（想法页同款）
                int max = perkMaxScroll();
                int total = perkLogicalRows().size();
                int thumbH = Math.max(8, perkTrackH * PERK_VISIBLE_ROWS / total);
                int thumbY = perkTrackY + (perkTrackH - thumbH) * this.perkScroll / max;
                this.perkDragGrabOffset = Mth.clamp((int) mouseY - thumbY, 0, thumbH);
                this.perkScrollbarDragging = true;
                updatePerkScrollFromMouse((int) mouseY, perkTrackY, perkTrackH, thumbH, max);
                return true;
            }
            if (mouseX >= this.leftPos + 4 && mouseX < this.leftPos + this.imageWidth - 4
                    && mouseY >= this.topPos + perkAreaTop() && mouseY < this.topPos + perkAreaBottom()) {
                int perkTier = mainSkillTier();
                int relPerk = (int) (mouseY - (this.topPos + perkAreaTop()));
                int visible = relPerk / ROW_H;
                var rows = perkLogicalRows();
                int idx = this.perkScroll + visible;
                if (idx < rows.size()) {
                    PerkRow row = rows.get(idx);
                    if (row.branch() < 0) {
                        // 节点行：已解锁的展开/收起；展开时滚到该节点置顶，分支直接可见
                        if (perkTier >= PERK_NODE_TIERS[row.node()]) {
                            this.perkExpandedNode = this.perkExpandedNode == row.node() ? -1 : row.node();
                            clampPerkScroll();
                        }
                    } else {
                        // 分支行：点中就发选档包（服务端复核段位后落档，免费改选）
                        Resident r = this.menu.getResident();
                        if (r != null) {
                            ModNetworking.sendToServer(new ModNetworking.PerkChoosePayload(
                                    r.getId(), perkNodeIds()[row.node()],
                                    perkBranchIds()[row.node()][row.branch()]));
                        }
                    }
                }
                return true;
            }

            // 上板其余区域是死区（下面躺着被隐藏的格子）——
            // 例外：学者写作/抄写槽是真槽位，点击必须放行给 vanilla 处理
            // （拿起/放回/Shift 搬运都在 super.mouseClicked 里分发）
            boolean inWipSlot = inRect(mouseX, mouseY, ResidentMenu.WIP_SLOT_X, ResidentMenu.WIP_SLOT_Y)
                    || inRect(mouseX, mouseY, ResidentMenu.COPY_SLOT_X, ResidentMenu.WIP_SLOT_Y);
            if (mouseX >= this.leftPos && mouseX < this.leftPos + this.imageWidth
                    && mouseY >= this.topPos && mouseY < this.topPos + ResidentMenu.PLAYER_INV_Y - 4
                    && !inWipSlot) {
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
        // ---- 知识页：点已学书行 = 装备该书（服务端按排序下标对齐） ----
        if (this.page == PAGE_KNOWLEDGE && button == 0) {
            int[] row = knowledgeRowAt(mouseX, mouseY);
            if (row != null) {
                clickMenu(ResidentMenu.BTN_KNOWLEDGE_BASE + row[0]);
                return true;
            }
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

    /** perk 滚动条拖拽的换算（想法页同款公式）。 */
    private void updatePerkScrollFromMouse(int mouseY, int trackY, int trackH, int thumbH, int max) {
        int target = (mouseY - this.perkDragGrabOffset - trackY) * max / (trackH - thumbH);
        this.perkScroll = Mth.clamp(target, 0, max);
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
        if (this.perkScrollbarDragging && this.page == PAGE_WORK) {
            int max = perkMaxScroll();
            if (max <= 0) {
                this.perkScrollbarDragging = false;
            } else {
                int trackY = this.topPos + perkAreaTop() + 1;
                int trackH = perkAreaBottom() - perkAreaTop() - 2;
                int thumbH = Math.max(8, trackH * PERK_VISIBLE_ROWS / perkLogicalRows().size());
                updatePerkScrollFromMouse((int) mouseY, trackY, trackH, thumbH, max);
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            this.thoughtScrollbarDragging = false;
            this.perkScrollbarDragging = false;
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
        if (this.page == PAGE_WORK) {
            // 悬停在炼金药水弹层上：滚药水列表（固定 2 行 + 滚动条）
            if (this.alchPickerOpen
                    && currentProfession() == com.bmf.bmfstown.entity.profile.ProfessionType.ALCHEMIST) {
                var potions = com.bmf.bmfstown.client.ClientAlchemyCatalog.entries();
                int arows = (potions.size() + 7) / 8;
                int maxScroll = Math.max(0, arows - 2);
                int px = 4;
                int py = perkAreaBottom() + 2;
                int pw = this.imageWidth - 8;
                if (maxScroll > 0 && mouseX >= this.leftPos + px && mouseX < this.leftPos + px + pw
                        && mouseY >= this.topPos + py && mouseY < this.topPos + py + 88) {
                    this.alchScroll = Mth.clamp(this.alchScroll - (int) Math.signum(scrollY),
                            0, maxScroll);
                    return true;
                }
            }
            // 悬停在 perk 区上：滚 perk 树（固定高滚动菜单）
            if (mouseY >= this.topPos + perkAreaTop() && mouseY < this.topPos + perkAreaBottom()) {
                clampPerkScroll();
                this.perkScroll = Mth.clamp(this.perkScroll - (int) Math.signum(scrollY), 0, perkMaxScroll());
                return true;
            }
            // 其余区域：职业列表滚轮（职业超过 3 行可见上限时翻页）
            int max = Math.max(0, selectableProfessions().size() - VISIBLE_PROF_ROWS);
            this.workScroll = Mth.clamp(this.workScroll - (int) Math.signum(scrollY), 0, max);
            return true;
        }
        if (this.page == PAGE_TOWN) {
            int max = Math.max(0, currentRows().size() - MAX_VISIBLE);
            this.townScroll = Mth.clamp(this.townScroll - (int) Math.signum(scrollY), 0, max);
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


    // ==========================================================
    //  知识页（v0.11 学者）：书槽 + 已学列表（点击装备）
    // ==========================================================

    /** 知识页背景：深底书卷区（与想法页同语言）。 */
    private void renderKnowledgePageBg(GuiGraphics gfx) {
        gfx.fill(this.leftPos + 4, this.topPos + ResidentMenu.PANEL_Y + 2,
                this.leftPos + this.imageWidth - 4, this.topPos + ResidentMenu.PANEL_Y + 9 * ROW_H,
                0xFF373737);
    }

    /** 知识页已学列表的顶部（文字层与命中检测共用——防两处漂移错行）。 */
    private static int knowledgeListTop() {
        return ResidentMenu.PANEL_Y + 3 * ROW_H + 6;
    }

    /** 知识页命中检测：鼠标所在已学行下标（null = 不在任何行上）。 */
    private int[] knowledgeRowAt(double mouseX, double mouseY) {
        var snapshot = this.menu.getResident() != null
                ? this.menu.getResident().getArchive() : null;
        if (snapshot == null) {
            return null;
        }
        var learned = snapshot.learnedBooks();
        if (learned.isEmpty()) {
            return null;
        }
        int listX = this.leftPos + 4;
        int listW = this.imageWidth - 8;
        int rowTop = this.topPos + knowledgeListTop();
        int rel = (int) (mouseY - rowTop);
        if (mouseX >= listX && mouseX < listX + listW && rel >= 0 && rel < learned.size() * ROW_H) {
            return new int[]{rel / ROW_H};
        }
        return null;
    }

    /** 知识页内容：生效中的书 + 学识统计 + 已学列表（点击切换生效）。 */
    private void renderKnowledgePageText(GuiGraphics gfx, double mouseX, double mouseY) {
        Resident resident = this.menu.getResident();
        var snapshot = resident != null ? resident.getArchive() : null;
        if (snapshot == null) {
            return;
        }
        int textX = TEXT_PAD;
        int y = ResidentMenu.PANEL_Y + 4;

        // ---- 生效行：单选槽（v0.10.4 学识制：已学多本共存、同时生效一本） ----
        String equipped = snapshot.equippedBookId();
        String equippedName = equipped.isEmpty()
                ? I18n.get("gui.bmfstown.resident.knowledge.none")
                : specialBookName(equipped);
        gfx.drawString(this.font,
                I18n.get("gui.bmfstown.resident.knowledge.equipped", equippedName),
                textX, y, 0x303030, false);
        y += ROW_H;

        // ---- 统计行：可用学识 + 累计阅读（v0.10.4 学识制） ----
        gfx.drawString(this.font,
                I18n.get("gui.bmfstown.resident.knowledge.stats",
                        snapshot.literacy(), snapshot.totalBooksRead()),
                textX, y, 0x606060, false);
        y += ROW_H;

        // ---- 提示行 ----
        gfx.drawString(this.font,
                I18n.get("gui.bmfstown.resident.knowledge.hint"),
                textX, y, 0x606060, false);
        y += ROW_H + 2;

        // ---- 已学列表（点击切换生效；与 knowledgeListTop 对齐） ----
        var learned = snapshot.learnedBooks();
        if (learned.isEmpty()) {
            gfx.drawString(this.font,
                    I18n.get("gui.bmfstown.resident.knowledge.empty"),
                    textX, y, 0x808080, false);
            return;
        }
        int listY = knowledgeListTop();
        for (int i = 0; i < learned.size(); i++) {
            String id = learned.get(i);
            int rowY = listY + i * ROW_H;
            boolean equippedNow = id.equals(equipped);
            int[] row = knowledgeRowAt(mouseX, mouseY);
            boolean hover = row != null && row[0] == i && !equippedNow;
            String label = specialBookName(id)
                    + (equippedNow ? I18n.get("gui.bmfstown.resident.knowledge.equippedMark") : "");
            // 本方法在 translate(leftPos, topPos) 后的相对坐标系里绘制——
            // fill 不能再加 leftPos（否则高亮横飞出面板，用户 21:07 截图实锤）
            gfx.fill(4, rowY - 1, this.imageWidth - 4,
                    rowY + ROW_H - 2, hover ? 0xFFB8B8B8 : equippedNow ? 0xFFDCDCDC : 0x00000000);
            gfx.drawString(this.font, label, textX + 2, rowY, 0x303030, false);
        }
    }

    /** 特殊书显示名：登记表 lang 键（book.bmfstown.special.<id>.name），未登记退 id。 */
    private static String specialBookName(String id) {
        String key = "book.bmfstown.special." + id + ".name";
        String text = I18n.get(key);
        return text.equals(key) ? id : text;
    }

    /**
     * 客户端 perk 判别——<b>必须走快照</b>：{@code resident.hasPerk} 读
     * {@code growth}（服务端域对象，客户端恒空），用它判别在客户端
     * 永远 false（效率行"强壮/学者天赋"段消失的根因）。
     */
    private static boolean hasPerk(com.bmf.bmfstown.entity.domain.ResidentSnapshot snapshot,
            String node, String branch) {
        String choice = snapshot.perkChoice(
                com.bmf.bmfstown.entity.profile.Perks.ownerOf(node), node);
        return branch.equals(choice);
    }

    /**
     * 当前生效的已学书提供的某类效果加成（%）——客户端镜像
     * {@code Resident.learnedEffectBonus}：单选槽（生效中的那本已学书）。
     * 登记表在 mod 构造时双侧填充，客户端可直接读。
     */
    private static int equippedLearnedBonus(com.bmf.bmfstown.entity.domain.ResidentSnapshot snapshot,
            com.bmf.bmfstown.book.SpecialBookEffect.Type type) {
        String id = snapshot.equippedBookId();
        if (id.isEmpty() || !snapshot.learnedBooks().contains(id)) {
            return 0;
        }
        var book = com.bmf.bmfstown.book.SpecialBookRegistry.byId(id);
        if (book == null) {
            return 0;
        }
        int sum = 0;
        for (var effect : book.effects()) {
            if (effect.type() == type) {
                sum += effect.amount();
            }
        }
        return sum;
    }

    /**
     * 当前模式的 WIP 书内容（镜像服务端 bookprogress 命令的取槽口径）：
     * 抄书模式看抄写槽、写书模式看写作槽；当前槽空则看另一个槽。
     */
    private com.bmf.bmfstown.book.BookContent activeWipContent() {
        if (this.menu.getResident() == null) {
            return null;
        }
        boolean copying = this.menu.getResident().getWorkMode()
                == com.bmf.bmfstown.entity.WorkMode.COPY;
        var wip = this.menu.getSlot(copying
                ? ResidentMenu.COPY_SLOT_INDEX : ResidentMenu.WIP_SLOT_INDEX).getItem();
        if (wip.isEmpty()) {
            wip = this.menu.getSlot(copying
                    ? ResidentMenu.WIP_SLOT_INDEX : ResidentMenu.COPY_SLOT_INDEX).getItem();
        }
        return wip.get(com.bmf.bmfstown.ModDataComponents.BOOK_CONTENT.get());
    }

    /** 乘数显示格式：1.5 → "1.5"，1.25 → "1.25"，1.0 → "1"（不出 1.2500000001）。 */
    private static String fmtFactor(double factor) {
        double rounded = Math.round(factor * 100.0) / 100.0;
        if (rounded == Math.floor(rounded)) {
            return String.valueOf((long) rounded);
        }
        return String.valueOf(rounded);
    }

    /**
     * 想法行渲染：arg 协议 "idle:<种子>" → 客户端按种子现译书名填 %s
     * （服务端不存成品文本，双语自动）；无参数想法照常直译。
     */
    private static String renderThoughtLine(ModNetworking.MoodThought t) {
        if (t.arg() != null && t.arg().startsWith("idle:")) {
            try {
                long seed = Long.parseLong(t.arg().substring("idle:".length()));
                return I18n.get(t.key(),
                        com.bmf.bmfstown.book.BookLexicon.title(seed).getString());
            } catch (NumberFormatException ignored) {
                // 坏数据退回直译
            }
        }
        if (t.arg() != null && t.arg().startsWith("book:")) {
            // 特殊书想法：按登记表 lang 键现译书名（"他学会了《X》"）
            return I18n.get(t.key(),
                    specialBookName(t.arg().substring("book:".length())));
        }
        return t.arg() != null && !t.arg().isEmpty()
                ? I18n.get(t.key(), t.arg()) : I18n.get(t.key());
    }
}
