package com.bmf.bmfstown.book;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 成书（v0.11）：学者写完的成品——特殊书（职业强化/隐藏解锁）与
 * 闲书共用这一个物品，内容由 BOOK_CONTENT 组件区分。
 *
 * <p>显示名按内容单生成（确定性）：特殊书查登记表 lang 键、
 * 闲书按词库种子拼装；作者行进 tooltip。
 */
public class FinishedBookItem extends Item {

    public FinishedBookItem(Properties properties) {
        super(properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        BookContent content = stack.get(com.bmf.bmfstown.ModDataComponents.BOOK_CONTENT.get());
        if (content == null) {
            return super.getName(stack);
        }
        net.minecraft.network.chat.MutableComponent name = switch (content.draft()) {
            // 特殊书名在登记表 lang：book.bmfstown.special.<id>.name
            case BookDraft.Perk p -> Component.translatable("book.bmfstown.special." + p.perkId() + ".name");
            case BookDraft.Unlock u -> Component.translatable("book.bmfstown.special." + u.unlockId() + ".name");
            case BookDraft.Idle i -> BookLexicon.title(i.seed());
        };
        // 批注 perk（v0.10.3）：书名后缀"——批注"（数据标记在 BOOK_CONTENT.Annotated）
        if (content.annotated()) {
            name = name.copy().append(Component.translatable("book.bmfstown.annotated_suffix"));
        }
        return name;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        BookContent content = stack.get(com.bmf.bmfstown.ModDataComponents.BOOK_CONTENT.get());
        if (content != null && !content.authors().isEmpty()) {
            tooltip.add(Component.translatable("book.bmfstown.authors",
                    Component.literal(String.join("、", content.authors()))
                            .withStyle(ChatFormatting.GRAY)).withStyle(ChatFormatting.DARK_GRAY));
        }
        // 抄本标记（v0.10.4）：抄工 = 作者链末位（抄书时追加在最后）
        if (Boolean.TRUE.equals(stack.get(com.bmf.bmfstown.ModDataComponents.BOOK_COPIED.get()))
                && content != null && !content.authors().isEmpty()) {
            tooltip.add(Component.translatable("book.bmfstown.copied",
                    content.authors().get(content.authors().size() - 1))
                    .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        }
        super.appendHoverText(stack, context, tooltip, flag);
    }
}
