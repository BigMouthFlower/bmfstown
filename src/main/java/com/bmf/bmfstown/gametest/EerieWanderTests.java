package com.bmf.bmfstown.gametest;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.ModDataComponents;
import com.bmf.bmfstown.book.BookContent;
import com.bmf.bmfstown.book.BookDraft;
import com.bmf.bmfstown.eerie.HorrorLineState;
import com.bmf.bmfstown.eerie.HorrorLines;
import com.bmf.bmfstown.eerie.TownBulletinData;
import com.bmf.bmfstown.eerie.WanderEvents;
import com.bmf.bmfstown.entity.Resident;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * 闲暇随机事件框架 GameTest（决策 #57）：
 * 十部制状态机（推进/单线锁定/每日配额/解锁/应验）、
 * 恐怖事件三出口（分卷手记进书架 + 公告栏 + 状态推进）、
 * 书架全满回落背包。
 */
@GameTestHolder(BmfTown.MOD_ID)
@PrefixGameTestTemplate(false) // 复用现有结构文件（不带小写类名前缀）
public class EerieWanderTests {

    /** 把世界日拨到第 day 游戏日（dayTime=day*24000+1000，正午免昼夜干扰）。 */
    private static void gotoDay(ServerLevel level, long day) {
        level.setDayTime(day * 24000L + 1000L);
    }

    /**
     * 十部制全弧线：第 1 天锁线写第 1 格 → 同日再推被配额拦住 →
     * 连续 9 天推满 10 格 → 解锁 + 掷应验日 → 到期应验入已应验集。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void horrorTenChapterArc(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        HorrorLineState state = HorrorLineState.get(level);
        UUID townId = UUID.randomUUID();
        RandomSourceWrap random = new RandomSourceWrap();

        gotoDay(level, 1);
        HorrorLineState.Advance a1 = state.advance(level, townId, random.source());
        if (a1 == null || a1.chapter() != 1 || a1.unlockedNow()) {
            helper.fail("首命中应锁线写第 1 格，实际 " + a1);
        }
        if (!"test".equals(state.lockedLineOf(townId))) {
            helper.fail("应锁定测试线，实际 " + state.lockedLineOf(townId));
        }
        // 单线锁定 + 每日配额：同一天再推 = null
        if (state.advance(level, townId, random.source()) != null) {
            helper.fail("同日第二次推进应被每日配额拦住");
        }

        // 第 2~10 格：每天一格
        for (int day = 2; day <= HorrorLines.CHAPTERS; day++) {
            gotoDay(level, day);
            HorrorLineState.Advance a = state.advance(level, townId, random.source());
            if (a == null || a.chapter() != day) {
                helper.fail("第 " + day + " 天应推进到第 " + day + " 格，实际 " + a);
            }
            if (day < HorrorLines.CHAPTERS && a.unlockedNow()) {
                helper.fail("未满 10 格不应解锁");
            }
        }
        // 第 10 格：解锁 + 应验日
        if (state.unlockedOf(townId).isEmpty()) {
            helper.fail("10 格写齐应解锁");
        }
        if (state.lockedLineOf(townId) != null) {
            helper.fail("解锁后应释放单线锁定");
        }
        // 应验日之前：poll 不出
        gotoDay(level, HorrorLines.CHAPTERS + 1);
        if (!state.pollVerifications(level, townId).isEmpty()) {
            helper.fail("应验日之前不应应验");
        }
        // 拨到足够晚（解锁日+9 > 时窗上限 2+3）：必已到期
        gotoDay(level, HorrorLines.CHAPTERS + 9);
        var due = state.pollVerifications(level, townId);
        if (due.size() != 1 || !"test".equals(due.get(0).id())) {
            helper.fail("到期应验应返回测试线，实际 " + due);
        }
        if (!state.verifiedOf(townId).contains("test")) {
            helper.fail("应验后应入已应验集");
        }
        helper.succeed();
    }

    /** RandomSource 包装（测试只需任意源）。 */
    private static final class RandomSourceWrap {
        net.minecraft.util.RandomSource source() {
            return net.minecraft.util.RandomSource.create();
        }
    }

