package com.bmf.bmfstown.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 烟花火箭访问器：暴露 {@code attachedToEntity}（骑乘/鞘翅挂靠目标，private）——
 * 工匠章伤害结算需要复刻原版"挂靠实体直伤"分支。
 */
@Mixin(FireworkRocketEntity.class)
public interface FireworkRocketEntityAccessor {

    @Accessor("attachedToEntity")
    LivingEntity bmfstown$getAttachedTo();
}
