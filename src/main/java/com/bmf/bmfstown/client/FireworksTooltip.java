package com.bmf.bmfstown.client;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.ModDataComponents;
import com.bmf.bmfstown.fireworks.FireworksMark;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.List;

/**
 * 特制烟花的悬浮提示（客户端）。
 *
 * <p>烟火工匠的战斗向天赋物化为烟花上的数据组件后，玩家在背包里应该一眼看出
 * "这颗烟花和原版不一样"——特制烟花在 tooltip 追加对应加成行
 * （破片=范围 / 装药足量=伤害 / 回响=二段爆炸，口径与天赋树命名一致）。
 * 原版物品改不了 {@code appendHoverText}，挂 {@link ItemTooltipEvent} 是唯一干净挂点。
 */
@EventBusSubscriber(modid = BmfTown.MOD_ID, value = Dist.CLIENT)
public final class FireworksTooltip {

    private FireworksTooltip() {
    }

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        FireworksMark mark = event.getItemStack().get(ModDataComponents.FIREWORKS_MARK);
        if (mark == null) {
            return;
        }
        List<Component> tip = event.getToolTip();
        if (mark.shrapnel()) {
            tip.add(Component.translatable(
                    "item.bmfstown.firework_rocket.mark_shrapnel")
                    .withStyle(ChatFormatting.GOLD));
        }
        if (mark.payload()) {
            tip.add(Component.translatable(
                    "item.bmfstown.firework_rocket.mark_payload")
                    .withStyle(ChatFormatting.RED));
        }
        if (mark.echo()) {
            tip.add(Component.translatable(
                    "item.bmfstown.firework_rocket.mark_echo")
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        }
    }
}
