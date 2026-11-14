package com.bmf.bmfstown.entity;

import com.bmf.bmfstown.config.Config;
import com.bmf.bmfstown.entity.profile.ProfessionType;
import com.bmf.bmfstown.town.Town;
import com.bmf.bmfstown.town.TownSaveData;
import com.bmf.bmfstown.town.TownWandMode;
import com.bmf.bmfstown.town.TownZone;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 酒保工作（v0.16 酒馆第一步，决策 #63）：在酒馆区（{@link TownWandMode#TAVERN}
 * 划区，玩家用魔杖自画，无专属方块）采集入库。
 *
 * <h2>一图流</h2>
 * <pre>
 *   库存容器(区内容器方块) ──取空桶/空瓶──▶ 走到目标 ──挤奶/采蜜──▶ 走回容器 ──存奶桶/蜂蜜瓶
 *        ▲                                                                    │
 *        └──────────────────── 循环（每轮一个目标）────────────────────────────┘
 * </pre>
 *
 * <h2>规则</h2>
 * <ul>
 *   <li><b>采集对象</b>：区内的奶牛（每牛每个游戏日一次，按 UUID 记账）与
 *       蜂箱（蜜级满 5 才收，收后归 0）——它们之于酒保，就是讲台之于学者：
 *       区块内现成的东西，登记动作零新增（划区即登记）；</li>
 *   <li><b>工具即消耗</b>：挤奶要空桶、采蜜要空瓶，都从库存容器里取——
 *       玩家往酒馆桶里放桶和瓶子，酒保才能开工，产物也存回同一个容器
 *       （财富闭环：容器就是酒吧的"酒窖"）；</li>
 *   <li><b>缺工具 = 缺料待工</b>（#62 同款语义）：容器里既没桶也没瓶 →
 *       Goal 收工落回闲暇池，节流重扫，补货自动复工；</li>
 *   <li><b>采蜜不激怒蜂群</b>（v1 简化）：酒保当"懂行的人"，采蜜照常出蜂蜜瓶
 *       但蜂群不炸——要不要"无营火会被蜇"的要素，等用户后续拍板；</li>
 *   <li>无订单/无岗位认领系统——酒馆就一个酒窖，先到先得都没必要；
 *       两个酒保挤一个酒馆是玩家自己安排的事。</li>
 * </ul>
 */
public class TavernkeepGoal extends net.minecraft.world.entity.ai.goal.Goal {

    // ---- 常量 ----
    /** 距离目标/容器多近算"到了"（与 WorkerGoal 同款口径）。 */
    private static final double OPEN_RANGE = 3.0D;
    /** 扫描节流：区域扫描（容器+牛+蜂箱）每 100t 一次，防每 tick 全图扫。 */
    private static final int SCAN_PERIOD = 100;
    /** 一轮采集之间的喘息（走回容器存完货后歇 5 秒再规划下一轮）。 */
    private static final int REST_TICKS = 100;
    /** 蜂箱蜜级（BeehiveBlock.HONEY_LEVEL 满值，收蜜门槛）。 */
    private static final int HIVE_FULL = BeehiveBlock.MAX_HONEY_LEVELS;

    // ---- 运行状态 ----
    private final Resident resident;
    private Phase phase = Phase.SCAN;
    /** 本轮采集目标：奶牛或蜂箱坐标（二选一）。 */
    private BlockPos targetPos;
    private Cow targetCow;
    /** 本轮要用的空工具（取到手后持有）。 */
    private ItemStack tool = ItemStack.EMPTY;
    /** 采到的产物（存进容器前持有）。 */
    private ItemStack product = ItemStack.EMPTY;
    /** 当前用的库存容器。 */
    private BlockPos stockPos;
    /** 扫描节流游标。 */
    private long nextScanAt;
    private int retryCooldown;
    /** 奶牛产奶记账：UUID → 上次挤奶的游戏日。 */
    private final Map<UUID, Long> milkedOnDay = new HashMap<>();

    private enum Phase { SCAN, FETCH_TOOL, GO_TARGET, COLLECT, STORE }

