package com.bmf.bmfstown.town;

import com.bmf.bmfstown.client.TownHandbookClient;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 城镇手册——右键打开的"城镇账本"。
 *
 * <h2>为什么是独立物品而不是魔杖的附加功能</h2>
 * 魔杖是"编辑工具"（选点、确认），手册是"查看工具"（列表、改名、删除）。
 * 两者的使用场景分开：玩家不拿魔杖也能随时翻手册。
 *
 * <h2>打开流程</h2>
 * 右键 → 客户端立刻开屏幕（本地响应零延迟）→ 屏幕初始化时发
 * {@code HandbookRequestPayload} 拉数据 → 服务端回
 * {@code HandbookSyncPayload} → 屏幕刷新列表。
 * 首次打开会有一瞬"加载中"，之后客户端缓存了数据就几乎即时。
 */
public class TownHandbookItem extends Item {

    public TownHandbookItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) {
            TownHandbookClient.open();
        }
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<net.minecraft.network.chat.Component> tooltip,
                                TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(net.minecraft.network.chat.Component.translatable(
                "tip.bmfstown.handbook.open"));
    }
}
