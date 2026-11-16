package com.bmf.bmfstown.gametest;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.entity.domain.Identity;
import com.bmf.bmfstown.town.Town;
import com.bmf.bmfstown.town.WandererState;
import com.bmf.bmfstown.town.Wanderers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * 过客刷新 GameTest（决策 #61）：标记 NBT 往返、领养删标记（单向）、
 * 三天到期移除、人口计数与开关语义（默认开/手动关）。
 */
@GameTestHolder(BmfTown.MOD_ID)
@PrefixGameTestTemplate(false) // 复用现有结构文件（不带小写类名前缀）
public class WandererTests {

    /** 场景：镇 + 一个居民。 */
    private record Scene(Town town, Resident resident) {
    }

    private static Scene scene(GameTestHelper helper) {
        var s = com.bmf.bmfstown.gametest.AlchemyScaffold.prepareTown(
                helper, new BlockPos(1, 0, 1), new BlockPos(7, 0, 7), 0);
        Resident r = helper.spawn(com.bmf.bmfstown.ModEntities.RESIDENT.get(), new BlockPos(3, 1, 4));
        r.setNoAi(true);
        return new Scene(s.town(), r);
    }

    /**
     * 标记语义全弧线：打标记 → NBT 往返保留（含 since）→ 赋归属领养
     * 当场删标记 → 再 NBT 往返确认标记确实永久消失。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void wandererFlagRoundtripAndAdoptionClears(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Scene scene = scene(helper);
        Resident r = scene.resident();

        // 1. 打标记
        long since = level.getDayTime();
        r.getIdentity().markWanderer(since);
        if (!r.getIdentity().isWanderer()) {
            helper.fail("markWanderer 后应为过客");
        }

        // 2. NBT 往返：标记与计时都活着
        Identity roundtrip = Identity.load(r.getIdentity().save(), false);
        if (!roundtrip.isWanderer() || roundtrip.wandererSince() != since) {
            helper.fail("NBT 往返应保留过客标记与计时");
        }

        // 3. 领养：赋归属 + onAdopted → 标记当场删除（单向）
        r.getIdentity().setTownId(scene.town().id());
        Wanderers.onAdopted(r, level);
        if (r.getIdentity().isWanderer()) {
            helper.fail("领养后过客标记应被删除");
        }

        // 4. 再往返：标记确实永久消失（这就是"退镇不得复活成过客"的根）
        Identity after = Identity.load(r.getIdentity().save(), false);
        if (after.isWanderer()) {
            helper.fail("领养后 NBT 往返不应再出现过客标记");
        }
        helper.succeed();
    }

    /** 三天期限：超期未领养 → 到期扫描移除 + （广播在无头服无害）。 */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void wandererExpiresAfterThreeDays(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Scene scene = scene(helper);
        Resident r = scene.resident();
        r.getIdentity().markWanderer(level.getDayTime() - Wanderers.EXPIRE_DAYS * 24000L);

        Wanderers.expireScan(level);

        if (!r.isRemoved()) {
            helper.fail("超期过客应被移除");
        }
        helper.succeed();
    }

    /** 人口计数与开关语义：默认开、未满员不动、手动关可关、过客不计入人口。 */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void populationCountAndSwitchSemantics(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Scene scene = scene(helper);
        Town town = scene.town();
        UUID townId = town.id();

        // 场景居民入镇 + 一个过客（无归属，不计入人口）
        scene.resident().getIdentity().setTownId(townId);
        Resident traveler = helper.spawn(
                com.bmf.bmfstown.ModEntities.RESIDENT.get(), new BlockPos(5, 1, 4));
        traveler.setNoAi(true);
        traveler.getIdentity().markWanderer(level.getDayTime());

        WandererState state = WandererState.get(level);
        if (!state.isEnabled(townId)) {
            helper.fail("开关默认应为开");
        }
        if (Wanderers.populationOf(level, town) != 1) {
            helper.fail("人口应只计已入籍镇民（=1），实际 "
                    + Wanderers.populationOf(level, town));
        }

        // 未满员：上限检查不动开关
        Wanderers.checkCap(level, town);
        if (!state.isEnabled(townId)) {
            helper.fail("未满员不应自动关闸");
        }

        // 手动关（公告栏开关的服务端动作）+ 再读一致
        state.setEnabled(townId, false);
        if (state.isEnabled(townId)) {
            helper.fail("手动关闸后应保持关闭");
        }

        traveler.discard();
        helper.succeed();
    }

    /**
     * 预约制调度（2026-11-12 频率补记）：下次路过日 NBT 往返保留；
     * 开关闸门语义——无镇放行、有镇任一开即放行、全部手动关则不放行。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void wandererSchedulePersistenceAndGate(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Scene scene = scene(helper);
        Town town = scene.town();
        WandererState state = WandererState.get(level);

        // 1. 排期持久化：设值 → load 往返 → 值还在（重启不重置节奏）
        state.setNextArrivalDay(12345L);
        WandererState reloaded = WandererState.load(state.save(
                new net.minecraft.nbt.CompoundTag(),
                level.getServer().registryAccess()), level.getServer().registryAccess());
        if (reloaded.nextArrivalDay() != 12345L) {
            helper.fail("下次路过日应随 SavedData 持久化，实际 "
                    + reloaded.nextArrivalDay());
        }

        // 2. 闸门纯逻辑（不碰共享关卡——其他测试的镇会干扰全开全关断言）：
        //    无镇放行；任一开即放行；全关不放行
        java.util.List<Town> oneTown = java.util.List.of(town);
        if (!Wanderers.spawningAllowed(java.util.List.of(), id -> true)) {
            helper.fail("无镇应放行刷新（开局铺人）");
        }
        if (!Wanderers.spawningAllowed(oneTown, id -> true)) {
            helper.fail("任一镇开关开着应放行刷新");
        }
        if (Wanderers.spawningAllowed(oneTown, id -> false)) {
            helper.fail("全部镇开关关着不应放行刷新");
        }

        // 3. 防倒拨判定（pitfalls 坑11，2026-11-17 用户存档实测触发）：
        //    预约领先 > 班距上限（8）= 时间被 /time set 倒拨，排程作废须重排
        if (!Wanderers.scheduleOrphaned(0L, 104L)) {
            helper.fail("预约领先当前日 104 天应判倒拨（真实存档案例）");
        }
        if (Wanderers.scheduleOrphaned(0L, (long) Wanderers.INTERVAL_MAX)) {
            helper.fail("预约领先=班距上限（正常排程边缘）不应判倒拨");
        }
        if (Wanderers.scheduleOrphaned(50L, 55L)) {
            helper.fail("预约领先 5 天（正常班距内）不应判倒拨");
        }
        if (Wanderers.scheduleOrphaned(10L, -1L)) {
            helper.fail("未排期（next<0）不是倒拨，走首来日分支");
        }
        helper.succeed();
    }
}