    public TavernkeepGoal(Resident resident) {
        this.resident = resident;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    // ==========================================================
    //  Goal 生命周期
    // ==========================================================

    @Override
    public boolean canUse() {
        if (this.resident.getProfession() != ProfessionType.TAVERNKEEP
                || this.resident.isSleeping() || this.resident.isForaging()) {
            return false;
        }
        ServerLevel level = serverLevel();
        if (level == null || !inWorkHours(level)) {
            return false;
        }
        if (!com.bmf.bmfstown.calendar.TownCalendar.get(level).isWorkday(level)) {
            return false; // 周末不营业，进闲暇池
        }
        if (boundTown() == null || tavernZones().isEmpty()) {
            return false; // 没酒馆区 = 这人就是个闲人
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        ServerLevel level = serverLevel();
        if (level == null || this.resident.isSleeping() || this.resident.isForaging()
                || !inWorkHours(level)
                || this.resident.getProfession() != ProfessionType.TAVERNKEEP) {
            return false;
        }
        return boundTown() != null && !tavernZones().isEmpty();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (this.retryCooldown > 0) {
            this.retryCooldown--;
            return;
        }
        ServerLevel level = serverLevel();
        if (level == null) {
            return;
        }
        switch (this.phase) {
            case SCAN -> tickScan(level);
            case FETCH_TOOL -> tickFetchTool(level);
            case GO_TARGET -> tickGoTarget(level);
            case COLLECT -> tickCollect(level);
            case STORE -> tickStore(level);
        }
    }

    // ==========================================================
    //  阶段
    // ==========================================================

    /** 规划一轮：手里有工具直接配目标；没有就去酒窖取。 */
    private void tickScan(ServerLevel level) {
        if (this.resident.tickCount < this.nextScanAt) {
            this.retryCooldown = 20;
            return;
        }
        this.nextScanAt = this.resident.tickCount + SCAN_PERIOD;

        // 手里有空工具 → 按工具找目标
        if (!this.tool.isEmpty()) {
            BlockPos t = findTarget(level, this.tool.getItem());
            if (t != null) {
                this.targetPos = t;
                this.phase = Phase.GO_TARGET;
                navigateTo(t);
                return;
            }
            // 目标没了（牛都挤过/蜜没满）：留着工具，歇一会再扫
            this.retryCooldown = REST_TICKS;
            return;
        }

        // 没工具：先找酒窖
        BlockPos stock = findStock(level);
        if (stock == null) {
            // 酒馆区连个容器都没有——登记了个空房，歇一会
            this.retryCooldown = REST_TICKS;
            return;
        }
        this.stockPos = stock;

        // 区里有什么就取什么工具：能采蜜先看蜜，能挤奶再看牛（就近原则，谁近干谁）
        BlockPos hive = findHoneyedHive(level);
        Cow nearest = findNearestCow(level);
        BlockPos cow = nearest == null ? null : nearest.blockPosition();
        if (hive == null && cow == null) {
            this.retryCooldown = REST_TICKS;
            return;
        }

        // 定目标 + 对应工具
        boolean wantHoney;
        if (hive != null && cow != null) {
            wantHoney = distSq(hive) <= distSq(cow);
        } else {
            wantHoney = hive != null;
        }
        this.targetPos = wantHoney ? hive : cow;
        this.tool = new ItemStack(wantHoney ? Items.GLASS_BOTTLE : Items.BUCKET);
        this.phase = Phase.FETCH_TOOL;
        navigateTo(this.stockPos);
    }

    /** 到酒窖 → 取空桶/空瓶；取不到 = 缺工具（#62 语义）：收工等补货。 */
    private void tickFetchTool(ServerLevel level) {
        if (this.stockPos == null) {
            this.phase = Phase.SCAN;
            return;
        }
        if (!this.resident.blockPosition().closerThan(this.stockPos, OPEN_RANGE)) {
            if (this.resident.getNavigation().isDone()) {
                navigateTo(this.stockPos);
                this.retryCooldown = 20;
            }
            return;
        }
        this.resident.getNavigation().stop();
        Container stock = containerAt(level, this.stockPos);
        if (stock == null) {
            this.stockPos = null;
            this.phase = Phase.SCAN;
            return;
        }
        Item wanted = this.tool.getItem() == Items.GLASS_BOTTLE ? Items.GLASS_BOTTLE : Items.BUCKET;
        int slot = findItemSlot(stock, wanted);
        if (slot < 0) {
            // 缺工具：歇一会再扫（容器里补了桶/瓶自动复工）
            this.tool = ItemStack.EMPTY;
            this.retryCooldown = REST_TICKS;
            this.phase = Phase.SCAN;
            return;
        }
        ItemStack taken = stock.getItem(slot).copy();
        taken.setCount(1);
        stock.getItem(slot).shrink(1);
        stock.setChanged();
        this.tool = taken;
        this.phase = Phase.GO_TARGET;
        navigateTo(this.targetPos);
    }

    /** 走向采集目标（牛是活的会挪窝：到了再校验，跑丢了重扫）。 */
    private void tickGoTarget(ServerLevel level) {
        if (this.targetPos == null) {
            this.phase = Phase.SCAN;
            return;
        }
        if (this.resident.blockPosition().closerThan(this.targetPos, OPEN_RANGE + 1.5D)) {
            this.resident.getNavigation().stop();
            this.phase = Phase.COLLECT;
            return;
        }
        if (this.resident.getNavigation().isDone()) {
            // 目标是牛：跟着牛的新位置重新寻路
            if (this.targetCow != null && this.targetCow.isAlive()) {
                this.targetPos = this.targetCow.blockPosition();
                navigateTo(this.targetPos);
            } else {
                navigateTo(this.targetPos);
            }
            this.retryCooldown = 20; // 走不到（被围死等）：防每 tick 重寻路
        }
    }

    /** 到位 → 采。牛：记账+换货；蜂箱：蜜级归零+换货。均就地播放原版音效。 */
    private void tickCollect(ServerLevel level) {
        if (!this.tool.isEmpty() && this.tool.getItem() == Items.BUCKET) {
            Cow cow = this.targetCow != null && this.targetCow.isAlive()
                    && this.targetCow.blockPosition().closerThan(this.resident.blockPosition(), OPEN_RANGE + 1.5D)
                    ? this.targetCow : nearestCowAt(level, this.targetPos);
            if (cow == null || !milkable(level, cow)) {
                // 牛跑了/刚被挤过：工具留着（桶是通用的），重新配目标
                this.targetCow = null;
                this.targetPos = null;
                this.phase = Phase.SCAN;
                return;
            }
            this.milkedOnDay.put(cow.getUUID(), gameDay(level));
            this.product = new ItemStack(Items.MILK_BUCKET);
            level.playSound(null, cow.blockPosition(), SoundEvents.COW_MILK,
                    SoundSource.NEUTRAL, 1.0F, 1.0F);
        } else {
            BlockState state = level.getBlockState(this.targetPos);
            if (!state.is(Blocks.BEEHIVE) && !state.is(Blocks.BEE_NEST)) {
                this.targetPos = null;
                this.phase = Phase.SCAN;
                return;
            }
            int honey = state.getValue(BeehiveBlock.HONEY_LEVEL);
            if (honey < HIVE_FULL) {
                this.targetPos = null;
                this.phase = Phase.SCAN;
                return;
            }
            level.setBlock(this.targetPos,
                    state.setValue(BeehiveBlock.HONEY_LEVEL, 0), 3);
            this.product = new ItemStack(Items.HONEY_BOTTLE);
            level.playSound(null, this.targetPos, SoundEvents.BEEHIVE_SHEAR,
                    SoundSource.NEUTRAL, 1.0F, 1.0F);
        }
        this.tool = ItemStack.EMPTY;
        this.targetCow = null;
        this.phase = Phase.STORE;
        navigateTo(this.stockPos != null ? this.stockPos : this.targetPos);
    }

    /** 走回酒窖 → 存产物；窖满了先自己拿着，歇一会再存。 */
    private void tickStore(ServerLevel level) {
        BlockPos store = this.stockPos != null ? this.stockPos : this.targetPos;
        if (store == null) {
            this.phase = Phase.SCAN;
            return;
        }
        if (!this.resident.blockPosition().closerThan(store, OPEN_RANGE)) {
            if (this.resident.getNavigation().isDone()) {
                navigateTo(store);
                this.retryCooldown = 20;
            }
            return;
        }
        this.resident.getNavigation().stop();
        Container stock = containerAt(level, store);
        if (stock != null) {
            for (int i = 0; i < stock.getContainerSize(); i++) {
                ItemStack slot = stock.getItem(i);
                if (slot.isEmpty()) {
                    stock.setItem(i, this.product.copy());
                    this.product = ItemStack.EMPTY;
                    stock.setChanged();
                    break;
                }
            }
        }
        if (!this.product.isEmpty()) {
            // 窖满/容器没了：拿着货歇一会（不自扔，不蒸发）
            this.retryCooldown = REST_TICKS;
        } else {
            // 一轮结束，喘口气
            this.retryCooldown = REST_TICKS;
        }
        this.phase = Phase.SCAN;
    }

    // ==========================================================
    //  发现（懒评估，100t 节流）
    // ==========================================================

    private Town boundTown() {
        return this.resident.boundTown();
    }

    /** 本城镇全部酒馆区。 */
    private java.util.List<TownZone> tavernZones() {
        Town town = boundTown();
        return town == null ? java.util.List.of() : town.zones(TownWandMode.TAVERN);
    }

    /** 最近的有库存的容器（区内方块实体扫描，命中即用）。 */
    private BlockPos findStock(ServerLevel level) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (TownZone zone : tavernZones()) {
            for (BlockPos pos : TownZone.iterate(zone)) {
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof Container) {
                    double d = distSq(pos);
                    if (d < bestDist) {
                        bestDist = d;
                        best = pos.immutable();
                    }
                }
            }
        }
        return best;
    }

