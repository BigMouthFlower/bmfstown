package com.bmf.bmfstown.entity;

import com.bmf.bmfstown.config.Config;
import com.bmf.bmfstown.entity.domain.JobState;
import com.bmf.bmfstown.entity.profile.ProfessionType;
import com.bmf.bmfstown.entity.profile.SkillType;
import com.bmf.bmfstown.entity.profile.WorkshopCraft;
import com.bmf.bmfstown.entity.profile.WorkshopCrafts;
import com.bmf.bmfstown.town.Town;
import com.bmf.bmfstown.town.TownSaveData;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.item.crafting.Ingredient;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工人的工作 Goal（v0.12 重做）：在**城镇登记的方块**上自动认领岗位合成红石元件。
 *
 * <h2>与 v0.8.1 三件套绑定的区别（决策 #24，城镇级登记）</h2>
 * 旧版每个工人在 NPC 面板里手工指认 合成台/原料箱/产物箱 三个坐标；
 * 新版几何全面上收到城镇：手册在**城镇范围内**登记容器与工作台（不需要任何
 * 子区域——工坊区对工人是纯手续，用户 22:40 拍板推翻区级初版），
 * 工人开工时自动认领一张空闲工作台（一桌一工人，内存占用互斥），
 * 容器统一池不分公司——取料/入库全走"就近"。
 *
 * <h2>跨箱凑料（用户定稿）</h2>
 * 出发前**全池虚拟盘点**（配方各原料在所有登记容器里的总量能凑几套），
 * 凑不出一套才待机；跑单时逐箱"见啥拿啥"（按缺口封顶，不囤货），
 * 凑满目标批数或池子扫空就回岗——有几套做几套。
 *
 * <h2>就近的涌现</h2>
 * 取料 = 离自己最近的有缺口原料的箱；入库 = 离**工作站**最近的还有空位的箱
 * （锚定工作站而非 NPC：工人走位不会让目标箱抖动）。玩家无需指定方向，
 * 把箱子摆在哪，工人就往哪跑——"补料即下单"。
 *
 * <h2>背包优先（v0.8.1 语义保留）</h2>
 * 被打断揣走的原料先算进本批；只有下班/解绑/停工才退料
 * （退到最近的一个登记容器——旧版退原料箱，新版没有专属原料箱）。
 *
 * <h2>健壮性（抄图书馆"拆了自动重找"）</h2>
 * 每次扫描登记容器时顺手校验：方块不再是容器（被拆/被换）→ 自动摘出登记表；
 * 工作台被拆 → 岗位失效，Goal 停止后重新认领别的岗位；城镇没了 → 停工待绑定。
 */
public class WorkerGoal extends Goal {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 与工作站/箱子的交互距离（格）。 */
    private static final double OPEN_RANGE = 3.0D;

    /** 待机重试间隔（tick）。 */
    private static final int IDLE_RETRY_TICKS = 100;

    /** 开/关盖动画停留时长（tick）。 */
    private static final int LID_TICKS = 10;

    /** 一批最多做几套（用户定稿：整批取送、单件合成）。 */
    private static final int BATCH_MAX = 3;

    /** 岗位认领的过期时间（毫秒）：持有者长时间不刷新视为失踪，允许接管。 */
    private static final long CLAIM_STALE_MS = 30_000L;

    private enum Phase {
        GO_POST, FETCH_GO, FETCH_TAKE, CRAFT, STORE_GO, STORE_PUT
    }

    /** 岗位占用记录（服务端内存级；重启清空 → 工人重新认领，无副作用）。 */
    private record Claim(UUID holder, long at) {
    }

    /** 工作台 asLong → 占用记录。 */
    private static final Map<Long, Claim> CLAIMS = new HashMap<>();

    private final Resident resident;

    // ---- 认领上下文（canUse/start 时确定；v0.12 修正：登记城镇级，无区概念） ----
    private UUID townId;
    private BlockPos tablePos;

