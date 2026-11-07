package com.bmf.bmfstown.gametest;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.ModDataComponents;
import com.bmf.bmfstown.book.BookContent;
import com.bmf.bmfstown.book.BookDraft;
import com.bmf.bmfstown.entity.PracticeObserver;
import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.entity.profile.ProfessionType;
import com.bmf.bmfstown.leisure.LibraryReadActivity;
import com.bmf.bmfstown.town.TownZone;
import com.bmf.bmfstown.town.TownWandMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;

import java.util.UUID;

/**
 * 学者侧两块收尾的用例（2026-11-07 用户委托）：
 * 还书清扫网（sweepOrphanBook 双分支）+ 跟随见习观察计数（PracticeObserver 双分支）。
 */
@GameTestHolder(BmfTown.MOD_ID)
public class LibraryPracticeTests {

    private static final BlockPos SHELF_REL = new BlockPos(5, 1, 5);

    /** 场景：镇 + 工作区（含雕纹书架）+ 一个镇民。 */
    private record Scene(Resident resident, ChiseledBookShelfBlockEntity shelf) {
    }

    private static Scene scene(GameTestHelper helper, boolean withShelf) {
        var s = com.bmf.bmfstown.gametest.AlchemyScaffold.prepareTown(
                helper, new BlockPos(1, 0, 1), new BlockPos(7, 0, 7), 0);
        ChiseledBookShelfBlockEntity shelf = null;
        if (withShelf) {
            helper.setBlock(SHELF_REL, Blocks.CHISELED_BOOKSHELF);
            s.town().zonesForWrite(TownWandMode.WORK).add(new TownZone(UUID.randomUUID(), "图书馆",
                    helper.absolutePos(new BlockPos(3, 0, 3)),
                    helper.absolutePos(new BlockPos(7, 3, 7))));
            shelf = (ChiseledBookShelfBlockEntity) helper.getLevel()
                    .getBlockEntity(helper.absolutePos(SHELF_REL));
        }
        Resident r = helper.spawn(com.bmf.bmfstown.ModEntities.RESIDENT.get(), new BlockPos(3, 1, 4));
        r.getIdentity().setTownId(s.town().id());
        r.syncClient();
        return new Scene(r, shelf);
    }

    /** 一本带 BOOK_CONTENT 的成书。 */
    private static ItemStack contentBook(UUID townId) {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(ModDataComponents.BOOK_CONTENT.get(), BookContent.startWriting(
                townId, "作者", new BookDraft.Perk("alchemist.adept", "alchemist", 0, 3), 1000L));
        return book;
    }

    /** 还书清扫网：不在阅读状态的镇民，手里的书被送回登记书架。 */
    @GameTest(template = "room9x4x9", timeoutTicks = 400)
    public static void sweepReturnsOrphanBook(GameTestHelper helper) {
        Scene sc = scene(helper, true);
        ItemStack book = contentBook(sc.resident().getIdentity().townId());
        sc.resident().setItemInHand(InteractionHand.MAIN_HAND, book);
        LibraryReadActivity.sweepOrphanBook(sc.resident(), helper.getLevel(), false);
        if (!sc.resident().getMainHandItem().isEmpty()) {
            helper.fail("清扫后手没清空");
            return;
        }
        boolean shelved = false;
        for (int i = 0; i < sc.shelf().getContainerSize(); i++) {
            if (!sc.shelf().getItem(i).isEmpty()) {
                shelved = true;
            }
        }
        if (!shelved) {
            helper.fail("书没进书架");
            return;
        }
        helper.succeed();
    }

    /** 清扫网跳过：正在阅读的镇民，书本来就该在手里。 */
    @GameTest(template = "room9x4x9", timeoutTicks = 400)
    public static void sweepSkipsReadingResident(GameTestHelper helper) {
        Scene sc = scene(helper, true);
        sc.resident().setItemInHand(InteractionHand.MAIN_HAND,
                contentBook(sc.resident().getIdentity().townId()));
        LibraryReadActivity.sweepOrphanBook(sc.resident(), helper.getLevel(), true);
        if (sc.resident().getMainHandItem().isEmpty()) {
            helper.fail("阅读中的书被清走了");
            return;
        }
        helper.succeed();
    }

