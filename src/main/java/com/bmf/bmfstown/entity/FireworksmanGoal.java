package com.bmf.bmfstown.entity;

import com.bmf.bmfstown.ModDataComponents;
import com.bmf.bmfstown.config.Config;
import com.bmf.bmfstown.entity.domain.JobState;
import com.bmf.bmfstown.entity.profile.Perks;
import com.bmf.bmfstown.entity.profile.ProfessionType;
import com.bmf.bmfstown.fireworks.FireworksCatalog;
import com.bmf.bmfstown.fireworks.FireworksCatalog.FwOrder;
import com.bmf.bmfstown.fireworks.FireworksCatalog.StarSpec;
import com.bmf.bmfstown.fireworks.FireworksMark;
import com.bmf.bmfstown.town.Town;
import com.bmf.bmfstown.town.TownSaveData;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 烟火工匠的工作 Goal（v0.13，.ai/designs/fireworksmaker.md）：
 * 在**城镇登记的方块**上自动认领岗位，两段真合成烟花——先造星再组装。
 *
 * <h2>与工人的关系：定点加工型模板第二实例（决策 #15/#24 骨架全链复用）</h2>
 * 镇内工作台池自动认领（30 秒心跳互斥）/ 全池虚拟盘点跨箱凑料 / 单件合成 /
 * 锚工作站就近入库 / 打断续作——与 {@link WorkerGoal} 逐段同构。差异：
 * <ul>
 *   <li>**共池认领=先到先得**（决策 #30）：认领表 {@link WorkshopClaims}
 *       与工人共享，谁先心跳谁占用，另一职业跳过该桌；</li>
 *   <li>**两段合成**：缺星先造星（真实特殊配方 assemble）、有星直接组装
 *       （{@link FireworksCatalog}，无星哑炮铁律）；星是合法中间物，
 *       池里的现成星按爆炸组件精确匹配可直接取用；</li>
 *   <li>**理货铁律**（决策 #39，用户明确要求）：每批入库后**卸空背包**——
 *       未消耗材料/盈余星送回最近登记容器，容器全满留在身上、下批再试，
 *       不卡死不丢物。</li>
 * </ul>
 *
 * <h2>perk 六件套（决策 #30/#31/#32/#39/#40，分支 id 见 {@link Perks#FIREWORKSMAN_BRANCHES}）</h2>
 * 开源=每颗成品独立掷多产 1 个 / 节流=每套掷命中整份免料 / 模具复用=造星时
 * 形状物不消耗（assemble 是纯计算不消耗输入，扣料是我们自己做的——跳扣即可）/
 * 破片+装药足量+回响=战斗向，产物盖工匠章 {@link FireworksMark}，爆炸挂点生效。
 */
public class FireworksmanGoal extends Goal {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 与工作站/箱子的交互距离（格）。 */
    private static final double OPEN_RANGE = 3.0D;

    /** 待机重试间隔（tick）。 */
    private static final int IDLE_RETRY_TICKS = 100;

    /** 开/关盖动画停留时长（tick）。 */
    private static final int LID_TICKS = 10;

    /** 一批最多做几套（与工人同构）。 */
    private static final int BATCH_MAX = 3;

    private enum Phase {
        GO_POST, FETCH_GO, FETCH_TAKE, CRAFT, STORE_GO, STORE_PUT, TIDY_GO, TIDY_PUT
    }

    private final Resident resident;

    // ---- 认领上下文（城镇级登记，与工人同构） ----
    private UUID townId;
    private BlockPos tablePos;

    // ---- 运行态 ----
    private Phase phase = Phase.GO_POST;
    /** 到岗后该去取料（true）还是直接开始合成（false）。 */
    private boolean afterPostFetch = true;
    @Nullable
    private FwOrder order;
    private int batchTarget;
    /** 本批还剩几套没组装。 */
    private int rocketRemaining;
    /** 还要现造的星（规格键 → 颗数）。 */
    private final Map<String, Integer> starsToCraft = new LinkedHashMap<>();
    /** 规格键 → 规格（订单全量，供星段与匹配用）。 */
    private final Map<String, StarSpec> starSpecs = new LinkedHashMap<>();
    /** 当前正在造的星（CRAFT 星段）。 */
    @Nullable
    private StarSpec currentStar;
    private BlockPos fetchTarget;
    private BlockPos storeTarget;
    private int craftTicksLeft;
    private int lidTimer;
    private int retryCooldown;
    /** 认领失败后的扫描节流（resident.tickCount）。 */
    private long nextScanAt;
    private boolean running;

