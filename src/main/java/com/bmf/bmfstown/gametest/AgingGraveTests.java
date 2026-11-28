package com.bmf.bmfstown.gametest;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.calendar.TownCalendar;
import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.entity.domain.AgingRolls;
import com.bmf.bmfstown.entity.domain.Identity;
import com.bmf.bmfstown.entity.domain.ResidentLedger;
import com.bmf.bmfstown.town.GraveyardData;
import com.bmf.bmfstown.town.Town;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 年龄 / 寿命 / 终身统计 / 死者名册 / 碑位（2026-11-28，决策 #139~#141）。
 *
 * <p>这些用例各自守一条"设计上容易被顺手改坏"的约定，
 * 每条都做过反向验证（把实现改回错误写法 ⇒ 确认变红且报出精确数字）。
 */
@GameTestHolder(BmfTown.MOD_ID)
@PrefixGameTestTemplate(false)
public class AgingGraveTests {

    /** 场景：镇 + 一个居民（已入镇）。 */
    private record Scene(Town town, Resident resident) {
    }

    private static Scene scene(GameTestHelper helper) {
        var s = com.bmf.bmfstown.gametest.AlchemyScaffold.prepareTown(
                helper, new BlockPos(1, 0, 1), new BlockPos(7, 0, 7), 0);
        Resident r = helper.spawn(com.bmf.bmfstown.ModEntities.RESIDENT.get(),
                new BlockPos(3, 1, 4));
        r.setNoAi(true);
        return new Scene(s.town(), r);
    }

    // ==========================================================
    //  AgingRolls：纯函数（无需世界）
    // ==========================================================