    /** 场景：镇 + 镇民（绑定镇 id）。 */
    private record Scene(com.bmf.bmfstown.town.Town town, Resident resident) {
    }

    private static Scene scene(GameTestHelper helper, boolean withShelf) {
        var s = com.bmf.bmfstown.gametest.AlchemyScaffold.prepareTown(
                helper, new BlockPos(1, 0, 1), new BlockPos(7, 0, 7), 0);
        if (withShelf) {
            helper.setBlock(new BlockPos(5, 1, 5), Blocks.CHISELED_BOOKSHELF);
        }
        Resident r = helper.spawn(com.bmf.bmfstown.ModEntities.RESIDENT.get(), new BlockPos(3, 1, 4));
        r.setNoAi(true);
        r.getIdentity().setTownId(s.town().id());
        r.syncClient();
        return new Scene(s.town(), r);
    }

    /** 强制命中：事件三出口——第 1 卷手记进书架 + 公告栏记一笔 + 状态推进。 */
    @GameTest(template = "librarypracticetests.room9x4x9", timeoutTicks = 200)
    public static void horrorEventWritesVolumeAndBulletin(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Scene scene = scene(helper, true);
        UUID townId = scene.town().id();
        gotoDay(level, 1);

        WanderEvents.forceHorrorEvent(scene.resident(), level);

        // 1. 状态：锁线第 1 格
        HorrorLineState state = HorrorLineState.get(level);
        if (state.progressOf(townId) != 1) {
            helper.fail("强制命中应推进到第 1 格，实际 " + state.progressOf(townId));
        }
        // 2. 分卷手记在书架上（Unlock 类，horror_ 前缀编码）
        var shelf = (ChiseledBookShelfBlockEntity) level
                .getBlockEntity(helper.absolutePos(new BlockPos(5, 1, 5)));
        ItemStack found = null;
        for (int slot = 0; slot < shelf.getContainerSize(); slot++) {
            ItemStack s = shelf.getItem(slot);
            if (!s.isEmpty()) {
                found = s;
            }
        }
        if (found == null) {
            helper.fail("分卷手记应放进雕纹书架");
        }
        BookContent content = found.get(ModDataComponents.BOOK_CONTENT.get());
        if (content == null || !(content.draft() instanceof BookDraft.Unlock u)
                || !u.unlockId().startsWith("horror_test#1")) {
            helper.fail("手记应为恐怖分卷成书（horror_test#1），实际 " + content);
        }
        // 3. 公告栏记了发现
        var bulletins = TownBulletinData.get(level).latest(townId, 10);
        if (bulletins.size() != 1
                || !TownBulletinData.KIND_SPOT.equals(bulletins.get(0).kind())) {
            helper.fail("公告栏应恰好一条发现公告，实际 " + bulletins.size());
        }
        // 4. 每日配额：同日再命中 → 无新公告（没发现就没故事）
        WanderEvents.forceHorrorEvent(scene.resident(), level);
        if (TownBulletinData.get(level).latest(townId, 10).size() != 1) {
            helper.fail("同日第二次命中应被每日配额拦住");
        }
        helper.succeed();
    }

    /** 书架全满：手记回落居民背包（绝不吞）。 */
    @GameTest(template = "librarypracticetests.room9x4x9", timeoutTicks = 200)
    public static void horrorEventFallsBackToInventory(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Scene scene = scene(helper, false); // 没有书架
        UUID townId = scene.town().id();
        gotoDay(level, 1);

        WanderEvents.forceHorrorEvent(scene.resident(), level);

        boolean inBag = false;
        for (int i = 0; i < scene.resident().getInventory().getContainerSize(); i++) {
            ItemStack s = scene.resident().getInventory().getItem(i);
            if (!s.isEmpty() && s.get(ModDataComponents.BOOK_CONTENT.get()) != null) {
                inBag = true;
            }
        }
        if (!inBag) {
            helper.fail("无书架时分卷手记应留在居民背包");
        }
        helper.succeed();
    }
}