    public FireworksmanGoal(Resident resident) {
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

    /** 是否已认领岗位（面板"岗位已认领/待认领"显示用）。 */
    public boolean hasClaim() {
        return this.running && this.tablePos != null;
    }

    // ==========================================================
    //  Goal 生命周期
    // ==========================================================

    @Override
    public boolean canUse() {
        if (this.resident.getProfession() != ProfessionType.FIREWORKSMAN
                || this.resident.isSleeping() || this.resident.isForaging()) {
            return false;
        }
        ServerLevel level = serverLevel();
        if (level == null || !inWorkHours(level)) {
            return false;
        }
        if (!com.bmf.bmfstown.calendar.TownCalendar.get(level).isWorkday(level)) {
            return false; // 周末不上班，进闲暇池
        }
        JobState job = this.resident.getJobState();
        if (!job.workerEnabled() || job.orderItem().isEmpty()) {
            return false;
        }
        if (FireworksCatalog.parse(job.orderItem()) == null) {
            return false; // 非烟火订单键（跨职业防护）
        }
        if (this.resident.boundTown() == null
                || this.resident.boundTown().workshop().isEmpty()) {
            this.nextScanAt = this.resident.tickCount + 60;
            return false;
        }
        if (this.resident.tickCount < this.nextScanAt) {
            return false;
        }
        if (!claimPost(level)) {
            this.nextScanAt = this.resident.tickCount + 60;
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        ServerLevel level = serverLevel();
        if (level == null || this.resident.isSleeping() || this.resident.isForaging()
                || !inWorkHours(level)) {
            return false;
        }
        JobState job = this.resident.getJobState();
        if (!job.workerEnabled() || job.orderItem().isEmpty()
                || this.resident.getProfession() != ProfessionType.FIREWORKSMAN) {
            return false;
        }
        return townAlive(level)
                && (this.tablePos == null || isCraftingTable(level, this.tablePos));
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.running = true;
        this.retryCooldown = 0;
        this.order = FireworksCatalog.parse(this.resident.getJobState().orderItem());
        // 恢复被打断的批次：星/材料还在背包、还剩没做完的件数 → 直接回岗接着做
        boolean resumeBatch = this.order != null && (this.rocketRemaining > 0
                || !this.starsToCraft.isEmpty());
        this.phase = Phase.GO_POST;
        this.afterPostFetch = !resumeBatch;
        navigateToTable();
        LOGGER.debug("[BmfTown][烟火] {} 开工（订单={}，岗位={}，{}）",
                this.resident.getName().getString(),
                this.resident.getJobState().orderItem(),
                this.tablePos == null ? "?" : this.tablePos.toShortString(),
                resumeBatch ? "续作本批" : "新批次");
    }

    @Override
    public void stop() {
        this.running = false;
        this.resident.setWorkerVisual(false);
        ServerLevel level = serverLevel();
        boolean resumable = level != null && inWorkHours(level)
                && this.resident.getJobState().workerEnabled()
                && !this.resident.getJobState().orderItem().isEmpty()
                && townAlive(level);
        if (!resumable) {
            // 下班/停工：理货（身上未消耗材料/盈余星退回最近的登记容器；无容器揣着不丢）
            giveBackEverything(level);
            this.order = null;
            this.batchTarget = 0;
            this.rocketRemaining = 0;
            this.starsToCraft.clear();
            this.starSpecs.clear();
            this.currentStar = null;
        }
        // 被临时打断 → 材料随身保留（背包优先），start() 检测剩余件数续作
        releaseClaim();
        this.fetchTarget = null;
        this.storeTarget = null;
        this.phase = Phase.GO_POST;
        this.afterPostFetch = true;
        this.retryCooldown = IDLE_RETRY_TICKS;
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
        if (this.order == null) {
            this.order = FireworksCatalog.parse(this.resident.getJobState().orderItem());
            if (this.order == null) {
                this.retryCooldown = IDLE_RETRY_TICKS;
                return;
            }
        }
        // 认领心跳（共池：与工人互斥）
        if (this.tablePos != null) {
            WorkshopClaims.put(this.tablePos.asLong(), this.resident.getUUID());
        }
        switch (this.phase) {
            case GO_POST -> tickGoPost(level);
            case FETCH_GO -> tickFetchGo(level);
            case FETCH_TAKE -> tickFetchTake(level);
            case CRAFT -> tickCraft(level);
            case STORE_GO -> tickStoreGo(level);
            case STORE_PUT -> tickStorePut(level);
            case TIDY_GO -> tickTidyGo(level);
            case TIDY_PUT -> tickTidyPut(level);
        }
        this.resident.setWorkerVisual(this.phase != Phase.GO_POST);
    }

    // ==========================================================
    //  阶段
    // ==========================================================

    private void tickGoPost(ServerLevel level) {
        if (this.tablePos == null) {
            // 断台/缺料让台后重认领（节流由 tick() 顶部的 retryCooldown 承担）：
            // 认领不占住就给共池的工人留机会，等料来了再抢回
            if (claimPost(level)) {
                this.afterPostFetch = true;
                navigateToTable();
            }
            this.retryCooldown = IDLE_RETRY_TICKS;
            return;
        }
        if (this.resident.blockPosition().closerThan(this.tablePos, OPEN_RANGE)) {
            this.resident.getNavigation().stop();
            if (this.afterPostFetch) {
                this.phase = Phase.FETCH_GO;
            } else {
                beginCraft();
            }
            return;
        }
        if (this.resident.getNavigation().isDone()) {
            navigateToTable();
            this.retryCooldown = 20;
        }
    }

    /**
     * 规划本批（全池虚拟盘点）+ 选最近的缺料箱。
     * 盘点口径：一套 = 纸1 + 火药(n+现造星数) + 各规格造星材料 − 现成星抵扣；
     * 现成星（背包+池）按爆炸组件精确匹配，规格 s 的缺口才需要造星材料。
     */
    private void tickFetchGo(ServerLevel level) {
        this.order = FireworksCatalog.parse(this.resident.getJobState().orderItem());
        if (this.order == null) {
            LOGGER.warn("[BmfTown][烟火] {} 订单键非法（{}），停工待改单",
                    this.resident.getName().getString(),
                    this.resident.getJobState().orderItem());
            this.retryCooldown = IDLE_RETRY_TICKS;
            this.phase = Phase.GO_POST;
            this.afterPostFetch = true;
            return;
        }

        List<BlockPos> containers = validContainers(level);
        int sets = setsSupportedBy(level, containers);
        if (sets <= 0) {
            // 无活可干：让出工作台（共池先到先得——缺料蹲台会饿死另一职业）
            releaseClaim();
            this.retryCooldown = IDLE_RETRY_TICKS;
            this.phase = Phase.GO_POST;
            this.afterPostFetch = true;
            logShortage(level, containers);
            return;
        }
        this.batchTarget = sets;
        this.rocketRemaining = sets;

        // 星段规划：每规格目标颗数 = 订单出现次数 × 套数；缺口按背包+池现成星抵扣
        rebuildStarPlan(containers);
        // 目标总料清单 → 池里还有缺口料：跑单；都在背包里：直接做
        List<Need> needs = buildNeeds(containers);
        BlockPos target = poolHasUseful(level, containers, needs)
                ? nearestContainerWithNeeded(level, containers, needs) : null;
        if (target == null) {
            this.phase = Phase.GO_POST;
            this.afterPostFetch = false;
            if (inventoryCanCraft()) {
                beginCraft();
            } else {
                this.afterPostFetch = true;
                this.retryCooldown = IDLE_RETRY_TICKS;
                logShortage(level, containers);
            }
            return;
        }
        this.fetchTarget = target;
        navigateTo(this.fetchTarget);
        this.phase = Phase.FETCH_TAKE;
    }

    /** 到箱 → 开盖 → 见啥拿啥（按缺口封顶）→ 池里还有缺口料就跑下一箱。 */
    private void tickFetchTake(ServerLevel level) {
        if (!this.resident.blockPosition().closerThan(this.fetchTarget, OPEN_RANGE)) {
            if (this.resident.getNavigation().isDone()) {
                navigateTo(this.fetchTarget);
                this.retryCooldown = 20;
            }
            return;
        }
        this.resident.getNavigation().stop();
        if (this.lidTimer == 0) {
            this.resident.containerFx(level, this.fetchTarget, true);
            this.lidTimer = LID_TICKS;
            return;
        }
        if (--this.lidTimer > 0) {
            return;
        }
        Container chest = containerAt(level, this.fetchTarget);
        this.resident.containerFx(level, this.fetchTarget, false);
        if (chest == null) {
            this.fetchTarget = null;
            this.phase = Phase.FETCH_GO;
            return;
        }
        List<BlockPos> containers = validContainers(level);
        takeFromChest(level, chest, containers);

        List<Need> needs = buildNeeds(containers);
        this.fetchTarget = null;
        if (!poolHasUseful(level, containers, needs) || inventoryFull()) {
            this.phase = Phase.GO_POST;
            this.afterPostFetch = false;
            LOGGER.debug("[BmfTown][烟火] {} 凑料完成（目标 {} 套），回岗合成",
                    this.resident.getName().getString(), this.batchTarget);
        } else {
            this.phase = Phase.FETCH_GO;
        }
    }

    /**
     * 合成：**逐件**——星段逐颗、组装段逐套，各自单独计时。
     * 星造完 → 组装 → 整批做完去入库。
     */
    private void tickCraft(ServerLevel level) {
        if (--this.craftTicksLeft > 0) {
            this.resident.getNavigation().stop();
            return;
        }
        // ---- 星段：先造星 ----
        if (this.currentStar != null) {
            craftOneStar(level);
            return;
        }
        // ---- 组装段 ----
        if (this.rocketRemaining > 0) {
            craftOneRocket(level);
            return;
        }
        LOGGER.debug("[BmfTown][烟火] {} 整批完成（{} 套），去最近的登记容器入库",
                this.resident.getName().getString(), this.batchTarget);
        this.storeTarget = null;
        this.phase = Phase.STORE_GO;
    }

    /** 造一颗星：扣料（模具复用免形状物）→ 真实 assemble → 星进背包。 */
    private void craftOneStar(ServerLevel level) {
        StarSpec spec = this.currentStar;
        this.currentStar = null;
        if (spec != null) {
            if (!consumeStarMaterials(spec)) {
                // 星料对不上（池被中途抽干）：跳过这颗，靠组装段的从严校验兜底
                LOGGER.info("[BmfTown][烟火] {} 造星缺料（{}），跳过该颗",
                        this.resident.getName().getString(), spec.encode());
            } else {
                ItemStack star = FireworksCatalog.assembleStar(level, spec);
                if (star.isEmpty()) {
                    // 配方层异常（数据包改坏）：退款防御——扣料已发生，星料退回背包
                    for (ItemStack mat : spec.materials()) {
                        this.resident.getInventory().addItem(mat.copy());
                    }
                    LOGGER.warn("[BmfTown][烟火] {} 星合成未命中配方（{}），跳过该颗",
                            this.resident.getName().getString(), spec.encode());
                } else {
                    this.resident.getInventory().addItem(star);
                }
            }
            // 星段排队递减：这一颗已处理（成或跳），划掉一颗——
            // 否则排队表永不收敛，CRAFT 段无限重试同一规格
            String key = spec.encode();
            int left = this.starsToCraft.getOrDefault(key, 0) - 1;
            if (left <= 0) {
                this.starsToCraft.remove(key);
            } else {
                this.starsToCraft.put(key, left);
            }
        }
        // 排下一颗星（无则星段结束，组装段接管）
        var it = this.starsToCraft.entrySet().iterator();
        if (it.hasNext()) {
            var e = it.next();
            this.currentStar = this.starSpecs.get(e.getKey());
        }
        this.craftTicksLeft = craftTicks();
    }

    /**
     * 组装一套：先校验星快照齐全 → 节流掷骰（命中整份免料）否则扣纸+火药+星 →
     * 真实 assemble → 盖工匠章 → 开源掷骰 → XP。
     */
    private void craftOneRocket(ServerLevel level) {
        if (this.order == null) {
            this.rocketRemaining = 0;
            return;
        }
        List<ItemStack> starInputs = collectStars(); // 只读快照（assemble 纯计算）
        if (starInputs.size() < this.order.stars().size()) {
            LOGGER.info("[BmfTown][烟火] {} 组装缺星，本批提前收工",
                    this.resident.getName().getString());
            this.rocketRemaining = 0;
            this.storeTarget = null;
            this.phase = Phase.STORE_GO;
            return;
        }
        // 组装料（纸+火药）必须全付——装饰天赋只省星的染料/修饰/形状物（决策 #41 修订）
        List<ItemStack> mats = new ArrayList<>();
        mats.add(new ItemStack(Items.PAPER));
        for (int i = 0; i < this.order.gunpowder(); i++) {
            mats.add(new ItemStack(Items.GUNPOWDER));
        }
        if (!consume(mats) || !consumeStars()) {
            LOGGER.info("[BmfTown][烟火] {} 组装材料对不上，本批提前收工",
                    this.resident.getName().getString());
            this.rocketRemaining = 0;
            this.storeTarget = null;
            this.phase = Phase.STORE_GO;
            return;
        }
        ItemStack rocket = FireworksCatalog.assembleRocket(level, this.order, starInputs);
        if (rocket.isEmpty()) {
            LOGGER.warn("[BmfTown][烟火] {} 火箭组装未命中配方，本批提前收工",
                    this.resident.getName().getString());
            this.rocketRemaining = 0;
            this.storeTarget = null;
            this.phase = Phase.STORE_GO;
            return;
        }
        stampMark(rocket);
        this.resident.getInventory().addItem(rocket);
        this.resident.addSkillXp(this.resident.getProfession().mainSkill(),
                Config.FIREWORK_XP_PER_ITEM.get());
        this.rocketRemaining--;
        this.craftTicksLeft = craftTicks();
    }

    /** 战斗向 perk 物化：有任一战斗分支 → 盖工匠章（出厂即定型）。 */
    private void stampMark(ItemStack rocket) {
        boolean shrapnel = this.resident.hasPerk(Perks.NODE_FW_ADEPT, "shrapnel");
        boolean payload = this.resident.hasPerk(Perks.NODE_FW_MASTER, "packed");
        boolean echo = this.resident.hasPerk(Perks.NODE_FW_LEGEND, "echo");
        if (shrapnel || payload || echo) {
            rocket.set(ModDataComponents.FIREWORKS_MARK.get(),
                    new FireworksMark(shrapnel, payload, echo));
        }
    }

    // ==========================================================
    //  入库与理货
    // ==========================================================

    /** 选入库目标：离工作站最近的、还有空位的登记容器（锚定岗位，不走位抖动）。 */
    private void tickStoreGo(ServerLevel level) {
        if (this.storeTarget == null) {
            this.storeTarget = nearestContainerWithSpaceForAny(level);
            if (this.storeTarget == null) {
                this.retryCooldown = IDLE_RETRY_TICKS;
                return;
            }
        }
        if (!this.resident.blockPosition().closerThan(this.storeTarget, OPEN_RANGE)) {
            if (this.resident.getNavigation().isDone()) {
                navigateTo(this.storeTarget);
                this.retryCooldown = 20;
            }
            return;
        }
        this.resident.getNavigation().stop();
        this.resident.containerFx(level, this.storeTarget, true);
        this.lidTimer = LID_TICKS;
        this.phase = Phase.STORE_PUT;
    }

    private void tickStorePut(ServerLevel level) {
        if (--this.lidTimer > 0) {
            return;
        }
        Container output = containerAt(level, this.storeTarget);
        this.resident.containerFx(level, this.storeTarget, false);
        if (output == null) {
            this.storeTarget = null;
            this.phase = Phase.STORE_GO;
            return;
        }
        // 烟花成品 + 盈余星（合法中间物）全部入库
        moveAllFromInventory(output, Items.FIREWORK_ROCKET);
        moveAllFromInventory(output, Items.FIREWORK_STAR);
        com.bmf.bmfstown.entity.PracticeObserver.observe(level, this.resident,
                ProfessionType.FIREWORKSMAN);
        this.storeTarget = null;
        // 理货铁律（决策 #39）：入库之后卸空背包——未消耗材料送回容器池
        if (inventoryHasItems()) {
            this.phase = Phase.TIDY_GO;
        } else {
            finishBatch();
        }
    }

    private void tickTidyGo(ServerLevel level) {
        if (this.storeTarget == null) {
            this.storeTarget = nearestContainerWithSpaceForAny(level);
            if (this.storeTarget == null) {
                // 全满：留在身上、下批再试（不卡死不丢物）
                LOGGER.debug("[BmfTown][烟火] {} 理货：容器全满，材料暂存身上",
                        this.resident.getName().getString());
                finishBatch();
                return;
            }
        }
        if (!this.resident.blockPosition().closerThan(this.storeTarget, OPEN_RANGE)) {
            if (this.resident.getNavigation().isDone()) {
                navigateTo(this.storeTarget);
                this.retryCooldown = 20;
            }
            return;
        }
        this.resident.getNavigation().stop();
        this.resident.containerFx(level, this.storeTarget, true);
        this.lidTimer = LID_TICKS;
        this.phase = Phase.TIDY_PUT;
    }

    private void tickTidyPut(ServerLevel level) {
        if (--this.lidTimer > 0) {
            return;
        }
        Container output = containerAt(level, this.storeTarget);
        this.resident.containerFx(level, this.storeTarget, false);
        if (output == null) {
            this.storeTarget = null;
            this.phase = Phase.TIDY_GO;
            return;
        }
        tidyInventoryInto(output);
        this.storeTarget = null;
        if (inventoryHasItems()) {
            // 还有装不下的（容器满）：换下一个箱继续；全满就地收尾
            this.phase = Phase.TIDY_GO;
            if (nearestContainerWithSpaceForAny(level) == null) {
                finishBatch();
            }
        } else {
            finishBatch();
        }
    }

    private void finishBatch() {
        this.batchTarget = 0;
        this.rocketRemaining = 0;
        this.starsToCraft.clear();
        this.starSpecs.clear();
        this.currentStar = null;
        this.phase = Phase.FETCH_GO;
    }

    // ==========================================================
    //  批次规划（盘点/需求/取料）
    // ==========================================================

    /** 规划星段：每规格目标 = 订单出现次数 × 套数，缺口（背包+池现成星抵扣后）现造。 */
    private void rebuildStarPlan(List<BlockPos> containers) {
        this.starsToCraft.clear();
        this.starSpecs.clear();
        if (this.order == null) {
            return;
        }
        // 按规格归并（encode 字符串做键——StarSpec 带 int[] 不能直接当 map 键）
        Map<String, Integer> wanted = new LinkedHashMap<>();
        for (StarSpec star : this.order.stars()) {
            wanted.merge(star.encode(), 1, Integer::sum);
            this.starSpecs.putIfAbsent(star.encode(), star);
        }
        for (var e : wanted.entrySet()) {
            StarSpec spec = this.starSpecs.get(e.getKey());
            int have = countStars(containers, spec);
            int gap = e.getValue() * this.batchTarget - have;
            if (gap > 0) {
                this.starsToCraft.put(e.getKey(), gap);
            }
        }
    }

    /**
     * 一条目标总料需求：样品（决定匹配哪些物品）+ 本批目标总量。
     * 星需求（{@code spec != null}）的 want = 池中现成星数（要取回来的量）。
     */
    private record Need(ItemStack sample, int want, @Nullable StarSpec spec) {
        boolean star() {
            return this.spec != null;
        }
    }

    /** 本批目标总料清单（组装料 + 星取回量 + 造星料；模具复用免形状物）。 */
    private List<Need> buildNeeds(List<BlockPos> containers) {
        List<Need> needs = new ArrayList<>();
        if (this.order == null) {
            return needs;
        }
        var inv = this.resident.getInventory();
        needs.add(new Need(new ItemStack(Items.PAPER), this.batchTarget, null));
        int powder = this.order.gunpowder() * this.batchTarget;
        for (var e : this.starsToCraft.entrySet()) {
            powder += e.getValue();
        }
        needs.add(new Need(new ItemStack(Items.GUNPOWDER), powder, null));
        boolean moldReuse = this.resident.hasPerk(Perks.NODE_FW_LEGEND, "mold_reuse");
        for (var e : this.starSpecs.entrySet()) {
            StarSpec spec = e.getValue();
            // 池里的现成星全部取回（按规格）
            int poolStars = countStars(containers, spec) - countStars(List.of(), spec);
            if (poolStars > 0) {
                needs.add(new Need(new ItemStack(Items.FIREWORK_STAR), poolStars, spec));
            }
            Integer craftN = this.starsToCraft.get(e.getKey());
            if (craftN == null || craftN <= 0) {
                continue;
            }
            for (ItemStack mat : spec.materials()) {
                if (moldReuse && ItemStack.isSameItemSameComponents(mat, spec.shapeItem())) {
                    continue; // 模具复用：形状物免备免耗
                }
                needs.add(new Need(mat, craftN * mat.getCount(), null));
            }
        }
        return needs;
    }

    /** 池里是否还有"本批缺的"料（跑单继续的判据；对齐工人 poolSets>0 语义）。 */
    private boolean poolHasUseful(ServerLevel level, List<BlockPos> containers,
            List<Need> needs) {
        var inv = this.resident.getInventory();
        for (Need need : needs) {
            if (need.star()) {
                for (BlockPos pos : containers) {
                    Container c = containerAt(level, pos);
                    if (c != null && poolStarsIn(c, need) > 0) {
                        return true;
                    }
                }
                continue;
            }
            int gap = need.want() - countItem(inv, need.sample());
            if (gap <= 0) {
                continue;
            }
            for (BlockPos pos : containers) {
                Container c = containerAt(level, pos);
                if (c != null && countItem(c, need.sample()) > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 该箱内属于"need 对应星规格"的现成星数。 */
    private int poolStarsIn(Container chest, Need need) {
        FireworkExplosion want = need.spec().explosion();
        int total = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack stack = chest.getItem(i);
            if (stack.is(Items.FIREWORK_STAR)
                    && want.equals(stack.get(DataComponents.FIREWORK_EXPLOSION))) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * 虚拟盘点：背包+全池现有料/现成星能支持几套（从严——做不出就不开工）。
     * 火药总账 = 组装 n×sets + 现造星 Σ颗数。
     */
    private int setsSupportedBy(ServerLevel level, List<BlockPos> containers) {
        if (this.order == null) {
            return 0;
        }
        Map<String, Integer> perSet = new LinkedHashMap<>();
        Map<String, StarSpec> specs = new LinkedHashMap<>();
        for (StarSpec star : this.order.stars()) {
            perSet.merge(star.encode(), 1, Integer::sum);
            specs.putIfAbsent(star.encode(), star);
        }
        for (int sets = BATCH_MAX; sets >= 1; sets--) {
            if (fitsSets(level, containers, perSet, specs, sets)) {
                return sets;
            }
        }
        return 0;
    }

    private boolean fitsSets(ServerLevel level, List<BlockPos> containers,
            Map<String, Integer> perSet, Map<String, StarSpec> specs, int sets) {
        var inv = this.resident.getInventory();
        boolean moldReuse = this.resident.hasPerk(Perks.NODE_FW_LEGEND, "mold_reuse");
        // 火药：组装 + 现造星（每颗 1；现造颗数按现成星抵扣）
        int gunpowder = this.order.gunpowder() * sets;
        for (var e : perSet.entrySet()) {
            StarSpec spec = specs.get(e.getKey());
            int avail = countStars(containers, spec);
            int craft = Math.max(0, e.getValue() * sets - avail);
            gunpowder += craft;
        }
        if (countItem(inv, new ItemStack(Items.GUNPOWDER))
                + poolCount(level, containers, new ItemStack(Items.GUNPOWDER)) < gunpowder) {
            return false;
        }
        if (countItem(inv, new ItemStack(Items.PAPER))
                + poolCount(level, containers, new ItemStack(Items.PAPER)) < sets) {
            return false;
        }
        for (var e : perSet.entrySet()) {
            StarSpec spec = specs.get(e.getKey());
            int craft = Math.max(0, e.getValue() * sets
                    - countStars(containers, spec));
            if (craft == 0) {
                continue;
            }
            for (ItemStack mat : spec.materials()) {
                if (moldReuse && ItemStack.isSameItemSameComponents(mat, spec.shapeItem())) {
                    continue;
                }
                if (countItem(inv, mat) + poolCount(level, containers, mat)
                        < craft * mat.getCount()) {
                    return false;
                }
            }
        }
        return true;
    }

    // ==========================================================
    //  背包操作
    // ==========================================================

    /** 背包里的料够不够直接开做（纸+火药按订单足量）。 */
    private boolean inventoryCanCraft() {
        if (this.order == null) {
            return false;
        }
        var inv = this.resident.getInventory();
        return countItem(inv, new ItemStack(Items.PAPER)) >= this.batchTarget
                && countItem(inv, new ItemStack(Items.GUNPOWDER))
                >= this.order.gunpowder() * this.batchTarget;
    }

    /**
     * 造星扣料（装饰天赋三档节省，决策 #41 修订）：
     * 惜色（学徒）掷免染料 / 惜辉（大师）掷免钻石荧石 / 模具复用（传奇）恒免形状物；
     * 火药与纸不在星料减免之列——火药必须得有。
     */
    private boolean consumeStarMaterials(StarSpec spec) {
        List<ItemStack> mats = new ArrayList<>(spec.materials());
        if (this.resident.hasPerk(Perks.NODE_FW_LEGEND, "mold_reuse")) {
            mats.removeIf(m -> ItemStack.isSameItemSameComponents(m, spec.shapeItem()));
        }
        if (this.resident.hasPerk(Perks.NODE_FW_ADEPT, "dye_thrift")
                && this.resident.getRandom().nextInt(100)
                        < Config.FIREWORK_DYE_THRIFT_PERCENT.get()) {
            mats.removeIf(m -> m.getItem() instanceof net.minecraft.world.item.DyeItem);
        }
        if (this.resident.hasPerk(Perks.NODE_FW_MASTER, "mod_thrift")
                && this.resident.getRandom().nextInt(100)
                        < Config.FIREWORK_MOD_THRIFT_PERCENT.get()) {
            mats.removeIf(m -> m.is(Items.DIAMOND) || m.is(Items.GLOWSTONE_DUST));
        }
        return consume(mats);
    }

    /** 组装扣星（按订单规格逐颗，组件精确匹配）。 */
    private boolean consumeStars() {
        if (this.order == null) {
            return false;
        }
        for (StarSpec spec : this.order.stars()) {
            var inv = this.resident.getInventory();
            FireworkExplosion want = spec.explosion();
            boolean got = false;
            for (int i = 0; i < inv.getContainerSize() && !got; i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.is(Items.FIREWORK_STAR)
                        && want.equals(stack.get(DataComponents.FIREWORK_EXPLOSION))) {
                    stack.shrink(1);
                    if (stack.isEmpty()) {
                        inv.setItem(i, ItemStack.EMPTY);
                    }
                    got = true;
                }
            }
            if (!got) {
                return false;
            }
        }
        return true;
    }

    private boolean consume(List<ItemStack> mats) {
        var inv = this.resident.getInventory();
        // 先验总量再扣（避免扣一半失败）
        for (ItemStack mat : mats) {
            if (countItem(inv, mat) < mat.getCount()) {
                return false;
            }
        }
        for (ItemStack mat : mats) {
            int remaining = mat.getCount();
            for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
                ItemStack stack = inv.getItem(i);
                if (!ItemStack.isSameItemSameComponents(stack, mat)) {
                    continue;
                }
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                if (stack.isEmpty()) {
                    inv.setItem(i, ItemStack.EMPTY);
                }
                remaining -= take;
            }
        }
        return true;
    }

    /** 读背包按订单规格收星快照（assemble 输入；不修改背包）。 */
    private List<ItemStack> collectStars() {
        List<ItemStack> out = new ArrayList<>();
        if (this.order == null) {
            return out;
        }
        var inv = this.resident.getInventory();
        for (StarSpec spec : this.order.stars()) {
            FireworkExplosion want = spec.explosion();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.is(Items.FIREWORK_STAR)
                        && want.equals(stack.get(DataComponents.FIREWORK_EXPLOSION))) {
                    out.add(stack.copyWithCount(1));
                    break;
                }
            }
        }
        return out;
    }

    private void moveAllFromInventory(Container output, net.minecraft.world.item.Item item) {
        var inv = this.resident.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.is(item)) {
                continue;
            }
            storeItem(output, stack);
            if (stack.isEmpty()) {
                inv.setItem(i, ItemStack.EMPTY);
            }
        }
    }

    /** 理货：把身上所有非空槽尽可能塞进该容器（合并同类；装不下留身上）。 */
    private void tidyInventoryInto(Container output) {
        var inv = this.resident.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            storeItem(output, stack);
            if (stack.isEmpty()) {
                inv.setItem(i, ItemStack.EMPTY);
            }
        }
    }