    /** 跟随见习：对口职业者每完成一次行为，卡点书计数 +1，满额解除卡点与跟随。 */
    @GameTest(template = "room9x4x9", timeoutTicks = 400)
    public static void practiceObservationClearsCheckpoint(GameTestHelper helper) {
        Scene sc = scene(helper, false);
        var worker = helper.spawn(com.bmf.bmfstown.ModEntities.RESIDENT.get(), new BlockPos(4, 1, 4));
        worker.getIdentity().setProfession(ProfessionType.ALCHEMIST);
        var scholar = sc.resident();
        scholar.getIdentity().setProfession(ProfessionType.SCHOLAR);
        scholar.syncClient(); // getProfession() 读同步快照——改完 identity 必须刷新
        scholar.setFollowResidentId(worker.getUUID());
        // 逐过滤器预断言：observe 的每个 continue 条件在测试环境都成立
        if (scholar.getProfession() != ProfessionType.SCHOLAR) {
            helper.fail("scholar 职业未生效");
            return;
        }
        if (!worker.getUUID().equals(scholar.getFollowResidentId())) {
            helper.fail("跟随目标未生效");
            return;
        }
        // 卡点书：进度 500/1000（≥50% = 卡住），实践目标 3 次未满
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(ModDataComponents.BOOK_CONTENT.get(), new BookContent(
                sc.resident().getIdentity().townId(), java.util.List.of("作者"),
                new BookDraft.Perk("alchemist.adept", "alchemist", 0, 3),
                500L, 1000L, false, false));
        scholar.setWritingBook(book);
        var wipCheck = scholar.getWritingBook().get(ModDataComponents.BOOK_CONTENT.get());
        if (wipCheck == null || !wipCheck.isStuck()) {
            helper.fail("卡点状态未生效: " + (wipCheck == null ? "无内容" : wipCheck.isStuck()));
            return;
        }

        for (int i = 0; i < 3; i++) {
            PracticeObserver.observe(helper.getLevel(), worker, ProfessionType.ALCHEMIST);
        }
        var after = scholar.getWritingBook().get(ModDataComponents.BOOK_CONTENT.get());
        if (!(after.draft() instanceof BookDraft.Perk perk)) {
            helper.fail("书稿类型丢失");
            return;
        }
        if (perk.practiceCount() != 3) {
            helper.fail("实践计数应为 3: " + perk.practiceCount());
            return;
        }
        if (!after.checkpointPassed()) {
            helper.fail("3 次观察后卡点未解除");
            return;
        }
        if (scholar.getFollowResidentId() != null) {
            helper.fail("满额后未解除跟随");
            return;
        }
        helper.succeed();
    }

    /** 领域不对口：炼金工人的行为不会给"战士书"计数。 */
    @GameTest(template = "room9x4x9", timeoutTicks = 400)
    public static void practiceWrongProfessionIgnores(GameTestHelper helper) {
        Scene sc = scene(helper, false);
        var worker = helper.spawn(com.bmf.bmfstown.ModEntities.RESIDENT.get(), new BlockPos(4, 1, 4));
        worker.getIdentity().setProfession(ProfessionType.ALCHEMIST);
        var scholar = sc.resident();
        scholar.getIdentity().setProfession(ProfessionType.SCHOLAR);
        scholar.syncClient(); // getProfession() 读同步快照——改完 identity 必须刷新
        scholar.setFollowResidentId(worker.getUUID());
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(ModDataComponents.BOOK_CONTENT.get(), new BookContent(
                sc.resident().getIdentity().townId(), java.util.List.of("作者"),
                new BookDraft.Perk("warrior.adept", "warrior", 0, 3),
                500L, 1000L, false, false));
        scholar.setWritingBook(book);

        PracticeObserver.observe(helper.getLevel(), worker, ProfessionType.ALCHEMIST);

        var after = scholar.getWritingBook().get(ModDataComponents.BOOK_CONTENT.get());
        if (!(after.draft() instanceof BookDraft.Perk perk) || perk.practiceCount() != 0) {
            helper.fail("不对口职业被错误计数");
            return;
        }
        if (after.checkpointPassed() || scholar.getFollowResidentId() == null) {
            helper.fail("不对口观察不该有副作用");
            return;
        }
        helper.succeed();
    }
}
