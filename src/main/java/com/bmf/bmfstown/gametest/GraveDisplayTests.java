package com.bmf.bmfstown.gametest;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.entity.domain.AgingRolls;
import com.bmf.bmfstown.entity.domain.Identity;
import com.bmf.bmfstown.entity.domain.ResidentLedger;
import com.bmf.bmfstown.town.GraveText;
import com.bmf.bmfstown.town.GraveyardData;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;

/**
 * 2026-11-28 傍晚那批"界面/年龄全空"的回归用例。
 *
 * <h2>这批用例守的是什么</h2>
 * 用户实测报了三件事，看起来是三个 bug，<b>其实是同一个根因 + 两个连带</b>：
 * <ol>
 *   <li>"生平信息什么都没有，还是只有一个死法"——生日投影为<b>负数</b>时
 *       被当成"未知"，享年/在镇整行被吞；</li>
 *   <li>"npc 的 UI 里面也是年龄什么的要啥啥没有"——同一条，快照里
 *       {@code ageYears} 恒 -1，面板判定 {@code >= 0} 不画；</li>
 *   <li>表头 "Format error: 死因 / 第 %s 日"——表头键带占位符却无参调用
 *       {@code I18n.get}。</li>
 * </ol>
 *
 * <h2>为什么原来的用例全绿却漏了它</h2>
 * {@code AgingGraveTests} 里所有用例都拿 {@code today = 10000} 这类大数当"今天"，
 * 生日必然算成正数 ⇒ <b>负数区间一次都没被走到</b>。
 * 这是"用例选了舒服的输入"的典型代价：判据本身没错，样本把 bug 全躲开了。
 * 所以这里的用例<b>刻意把 today 压到很小的值</b>（开档头几天）。
 */
@GameTestHolder(BmfTown.MOD_ID)
@PrefixGameTestTemplate(false)
public class GraveDisplayTests {

    /**
     * 测试用的世界历纪元。**取一个正值**，让 {@code deathDay − epochDay}
     * 落在 0 附近（碑文日期会是"公元 1 年"上下）——
     * 数据用例只关心"有没有把日期拼进去"，不关心具体几年几月。
     */
    private static final long EPOCH = 1000L;

    // ==========================================================
    //  一、负生日：享年/在镇必须算得出来
    // ==========================================================

    /**
     * 生日投影落在开档之前（负数）时，{@link AgingRolls#ageAt} 必须照样给出年龄。
     *
     * <p><b>反向验证</b>：把 {@code ageAt} 的判据改回 {@code birthDay < 0} ⇒
     * 本条立刻红（实测返回 -1）。
     *
     * <p>场景是用户实际遇到的：世界第 12 天，一个 29 岁的居民。
     * 生日 = 12 − 29×96 − offset ≈ −2800，负数，但<b>完全合法</b>。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void negativeBirthdayStillHasAge(GameTestHelper helper) {
        long today = 12L;          // 开档第 12 天（用户截图里就是"第 12 日"）
        long birth = -2800L;       // 一个 29 岁的人被投影到开档之前
        int age = AgingRolls.ageAt(today, birth);
        if (age < 0) {
            helper.fail("负生日（" + birth + "）被当成未知了，ageAt 返回 " + age
                    + "。这是用户实测'享年/年龄什么都没有'的根因");
        }
        // 12 − (−2800) = 2812 天 / 96 ≈ 29 岁
        if (age != 29) {
            helper.fail("负生日的年龄算错了：期望 29，实测 " + age);
        }
        helper.succeed();
    }

    /** 真正未知（哨兵）才返回 -1；负数和 0 都不是未知。 */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void onlySentinelMeansUnknown(GameTestHelper helper) {
        if (AgingRolls.ageAt(100L, AgingRolls.UNKNOWN_DAY) != -1) {
            helper.fail("哨兵值应该判成未知（-1）");
        }
        if (AgingRolls.ageAt(100L, 0L) < 0) {
            helper.fail("birthDay=0 是合法值（第 0 天出生），不该判未知");
        }
        if (AgingRolls.daysSince(100L, -50L) != 150) {
            helper.fail("负起点算天数应得 150，实测 " + AgingRolls.daysSince(100L, -50L)
                    + "（用 `startDay < 0` 判未知会把它丢了）");
        }
        if (AgingRolls.daysSince(100L, AgingRolls.UNKNOWN_DAY) != -1) {
            helper.fail("哨兵起点应返回 -1");
        }
        helper.succeed();
    }

    /**
     * 真实路径：开档早期生成的居民，{@code ensureAging} 之后年龄必须可读。
     *
     * <p><b>必须走 {@link Identity#ensureAging}</b> 而不是手搓 birthDay——
     * 只测纯函数的话，"Identity 把负生日当未知"这个真 bug 照样绿（坑72）。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 100)
    public static void earlyGameResidentHasAge(GameTestHelper helper) {
        long today = 5L; // 开档第 5 天：任何 18 岁以上的人，生日必然为负
        int withAge = 0;
        for (int i = 0; i < 8; i++) {
            Identity id = new Identity();
            id.ensureAging(today, RandomSource.create(7000L + i));
            if (!id.hasAging()) {
                helper.fail("ensureAging 之后应 hasAging（种子 " + (7000L + i) + "）");
            }
            if (id.birthDay() >= 0) {
                helper.fail("开档第 5 天的成年居民，生日不该是正数：" + id.birthDay());
            }
            int age = id.ageYears(today);
            if (age < AgingRolls.MIN_AGE_YEARS) {
                helper.fail("生日 " + id.birthDay() + " 算出的年龄是 " + age
                        + "（应 ≥ " + AgingRolls.MIN_AGE_YEARS
                        + "）。负生日被当未知就是这个结果");
            }
            withAge++;
        }
        if (withAge != 8) {
            helper.fail("8 个人里只有 " + withAge + " 个算得出年龄");
        }
        helper.succeed();
    }

    /** 碑上享年：负生日也要算，不许整行消失。 */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void graveAgeWithNegativeBirthday(GameTestHelper helper) {
        var grave = new GraveyardData.Grave(
                java.util.UUID.randomUUID(), "迟缓阳光", "none", null,
                12L, "other", "" , null,
                -2800L, 36500L, "", 0, 3L, // 生日负、入镇第 3 天
                List.of(), Map.of());
        if (grave.ageYears() != 29) {
            helper.fail("碑上享年应为 29，实测 " + grave.ageYears()
                    + "（`birthDay < 0` 判未知会整行吞掉）");
        }
        if (grave.stayDays() != 9) {
            helper.fail("在镇天数应为 9（12−3），实测 " + grave.stayDays());
        }
        helper.succeed();
    }

