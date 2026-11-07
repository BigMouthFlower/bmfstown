package com.bmf.bmfstown.mixin;

import com.bmf.bmfstown.alchemy.AlchemyGate;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionBrewing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 秘传酿造门禁（决策 #25 晚间口径）：mix 恒注册，但未解锁时
 * {@code hasMix} 对秘传组合返回 false——酿造台判定不可酿，料放进去
 * 无反应、不烧燃料；解锁瞬间开禁（玩家与镇民同表同源）。
 * NeoForge 1.21 运行时即 Mojmap，无需 refmap。
 */
@Mixin(PotionBrewing.class)
public abstract class PotionBrewingMixin {

    @Inject(method = "hasMix(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z",
            at = @At("HEAD"), cancellable = true)
    private void bmfstown$gateSecretMix(ItemStack from, ItemStack reagent,
            CallbackInfoReturnable<Boolean> cir) {
        if (AlchemyGate.isLockedCombo(from, reagent)) {
            cir.setReturnValue(false);
        }
    }
}