    // ---- 运行态 ----
    private BlockPos fetchTarget;
    private BlockPos storeTarget;
    private WorkshopCraft craft;
    private int batchTarget;
    /** 本批还剩几件没合成（合成逐件扣减）。 */
    private int craftRemaining;
    private Phase phase = Phase.GO_POST;
    /** 到岗后该去取料（true）还是直接开始合成（false）。 */
    private boolean afterPostFetch = true;
    private int craftTicksLeft;
    private int lidTimer;
    private int retryCooldown;
    /** 认领失败后的扫描节流（resident.tickCount），防止空工坊每 tick 全图扫。 */
    private long nextScanAt;
    private boolean running;

    public WorkerGoal(Resident resident) {
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
        if (this.resident.getProfession() != ProfessionType.WORKER
                || this.resident.isSleeping() || this.resident.isForaging()) {
            return false;
        }
        ServerLevel level = serverLevel();
        if (level == null || !inWorkHours(level)) {
            return false;
        }
        if (!com.bmf.bmfstown.calendar.TownCalendar.get(level).isWorkday(level)) {
            return false; // 周末（周六日）不上班，进闲暇池
        }
        JobState job = this.resident.getJobState();
        if (!job.workerEnabled() || job.orderItem().isEmpty()) {
            return false;
        }
        // 城镇里没有任何登记方块：连扫描都省了（60 tick 节流）
        if (this.resident.boundTown() == null
                || this.resident.boundTown().workshop().isEmpty()) {
            this.nextScanAt = this.resident.tickCount + 60;
            return false;
        }
        // 节流：刚扫过没有可用岗位，歇一会再试
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
                || this.resident.getProfession() != ProfessionType.WORKER) {
            return false;
        }
        // 岗位被拆 / 城镇没了 → 停止（stop 释放认领，下轮 canUse 重新认领）
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
        // 恢复被打断的批次（材料还在背包、还剩没做完的件数）：直接回岗接着合成，
        // 不重新取料——用户 23:05 定稿"被打断，继续恢复工作就行"
        boolean resumeBatch = this.craft != null && this.craftRemaining > 0;
        this.phase = Phase.GO_POST;
        this.afterPostFetch = !resumeBatch;
        navigateToTable();
        LOGGER.debug("[BmfTown][工人] {} 开工（订单={}，岗位={}，{}）",
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
        // 被打断（面板/临时状态）→ 材料随身保留（背包优先）；
        // 下班/停工/城镇没了 → 退到最近的登记容器
        if ((this.phase == Phase.CRAFT || this.phase == Phase.FETCH_TAKE
                || this.phase == Phase.STORE_PUT) && !resumable) {
            giveBackHeldMaterials(level);
            LOGGER.debug("[BmfTown][工人] {} 下班/停工，原料退回最近的登记容器",
                    this.resident.getName().getString());
        } else if (this.phase == Phase.CRAFT || this.phase == Phase.FETCH_TAKE) {
            LOGGER.debug("[BmfTown][工人] {} 被临时打断，材料随身保留",
                    this.resident.getName().getString());
        }
        releaseClaim();
        this.fetchTarget = null;
        this.storeTarget = null;
        if (resumable) {
            // **批次保留**：craft/craftRemaining/batchTarget 不清——
            // start() 检测到剩余件数就直接回岗续作，不再重取原料
            if (this.craftRemaining <= 0 && this.phase != Phase.FETCH_GO) {
                // 批次做完了被打断（入库前后）：只剩正常流转，无批次可续
                this.craft = null;
            }
        } else {
            this.craft = null;
            this.batchTarget = 0;
            this.craftRemaining = 0;
        }
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
        // 认领心跳：持有者持续刷新时间戳，失踪 30 秒后别的工人可接管
        if (this.tablePos != null) {
            CLAIMS.put(this.tablePos.asLong(),
                    new Claim(this.resident.getUUID(), System.currentTimeMillis()));
        }
        switch (this.phase) {
            case GO_POST -> tickGoPost(level);
            case FETCH_GO -> tickFetchGo(level);
            case FETCH_TAKE -> tickFetchTake(level);
            case CRAFT -> tickCraft(level);
            case STORE_GO -> tickStoreGo(level);
            case STORE_PUT -> tickStorePut(level);
        }
        // 可视化标记：进工坊干活才亮，走路去工坊不亮
        this.resident.setWorkerVisual(this.phase != Phase.GO_POST);
    }