    // ==========================================================
    //  二、碑文内容：不许只剩一行死因
    // ==========================================================

    /**
     * 碑文必须<b>至少三行</b>：享年、职业/在镇、死因。
     *
     * <p>用户原话："显示他的生平信息什么都没有，还是只有一个死法。"
     * 反向验证：把 {@code epitaphLines} 里享年那行删掉 ⇒ 只剩 2 行 ⇒ 红。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void epitaphHasMoreThanCause(GameTestHelper helper) {
        var grave = new GraveyardData.Grave(
                java.util.UUID.randomUUID(), "迟缓阳光", "warrior", null,
                12L, "other", "" , null,
                -2800L, 36500L, "title.bmfstown.novice", 3, 3L,
                List.of(), Map.of("mobs_killed", 7));
        List<net.minecraft.network.chat.Component> lines =
                GraveText.epitaphLines(grave, EPOCH);
        if (lines.size() < 3) {
            helper.fail("碑文只有 " + lines.size() + " 行（" + describe(lines)
                    + "）——用户实测'只剩一个死法'就是这个");
        }
        // 第一行不许以标点开头：那是复用"摘要连接片段"留下的排版事故。
        // ⚠ 不能断言中文"享年"——GameTestServer 是 en_us。
        String first = lines.get(0).getString();
        if (first.startsWith("，") || first.startsWith(",") || first.startsWith("、")) {
            helper.fail("碑文第一行以标点开头：" + first
                    + "（行首要自带主语，不能复用摘要的拼接片段）");
        }
        // 第一行必须是"享年"那条 —— 断言 lang key 而不是渲染文字
        if (!lines.get(0).getContents().toString()
                .contains("grave.bmfstown.line.age")) {
            helper.fail("碑文第一行应是享年行，实测：" + lines.get(0));
        }
        helper.succeed();
    }

    /**
     * 天数类不进"统计 Top 3"：它们值有几千，会把击杀这类真统计全部挤掉。
     *
     * <p>反向验证：去掉 {@code topStats} 里的 {@link GraveText#isDateDerived}
     * 过滤 ⇒ 前 3 条必是 days_alive/days_in_town + 一条别的 ⇒ 红。
     *
     * <p>而且这两个 key <b>没有 lang</b>（它们不是统计项，是履历页表头），
     * 混进来会直接印出 {@code gui.bmfstown.resident.stats.k.days_alive}。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void dateDerivedStatsAreExcluded(GameTestHelper helper) {
        Map<String, Integer> stats = new java.util.LinkedHashMap<>();
        stats.put(ResidentLedger.Keys.DAYS_ALIVE, 2812);
        stats.put(ResidentLedger.Keys.DAYS_IN_TOWN, 9);
        stats.put(ResidentLedger.Keys.MOBS_KILLED, 214);
        stats.put(ResidentLedger.Keys.BOOKS_READ, 12);

        List<GraveText.StatEntry> top = GraveText.topStats(stats, 3);
        for (GraveText.StatEntry e : top) {
            if (GraveText.isDateDerived(e.key())) {
                helper.fail("天数类（" + e.key() + "=" + e.value()
                        + "）混进了统计 Top3，会把击杀 214 挤掉并印出原始 key");
            }
        }
        if (top.isEmpty() || !ResidentLedger.Keys.MOBS_KILLED.equals(top.get(0).key())) {
            helper.fail("Top1 应是击杀（214），实测 " + (top.isEmpty() ? "空" : top.get(0).key()));
        }
        helper.succeed();
    }

    /** 真的没有统计项时，碑文仍要给出享年——不许因为统计为空就只剩死因。 */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void emptyStatsStillHasAge(GameTestHelper helper) {
        var grave = new GraveyardData.Grave(
                java.util.UUID.randomUUID(), "新来的", "none", null,
                30L, "other", "", null, -2866L, 36500L, "", 0, 30L,
                List.of(), Map.of());
        List<net.minecraft.network.chat.Component> lines =
                GraveText.epitaphLines(grave, EPOCH);
        if (lines.size() < 2) {
            helper.fail("零统计时碑文只剩 " + lines.size() + " 行（" + describe(lines) + "）");
        }
        boolean hasAge = false;
        for (var c : lines) {
            if (c.getContents().toString().contains("grave.bmfstown.line.age")) {
                hasAge = true;
                break;
            }
        }
        if (!hasAge) {
            helper.fail("零统计时也该有享年行（lang key grave.bmfstown.line.age），实测：" + describe(lines));
        }
        helper.succeed();
    }

    // ==========================================================
    //  三、快照：在镇天数要有值，且未知是 -1
    // ==========================================================

