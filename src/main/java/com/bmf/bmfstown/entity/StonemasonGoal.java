package com.bmf.bmfstown.entity;

import com.bmf.bmfstown.config.Config;
import com.bmf.bmfstown.entity.profile.ProfessionType;
import com.bmf.bmfstown.entity.profile.SkillType;
import com.bmf.bmfstown.material.SalvageRecipes;
import com.bmf.bmfstown.town.Town;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * 石匠的工作 Goal（v0.17，2026-11-25 用户立项）。
 *
 * <h2>这个职业干什么</h2>
 * 上班时间扫遍镇上<b>登记过的容器</b>，把里面的旧建材拆成三种通货
 * （石材 / 木材 / 碎沙），再存回容器。玩家拆了房子把砖头锯末丢进箱子，
 * 石匠慢慢把它们化成能用的料，玩家再用切石机把这些料做成新建材。
 *
 * <h2>工作方块 = 切石机（v0.17 修正，2026-11-25 用户实测指出）</h2>
 * 首版没有岗位、直接对着箱子开锤，用户实测："他离得非常远，他也能进行分解"。
 * 这是设计漏洞——没有工作方块＝没有"必须在某个地方干活"的约束，
 * 石匠可以站在原地把全镇的箱子隔空拆完，既不真实也不符合"镇民要上班"的设定。
 * <b>现在石匠走切石机岗位</b>：
 * <ul>
 *   <li>切石机登记进 {@code WorkshopRegister.stations}（与工作台同一个池子）；</li>
 *   <li>石匠开工先认领一台空闲切石机（{@link WorkshopClaims} 一机一人）；</li>
 *   <li>取料 → <b>走回切石机</b> → 在切石机旁锤 → 存料，全程绑定岗位；</li>
 *   <li>岗位被拆 / 认领不到 → 不干活（落回闲暇池），不会隔空拆箱子。</li>
 * </ul>
 * 为什么切石机而不是工作台：石匠产出的是"待切的料"，切石机是他的生产工具，
 * 也让玩家一眼看出"这台机器归他"。工作台留给工人/烟火商（{@code isCraftingTable} 判定不变）。
 *
 * <h2>核心规则：必须凑批，绝不吞货</h2>
 * 台阶这类物品的原版产出是 2（1 料 → 2 台阶），所以兑换率是
 * <b>2 个台阶 = 1 份料</b>。如果允许拆单个台阶而不给料，就是吞货；
 * 如果给它算半份，就要引入小数账。所以规则是：
 * <b>数量凑不够一批（{@link SalvageRecipes#batchOf}）就跳过，等玩家补货</b>。
 * 手上余数永远留在容器里，不动。
 *
 * <h2>效率曲线（与工人同源）</h2>
 * {@code 效率 = 熟练度段位 × 心情(坚韧轴位移阈值) × 专注段}，
 * 再乘特殊书 {@code CRAFT_SPEED} 加成——三项都与 {@link WorkerGoal#craftTicks}
 * 完全同源（用户 2026-11-25 问"效率受不受等级影响"：受，且是同一根曲线）。
 *
 * <h2>骨架复用（硬规矩）</h2>
 * 容器访问/选址/开盖表演/收尾理货/缺料播报全部走 {@link JobKit} 与
 * {@link NavOps}，作息窗口/摸鱼/效率轴照抄 {@link WorkerGoal} 的口径
 * ——新职业禁止手搓（architecture.md 决策 #68）。
 */
public class StonemasonGoal extends Goal {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 与容器/切石机交互的距离（格）。 */
    private static final double OPEN_RANGE = 3.0D;

    /** 待机重试间隔（tick）。 */
    private static final int IDLE_RETRY_TICKS = 100;

    /** 开/关盖动画停留时长（tick）。 */
    private static final int LID_TICKS = 10;

    /** 一批最多拆几组（受手上数量与背包空间约束）。 */
    private static final int BATCH_MAX = 8;

    private enum Phase {
        /** 去认领的切石机旁（新阶段：没有岗位不干活）。 */
        GO_POST,
        SCAN,
        FETCH_GO,
        FETCH_TAKE,
        /** 取到料后<b>走回切石机</b>再开工。 */
        WORK_GO,
        WORK,
        STORE_GO,
        STORE_PUT
    }

    private final Resident resident;

    // ---- 岗位上下文 ----
    private BlockPos postPos;
    private UUID townId;

    // ---- 运行态 ----
    private BlockPos fetchTarget;
    private BlockPos storeTarget;
    private Phase phase = Phase.SCAN;
    private int lidTimer;
    private int retryCooldown;
    private long nextScanAt;

    /**
     * 扫描阶段选中的<b>候选</b>物品（**不代表已经持有**）。
     *
     * <p>坑 65 的核心：这个字段只管"打算去拆什么"，走到箱子、真正扣减成功
     * 才会转成 {@link #workingItem}。绝不能在这里就赋 {@link #workingHeld}
     * ——旧版正是那么写的，于是"还没走到箱子就被打断"时 {@code stop()}
     * 会把<b>还在箱子里的料</b>当成手上这批又存一份，每打断一次箱子多一份。
     */
    @Nullable
    private Item plannedItem;

    /**
     * 真正端在手上的原料与数量。
     *
     * <p><b>语义铁律（坑 65）</b>：只有"已从容器扣减成功"才允许赋值。
     * 这保证了 {@code stop()} 的退料逻辑（见到非空就退）永远退的是
     * 真·已离开箱子的那一批，退一次不会多出一份。
     */
    @Nullable
    private Item workingItem;
    private int workingHeld;
    /** 手上这批料的来源容器：退料时优先回原位，玩家不会发现"料跑箱子了"。 */
    @Nullable
    private BlockPos workingFrom;

    /** 拆出来的通货（拆完统一入库；入库失败留在身上下次再存）。 */
    private final List<ItemStack> pending = new ArrayList<>();
    /** 缺料待工标记：容器里没有可拆的东西 → 落回闲暇池闲逛，补货自动复工。 */
    private boolean shortageIdle;
    private boolean running;

    public StonemasonGoal(Resident resident) {
        this.resident = resident;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /** 面板状态显示用：当前阶段序号。 */
    public int phaseOrdinal() {
        return this.phase.ordinal();
    }

    /** 面板状态显示用：Goal 是否在执行。 */
    public boolean isRunningGoal() {
        return this.running;
    }

    /** 面板状态显示用：是否已认领切石机岗位。 */
    public boolean hasClaim() {
        return this.postPos != null;
    }

    /**
     * 只读状态快照（<b>仅供 GameTest 观测</b>，游戏逻辑不得依赖）。
     *
     * <p>为什么必须暴露这个：坑 65 那个刷物品 bug 发生在
     * "已扫描、还没走到箱子" 的窗口里——旧版此时 {@code workingHeld} 已被
     * 提前赋值，{@code stop()} 就会把还在箱子里的料再存一份。测试要精确地
     * 在这个窗口里打断他，就必须能看见"有没有落账"，而这个状态是私有的。
     *
     * @param phase      当前阶段序号（对应 {@code wphase.*} 语言键）
     * @param heldCount  <b>已真正从容器扣减</b>的原料数量（0 = 没端着任何料）
     * @param planned    是否已选中候选物品但还没取（FETCH_GO 窗口）
     * @param pendingOut 已产出但还没入库的通货堆数
     */
    public record TestSnapshot(int phase, int heldCount, boolean planned, int pendingOut) {
    }

    /** 只读状态快照（仅 GameTest 用，见 {@link TestSnapshot}）。 */
    public TestSnapshot testSnapshot() {
        return new TestSnapshot(this.phase.ordinal(), this.workingHeld,
                this.plannedItem != null, this.pending.size());
    }

    /**
     * 仅 GameTest：把"已从容器取走某批料"这个状态直接摆出来（含真实扣减）。
     *
     * <p>为什么需要这个钩子：坑 65 的两个洞都发生在 {@code stop()} 的退料
     * 路径上，而"让 AI 恰好走到那一步"受摸索鱼/寻路/作息影响，测试不稳定。
     * 这里把前置状态<b>连同容器扣减一起</b>摆好（扣减与记账同源，正是
     * {@code tickFetchTake} 的做法），再让测试去调 {@code stop()} 验守恒。
     *
     * @param chest  要扣减的容器
     * @param item   料
     * @param count  扣减数量（会按 batch 向下取整）
     * @return 实际落账的数量（0 = 箱里不够一批，什么都没做）
     */
    public int testTakeIntoHandForTest(Container chest, Item item, int count, BlockPos fromPos) {
        int batch = SalvageRecipes.batchOf(new ItemStack(item));
        if (count < batch) {
            return 0; // 凑不够一批：与 Goal 的"绝不吞货"口径一致
        }
        // 真扣减（走 tickFetchTake 的同一条私有路径，不复制逻辑）
        int taken = takeInBatchesIntoHand(chest, item);
        if (taken <= 0) {
            return 0;
        }
        this.workingItem = item;
        this.workingHeld = taken;
        this.workingFrom = fromPos;
        this.plannedItem = null;
        return taken;
    }

    /**
     * 仅 GameTest：只摆"我已选好要拆什么，但还没走到箱子"这个状态。
     *
     * <p>这正是用户反复打断的那个窗口。修复后的语义要求此时
     * {@code workingHeld == 0}（料还在箱子里，没资格算"手上"）。
     */
    public void testPlanOnlyForTest(Item item) {
        this.plannedItem = item;
        this.workingItem = null;
        this.workingHeld = 0;
        this.workingFrom = null;
    }

    /**
     * 仅 GameTest：摆一批"已拆出、还没入库"的通货（模拟 {@code tickWork}
     * 结算完但 {@code STORE_PUT} 还没执行即被打断）。
     */
    public void testAddPendingForTest(ItemStack out) {
        this.pending.add(out);
    }

    // ==========================================================
    //  Goal 生命周期
    // ==========================================================

    @Override
    public boolean canUse() {
        if (this.resident.jobSuspended()) {
            return false; // 城外回城期间挂起（#91b）
        }
        if (this.resident.getProfession() != ProfessionType.STONEMASON
                || this.resident.isSleeping() || this.resident.isForaging()) {
            return false;
        }
        ServerLevel level = serverLevel();
        if (level == null || !inWorkHours(level)) {
            return false;
        }
        if (!com.bmf.bmfstown.calendar.TownCalendar.get(level).isWorkday(level)) {
            return false; // 周末不上班
        }
        if (tryGoofOff(level)) {
            return false;
        }
        // 城镇里没有登记容器 / 没有切石机：连扫描都省了（节流）
        Town town = this.resident.boundTown();
        if (town == null) {
            return false;
        }
        if (town.workshop().containers.isEmpty() || town.workshop().stations.isEmpty()) {
            this.nextScanAt = this.resident.tickCount + 60;
            return false;
        }
        if (this.resident.tickCount < this.nextScanAt) {
            return false;
        }
        // 缺料待工：上次全池无可拆物 → 补货前不蹲岗，留在闲暇池
        if (this.shortageIdle) {
            if (!hasSalvageable(level)) {
                this.nextScanAt = this.resident.tickCount + 60;
                return false;
            }
            this.shortageIdle = false;
        }
        // 认领岗位：没有空闲切石机就不开工（这是"必须到岗"的硬门）
        if (!claimPost(level)) {
            this.nextScanAt = this.resident.tickCount + 60;
            return false;
        }
        return hasSalvageable(level);
    }

    @Override
    public boolean canContinueToUse() {
        if (this.resident.jobSuspended()) {
            return false;
        }
        ServerLevel level = serverLevel();
        if (level == null || this.resident.isSleeping() || this.resident.isForaging()
                || !inWorkHours(level)) {
            return false;
        }
        if (this.resident.getProfession() != ProfessionType.STONEMASON) {
            return false;
        }
        if (this.shortageIdle) {
            return false;
        }
        // 岗位被拆 / 城镇没了 → 停止（stop 释放认领，下轮重新找台）
        return townAlive(level) && (this.postPos == null || isStonecutter(level, this.postPos));
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.running = true;
        this.retryCooldown = 0;
        this.phase = Phase.GO_POST;
        this.fetchTarget = null;
        this.storeTarget = null;
        this.plannedItem = null;
        // 注意：workingItem/workingHeld **不在这里清**（坑 65）。
        // 正常流程 stop() 已把它们清空；万一有残留（旧存档/异常路径），
        // 那是真·手上的料，开工首件事应该是退回而不是抹掉（抹掉=吞掉）。
        if (this.workingItem != null && this.workingHeld > 0) {
            ServerLevel level = serverLevel();
            if (level != null) {
                returnHeldInput(level);
            }
        }
        LOGGER.debug("[BmfTown][石匠] {} 开工（岗位 {}）",
                this.resident.getName().getString(),
                this.postPos == null ? "无" : this.postPos.toShortString());
    }

    @Override
    public void stop() {
        this.running = false;
        ServerLevel level = serverLevel();
        // ==========================================================
        //  退料顺序（坑 65 重写，2026-11-25 用户实测刷物品）
        // ==========================================================
        //  ① 手上那批**原料**：只有在 tickFetchTake 真扣成功后才非空，
        //     退一次恰好抵掉扣一次，绝不会多出一份。
        //  ② pending 那批**通货**：拆完的产物，还没入库就必须退，否则凭空消失。
        //     旧版这里只有 giveBack（扫背包）+ pending.clear() —— pending 是
        //     Goal 内存里的 List，不在背包，giveBack 扫不到，clear() 直接吞掉。
        //  ③ 背包里别的货：走 giveBack 收尾理货。
        // 顺序不能反：原料先回箱，否则通货占满位置可能让原料塞不下而掉地。
        returnHeldInput(level);
        returnPendingCargo(level);
        // 收尾理货（JobKit 铁律）：身上别的建材都退登记容器；
        // 够不着就挂待还任务（#72 不隔空放货）
        if (level != null) {
            Town town = this.resident.boundTown();
            if (town != null) {
                List<BlockPos> pool = JobKit.validContainers(level, town.workshop());
                JobKit.giveBack(level, this.resident, pool, StonemasonGoal::isCargo);
            }
        }
        releaseClaim();
        this.fetchTarget = null;
        this.storeTarget = null;
        this.plannedItem = null;
        this.phase = Phase.SCAN;
        this.retryCooldown = IDLE_RETRY_TICKS;
        this.resident.setWorkerVisual(false);
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
        // 认领心跳：持有者持续刷新时间戳，失踪 30 秒后别的镇民可接管
        if (this.postPos != null) {
            WorkshopClaims.put(this.postPos.asLong(), this.resident.getUUID());
        }
        switch (this.phase) {
            case GO_POST -> tickGoPost(level);
            case SCAN -> tickScan(level);
            case FETCH_GO -> tickFetchGo(level);
            case FETCH_TAKE -> tickFetchTake(level);
            case WORK_GO -> tickWorkGo(level);
            case WORK -> tickWork(level);
            case STORE_GO -> tickStoreGo(level);
            case STORE_PUT -> tickStorePut(level);
        }
        this.resident.setWorkerVisual(this.phase != Phase.SCAN && this.phase != Phase.GO_POST);
    }

    // ==========================================================
    //  阶段
    // ==========================================================

    /** 走到认领的切石机旁：到岗后才开始扫描容器。 */
    private void tickGoPost(ServerLevel level) {
        if (this.postPos == null || !isStonecutter(level, this.postPos)) {
            // 岗位没了：重新认领
            if (!claimPost(level)) {
                this.retryCooldown = IDLE_RETRY_TICKS;
                return;
            }
        }
        switch (NavOps.approach(this.resident, this.postPos,
                OPEN_RANGE, Config.STROLL_SPEED.get())) {
            case STUCK -> {
                // 到不了这台：拉黑并换一台
                NavOps.blocked(this.resident, this.postPos);
                releaseClaim();
                this.retryCooldown = IDLE_RETRY_TICKS;
            }
            case MOVING -> {
            }
            case ARRIVED -> this.phase = Phase.SCAN;
        }
    }

    /**
     * 扫描：找"有可拆建材、且能凑够一批"的最近容器。
     *
     * <p>身上还有 pending 就先入库，否则选箱子。扫描是 O(容器数 × 槽位)，
     * 登记池通常几十个箱子，每 100 tick 一次、且 canUse 已节流，成本可接受。
     */
    private void tickScan(ServerLevel level) {
        // 身上还有上次没存进去的通货：先入库，别急着拆新的
        if (hasCargo()) {
            this.phase = Phase.STORE_GO;
            return;
        }
        Town town = this.resident.boundTown();
        if (town == null) {
            this.retryCooldown = IDLE_RETRY_TICKS;
            return;
        }
        List<BlockPos> pool = JobKit.validContainers(level, town.workshop());
        if (pool.isEmpty()) {
            this.shortageIdle = true;
            this.nextScanAt = this.resident.tickCount + 60;
            return;
        }
        BlockPos best = null;
        Item bestItem = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos from = this.postPos != null ? this.postPos : this.resident.blockPosition();
        for (BlockPos pos : pool) {
            Container c = JobKit.containerAt(level, pos);
            if (c == null) {
                continue;
            }
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack slot = c.getItem(i);
                SalvageRecipes.Rule rule = SalvageRecipes.ruleOf(slot);
                if (rule == null || slot.getCount() < rule.batch()) {
                    continue; // 不可拆，或凑不够一批（绝不吞货）
                }
                // 以岗位为锚：石匠不想到处乱跑（用户要求"在切石机上工作"）
                double d = pos.distSqr(from);
                if (d < bestDist) {
                    bestDist = d;
                    best = pos;
                    bestItem = slot.getItem();
                }
            }
        }
        if (best == null) {
            // 全池没有可拆的：收工进闲暇池，补货 60t 内自动复工
            this.shortageIdle = true;
            this.nextScanAt = this.resident.tickCount + 60;
            LOGGER.debug("[BmfTown][石匠] {} 没有可拆的建材，收工闲逛",
                    this.resident.getName().getString());
            return;
        }
        this.fetchTarget = best;
        // **只记"打算拆什么"，绝不记数量**（坑 65：这里若写 workingHeld，
        // 玩家在石匠走过去之前打断他，stop() 就会把还在箱子里的料又存一份）。
        this.plannedItem = bestItem;
        navigateTo(this.fetchTarget);
        this.phase = Phase.FETCH_GO;
    }

    private void tickFetchGo(ServerLevel level) {
        if (this.fetchTarget == null) {
            this.phase = Phase.SCAN;
            return;
        }
        switch (NavOps.approach(this.resident, this.fetchTarget,
                OPEN_RANGE, Config.STROLL_SPEED.get())) {
            case STUCK -> {
                this.fetchTarget = null;
                this.retryCooldown = IDLE_RETRY_TICKS;
                this.phase = Phase.SCAN;
            }
            case MOVING -> {
            }
            case ARRIVED -> {
                this.phase = Phase.FETCH_TAKE;
                this.lidTimer = 0;
            }
        }
    }

    /**
     * 取货：开盖 → 停顿 → 按批取整 → 关盖。
     *
     * <p><b>关键：取出来的料不放背包，只记在 {@link #workingHeld} 里</b>。
     * 理由（v0.17 自查修正）：若先塞背包再在 WORK 阶段扣，背包满时
     * {@code stowOrDrop} 会把料掉在地上，之后扣不到就变成"料已掉地、石匠走了"。
     * 现在原料从箱子直接进"手中"，WORK 结算后直接产出通货，全程不碰背包，
     * 只有"背包满"这一条路径根本不会出现。
     */
    private void tickFetchTake(ServerLevel level) {
        if (this.fetchTarget == null) {
            this.phase = Phase.SCAN;
            return;
        }
        if (this.lidTimer == 0) {
            this.resident.containerFx(level, this.fetchTarget, true);
            this.lidTimer = LID_TICKS;
            return;
        }
        if (--this.lidTimer > 0) {
            return;
        }
        Container chest = JobKit.containerAt(level, this.fetchTarget);
        this.resident.containerFx(level, this.fetchTarget, false);
        if (chest == null || this.plannedItem == null) {
            this.fetchTarget = null;
            this.plannedItem = null;
            this.phase = Phase.SCAN;
            return;
        }
        // 直接从箱子拿走整批，扣减与记录同时完成（不经过背包）
        BlockPos fromPos = this.fetchTarget;
        int taken = takeInBatchesIntoHand(chest, this.plannedItem);
        if (taken <= 0) {
            // 走过来的路上被玩家拿走了：换下一个
            this.fetchTarget = null;
            this.plannedItem = null;
            this.phase = Phase.SCAN;
            return;
        }
        // **扣减成功才落账**（坑 65）：到这里料确实已经离开箱子了，
        // 现在把 workingItem/workingHeld 立起来，stop() 退料才对得上账。
        this.workingItem = this.plannedItem;
        this.workingHeld = taken;
        this.workingFrom = fromPos;
        this.plannedItem = null;
        this.fetchTarget = null;
        // 拿到料 → 走回切石机再锤（"在切石机上工作"的实体体现）
        this.phase = Phase.WORK_GO;
        navigateToPost();
    }

    /** 走回岗位（切石机）；岗位丢了就重新认领，认不到就换一台。 */
    private void tickWorkGo(ServerLevel level) {
        if (this.postPos == null || !isStonecutter(level, this.postPos)) {
            releaseClaim();
            if (!claimPost(level)) {
                // 一手料没处放：退回容器（绝不吞货），本轮放弃
                returnHeldInput(level);
                this.retryCooldown = IDLE_RETRY_TICKS;
                this.phase = Phase.SCAN;
                return;
            }
            navigateToPost();
            return;
        }
        switch (NavOps.approach(this.resident, this.postPos,
                OPEN_RANGE, Config.STROLL_SPEED.get())) {
            case STUCK -> {
                // 走不回岗位：把这批料退回箱子，避免揣着料卡死
                returnHeldInput(level);
                NavOps.blocked(this.resident, this.postPos);
                releaseClaim();
                this.retryCooldown = IDLE_RETRY_TICKS;
            }
            case MOVING -> {
            }
            case ARRIVED -> {
                this.workTicksLeft = salvageTicks(); // 到岗才开始计工时
                this.phase = Phase.WORK;
            }
        }
    }

    /**
     * 拆解：在切石机旁锤 {@link #workTicksLeft} 拍，再按
     * {@link SalvageRecipes#evaluate} 结算——一次结清整批。
     *
     * <p>取货时已保证取的是整批，所以余数为 0。
     */
    private void tickWork(ServerLevel level) {
        if (this.workingItem == null || this.workingHeld <= 0) {
            this.phase = Phase.SCAN;
            return;
        }
        // 就地锤一会儿（象征在干活，也符合"消耗上班时间"的设定）
        if (--this.workTicksLeft > 0) {
            this.resident.getNavigation().stop();
            this.resident.getLookControl().setLookAt(
                    this.postPos.getX() + 0.5, this.postPos.getY() + 0.5,
                    this.postPos.getZ() + 0.5);
            return;
        }
        ItemStack batch = new ItemStack(this.workingItem, this.workingHeld);
        SalvageRecipes.Exchange ex = SalvageRecipes.evaluate(batch);
        if (ex == null) {
            // 理论上不该发生（取货时已按批校验）；兜底把料还给登记容器/掉脚边
            LOGGER.warn("[BmfTown][石匠] {} 兑换结算失败（{}×{}），原料退还",
                    this.resident.getName().getString(),
                    this.workingHeld, new ItemStack(this.workingItem).getHoverName().getString());
            returnHeldInput(level);
            this.phase = Phase.SCAN;
            return;
        }
        // 料已经在取货时从箱子里拿走了，这里不需要再扣背包
        ItemStack out = ex.line().stack(ex.output());
        this.pending.add(out);
        this.resident.addSkillXp(SkillType.CRAFTING, ex.output() * Config.SALVAGE_XP_PER_UNIT.get());
        // 终身统计（2026-11-28）：按拆出的份数记（与经验同一口径）
        this.resident.getLedger().add(
                com.bmf.bmfstown.entity.domain.ResidentLedger.Keys.MATERIALS_SAVED, ex.output());

        LOGGER.debug("[BmfTown][石匠] {} 拆了 {}×{} → {}×{}",
                this.resident.getName().getString(),
                ex.consumed(), new ItemStack(this.workingItem).getHoverName().getString(),
                ex.output(), ex.line().item().getDescription().getString());

        this.workingItem = null;
        this.workingHeld = 0;
        this.workingFrom = null;
        // 还有别的可拆的：继续扫（守着岗位找下一个箱子）
        this.phase = Phase.SCAN;
    }

    /** 入库：选最近的有空位容器，把 pending 全部倒进去。 */
    private void tickStoreGo(ServerLevel level) {
        if (this.storeTarget == null) {
            Town town = this.resident.boundTown();
            if (town == null) {
                this.retryCooldown = IDLE_RETRY_TICKS;
                return;
            }
            List<BlockPos> pool = JobKit.validContainers(level, town.workshop());
            this.storeTarget = JobKit.nearest(level, pool, null, this.resident,
                    c -> canAcceptPending(c));
            if (this.storeTarget == null) {
                // 全满：通货留在身上，待机等玩家清箱（不丢不刷）
                this.retryCooldown = IDLE_RETRY_TICKS;
                return;
            }
            navigateTo(this.storeTarget);
        }
        switch (NavOps.approach(this.resident, this.storeTarget,
                OPEN_RANGE, Config.STROLL_SPEED.get())) {
            case STUCK -> {
                this.storeTarget = null;
                this.retryCooldown = IDLE_RETRY_TICKS;
            }
            case MOVING -> {
            }
            case ARRIVED -> {
                this.phase = Phase.STORE_PUT;
                this.lidTimer = 0;
            }
        }
    }

    private void tickStorePut(ServerLevel level) {
        if (this.storeTarget == null) {
            this.phase = Phase.SCAN;
            return;
        }
        if (this.lidTimer == 0) {
            this.resident.containerFx(level, this.storeTarget, true);
            this.lidTimer = LID_TICKS;
            return;
        }
        if (--this.lidTimer > 0) {
            return;
        }
        Container chest = JobKit.containerAt(level, this.storeTarget);
        this.resident.containerFx(level, this.storeTarget, false);
        if (chest == null) {
            this.storeTarget = null;
            this.phase = Phase.STORE_GO;
            return;
        }
        for (ItemStack st : this.pending) {
            JobKit.storeItem(chest, st);
            // storeItem 会消耗 stack 的数量；没进完的留身上下轮再存
        }
        this.pending.removeIf(ItemStack::isEmpty);
        this.storeTarget = null;
        // 存完回岗位，别越跑越远（石匠绑在切石机上）
        this.phase = this.postPos != null ? Phase.WORK_GO : Phase.SCAN;
    }

    // ==========================================================
    //  岗位认领
    // ==========================================================

    /**
     * 认领一台空闲切石机（最近优先）。
     *
     * <p>石匠从自己脚下开始找最近的，尽量少走路。
     * 与工人/烟火商共池（{@link WorkshopClaims}），但两者判定方块类型不同
     * （他们认工作台，石匠认切石机），实际不会互抢。
     */
    private boolean claimPost(ServerLevel level) {
        releaseClaim();
        Town town = this.resident.boundTown();
        if (town == null) {
            return false;
        }
        UUID self = this.resident.getUUID();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (Long p : town.workshop().stations) {
            BlockPos pos = BlockPos.of(p);
            if (!isStonecutter(level, pos)) {
                continue; // 不是切石机（可能是工作台/已拆）：跳过
            }
            if (NavOps.blocked(this.resident, pos)) {
                continue; // 寻路黑名单：放弃过的台 10 秒内不认领
            }
            if (WorkshopClaims.isTaken(p, self)) {
                continue;
            }
            double d = pos.distSqr(this.resident.blockPosition());
            if (d < bestDist) {
                bestDist = d;
                best = pos;
            }
        }
        if (best == null) {
            return false;
        }
        WorkshopClaims.put(best.asLong(), self);
        this.postPos = best;
        this.townId = town.id();
        return true;
    }

    private void releaseClaim() {
        if (this.postPos != null) {
            WorkshopClaims.release(this.postPos.asLong(), this.resident.getUUID());
            this.postPos = null;
        }
    }

    /** 城镇是否还活着（存在且同维度）。 */
    private boolean townAlive(ServerLevel level) {
        Town town = this.resident.boundTown();
        return town != null && town.dimension()
                .equals(level.dimension().location().toString());
    }

    // ==========================================================
    //  工具方法
    // ==========================================================

    /** 拆解用时（tick）：与工人同口径（熟练度×心情×坚韧×专注 + 特殊书）。 */
    private int workTicksLeft = 0;

    /**
     * 拆一批的耗时：{@code 基础 / 效率}。
     *
     * <p>效率算式与 {@link WorkerGoal#craftTicks()} <b>逐项同源</b>
     * （用户 2026-11-25 问"效率受不受等级影响"）：
     * <ol>
     *   <li>熟练度段位：{@code SkillTiers.efficiencyOf}（Lv1=0.6 … Lv10=1.5）；</li>
     *   <li>心情乘区：低于坚韧轴位移后的阈值 → ×0.7；</li>
     *   <li>专注段：±5%；</li>
     *   <li>特殊书 {@code CRAFT_SPEED}（《天工开物》《考工记》）。</li>
     * </ol>
     * 技能线是 {@code SkillType.CRAFTING}（拆解算合成手艺），
     * 所以工人练出来的合成熟练度可以直接用来拆——同一只手艺。
     */
    private int salvageTicks() {
        double efficiency = com.bmf.bmfstown.entity.profile.EfficiencyMath.total(
                this.resident.getGrowth().skillXp(SkillType.CRAFTING),
                this.resident.getStatus().mood(), false,
                this.resident.axisValue(com.bmf.bmfstown.entity.profile.PersonalityAxis.GRIT),
                this.resident.axisValue(com.bmf.bmfstown.entity.profile.PersonalityAxis.FOCUS));
        // 特殊书合成速度加成（与工人/烟火同一乘区）
        efficiency *= 1.0 + this.resident.learnedEffectBonus(
                com.bmf.bmfstown.book.SpecialBookEffect.Type.CRAFT_SPEED) / 100.0;
        if (efficiency < 0.1D) {
            efficiency = 0.1D;
        }
        return Math.max(1, (int) Math.round(
                Config.SALVAGE_TICKS_PER_BATCH.get() / efficiency));
    }

    /**
     * 从容器里按"整批"拿走指定物品，**不进背包**，只在 {@link #workingHeld} 记账。
     *
     * <p>返回实际取到的数量（保证是 batch 的整数倍）。
     * 为什么不过背包：见 {@link #tickFetchTake} 的注释——背包满会把料掉在地上。
     * 这些料在 WORK 阶段被"消耗"（兑换成通货），是逻辑上的一手持料一手出货，
     * 物理上不存在"背包放不下"的问题。若 Goal 中途被打断（stop），
     * 这批料已经不在箱子里了——由 {@link #stop} 的理货路径负责兜底
     * （把 {@link #workingHeld} 对应的原料退回容器）。
     */
    private int takeInBatchesIntoHand(Container chest, Item target) {
        int batch = SalvageRecipes.batchOf(new ItemStack(target));
        int have = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack slot = chest.getItem(i);
            if (slot.is(target)) {
                have += slot.getCount();
            }
        }
        int usable = (Math.min(have, BATCH_MAX * batch) / batch) * batch;
        if (usable <= 0) {
            return 0;
        }
        int need = usable;
        for (int i = 0; i < chest.getContainerSize() && need > 0; i++) {
            ItemStack slot = chest.getItem(i);
            if (!slot.is(target)) {
                continue;
            }
            int move = Math.min(slot.getCount(), need);
            slot.shrink(move);
            if (slot.isEmpty()) {
                chest.setItem(i, ItemStack.EMPTY);
            }
            need -= move;
        }
        chest.setChanged();
        return usable - need;
    }

    /**
     * 把手上那批料退回容器（走不回岗位/认领不到/被打断时用，绝不吞货）。
     *
     * <p><b>只退"真·已从容器扣减"的那批</b>（坑 65）：调用方不需要自己判，
     * 本方法见到 {@link #workingItem} 为空直接返回——这正是旧版 spawn 刷物品
     * 那个洞的反面：记账前提是扣减成功，所以退一次恰好抵一次。
     *
     * <p>退料优先级：**原箱优先**（玩家不会发现料"跑箱子了"）→ 最近的能收容器
     * → 背包 → 掉脚边。退完无条件清账，避免下次 stop 退第二遍。
     */
    private void returnHeldInput(ServerLevel level) {
        if (this.workingItem == null || this.workingHeld <= 0) {
            this.workingItem = null;
            this.workingHeld = 0;
            this.workingFrom = null;
            return;
        }
        ItemStack back = new ItemStack(this.workingItem, this.workingHeld);
        boolean placed = false;
        if (level != null) {
            // ① 原箱优先：料从哪来回哪去
            if (this.workingFrom != null) {
                Container origin = JobKit.containerAt(level, this.workingFrom);
                if (origin != null && JobKit.hasSpaceFor(origin, back.getItem())) {
                    JobKit.storeItem(origin, back);
                    placed = back.isEmpty();
                }
            }
            // ② 原箱没了/满了：退最近的能收容器
            if (!placed && !back.isEmpty()) {
                Town town = this.resident.boundTown();
                List<BlockPos> pool = town != null
                        ? JobKit.validContainers(level, town.workshop()) : List.of();
                BlockPos target = JobKit.nearest(level, pool, null, this.resident,
                        c -> JobKit.hasSpaceFor(c, back.getItem()));
                if (target != null && target.distSqr(this.resident.blockPosition())
                        <= JobKit.RETURN_REACH * JobKit.RETURN_REACH) {
                    Container c = JobKit.containerAt(level, target);
                    if (c != null) {
                        JobKit.storeItem(c, back);
                        placed = back.isEmpty();
                    }
                }
            }
        }
        // ③ 退不进容器：塞背包，塞不下才掉脚边（绝不吞）
        if (!back.isEmpty()) {
            JobKit.stowOrDrop(this.resident, back);
        }
        this.workingItem = null;
        this.workingHeld = 0;
        this.workingFrom = null;
    }

    /**
     * 拆出来的通货（{@link #pending}）退回登记容器。
     *
     * <p><b>坑 65 的第二个洞</b>：旧版 {@code stop()} 只调 {@code giveBack}
     * 然后 {@code pending.clear()}。但 {@code pending} 是 Goal 内存里的
     * {@code List}，<b>不在背包里</b>，{@code giveBack} 扫背包根本扫不到它，
     * 紧跟着的 {@code clear()} 就把这批通货直接抹了——玩家看到的是"拆完
     * 被打断，产物凭空消失"。
     *
     * <p>现在显式倒进容器（原箱优先不够格——通货是产物，任何能收的都行），
     * 塞不下才进背包/掉地；倒完才清列表。
     */
    private void returnPendingCargo(@Nullable ServerLevel level) {
        if (this.pending.isEmpty()) {
            return;
        }
        if (level != null) {
            Town town = this.resident.boundTown();
            List<BlockPos> pool = town != null
                    ? JobKit.validContainers(level, town.workshop()) : List.of();
            for (ItemStack st : this.pending) {
                if (st.isEmpty()) {
                    continue;
                }
                // 挨个能收的容器试（走 JobKit.storeItem，绝不隔空/不丢）
                for (BlockPos pos : pool) {
                    Container c = JobKit.containerAt(level, pos);
                    if (c == null || !JobKit.hasSpaceFor(c, st.getItem())) {
                        continue;
                    }
                    JobKit.storeItem(c, st);
                    if (st.isEmpty()) {
                        break;
                    }
                }
                if (!st.isEmpty()) {
                    JobKit.stowOrDrop(this.resident, st); // 背包兜底再满掉脚边
                }
            }
        } else {
            for (ItemStack st : this.pending) {
                if (!st.isEmpty()) {
                    JobKit.stowOrDrop(this.resident, st);
                }
            }
        }
        this.pending.clear();
    }

    /** 容器池里是否至少有一处可拆（凑够一批）。 */
    private boolean hasSalvageable(ServerLevel level) {
        Town town = this.resident.boundTown();
        if (town == null) {
            return false;
        }
        for (long p : town.workshop().containers) {
            Container c = JobKit.containerAt(level, BlockPos.of(p));
            if (c == null) {
                continue;
            }
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack slot = c.getItem(i);
                SalvageRecipes.Rule rule = SalvageRecipes.ruleOf(slot);
                if (rule != null && slot.getCount() >= rule.batch()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean canAcceptPending(Container c) {
        for (ItemStack st : this.pending) {
            if (JobKit.hasSpaceFor(c, st.getItem())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasCargo() {
        return !this.pending.isEmpty();
    }

    /** 收尾理货的过滤器：通货 + 表内可拆的建材都算"货"（不揣回家）。 */
    private static boolean isCargo(ItemStack st) {
        if (SalvageRecipes.ruleOf(st) != null) {
            return true;
        }
        return st.is(com.bmf.bmfstown.ModItems.MATERIAL_STONE.get())
                || st.is(com.bmf.bmfstown.ModItems.MATERIAL_WOOD.get())
                || st.is(com.bmf.bmfstown.ModItems.MATERIAL_SAND.get());
    }

    /** 岗位方块判定：只认切石机（工作台归工人/烟火商）。 */
    private static boolean isStonecutter(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(Blocks.STONECUTTER);
    }

    private void navigateTo(BlockPos pos) {
        this.resident.getNavigation().moveTo(
                pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, Config.STROLL_SPEED.get());
    }

    private void navigateToPost() {
        if (this.postPos != null) {
            navigateTo(this.postPos);
        }
    }

    private ServerLevel serverLevel() {
        return this.resident.level() instanceof ServerLevel sl ? sl : null;
    }

    private boolean inWorkHours(ServerLevel level) {
        long t = level.getDayTime() % 24000L;
        int[] window = this.resident.workWindow();
        int start = window[0];
        int end = window[1];
        return start <= end ? (t >= start && t < end) : (t >= start || t < end);
    }

    /** 摸鱼判定游标（与工人同口径，每工作段判一次）。 */
    private long nextGoofRollAt = 0;

    private boolean tryGoofOff(ServerLevel level) {
        long now = level.getGameTime();
        if (now < this.nextGoofRollAt) {
            return false;
        }
        this.nextGoofRollAt = now + Math.max(100, Config.SALVAGE_TICKS_PER_BATCH.get());
        double chance = com.bmf.bmfstown.entity.profile.PersonalityFormulas.goofChance(
                this.resident.axisValue(com.bmf.bmfstown.entity.profile.PersonalityAxis.FOCUS));
        if (chance <= 0) {
            return false;
        }
        if (level.getRandom().nextDouble() < chance) {
            int session = Config.AXIS_GOOF_SESSION_MIN.get()
                    + level.getRandom().nextInt(Math.max(1,
                            Config.AXIS_GOOF_SESSION_MAX.get() - Config.AXIS_GOOF_SESSION_MIN.get() + 1));
            this.resident.startGoofOff(level.getGameTime() + session);
            return true;
        }
        return false;
    }
}