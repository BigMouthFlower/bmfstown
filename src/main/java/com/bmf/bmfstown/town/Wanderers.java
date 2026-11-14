package com.bmf.bmfstown.town;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.ModEntities;
import com.bmf.bmfstown.entity.Resident;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * 过客刷新（决策 #61，镇民从哪来）：镇民的自然生成来源。
 *
 * <h2>生命周期</h2>
 * <ol>
 *   <li><b>路过</b>：预约制（频率用户拍板，2026-11-12 补记）——开局 1~3
 *       游戏日内来第一队，之后每班间隔随机 2~8 日（平均 5 日），下次日期
 *       持久化在 {@link WandererState}，重启不重置；命中就在随机在线玩家
 *       （主世界）周围野外刷 1~3 个带 {@code wanderer} 标记的 Resident
 *       （townId 为 null，行为 = 闲逛不干活），聊天框播报"N 个人路过了
 *       附近：名字…"；</li>
 *   <li><b>领养</b>：玩家右键面板选归属（复用 {@code ResidentMenu} 的
 *       BTN_ASSIGN 分支，零新增 UI）→ 赋 townId 时<b>当场删标记</b>（单向），
 *       实体自动寻路进镇；</li>
 *   <li><b>离开</b>：带标记 3 个游戏日无人管 → 移除 + 播"离开了"；
 *       中途死亡 → 播"倒在了途中"（{@code PersonalityEventHandler} 的
 *       死亡事件顺带检测，死亡系统不动工）。</li>
 * </ol>
 *
 * <h2>开关与人口上限（用户拍板）</h2>
 * 公告栏潜行右键切换本镇"过客刷新"开关（{@link WandererState}，默认开）；
 * 镇民满 {@link #POP_CAP} 人自动关（播报一次），玩家可手动重开继续刷。
 *
 * <h2>过客判据铁律</h2>
 * 判"是不是过客"只看 {@code Identity.isWanderer()} 标记，<b>绝不能</b>用
 * {@code townId == null}——已领养镇民被手动清归属后标记早已删除，不会被
 * 当过客刷掉（用户明确纠正）。镇民实体构造时已 {@code setPersistenceRequired()}，
 * 天然不会被原版距离清除。
 */
@EventBusSubscriber(modid = BmfTown.MOD_ID)
public final class Wanderers {

    // ==========================================================
    //  固定参数（决策 #61 拍板口径 + 频率补记；调优后可挪 config）
    // ==========================================================

    /** 首次路过的游戏日区间（含端点）：开局 3 天内必来一次（用户拍板）。 */
    public static final int FIRST_ARRIVAL_MIN = 1;
    public static final int FIRST_ARRIVAL_MAX = 3;

    /**
     * 后续两次路过间隔的游戏日区间（含端点）。2~8 的平均正好 5 日，
     * 即"平均来讲 5 天来一次"（用户拍板）——预约制，不是每日掷骰。
     */
    public static final int INTERVAL_MIN = 2;
    public static final int INTERVAL_MAX = 8;

    /** 一队路过的人数区间（含端点）。 */
    public static final int GROUP_MIN = 1;
    public static final int GROUP_MAX = 3;

    /** 过客期限（游戏日）：超期未领养即离开。 */
    public static final long EXPIRE_DAYS = 3;

    /** 单镇人口上限：满员自动关刷新开关。 */
    public static final int POP_CAP = 30;

    /** 全服同时存在的过客上限（防没人领时堆积）。 */
    public static final int MAX_WANDERERS_ALIVE = 6;

    /** 刷新点离玩家的水平距离区间（格，流浪商人口味）。 */
    public static final int MIN_SPAWN_DIST = 24;
    public static final int MAX_SPAWN_DIST = 48;

    /** 到期扫描周期（tick）：镇民少，5 秒一轮绰绰有余。 */
    private static final int EXPIRE_SCAN_PERIOD = 100;

    /** 落点尝试次数：找不到合规野外点就本日放弃。 */
    private static final int SPAWN_ATTEMPTS = 10;

    // ==========================================================
    //  驱动（LevelTickEvent，只挂主世界）
    // ==========================================================

    private static long lastSeenDay = Long.MIN_VALUE;
    private static long lastExpireScan = Long.MIN_VALUE;

    /** 主世界每 tick 推进（内部按 tick/日去重，绝大多数 tick 纯空判）。 */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || level.dimension() != Level.OVERWORLD) {
            return; // 过客只在主世界出没
        }
        long now = level.getGameTime();

        // 到期扫描：5 秒一轮（镇民数量少，全量过滤开销可忽略）
        if (now - lastExpireScan >= EXPIRE_SCAN_PERIOD) {
            lastExpireScan = now;
            expireScan(level);
        }

        // 路过是预约制（决策 #61 补记）：首次开局 1~3 日内，之后间隔 2~8
        // 日（平均 5）。日期持久化在 WandererState，重启不重置节奏。
        WandererState state = WandererState.get(level);
        long day = level.getDayTime() / 24000L;
        if (day != lastSeenDay) {
            lastSeenDay = day;
            closeOverflowSwitches(level);
        }
        long next = state.nextArrivalDay();
        if (next < 0) {
            // 新世界没排过期：排一个开局 3 日内的首来日（1~3，避开世界刚创的当天）
            state.setNextArrivalDay(day + level.getRandom()
                    .nextInt(FIRST_ARRIVAL_MAX - FIRST_ARRIVAL_MIN + 1) + FIRST_ARRIVAL_MIN);
            return;
        }
        if (day >= next) {
            if (spawningAllowed(level)) {
                trySpawnGroup(level);
            }
            // 到点就排下一班（不管这次有没有人来——开关关着也算排上了）
            state.setNextArrivalDay(day + level.getRandom()
                    .nextInt(INTERVAL_MAX - INTERVAL_MIN + 1) + INTERVAL_MIN);
        }
    }

    /**
     * 刷新闸门（开关语义）：还没有任何城镇时放行（开局铺人，开关挂在
     * 公告栏上，无镇即无公告栏，无从关起）；有镇时任一镇开关开着即放行。
     * 公开供 GameTest 驱动。
     */
    public static boolean spawningAllowed(ServerLevel level) {
        return spawningAllowed(TownSaveData.get(level).allTowns(),
                townId -> WandererState.get(level).isEnabled(townId));
    }

    /**
     * 闸门纯逻辑（与关卡状态解耦，GameTest 直接测这个——共享关卡里
     * 其他测试的镇会干扰全开全关断言）。
     */
    public static boolean spawningAllowed(List<Town> towns,
            java.util.function.Predicate<java.util.UUID> enabled) {
        if (towns.isEmpty()) {
            return true;
        }
        for (Town town : towns) {
            if (enabled.test(town.id())) {
                return true;
            }
        }
        return false;
    }

    // ==========================================================
    //  到期离开
    // ==========================================================

    /** 扫描全部过客：超期未领养 → 移除 + 播报。GameTest 可直接驱动。 */
    public static void expireScan(ServerLevel level) {
        long now = level.getDayTime();
        for (Resident r : wanderers(level)) {
            if (now - r.getIdentity().wandererSince() >= EXPIRE_DAYS * 24000L) {
                broadcast(level, Component.translatable(
                        "msg.bmfstown.wanderers.left", r.getName().getString()));
                r.discard();
            }
        }
    }

    /** 当前活着的全部过客（主世界已加载实体里筛）。 */
    private static List<Resident> wanderers(ServerLevel level) {
        List<Resident> out = new ArrayList<>();
        for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
            if (e instanceof Resident r && r.getIdentity().isWanderer() && r.isAlive()) {
                out.add(r);
            }
        }
        return out;
    }

    // ==========================================================
    //  刷新
    // ==========================================================

    /** 尝试刷一队过客：守卫全过 → 找野外落点 → 生成 + 播报。 */
    private static void trySpawnGroup(ServerLevel level) {
        if (wanderers(level).size() >= MAX_WANDERERS_ALIVE) {
            return; // 没人领也别堆积
        }
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) {
            return;
        }
        ServerPlayer anchor = players.get(level.getRandom().nextInt(players.size()));

        BlockPos spot = findWildSpot(level, anchor.blockPosition());
        if (spot == null) {
            return;
        }

        RandomSource random = level.getRandom();
        int count = GROUP_MIN + random.nextInt(GROUP_MAX - GROUP_MIN + 1);
        StringJoiner names = new StringJoiner("、");
        for (int i = 0; i < count; i++) {
            Resident r = spawnOne(level, spot);
            if (r != null) {
                names.add(r.getName().getString());
            }
        }
        if (names.length() == 0) {
            return;
        }
        broadcast(level, Component.translatable(
                "msg.bmfstown.wanderers.arrived", count, names.toString()));
    }

    /** 在锚点周围找野外落点：领地外、地表有站立空间。找不到返回 null。 */
    @Nullable
    private static BlockPos findWildSpot(ServerLevel level, BlockPos anchor) {
        RandomSource random = level.getRandom();
        String dimension = level.dimension().location().toString();
        for (int attempt = 0; attempt < SPAWN_ATTEMPTS; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            int dist = MIN_SPAWN_DIST + random.nextInt(MAX_SPAWN_DIST - MIN_SPAWN_DIST + 1);
            int x = anchor.getX() + (int) Math.round(Math.cos(angle) * dist);
            int z = anchor.getZ() + (int) Math.round(Math.sin(angle) * dist);
            BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING,
                    new BlockPos(x, 0, z));
            BlockPos stand = top.above();
            if (!level.getFluidState(top).isEmpty()
                    || !level.getFluidState(stand).isEmpty()
                    || !level.getFluidState(stand.above()).isEmpty()) {
                continue; // 水面/流体里不站人
            }
            if (!level.getBlockState(top).isSolidRender(level, top)) {
                continue; // 悬空/树叶顶不当落脚点
            }
            // 领地里不刷（登记制：野外才是"路过"）
            if (TownSaveData.get(level).findContaining(dimension, stand, stand) != null) {
                continue;
            }
            return stand;
        }
        return null;
    }

    /** 生成一个过客：命名、全新档案、打标记、入世界。失败返回 null。 */
    @Nullable
    private static Resident spawnOne(ServerLevel level, BlockPos pos) {
        Resident r = ModEntities.RESIDENT.get().create(level);
        if (r == null) {
            return null;
        }
        r.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                level.getRandom().nextFloat() * 360.0F, 0.0F);
        r.finalizeSpawn(level, level.getCurrentDifficultyAt(pos),
                MobSpawnType.MOB_SUMMONED, null);
        r.getIdentity().markWanderer(level.getDayTime());
        r.syncClient();
        level.addFreshEntity(r);
        return r;
    }

    // ==========================================================
    //  领养（ResidentMenu 赋归属时调用）
    // ==========================================================

    /**
     * 玩家给过客赋了城镇归属：当场删标记（单向）+ 自动寻路进镇 + 上限检查。
     * 非过客调用是纯空判（普通镇民换镇不触发任何行为）。
     */
    public static void onAdopted(Resident resident, ServerLevel level) {
        if (!resident.getIdentity().isWanderer()) {
            return;
        }
        resident.getIdentity().clearWanderer();
        resident.syncClient();

        Town town = TownSaveData.get(level)
                .findTown(resident.getIdentity().townId().toString());
        if (town == null) {
            return;
        }
        // 自动寻路进镇：目的地 = 城镇外框中心 XZ 的地表（寻路失败就原地闲逛，无害）
        BlockPos center = new BlockPos(
                (town.min().getX() + town.max().getX()) / 2,
                town.max().getY(),
                (town.min().getZ() + town.max().getZ()) / 2);
        BlockPos target = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, center);
        resident.getNavigation().moveTo(
                target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0D);

        checkCap(level, town);
    }

    // ==========================================================
    //  人口上限（满 30 自动关开关）
    // ==========================================================

    /** 领养/每日掷点时检查：满员且开关还开着 → 自动关 + 播报一次。公开供 GameTest 驱动。 */
    public static void checkCap(ServerLevel level, Town town) {
        WandererState state = WandererState.get(level);
        if (!state.isEnabled(town.id())) {
            return;
        }
        int pop = populationOf(level, town);
        if (pop >= POP_CAP) {
            state.setEnabled(town.id(), false);
            broadcast(level, Component.translatable(
                    "msg.bmfstown.wanderers.cap_off", town.name(), pop));
        }
    }

    /** 每日掷点前跑一遍：把满员但开关还开着的镇关掉（不在线的镇也管）。 */
    private static void closeOverflowSwitches(ServerLevel level) {
        for (Town town : TownSaveData.get(level).allTowns()) {
            checkCap(level, town);
        }
    }

    /**
     * 镇人口 = 全部归属该镇的已加载镇民数（按城镇外框 + 追加区块扫描，
     * 与手册居民列表同口径）。未加载区块里的不计——上限是近似值，
     * 少计几个只影响"提前/推迟关闸"，无害。
     */
    public static int populationOf(ServerLevel level, Town town) {
        int count = 0;
        for (AABB box : townBoxes(town)) {
            count += level.getEntitiesOfClass(Resident.class, box,
                    r -> town.id().equals(r.getIdentity().townId())).size();
        }
        return count;
    }

    /** 城镇扫描盒序列：0 号外框 + 追加区块（与 TownCensusManager 同款）。 */
    private static List<AABB> townBoxes(Town town) {
        List<AABB> boxes = new ArrayList<>();
        boxes.add(new AABB(
                town.min().getX(), town.min().getY(), town.min().getZ(),
                town.max().getX() + 1, town.max().getY() + 1, town.max().getZ() + 1));
        for (TownZone block : town.townBlocks()) {
            boxes.add(new AABB(
                    block.min().getX(), block.min().getY(), block.min().getZ(),
                    block.max().getX() + 1, block.max().getY() + 1, block.max().getZ() + 1));
        }
        return boxes;
    }

    // ==========================================================
    //  死亡（PersonalityEventHandler 的死亡事件顺带调用）
    // ==========================================================

    /** 过客死在半路：播"倒在了途中"。非过客不吭声（死亡系统另算）。 */
    public static void onWandererDied(Resident dead, ServerLevel level) {
        if (dead.getIdentity().isWanderer()) {
            broadcast(level, Component.translatable(
                    "msg.bmfstown.wanderers.died", dead.getName().getString()));
        }
    }

    // ==========================================================
    //  杂项
    // ==========================================================

    /** 全服聊天框播报（过客事件是世界级新闻，人人可见）。 */
    private static void broadcast(ServerLevel level, Component message) {
        level.getServer().getPlayerList().broadcastSystemMessage(message, false);
    }

    private Wanderers() {
    }
}
