package com.bmf.bmfstown.mixin;

import com.bmf.bmfstown.fireworks.FireworksMarkHandler;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 烟火工匠爆炸挂点（v0.13）：烟花火箭结算伤害前读**工匠章**——
 * 有章走自定义伤害结算（原版公式 + 破片半径/装药倍率 + 回响二段爆调度），
 * 无章走原版逻辑照跑（玩家产的烟花零变化）。
 *
 * <p>原版烟花伤害不走 {@code Explosion}（是 {@code dealExplosionDamage}
 * 里遍历实体逐个 hurt），NeoForge 的 {@code ExplosionEvent} 系列不覆盖它——
 * mixin 是唯一挂点（设计稿 §6"动工时核实挂点"的结论）。
 * NeoForge 1.21 运行时即 Mojmap，无需 refmap。
 */
@Mixin(FireworkRocketEntity.class)
public abstract class FireworkRocketEntityMixin {

    @Inject(method = "dealExplosionDamage", at = @At("HEAD"), cancellable = true)
    private void bmfstown$markedExplosion(CallbackInfo ci) {
        if (FireworksMarkHandler.handleExplode((FireworkRocketEntity) (Object) this)) {
            ci.cancel();
        }
    }
}
