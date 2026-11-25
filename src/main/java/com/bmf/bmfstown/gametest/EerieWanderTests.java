package com.bmf.bmfstown.gametest;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.ModDataComponents;
import com.bmf.bmfstown.book.BookContent;
import com.bmf.bmfstown.book.BookDraft;
import com.bmf.bmfstown.eerie.HorrorLineState;
import com.bmf.bmfstown.eerie.HorrorLines;
import com.bmf.bmfstown.eerie.TownBulletinData;
import com.bmf.bmfstown.eerie.VerificationEffects;
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

    /**
     * 全弧线用例的探针线（10 章、无异象）。
     *
     * <p>为什么自带而不是用生产登记表里的线：正式故事上线后，登记表里只剩
     * 真故事，用例不该绑死某条故事的名字与章数（改了故事长度就得改测试）。
     * 探针线只在本用例执行期间通过 {@link HorrorLines#swapForTest} 临时占表。
     */
    private static final HorrorLines.HorrorLine PROBE_10 =
            new HorrorLines.HorrorLine("probe_ten", 10);

    /** 把世界日拨到第 day 游戏日（dayTime=day*24000+1000，正午免昼夜干扰）。 */
    private static void gotoDay(ServerLevel level, long day) {
        level.setDayTime(day * 24000L + 1000L);
    }

    /**
     * 全弧线（按线章数）：第 1 天锁线写第 1 格 → 同日再推被配额拦住 →
     * 连续推到该线章数 → 解锁 + 掷应验日 → 到期应验入已应验集。
     *
     * <p>分母取自探针线自己的章数——2026-11-25 起章数按线声明，不再是全局
     * 常量（决策 #129）。**用例自带探针线**（{@link #PROBE_10}）：正式内容
     * 上线后生产登记表里就不再留"测试线占位"了，用例不该依赖它存在。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void horrorTenChapterArc(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        HorrorLineState state = HorrorLineState.get(level);
        UUID townId = UUID.randomUUID();
        RandomSourceWrap random = new RandomSourceWrap();
        var old = HorrorLines.swapForTest(java.util.List.of(PROBE_10));
        int chapters = PROBE_10.chapters();
        try {
            runTenChapterArc(helper, level, state, townId, random, chapters);
        } finally {
            HorrorLines.restoreAfterTest(old);
        }
        helper.succeed();
    }

    private static void runTenChapterArc(GameTestHelper helper, ServerLevel level,
                                         HorrorLineState state, UUID townId,
                                         RandomSourceWrap random, int chapters) {
        gotoDay(level, 1);
        HorrorLineState.Advance a1 = state.advance(level, townId, random.source());
        if (a1 == null || a1.chapter() != 1 || a1.unlockedNow()) {
            helper.fail("首命中应锁线写第 1 格，实际 " + a1);
        }
        if (!PROBE_10.id().equals(state.lockedLineOf(townId))) {
            helper.fail("应锁定量测线，实际 " + state.lockedLineOf(townId));
        }
        // 单线锁定 + 每日配额：同一天再推 = null
        if (state.advance(level, townId, random.source()) != null) {
            helper.fail("同日第二次推进应被每日配额拦住");
        }

        // 第 2 格 ~ 最后一格：每天一格
        for (int day = 2; day <= chapters; day++) {
            gotoDay(level, day);
            HorrorLineState.Advance a = state.advance(level, townId, random.source());
            if (a == null || a.chapter() != day) {
                helper.fail("第 " + day + " 天应推进到第 " + day + " 格，实际 " + a);
            }
            if (day < chapters && a.unlockedNow()) {
                helper.fail("未写满 " + chapters + " 格不应解锁（第 " + day + " 格就解锁了）");
            }
        }
        // 最后一格：解锁 + 应验日
        if (state.unlockedOf(townId).isEmpty()) {
            helper.fail(chapters + " 格写齐应解锁");
        }
        if (state.lockedLineOf(townId) != null) {
            helper.fail("解锁后应释放单线锁定");
        }
        // 应验日之前：poll 不出
        gotoDay(level, chapters + 1);
        if (!state.pollVerifications(level, townId).isEmpty()) {
            helper.fail("应验日之前不应应验");
        }
        // 拨到足够晚（解锁日+9 > 时窗上限 2+3）：必已到期
        gotoDay(level, chapters + 9);
        var due = state.pollVerifications(level, townId);
        if (due.size() != 1 || !PROBE_10.id().equals(due.get(0).id())) {
            helper.fail("到期应验应返回量测线，实际 " + due);
        }
        if (!state.verifiedOf(townId).contains(PROBE_10.id())) {
            helper.fail("应验后应入已应验集");
        }
    }

    /**
     * 章数按线声明（决策 #129）：短故事 3 章，写满第 3 格就解锁。
     *
     * <p>守的是 2026-11-25 的改造本身——如果哪天有人把
     * {@code chapter >= line.chapters()} 写回 {@code chapter >= 10}，
     * 这条会红（报"第 3 格写完还没解锁"），而不是安静地让短故事永远跑不完。
     *
     * <p><b>必须把登记表换成只剩这条线</b>：{@code pickLine} 随机抽线，
     * 往长表里塞短测试线会让"首次命中抽到谁"变成掷骰子（首版真就这么
     * 红了一次，报的是"3 章线写满第 3 格应解锁"——因为抽到了 10 章的线）。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void shortStoryUnlocksAtOwnChapterCount(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        UUID townId = UUID.randomUUID();
        RandomSourceWrap random = new RandomSourceWrap();
        String id = "short_probe";
        var old = HorrorLines.swapForTest(
                java.util.List.of(new HorrorLines.HorrorLine(id, 3)));
        try {
            HorrorLineState state = HorrorLineState.get(level);
            for (int day = 1; day <= 3; day++) {
                gotoDay(level, day);
                HorrorLineState.Advance a = state.advance(level, townId, random.source());
                if (a == null) {
                    helper.fail("第 " + day + " 天应能推进（3 章线）");
                }
                if (day < 3 && a.unlockedNow()) {
                    helper.fail("3 章线不应在第 " + day + " 格就解锁");
                }
                if (day == 3 && !a.unlockedNow()) {
                    helper.fail("3 章线写满第 3 格应解锁（章数没被按线取，还是 10 吗？）");
                }
            }
            if (!state.unlockedOf(townId).contains(id)) {
                helper.fail("3 章线写齐后应入已解锁集");
            }
        } finally {
            HorrorLines.restoreAfterTest(old);
        }
        helper.succeed();
    }

    /** 应验效果注册表：声明的 id 必须真有实现（防止登记表写错字符串）。 */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 100)
    public static void declaredEffectsAreRegistered(GameTestHelper helper) {
        for (HorrorLines.HorrorLine line : HorrorLines.all()) {
            if (line.hasEffect() && !VerificationEffects.isRegistered(line.effectId())) {
                helper.fail("故事线 " + line.id() + " 声明的效果 " + line.effectId()
                        + " 未在 VerificationEffects 登记");
            }
        }
        // 空串 = 只发公告，必须合法（不能要求所有线都有效果）
        new HorrorLines.HorrorLine("probe", 1, "");
        helper.succeed();
    }

    /**
     * 登记的每条线，正文键必须真的在 lang 里（守决策 #127 的教训）。
     *
     * <p>为什么这条必须有：lang 键写错/漏写**不会编译报错、不会崩、不会
     * 打日志**，游戏里只会显示原始键名（{@code book.bmfstown.horror.x.ch3}）。
     * 这是"最安静的一类 bug"，只能靠断言守。
     *
     * <p><b>必须从 classpath 直读 lang JSON</b>，不能用
     * {@code Component.translatable(key).getString() == key} 判缺失——
     * 服务端 ResourceManager 只挂 data 包、不挂 assets/lang，取不到语言表时
     * 那句断言永远为 false，用例就变成**永远绿的空壳**（本用例首版真踩了：
     * 故意删掉 ch5 后仍然全绿）。正确做法抄 {@code EventSpecTests.lang()}。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 100)
    public static void registeredLinesHaveText(GameTestHelper helper) {
        var all = HorrorLines.all();
        if (all.isEmpty()) {
            helper.fail("登记表为空：恐怖线一条都没有，抽取永远落空");
        }
        var zh = langOrNull("zh_cn");
        var en = langOrNull("en_us");
        if (zh == null || en == null) {
            helper.fail("lang 文件在 classpath 上读不到（zh=" + (zh != null) + ", en=" + (en != null) + "）");
        }
        for (HorrorLines.HorrorLine line : all) {
            assertLang(helper, zh, en, line.titleKey(), "《" + line.id() + "》总名");
            for (int ch = 1; ch <= line.chapters(); ch++) {
                assertLang(helper, zh, en, line.chapterKey(ch),
                        line.id() + " 第 " + ch + " 章");
            }
            // 声明章数之外还有多余文本 = 章数写小了（文案白写），也抓出来
            String beyond = line.chapterKey(line.chapters() + 1);
            if (zh.has(beyond) || en.has(beyond)) {
                helper.fail("故事线 " + line.id() + " 声明 " + line.chapters()
                        + " 章，但 lang 里还有多余的第 " + (line.chapters() + 1)
                        + " 章键：" + beyond);
            }
        }
        helper.succeed();
    }

    /** 断言某个键在中英两份 lang 里都有非空文本。 */
    private static void assertLang(GameTestHelper helper, com.google.gson.JsonObject zh,
                                   com.google.gson.JsonObject en, String key, String what) {
        if (!hasText(zh, key)) {
            helper.fail("zh_cn 缺语言键（游戏里会显示键名）：" + key + " —— " + what);
        }
        if (!hasText(en, key)) {
            helper.fail("en_us 缺语言键（游戏里会显示键名）：" + key + " —— " + what);
        }
    }

    private static boolean hasText(com.google.gson.JsonObject lang, String key) {
        var v = lang.get(key);
        return v != null && !v.isJsonNull() && !v.getAsString().isEmpty();
    }

    /** 从 classpath 读语言文件（服务端 ResourceManager 只挂 data 包，读不到 assets/lang）。 */
    private static com.google.gson.JsonObject langOrNull(String code) {
        try (java.io.InputStream in = EerieWanderTests.class.getClassLoader()
                .getResourceAsStream("assets/bmfstown/lang/" + code + ".json")) {
            if (in == null) {
                return null;
            }
            return com.google.gson.JsonParser.parseReader(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (java.io.IOException e) {
            return null;
        }
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
        // 登记图书馆区（决策 #74：手记上架走登记制，整间房都是图书馆）——
        // 两种情形都登记：withShelf 只决定区内有没有有空位的雕纹书架
        s.town().zonesForWrite(com.bmf.bmfstown.town.TownWandMode.WORK).add(
                new com.bmf.bmfstown.town.TownZone(UUID.randomUUID(), "图书馆",
                        helper.absolutePos(new BlockPos(1, 0, 1)),
                        helper.absolutePos(new BlockPos(7, 3, 7))));
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
                || !u.unlockId().startsWith("horror_")
                || !u.unlockId().endsWith("#1")) {
            // 不绑死具体故事名：登记表里的线由内容定稿决定（2026-11-25 起
            // 正式故事上线、测试线已删），这里只验"编码形状"=horror_<线>#<格>
            helper.fail("手记应为恐怖分卷成书（horror_<线>#1），实际 " + content);
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

    /** 登记图书馆内没有空位雕纹书架：手记回落居民背包（绝不吞）。 */
    @GameTest(template = "librarypracticetests.room9x4x9", timeoutTicks = 200)
    public static void horrorEventFallsBackToInventory(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Scene scene = scene(helper, false); // 图书馆区已登记，但区内无书架
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
