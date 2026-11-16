package com.bmf.bmfstown.entity;

import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * 可坐方块判定（0.16 坐姿，#67 用户拍板清单）——视觉上"像椅子"的只有两类：
 * <ul>
 *   <li><b>楼梯方块</b>（任意材质）：半高座面+靠背，天然的椅子；</li>
 *   <li><b>下半砖</b>：长凳/条凳，吧台与长椅的坐面。</li>
 * </ul>
 * 不走登记制：纯形状识别，玩家用任何材质搭的座椅<b>建成即生效</b>，
 * 零配置（与"度量不理解"的感知哲学同源）。地毯/压力板/上半砖一律不算。
 */
public final class SeatBlocks {

    private SeatBlocks() {
    }

    /** 这个方块状态能不能坐。 */
    public static boolean isSeat(BlockState state) {
        return state.getBlock() instanceof StairBlock
                || (state.getBlock() instanceof SlabBlock
                        && state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM);
    }
}
