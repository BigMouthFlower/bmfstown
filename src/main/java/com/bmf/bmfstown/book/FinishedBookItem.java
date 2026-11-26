package com.bmf.bmfstown.book;

import com.bmf.bmfstown.ModDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * 成书（v0.11）：学者写完的成品——特殊书（职业强化/隐藏解锁）与
 * 闲书共用这一个物品，内容由 BOOK_CONTENT 组件区分。
 *
 * <p>显示名按内容单生成（确定性）：特殊书查登记表 lang 键、
 * 闲书按词库种子拼装；作者行进 tooltip。
 *
 * <h2>翻开阅读（v0.11.x 书本改造，用户拍板"成书要能打开"）</h2>
 * 右键成书 → 原版翻书界面（BookViewScreen）。正文页<b>确定性重放</b>：
 * 闲书按词库种子拼装、特殊书走登记表 lang 键，与书名同源——所以
 * 正文不占存档，也不需要往物品上挂原版 written_book_content 组件
 * （原版服务端 openItemGui 写死原版成书物品，走不通；客户端
 * BookAccess 可直接构造）。老档成书/抄本任何时候都翻得开；
 * 写作中的书稿（{@link WritingBookItem}，带紫光的中间产物）照旧打不开。
 */
public class FinishedBookItem extends Item {