    // ==========================================================
    //  阶段
    // ==========================================================

    /** 回岗/出发：站在工作台旁就进入下一阶段（取料或合成）。 */
    private void tickGoPost(ServerLevel level) {
        if (this.tablePos == null) {
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
            this.retryCooldown = 20; // 走不到（被围死等）：20 tick 后再试，防每 tick 重寻路
        }
    }

    /** 规划本批（全池盘点）+ 选最近的缺料箱。 */
    private void tickFetchGo(ServerLevel level) {
        String orderItem = this.resident.getJobState().orderItem();
        WorkshopCraft order = findOrder(level, orderItem);
        if (order == null || !WorkshopCrafts.lineAllowed(this.resident.getArchive(), order.result())) {
            this.retryCooldown = IDLE_RETRY_TICKS;
            this.phase = Phase.GO_POST;
            this.afterPostFetch = true;
            return;
        }
        this.craft = order;

        int invSets = inventorySets(order);
        int poolSets = poolSets(level, order);
        this.batchTarget = Math.min(BATCH_MAX, invSets + poolSets);
        if (this.batchTarget <= invSets) {
            if (invSets <= 0) {
                // 全池空 + 背包空：等补料（补料即下单）
                this.retryCooldown = IDLE_RETRY_TICKS;
                this.phase = Phase.GO_POST;
                this.afterPostFetch = true;
                LOGGER.debug("[BmfTown][工人] {} 没料可做，待机等补料",
                        this.resident.getName().getString());
                return;
            }
            // 池空但背包有存货：直接回岗合成
            this.phase = Phase.GO_POST;
            this.afterPostFetch = false;
            return;
        }

        BlockPos target = nearestContainerWithNeeded(level, order, invSets);
        if (target == null) {
            // 盘点后有料、开跑前被抽干：有存货就地做，没有就待机
            if (invSets > 0) {
                this.phase = Phase.GO_POST;
                this.afterPostFetch = false;
            } else {
                this.retryCooldown = IDLE_RETRY_TICKS;
            }
            return;
        }
        this.fetchTarget = target;
        navigateTo(this.fetchTarget);
        this.phase = Phase.FETCH_TAKE;
    }

