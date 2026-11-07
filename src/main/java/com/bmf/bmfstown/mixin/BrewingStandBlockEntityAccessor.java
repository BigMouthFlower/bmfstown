package com.bmf.bmfstown.mixin;

import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 酿造台字段访问器（低温熟成"燃料永不耗尽"用）。
 */
@Mixin(BrewingStandBlockEntity.class)
public interface BrewingStandBlockEntityAccessor {

    @Accessor("fuel")
    void bmfstown$setFuel(int fuel);

    @Accessor("fuel")
    int bmfstown$getFuel();

    @Accessor("brewTime")
    int bmfstown$getBrewTime();
}
