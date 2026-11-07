package com.bmf.bmfstown.mixin;

import com.bmf.bmfstown.alchemy.AlchemyPace;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 镇民酿造加速/低温熟成（v0.12 炼金，决策 #28）：原版酿造起始时间写死 400t——
 * 对被炼金术士认领的酿造台，按该镇民的效率乘区改写（等级/心情/急火/低温/
 * 特殊书全进乘区）；玩家的台子不受任何影响。低温熟成另保燃料永不耗尽。
 */
@Mixin(BrewingStandBlockEntity.class)
public abstract class BrewingStandBlockEntityMixin {

    /** 起始酿造时间按效率缩放（原版常量 400）。 */
    @ModifyConstant(method = "serverTick", constant = @Constant(intValue = 400))
    private static int bmfstown$pace(int original, Level level, BlockPos pos,
            BlockState state, BrewingStandBlockEntity stand) {
        return AlchemyPace.brewStartTicks(original, level, pos, stand);
    }

    /** 低温熟成：燃料每 tick 顶满（等效永不消耗，烈焰粉物品不再被吃）。 */
    @Inject(method = "serverTick", at = @At("HEAD"))
    private static void bmfstown$coldFuel(Level level, BlockPos pos, BlockState state,
            BrewingStandBlockEntity stand, CallbackInfo ci) {
        if (level instanceof ServerLevel && AlchemyPace.isColdBrew(stand)) {
            ((BrewingStandBlockEntityAccessor) (Object) stand).bmfstown$setFuel(20);
        }
    }
}