    /** 到箱 → 开盖 → 见啥拿啥（按缺口封顶）→ 重算是否凑满。 */
    private void tickFetchTake(ServerLevel level) {
        if (!this.resident.blockPosition().closerThan(this.fetchTarget, OPEN_RANGE)) {
            if (this.resident.getNavigation().isDone()) {
                navigateTo(this.fetchTarget);
                this.retryCooldown = 20; // 防不可达目标每 tick 重寻路
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
            this.phase = Phase.FETCH_GO; // 箱没了：重新规划
            return;
        }
        takeFromChest(chest, this.craft);

        int invSets = inventorySets(this.craft);
        if (invSets >= this.batchTarget || poolSets(level, this.craft) == 0) {
            // 凑满目标批数 / 池子扫空：回岗合成（有几套做几套）
            this.batchTarget = Math.max(1, Math.min(this.batchTarget, invSets));
            this.fetchTarget = null;
            this.phase = Phase.GO_POST;
            this.afterPostFetch = false;
            LOGGER.debug("[BmfTown][工人] {} 凑料完成：{} 套（目标 {}），回岗合成",
                    this.resident.getName().getString(), this.batchTarget, this.batchTarget);
        } else {
            // 还没凑满且池里还有料：跑下一个最近的箱
            this.fetchTarget = null;
            this.phase = Phase.FETCH_GO;
        }
    }

    /** 合成：**逐件**——每件单独计时，到点扣一套料出一个成品；整批做完才去入库。 */
    private void tickCraft(ServerLevel level) {
        if (--this.craftTicksLeft > 0) {
            this.resident.getNavigation().stop();
            return;
        }
        // 一件一结：节流 perk 按件判定，扣不出料就地中止本批（回池重新盘点）
        boolean thrift = this.resident.hasPerk(
                com.bmf.bmfstown.entity.profile.Perks.NODE_W_LEGEND, "thrift")
                && this.resident.getRandom().nextInt(100) < Config.CRAFT_THRIFT_PERCENT.get();
        if (!thrift && !consumeFromInventory(this.craft, 1)) {
            this.retryCooldown = IDLE_RETRY_TICKS;
            this.phase = Phase.FETCH_GO;
            LOGGER.info("[BmfTown][工人] {} 合成中材料对不上，本批提前收工",
                    this.resident.getName().getString());
            return;
        }

        // 产量 = 配方单次产量；开源 perk：本件概率额外 +1 份
        int count = this.craft.resultCount();
        if (this.resident.hasPerk(
                com.bmf.bmfstown.entity.profile.Perks.NODE_W_LEGEND, "opensource")) {
            if (this.resident.getRandom().nextInt(100) < Config.CRAFT_OPEN_SOURCE_PERCENT.get()) {
                count += this.craft.resultCount();
            }
        }
        this.resident.getInventory().addItem(new ItemStack(this.craft.result(), count));
        this.resident.addSkillXp(SkillType.CRAFTING, Config.CRAFT_XP_PER_ITEM.get());
        this.craftRemaining--;

        if (this.craftRemaining > 0) {
            this.craftTicksLeft = craftTicks(); // 下一件接着做
            return;
        }
        LOGGER.debug("[BmfTown][工人] {} 整批合成 {} 件，去最近的登记容器入库",
                this.resident.getName().getString(), this.batchTarget);
        this.storeTarget = null;
        this.phase = Phase.STORE_GO;
    }

    /** 选入库目标：**离工作站最近**的还有空位的登记容器（锚定岗位，不走位抖动）。 */
    private void tickStoreGo(ServerLevel level) {
        if (this.storeTarget == null) {
            this.storeTarget = nearestContainerWithSpace(level, this.craft.result());
            if (this.storeTarget == null) {
                // 全满：抱着成品待机，等玩家清箱
                this.retryCooldown = IDLE_RETRY_TICKS;
                return;
            }
        }
        if (!this.resident.blockPosition().closerThan(this.storeTarget, OPEN_RANGE)) {
            if (this.resident.getNavigation().isDone()) {
                navigateTo(this.storeTarget);
                this.retryCooldown = 20; // 防不可达目标每 tick 重寻路
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
            this.phase = Phase.STORE_GO; // 目标箱没了：换下一个
            return;
        }
        moveAllFromInventory(output, this.craft.result());
        // 学者实践观察（v0.11）：跟随中的学者看到工人合成 → 书上计数 +1
        PracticeObserver.observe(level, this.resident, ProfessionType.WORKER);

        // 没装下的成品留在背包 → 换下一个箱继续入库；全入库 → 规划下一批
        boolean leftover = false;
        var inv = this.resident.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(this.craft.result())) {
                leftover = true;
                break;
            }
        }
        this.storeTarget = null;
        if (leftover) {
            this.phase = Phase.STORE_GO;
        } else {
            this.phase = Phase.FETCH_GO;
        }
    }

    // ==========================================================
    //  岗位认领
    // ==========================================================

    /** 从本镇登记的岗位里认领一张空闲工作台（最近优先）。失败返回 false。 */
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
            Claim claim = CLAIMS.get(p);
            boolean taken = claim != null && !claim.holder().equals(self)
                    && System.currentTimeMillis() - claim.at() < CLAIM_STALE_MS;
            if (taken) {
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
        CLAIMS.put(bestTable.asLong(), new Claim(self, System.currentTimeMillis()));
        this.tablePos = bestTable;
        this.townId = town.id();
        return true;
    }

    private void releaseClaim() {
        if (this.tablePos != null) {
            long key = this.tablePos.asLong();
            Claim claim = CLAIMS.get(key);
            // 只释放自己的认领：接管过别人的陈旧记录时不误删
            if (claim != null && this.resident.getUUID().equals(claim.holder())) {
                CLAIMS.remove(key);
            }
            this.tablePos = null;
        }
    }