    /** 寿命是正态、不截断。 */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 100)
    public static void lifespanIsNormalAndUncut(GameTestHelper helper) {
        RandomSource rnd = RandomSource.create(20261128L);
        double sum = 0;
        double sumSq = 0;
        int n = 20000;
        int below50 = 0;
        int above90 = 0;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double v = AgingRolls.rollLifespanYears(rnd);
            sum += v;
            sumSq += v * v;
            if (v < 50) {
                below50++;
            }
            if (v > 90) {
                above90++;
            }
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        double mean = sum / n;
        double sd = Math.sqrt(sumSq / n - mean * mean);
        if (mean < 69 || mean > 71) {
            helper.fail("寿命均值应约 70，实测 " + String.format("%.2f", mean));
        }
        if (sd < 7.0 || sd > 9.0) {
            helper.fail("寿命 sd 应约 8，实测 " + String.format("%.2f", sd));
        }
        // 不截断：两端必须有样本
        if (below50 == 0 || above90 == 0) {
            helper.fail("寿命被截断了：<50 样本 " + below50 + " 个，>90 样本 " + above90
                    + " 个（两端各应 >0），实测范围 " + String.format("%.1f", min)
                    + "~" + String.format("%.1f", max));
        }
        double pBelow = below50 * 100.0 / n;
        if (pBelow < 0.3 || pBelow > 1.5) {
            helper.fail("P(v<50) 应约 0.6%，实测 " + String.format("%.2f", pBelow) + "%");
        }
        helper.succeed();
    }

    /** 年龄是整岁、≥18、≤上限。 */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 100)
    public static void ageIsAdultAndWhole(GameTestHelper helper) {
        RandomSource rnd = RandomSource.create(777L);
        long sum = 0;
        int n = 5000;
        for (int i = 0; i < n; i++) {
            double lifespan = AgingRolls.rollLifespanYears(rnd);
            int age = AgingRolls.rollAgeYears(rnd, lifespan);
            if (age < AgingRolls.MIN_AGE_YEARS) {
                helper.fail("年龄不该低于 " + AgingRolls.MIN_AGE_YEARS + "，实测 " + age);
            }
            // 必须留下最少剩余寿命（容许年内偏移吃掉一天）
            int cap = AgingRolls.lifespanCapYears(lifespan);
            if (age > cap) {
                helper.fail("年龄超过寿命倒推上限 " + cap + "，实测 " + age
                        + "（lifespan=" + String.format("%.1f", lifespan) + "）");
            }
            sum += age;
        }
        double mean = (double) sum / n;
        // Erlang(2)+18 均值 30.0；上限截断后略低
        if (mean < 28.0 || mean > 31.0) {
            helper.fail("年龄均值应约 29~30，实测 " + String.format("%.2f", mean));
        }
        helper.succeed();
    }

    /**
     * 生日两个颗粒度：同一"今天"生成的 12 个人，生日<b>不能塌缩成同一天</b>。
     *
     * <p><b>必须走真实路径 {@link Identity#ensureAging}</b>，不能只测
     * {@code rollDayOffset} 本身——只测抽样函数的话，"Identity 忘了用 offset"
     * 这个真 bug 照样绿（坑72：名字里写的行为必须真测到）。
     * 塌缩实现（{@code birthDay = today − age×96}，不抽 offset）下此值恒为 1。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 100)
    public static void birthdayOffsetIsIndependent(GameTestHelper helper) {
        long today = 10000L;
        java.util.Set<Long> offsets = new java.util.HashSet<>();
        for (int i = 0; i < 12; i++) {
            Identity id = new Identity();
            id.ensureAging(today, RandomSource.create(1000L + i));
            if (!id.hasAging()) {
                helper.fail("ensureAging 之后应 hasAging");
            }
            offsets.add((long) Math.floorMod(id.birthDay(), TownCalendar.DAYS_PER_YEAR));
        }
        // 12 人全撞的概率极低（生日悖论下撞一对是常态，全同才是 bug）
        if (offsets.size() < 8) {
            helper.fail("12 个人的生日偏移只有 " + offsets.size()
                    + " 种不同值（应 ≥8）。生日塌缩成同一天 = 坑78");
        }
        helper.succeed();
    }

    /**
     * 在镇天数是<b>算</b>的，不是计数器。
     *
     * <p>判据：{@code today − arrivalDay} 能推出来 ⇒ 不存成计数。
     * 反向验证：改成"每天 ++ 的计数器"后，跨天停机会漏加 —— 这条断言
     * 直接量"两个不同 today 的差值"，计数器实现下第二个数不会跟着走。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 100)
    public static void daysInTownIsComputed(GameTestHelper helper) {
        long arrival = 5000L;
        int d1 = AgingRolls.daysSince(arrival + 300, arrival);
        if (d1 != 300) {
            helper.fail("应为 300 天，实测 " + d1);
        }
        int d2 = AgingRolls.daysSince(arrival + 301, arrival);
        if (d2 != 301) {
            helper.fail("多过一天应为 301 天，实测 " + d2
                    + "（计数器实现漏加会停在 300）");
        }
        // 起点未知 ⇒ -1，绝不许拿 today 冒充（那会让老居民变成"在此 0 年"）
        // ⚠ 2026-11-28 起"未知"是 UNKNOWN_DAY 而不是 -1：生日投影可为负，
        // 用 -1 当哨兵会把合法负生日判成未知（用户实测的全空白 bug）。
        if (AgingRolls.daysSince(9999L, AgingRolls.UNKNOWN_DAY) != -1) {
            helper.fail("起点未知应返回 -1，不许拿 today 冒充");
        }
        helper.succeed();
    }

    /** 年龄到生日才 +1（整数除法）。 */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 100)
    public static void ageIncrementsOnlyOnBirthday(GameTestHelper helper) {
        long year = TownCalendar.DAYS_PER_YEAR;
        long birthDay = 1000L;
        long day0 = birthDay + 29 * year + 40; // 29 岁 + 40 天
        int a = AgingRolls.ageAt(day0, birthDay);
        if (a != 29) {
            helper.fail("应为 29 岁，实测 " + a);
        }
        int b = AgingRolls.ageAt(day0 + (year - 41), birthDay); // 生日前一天
        if (b != 29) {
            helper.fail("生日前一天应仍 29 岁，实测 " + b);
        }
        int c = AgingRolls.ageAt(day0 + (year - 40), birthDay); // 生日当天
        if (c != 30) {
            helper.fail("生日当天应 30 岁，实测 " + c);
        }
        helper.succeed();
    }

    // ==========================================================
    //  Identity：落档与入镇日
    // ==========================================================

    /** 年龄三个字段能往返存档；老档缺键读到 -1。 */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 100)
    public static void agingFieldsRoundtrip(GameTestHelper helper) {
        Identity id = new Identity();
        id.ensureAging(5000L, RandomSource.create(9L));
        if (!id.hasAging()) {
            helper.fail("ensureAging 之后应 hasAging");
        }
        Identity rt = Identity.load(id.save(), false);
        if (rt.birthDay() != id.birthDay() || rt.lifespanDays() != id.lifespanDays()) {
            helper.fail("年龄/寿命没往返成功：" + rt.birthDay() + "/" + rt.lifespanDays());
        }
        // 老档：没有这三个键 ⇒ 未知，绝不许 = today。
        // 2026-11-28：生日的未知哨兵从 -1 换成 UNKNOWN_DAY（见 AgingRolls），
        // 寿命仍是 -1（它永远为正，撞不到负数区间）。
        Identity legacy = Identity.load(new net.minecraft.nbt.CompoundTag(), false);
        if (legacy.birthDay() != AgingRolls.UNKNOWN_DAY
                || legacy.arrivalDay() != AgingRolls.UNKNOWN_DAY
                || legacy.lifespanDays() != -1L) {
            helper.fail("老档缺键应读成未知，实测 birth=" + legacy.birthDay()
                    + " arrival=" + legacy.arrivalDay()
                    + " lifespan=" + legacy.lifespanDays());
        }
        // 旧版本写的 -1 脏值也必须归一成未知（否则会算成"活了天文数字天"）
        net.minecraft.nbt.CompoundTag dirty = new net.minecraft.nbt.CompoundTag();
        dirty.putLong("BirthDay", -1L);
        dirty.putLong("ArrivalDay", -1L);
        Identity migrated = Identity.load(dirty, false);
        if (migrated.birthDay() != AgingRolls.UNKNOWN_DAY
                || migrated.arrivalDay() != AgingRolls.UNKNOWN_DAY) {
            helper.fail("旧档里的 -1 哨兵没归一成未知：birth=" + migrated.birthDay()
                    + " arrival=" + migrated.arrivalDay());
        }
        helper.succeed();
    }

    /** ensureAging 幂等：第二次调不许变（防每日重抽）。 */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 100)
    public static void ensureAgingIdempotent(GameTestHelper helper) {
        Identity id = new Identity();
        id.ensureAging(5000L, RandomSource.create(31L));
        long birth = id.birthDay();
        long life = id.lifespanDays();
        id.ensureAging(9999L, RandomSource.create(32L)); // 换天换种子再调
        if (id.birthDay() != birth || id.lifespanDays() != life) {
            helper.fail("第二次 ensureAging 改了值（生日/l 寿命必须只抽一次）");
        }
        helper.succeed();
    }

    /**
     * 入镇日：从过客标记补出，且<b>必须在 clearWanderer 之前</b>。
     *
     * <p><b>必须调真实入口 {@code Wanderers.onAdopted}</b>，不能只测 Identity 上
     * 那两个方法——只测方法的话，"onAdopted 里顺序写反"这个真 bug 照样绿
     * （坑72：用例名里写的行为必须真测到）。
     *
     * <p>反向验证：把 onAdopted 里两行顺序对调 ⇒ 此条红（详见 §8）。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void arrivalDaySurvivesClearWanderer(GameTestHelper helper) {
        Scene s = scene(helper);
        Resident r = s.resident();
        ServerLevel level = helper.getLevel();
        java.util.UUID townId = s.town().id();

        // 造一个"第 18 天刷出、现在被领养"的过客
        level.setDayTime(18 * 24000L + 500L);
        r.getIdentity().markWanderer(level.getDayTime());
        r.getIdentity().setTownId(townId);

        // 走真实入口
        com.bmf.bmfstown.town.Wanderers.onAdopted(r, level);

        if (r.getIdentity().isWanderer()) {
            helper.fail("领养后不该还是过客");
        }
        if (r.getIdentity().wandererSince() != 0L) {
            helper.fail("领养后 wandererSince 应清零，实测 " + r.getIdentity().wandererSince());
        }
        if (r.getIdentity().arrivalDay() != 18L) {
            helper.fail("入镇日应为第 18 天，实测 " + r.getIdentity().arrivalDay()
                    + "（onAdopted 里两行顺序写反会变成 -1）");
        }
        helper.succeed();
    }

    /** 入镇日只写一次：换镇不重写。 */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 100)
    public static void arrivalDayWrittenOnce(GameTestHelper helper) {
        Identity id = new Identity();
        id.markArrival(100L);
        id.markArrival(999L);
        if (id.arrivalDay() != 100L) {
            helper.fail("入镇日应只写一次（100），实测 " + id.arrivalDay());
        }
        helper.succeed();
    }

    // ==========================================================
    //  ResidentLedger：终身累计
    // ==========================================================

    /** 统计只增不清（跨每日结算仍是原值）。 */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void ledgerNeverClears(GameTestHelper helper) {
        Scene s = scene(helper);
        Resident r = s.resident();
        r.getLedger().add(ResidentLedger.Keys.MOBS_KILLED, 1);
        // 跑三次每日结算（换天）
        ServerLevel level = helper.getLevel();
        for (int i = 0; i < 3; i++) {
            level.setDayTime(level.getDayTime() + 24000L);
            r.getStatus().tickDaily(r, level);
        }
        int v = r.getLedger().get(ResidentLedger.Keys.MOBS_KILLED);
        if (v != 1) {
            helper.fail("终身统计跨每日结算应保持 1，实测 " + v + "（并进 Status 的每日清零区会变 0）");
        }
        helper.succeed();
    }

    /** 统计整表能往返存档。 */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 100)
    public static void ledgerRoundtrip(GameTestHelper helper) {
        ResidentLedger l = new ResidentLedger();
        l.add(ResidentLedger.Keys.ITEMS_CRAFTED, 137);
        l.add(ResidentLedger.Keys.BOOKS_WRITTEN, 4);
        ResidentLedger rt = new ResidentLedger();
        rt.loadFrom(l.save());
        if (rt.get(ResidentLedger.Keys.ITEMS_CRAFTED) != 137
                || rt.get(ResidentLedger.Keys.BOOKS_WRITTEN) != 4) {
            helper.fail("统计往返失败：" + rt.get(ResidentLedger.Keys.ITEMS_CRAFTED)
                    + "/" + rt.get(ResidentLedger.Keys.BOOKS_WRITTEN));
        }
        helper.succeed();
    }

    // ==========================================================
    //  GraveyardData：名册 / 待葬 / 碑位
    // ==========================================================

    /** 死亡记录把统计整表抄进名册（死后仍能读到）。 */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void graveKeepsStats(GameTestHelper helper) {
        Scene s = scene(helper);
        Resident r = s.resident();
        ServerLevel level = helper.getLevel();
        r.getIdentity().setTownId(s.town().id());
        r.getLedger().add(ResidentLedger.Keys.MOBS_KILLED, 3);
        r.getIdentity().ensureAging(TownCalendar.absoluteDay(level), RandomSource.create(5L));

        GraveyardData data = GraveyardData.get(level);
        data.record(r, level, r.damageSources().genericKill());

        var g = data.find(r.getUUID());
        if (g == null) {
            helper.fail("死亡后名册里应该有他");
        }
        if (g.stat(ResidentLedger.Keys.MOBS_KILLED) != 3) {
            helper.fail("名册应抄到击杀 3，实测 " + g.stat(ResidentLedger.Keys.MOBS_KILLED)
                    + "（删掉 record 里抄 ledger 那几行必红）");
        }
        if (!"old_age".equals(g.cause())) {
            helper.fail("genericKill 应判 old_age，实测 " + g.cause());
        }
        helper.succeed();
    }

    /** 名册幂等：同一个人写两次只有一条。 */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void graveRecordIdempotent(GameTestHelper helper) {
        Scene s = scene(helper);
        Resident r = s.resident();
        ServerLevel level = helper.getLevel();
        r.getIdentity().setTownId(s.town().id());
        GraveyardData data = GraveyardData.get(level);
        data.record(r, level, r.damageSources().genericKill());
        data.record(r, level, r.damageSources().genericKill());
        long count = data.ofTown(s.town().id()).stream()
                .filter(g -> g.id().equals(r.getUUID())).count();
        if (count != 1) {
            helper.fail("同一 UUID 应只有 1 条记录，实测 " + count);
        }
        helper.succeed();
    }

    /**
     * 碑可挖、回到待葬；名册那条一个字不动。
     *
     * <p><b>⚠ 名册是全局单例</b>（`GraveyardData` 挂主世界，
     * 同一 GameTest 批次里所有用例共享一份），所以<b>不能断言"总共 N 个待葬"</b>
     * ——前一个用例留下的记录会让计数变成 4。
     * 判据必须锚在"这一个 UUID"上：他的待葬状态会随立碑/挖碑翻转。
     *
     * <p>反向验证：把 unburied() 改成"直接返回全部 graves" ⇒ 立碑后仍含他 ⇒ 此条红。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void unburiedGoesBackAfterDig(GameTestHelper helper) {
        Scene s = scene(helper);
        Resident a = s.resident();
        ServerLevel level = helper.getLevel();
        a.getIdentity().setTownId(s.town().id());
        GraveyardData data = GraveyardData.get(level);
        data.record(a, level, a.damageSources().genericKill());

        if (!isUnburied(data, a.getUUID())) {
            helper.fail("刚死应该在待葬列表里");
        }
        BlockPos pos = new BlockPos(4, 1, 4);
        data.bindSlot(pos, a.getUUID());
        if (isUnburied(data, a.getUUID())) {
            helper.fail("立碑后不该还在待葬列表（unburied 不去重会红）");
        }
        if (data.graveAt(pos) == null) {
            helper.fail("碑应该认得他");
        }
        // 挖掉碑 → 回到待葬，但名册记录不许消失
        data.clearSlot(pos);
        if (!isUnburied(data, a.getUUID())) {
            helper.fail("挖掉碑后应回到待葬（过滤掉了已葬的会红）");
        }
        if (data.find(a.getUUID()) == null) {
            helper.fail("挖碑不该删名册记录");
        }
        if (data.graveAt(pos) != null) {
            helper.fail("清位后那块地应该是无名碑");
        }
        helper.succeed();
    }

    /** 该 UUID 是否在待葬列表里（全局单例下唯一可靠的判据）。 */
    private static boolean isUnburied(GraveyardData data, java.util.UUID id) {
        return data.unburied().stream().anyMatch(g -> g.id().equals(id));
    }

    /**
     * 一人只能一块碑：请过他之后请不到第二次。
     *
     * <p><b>同样受全局单例影响</b>：不能断言"takeNextUnburied() == null"
     * （还有别人待葬），只能断言"请到的不再是他"。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void onePersonOneStone(GameTestHelper helper) {
        Scene s = scene(helper);
        Resident a = s.resident();
        ServerLevel level = helper.getLevel();
        a.getIdentity().setTownId(s.town().id());
        GraveyardData data = GraveyardData.get(level);
        data.record(a, level, a.damageSources().genericKill());

        if (!isUnburied(data, a.getUUID())) {
            helper.fail("他应该在待葬列表里");
        }
        // 给他立一块碑（模拟请碑成功）
        data.bindSlot(new BlockPos(4, 1, 4), a.getUUID());

        // 现在他不在待葬列表里 ⇒ 不可能被再请一次
        if (isUnburied(data, a.getUUID())) {
            helper.fail("立碑后还能请到他 = 一人多碑");
        }
        // 换个位置再请：请到的若是别人就没问题
        var next = data.takeNextUnburied();
        if (next != null && next.id().equals(a.getUUID())) {
            helper.fail("同一个人不该能请第二次（一人一碑）");
        }
        helper.succeed();
    }

    /** 碑位表能往返存档（含"谁埋在哪"）。 */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void graveSlotsRoundtrip(GameTestHelper helper) {
        Scene s = scene(helper);
        Resident a = s.resident();
        ServerLevel level = helper.getLevel();
        a.getIdentity().setTownId(s.town().id());
        GraveyardData data = GraveyardData.get(level);
        data.record(a, level, a.damageSources().genericKill());
        BlockPos pos = new BlockPos(2, 1, 5);
        data.bindSlot(pos, a.getUUID());

        // 手动往返（SavedData 的 save/load 是同一对函数）
        var tag = data.save(new net.minecraft.nbt.CompoundTag(),
                level.registryAccess());
        GraveyardData rt = GraveyardData.load(tag, level.registryAccess());
        if (rt.graveAt(pos) == null) {
            helper.fail("碑位没往返成功：读回来的 pos 上没人");
        }
        if (rt.graveAt(pos).stat(ResidentLedger.Keys.BOOKS_READ) != 0) {
            // 这条只是顺手确认 stats 表在往返里没炸（0 是正常的：他没读过书）
            helper.fail("往返后 stats 读取出错");
        }
        helper.succeed();
    }

    // ==========================================================
    //  页签布局（纯常量断言，防"加页不重算宽度"）
    // ==========================================================

    /**
     * 七个页签必须塞进面板宽度。
     *
     * <p><b>读的是真常量</b>：{@code ResidentMenu.TAB_WIDTHS} / {@code TAB_START_X}
     * / {@code TAB_GAP} / {@code GUI_WIDTH}。初版在测试里复刻了一份算式，
     * 改了真数组照样绿——坑72 的原型，已改为直读（坑83）。
     *
     * <p>反向验证：把 {@code ResidentMenu.TAB_WIDTHS} 改回
     * {34,30,30,30,30,30,30} ⇒ 此条红（报出右缘 200 > 176）。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void tabWidthsFitPanel(GameTestHelper helper) {
        int[] widths = com.bmf.bmfstown.menu.ResidentMenu.TAB_WIDTHS;
        int right = com.bmf.bmfstown.menu.ResidentMenu.tabsRightEdge();
        if (right > com.bmf.bmfstown.menu.ResidentMenu.GUI_WIDTH) {
            helper.fail("页签总宽溢出面板：右缘 " + right
                    + " > " + com.bmf.bmfstown.menu.ResidentMenu.GUI_WIDTH);
        }
        if (widths.length != 7) {
            helper.fail("页签数量应为 7（含履历页），实测 " + widths.length);
        }
        // 页编号与页签下标的对应关系：TAB_WIDTHS[i] 必须属于第 i 页。
        // 履历页在 index 3（工作页后），知识页在末尾 —— 两处最容易插错。
        if (widths.length != com.bmf.bmfstown.menu.ResidentMenu.PAGE_KNOWLEDGE + 1) {
            helper.fail("页签片数应等于 PAGE_KNOWLEDGE + 1，实测 " + widths.length
                    + " vs " + (com.bmf.bmfstown.menu.ResidentMenu.PAGE_KNOWLEDGE + 1));
        }
        if (com.bmf.bmfstown.menu.ResidentMenu.PAGE_STATS != 3) {
            helper.fail("履历页应是第 4 片（index 3），实测 PAGE_STATS="
                    + com.bmf.bmfstown.menu.ResidentMenu.PAGE_STATS);
        }
        helper.succeed();
    }

    /**
     * 履历页内容必须装得进面板高度。
     *
     * <p>守的是**纵向**那句账：起始 + 头部 4 行 + 分隔 7px + 统计 12 行 + 底留 6px。
     * 初版只算了统计行（`PANEL_Y + 12*13 + 6 = 178`），把 4 行头部漏在面板外，
     * 最后两三行统计直接画到背景上——**深灰字落在更深背景，不刺眼所以没人报**，
     * 与页签溢出一模一样（坑83）。
     *
     * <p>反向验证：把 {@code STATS_HEADER_ROWS} 改回 0（等价于漏算头部）⇒ 此条必红
     * （报出"内容底 181 > 面板底 181"或直接溢出）。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void statsPageContentFitsPanel(GameTestHelper helper) {
        int contentBottom = com.bmf.bmfstown.menu.ResidentMenu.STATS_CONTENT_BOTTOM();
        int panelHeight = com.bmf.bmfstown.menu.ResidentMenu.STATS_GUI_HEIGHT;
        if (contentBottom > panelHeight) {
            helper.fail("履历页内容溢出面板：内容底 " + contentBottom
                    + " > 面板高 " + panelHeight);
        }
        // 反方向也要守：留白超过一整行说明高度虚高（面板白长一块）
        int slack = panelHeight - contentBottom;
        if (slack < 0 || slack > com.bmf.bmfstown.menu.ResidentMenu.STATS_ROW_H) {
            helper.fail("履历页底部留白异常：" + slack + "px（应 0~"
                    + com.bmf.bmfstown.menu.ResidentMenu.STATS_ROW_H + "px）");
        }
        // 头部必须真的算进去了：4 项时间（名字也占一行）
        if (com.bmf.bmfstown.menu.ResidentMenu.STATS_HEADER_ROWS < 4) {
            helper.fail("履历页头部行数应 ≥ 4（名字+活了多少天+在镇+年龄），实测 "
                    + com.bmf.bmfstown.menu.ResidentMenu.STATS_HEADER_ROWS);
        }
        helper.succeed();
    }
}