    /** 按工具类型找目标：桶→可挤奶牛，瓶→满蜜蜂箱。返回目标坐标。 */
    private BlockPos findTarget(ServerLevel level, net.minecraft.world.item.Item toolItem) {
        if (toolItem == Items.BUCKET) {
            Cow cow = findNearestCow(level);
            if (cow != null) {
                this.targetCow = cow;
                return cow.blockPosition();
            }
            return null;
        }
        return findHoneyedHive(level);
    }

    /** 区内最近的可挤奶牛（成年 + 今日未挤）。 */
    private Cow findNearestCow(ServerLevel level) {
        Cow best = null;
        double bestDist = Double.MAX_VALUE;
        for (TownZone zone : tavernZones()) {
            AABB box = zoneAabb(zone).inflate(1.0D);
            for (Cow cow : level.getEntitiesOfClass(Cow.class, box)) {
                if (cow.isBaby() || !milkable(level, cow)) {
                    continue;
                }
                double d = this.resident.blockPosition().distSqr(cow.blockPosition());
                if (d < bestDist) {
                    bestDist = d;
                    best = cow;
                }
            }
        }
        return best;
    }

    /** 目标点附近找牛（goTarget 跟丢后的兜底校验）。 */
    private Cow nearestCowAt(ServerLevel level, BlockPos pos) {
        AABB box = zoneAabbAt(pos).inflate(OPEN_RANGE + 2.0D);
        Cow best = null;
        double bestDist = Double.MAX_VALUE;
        for (Cow cow : level.getEntitiesOfClass(Cow.class, box)) {
            if (cow.isBaby() || !milkable(level, cow)) {
                continue;
            }
            double d = this.resident.blockPosition().distSqr(cow.blockPosition());
            if (d < bestDist) {
                bestDist = d;
                best = cow;
            }
        }
        return best;
    }