    // ==========================================================
    //  城镇登记表访问
    // ==========================================================

    @Nullable
    private Town.WorkshopRegister register(ServerLevel level) {
        Town town = TownSaveData.get(level).findTown(this.townId.toString());
        return town == null ? null : town.workshop();
    }

    /** 城镇是否还活着（存在且同维度）。 */
    private boolean townAlive(ServerLevel level) {
        Town town = TownSaveData.get(level).findTown(this.townId.toString());
        return town != null && town.dimension()
                .equals(level.dimension().location().toString());
    }

    /**
     * 有效的登记容器列表。顺手惰性清理：登记位上不再是容器（被拆/被换）
     * → 摘出登记表并标脏——"拆了自动重找"的容器版。
     */
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
            LOGGER.debug("[BmfTown][工人] 清理失效登记容器 x{}", broken.size());
        }
        return out;
    }

    /** 全池虚拟盘点：配方各原料在所有登记容器里的总量能凑几套。 */
    private int poolSets(ServerLevel level, WorkshopCraft order) {
        List<WorkshopCraft.Req> reqs = order.requirements();
        int[] have = new int[reqs.size()];
        for (BlockPos pos : validContainers(level)) {
            Container c = containerAt(level, pos);
            if (c == null) {
                continue;
            }
            for (int i = 0; i < reqs.size(); i++) {
                have[i] += countMatching(c, reqs.get(i).ingredient());
            }
        }
        int sets = Integer.MAX_VALUE;
        for (int i = 0; i < reqs.size(); i++) {
            sets = Math.min(sets, have[i] / reqs.get(i).count());
        }
        return sets == Integer.MAX_VALUE ? 0 : sets;
    }

    /** 离工人最近的、"还缺这种原料"的登记容器（跨箱跑单的下一站）。 */
    @Nullable
    private BlockPos nearestContainerWithNeeded(ServerLevel level, WorkshopCraft order, int invSets) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : validContainers(level)) {
            Container c = containerAt(level, pos);
            if (c == null) {
                continue;
            }
            boolean useful = false;
            for (WorkshopCraft.Req req : order.requirements()) {
                int need = req.count() * this.batchTarget
                        - countMatching(this.resident.getInventory(), req.ingredient());
                if (need > 0 && countMatching(c, req.ingredient()) > 0) {
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

    /** 离工作站最近的、装得下成品的登记容器（入库锚定工作站）。 */
    @Nullable
    private BlockPos nearestContainerWithSpace(ServerLevel level, Item result) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : validContainers(level)) {
            Container c = containerAt(level, pos);
            if (c == null || !hasSpaceFor(c, result)) {
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

    /** 背包里的原料够几套。 */
    private int inventorySets(WorkshopCraft craft) {
        var inv = this.resident.getInventory();
        int sets = Integer.MAX_VALUE;
        for (WorkshopCraft.Req req : craft.requirements()) {
            sets = Math.min(sets, countMatching(inv, req.ingredient()) / req.count());
        }
        return sets == Integer.MAX_VALUE ? 0 : sets;
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

    /** 订单解析：产物注册名 → 真实配方目录条目。 */
    private WorkshopCraft findOrder(ServerLevel level, String orderItem) {
        for (WorkshopCraft c : WorkshopCrafts.catalog(
                level.getServer().getRecipeManager(), level.registryAccess())) {
            if (WorkshopCrafts.keyOf(c.result()).equals(orderItem)) {
                return c;
            }
        }
        return null;
    }

    private int craftTicks() {
        var skill = this.resident.getProfession().mainSkill();
        double efficiency = com.bmf.bmfstown.entity.profile.EfficiencyMath.total(
                this.resident.getGrowth().skillXp(skill),
                this.resident.getStatus().mood(), false);
        efficiency *= com.bmf.bmfstown.entity.profile.EfficiencyMath.workerSpeedMultiplier(
                this.resident.getGrowth().perkChoice(ProfessionType.WORKER,
                        com.bmf.bmfstown.entity.profile.Perks.NODE_W_ADEPT));
        // 《天工开物》《考工记》（CRAFT_SPEED）：装备已学特殊书的合成速度加成
        efficiency *= 1.0 + this.resident.learnedEffectBonus(
                com.bmf.bmfstown.book.SpecialBookEffect.Type.CRAFT_SPEED) / 100.0;
        if (efficiency < 0.1D) {
            efficiency = 0.1D;
        }
        return Math.max(1, (int) Math.round(
                Config.CRAFT_TICKS_PER_ITEM.get() / efficiency));
    }

    /** 回岗合成：按"背包实际套数"封顶（池子中途被抽干时宁可少做不做空账）。
     *  合成是**逐件**的（用户 23:00 定稿）：单件计时、单件扣料、单件产出，
     *  观感上才是"在干活"而不是憋一整批憋个大招。 */
    private void beginCraft() {
        int sets = Math.min(this.batchTarget, inventorySets(this.craft));
        if (sets <= 0) {
            this.phase = Phase.FETCH_GO;
            return;
        }
        this.batchTarget = sets;
        this.craftRemaining = sets;
        this.craftTicksLeft = craftTicks(); // 单件时长
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

    private int countMatching(Container container, Ingredient ingredient) {
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (ingredient.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private boolean hasSpaceFor(Container container, Item item) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack slot = container.getItem(i);
            if (slot.isEmpty()) {
                return true;
            }
            if (slot.is(item) && slot.getCount() < slot.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 逐箱取料：这箱里属于该配方的原料**见啥拿啥**——
     * 每种原料只拿到"补齐 batchTarget 套缺口"为止，不囤货。
     * 背包装不下（leftover）就地停手，靠背包优先语义恢复。
     */
    private void takeFromChest(Container chest, WorkshopCraft order) {
        var inv = this.resident.getInventory();
        for (WorkshopCraft.Req req : order.requirements()) {
            int want = req.count() * this.batchTarget
                    - countMatching(inv, req.ingredient());
            for (int i = 0; i < chest.getContainerSize() && want > 0; i++) {
                ItemStack stack = chest.getItem(i);
                if (!req.matches(stack)) {
                    continue;
                }
                int take = Math.min(want, stack.getCount());
                chest.removeItem(i, take);
                want -= take;
                ItemStack grab = stack.copy();
                grab.setCount(take);
                ItemStack leftover = this.resident.getInventory().addItem(grab);
                if (!leftover.isEmpty()) {
                    storeItem(chest, leftover);
                    return; // 背包满了：本箱到此为止
                }
            }
        }
    }

    private boolean consumeFromInventory(WorkshopCraft craft, int sets) {
        var inv = this.resident.getInventory();
        for (WorkshopCraft.Req req : craft.requirements()) {
            if (countMatching(inv, req.ingredient()) < req.count() * sets) {
                return false;
            }
        }
        for (WorkshopCraft.Req req : craft.requirements()) {
            int remaining = req.count() * sets;
            for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
                ItemStack stack = inv.getItem(i);
                if (!req.matches(stack)) {
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

    private void moveAllFromInventory(Container output, Item result) {
        var inv = this.resident.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.is(result)) {
                continue;
            }
            storeItem(output, stack);
            if (stack.isEmpty()) {
                inv.setItem(i, ItemStack.EMPTY);
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

    /**
     * 退料：退到**离自己最近**的登记容器（没有专属原料箱了）。
     * 一个登记容器都没有时揣着——下次开工背包优先直接用，不丢不刷。
     */
    private void giveBackHeldMaterials(ServerLevel level) {
        if (this.craft == null || this.batchTarget <= 0) {
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
        if (target == null) {
            return;
        }
        for (WorkshopCraft.Req req : this.craft.requirements()) {
            int remaining = req.count() * this.batchTarget;
            for (int i = 0; i < this.resident.getInventory().getContainerSize() && remaining > 0; i++) {
                ItemStack stack = this.resident.getInventory().getItem(i);
                if (!req.matches(stack)) {
                    continue;
                }
                int give = Math.min(remaining, stack.getCount());
                storeItem(target, stack.split(give));
                remaining -= give;
            }
        }
    }
}