    /**
     * 开档早期的居民，进快照后 {@code ageYears} 与 {@code daysInTown}
     * <b>都不能是 -1</b>——面板靠 {@code >= 0} 决定画不画那一行，
     * 恒 -1 就是用户说的"要啥啥没有"。
     *
     * <p><b>为什么必须走 {@code syncClient()} 而不是直接查 Identity</b>：
     * 客户端面板读的是 {@code DATA_ARCHIVE} 快照，不是 Identity。
     * 只断言 Identity 有值、快照漏传，照样是"面板上没有"——
     * 这正是上一轮"我改了 Identity 就说做完了"的地方（坑72 变体）。
     *
     * <p>反向验证：把 {@code syncClient} 里 {@code daysSince(...)} 那个参数
     * 换回硬编码 {@code -1} ⇒ 本条红。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 100)
    public static void snapshotCarriesAgeAndDaysInTown(GameTestHelper helper) {
        Scene s = scene(helper);
        Resident r = s.resident();
        ServerLevel level = helper.getLevel();
        // ⚠ 必须用<b>代码会用的那个 today</b>（absoluteDay），不能自己拍一个数：
        // GameTestServer 的 dayTime 是跨用例累积的，拍死一个数就与实现口径分家。
        long today = com.bmf.bmfstown.calendar.TownCalendar.absoluteDay(level);

        // 入镇日 = 两天前 ⇒ 在镇 2 天
        r.getIdentity().markArrival(today - 2);
        r.getIdentity().ensureAging(today, RandomSource.create(11L));
        r.syncClient();

        var snap = r.getArchive();
        if (snap.ageYears() < AgingRolls.MIN_AGE_YEARS) {
            helper.fail("快照里的 ageYears 是 " + snap.ageYears()
                    + "（今天是第 " + today + "，生日 " + r.getIdentity().birthDay()
                    + "）——负生日被吞或快照没传，面板就画不出年龄那一行");
        }
        if (snap.daysInTown() != 2) {
            helper.fail("快照里的 daysInTown 应为 2（今天 " + today + "，入镇 "
                    + r.getIdentity().arrivalDay() + "），实测 " + snap.daysInTown());
        }
        helper.succeed();
    }

    /** 场景：镇 + 一个居民（绑到该镇）。 */
    private static Scene scene(GameTestHelper helper) {
        var setup = AlchemyScaffold.prepareTown(
                helper, new BlockPos(1, 0, 1), new BlockPos(7, 0, 7), 0);
        Resident r = helper.spawn(com.bmf.bmfstown.ModEntities.RESIDENT.get(),
                new BlockPos(3, 1, 4));
        r.setNoAi(true);
        r.getIdentity().setTownId(setup.town().id());
        return new Scene(setup.town(), r);
    }

    private record Scene(com.bmf.bmfstown.town.Town town, Resident resident) {
    }

