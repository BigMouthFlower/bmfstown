package com.bmf.bmfstown.entity;

import com.bmf.bmfstown.book.BookContent;
import com.bmf.bmfstown.book.BookDraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * 实践观察（v0.11，用户定稿的 50% 卡点解除途径 B）：
 * 学者跟随某个镇民时，被跟随者每完成一次对应职业行为
 * （战士杀敌 / 工人合成），附近学者的写作中书上计数 +1；
 * 计数满 → 卡点**永久解除**（状态写在书上，之后任何学者可写完）。
 */
public final class PracticeObserver {

    /** 观察半径：跟随本来就贴身，8 格是宽容上限。 */
    private static final double RANGE_SQR = 8.0 * 8.0;

    private PracticeObserver() {
    }

    /** 战士杀敌 / 工人合成等"对应职业行为"结算点调用。 */
    public static void observe(ServerLevel level, Resident worker,
                               com.bmf.bmfstown.entity.profile.ProfessionType profession) {
        for (Resident scholar : level.getEntitiesOfClass(Resident.class,
                worker.getBoundingBox().inflate(8.0D))) {
            if (scholar == worker
                    || scholar.getProfession() != com.bmf.bmfstown.entity.profile.ProfessionType.SCHOLAR) {
                continue;
            }
            if (!worker.getUUID().equals(scholar.getFollowResidentId())) {
                continue; // 只认"正在跟随这个工人/战士"的学者
            }
            ItemStack wip = scholar.getWritingBook();
            BookContent content = wip.get(com.bmf.bmfstown.ModDataComponents.BOOK_CONTENT.get());
            if (content == null
                    || !(content.draft() instanceof BookDraft.Perk perk)
                    || !perk.professionId().equals(profession.getSerializedName())) {
                continue; // 学者手里的书不是这个职业的
            }
            BookContent after = withPractice(wip).get(
                    com.bmf.bmfstown.ModDataComponents.BOOK_CONTENT.get());
            scholar.setWritingBook(withPractice(wip));
            if (after != null && after.checkpointPassed()) {
                scholar.setFollowResidentId(null); // 实践满额：解除跟随，回去写书
            }
        }
    }

    private static ItemStack withPractice(ItemStack wip) {
        BookContent content = wip.get(com.bmf.bmfstown.ModDataComponents.BOOK_CONTENT.get());
        if (content != null) {
            wip.set(com.bmf.bmfstown.ModDataComponents.BOOK_CONTENT.get(),
                    content.withPracticeIncremented());
        }
        return wip;
    }
}