    public FinishedBookItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        BookContent content = stack.get(ModDataComponents.BOOK_CONTENT.get());
        if (content == null || !content.isFinished()) {
            return InteractionResultHolder.pass(stack);
        }
        if (level.isClientSide) {
            com.bmf.bmfstown.client.BookViewOpener.open(readPages(content));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    /** 书名（显示名与题名页同源；批注本由调用方按需加后缀）。 */
    private static MutableComponent titleOf(BookContent content) {
        return switch (content.draft()) {
            // 特殊书名在登记表 lang：book.bmfstown.special.<id>.name
            case BookDraft.Perk p -> Component.translatable("book.bmfstown.special." + p.perkId() + ".name");
            case BookDraft.Unlock u -> unlockTitle(u.unlockId());
            case BookDraft.Idle i -> BookLexicon.title(i.seed());
        };
    }

    // ==========================================================
    //  隐藏解锁书：秘传配方（药水）与恐怖手记分卷共用一个种类
    // ==========================================================

    /**
     * 恐怖手记分卷的 unlockId 编码（决策 #58 归类纠正：手记是<b>隐藏解锁类
     * 的扩容</b>，不是新种类——书系恒为闲书/知识 perk/隐藏解锁三类）。
     * 编码 {@code horror_<线id>#<格数>}；非该前缀 = 普通解锁书。
     */
    public static final String HORROR_PREFIX = "horror_";

    /** 该解锁 id 是否是恐怖手记分卷。 */
    public static boolean isHorrorVolume(String unlockId) {
        return unlockId.startsWith(HORROR_PREFIX) && unlockId.indexOf('#') > 0;
    }

    /** 恐怖分卷 → 线 id。 */
    public static String horrorLine(String unlockId) {
        return unlockId.substring(HORROR_PREFIX.length(), unlockId.indexOf('#'));
    }

    /** 恐怖分卷 → 格数（1 起）。 */
    public static int horrorChapter(String unlockId) {
        try {
            return Integer.parseInt(unlockId.substring(unlockId.indexOf('#') + 1));
        } catch (NumberFormatException e) {
            return 0; // 坏编码回落：正文/卷号走占位键
        }
    }

    /**
     * 解锁书书名：恐怖分卷 = 故事总名+卷号；其余查登记表 lang。
     *
     * <p><b>卷号是数据，不是逻辑</b>（2026-11-26 用户拍板）：某一格若在 lang 里
     * 声明了 {@code book.bmfstown.horror.<线>.ch<i>.untitled}，该卷物品名就
     * <b>不带卷号</b>，只用它的值当名字。代码这里不判断"哪一格是特殊的"——
     * 它只知道"这一格有没有自己的名字"。机制侧完全不知道这回事：它仍是这条线
     * 的第 i 格，推进/配额/解锁/应验全按格数走，只有印在物品上那行字变了。
     */
    private static MutableComponent unlockTitle(String unlockId) {
        if (isHorrorVolume(unlockId)) {
            String line = horrorLine(unlockId);
            int chapter = horrorChapter(unlockId);
            String ownName = "book.bmfstown.horror." + line + ".ch" + chapter + ".untitled";
            var language = net.minecraft.locale.Language.getInstance();
            if (language.has(ownName)) {
                // 该格自带名字（含不带卷号这种情形）：直接用它，不追加卷号
                return Component.translatable(ownName);
            }
            return Component.translatable("book.bmfstown.horror." + line + ".name")
                    .append(" ")
                    .append(Component.translatable("book.bmfstown.horror.volume", chapter));
        }
        return Component.translatable("book.bmfstown.special." + unlockId + ".name");
    }

    /**
     * 翻开正文页：题名页（书名 + 作者链）+ 正文（闲书 = 词库两页、
     * 特殊书 = 通读页）。全部 translatable 组件，显示时才取本地化文本。
     */
    public static List<Component> readPages(BookContent content) {
        List<Component> pages = new ArrayList<>();
        MutableComponent titlePage = Component.empty()
                .append("《").append(titleOf(content)).append("》")
                .append("\n\n")
                .append(Component.translatable("book.bmfstown.authors",
                        String.join("、", content.authors())).withStyle(ChatFormatting.GRAY));
        if (content.annotated()) {
            titlePage.append("\n")
                    .append(Component.translatable("book.bmfstown.annotated_suffix")
                            .withStyle(ChatFormatting.DARK_GRAY));
        }
        pages.add(titlePage);
        switch (content.draft()) {
            case BookDraft.Idle idle -> pages.addAll(BookLexicon.pages(idle.seed()));
            case BookDraft.Unlock unlock -> {
                // 恐怖手记分卷：正文 = 该格叙事（lang 确定性重放）；其余 = 秘传配方页
                if (isHorrorVolume(unlock.unlockId())) {
                    pages.add(Component.translatable("book.bmfstown.horror."
                            + horrorLine(unlock.unlockId()) + ".ch" + horrorChapter(unlock.unlockId())));
                } else {
                    pages.addAll(secretRecipePages(unlock.unlockId()));
                }
            }
            default -> pages.add(Component.translatable("book.bmfstown.special.intro"));
        }
        return pages;
    }

    /**
     * 秘传配方页（统一框架）：按 unlockId 反查秘传登记表，列出底药/催化剂/产物——
     * 所有秘方书共用这一段，没有任何 per-book 代码；非秘传的解锁书回落通读页。
     */
    private static List<Component> secretRecipePages(String unlockId) {
        var entry = com.bmf.bmfstown.alchemy.ModPotions.SECRETS.get(unlockId);
        if (entry == null) {
            return List.of(Component.translatable("book.bmfstown.special.intro"));
        }
        var potionHolder = entry.potion();
        String productKey = net.minecraft.world.item.alchemy.Potion.getName(
                java.util.Optional.of(potionHolder), "item.minecraft.potion.effect.");
        // 底药按条目登记（绝大多数=粗制；悬浮=缓降，决策 #33）——vanilla 药水键原版自带
        String baseKey = net.minecraft.world.item.alchemy.Potion.getName(
                java.util.Optional.of(entry.base()), "item.minecraft.potion.effect.");
        // 全程保持 translatable 组件不解析——文本在渲染时刻才按玩家语言取词，
        // 任何时序下都不会把原始键名暴露成页面正文
        Component page = Component.translatable("gui.bmfstown.alchemy.recipe_page",
                Component.translatable(baseKey),
                Component.translatable(entry.catalyst().getDescriptionId()),
                Component.translatable(productKey));
        return List.of(page);
    }

    @Override
    public Component getName(ItemStack stack) {
        BookContent content = stack.get(ModDataComponents.BOOK_CONTENT.get());
        if (content == null) {
            return super.getName(stack);
        }
        MutableComponent name = titleOf(content);
        // 批注 perk（v0.10.3）：书名后缀"——批注"（数据标记在 BOOK_CONTENT.Annotated）
        if (content.annotated()) {
            name = name.append(Component.translatable("book.bmfstown.annotated_suffix"));
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
