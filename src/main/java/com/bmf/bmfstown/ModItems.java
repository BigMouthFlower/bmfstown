package com.bmf.bmfstown;

import com.bmf.bmfstown.town.TownHandbookItem;
import com.bmf.bmfstown.town.TownWandItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 物品注册。
 *
 * <p>{@link DeferredRegister} 的作用：把「声明」推迟到注册表就绪的那一刻执行。
 * 你在这里写的只是"我要注册一个叫 X 的东西"，真正 {@code new Item()} 发生在
 * NeoForge 调用 register 的时候。所以绝不要在静态块里直接 new 一个 Item 然后
 * 塞进某个集合——那样的物品不属于注册表，会被游戏忽略。
 */
public final class ModItems {

    /** 物品注册器。DeferredRegister.Items 是 1.21 提供的便捷子类。 */
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(BmfTown.MOD_ID);

    /**
     * 占位物品：城镇凭证。
     *
     * <p>将来会被「城镇核心」之类的功能性方块替代。
     */
    public static final DeferredItem<Item> TOWN_CHARTER =
            ITEMS.registerSimpleItem("town_charter", new Item.Properties());

    /**
     * 镇民刷怪蛋。
     *
     * <p>1.21.1 的 {@link SpawnEggItem} 构造函数是四参数：
     * {@code (EntityType<? extends Mob>, int backgroundColor, int highlightColor, Item.Properties)}。
     * 后两个是 24 位 RGB 颜色，分别作为蛋的底色与斑点色。
     *
     * <p>配色：0xE8D9B0 米黄（羊皮纸）+ 0x6B4F35 深褐（墨迹），
     * 与「城镇凭证」的视觉风格一致。
     */
    public static final DeferredItem<SpawnEggItem> RESIDENT_SPAWN_EGG =
            ITEMS.registerItem("resident_spawn_egg",
                    props -> new SpawnEggItem(ModEntities.RESIDENT.get(), 0xE8D9B0, 0x6B4F35, props),
                    new Item.Properties());

    /**
     * 镇界魔杖。
     *
     * <p>所有区域类型（城镇/就餐区/住宅/工作区）共用这一根杖，
     * 靠 Alt+滚轮切模式区分。模式与选点存在物品数据组件上
     * （见 {@link ModDataComponents}），一根杖一份独立状态。
     *
     * <p>stacksTo(1)：一根杖就是一份状态，叠放会让"选区"概念混乱。
     */
    public static final DeferredItem<TownWandItem> TOWN_WAND =
            ITEMS.registerItem("town_wand",
                    props -> new TownWandItem(props),
                    new Item.Properties().stacksTo(1));

    /**
     * 城镇手册：右键打开城镇列表 UI（查看/改名/删除）。
     *
     * <p>手册与魔杖职责分离：魔杖管"画"，手册管"管"。
     */
    public static final DeferredItem<TownHandbookItem> TOWN_HANDBOOK =
            ITEMS.registerItem("town_handbook",
                    props -> new TownHandbookItem(props),
                    new Item.Properties().stacksTo(1));

    /**
     * 挂历：镇民历的实体显示设备（mod 第一个方块！）。
     * 贴墙/平铺/吸顶放置，右键在 actionbar 报镇民历日期。
     */
    public static final DeferredItem<net.minecraft.world.item.BlockItem> WALL_CALENDAR =
            ITEMS.registerSimpleBlockItem(com.bmf.bmfstown.block.ModBlocks.WALL_CALENDAR);

    /**
     * 镇公告栏（v0.16 沉浸层 #57）：闲逛随机发现的非常规事件公示板，
     * 玩家侧唯一的恐怖线进度窗口（右键看最近公告）。
     */
    public static final DeferredItem<net.minecraft.world.item.BlockItem> BULLETIN_BOARD =
            ITEMS.registerSimpleBlockItem(com.bmf.bmfstown.block.ModBlocks.BULLETIN_BOARD);

    /**
     * 酒吧菜单板（#86 升格）：区内右键开菜单编辑 UI，区外只读；数据在镇级。
     */
    public static final DeferredItem<net.minecraft.world.item.BlockItem> TAVERN_MENU_BOARD =
            ITEMS.registerSimpleBlockItem(com.bmf.bmfstown.block.ModBlocks.TAVERN_MENU_BOARD);

