package com.bmf.bmfstown.menu;

import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.network.ModNetworking;
import com.bmf.bmfstown.entity.profile.ProfessionType;
import com.bmf.bmfstown.entity.profile.ResidentInventory;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

/**
 * 镇民面板的容器菜单。
 *
 * <h2>这个类在做什么</h2>
 * Minecraft 的界面（GUI）分两层：
 * <ul>
 *   <li><b>Menu</b>（本类）—— 逻辑层，只管"有哪些格子、点击怎么处理"，
 *       <b>服务端和客户端各有一份</b>，靠 {@code clickMenuButton} / 槽位同步保持一致</li>
 *   <li><b>Screen</b>（{@link com.bmf.bmfstown.client.ResidentScreen}）—— 绘制层，
 *       只在客户端存在，负责画背景、文字、图标</li>
 * </ul>
 * 分开的理由是：服务端没有图形界面，但必须独立校验每一次点击是否合法
 * （否则改客户端就能无限刷物品）。
 *
 * <h2>槽位布局（方案 A）</h2>
 * <pre>
 *   面板左上角 (0,0)
 *   ┌──────────────────────────────────────┐
 *   │  装备行: [主手][副手][头][胸][腿][脚]  │  ← y = 18
 *   │  背包 3×9:                            │  ← y = 44
 *   │    [0][1][2][3][4][5][6][7][8]        │
 *   │    [9] ...                     [17]   │
 *   │    [18] ...                    [26]   │
 *   │  ── 状态文字：生命/饥饿/心情/好感… ──  │  ← y = 106（6 行）
 *   │  玩家背包 3×9 + 快捷栏                │  ← y = 176 / 234
 *   └──────────────────────────────────────┘
 * </pre>
 *
 * <p>装备行 6 格从左到右是：主手、副手、头盔、胸甲、护腿、靴子。
 * 顺序按"战斗相关在前"排，因为卫士最常用的就是武器。
 *
 * <p><b>历史教训</b>：最初背包 y=84、状态文字 y=102、玩家背包 y=134，
 * 三段互相重叠——物品画在文字上、你的背包和镇民背包混在一起，
 * 看起来"哪里都能放曲奇"。所有 y 现在都由常量链推导，不再手填死数。
 */
public class ResidentMenu extends AbstractContainerMenu {

    // ==========================================================
    //  布局常量（Screen 里也要用，所以是 public）
    // ==========================================================

    /** 面板主区域左上角的 x（相对 GUI 左上角）。 */
    public static final int PANEL_X = 8;
    /**
     * 面板主区域左上角的 y（= 装备行的 y）。
     *
     * <p>v0.5 取 16：面板标题删除（名字挪进状态区、页签占面板顶部）后
     * 内容整体上提，给底部省垂直空间——整个 GUI 高度曾因此超出小窗口。
     */
    public static final int PANEL_Y = 16;

    /** 装备槽每格边长。 */
    public static final int SLOT_SIZE = 18;

    /** 镇民背包第一行的 y。装备行占 18px，留 6px 间隔。 */
    public static final int BAG_Y = PANEL_Y + SLOT_SIZE + 6;

    /** 状态文字（名字/生命/饥饿/心情…）第一行的 y。背包 3 行结束于 94，留 6px 间隔。 */
    public static final int STATS_Y = BAG_Y + 3 * SLOT_SIZE + 6;

    /**
     * 状态文字的行数。
     *
     * <p>v0.5 是 8 行：名字（v0.5 从面板标题挪进来，和数据放在一起）
     * + 生命/饥饿/心情/好感/专长/喜好/归属。
     * 归属绑定入口在第二页的"归属"列表，见 {@link #BTN_ASSIGN_BASE}。
     */
    public static final int STAT_LINES = 8;

    /** 玩家背包第一行的 y。状态文字占 STAT_LINES 行，留 6px 间隔。 */
    public static final int PLAYER_INV_Y = STATS_Y + STAT_LINES * 10 + 6;

