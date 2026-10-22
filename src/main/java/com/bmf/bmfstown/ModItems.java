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

    private ModItems() {
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}
