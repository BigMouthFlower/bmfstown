package com.bmf.bmfstown.town;

import com.bmf.bmfstown.calendar.TownCalendar;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 碑文的文本组装（2026-11-28 决策 #143）。
 *
 * <h2>为什么单独抽一个类</h2>
 * 同一份名册有两个出口：{@code /bmfstown grave} 命令、墓碑方块右键。
 * <b>两处各写一遍格式化，迟早会漂</b>——命令改了排序、方块没改，
 * 玩家就会看到"命令说杀了 12 个、碑上写 3 个"。所以排序口径与字段顺序
 * <b>只在这里定义一次</b>。
 *
 * <h2>为什么返回 Component 而不是 String</h2>
 * 统计项的显示名在 lang 里，服务端没有语言环境。
 * 服务端拼 {@code Component.translatable(key)}，由客户端渲染。
 * ⚠ <b>绝不在这里做"缺键检测"</b>：服务端 {@code translatable(key).getString()}
 * 拿不到客户端 lang，判缺键必然永绿空壳（坑69）。缺键就让客户端显示原文 key，
 * 那是可接受的兜底。
 */
public final class GraveText {

    /** 碑上最多列几条统计（按次数降序，只列非零）。 */
    public static final int TOP_STATS = 3;

    /** 一条排好序的统计。公开出来是为了让测试能断言"取的确实是最大的前 N 条"。 */
    public record StatEntry(String key, int value) {
    }