    /** 玩家快捷栏的 y。玩家背包 3 行之后空 4px。 */
    public static final int HOTBAR_Y = PLAYER_INV_Y + 3 * SLOT_SIZE + 4;

    /**
     * 装备槽在 {@code slots} 列表里的起始索引。
     * 前 6 个是装备，接下来 27 个是背包，最后 36 个是玩家背包。
     */
    public static final int EQUIP_START = 0;
    public static final int BAG_START = 6;
    public static final int PLAYER_INV_START = BAG_START + ResidentInventory.SIZE;

    /** 装备行的顺序。枚举顺序即视觉顺序。 */
    public static final EquipmentSlot[] EQUIP_ORDER = {
            EquipmentSlot.MAINHAND,
            EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET
    };

    /** 整个 GUI 宽度，Screen 画背景用。 */
    public static final int GUI_WIDTH = 176;    /**
     * 整个 GUI 高度。
     * 快捷栏底边 = HOTBAR_Y + 18，再留 4px 边距。
     * 由常量链推导，不手填死数——改任何一个间距都会自动跟着变。
     */
    public static final int GUI_HEIGHT = HOTBAR_Y + SLOT_SIZE + 4;

    /**
     * 玩家背包区贴图的横向起点（相对 GUI 左边）。
     *
     * <p>原版 inventory.png 里背包凹槽从贴图 x=7 开始，而格子中心在 GUI x=8+18n。
     * 我们的格子就摆在 {@link #PANEL_X}=8，所以贴图要往左挪 1px 去对齐格子，
     * <b>而不是</b>把格子往右挪——格子坐标一改，点击判定和显示就会错位。
     */
    public static final int PLAYER_AREA_X = PANEL_X;

    // ==========================================================
    //  数据槽（状态数值的实时同步）
    // ==========================================================

    /**
     * 数据槽索引：血量（半颗心为单位，×2 存整数）。
     *
     * <p>为什么乘 2：{@code ContainerData} 只能传 int，而血量是 float
     * （还有 0.5 的奇数档）。×2 之后 0.5 也丢不了精度，
     * 客户端显示时再除回去。
     */
    public static final int DATA_HEALTH = 0;
    /** 数据槽索引：饥饿值（0~20）。 */
    public static final int DATA_HUNGER = 1;
    /** 数据槽索引：心情（0~100）。 */
    public static final int DATA_MOOD = 2;
    /** 数据槽索引：好感度（0~100）。 */
    public static final int DATA_AFFECTION = 3;
    /** 数据槽索引：工作模式（{@code WorkMode.ordinal()}：0 无 / 1 跟随 / 2 站岗）。 */
    public static final int DATA_WORKMODE = 4;
    /** 数据槽总数。客户端和服务端必须一致，否则索引会错位。 */
    public static final int DATA_COUNT = 5;

    // ==========================================================
    //  菜单按钮（归属城镇，v0.5）
    // ==========================================================

    /** 按钮编号：解除归属，镇民回到"自由身"。也是列表的第 0 项。 */
    public static final int BTN_UNASSIGN = 0;

    // ==========================================================
    //  客户端页状态（配合 Slot.isActive 做真正的"页面开关"）
    // ==========================================================

    /** 页编号：资料页（装备 + 背包 + 状态）。 */
    public static final int PAGE_INFO = 0;
    /** 页编号：归属页（城镇列表）。 */
    public static final int PAGE_TOWN = 1;
    /** 页编号：工作页（职业分配 + 战士工作模式）。 */
    public static final int PAGE_WORK = 2;

    /**
     * 客户端当前页（0=资料页）。
     *
     * <h2>为什么是 static、为什么服务端永远是 0</h2>
     * {@code Slot.isActive()} 里没有回指 Menu 的通道，槽位要感知页状态
     * 只能走静态量。它<b>只代表客户端的视觉状态</b>：服务端从不写它，
     * 永远停在资料页 → 服务端所有槽位永远激活，逻辑零影响；
     * 客户端切到归属页时，上板槽位 {@code isActive()} 返回 false，
     * 原版会跳过它们的渲染、悬停高亮、tooltip 和点击——
     * 是"关闭第一页"而不是"把第一页盖住"。
     *
     * <p>同一时刻只有一个容器界面在前台，静态量不会串台。
     */
    public static int CLIENT_PAGE = PAGE_INFO;

