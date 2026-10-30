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
 * 挂历是镇民历的"实体显示设备"：贴墙放，右键在 actionbar 报日期。
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

    private ModBlocks() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
    }
}