    private static String describe(List<net.minecraft.network.chat.Component> lines) {
        StringBuilder sb = new StringBuilder();
        for (var c : lines) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(c.getString());
        }
        return sb.toString();
    }

    // ==========================================================
    //  四、lang 键与调用点的契约
    // ==========================================================

    /**
     * <b>表头类键不许带占位符</b>：无参 {@code I18n.get(key)} 走
     * {@code String.format}，值里有 {@code %s} 就抛 IllegalFormatException，
     * 原版 catch 成 "Format error: &lt;原文&gt;" 显示出来。
     *
     * <p>用户截图里那行 <code>Format error: 死因 / 第 %s 日</code> 就是这个。
     *
     * <p><b>为什么必须从 classpath 直读 lang</b>：服务端 ResourceManager
     * 只挂 data 包，读不到 assets/lang，{@code I18n.exists} 之类在 GameTest
     * 里恒 false，断言会变成永远绿的空壳（坑69 同款）。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void headerKeysHaveNoPlaceholder(GameTestHelper helper) {
        var zh = langOrNull("zh_cn");
        var en = langOrNull("en_us");
        if (zh == null || en == null) {
            helper.fail("lang 在 classpath 上读不到（zh=" + (zh != null)
                    + ", en=" + (en != null) + "）");
        }
        // 这三个键在界面里都是**无参**调用的（列名/标题/提示），不许有 %
        String[] noArgKeys = {
                "gui.bmfstown.grave_pick.col_name",
                "gui.bmfstown.grave_pick.col_death",
                "gui.bmfstown.grave_pick.title",
                "gui.bmfstown.grave_epitaph.title",
                "gui.bmfstown.grave_epitaph.hint",
                "gui.bmfstown.grave_epitaph.scroll",
        };
        for (String key : noArgKeys) {
            for (var lang : List.of(zh, en)) {
                if (!lang.has(key)) {
                    helper.fail("缺 lang 键：" + key + "（界面上会直接显示原始 key）");
                }
                String v = lang.get(key).getAsString();
                if (v.indexOf('%') >= 0) {
                    helper.fail("键 " + key + " 的值含占位符：" + v
                            + "。它被无参 I18n.get 调用，会显示成 "
                            + "\"Format error: " + v + "\"");
                }
            }
        }
        helper.succeed();
    }

    /** 带参的键必须真的有占位符（否则参数被静默丢掉）。 */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void paramKeysActuallyUseTheirArgument(GameTestHelper helper) {
        var zh = langOrNull("zh_cn");
        if (zh == null) {
            helper.fail("lang 读不到");
        }
        String[] mustHaveParam = {
                "gui.bmfstown.grave_pick.day",
                "gui.bmfstown.grave_pick.hint_confirm",
                "grave.bmfstown.carved",
                "grave.bmfstown.line.age",
                "grave.bmfstown.line.stay",
        };
        for (String key : mustHaveParam) {
            if (!zh.has(key)) {
                helper.fail("缺 lang 键：" + key);
            }
            if (zh.get(key).getAsString().indexOf('%') < 0) {
                helper.fail("键 " + key + " 应该有 %s，实测："
                        + zh.get(key).getAsString() + "（调用方传的参数会被丢掉）");
            }
        }
        helper.succeed();
    }

    /**
     * <b>不许再用聊天栏打碑文</b>：碑文走界面（#146）。
     *
     * <p>用 {@code displayClientMessage} 逐行打碑文的写法会在两处复发：
     * ① 刻完那一刻、② 右键已刻碑。用户明确否掉过（"以后墓志铭会很长，
     * 你也要就这样放聊天框吗"）。这条扫源码，任何一处漏改都抓得到。
     *
     * <p><b>为什么扫源码不扫字节码</b>：方法调用在字节码里没有可读的字符串，
     * 只有常量池里的类名，容易误判。字面量/方法名直接读源文件最准
     * （与 {@code SpeedTierTests} 同款做法）。
     *
     * <p>反向验证：把 {@code handleGraveCarve} 里改回
     * {@code player.displayClientMessage(line, false)} 循环 ⇒ 红。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void epitaphIsNotChatSpam(GameTestHelper helper) {
        var root = java.nio.file.Path.of("..", "src", "main", "java");
        if (!java.nio.file.Files.isDirectory(root)) {
            helper.fail("源码目录不可达：" + root.toAbsolutePath());
            return;
        }
        // 碑文相关文件里，不许出现"遍历 epitaphLines 然后 displayClientMessage"
        String[] files = {
                "com/bmf/bmfstown/block/GraveStoneBlock.java",
                "com/bmf/bmfstown/network/ModNetworking.java",
        };
        for (String rel : files) {
            var p = root.resolve(rel);
            String src;
            try {
                src = java.nio.file.Files.readString(p);
            } catch (java.io.IOException e) {
                helper.fail("读不到 " + rel + "：" + e.getMessage());
                return;
            }
            if (src.contains("for (Component line : com.bmf.bmfstown.town.GraveText.epitaphLines")
                    || src.contains("for (Component line : GraveText.epitaphLines")) {
                helper.fail(rel + " 里还在遍历 epitaphLines 往聊天栏打——"
                        + "碑文该走 GraveEpitaphScreen（#146）");
            }
        }
        helper.succeed();
    }

    // ==========================================================
    //  五、履历页时间区：年龄 + 生日，没有"活了多久"
    //     （#147 第一版是"活了 2829"→改年+天；用户第二次打回后改成年龄+生日）
    // ==========================================================

    /**
     * 履历页头部<b>不许</b>再出现"活了 X 天"，必须是<b>年龄 + 生日</b>。
     *
     * <h2>用户两次打回的过程（这条用例的完整来由）</h2>
     * <ol>
     *   <li>第一版：履历页印"活了 2829"和"29 岁"并排 ⇒
     *       "活了 2829，年龄 29，你觉得像人话吗？"——两个单位量同一个量；</li>
     *   <li>第二版：把"活了 2829"改成"活了 29 年 47 天" ⇒ 单位对齐了，
     *       但用户第二句话更准：<b>"履历显示的应该是他的年龄和出生日期，
     *       而非活了多久，活了多久具体到天无人在意，年龄就能看到大致了。"</b></li>
     * </ol>
     *
     * <p>后者不是精度问题，是<b>信息重复</b>：年龄已经答了"活了多久"，
     * 再报一个同量只是噪音。生日给的是年龄给不出的那半条信息——<b>哪天长一岁</b>。
     * 所以"活得久不久"这一栏被<b>删除</b>，不是被格式化。
     *
     * <p>用源码扫描守三件事：
     * ① 履历页不再读 {@code daysAlive()} / {@code stats.alive_days}；
     * ② 头部确实画了生日一行（{@code stats.birthday} + {@code birthdayText}）；
     * ③ 语言文件里那条"活了"的键已经删掉（留着就是下次又被谁捡回去用）。
     *
     * <p>反向验证：把履历页加回 {@code I18n.get("...stats.alive_days")} ⇒ ①红；
     * 删掉 {@code birthdayText} 调用 ⇒ ②红；把 alive_days 键加回 lang ⇒ ③红。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void ledgerShowsBirthdayNotDaysLived(GameTestHelper helper) {
        var p = java.nio.file.Path.of("..", "src", "main", "java",
                "com", "bmf", "bmfstown", "client", "ResidentScreen.java");
        String src;
        try {
            src = java.nio.file.Files.readString(p);
        } catch (java.io.IOException e) {
            helper.fail("读不到 ResidentScreen.java：" + e.getMessage());
            return;
        }
        // ① 履历页不许再碰"活了几天"
        if (src.contains("stats.alive_days") || src.contains("ledger.daysAlive()")) {
            helper.fail("履历页还在用'活了多久'——用户原话："
                    + "'履历显示的应该是他的年龄和出生日期，而非活了多久'");
        }
        // ② 生日必须真的画出来
        if (!src.contains("gui.bmfstown.resident.stats.birthday")) {
            helper.fail("履历页没有生日一行");
        }
        if (!src.contains("birthdayText(")) {
            helper.fail("生日没有走 birthdayText 换算（会印出裸年月日或负年号）");
        }
        // ③ lang 里那条键必须已经删掉，别留着给下次复用
        var zh = langOrNull("zh_cn");
        var en = langOrNull("en_us");
        if (zh == null || en == null) {
            helper.fail("读不到语言文件");
            return;
        }
        for (var lang : new Object[]{zh, en}) {
            if (((com.google.gson.JsonObject) lang)
                    .has("gui.bmfstown.resident.stats.alive_days")) {
                helper.fail("语言文件里还留着打回过的'活了'键"
                        + "（gui.bmfstown.resident.stats.alive_days），删掉它");
                return;
            }
        }
        helper.succeed();
    }

    /**
     * 生日换算必须对<b>负数绝对天数</b>安全（{@code floorMod} 而不是 {@code %}）。
     *
     * <h2>为什么这是真风险</h2>
     * 生日 = {@code today − (年龄×96 + 偏移)}，29 岁的居民约在 2784 天前，
     * 而纪元是<b>世界第 0 天</b>——所以 <b>{@code birthDay − epoch}
     * 几乎必然为负</b>。Java 的 {@code %} / {@code /} 对负数<b>向零截断</b>：
     * <ul>
     *   <li>{@code -1 % 8 == -1} ⇒ 日号算出 0（历法里没有 0 号）；</li>
     *   <li>{@code -1 / 12 == 0} ⇒ 年份少一年。</li>
     * </ul>
     * 用错就是"公元前 28 年 12 月 0 日"这种一看就假的东西。
     *
     * <p>断言口径：<b>连续 400 天逐日推进，日号必须始终在 1~8、月在 1~12</b>，
     * 且年号单调不跳。这个量级（>4 年）够把跨年、跨月、跨纪元全扫一遍。
     *
     * <p>反向验证：把 {@code dateOf} 里的 {@code Math.floorMod} 换成 {@code %} ⇒ 红
     * （日号立刻出 0）。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void birthdayDateHandlesNegativeDays(GameTestHelper helper) {
        long epoch = 5000L; // 随便一个纪元
        // ⚠ 起点必须落在<b>负数绝对天数</b>上，否则这条用例是空壳（初版踩过）：
        //   初版写 epoch - 30*96 = 2120，仍然是正数，而 % 与 floorMod 在正数上
        //   **完全相同**——注入 bug 后照样绿。绝对天数确实会是负数：世界开了
        //   第 10 天时，一个 29 岁居民的 birthDay = 10 − 2784 = −2774。
        //   所以从 −3000 起步，走 400 天到 −2600，全程在负区。
        long start = -3000L;
        int prevYear = Integer.MIN_VALUE;
        for (int i = 0; i < 400; i++) {
            var d = com.bmf.bmfstown.calendar.TownCalendar.dateOf(epoch, start + i);
            if (d.day() < 1 || d.day() > com.bmf.bmfstown.calendar.TownCalendar.DAYS_PER_MONTH) {
                helper.fail("日号越界：" + d.day() + "（第 " + i + " 天，绝对天数 "
                        + (start + i) + "）——负数上天数用了 % 而不是 floorMod");
                return;
            }
            if (d.month() < 1 || d.month() > com.bmf.bmfstown.calendar.TownCalendar.MONTHS_PER_YEAR) {
                helper.fail("月号越界：" + d.month() + "（第 " + i + " 天）");
                return;
            }
            if (d.year() < prevYear) {
                helper.fail("年号倒流：" + prevYear + " → " + d.year() + "（第 " + i + " 天）");
                return;
            }
            prevYear = d.year();
        }
        // 顺带钉住"世界第 0 天 = 公元 1 年 1 月某日、年号为 1"
        var atEpoch = com.bmf.bmfstown.calendar.TownCalendar.dateOf(epoch, epoch);
        if (atEpoch.year() != 1) {
            helper.fail("纪元当天的年号应为 1（公元 1 年），实际 " + atEpoch.year());
        }
        // 生日早于纪元一年 ⇒ 年号 0（= 公元前 1 年，没有公元 0 年），不能是 -1
        var beforeOne = com.bmf.bmfstown.calendar.TownCalendar.dateOf(
                epoch, epoch - com.bmf.bmfstown.calendar.TownCalendar.DAYS_PER_YEAR);
        if (beforeOne.year() != 0) {
            helper.fail("公元前 1 年的年号应为 0，实际 " + beforeOne.year()
                    + "（把'公元前 1 年'算成了公元前 2 年）");
        }
        helper.succeed();
    }

    // ==========================================================
    //  四、死因必须用原版的整句（#147，用户打回"横死"两字）
    // ==========================================================

    /**
     * 碑文死因行必须是<b>原版死亡消息</b>（{@code death.attack.<msgId>}），
     * 不是自己编的"横死/意外"。
     *
     * <p>用户原话："什么叫横死，原版对于生物不同死法是有描述的，唯一需要新加
     * 的老死的那一批的人的死法……这么充沛的原版资源，你来俩字横死。"
     *
     * <p>反向验证：把 {@code Grave.deathLine()} 改回
     * {@code Component.translatable("death.bmfstown.cause." + cause)} ⇒ 红
     * （因为没有 {@code death.attack.} 前缀）。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void deathLineUsesVanillaMessage(GameTestHelper helper) {
        // 被僵尸打死
        var killed = new GraveyardData.Grave(
                java.util.UUID.randomUUID(), "迟缓阳光", "none", null,
                100L, "killed", "mob", net.minecraft.network.chat.Component.literal("Zombie"),
                -2800L, 36500L, "", 0, 3L,
                List.of(), Map.of());
        String key = killed.deathLine().getContents().toString();
        if (!key.contains("death.attack.mob")) {
            helper.fail("被僵尸打死的碑文应走原版 death.attack.mob，实际：" + key);
        }
        // 原版那句要能带上凶手名（否则就成了"某人被杀死"，不知道被谁杀死）
        if (!containsComponent(killed.deathLine(), "Zombie")) {
            helper.fail("死因里丢了凶手名，渲染出来是：" + killed.deathLine().getString());
        }

        // 摔死：无凶手，也要给出原版那句
        var fell = new GraveyardData.Grave(
                java.util.UUID.randomUUID(), "某人", "none", null,
                50L, "other", "fall", null,
                -2800L, 36500L, "", 0, 3L,
                List.of(), Map.of());
        if (!fell.deathLine().getContents().toString().contains("death.attack.fall")) {
            helper.fail("摔死应走 death.attack.fall，实际："
                    + fell.deathLine().getContents());
        }
        helper.succeed();
    }

    /** 递归找组件树里有没有某段文本（凶手名可能挂在参数位上）。 */
    /**
     * 组件树里有没有这个<b>翻译键</b>（递归查 {@code getContents()}）。
     *
     * <h2>为什么不能用 {@code getString()} 查键</h2>
     * GameTestServer 跑在 en_us 上，{@code getString()} 会把
     * {@code Component.translatable("calendar.bmfstown.date.ce", 3)} 渲染成
     * <b>"AD 3"</b>——**渲染结果里没有键名**。于是"查字符串里有没有
     * calendar.bmfstown.date"必然为假（第一版真这么红了一次）。
     * 要断言"这段是不是走了某个翻译键"，只能查组件结构本身（坑69 的同一类）。
     */
    private static boolean hasTranslationKey(net.minecraft.network.chat.Component c, String key) {
        var contents = c.getContents();
        if (contents instanceof net.minecraft.network.chat.contents.TranslatableContents tr) {
            if (tr.getKey().contains(key)) {
                return true;
            }
            // ⚠ 必须也递归 <b>args</b>：`line.died` 的日期参数是当参数塞进去的
            // （args=[translate(date.ce)]），它不在 siblings 里。
            // 只查 siblings 时这条用例假红了一次（报错里明明印着 date.ce）。
            for (Object arg : tr.getArgs()) {
                if (arg instanceof net.minecraft.network.chat.Component argComp
                        && hasTranslationKey(argComp, key)) {
                    return true;
                }
            }
        }
        for (var s : c.getSiblings()) {
            if (hasTranslationKey(s, key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsComponent(net.minecraft.network.chat.Component c, String text) {
        if (c.getString().contains(text)) {
            return true;
        }
        for (var s : c.getSiblings()) {
            if (containsComponent(s, text)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 寿终（{@code genericKill}）要走 mod 自己的那句，不能显示原版的"s lain"。
     *
     * <p>这是<b>唯一</b>需要新写死因的一类——原版没有"老死"这个伤害类型
     * （用户原话："唯一需要新加的老死的那一批的人的死法"）。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void oldAgeHasOwnLine(GameTestHelper helper) {
        var old = new GraveyardData.Grave(
                java.util.UUID.randomUUID(), "老王", "none", null,
                300L, "old_age", "genericKill", null,
                -2800L, 36500L, "", 0, 3L,
                List.of(), Map.of());
        String key = old.deathLine().getContents().toString();
        if (!key.contains("grave.bmfstown.death.old_age")) {
            helper.fail("寿终应走 grave.bmfstown.death.old_age（原版没有老死），实际：" + key);
        }
        if (key.contains("death.attack.genericKill")) {
            helper.fail("寿终不许套原版的 genericKill（那句是'被杀死'，不是寿终）");
        }
        helper.succeed();
    }

    /** 老档（没有 damageId）必须能降级，不能崩、也不能印出空 key。 */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void legacyGraveFallsBackToCause(GameTestHelper helper) {
        var legacy = new GraveyardData.Grave(
                java.util.UUID.randomUUID(), "老档的人", "none", null,
                100L, "killed", "", null,
                -2800L, 36500L, "", 0, 3L,
                List.of(), Map.of());
        String key = legacy.deathLine().getContents().toString();
        if (!key.contains("death.bmfstown.cause.killed")) {
            helper.fail("老档无 damageId 时应退回粗分类 death.bmfstown.cause.killed，实际：" + key);
        }
        helper.succeed();
    }

    /**
     * 死因的每个 lang 键都必须在<b>两份</b>语言文件里真实存在。
     *
     * <p>守的是"短标签"这条新路径：{@link GraveText#causeShortKey} 会按
     * {@code msgId} 造 {@code death.bmfstown.short.*} 键，<b>造错了不会报错</b>，
     * 界面会直接印出原始 key（"death.bmfstown.short.witherSkull"）。
     * 原版有 87 个 {@code msgId}，光靠手测覆盖不到。
     *
     * <p>反向验证：把任意一条 {@code short.*} 从 zh_cn 删掉 ⇒ 红。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void shortCauseKeysExist(GameTestHelper helper) {
        // 覆盖全部失败方式：生物 / 玩家 / 弹射物 / 环境 / 爆炸 / 火 / 摔 / 水
        String[] ids = {
                "mob", "player", "arrow", "trident", "fireball", "thrown", "sting",
                "witherSkull", "indirectMagic", "thorns", "sonic_boom",
                "lava", "inFire", "onFire", "hotFloor", "fireworks",
                "fall", "flyIntoWall", "outOfWorld", "fallingBlock",
                "drown", "freeze", "dryout",
                "explosion", "badRespawnPoint",
                "cactus", "cramming", "wither", "starve", "generic",
        };
        var zh = langOrNull("zh_cn");
        var en = langOrNull("en_us");
        if (zh == null || en == null) {
            helper.fail("读不到语言文件（classpath 上没有 assets/bmfstown/lang/*.json）");
            return;
        }
        for (String id : ids) {
            String cause = "mob".equals(id) || "player".equals(id) ? "killed" : "other";
            var g = new GraveyardData.Grave(
                    java.util.UUID.randomUUID(), "某人", "none", null,
                    100L, cause, id, null,
                    -2800L, 36500L, "", 0, 3L, List.of(), Map.of());
            String key = GraveText.causeShortKey(g);
            for (var lang : new Object[]{zh, en}) {
                if (!((com.google.gson.JsonObject) lang).has(key)) {
                    helper.fail("伤害源 " + id + " 的短标签键 " + key
                            + " 在语言文件里不存在（界面会印出原始 key）");
                    return;
                }
            }
        }
        // 寿终也要有
        var old = new GraveyardData.Grave(
                java.util.UUID.randomUUID(), "老王", "none", null,
                300L, "old_age", "genericKill", null,
                -2800L, 36500L, "", 0, 3L, List.of(), Map.of());
        if (!zh.has(GraveText.causeShortKey(old))) {
            helper.fail("寿终的短标签键缺失");
        }
        helper.succeed();
    }

    /**
     * 碑文/摘要的死因行<b>不许</b>是那两个字的粗分类——守 #147 的初衷。
     *
     * <p>反向验证：把 {@code epitaphLines} 里的 {@code g.deathLine()}
     * 改回 {@code Component.translatable("death.bmfstown.cause." + g.cause())} ⇒ 红。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void epitaphDeathIsNotTwoWords(GameTestHelper helper) {
        var g = new GraveyardData.Grave(
                java.util.UUID.randomUUID(), "迟缓阳光", "warrior", null,
                100L, "killed", "mob", net.minecraft.network.chat.Component.literal("Zombie"),
                -2800L, 36500L, "title.bmfstown.novice", 3, 3L,
                List.of(), Map.of());
        var lines = GraveText.epitaphLines(g, EPOCH);
        boolean sawCauseLine = false;
        for (var line : lines) {
            String raw = line.getContents().toString();
            if (raw.contains("death.bmfstown.cause.")) {
                helper.fail("碑文死因还在用粗分类（" + raw
                        + "）——用户原话：'什么叫横死'");
            }
            if (raw.contains("death.attack.mob")) {
                sawCauseLine = true;
            }
        }
        if (!sawCauseLine) {
            helper.fail("碑文里找不到原版死亡消息行：" + describe(lines));
        }
        helper.succeed();
    }

    // ==========================================================
    //  六、碑文面必须是"可透出石色的贴花"（#147，用户打回灰板）
    // ==========================================================

    /**
     * 碑文面（{@code grave_face.png}）必须有<b>透明像素</b>，且模型必须
     * 用 {@code cutout} 渲染 + <b>独立薄片</b>贴在外侧。
     *
     * <h2>用户实测的问题</h2>
     * "碑文它是一个全整体不透明的一个绘制……是一整面不透明的材质蒙在了一个石碑上，
     * 其他的那种灰色系黑色系的石碑不明显，但是花岗岩这个很明显，
     * 你会发现碑文的底色是灰色的，而花岗岩石碑本身是红色的。"
     *
     * <p>根因：初版把 {@code grave_face} 直接铺在立方体的 south 面上，
     * 而那张贴图 16×16 <b>全部不透明</b>——等于给石头糊了一张灰纸。
     * 花岗岩是红的，糊上灰纸立刻穿帮；灰石头糊灰纸看不出来。
     *
     * <h2>正确做法（两条都必须满足）</h2>
     * ① 贴图里"碑面底"全透明，只有刻痕/外框是墨色；
     * ② 碑文<b>不能</b>直接铺在方块表面——那样透明处会露出方块内腔（黑洞）。
     *    必须做成一个独立薄片（{@code from.z == to.z 附近}的小平面）贴在表面外侧，
     *    让透明处看到的是<b>背后的真实石面</b>。
     *
     * <p>反向验证：把贴图存成 256 个不透明像素 ⇒ 红；
     * 把 {@code render_type} 删掉 ⇒ 红（solid 层会忽略 alpha）。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void graveFaceIsTransparentDecal(GameTestHelper helper) {
        // ---- ① 模型：必须声明 cutout ----
        var modelPath = java.nio.file.Path.of("..", "src", "main", "resources",
                "assets", "bmfstown", "models", "block", "grave_stone_base.json");
        String model;
        try {
            model = java.nio.file.Files.readString(modelPath);
        } catch (java.io.IOException e) {
            helper.fail("读不到 grave_stone_base.json：" + e.getMessage());
            return;
        }
        if (!model.contains("\"render_type\"") || !model.contains("cutout")) {
            helper.fail("碑文模型必须声明 \"render_type\": \"minecraft:cutout\"，"
                    + "否则 solid 层会忽略 alpha，透明处画成黑块");
        }
        // 贴花必须是独立薄片：元素里要有一个"z 跨度极小"的
        var json = com.google.gson.JsonParser.parseString(model).getAsJsonObject();
        var els = json.getAsJsonArray("elements");
        boolean decal = false;
        for (var el : els) {
            var o = el.getAsJsonObject();
            var from = o.getAsJsonArray("from");
            var to = o.getAsJsonArray("to");
            double dz = Math.abs(to.get(2).getAsDouble() - from.get(2).getAsDouble());
            double dx = Math.abs(to.get(0).getAsDouble() - from.get(0).getAsDouble());
            double dy = Math.abs(to.get(1).getAsDouble() - from.get(1).getAsDouble());
            if (dz < 0.2 && dx > 4 && dy > 4) {
                decal = true;
                // 薄片只许有朝外那一面
                var faces = o.getAsJsonObject("faces");
                if (faces.size() != 1) {
                    helper.fail("碑文薄片只该有朝外一面（否则里侧那面会从背面看到）");
                }
                break;
            }
        }
        if (!decal) {
            helper.fail("找不到碑文薄片：碑文不许直接铺在方块面上"
                    + "（透明处会露出方块内腔），必须做独立小平面贴在表面外侧");
        }

        // ---- ② 贴图：必须有透明像素 ----
        try (java.io.InputStream in = GraveDisplayTests.class.getClassLoader()
                .getResourceAsStream("assets/bmfstown/textures/block/grave_face.png")) {
            if (in == null) {
                helper.fail("classpath 上找不到 grave_face.png");
                return;
            }
            var img = javax.imageio.ImageIO.read(in);
            if (img == null) {
                helper.fail("grave_face.png 不是有效图片");
                return;
            }
            int total = img.getWidth() * img.getHeight();
            int transparent = 0;
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    if (((img.getRGB(x, y) >>> 24) & 0xFF) < 16) {
                        transparent++;
                    }
                }
            }
            if (transparent < total / 3) {
                helper.fail("碑文贴图只有 " + transparent + "/" + total
                        + " 个透明像素——它还是一整面不透明的材质蒙在石碑上，"
                        + "花岗岩上会露出灰色底（用户实测）");
            }

            // ---- ③ 刻痕必须是一条中性灰，不许又黑又白 ----
            // 用户 2026-11-28 第二张截图打回："这太丑了，弄一些灰色条纹就够了，
            // 这又黑又白实在太难看了。"
            //
            // 根因值得记下来：上一版刻痕外框是 0/105（近黑），刻痕下沿又叠了一条
            // 255/45 的"受光棱"。**cutout 层不做 alpha 混合**——它只做
            // "alpha 过阈值就原样画"，所以 45/255 的白会被画成 100% 纯白。
            // 于是设计里想要的"淡高光"渲染成了硬白条，和近黑刻痕交错 = 条形码。
            // 结论：cutout 里 alpha 只有"有/没有"两个状态，**颜色必须直接画准**，
            // 不能靠半透明去调深浅。
            int nearBlack = 0;
            int nearWhite = 0;
            int offGray = 0;
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    int argb = img.getRGB(x, y);
                    int a = (argb >>> 24) & 0xFF;
                    if (a < 16) {
                        continue;
                    }
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    if (r != g || g != b) {
                        offGray++;
                    }
                    int luma = (r + g + b) / 3;
                    // 0x40 / 0xC0 是两个"明显超出灰区"的界限：低于它读起来是黑，
                    // 高于它读起来是白（cutout 下会渲染成硬白条，坑94）。
                    // 定稿贴图是 96/78，离两条界限都还有余量。
                    if (luma < 0x40) {
                        nearBlack++;
                    }
                    if (luma > 0xC0) {
                        nearWhite++;
                    }
                }
            }
            if (offGray > 0) {
                helper.fail("碑文贴图有 " + offGray + " 个非灰色不透明像素——"
                        + "刻痕必须是中性灰（红石头/黑石头上才不会撞色）");
            }
            if (nearBlack > 0) {
                helper.fail("碑文贴图有 " + nearBlack + " 个近黑不透明像素——"
                        + "用户原话：'这又黑又白实在太难看了'");
            }
            if (nearWhite > 0) {
                helper.fail("碑文贴图有 " + nearWhite + " 个近白不透明像素——"
                        + "cutout 层不做 alpha 混合，半透明白会被画成纯白硬条");
            }
        } catch (java.io.IOException e) {
            helper.fail("读 grave_face.png 失败：" + e.getMessage());
            return;
        }
        helper.succeed();
    }

    /** 从 classpath 读语言文件（服务端 ResourceManager 读不到 assets/lang）。 */
    private static com.google.gson.JsonObject langOrNull(String code) {
        try (java.io.InputStream in = GraveDisplayTests.class.getClassLoader()
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

    // ==========================================================
    //  七、碑上必须有死亡日期；历法叫"公元"、不许叫"镇民历/开镇前"
    //     （#149，用户："碑上写了死亡信息但没有死亡时间"）
    // ==========================================================

    /**
     * 碑文必须有一行"死于 <日期>"，而且<b>不是</b>裸天数。
     *
     * <h2>用户原话</h2>
     * "另外墓碑上面写了死亡信息了但是没有死亡时间，赶紧去改吧。"
     *
     * <p>说得很准：{@code deathDay} 这个字段从第一版就存进名册、进存档，
     * <b>却从来没有任何地方显示过它</b>（选人界面那个 "第 %s 日" 是另一回事，
     * 那是裸天数、且只在那儿出现）。一块碑说"享年 47、被杀"而不说哪天，
     * 就是一本没有落款的账。
     *
     * <p>一条用例守三件事：
     * ① 碑文里有一行带 {@code grave.bmfstown.line.died} 键；
     * ② 那一行里出现了<b>由历法换出来</b>的年月部件（{@code calendar.bmfstown.date.*}），
     *    不是裸数字；
     * ③ 传 {@code Long.MIN_VALUE}（拿不到历法）时整行不写，而不是写"死于 0 年"。
     *
     * <p>反向验证：把 {@code epitaphLines} 里那段 dateOf/formatDate 删掉 ⇒ ①红。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void epitaphHasDeathDate(GameTestHelper helper) {
        var g = new GraveyardData.Grave(
                java.util.UUID.randomUUID(), "老王", "none", null,
                1200L, "other", "fall", null,
                -2800L, 36500L, "", 0, 3L, List.of(), Map.of());

        var lines = GraveText.epitaphLines(g, EPOCH);
        boolean sawDied = false;
        for (var line : lines) {
            String raw = line.getContents().toString();
            if (raw.contains("grave.bmfstown.line.died")) {
                sawDied = true;
                // 整行里必须带"日期部件"，且不许是裸数字
                // ⚠ 必须查组件键，不能查 getString()——服务端会渲染成 "Died AD 3"
                if (!hasTranslationKey(line, "calendar.bmfstown.date")) {
                    helper.fail("死亡日期那行没有走历法格式化（会印出裸天数）：" + raw);
                }
            }
        }
        if (!sawDied) {
            helper.fail("碑文里没有死亡日期一行（deathDay 存了却从没显示过）");
        }

        // 拿不到历法时：整行不写，不瞎猜
        var noCal = GraveText.epitaphLines(g, Long.MIN_VALUE);
        for (var line : noCal) {
            if (line.getContents().toString().contains("grave.bmfstown.line.died")) {
                helper.fail("拿不到历法时不该写死亡日期（会印成 0 年）");
            }
        }
        helper.succeed();
    }

    /**
     * 世界上没有"公元 0 年"：{@code year <= 0} 一律是<b>公元前</b>，
     * 且 {@code year=0} 就是"公元前 1 年"（紧挨着公元 1 年）。
     *
     * <h2>为什么单挑这条</h2>
     * 这里是最容易写错一年的一格：{@code 0} 既是"公元前 1 年"，
     * 又很容易被 {@code %s} 直接印成 "公元前 0 年"（那个年份不存在）。
     *
     * <p>反向验证：把 {@code formatYear} 的 {@code 1 - year} 改成 {@code -year} ⇒ 红
     * （报"公元前 0 年"）。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void bceYearHasNoYearZero(GameTestHelper helper) {
        // year=1 ⇒ 走 date.ce
        var ce1 = com.bmf.bmfstown.calendar.TownCalendar.formatYear(1);
        if (!hasTranslationKey(ce1, "calendar.bmfstown.date.ce")) {
            helper.fail("公元 1 年应走 date.ce，实际 " + ce1);
        }
        // year=0 ⇒ 走 date.bce，且参数必须是 1（"公元前 1 年"，不许"公元前 0 年"）
        var bce1 = com.bmf.bmfstown.calendar.TownCalendar.formatYear(0);
        if (hasTranslationKey(bce1, "calendar.bmfstown.date.ce")) {
            helper.fail("year=0 被当成公元了");
        }
        if (!hasTranslationKey(bce1, "calendar.bmfstown.date.bce")) {
            helper.fail("year=0 应走 date.bce（公元前 1 年）");
        }
        if (!containsComponent(bce1, "1")) {
            helper.fail("year=0 的年份参数应为 1（公元前 1 年），实际 " + bce1);
        }
        // year=-27 ⇒ 公元前 28 年（1 - (-27)）
        var bce28 = com.bmf.bmfstown.calendar.TownCalendar.formatYear(-27);
        if (!hasTranslationKey(bce28, "calendar.bmfstown.date.bce")) {
            helper.fail("year=-27 应走 date.bce");
        }
        if (!containsComponent(bce28, "28")) {
            helper.fail("year=-27 应换算成公元前 28 年，实际 " + bce28);
        }
        helper.succeed();
    }

    /**
     * 玩家能看到的字符串里不许再出现"镇民历"或"开镇前"。
     *
     * <h2>为什么这条要守得住</h2>
     * 2026-11-28 我先发明了"开镇前"（历法不属于任何镇，见坑96），
     * 用户又要求整套改成<b>公元 / 公元前</b>。这两件事都容易被后来的
     * 一次"顺手补个文案"覆盖回去——尤其"镇民历"这个词在旧文档/旧注释里到处都是。
     *
     * <p>扫描范围只限<b>语言文件</b>（玩家真看到的）与<b>墓碑/日历相关源码</b>，
     * 不做全局扫（历史决策文档里保留旧称是合理的，那是记录，不是界面）。
     *
     * <p>反向验证：往 zh_cn.json 里塞一条值为"镇民历"的键 ⇒ 红。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void playerFacingTextSaysCE(GameTestHelper helper) {
        for (String code : new String[]{"zh_cn"}) {
            var lang = langOrNull(code);
            if (lang == null) {
                helper.fail("读不到语言文件 " + code);
                return;
            }
            for (var e : lang.entrySet()) {
                // ⚠ 有的值是<b>数组</b>（恐怖线正文的样式组件，见坑71），
                // getAsString() 会直接抛 "Array must have size 1, but has size 2"。
                if (!e.getValue().isJsonPrimitive()
                        || !e.getValue().getAsJsonPrimitive().isString()) {
                    continue;
                }
                String v = e.getValue().getAsString();
                if (v.contains("镇民历")) {
                    helper.fail("语言 " + code + " 的键 " + e.getKey()
                            + " 还在说'镇民历'——用户要求改成公元：" + v);
                    return;
                }
                if (v.contains("开镇前")) {
                    helper.fail("语言 " + code + " 的键 " + e.getKey()
                            + " 用了'开镇前'——历法不属于任何镇（坑96）：" + v);
                    return;
                }
            }
        }
        helper.succeed();
    }

}