    /** 区内最近的满蜜蜂箱/蜂巢。 */
    private BlockPos findHoneyedHive(ServerLevel level) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (TownZone zone : tavernZones()) {
            for (BlockPos pos : TownZone.iterate(zone)) {
                BlockState state = level.getBlockState(pos);
                if ((state.is(Blocks.BEEHIVE) || state.is(Blocks.BEE_NEST))
                        && state.getValue(BeehiveBlock.HONEY_LEVEL) >= HIVE_FULL) {
                    double d = distSq(pos);
                    if (d < bestDist) {
                        bestDist = d;
                        best = pos.immutable();
                    }
                }
            }
        }
        return best;
    }

    /** 这头牛今天挤过没有。 */
    private boolean milkable(ServerLevel level, Cow cow) {
        Long last = this.milkedOnDay.get(cow.getUUID());
        return last == null || last != gameDay(level);
    }

    /** 区域的实体搜索外框（BlockPos 对角 → 6-double AABB）。 */
    private static AABB zoneAabb(TownZone zone) {
        return new AABB(zone.min().getX(), zone.min().getY(), zone.min().getZ(),
                zone.max().getX() + 1.0D, zone.max().getY() + 1.0D,
                zone.max().getZ() + 1.0D);
    }

    /** 单点附近的实体搜索外框。 */
    private static AABB zoneAabbAt(BlockPos pos) {
        return new AABB(pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1.0D, pos.getY() + 1.0D, pos.getZ() + 1.0D);
    }

    // ==========================================================
    //  工具方法
    // ==========================================================

    private long gameDay(ServerLevel level) {
        return level.getDayTime() / 24000L;
    }

    private double distSq(BlockPos pos) {
        return this.resident.blockPosition().distSqr(pos);
    }

    private boolean inWorkHours(ServerLevel level) {
        long t = level.getDayTime() % 24000L;
        int[] window = this.resident.workWindow(); // v0.14：边界带晨夜轴个人偏移
        int start = window[0];
        int end = window[1];
        return start <= end ? (t >= start && t < end) : (t >= start || t < end);
    }

    private ServerLevel serverLevel() {
        return this.resident.level() instanceof ServerLevel sl ? sl : null;
    }

    private void navigateTo(BlockPos pos) {
        this.resident.getNavigation().moveTo(
                pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D,
                Config.STROLL_SPEED.get());
    }

    private Container containerAt(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof Container container ? container : null;
    }

    private static int findItemSlot(Container stock, net.minecraft.world.item.Item item) {
        for (int i = 0; i < stock.getContainerSize(); i++) {
            if (stock.getItem(i).is(item)) {
                return i;
            }
        }
        return -1;
    }
}