    /** 下班/停工：全背包退到最近的登记容器（没有容器揣着，不丢不刷）。 */
    private void giveBackEverything(@Nullable ServerLevel level) {
        if (level == null || this.townId == null) {
            return;
        }
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : validContainers(level)) {
            double d = pos.distSqr(this.resident.blockPosition());
            if (d < bestDist) {
                bestDist = d;
                best = pos;
            }
        }
        if (best == null) {
            return;
        }
        Container target = containerAt(level, best);
        if (target != null) {
            tidyInventoryInto(target);
        }
    }

    // ==========================================================
    //  岗位认领（共池：与工人先到先得）
    // ==========================================================

    private boolean claimPost(ServerLevel level) {
        releaseClaim();
        Town town = this.resident.boundTown();
        if (town == null) {
            return false;
        }
        UUID self = this.resident.getUUID();
        BlockPos bestTable = null;
        double bestDist = Double.MAX_VALUE;
        for (Long p : town.workshop().stations) {
            BlockPos pos = BlockPos.of(p);
            if (!isCraftingTable(level, pos)) {
                continue; // 被拆了：等登记表惰性清理，这里直接跳过
            }
            if (WorkshopClaims.isTaken(p, self)) {
                continue;
            }
            double d = pos.distSqr(this.resident.blockPosition());
            if (d < bestDist) {
                bestDist = d;
                bestTable = pos;
            }
        }
        if (bestTable == null) {
            return false;
        }
        WorkshopClaims.put(bestTable.asLong(), self);
        this.tablePos = bestTable;
        this.townId = town.id();
        return true;
    }

    private void releaseClaim() {
        if (this.tablePos != null) {
            WorkshopClaims.release(this.tablePos.asLong(), this.resident.getUUID());
            this.tablePos = null;
        }
    }

    // ==========================================================
    //  城镇登记表访问（与工人同构）
    // ==========================================================

    @Nullable
    private Town.WorkshopRegister register(ServerLevel level) {
        Town town = TownSaveData.get(level).findTown(this.townId.toString());
        return town == null ? null : town.workshop();
    }

    private boolean townAlive(ServerLevel level) {
        Town town = TownSaveData.get(level).findTown(this.townId.toString());
        return town != null && town.dimension()
                .equals(level.dimension().location().toString());
    }

    private List<BlockPos> validContainers(ServerLevel level) {
        List<BlockPos> out = new ArrayList<>();
        Town.WorkshopRegister reg = register(level);
        if (reg == null) {
            return out;
        }
        List<Long> broken = null;
        for (Long p : reg.containers) {
            BlockPos pos = BlockPos.of(p);
            if (containerAt(level, pos) != null) {
                out.add(pos);
            } else {
                if (broken == null) {
                    broken = new ArrayList<>();
                }
                broken.add(p);
            }
        }
        if (broken != null) {
            reg.containers.removeAll(broken);
            TownSaveData.get(level).setDirty();
            LOGGER.debug("[BmfTown][烟火] 清理失效登记容器 x{}", broken.size());
        }
        return out;
    }

    /** 离自己最近的、"还缺这种需求"的登记容器（跨箱跑单的下一站）。 */
    @Nullable
    private BlockPos nearestContainerWithNeeded(ServerLevel level,
            List<BlockPos> containers, List<Need> needs) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        var inv = this.resident.getInventory();
        for (BlockPos pos : containers) {
            Container c = containerAt(level, pos);
            if (c == null) {
                continue;
            }
            boolean useful = false;
            for (Need need : needs) {
                if (need.star()) {
                    if (poolStarsIn(c, need) > 0) {
                        useful = true;
                        break;
                    }
                } else if (need.want() - countItem(inv, need.sample()) > 0
                        && countItem(c, need.sample()) > 0) {
                    useful = true;
                    break;
                }
            }
            if (!useful) {
                continue;
            }
            double d = pos.distSqr(this.resident.blockPosition());
            if (d < bestDist) {
                bestDist = d;
                best = pos;
            }
        }
        return best;
    }

    /** 离工作站最近的、还有空位的登记容器（入库/理货锚定岗位）。 */
    @Nullable
    private BlockPos nearestContainerWithSpaceForAny(ServerLevel level) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : validContainers(level)) {
            Container c = containerAt(level, pos);
            if (c == null || !hasSpaceForAny(c)) {
                continue;
            }
            double d = this.tablePos == null
                    ? pos.distSqr(this.resident.blockPosition())
                    : pos.distSqr(this.tablePos);
            if (d < bestDist) {
                bestDist = d;
                best = pos;
            }
        }
        return best;
    }

    // ==========================================================
    //  工具方法
    // ==========================================================

    private static boolean inWorkHours(ServerLevel level) {
        long t = level.getDayTime() % 24000L;
        int start = Config.CRAFT_WORK_START.get();
        int end = Config.CRAFT_WORK_END.get();
        return start <= end ? (t >= start && t < end) : (t >= start || t < end);
    }

    private int craftTicks() {
        var skill = this.resident.getProfession().mainSkill();
        // 效率公式（设计稿 §6.2）：段位段 × 特殊书段；perk 段占位 1.0；
        // 心情不进乘区（炼金"只看等级"悬案未决，烟火不踩一遍）
        double efficiency = com.bmf.bmfstown.entity.profile.EfficiencyMath.total(
                this.resident.getGrowth().skillXp(skill),
                this.resident.getStatus().mood(), false);
        efficiency *= 1.0 + this.resident.learnedEffectBonus(
                com.bmf.bmfstown.book.SpecialBookEffect.Type.CRAFT_SPEED) / 100.0;
        if (efficiency < 0.1D) {
            efficiency = 0.1D;
        }
        return Math.max(1, (int) Math.round(
                Config.FIREWORK_TICKS_PER_ITEM.get() / efficiency));
    }

    private void beginCraft() {
        if (this.order == null) {
            this.phase = Phase.FETCH_GO;
            return;
        }
        // 回岗：按背包实际（池不参与——料已取回）重算套数（宁可少做不做空账）
        int sets = setsSupportedBy(serverLevel(), List.of());
        if (sets <= 0) {
            this.phase = Phase.FETCH_GO;
            return;
        }
        this.batchTarget = sets;
        this.rocketRemaining = sets;
        rebuildStarPlan(List.of());
        // 星段排队：造星优先
        var it = this.starsToCraft.entrySet().iterator();
        if (it.hasNext()) {
            var e = it.next();
            this.currentStar = this.starSpecs.get(e.getKey());
        }
        this.craftTicksLeft = craftTicks();
        this.phase = Phase.CRAFT;
    }

    private ServerLevel serverLevel() {
        return this.resident.level() instanceof ServerLevel sl ? sl : null;
    }

    private void navigateToTable() {
        if (this.tablePos != null) {
            navigateTo(this.tablePos);
        }
    }

    private void navigateTo(BlockPos pos) {
        this.resident.getNavigation().moveTo(
                pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D,
                Config.STROLL_SPEED.get());
    }

    private static boolean isCraftingTable(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(Blocks.CRAFTING_TABLE);
    }

    private Container containerAt(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof Container container ? container : null;
    }

    /** 逐槽计数：与样品同物品同组件的总量。 */
    private static int countItem(Container container, ItemStack sample) {
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, sample)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int poolCount(ServerLevel level, List<BlockPos> containers,
            ItemStack sample) {
        int total = 0;
        for (BlockPos pos : containers) {
            Container c = level.getBlockEntity(pos) instanceof Container cc ? cc : null;
            if (c != null) {
                total += countItem(c, sample);
            }
        }
        return total;
    }

    /** 按规格数星：containers 空 = 只看背包。 */
    private int countStars(List<BlockPos> containers, StarSpec spec) {
        FireworkExplosion want = spec.explosion();
        int total = 0;
        var inv = this.resident.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(Items.FIREWORK_STAR)
                    && want.equals(stack.get(DataComponents.FIREWORK_EXPLOSION))) {
                total += stack.getCount();
            }
        }
        if (containers.isEmpty()) {
            return total;
        }
        ServerLevel level = serverLevel();
        if (level == null) {
            return total;
        }
        for (BlockPos pos : containers) {
            Container c = containerAt(level, pos);
            if (c == null) {
                continue;
            }
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack stack = c.getItem(i);
                if (stack.is(Items.FIREWORK_STAR)
                        && want.equals(stack.get(DataComponents.FIREWORK_EXPLOSION))) {
                    total += stack.getCount();
                }
            }
        }
        return total;
    }

    private static boolean hasSpaceForAny(Container container) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (container.getItem(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean inventoryFull() {
        var inv = this.resident.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private boolean inventoryHasItems() {
        var inv = this.resident.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (!inv.getItem(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 逐箱取料：材料+现成星见啥拿啥（按缺口封顶，不囤货）。 */
    private void takeFromChest(ServerLevel level, Container chest,
            List<BlockPos> containers) {
        var inv = this.resident.getInventory();
        List<Need> needs = buildNeeds(containers);
        for (Need need : needs) {
            int want = need.star()
                    ? poolStarsIn(chest, need)
                    : need.want() - countItem(inv, need.sample());
            for (int i = 0; i < chest.getContainerSize() && want > 0; i++) {
                ItemStack stack = chest.getItem(i);
                if (stack.isEmpty()) {
                    continue;
                }
                if (need.star()) {
                    if (!need.spec().explosion().equals(
                            stack.get(DataComponents.FIREWORK_EXPLOSION))) {
                        continue;
                    }
                } else if (!ItemStack.isSameItemSameComponents(stack, need.sample())) {
                    continue;
                }
                int take = Math.min(want, stack.getCount());
                ItemStack grab = chest.removeItem(i, take);
                if (grab.isEmpty()) {
                    continue;
                }
                want -= take;
                ItemStack leftover = inv.addItem(grab);
                if (!leftover.isEmpty()) {
                    storeItem(chest, leftover);
                    return; // 背包满了：本箱到此为止
                }
            }
        }
    }

    private void storeItem(Container container, ItemStack stack) {
        for (int i = 0; i < container.getContainerSize() && !stack.isEmpty(); i++) {
            ItemStack slot = container.getItem(i);
            if (slot.isEmpty()) {
                container.setItem(i, stack.copy());
                stack.setCount(0);
            } else if (ItemStack.isSameItemSameComponents(slot, stack)) {
                int space = slot.getMaxStackSize() - slot.getCount();
                if (space > 0) {
                    int move = Math.min(space, stack.getCount());
                    slot.grow(move);
                    stack.shrink(move);
                }
            }
        }
    }

    /** 缺料播报点名（对齐工人/炼金：只说等补料不说缺什么=玩家无法对症补料）。 */
    private void logShortage(ServerLevel level, List<BlockPos> containers) {
        List<Need> needs = buildNeeds(containers);
        var inv = this.resident.getInventory();
        StringBuilder sb = new StringBuilder();
        for (Need need : needs) {
            int gap = need.star() ? need.want() : need.want() - countItem(inv, need.sample());
            if (gap <= 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(need.sample().getHoverName().getString()).append("×").append(gap);
        }
        if (sb.length() > 0) {
            LOGGER.debug("[BmfTown][烟火] {} 没料可做，等补料（缺：{}）",
                    this.resident.getName().getString(), sb);
        }
    }
}