    /**
     * 按钮编号基准：分配城镇 = 100 + 城镇下标。
     *
     * <p>为什么用下标而不是 UUID：容器按钮协议只能传 int。
     * 下标能这么用的前提是<b>两边的列表顺序一致</b>——服务端和客户端
     * 都按 {@code allTowns()}（按创建时间排序）+ 本维度过滤来列，
     * 同一份数据源跑同一段排序，顺序天然一致。
     *
     * <p>防御：下标越界时服务端静默忽略（列表可能刚变过），
     * 不会崩也不会绑错镇。
     */
    public static final int BTN_ASSIGN_BASE = 100;

    // ==========================================================
    //  菜单按钮（住宅分配）
    // ==========================================================

    /** 解除住宅绑定（住宅列表第 0 项）。 */
    public static final int BTN_HOUSING_UNASSIGN = 300;

    /**
     * 按钮编号基准：分配住宅 = 400 + 列表下标。
     *
     * <p>下标口径：客户端列的是"当前归属城镇的全部住宅区，按 {@code Town.zones(HOUSING)}
     * 的原顺序"。服务端必须用同一份数据、同一个顺序重新取一遍，
     * 所以服务端不接收 UUID，只接收下标——和城镇分配同一套约定。
     */
    public static final int BTN_HOUSING_BASE = 400;

    // ==========================================================
    //  菜单按钮（工作页）
    // ==========================================================

    /**
     * 按钮编号：跟随开关。开着 → 关（回 NONE）；关着 → 开（FOLLOW，
     * 目标玩家 = 点击按钮的玩家）。
     */
    public static final int BTN_FOLLOW = 600;

    /**
     * 按钮编号：站岗（选取位置）。服务端对它<b>只做职业校验</b>——
     * 真正进入"选位置"状态是客户端本地行为（关闭界面 + 红色预览），
     * 玩家右键确认后走 {@code WorkGuardPostPayload} 由服务端落账。
     */
    public static final int BTN_GUARD_POST = 601;

    /**
     * 按钮编号基准：分配职业 = 700 + {@code ProfessionType.values()} 下标。
     * 客户端工作页的职业列表与服务端都按 {@code values()} 原顺序列，
     * 下标天然一致（与城镇分配同一套约定）。
     */
    public static final int BTN_PROFESSION_BASE = 700;

    /**
     * 客户端点了按钮，服务端在这里处理。
     *
     * <p>原版机制：客户端发 {@code ServerboundContainerButtonClickPacket}
     * （由 {@code MultiPlayerGameMode.handleInventoryButtonClick} 触发），
     * 服务端调进本方法。
     *
     * <h2>为什么按钮操作天然安全</h2>
     * 服务端自己查城镇列表、自己按下标取镇，客户端只发一个 int——
     * 没有任何"客户端说了算"的数据。列表刚变过导致下标错位时，
     * 最坏结果是"绑到了列表里另一个真实存在的城镇"，绑不出不存在的东西。
     *
     * @return true 表示按钮被处理（原版约定；返回 false 会踢出玩家，
     *         所以所有未知按钮号都返回 true 并静默忽略）
     */
    @Override
    public boolean clickMenuButton(net.minecraft.world.entity.player.Player player, int id) {
        if (this.resident == null) {
            return true;
        }
        // 防御：只在服务端跑真逻辑
        if (player.level().isClientSide || !(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return true;
        }
        com.bmf.bmfstown.town.TownSaveData data = com.bmf.bmfstown.town.TownSaveData.get(level);
        // 只给本维度的城镇——跨维度的城镇锁不住镇民，没有意义
        String dim = level.dimension().location().toString();
        java.util.List<com.bmf.bmfstown.town.Town> towns = new java.util.ArrayList<>();
        for (com.bmf.bmfstown.town.Town t : data.allTowns()) {
            if (t.dimension().equals(dim)) {
                towns.add(t);
            }
        }

        if (id == BTN_UNASSIGN) {
            if (this.resident.getProfile().townId() == null) {
                return true; // 本来就自由身，别刷屏
            }
            // 城镇解绑 → 住宅绑定同时失效（住宅区隶属于城镇）
            this.resident.setProfile(this.resident.getProfile()
                    .withTownId(null).withHousing(null, 0L));
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "msg.bmfstown.resident.unbound", this.resident.getName().getString()), true);
            return true;
        }

