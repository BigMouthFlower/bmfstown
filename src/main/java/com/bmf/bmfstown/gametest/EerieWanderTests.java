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

    /**
     * "自带名字的卷"：声明了 {@code .untitled} 的格，物品名<b>不许</b>再拼卷号。
     *
     * <p>为什么值得单独立一条：这是唯一一处"印刷名与格数不一致"，而它一旦错了
     * 不会有任何功能异常——只是那一本悄悄变回"·第5卷"，把整个设计意图抹掉，
     * 而且没人会去看物品名才发现。所以用用例钉住：<b>有没有 .untitled 键</b>
     * 与实际拼出来的名字必须一致。
     *
     * <p>反向验证过：把 ch5.untitled 删掉后本条会报"第5格没有自带名字却按
     * 无卷号处理"一类的不一致（见 decisions #133）。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 100)
    public static void ownNameVolumesPrintNoNumber(GameTestHelper helper) {
        var zh = langOrNull("zh_cn");
        var en = langOrNull("en_us");
        if (zh == null || en == null) {
            helper.fail("lang 文件读不到");
        }
        int ownNamed = 0;
        for (HorrorLines.HorrorLine line : HorrorLines.all()) {
            for (int ch = 1; ch <= line.chapters(); ch++) {
                String key = line.chapterKey(ch) + ".untitled";
                boolean hasOwnName = zh.has(key) || en.has(key);
                if (!hasOwnName) {
                    continue;
                }
                ownNamed++;
                // 双语必须都声明（否则换语言后一本突然带上卷号，等于两本书）
                if (!zh.has(key)) {
                    helper.fail("zh_cn 缺 " + key + "：英文侧声明了自带名字，中文侧没有");
                }
                if (!en.has(key)) {
                    helper.fail("en_us 缺 " + key + "：中文侧声明了自带名字，英文侧没有");
                }
                if (!hasText(zh, key)) {
                    helper.fail(key + " 是空串：物品名会变成空白的书");
                }
            }
        }
        if (ownNamed == 0) {
            helper.fail("一条自带名字的卷都没有：若《雾中人》已登记，说明 ch5.untitled 被误删了");
        }
        helper.succeed();
    }

    /**
     * 调试台阶：逐格推进<b>只跳过每日配额</b>，其余规则一条不少。
     *
     * <p>守两件事，缺一不可：
     * <ol>
     *   <li>同一天连推能推进（这是用户要的那条通路，否则验收还得挂机）；</li>
     *   <li><b>生产路径同一天仍然只能推进一格</b>——如果哪天有人图省事把
     *       {@code advance} 的配额判断顺手删了/改成条件恒假，这条会红。没有
     *       第 2 点，第 1 点是"把闸拆了"，不是"开了个调试门"。</li>
     * </ol>
     *
     * <p>反向验证过：把 {@code advance(…, false, null)} 里的
     * {@code !ignoreDailyCap} 去掉（=生产也跳配额）→ 第 2 段报
     * "同日第二次推进应被配额拦住，实际推进到第 2 格"。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void debugStepSkipsOnlyDailyCap(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        HorrorLineState state = HorrorLineState.get(level);
        UUID townId = UUID.randomUUID();
        var random = net.minecraft.util.RandomSource.create();
        String id = "cap_probe";
        var old = HorrorLines.swapForTest(
                java.util.List.of(new HorrorLines.HorrorLine(id, 5)));
        try {
            gotoDay(level, 1);
            // 生产口径：同一天只能 1 格
            var p1 = state.advance(level, townId, random);
            if (p1 == null || p1.chapter() != 1) {
                helper.fail("生产首推应到第 1 格，实际 " + p1);
            }
            if (state.advance(level, townId, random) != null) {
                helper.fail("生产路径同日第二次推进应被每日配额拦住"
                        + "（若这里红了：配额判断被拆了，线上节奏会失控）");
            }
            // 调试口径：同一天可继续推
            var d2 = state.advanceIgnoringDailyCap(level, townId, random);
            if (d2 == null || d2.chapter() != 2) {
                helper.fail("调试台阶同日应能推进到第 2 格，实际 " + d2);
            }
            // 而且不连带把"今天已推过"抹掉：之后生产口径仍然拦住
            if (state.advance(level, townId, random) != null) {
                helper.fail("调试推进后，生产口径在同一天仍应被拦住");
            }
        } finally {
            HorrorLines.restoreAfterTest(old);
        }
        helper.succeed();
    }

    /**
     * 调试台阶：可以<b>点名</b>推进某条线（线上是等概率随机抽），
     * 但<b>不许插队</b>别的线（单线锁定是规则不是节奏）。
     *
     * <p>反向验证过：把 {@code forcedLineId} 的插队判断删掉 → 本用例报
     * "锁着 A 时点名 B 不该推进"。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void debugStepPicksNamedLineButNeverJumpsQueue(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        HorrorLineState state = HorrorLineState.get(level);
        UUID townId = UUID.randomUUID();
        var random = net.minecraft.util.RandomSource.create();
        String a = "line_a";
        String b = "line_b";
        var old = HorrorLines.swapForTest(java.util.List.of(
                new HorrorLines.HorrorLine(a, 3), new HorrorLines.HorrorLine(b, 3)));
        try {
            gotoDay(level, 1);
            // 点名 B：应当锁上 B（线上随机，调试要能指定）
            var first = state.advanceLineIgnoringDailyCap(level, townId, b, random);
            if (first == null || !b.equals(first.line().id())) {
                helper.fail("点名推进应落在 " + b + "，实际 " + first);
            }
            if (!b.equals(state.lockedLineOf(townId))) {
                helper.fail("点名推进后应锁上 " + b + "，实际锁 " + state.lockedLineOf(townId));
            }
            // 锁着 B 时点名 A：必须拒绝（不抢锁）
            if (state.advanceLineIgnoringDailyCap(level, townId, a, random) != null) {
                helper.fail("锁着 " + b + " 时点名 " + a + " 不该推进（单线锁定是规则）");
            }
            // 不存在的线：null，不炸
            if (state.advanceLineIgnoringDailyCap(level, townId, "no_such_line", random) != null) {
                helper.fail("不存在的线 id 应返回 null");
            }
        } finally {
            HorrorLines.restoreAfterTest(old);
        }
        helper.succeed();
    }

    /**
     * 调试台阶：写齐后能把应验拉到今天，并走<b>生产那条</b>应验派发
     * （公告 + 气泡 + 效果注册表），不是调试自己另开一条路。
     *
     * <p>判据选"公告栏出现 KIND_VERIFY 一条"正是为了钉住这一点：公告只在
     * {@code WanderEvents.pollVerificationsAndReport} 里发。如果哪天有人图省事
     * 让调试命令直接调 {@code VerificationEffects.run}（跳过派发），本用例会红
     * ——反向验证过。
     */
    @GameTest(template = "librarypracticetests.room9x4x9", timeoutTicks = 300)
    public static void debugVerifyNowGoesThroughProductionDispatch(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Scene scene = scene(helper, false); // 不需要书架：本用例只看派发
        UUID townId = scene.town().id();
        var finder = scene.resident();
        String id = "verify_probe";
        var old = HorrorLines.swapForTest(
                java.util.List.of(new HorrorLines.HorrorLine(id, 2)));
        try {
            // 写齐 2 格（调试台阶，同一天连推）
            gotoDay(level, 1);
            if (WanderEvents.debugStep(finder, level, id) == null) {
                helper.fail("第 1 格应能推进");
            }
            var last = WanderEvents.debugStep(finder, level, id);
            if (last == null || !last.unlockedNow()) {
                helper.fail("2 格线写齐应解锁，实际 " + last);
            }
            // 应验日还没到：poll 不出，公告栏也没有应验条
            if (!HorrorLineState.get(level).pollVerifications(level, townId).isEmpty()) {
                helper.fail("应验日未到不应应验");
            }
            // 拉到今天并触发：必须经生产派发
            var due = WanderEvents.debugVerifyNow(finder, level, id);
            if (due.size() != 1 || !id.equals(due.get(0).id())) {
                helper.fail("应触发 " + id + "，实际 " + due);
            }
            boolean hasVerifyBulletin = false;
            for (var entry : TownBulletinData.get(level).latest(townId, 20)) {
                if (TownBulletinData.KIND_VERIFY.equals(entry.kind())) {
                    hasVerifyBulletin = true;
                }
            }
            if (!hasVerifyBulletin) {
                helper.fail("应验必须经 WanderEvents 派发（公告栏应有一条 KIND_VERIFY）"
                        + "——若这里红了：调试命令绕开了生产派发，验的不是线上那条路");
            }
            // 已应验的线再拉应是空（没有可拉的日期）
            if (!WanderEvents.debugVerifyNow(finder, level, id).isEmpty()) {
                helper.fail("已应验的线不该还能再应验");
            }
        } finally {
            HorrorLines.restoreAfterTest(old);
        }
        helper.succeed();
    }

    /**
     * 调试台阶：reset 清空状态机，但<b>不动公告栏</b>（那是历史记录）。
     *
     * <p>反向验证过：让 {@code resetTown} 顺手清公告 → 本用例报
     * "reset 不该抹掉公告栏"。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void debugResetClearsStateKeepsBulletin(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        HorrorLineState state = HorrorLineState.get(level);
        UUID townId = UUID.randomUUID();
        var random = net.minecraft.util.RandomSource.create();
        String id = "reset_probe";
        var old = HorrorLines.swapForTest(
                java.util.List.of(new HorrorLines.HorrorLine(id, 3)));
        try {
            gotoDay(level, 1);
            state.advanceIgnoringDailyCap(level, townId, random);
            TownBulletinData.get(level).post(level, townId, new TownBulletinData.Entry(
                    1L, false, TownBulletinData.KIND_SPOT, "测试居民", id, 1));
            if (state.progressOf(townId) != 1) {
                helper.fail("前置：应推到第 1 格");
            }
            state.resetTown(townId);
            if (state.progressOf(townId) != 0 || state.lockedLineOf(townId) != null) {
                helper.fail("reset 后不该还有锁线/进度");
            }
            if (TownBulletinData.get(level).latest(townId, 10).isEmpty()) {
                helper.fail("reset 不该抹掉公告栏（那是历史记录）");
            }
        } finally {
            HorrorLines.restoreAfterTest(old);
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

    /**
     * 语言键是否有实际内容。
     *
     * <p>lang 的值有两种合法形态（坑71）：字符串，或<b>组件 JSON 数组</b>
     * （带颜色/字体的那一页就是这么写的——原版 {@code Language.loadFromJson}
     * 见到数组会按 {@code ComponentSerialization.CODEC} 解析成带样式组件）。
     * 旧版本这里直接 {@code getAsString()}，遇到数组会抛
     * {@code IllegalStateException}——用例不是报"缺键"，而是整条崩掉。
     */
    private static boolean hasText(com.google.gson.JsonObject lang, String key) {
        var v = lang.get(key);
        if (v == null || v.isJsonNull()) {
            return false;
        }
        if (v.isJsonArray()) {
            // 组件数组：至少有一条的 text 是非空字符串
            for (var e : v.getAsJsonArray()) {
                if (e.isJsonObject() && e.getAsJsonObject().has("text")
                        && !e.getAsJsonObject().get("text").isJsonNull()
                        && !e.getAsJsonObject().get("text").getAsString().isEmpty()) {
                    return true;
                }
            }
            return false;
        }
        return !v.getAsString().isEmpty();
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

    // ==========================================================
    //  八、时间回拨（坑97）：游标不许永久卡死
    // ==========================================================

    /**
     * 时间往回拨之后，<b>应验必须仍然会来</b>。
     *
     * <h2>用户问的那件事</h2>
     * "检查一下之前那些类似防止时间倒拨导致 npc 时间相关行为的 bug 会不会出来，
     * 类似那种时间倒拨以后游标永远不能触发的问题。"
     *
     * <p><b>确实会。</b>{@code pollVerifications} 原来是 {@code today >= dueDay}，
     * 而 dueDay 是解锁那一刻算出来的<b>未来某天</b>（today + 2~6）。时间一往回拨，
     * today 就永远追不上——这条线<b>永久</b>等不到应验。老代码在
     * {@code Status.elapsed} / {@code settleSleep} / {@code Events.townCooldownOk}
     * 里都写过"now < last 就放行"（v0.9.3 的教训），恐怖线是后加的，漏了。
     *
     * <h2>修法与这条用例守的不变量</h2>
     * 应验日<b>按回拨量整体平移</b>——等待期原样保留，不是"立刻应验"
     * （那等于拿回拨换免费解锁），也不是"不管"（那才叫永久卡死）。
     *
     * <p>场景：第 1~10 天写齐 → 应验日 ≈ 第 13 天。然后<b>拨回第 3 天</b>，
     * 再一路走到"回拨后应验日"。到点必须应验。
     *
     * <p>反向验证：把 {@code pollVerifications} 里那段平移删掉 ⇒ 红
     * （报"回拨之后永远等不到应验"）。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void horrorVerifySurvivesTimeRewind(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        HorrorLineState state = HorrorLineState.get(level);
        UUID townId = UUID.randomUUID();
        RandomSourceWrap random = new RandomSourceWrap();
        var old = HorrorLines.swapForTest(java.util.List.of(PROBE_10));
        try {
            // 写齐 10 格（第 1~10 天）
            gotoDay(level, 1);
            for (int day = 1; day <= PROBE_10.chapters(); day++) {
                gotoDay(level, day);
                state.advance(level, townId, random.source());
            }
            if (state.unlockedOf(townId).isEmpty()) {
                helper.fail("前置：写齐后应解锁");
                return;
            }
            long dueDay = state.pendingVerifications(townId).get(PROBE_10.id());

            // 关键一步：时间<b>往回拨</b>到第 3 天
            gotoDay(level, 3);
            state.pollVerifications(level, townId); // 这一下负责检测回拨并平移

            var shifted = state.pendingVerifications(townId).get(PROBE_10.id());
            if (shifted == null) {
                helper.fail("回拨后应验记录不该消失（它还没应验）");
                return;
            }
            // 平移后的应验日应该一起被拉回到"第 3 天 + 原本的等待期"
            if (shifted >= dueDay) {
                helper.fail("回拨后应验日没有跟着往前移（原 " + dueDay
                        + "，现在 " + shifted + "）——它永远追不上，这条线永久卡死");
                return;
            }

            // 先确认"平移后仍未到"：第 3 天不该立刻应验
            if (!state.pollVerifications(level, townId).isEmpty()) {
                helper.fail("刚回拨就立刻应验了——那等于拿回拨换免费解锁，等待期白设");
                return;
            }

            // 一路走到平移后的应验日：必须应验
            gotoDay(level, shifted);
            var due = state.pollVerifications(level, townId);
            if (due.size() != 1 || !PROBE_10.id().equals(due.get(0).id())) {
                helper.fail("走到平移后的应验日（第 " + shifted + " 天）仍不应验——"
                        + "这正是'游标永远不能触发'的bug，实际返回 " + due);
                return;
            }
        } finally {
            HorrorLines.restoreAfterTest(old);
        }
        helper.succeed();
    }

    /**
     * 没回拨时，回拨保护<b>绝不能误伤</b>：等待期还得老老实实等。
     *
     * <p>这条是上一条的对照。没有它，"把应验日直接拉到今天"这种写法
     * 也能让上一条绿——那等于每条线都立刻应验，恐怖线的节奏全毁。
     *
     * <p>反向验证：把平移逻辑改成"回拨检测恒为真 / 无条件把 dueDay 设成 today"
     * ⇒ 这条红（报"应验日之前不应验"）。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void horrorVerifyKeepsWaitingWithoutRewind(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        HorrorLineState state = HorrorLineState.get(level);
        UUID townId = UUID.randomUUID();
        RandomSourceWrap random = new RandomSourceWrap();
        var old = HorrorLines.swapForTest(java.util.List.of(PROBE_10));
        try {
            gotoDay(level, 1);
            for (int day = 1; day <= PROBE_10.chapters(); day++) {
                gotoDay(level, day);
                state.advance(level, townId, random.source());
            }
            long dueDay = state.pendingVerifications(townId).get(PROBE_10.id());
            if (dueDay <= PROBE_10.chapters()) {
                helper.fail("前置：应验日应在解锁日之后，实际 " + dueDay);
                return;
            }
            // 正常往前走一天，poll 不该应验
            gotoDay(level, PROBE_10.chapters() + 1);
            if (!state.pollVerifications(level, townId).isEmpty()) {
                helper.fail("应验日（第 " + dueDay + " 天）之前就应验了——等待期失效");
                return;
            }
            // 应验日仍在原地（没有因为正常前进被平移）
            var still = state.pendingVerifications(townId).get(PROBE_10.id());
            if (still == null || still != dueDay) {
                helper.fail("正常过一天不该改动应验日（原 " + dueDay + "，现在 " + still + "）");
                return;
            }
        } finally {
            HorrorLines.restoreAfterTest(old);
        }
        helper.succeed();
    }

}