    /**
     * 墓碑（2026-11-28 决策 #143 / #144）：8 种材质共用<b>一个</b>物品，
     * 具体材质存在原版 {@code block_state} 组件里（由切石机配方写入）。
     *
     * <p><b>为什么不是 {@code registerSimpleBlockItem}</b>：原版 {@code BlockItem}
     * 的名字恒为"墓碑"，不看组件 ⇒ 物品栏 8 个条目长得一模一样、名字也一样，
     * 玩家分不出哪个是深板岩。{@link com.bmf.bmfstown.block.GraveStoneItem}
     * 按组件取名字，图标由客户端 {@code overrides} 分派（见 {@code BmfTownClient}）。
     * 物品本身<b>仍然只有一个</b>，只是"同一个物品的不同变体"能看出区别了。
     */
    public static final DeferredItem<com.bmf.bmfstown.block.GraveStoneItem> GRAVE_STONE =
            ITEMS.registerItem("grave_stone",
                    props -> new com.bmf.bmfstown.block.GraveStoneItem(
                            com.bmf.bmfstown.block.ModBlocks.GRAVE_STONE.get(), props),
                    new Item.Properties());

    /**
     * 写作中的书（v0.11 学者）：开写即抽定内容，进度/实践计数/作者链全在
     * 组件 NBT 里——死亡掉落、换人续写、销毁退池都随书走。
     * 名字恒为"未完成的书"，玩家打不开（内容保密）。
     */
    public static final DeferredItem<com.bmf.bmfstown.book.WritingBookItem> WRITING_BOOK =
            ITEMS.registerItem("writing_book",
                    props -> new com.bmf.bmfstown.book.WritingBookItem(props),
                    new Item.Properties().stacksTo(1));

    /**
     * 成书（v0.11 学者）：写完的书——特殊书（职业强化/隐藏解锁）与
     * 词库闲书共用，内容由 BOOK_CONTENT 组件区分；名字按内容生成。
     */
    public static final DeferredItem<com.bmf.bmfstown.book.FinishedBookItem> FINISHED_BOOK =
            ITEMS.registerItem("finished_book",
                    props -> new com.bmf.bmfstown.book.FinishedBookItem(props),
                    new Item.Properties().stacksTo(1));

    // ==========================================================
    //  建材通货（v0.17 石匠线）
    // ==========================================================

    /**
     * 石材（石料通货）。
     *
     * <p>为什么用"通货"而不是"一种石头"：它是石匠的把旧石材拆解后的产物，
     * 也是切石机做新建材的原料。它<b>不代表某一种具体方块</b>——
     * 花岗岩、深板岩、凝灰岩拆出来都是它，做出去可以是任何石类建材。
     * 语义上更接近"料"而不是"物"，所以不参与任何普通合成配方。
     *
     * <p>堆叠上限 64，与普通建材一致。
     */
    public static final DeferredItem<Item> MATERIAL_STONE =
            ITEMS.registerSimpleItem("material_stone", new Item.Properties());

    /**
     * 木材（木料通货）。语义同 {@link #MATERIAL_STONE}：各树种拆出来都是它，
     * 做出去可以是任何木类建材。基准 = 1 原木拆 4 份。
     */
    public static final DeferredItem<Item> MATERIAL_WOOD =
            ITEMS.registerSimpleItem("material_wood", new Item.Properties());

    /**
     * 碎沙（沙料通货）。
     *
     * <p>为什么沙子要单独一组：砂岩的原版来源是<b>沙子</b>（4 沙 → 1 砂岩），
     * 如果把砂岩拆成"石材"，等于让玩家拿可再生的沙子换不可再生的石头，
     * 直接击穿平衡（2026-11-25 用户拍板）。所以沙系自成一组，与石材不通。
     *
     * <p>基准 = 1 份碎沙 ≈ 1 份沙子，可烧玻璃、可合砂岩。
     */
    public static final DeferredItem<Item> MATERIAL_SAND =
            ITEMS.registerSimpleItem("material_sand", new Item.Properties());

    private ModItems() {
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}