        // ---- 工作页按钮（600/700+）：必须放在住宅(400+)判断之前，
        // 否则会被 `id >= BTN_HOUSING_BASE` 分支吞掉 ----
        if (id == BTN_FOLLOW) {
            if (!this.resident.isWarrior()) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "msg.bmfstown.work.need_warrior"), true);
                return true;
            }
            if (this.resident.getWorkMode() == com.bmf.bmfstown.entity.WorkMode.FOLLOW) {
                this.resident.setWorkMode(com.bmf.bmfstown.entity.WorkMode.NONE, null, null);
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "msg.bmfstown.work.follow_off", this.resident.getName().getString()), true);
            } else {
                this.resident.setWorkMode(com.bmf.bmfstown.entity.WorkMode.FOLLOW,
                        player.getUUID(), null);
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "msg.bmfstown.work.follow_on", this.resident.getName().getString()), true);
            }
            return true;
        }

        if (id == BTN_GUARD_POST) {
            if (!this.resident.isWarrior()) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "msg.bmfstown.work.need_warrior"), true);
                return true;
            }
            // 职业合法：客户端已经在本地进入"选取岗哨位置"状态，
            // 服务端等 WorkGuardPostPayload 确认包再落账，这里无事可做。
            return true;
        }

        if (id >= BTN_PROFESSION_BASE) {
            int index = id - BTN_PROFESSION_BASE;
            ProfessionType[] all = ProfessionType.values();
            if (index < 0 || index >= all.length) {
                return true; // 列表刚变过，下标失效：忽略
            }
            ProfessionType type = all[index];
            if (this.resident.getProfession() == type) {
                return true; // 重复点不算错
            }
            this.resident.setProfile(this.resident.getProfile().withProfession(type));
            // 换成非战士职业 → 战士工作模式一并作废（岗位跟着职业走）
            if (type != ProfessionType.WARRIOR) {
                this.resident.setWorkMode(com.bmf.bmfstown.entity.WorkMode.NONE, null, null);
            }
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "msg.bmfstown.work.profession_set",
                    this.resident.getName().getString(),
                    net.minecraft.network.chat.Component.translatable(type.displayName()).getString()), true);
            return true;
        }

        // 注意顺序：住宅按钮（300/400+）数字上比城镇按钮（100+）大，
        // 必须先判住宅范围，否则会被下面的城镇分支吞掉——
        // 400-100=300 远超城镇数，静默返回，玩家就看到"点了没反应"。
        if (id >= BTN_HOUSING_BASE) {
            java.util.List<com.bmf.bmfstown.town.TownZone> zones = housingZonesOf(level);
            int index = id - BTN_HOUSING_BASE;
            if (index < 0 || index >= zones.size()) {
                return true; // 列表刚变过，下标失效：忽略
            }
            com.bmf.bmfstown.town.TownZone zone = zones.get(index);
            assignHousing(player, level, zone);
            return true;
        }

        if (id == BTN_HOUSING_UNASSIGN) {
            if (this.resident.getProfile().housingZoneId() == null) {
                return true; // 本来就没绑，别刷屏
            }
            this.resident.setProfile(this.resident.getProfile().withHousing(null, 0L));
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "msg.bmfstown.housing.unassigned", this.resident.getName().getString()), true);
            ModNetworking.sendHandbookSync((net.minecraft.server.level.ServerPlayer) player, false);
            return true;
        }

        if (id >= BTN_ASSIGN_BASE) {
            int index = id - BTN_ASSIGN_BASE;
            if (index >= towns.size()) {
                return true; // 列表刚变过，下标失效：忽略
            }
            com.bmf.bmfstown.town.Town town = towns.get(index);
            // 换城镇 → 原住宅失效（住宅区隶属于城镇），一并清掉
            this.resident.setProfile(this.resident.getProfile()
                    .withTownId(town.id()).withHousing(null, 0L));
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "msg.bmfstown.resident.bound",
                    this.resident.getName().getString(), town.name()), true);
            return true;
        }

        return true;
    }

    /**
     * 当前镇民所属城镇的住宅区列表（顺序必须与客户端 {@code housingRows()} 一致）。
     *
     * <p>客户端取的是 {@code Town.zones(HOUSING)} 的原顺序，这里照搬，
     * 不做任何额外排序——两边只要用同一个方法取，顺序就必然一致。
     */
    private java.util.List<com.bmf.bmfstown.town.TownZone> housingZonesOf(
            net.minecraft.server.level.ServerLevel level) {
        java.util.UUID townId = this.resident.getProfile().townId();
        if (townId == null) {
            return java.util.List.of();
        }
        com.bmf.bmfstown.town.Town town =
                com.bmf.bmfstown.town.TownSaveData.get(level).findTown(townId.toString());
        if (town == null) {
            return java.util.List.of();
        }
        return town.zones(com.bmf.bmfstown.town.TownWandMode.HOUSING);
    }

    /**
     * 把镇民分配到某个住宅区。
     *
     * <p>校验顺序有讲究：先看"是不是本来就在这儿"（重复点不算错），
     * 再看容量。容量按"床数"算——没有床的区域住不了人，
     * 这符合"几张床睡几个人"的直觉。
     */
    private void assignHousing(net.minecraft.world.entity.player.Player player,
                               net.minecraft.server.level.ServerLevel level,
                               com.bmf.bmfstown.town.TownZone zone) {
        java.util.UUID current = this.resident.getProfile().housingZoneId();
        if (zone.id().equals(current)) {
            return; // 已经住这儿了，静默成功
        }

        int capacity = com.bmf.bmfstown.town.HousingZones.capacity(level, zone);
        if (capacity <= 0) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "msg.bmfstown.housing.no_beds", zone.name()), false);
            // 排障：床扫不到多半是区域范围和床的位置对不上
            //（比如区域是平铺的一格高，床在它上面一格）。把范围打进日志。
            com.mojang.logging.LogUtils.getLogger().info(
                    "[BmfTown] 「{}」扫不到床：区域 {}~{}",
                    zone.name(), zone.min().toShortString(), zone.max().toShortString());
            return;
        }
        int occupants = com.bmf.bmfstown.town.HousingZones.occupantCount(level, zone);
        if (occupants >= capacity) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "msg.bmfstown.housing.full", zone.name(), capacity), false);
            return;
        }

        // 时间戳用真实时间而不是游戏 tick：游戏 tick 读档会重置，
        // 而"谁先住进来"必须跨存档稳定（超员踢人靠它排序）。
        this.resident.setProfile(this.resident.getProfile()
                .withHousing(zone.id(), System.currentTimeMillis()));
        player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                "msg.bmfstown.housing.assigned",
                this.resident.getName().getString(), zone.name(),
                occupants + 1, capacity), true);
        // 入住数变了 → 推一份新手册数据，客户端列表的 (n/床数) 立刻跟上
        ModNetworking.sendHandbookSync((net.minecraft.server.level.ServerPlayer) player, false);
    }

    // ==========================================================
    //  字段
    // ==========================================================

    /** 对应的镇民。客户端那份可能为 null（见构造说明）。 */
    @Nullable
    private final Resident resident;

    /**
     * 服务端专用的容器代理，让装备槽的读写落到实体身上。
     *
     * <p>客户端为 null —— 客户端不需要它，因为点击会发回服务端执行。
     */
    @Nullable
    private final Container equipmentContainer;

    /**
     * 状态数值的数据槽。
     *
     * <p>服务端这份实时从实体上读；客户端这份是个哑容器，
     * 原版同步包（{@code ClientboundContainerSetDataPacket}）会把
     * 变化写进来。见 {@link #layout} 里的说明。
     */
    private final ContainerData residentData;

    /**
     * 读一个状态数值。Screen 绘制状态行时<b>必须走这里</b>，
     * 不要直接读实体——客户端的实体档案只在数值变化时同步，
     * 而数据槽由 {@code broadcastChanges} 每 tick 比对推送，
     * 是面板上唯一保证实时的通道。
     */
    public int getStat(int index) {
        return this.residentData.get(index);
    }

    // ==========================================================
    //  构造
    // ==========================================================

    /**
     * 服务端用：拿得到实体，装备槽直接绑到实体身上。
     */
    public ResidentMenu(int containerId, Inventory playerInventory, Resident resident) {
        super(ModMenus.RESIDENT.get(), containerId);
        this.resident = resident;
        this.equipmentContainer = new EquipmentContainer(resident);
        this.residentData = new ContainerData() {
            @Override
            public int get(int index) {
                // 每 tick 都会被 broadcastChanges 轮询，必须读"当前值"
                return switch (index) {
                    case DATA_HEALTH -> Mth.floor(resident.getHealth() * 2.0F);
                    case DATA_HUNGER -> resident.getProfile().hunger();
                    case DATA_MOOD -> resident.getProfile().mood();
                    case DATA_AFFECTION -> resident.getProfile().affection();
                    case DATA_WORKMODE -> resident.getWorkMode().ordinal();
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
                // 数据槽是只读视图——状态数值由服务端逻辑改，UI 不回写
            }

            @Override
            public int getCount() {
                return DATA_COUNT;
            }
        };
        this.layout(playerInventory);
    }

    /**
     * 客户端用：实体可能还没同步过来，或者已经走远了。
     *
     * <p>这里传 {@code null} 是刻意的——客户端的槽位只用来画图标，
     * 真正的内容由服务端通过槽位同步包推送过来。
     * 如果这里硬要拿实体，会在实体未加载时抛 NPE。
     */
    public ResidentMenu(int containerId, Inventory playerInventory) {
        super(ModMenus.RESIDENT.get(), containerId);
        this.resident = null;
        this.equipmentContainer = null;
        this.residentData = new SimpleContainerData(DATA_COUNT);
        this.layout(playerInventory);
    }

    /**
     * 摆格子。服务端和客户端走同一份代码，保证索引完全一致
     * （索引不一致会导致点 A 格动了 B 格，是最经典的 GUI bug）。
     */
    private void layout(Inventory playerInventory) {
        // ---- 装备行：6 格横排 ----
        for (int i = 0; i < EQUIP_ORDER.length; i++) {
            EquipmentSlot type = EQUIP_ORDER[i];
            int x = PANEL_X + i * SLOT_SIZE;
            int y = PANEL_Y;
            this.addSlot(new EquipmentSlotSlot(this.equipmentContainer, i, x, y, type));
        }

        // ---- 镇民背包：3 行 × 9 列（ResidentBagSlot：归属页整体失活） ----
        Container bag = this.resident != null ? this.resident.getInventory() : EMPTY_BAG;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int index = row * 9 + col;
                this.addSlot(new ResidentBagSlot(bag, index,
                        PANEL_X + col * SLOT_SIZE,
                        BAG_Y + row * SLOT_SIZE));
            }
        }

        // ---- 玩家背包：3 行 × 9 列（中间隔着状态文字区，坐标见 PLAYER_INV_Y） ----
        // PlayerInvSlot：只在资料页激活——归属/工作页整段收起背包腾出空间，
        // 槽位必须真正 isActive()==false（而不是只不画），否则能对着看不见的格子点击
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new PlayerInvSlot(playerInventory,
                        col + row * 9 + 9,
                        PANEL_X + col * SLOT_SIZE,
                        PLAYER_INV_Y + row * SLOT_SIZE));
            }
        }

        // ---- 玩家快捷栏：1 行 × 9 列 ----
        for (int col = 0; col < 9; col++) {
            this.addSlot(new PlayerInvSlot(playerInventory, col,
                    PANEL_X + col * SLOT_SIZE, HOTBAR_Y));
        }

        // ---- 登记数据槽 ----
        // 服务端把"实时读实体"的那份登记进来；客户端把哑容器登记进来。
        // 两边 getCount() 一致（都是 DATA_COUNT），索引才不会错位。
        // broadcastChanges 会逐槽比对、变了就走
        // ClientboundContainerSetDataPacket 推给客户端，
        // 客户端 AbstractContainerMenu.setData 写回 residentData——
        // 这就是熔炉"烧炼进度实时走"用的同一套机制。
        this.addDataSlots(this.residentData);
    }

    // ==========================================================
    //  同步
    // ==========================================================

    /**
     * 服务端每 tick 调用，把槽位与数据槽的变化推给客户端。
     *
     * <h2>为什么这里要显式处理</h2>
     * 原版 {@code AbstractContainerMenu} 的同步依赖两点：
     * <ol>
     *   <li>槽位数量在一开始就登记进 {@code remoteSlots}</li>
     *   <li>{@code broadcastChanges()} 逐格比较后只发变化的那些</li>
     * </ol>
     *
     * <p>我们的镇民背包是一个普通 {@code Container}，不属于任何方块实体，
     * 所以<b>没有任何 {@code ContainerSynchronizer} 在监听它</b>。
     * 少了这个监听，"服务端吃掉了曲奇"这件事就永远传不到客户端——
     * 玩家看到的就是"背包里的曲奇一个不少、饥饿值纹丝不动"。
     *
     * <p>这里直接调 {@code super} 就够了：{@code Slot} 自身实现了
     * {@code RemoteSlot}，{@code AbstractContainerMenu.broadcastChanges}
     * 会拿 {@code slot.getItem()} 与上次发出去的值比对，变了才发。
     * <b>数据槽（生命/饥饿/心情/好感）也走同一次 super</b>：
     * {@code addDataSlots} 登记过的值会逐项和上次发送值比对，
     * 变了就走 {@code ClientboundContainerSetDataPacket}。
     * 我们不需要自己算差异。
     *
     * <p>为什么不写成空方法（只调 super 看着像没做事）：
     * 留着它是为了<b>标注这个类的同步边界</b>——
     * 如果有人把这个方法删掉并"顺手"改成 {@code return}，
     * 整个 UI 就会退化成"打开那一刻的静态快照"。
     */
    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
    }

    // ==========================================================
    //  查询
    // ==========================================================

    @Nullable
    public Resident getResident() {
        return this.resident;
    }

    /**
     * 玩家能不能继续看这个面板。
     *
     * <p>服务端每次点击都会调它。返回 false 会自动关界面并丢弃光标上的物品。
     * 这里判距离和存活——他死了或者走远了，面板就该关。
     */
    @Override
    public boolean stillValid(Player player) {
        if (this.resident == null) {
            // 客户端：交给服务端判定，本地一律放行（否则会误关）
            return true;
        }
        if (!this.resident.isAlive()) {
            return false;
        }
        return player.isCloseEnough(this.resident, 8.0D);
    }

    // ==========================================================
    //  快速移动（Shift + 点击）
    // ==========================================================

    /**
     * Shift+左键点击时，把那一格的物品搬到"另一区"。
     *
     * <p>三段逻辑：
     * <ul>
     *   <li>点在【镇民装备或背包】上 → 搬进玩家背包</li>
     *   <li>点在【玩家背包】上 → 优先搬进镇民背包（食物/杂物），
     *       如果那件东西是装备，优先放进对应装备槽</li>
     * </ul>
     *
     * <p>返回 {@link ItemStack#EMPTY} 表示"全部搬完了"。
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack original = slot.getItem();
        ItemStack copy = original.copy();

        boolean inResidentArea = index < PLAYER_INV_START;

        if (inResidentArea) {
            // 镇民区域 → 玩家背包（从玩家背包第一格开始找位置）
            if (!this.moveItemStackTo(original, PLAYER_INV_START, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // 玩家背包 → 先试装备槽（如果是能穿的），再试镇民背包
            boolean moved = false;
            if (this.isEquipment(original)) {
                moved = this.moveItemStackTo(original, EQUIP_START, EQUIP_START + EQUIP_ORDER.length, false);
            }
            if (!moved) {
                moved = this.moveItemStackTo(original, BAG_START, PLAYER_INV_START, false);
            }
            if (!moved) {
                return ItemStack.EMPTY;
            }
        }

        if (original.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    /** 粗略判断"这是不是装备"。真正的槽位过滤在 {@link EquipmentSlotSlot} 里。 */
    private boolean isEquipment(ItemStack stack) {
        return stack.is(Items.LEATHER_HELMET)
                || stack.is(Items.IRON_HELMET)
                || stack.is(Items.GOLDEN_HELMET)
                || stack.is(Items.DIAMOND_HELMET)
                || stack.is(Items.NETHERITE_HELMET)
                || stack.is(Items.TURTLE_HELMET)
                || stack.is(Items.LEATHER_CHESTPLATE)
                || stack.is(Items.IRON_CHESTPLATE)
                || stack.is(Items.GOLDEN_CHESTPLATE)
                || stack.is(Items.DIAMOND_CHESTPLATE)
                || stack.is(Items.NETHERITE_CHESTPLATE)
                || stack.is(Items.LEATHER_LEGGINGS)
                || stack.is(Items.IRON_LEGGINGS)
                || stack.is(Items.GOLDEN_LEGGINGS)
                || stack.is(Items.DIAMOND_LEGGINGS)
                || stack.is(Items.NETHERITE_LEGGINGS)
                || stack.is(Items.LEATHER_BOOTS)
                || stack.is(Items.IRON_BOOTS)
                || stack.is(Items.GOLDEN_BOOTS)
                || stack.is(Items.DIAMOND_BOOTS)
                || stack.is(Items.NETHERITE_BOOTS)
                || stack.is(Items.WOODEN_SWORD)
                || stack.is(Items.STONE_SWORD)
                || stack.is(Items.IRON_SWORD)
                || stack.is(Items.GOLDEN_SWORD)
                || stack.is(Items.DIAMOND_SWORD)
                || stack.is(Items.NETHERITE_SWORD)
                || stack.is(Items.WOODEN_AXE)
                || stack.is(Items.STONE_AXE)
                || stack.is(Items.IRON_AXE)
                || stack.is(Items.GOLDEN_AXE)
                || stack.is(Items.DIAMOND_AXE)
                || stack.is(Items.NETHERITE_AXE)
                || stack.is(Items.BOW)
                || stack.is(Items.CROSSBOW)
                || stack.is(Items.TRIDENT)
                || stack.is(Items.SHIELD);
    }

    /**
     * 只在资料页可用的玩家槽位。
     *
     * <p>归属/工作页把面板下半段收掉腾给 perk 等内容——槽位必须
     * {@code isActive() == false}，vanilla 在渲染、悬停、tooltip、点击
     * 分发四条路径上都会检查它，"只不画"防不住隐形点击（见切页注释）。
     */
    private static class PlayerInvSlot extends Slot {
        PlayerInvSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean isActive() {
            return CLIENT_PAGE == PAGE_INFO;
        }
    }

    /** 客户端占位背包，保证两边 {@code slots} 数量一致。 */
    private static final Container EMPTY_BAG = new Container() {
        @Override
        public int getContainerSize() {
            return ResidentInventory.SIZE;
        }

        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public ItemStack getItem(int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
        }

        @Override
        public void setChanged() {
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public void clearContent() {
        }
    };
}
