package com.bmf.bmfstown.alchemy;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.alchemy.ModPotions;
import net.minecraft.world.item.alchemy.Potions;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.brewing.RegisterBrewingRecipesEvent;

/**
 * 秘传 mix 注册（v0.12 炼金，决策 #25 晚间修订口径）：mix **恒注册**进原版
 * 酿造表——解锁后玩家与镇民同表同源（原版酿造台都能酿）；未解锁时由
 * mixin 门禁（{@code PotionBrewingMixin} 拦 {@code hasMix}）全局锁定，
 * 料放进去无反应、不烧燃料。
 */
@EventBusSubscriber(modid = BmfTown.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class SecretRecipesBootstrap {

    @SubscribeEvent
    public static void onRegisterBrewing(RegisterBrewingRecipesEvent event) {
        for (var e : ModPotions.SECRETS.entrySet()) {
            // 粗制药水 + 催化剂 → 秘传药水（原版查无此方的组合，硬标准已过筛）
            event.getBuilder().addMix(Potions.AWKWARD, e.getValue().catalyst(),
                    e.getValue().potion());
        }
    }

    private SecretRecipesBootstrap() {
    }
}
