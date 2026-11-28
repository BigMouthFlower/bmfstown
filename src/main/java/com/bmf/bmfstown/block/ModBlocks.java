package com.bmf.bmfstown.block;

import com.bmf.bmfstown.BmfTown;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 方块注册。
 *
 * <p>v0.9.2 起本 mod 才有第一个方块（此前全是物品/实体）。
 * 挂历是历法的"实体显示设备"：贴墙放，右键在 actionbar 报日期（"公元 3 年 5 月 12 日"）。
 */
public final class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(BmfTown.MOD_ID);

    /**
     * 挂历。
     *
     * <p>props 说明：{@code noOcclusion}（薄面板不能当整块参与光照遮挡）、
     * {@code pushReaction=DESTROY}（活塞一推就碎，跟按钮/告示牌同款待遇，
     * 避免依附逻辑与活塞移动打架）。
     */
    public static final DeferredBlock<WallCalendarBlock> WALL_CALENDAR =
            BLOCKS.registerBlock("wall_calendar", WallCalendarBlock::new,
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.SAND)
                            .strength(0.8F)
                            .sound(SoundType.WOOD)
                            .noOcclusion()
                            .pushReaction(PushReaction.DESTROY));

    /**
     * 镇公告栏（v0.16 沉浸层 #57）：闲逛随机发现的非常规事件公示板。
     * 占位实现：整块简单方块，右键打印最近公告；美化后议。
     */
    public static final DeferredBlock<BulletinBoardBlock> BULLETIN_BOARD =
            BLOCKS.registerBlock("bulletin_board", BulletinBoardBlock::new,
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.WOOD)
                            .strength(1.5F)
                            .sound(SoundType.WOOD));

    /**
     * 酒吧菜单板（#86 升格，2026-11-21 拍板）：菜单=酒馆资产不绑酒保实体；
     * 区内右键开编辑 UI、区外只读打印；数据在镇级 Town.tavernMenu，拆板不丢。
     */
    public static final DeferredBlock<TavernMenuBoardBlock> TAVERN_MENU_BOARD =
            BLOCKS.registerBlock("tavern_menu_board", TavernMenuBoardBlock::new,
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.WOOD)
                            .strength(1.2F)
                            .sound(SoundType.WOOD));

    /**
     * 墓碑（2026-11-28 决策 #143）：石匠线的切石机产物，8 种石材变体共用一个方块。
     *
     * <p>props 说明：
     * <ul>
     *   <li>{@code noOcclusion}：碑是薄板 + 底座，占 1 格但不挡光——
     *       放在墓地不会把周围弄黑（这是玩家会立刻注意到的事）。</li>
     *   <li>{@code noOcclusion} 同时要求 {@code isViewBlocking} / {@code isSuffocating}
     *       保持默认，否则会出现"站在碑旁边窒息"。</li>
     *   <li>硬度 1.5 与石砖一致：石头做的，得用镐挖。</li>
     * </ul>
     */
    public static final DeferredBlock<GraveStoneBlock> GRAVE_STONE =
            BLOCKS.registerBlock("grave_stone", GraveStoneBlock::new,
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.STONE)
                            .strength(1.5F)
                            .sound(SoundType.STONE)
                            .noOcclusion());

    private ModBlocks() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
    }
}
