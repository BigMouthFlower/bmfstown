package com.bmf.bmfstown.book;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 写作中的书（v0.11 学者系统）：开写即抽定内容，NBT 承载全部状态——
 * 进度、实践计数、作者链。玩家不可见内容（名字恒为"未完成的书"、
 * 打不开），死亡掉落/换人续写时数据随书走，任何人可继承。
 *
 * <p><b>销毁退池</b>：这本书记载着奖池里已被抽走的一本特殊书——
 * 书稿被彻底销毁（火烧/爆炸等）时退回该镇奖池，不然那本书在该镇绝版。
 * （{@code /kill} 清除实体不走此回调，仍会绝版——已知边缘情况。）
 */
public class WritingBookItem extends Item {

    public WritingBookItem(Properties properties) {
        super(properties);
    }

    /** 淡紫附魔纹路（用户点名：未完成的书带"编辑中"的光感）。 */
    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public void onDestroyed(ItemEntity itemEntity) {
        if (itemEntity.level() instanceof ServerLevel level) {
            BookContent content = itemEntity.getItem().get(
                    com.bmf.bmfstown.ModDataComponents.BOOK_CONTENT.get());
            if (content != null && content.draft() instanceof BookDraft.Perk perk) {
                BookPoolState.get(level).returnToPool(level, content.townId(), perk.perkId());
            } else if (content != null && content.draft() instanceof BookDraft.Unlock unlock) {
                BookPoolState.get(level).returnToPool(level, content.townId(), unlock.unlockId());
            }
        }
        super.onDestroyed(itemEntity);
    }
}