    /**
     * 统计项排序：只留非零，按值降序，取前 {@code limit} 条。
     *
     * <p><b>平手时按 key 字典序</b>——否则同分值的两条在两次渲染里可能换位
     * （{@code Map} 的迭代序不保证跨版本稳定），玩家会觉得碑文自己在变。
     *
     * <p><b>⚠ 天数类必须排除（2026-11-28 修）</b>：{@code days_alive} /
     * {@code days_in_town} 会被写进 {@code stats} 表（死亡快照要落档），
     * 但它们的值是<b>几千</b>——按"值降序取前 3"必然霸占全部三个位置，
     * 把"击杀 214"这种真正该上碑的挤掉。而且这两个 key <b>没有 lang</b>
     * （它们不是"统计项"，是履历页表头的三项之一），会印出原始 key。
     * 天数已经在碑文第一行以"享年/在此"的形式出现过，不该再来一遍。
     */
    public static List<StatEntry> topStats(Map<String, Integer> stats, int limit) {
        List<StatEntry> out = new ArrayList<>();
        if (stats == null) {
            return out;
        }
        for (Map.Entry<String, Integer> e : stats.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) {
                continue;
            }
            if (isDateDerived(e.getKey())) {
                continue;
            }
            out.add(new StatEntry(e.getKey(), e.getValue()));
        }
        out.sort((a, b) -> {
            int byValue = Integer.compare(b.value(), a.value());
            return byValue != 0 ? byValue : a.key().compareTo(b.key());
        });
        return out.size() <= limit ? out : new ArrayList<>(out.subList(0, limit));
    }

    /**
     * 这一项是不是"由日期算出来的量"（天数类）。
     *
     * <p>它们进 {@code stats} 只是为了<b>落档</b>（死后没有 today 可算），
     * <b>不是</b>"一生统计"——不进 {@code topStats} 的候选池。
     * 判据集中在这里一处，避免"某个地方漏过滤"。
     */
    public static boolean isDateDerived(String key) {
        return com.bmf.bmfstown.entity.domain.ResidentLedger.Keys.DAYS_ALIVE.equals(key)
                || com.bmf.bmfstown.entity.domain.ResidentLedger.Keys.DAYS_IN_TOWN.equals(key);
    }

    /**
     * 一行摘要：`张三，享年 47，死于公元 3 年 5 月 12 日，在此 12 年，战士，寿终 | 击杀=214 | 做成=88`
     *
     * <p>未知项（{@code -1}）<b>整段不出现</b>，不写"享年 -1"。
     * 老档没有生日/入镇日时这是唯一正确的做法（见 {@code Grave.ageYears} 注释）。
     *
     * @param epochDay 世界历纪元；{@code Long.MIN_VALUE} = 拿不到历法，死亡日期整段不写
     */
    public static Component summary(GraveyardData.Grave g, long epochDay) {
        MutableComponent line = Component.literal(g.name());
        int age = g.ageYears();
        if (age >= 0) {
            line.append(Component.translatable("grave.bmfstown.part.age", age));
        }
        if (epochDay != Long.MIN_VALUE) {
            line.append(Component.translatable("grave.bmfstown.part.died",
                    TownCalendar.formatDate(TownCalendar.dateOf(epochDay, g.deathDay()))));
        }
        int stay = g.stayYears();
        if (stay >= 0) {
            line.append(Component.translatable("grave.bmfstown.part.stay", stay));
        }
        if (hasProfession(g.profession())) {
            // ⚠ 走 lang，不能印原始 id：名册里存的是 "warrior"，
            // 直接拼会印出 "warrior" 而不是"战士"（lang 里 profession.bmfstown.* 现成）。
            line.append(Component.literal("，"))
                    .append(Component.translatable(professionKey(g.profession())));
        }
        line.append(Component.literal("，"))
                .append(g.deathLine());
        for (StatEntry e : topStats(g.stats(), TOP_STATS)) {
            line.append(Component.literal(" | "))
                    .append(Component.translatable(
                            "gui.bmfstown.resident.stats.k." + e.key()))
                    .append(Component.literal("=" + e.value()));
        }
        return line;
    }

    /**
     * 碑文正文（界面/碑上逐项成行）：一行一项，名字单独做标题、不重复。
     *
     * <p>与 {@link #summary} 的差别只是<b>拆行</b>——同一个排序、同一套 key。
     *
     * <h2>为什么时间不并在第一行、而拆成两行</h2>
     * {@link #summary} 是一行挤完的摘要（命令用），时间用"、享年 47、在此 12 年"
     * 这样的<b>连接片段</b>拼在任何位置都读得通。但这里是<b>逐行</b>——
     * 行首要自带主语，不能以逗号开头。初版直接复用那套片段，
     * 于是界面第一行显示成"、享年 47 岁，在此 12 年"，
     * 开头那个逗号是明显的排版事故（2026-11-28 修）。
     *
     * <h2>为什么不写"活了几天"</h2>
     * {@code days_alive} 也在 stats 里（死亡快照要落档），但它是
     * {@link #isDateDerived} 排除项；碑上只写"享年 N 岁"，
     * 精确到天的账留给履历页——碑是纪念物，不是报表。
     *
     * <h2>为什么必须有"死于某日"（2026-11-28 用户："碑上写了死亡信息但没死亡时间"）</h2>
     * 一块碑只说"享年 47、被杀"而不说<b>哪天</b>，等于一本没有落款的账。
     * 而且这正是 {@code deathDay} 这个字段存在的唯一意义——它从第一版就
     * 存进名册、进存档，却<b>从来没有任何地方显示过它</b>（只在选人界面
     * 印过 "第 %s 日" 这种裸天数，那是另一回事）。
     *
     * <p><b>用哪个纪元</b>：世界历（{@link TownCalendar}），显示成
     * "死于公元 3 年 5 月 12 日"。<b>不是"开镇前"</b>——那套历法全世界一份，
     * 不属于任何镇（见 {@code TownCalendar} 类注释、坑96）。
     *
     * @param epochDay 世界历的纪元（{@code TownCalendar.epochDay(level)}）。
     *                 传 {@link Long#MIN_VALUE} 表示"调用方拿不到历法"
     *                 （只有纯单元测试会这么干），此时<b>整行不写</b>而不是瞎猜。
     */
    public static List<Component> epitaphLines(GraveyardData.Grave g, long epochDay) {
        List<Component> out = new ArrayList<>();
        int age = g.ageYears();
        int stay = g.stayYears();
        int stayDays = g.stayDays();
        // 享年：一整行。算不出来（老档）就整行不写，绝不写"享年 -1"。
        if (age >= 0) {
            out.add(Component.translatable("grave.bmfstown.line.age", age));
        }
        // 死亡日期：紧跟在享年后面（"活了多久"→"哪一天走的"，同一个人同一条时间线）。
        if (epochDay != Long.MIN_VALUE) {
            var died = TownCalendar.dateOf(epochDay, g.deathDay());
            out.add(Component.translatable("grave.bmfstown.line.died",
                    TownCalendar.formatDate(died)));
        }
        // 在镇：优先"年"（够了 1 年），不够 1 年写"天"——
        // 一个来了 40 天的人写"在此 0 年"读起来像在嘲笑他。
        if (stay > 0) {
            out.add(Component.translatable("grave.bmfstown.line.stay", stay));
        } else if (stayDays >= 0) {
            out.add(Component.translatable("grave.bmfstown.line.stay_days", stayDays));
        }
        if (hasProfession(g.profession())) {
            out.add(Component.translatable("grave.bmfstown.line.profession",
                    Component.translatable(professionKey(g.profession()))));
        }
        // 死因：原版那句完整描述（"被僵尸杀死"/"试图在岩浆里游泳"），
        // 不是"横死/意外"两个字（#147，用户打回）。
        out.add(g.deathLine());
        for (StatEntry e : topStats(g.stats(), TOP_STATS)) {
            out.add(Component.translatable("gui.bmfstown.resident.stats.k." + e.key())
                    .copy().append(Component.literal("  " + e.value())));
        }
        return out;
    }

    /** "无业"也有格子（名册里存 "none"），但碑上不写"职业：无业"。 */
    private static boolean hasProfession(String id) {
        return id != null && !id.isEmpty() && !"none".equals(id);
    }

    /**
     * 职业 id → lang key。
     *
     * <p><b>名册里存的是序列化 id（"warrior"），不是显示名。</b>
     * 碑文/名单一律走这个 key 由客户端渲染语言，<b>绝不能直接印 id</b>——
     * 那会印出 "warrior" 而不是"战士"（实测踩过，2026-11-28）。
     */
    public static String professionKey(String id) {
        return "profession.bmfstown." + id;
    }

    /**
     * 死因的<b>短标签</b>——只给"一列放不下整句"的地方用（选人界面的行）。
     *
     * <h2>为什么需要"短"这一层</h2>
     * 碑文正文和生平是<b>整句</b>（{@link GraveyardData.Grave#deathLine}，
     * "被僵尸杀死"），读起来是完整事实。但选人界面那一行只有 126px 宽，
     * 一列要塞"死因 + 第 N 日"，整句会被 {@code plainSubstrByWidth} 截成
     * "迟缓阳光被僵尸…"——比"被杀"更难读。
     *
     * <p>所以：<b>列表给类目，正文给原句</b>。这是两件不同的东西，
     * 不是"同一份文案的长短版"。类目仍然复用原版的 {@code msgId} 分组，
     * 不是另编一套（"mob"/"player"/"arrow" 都归"被杀"）。
     *
     * @return lang key
     */
    public static String causeShortKey(GraveyardData.Grave g) {
        if ("old_age".equals(g.cause())) {
            return "death.bmfstown.short.old_age";
        }
        String id = g.damageId() == null ? "" : g.damageId();
        if (id.startsWith("mob") || id.startsWith("player") || id.startsWith("arrow")
                || id.startsWith("trident") || id.startsWith("fireball")
                || id.startsWith("thrown") || id.startsWith("sting")
                || id.startsWith("witherSkull") || id.startsWith("indirectMagic")
                || id.startsWith("thorns") || id.startsWith("sonic_boom")) {
            return "death.bmfstown.short.killed";
        }
        if (id.isEmpty()) {
            // 老档：只有粗分类可依据
            return "death.bmfstown.short." + ("killed".equals(g.cause()) ? "killed" : "other");
        }
        if (id.startsWith("explosion") || id.startsWith("badRespawnPoint")) {
            return "death.bmfstown.short.explosion";
        }
        if (id.startsWith("lava") || id.startsWith("inFire") || id.startsWith("onFire")
                || id.startsWith("hotFloor") || id.startsWith("fireworks")) {
            return "death.bmfstown.short.fire";
        }
        if (id.startsWith("fall") || id.startsWith("flyIntoWall")
                || id.startsWith("outOfWorld") || id.startsWith("falling")) {
            return "death.bmfstown.short.fall";
        }
        if (id.startsWith("drown") || id.startsWith("freeze")
                || id.startsWith("dryout")) {
            return "death.bmfstown.short.elements";
        }
        return "death.bmfstown.short.other";
    }

    private GraveText() {
    }
}