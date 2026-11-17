package com.bmf.bmfstown.entity;

import com.bmf.bmfstown.config.Config;
import com.bmf.bmfstown.entity.profile.ProfessionType;
import com.bmf.bmfstown.town.Town;
import com.bmf.bmfstown.town.TownWandMode;
import com.bmf.bmfstown.town.TownZone;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 酒保营业（v0.16 酒馆第二步，决策 #65 重做）——酒馆是<b>营业场所</b>，
 * 酒保是服务生，不再采集（挤奶收蜜砍掉，将来归农业 NPC）。
 *
 * <h2>一图流</h2>
 * <pre>
 *   SCAN ──有人点单──▶ 取窖瓶 / 桶现接 ──▶ 送到客人主手 ──▶ SCAN
 *        ──有桶酿好了──▶ 接瓶入窖（存量到目标停手）──▶ SCAN
 *        ──有空桶+窖里有料──▶ 取基液+配料 ──▶ 倒基液/投配料 ──▶ SCAN（发酵自动进行）
 *        ──有半成品桶──▶ 对账续投（补差额基液+配料；炼金 reconcileStand 同思想，#82）
 *        ──没活干──▶ 阈值盘点 → 缺什么播报什么 ──▶ 歇着
 * </pre>
 *
 * <h2>规则</h2>
 * <ul>
 *   <li><b>上酒服务</b>：客人落座点单（{@link TavernOrders}）→ 酒窖有瓶装酒
 *       优先取瓶，没有就从区内酒桶现接一杯 → 走到客人身边塞进主手
 *       （客人侧 {@code TavernVisitActivity.ORDER_WAIT} 检测到手就开喝）；</li>
 *   <li><b>酿造循环</b>：读 BAC 数据包配方（{@link KegRecipes}，v1 只做常温
 *       一层酒）→ 基液（水桶/奶桶/蜂蜜瓶）+ 配料全部从酒窖容器真实取出 →
 *       投进空酒桶（{@link KegAccess} NBT 直写）→ 发酵由 BAC 自己每刻推进 →
 *       酿好接瓶入窖到目标量为止；二层的酒倒酒与温度档配方 v1 不自动化；</li>
 *   <li><b>续桶对账</b>（2026-11-18 用户拍板 #82，炼金 reconcileStand 同思想）：
 *       半成品桶（被打断/部分投料）优先续投补完——基液同种补差额、配料是
 *       配方子集就补缺口；对不上任何配方（玩家手动酿的）绝不碰；在途料
 *       （货列）持久化在镇民身上，读档后孤儿回收交还酒窖再由续桶接上；</li>
 *   <li><b>余料归还</b>：水桶/奶桶倒完剩空桶、蜂蜜瓶倒完剩空瓶——当场放回
 *       酒窖（还杯铁律同源：绝不吞容器）；</li>
 *   <li><b>缺货播报</b>（#37 口径：点名缺什么）：奶/蜜/反胃药水低于目标量
 *       或可酿配方的原料有缺口 → 16 格内 actionbar，30 秒节流——现在全部
 *       缺口都只能玩家（或农业）补，所以一律点名；</li>
 *   <li><b>反胃秘传</b>（#64 通道保留）：每镇每游戏日 5% 掷中，成书进酒窖。</li>
 * </ul>
 */
public class TavernkeepGoal extends net.minecraft.world.entity.ai.goal.Goal {

    private static final org.slf4j.Logger LOGGER = com.bmf.bmfstown.BmfTown.LOGGER;

    // ---- 常量 ----
    /** 距离目标/容器多近算"到了"（与 WorkerGoal 同款口径）。 */
    private static final double OPEN_RANGE = 3.0D;
    /** 扫描节流：区域扫描（容器+酒桶）每 100t 一次，防每 tick 全图扫。 */
    private static final int SCAN_PERIOD = 100;
    /** 一轮活之间的喘息（#66 实测：100t=5 秒的喘息让点单接不上，压到 1 秒）。 */
    private static final int REST_TICKS = 20;
    /** 开盖→取放的停顿（对齐工人/烟火 LID_TICKS 口径：盖子飞起来再伸手）。 */
    private static final int LID_TICKS = 10;
    /** 投桶表演节奏：每份基液（一瓶/一桶）间隔（2026-11-17 用户点名：
     *  容器交互要有音效和动画——一 tick 连倒 4 瓶听起来就是"倒了一次"）。 */
    private static final int POUR_STEP_TICKS = 12;
    /** 投桶表演节奏：每件配料间隔。 */
    private static final int INSERT_STEP_TICKS = 8;
    /** 反胃药水的盘点样本（isSameItemSameComponents 口径）。 */
    private static final ItemStack NAUSEA_SAMPLE = net.minecraft.world.item.alchemy.PotionContents
            .createItemStack(Items.POTION, com.bmf.bmfstown.alchemy.ModPotions.NAUSEA);

    // ---- 运行状态 ----
    private final Resident resident;
    private Phase phase = Phase.SCAN;
    private Kind kind = Kind.IDLE;
    /** 当前用的酒窖容器。 */
    private BlockPos cellarPos;
    /** 当前操作的酒桶。 */
    private BlockPos kegPos;
    /** 当前服务的点单客人。 */
    private Entity patron;
    /** 当前酿造配方。 */
    private KegRecipes.FermentRecipe recipe;
    /** 手上的货（取回的瓶/料/接的酒；入窖前持有）——<b>懒取</b>镇民身上的
     *  持久化货列（{@link Resident#getWorkCargo()}，随实体存档）。2026-11-18
     *  用户拍板"货列补持久化"；当初不走背包的原因依然成立：蜂蜜瓶是食物
     *  （进背包会被自己的进食行为吃掉）、中断回滚的归属必须精确。 */
    private List<ItemStack> cargoRef;

    /**
     * 取在途货列（懒获取）。<b>绝不能在构造器里抓引用</b>：Goal 构造发生在
     * Resident 父类（Mob）构造器的 {@code registerGoals()} 期间，此刻
     * Resident 自有字段尚未初始化——构造期抓引用拿到的是 null
     * （2026-11-18 GameTest 实测 NPE 教训，构造期 this 泄漏）。
     */
    private List<ItemStack> cargo() {
        if (this.cargoRef == null) {
            this.cargoRef = this.resident.getWorkCargo();
        }
        return this.cargoRef;
    }
    /** 本轮酿造计划（2026-11-18 续桶对账的统一形态：新批次=进度为零的续桶）。 */
    private BrewPlan brewPlan;
    /** 主手展示中的货（端酒/端料给玩家看；引用跟踪——被手装导演换装即失联）。
     *  2026-11-15 用户拍板：送酒不许"虚空转移"，必须看得见他端着酒走。 */
    private ItemStack carried = ItemStack.EMPTY;
    /** 最近一轮酿造缺的料（播报用；null = 无酿造缺口）。 */
    private List<ItemStack> lastBrewMissing;
    /** 本轮接瓶的剩余额度（目标量 − 已有存量）。 */
    private int drawRemaining;
    /** 扫描节流游标。 */
    private long nextScanAt;
    private int retryCooldown;
    /** 开盖停顿游标（#68：酒窖取放走 containerFx 表演，节奏对齐工人）。 */
    private int lidTimer;
    /** 投桶表演：已倒/投的份数（基液在前、配料在后）。 */
    private int pourIndex;
    /** 投桶表演：下一份的倒计时。 */
    private int pourCooldown;

    private enum Phase { SCAN, FETCH, GO_KEG, POUR, GO_PATRON, STORE }
    private enum Kind { IDLE, SERVE_BOTTLE, SERVE_KEG, BREW, DRAW_STOCK }

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
    public void stop() {
        // 被掐断（出工作窗/睡觉/开饭）不占着货：手上的货（含主手展示品）当场
        // 回窖——与客人"还杯铁律"同源（#65），也杜绝"货卡在停用 Goal 里"的竞态
        ServerLevel level = serverLevel();
        if (level != null && (!cargo().isEmpty() || !this.carried.isEmpty())) {
            returnCargoToCellar(level);
        }
        // 停工理货（#68 用户拍板"下班不揣料"）：背包里剩的瓶瓶罐罐（cargo 溢出
        // 件等）退回最近酒窖容器。打断路径不表演（先顾命）；食物/书不归公。
        // 特例（2026-11-17 用户实测：蜂蜜瓶没回窖）：蜂蜜瓶带 FOOD 组件会被
        // isPersonal 的"食物不归公"拦住，但它对酒馆是工作原料/库存——
        // 酒馆饮品白名单（奶桶/蜂蜜瓶/BAC 酒）整体放行回窖（本类已有多处
        // isDrink 同款引用，口径统一）。
        if (level != null) {
            JobKit.giveBack(level, this.resident, cellarContainers(level),
                    st -> !JobKit.isPersonal(st)
                            || com.bmf.bmfstown.leisure.TavernVisitActivity.isDrink(st));
        }
        this.resident.getNavigation().stop();
        this.patron = null;
        this.recipe = null;
        this.brewPlan = null;
        this.kegPos = null;
        this.cellarPos = null;
        this.kind = Kind.IDLE;
        this.phase = Phase.SCAN;
        this.lidTimer = 0;
        this.pourIndex = 0;
        this.pourCooldown = 0;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        ServerLevel level = serverLevel();
        if (level == null) {
            return;
        }
        // 点单打铃（#67 顾客主动通知）：客人挂单瞬间清掉喘息/扫描节流，
        // SCAN 相位当 tick 受理；手头有活（取料/投桶/送酒）时干完这轮再受理
        // ——货在路上硬掐会吞货，returnCargoToCellar 兜底留给 stop()
        if (TavernOrders.takeBell(this.resident)) {
            this.retryCooldown = 0;
            if (this.phase != Phase.SCAN) {
                return;
            }
        }
        if (this.retryCooldown > 0) {
            this.retryCooldown--;
            return;
        }
        switch (this.phase) {
            case SCAN -> tickScan(level);
            case FETCH -> tickFetch(level);
            case GO_KEG -> tickGoKeg(level);
            case POUR -> tickPour(level);
            case GO_PATRON -> tickGoPatron(level);
            case STORE -> tickStore(level);
        }
    }

    /** FETCH 分发：上瓶装酒取瓶 / 酿造取料 / 接酒取空瓶（#81）。 */
    private void tickFetch(ServerLevel level) {
        switch (this.kind) {
            case SERVE_BOTTLE -> fetchServeBottle(level);
            case BREW -> fetchBrewSupplies(level);
            case DRAW_STOCK -> fetchDrawEmpties(level);
            case SERVE_KEG -> fetchServeKegEmpty(level);
            default -> backToScan();
        }
    }

    // ==========================================================
    //  SCAN：调度一轮
    // ==========================================================

    private void tickScan(ServerLevel level) {
        // 孤儿货回收（货列持久化后）：读档重载 Goal 从头跑，而存档瞬间手上正端着
        // 这轮的料——先交还酒窖（与 stop() 打断路径同款兜底），绝不吞；半成品桶
        // 留在原地，下面的续桶逻辑会把它认出来接上（#82）
        if (!cargo().isEmpty()) {
            returnCargoToCellar(level);
        }
        boolean due = this.resident.tickCount >= this.nextScanAt;
        // 点单不受全量扫描节流（#66 实测：原实现单被 SCAN_PERIOD+REST 双重
        // 压着，客人进店要干等 5~10 秒+）——有单随时处理
        if (!due && !TavernOrders.hasOrderNear(level, zoneAnchors())) {
            return;
        }
        if (due) {
            this.nextScanAt = this.resident.tickCount + SCAN_PERIOD;
            // ---- 酒保通道（决策 #64）：每镇每游戏日 5% 掷中 → 反胃秘传成书进酒窖
            //      （与营业正事并行不悖；全服上限记账在 NauseaSecretLedger）----
            Town secretTown = boundTown();
            if (secretTown != null && com.bmf.bmfstown.alchemy.NauseaSecretLedger.get(level)
                    .tavernRoll(level, secretTown.id(), this.resident.getRandom())) {
                issueNauseaSecret(level);
            }
        }

        // 1) 有人点单：上酒服务最高优先
        if (tryServe(level)) {
            return;
        }
        if (!due) {
            return; // 非扫描轮只为点单服务
        }
        // 2) 有桶酿好了：接瓶入窖
        if (tryDrawStock(level)) {
            return;
        }
        // 3) 有空桶+有料：开酿一轮
        if (tryBrew(level)) {
            return;
        }
        // 4) 没活干：盘点播报（缺口全靠玩家/农业补，一律点名）。
        //    注意保留 this.lastBrewMissing——tryBrew 失败时记录的"缺口最少的
        //    配方缺料"就是在这里播出去的，清空会导致酿造缺口永远不播（实测前自查发现）。
        Stock stock = cellarScan(level);
        notifyShortage(level, stock);
        // 5) 上班报到（#66 实测：原实现开工后就在醒来原地站桩到天黑——
        //    主城里一动不动看着像没上班）：没事干就走回酒馆区站岗
        if (!inTavernZone()) {
            BlockPos post = nearestPost();
            if (post != null && this.resident.getNavigation().isDone()) {
                navigateTo(post);
            }
        }
        this.retryCooldown = REST_TICKS;
    }

    /** 酒保是否已站在某个酒馆区内。 */
    private boolean inTavernZone() {
        BlockPos p = this.resident.blockPosition();
        for (TownZone zone : tavernZones()) {
            if (p.getX() >= zone.min().getX() && p.getX() <= zone.max().getX()
                    && p.getZ() >= zone.min().getZ() && p.getZ() <= zone.max().getZ()) {
                return true;
            }
        }
        return false;
    }

    /** 最近酒馆区的中心点（上班报到的岗位）。 */
    private BlockPos nearestPost() {
        TownZone best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos p = this.resident.blockPosition();
        for (TownZone zone : tavernZones()) {
            BlockPos c = new BlockPos((zone.min().getX() + zone.max().getX()) / 2,
                    zone.min().getY(), (zone.min().getZ() + zone.max().getZ()) / 2);
            double d = p.distSqr(c);
            if (d < bestDist) {
                bestDist = d;
                best = zone;
            }
        }
        return best == null ? null : new BlockPos(
                (best.min().getX() + best.max().getX()) / 2,
                best.min().getY(),
                (best.min().getZ() + best.max().getZ()) / 2);
    }

    // ------------------------------------------------------------
    //  上酒服务
    // ------------------------------------------------------------

    private boolean tryServe(ServerLevel level) {
        List<BlockPos> anchors = zoneAnchors();
        if (anchors.isEmpty()) {
            return false;
        }
        // 先看有没有酒可上（别白领单）：窖里有瓶优先，没瓶看桶能不能接
        BlockPos stock = findDrinkStock(level);
        BlockPos keg = stock == null ? findKeg(level, KegState.DRAWABLE) : null;
        if (stock == null && keg == null) {
            return false;
        }
        // 现场接也要空杯（#81/#83：杯型跟所接饮品走）；没杯不领单，客人超时自取兜底
        if (stock == null && findEmptyCupStock(level, cupForKeg(level, keg)) == null) {
            return false;
        }
        Entity p = TavernOrders.claim(level, anchors);
        if (p == null) {
            return false;
        }
        this.patron = p;
        if (stock != null) {
            this.cellarPos = stock;
            this.kind = Kind.SERVE_BOTTLE;
            this.phase = Phase.FETCH;
            navigateTo(stock);
        } else {
            this.kegPos = keg;
            this.cellarPos = findEmptyCupStock(level, cupForKeg(level, keg)); // 先取空杯再接
            this.kind = Kind.SERVE_KEG;
            this.phase = Phase.FETCH;
            navigateTo(this.cellarPos);
        }
        return true;
    }

    /** 区内含指定空杯（#83：接酒容器跟饮品走）的最近酒窖容器；无则 null。 */
    private BlockPos findEmptyCupStock(ServerLevel level, Item cup) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : cellarContainers(level)) {
            Container c = containerAt(level, pos);
            if (c == null) {
                continue;
            }
            boolean has = false;
            for (int i = 0; i < c.getContainerSize(); i++) {
                if (c.getItem(i).is(cup)) {
                    has = true;
                    break;
                }
            }
            if (!has) {
                continue;
            }
            double d = this.resident.blockPosition().distSqr(pos);
            if (d < bestDist) {
                bestDist = d;
                best = pos.immutable();
            }
        }
        return best;
    }

    /** FETCH：到酒窖取一瓶饮品（上瓶装酒）——先走到容器边上再取，绝不隔空掏。 */
    private void fetchServeBottle(ServerLevel level) {
        if (this.cellarPos == null) {
            backToScan();
            return;
        }
        if (!this.resident.blockPosition().closerThan(this.cellarPos, OPEN_RANGE)) {
            // 取货必须人到位（#66 实测：原版下一 tick 就隔空把酒拿进手=虚空取物）
            if (this.resident.getNavigation().isDone()) {
                navigateTo(this.cellarPos);
            }
            return;
        }
        this.resident.getNavigation().stop();
        // 开盖表演（#68 用户点名：不许靠过去直接隔空拿）——盖子飞起来停一拍再取
        if (this.lidTimer == 0) {
            this.resident.containerFx(level, this.cellarPos, true);
            this.lidTimer = LID_TICKS;
            return;
        }
        if (--this.lidTimer > 0) {
            return;
        }
        this.resident.containerFx(level, this.cellarPos, false);
        Container stock = containerAt(level, this.cellarPos);
        if (stock == null) {
            backToScan();
            return;
        }
        // 顾客喝什么（#67 用户拍板随机化）：窖里有几种就随机抽一种——
        // 绝不固定第一格（旧实现永远取第一个饮品格，蜂蜜囤前排全员喝蜂蜜）
        java.util.List<Integer> drinkSlots = new ArrayList<>();
        for (int i = 0; i < stock.getContainerSize(); i++) {
            if (com.bmf.bmfstown.leisure.TavernVisitActivity.isDrink(stock.getItem(i))) {
                drinkSlots.add(i);
            }
        }
        if (drinkSlots.isEmpty()) {
            backToScan(); // 被抢喝了：客人侧有超时自取兜底
            return;
        }
        int slot = drinkSlots.get(this.resident.getRandom().nextInt(drinkSlots.size()));
        ItemStack taken = stock.getItem(slot).copy();
        taken.setCount(1);
        stock.getItem(slot).shrink(1);
        stock.setChanged();
        carryVisible(taken);
        this.kind = Kind.SERVE_BOTTLE;
        this.phase = Phase.GO_PATRON;
        navigateTo(this.patron.blockPosition());
    }

    /** GO_PATRON：把主手端的酒送到客人主手（看得见的"端酒"交付）。 */
    private void tickGoPatron(ServerLevel level) {
        if (this.patron == null || !this.patron.isAlive()) {
            returnCargoToCellar(level);
            backToScan();
            return;
        }
        if (!this.carried.isEmpty() && !handCarrying()) {
            // 展示品被手装导演换下（送酒途中遭袭战斗，酒被收进背包）：
            // 本次服务作废，货回窖，绝不端着武器送酒
            returnCargoToCellar(level);
            backToScan();
            return;
        }
        BlockPos ppos = this.patron.blockPosition();
        if (!this.resident.blockPosition().closerThan(ppos, OPEN_RANGE)) {
            if (this.resident.getNavigation().isDone()) {
                navigateTo(ppos);
                this.retryCooldown = 20;
            }
            return;
        }
        this.resident.getNavigation().stop();
        if (this.patron instanceof LivingEntity le) {
            ItemStack pHand = le.getMainHandItem();
            if (pHand == this.carried) {
                // 展示品已经在"客人"手上（单人闭环：酒保自己就是客人，
                // ORDER_WAIT 已把主手这杯认走）——交付完成，手不动
                this.carried = ItemStack.EMPTY;
                TavernOrders.close(le);
            } else if (pHand.isEmpty() && handCarrying()
                    && TavernOrders.isClaimed(le)) {
                // 客人还在等单：正常交付，酒从酒保主手换到客人主手
                le.setItemInHand(InteractionHand.MAIN_HAND, this.carried);
                this.carried = ItemStack.EMPTY;
                this.resident.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                TavernOrders.close(le);
                level.playSound(null, ppos, SoundEvents.ITEM_PICKUP,
                        SoundSource.NEUTRAL, 0.6F, 1.0F);
            } else {
                // 客人不等了（等超时改自取/窗口掐断/手被别的占了）：
                // 酒回窖，绝不塞给已经放弃的人（#66 堵滞留杯）
                returnCargoToCellar(level);
            }
        } else {
            returnCargoToCellar(level);
        }
        this.patron = null;
        backToScan();
    }

    // ------------------------------------------------------------
    //  接瓶入窖
    // ------------------------------------------------------------

    private boolean tryDrawStock(ServerLevel level) {
        BlockPos keg = findKeg(level, KegState.DRAWABLE);
        if (keg == null) {
            return false;
        }
        KegAccess.KegSnap snap = KegAccess.read(level, keg);
        if (snap == null || snap.fluid() == null) {
            return false;
        }
        ResourceLocation fluidId = ResourceLocation.tryParse(snap.fluid().fluidId());
        if (fluidId == null) {
            return false;
        }
        Item bottled = BuiltInRegistries.ITEM.get(fluidId);
        Item cup = KegAccess.containerForFluid(snap.fluid().fluidId()); // #83 接酒容器跟饮品走
        Stock stock = cellarScan(level);
        int have = stock.bottled().getOrDefault(bottled, 0);
        int target = Math.max(1, Config.TAVERN_STOCK_TARGET.get());
        if (have >= target) {
            return false; // 瓶装够了：流体留给点单现场接
        }
        int cups = countCup(level, cup);
        if (cups <= 0) {
            return false; // 没空杯接不了（#81/#83）：客人喝完还杯回窖后自然恢复
        }
        this.drawRemaining = Math.min(target - have, cups);
        this.kegPos = keg;
        this.kind = Kind.DRAW_STOCK;
        this.cellarPos = findEmptyCupStock(level, cup); // 空杯从酒窖取
        if (this.cellarPos == null) {
            return false;
        }
        this.phase = Phase.FETCH; // 先取空杯再接（#81：接一杯耗一杯）
        navigateTo(this.cellarPos);
        return true;
    }

    // ------------------------------------------------------------
    //  酿造循环
    // ------------------------------------------------------------

    /**
     * 一轮酿造的执行计划（2026-11-18 续桶改造）：新批次与续桶统一形态——
     * 新批次就是"进度为零的续桶"。GO_KEG 到场用 {@code assumed*} 三项对账，
     * 桶被人动了就本轮作废、下轮重新对账。
     *
     * @param recipe           本轮配方
     * @param extraSources     还要倒的基液份数（续桶=补差额；新批次=足量）
     * @param extraIngredients 还要投的配料（已投部分扣除后的差额）
     * @param assumedFluidId   计划时桶内流体 id（null=无流体）
     * @param assumedDroplets  计划时桶内流体量（-1=无流体）
     * @param assumedLoaded    计划时投料槽内容签名（{@link #loadSignature}）
     */
    public record BrewPlan(KegRecipes.FermentRecipe recipe, int extraSources,
            List<ItemStack> extraIngredients,
            String assumedFluidId, long assumedDroplets, List<String> assumedLoaded) {

        /** 新批次计划：桶应为全空。 */
        public static BrewPlan fresh(KegRecipes.FermentRecipe r) {
            return new BrewPlan(r, sourceCountFor(r), List.copyOf(r.ingredients()),
                    null, -1L, List.of());
        }
    }

    /**
     * 续桶对账结果（纯函数出口，GameTest 直测）：plan 非 null = 可续；
     * plan 为 null 时 missing 非 null = "对得上配方但差额补不起"（缺口清单，
     * 供播报）；missing 也为 null = "对不上任何配方"（玩家手动操作，不碰不播）。
     */
    public record ResumeResult(BrewPlan plan, List<ItemStack> missing) {
    }

    /** 投料槽 0~3 的内容签名（"物品@数量"排序——续桶对账的"已投部分"口径）。 */
    private static List<String> loadSignature(ItemStack[] slots) {
        List<String> sig = new ArrayList<>();
        for (int i = 0; i <= 3 && i < slots.length; i++) {
            if (!slots[i].isEmpty()) {
                sig.add(BuiltInRegistries.ITEM.getKey(slots[i].getItem()).toString()
                        + "@" + slots[i].getCount());
            }
        }
        sig.sort(String::compareTo);
        return sig;
    }

    /** 桶内流体是否某配方的成品（酿好/在酿完成态——归接瓶通道，不归续桶）。 */
    private static boolean isResultFluid(List<KegRecipes.FermentRecipe> recipes,
            String fluidId) {
        for (KegRecipes.FermentRecipe r : recipes) {
            if (r.resultFluidId().equals(fluidId)) {
                return true;
            }
        }
        return false;
    }

    private boolean tryBrew(ServerLevel level) {
        List<KegRecipes.FermentRecipe> recipes = KegRecipes.all(level);
        if (recipes.isEmpty()) {
            return false; // BAC 不在：酿造循环整体停用
        }
        List<KegAt> kegs = scanKegs(level);
        BlockPos cellar = findStock(level);
        if (cellar == null) {
            return false;
        }
        Container cellarC = containerAt(level, cellar);
        Stock stock = cellarScan(level);

        // ① 续桶优先（2026-11-18 用户拍板，炼金 reconcileStand 同思想）：
        //    被打断/有半成品的桶必须接上——料已经进去一半，弃之既浪费又留死桶
        //    （用户实测：灌了一两瓶蜂蜜被打断，这桶从此没人认）。目标量让位：
        //    补完半批最多超产一批，好过桶永远卡死。
        BlockPos keg = null;
        BrewPlan plan = null;
        double bestDist = Double.MAX_VALUE;
        for (KegAt k : kegs) {
            if (k.snap().fluid() == null
                    && KegAccess.nonEmpty(k.snap().slots()).isEmpty()) {
                continue; // 全空桶：走 ② 新批次
            }
            if (k.snap().fluid() != null && isResultFluid(recipes,
                    k.snap().fluid().fluidId())) {
                continue; // 成品流体：归接瓶通道
            }
            BrewPlan p = resumePlanFor(k, recipes, cellarC);
            if (p == null) {
                continue; // 对不上任何配方/缺料：不碰（尊重玩家的手动操作），缺口已记播报
            }
            double d = this.resident.blockPosition().distSqr(k.pos());
            if (d < bestDist) {
                bestDist = d;
                keg = k.pos();
                plan = p;
            }
        }
        // ② 空桶新批次（原目标制逻辑；lastBrewMissing 优先保留 ① 记下的
        //    续桶缺口——半成品的差额比整批缺口更精准可达成）
        if (keg == null) {
            for (KegAt k : kegs) {
                if (k.snap().fluid() == null
                        && KegAccess.nonEmpty(k.snap().slots()).isEmpty()) {
                    double d = this.resident.blockPosition().distSqr(k.pos());
                    if (d < bestDist) {
                        bestDist = d;
                        keg = k.pos();
                    }
                }
            }
            if (keg == null) {
                return false; // 没空桶（都在酿/都酿好了）：上面 DRAW_STOCK 会处理
            }
            int target = Math.max(1, Config.TAVERN_STOCK_TARGET.get());
            KegRecipes.FermentRecipe best = null;
            int bestMissing = Integer.MAX_VALUE;
            for (KegRecipes.FermentRecipe r : recipes) {
                Item bottled = BuiltInRegistries.ITEM.get(
                        ResourceLocation.tryParse(r.resultFluidId()));
                // 在途也入账（2026-11-17 用户拍板）：窖内瓶数 + 在酿/酿好未接的折算瓶数
                int have = stock.bottled().getOrDefault(bottled, 0)
                        + inFlightBottles(kegs, r);
                if (have >= target) {
                    continue; // 这个酒够了（含正在酿的）
                }
                List<ItemStack> missing = missingFor(level, cellar, r);
                if (missing.size() < bestMissing) {
                    bestMissing = missing.size();
                    if (missing.isEmpty()) {
                        best = r;
                    }
                    if (this.lastBrewMissing == null
                            || missing.size() < this.lastBrewMissing.size()) {
                        this.lastBrewMissing = missing; // 记缺口最少的（播报用）
                    }
                }
            }
            if (best == null) {
                return false; // 全都够/全都缺料：缺料走播报
            }
            plan = BrewPlan.fresh(best);
        }
        this.recipe = plan.recipe();
        this.brewPlan = plan;
        this.kegPos = keg;
        this.cellarPos = cellar;
        this.kind = Kind.BREW;
        this.phase = Phase.FETCH;
        navigateTo(cellar);
        return true;
    }

    /**
     * 续桶对账（2026-11-18，炼金 reconcileStand 思想搬到酒桶）：桶内已有
     * 半成品（部分基液/部分配料/仅配料）能否对上某条配方并续投补完——
     * <ul>
     *   <li>基液：无流体=从头倒；同种且不超配方量=补差额；<b>超量不碰</b>
     *       （多半是玩家手倒的，不该被酒保改写）；别种流体换下条配方试；</li>
     *   <li>配料：已投部分必须是配方配料的子集（多投/错投不碰）；</li>
     *   <li>已配齐（流体足量+配料全在）= 正在酿的桶，不属续桶（在途入账管）；</li>
     *   <li>差额部分酒窖补不出 → 不接手，缺口记入播报（比整批缺口更精准）。</li>
     * </ul>
     *
     * @return 续桶计划；null = 这桶对不上或补不起（保持不动）
     */
    private BrewPlan resumePlanFor(KegAt k, List<KegRecipes.FermentRecipe> recipes,
            Container cellar) {
        ResumeResult rr = computeResumePlan(k.snap(), recipes, cellar);
        if (rr.plan() == null && rr.missing() != null
                && (this.lastBrewMissing == null
                        || rr.missing().size() < this.lastBrewMissing.size())) {
            this.lastBrewMissing = rr.missing(); // 半成品差额缺口，播报更精准
        }
        return rr.plan();
    }

    /**
     * 续桶对账核心（<b>纯函数，无副作用</b>——缺口经 {@link ResumeResult#missing}
     * 带回，GameTest 直测）：桶内已有半成品（部分基液/部分配料/仅配料）能否
     * 对上某条配方并续投补完——
     * <ul>
     *   <li>基液：无流体=从头倒；同种且不超配方量=补差额；<b>超量不碰</b>
     *       （多半是玩家手倒的，不该被酒保改写）；别种流体换下条配方试；</li>
     *   <li>配料：已投部分必须是配方配料的子集（多投/错投不碰）；</li>
     *   <li>已配齐（流体足量+配料全在）= 正在酿的桶，不属续桶（在途入账管）；</li>
     *   <li>差额部分酒窖补不出 → 不接手，缺口带回（比整批缺口更精准）。</li>
     * </ul>
     */
    public static ResumeResult computeResumePlan(KegAccess.KegSnap snap,
            List<KegRecipes.FermentRecipe> recipes, Container cellar) {
        KegAccess.FluidSnap f = snap.fluid();
        for (KegRecipes.FermentRecipe r : recipes) {
            String assumedFid;
            long assumedDroplets;
            int extraSources;
            if (f == null) {
                assumedFid = null;
                assumedDroplets = -1L;
                extraSources = sourceCountFor(r);
            } else if (f.fluidId().equals(r.baseFluidId())) {
                long needTotal = (long) r.baseAmountMb() * KegAccess.DROPLETS_PER_MB;
                if (f.droplets() > needTotal) {
                    continue; // 基液超量：不碰（尊重玩家手动操作）
                }
                assumedFid = f.fluidId();
                assumedDroplets = f.droplets();
                long remain = needTotal - f.droplets();
                extraSources = (int) ((remain + r.basePerSourceDroplets() - 1)
                        / r.basePerSourceDroplets());
            } else {
                continue; // 别种流体：换下条配方
            }
            // 配料对账：已投部分必须恰好是配方配料的子集（按物品，逐条扣额度）
            Map<Item, Integer> loadedPool = new HashMap<>();
            for (int i = 0; i <= 3 && i < snap.slots().length; i++) {
                ItemStack l = snap.slots()[i];
                if (!l.isEmpty()) {
                    loadedPool.merge(l.getItem(), l.getCount(), Integer::sum);
                }
            }
            List<ItemStack> extra = new ArrayList<>();
            boolean ok = true;
            for (ItemStack w : r.ingredients()) {
                int use = Math.min(w.getCount(),
                        loadedPool.getOrDefault(w.getItem(), 0));
                int extraC = w.getCount() - use;
                if (extraC > 0) {
                    extra.add(w.copyWithCount(extraC));
                }
                if (use > 0) {
                    loadedPool.merge(w.getItem(), -use, Integer::sum);
                }
            }
            for (int left : loadedPool.values()) {
                if (left > 0) {
                    ok = false; // 投了配方外的东西 / 投多了：不碰
                    break;
                }
            }
            if (!ok) {
                continue;
            }
            if (extraSources == 0 && extra.isEmpty()) {
                continue; // 已配齐在酿：归在途入账，不是续桶
            }
            // 差额部分酒窖补得出吗（补不出不接手——否则取一半卡半路又是死局）
            List<ItemStack> missing = new ArrayList<>();
            int haveBase = KegAccess.countItem(cellar, new ItemStack(r.baseSource()));
            if (extraSources > 0 && haveBase < extraSources) {
                missing.add(new ItemStack(r.baseSource()));
            }
            for (ItemStack e : extra) {
                if (KegAccess.countItem(cellar, e) < e.getCount()) {
                    missing.add(e);
                }
            }
            if (!missing.isEmpty()) {
                return new ResumeResult(null, missing);
            }
            return new ResumeResult(new BrewPlan(r, extraSources, List.copyOf(extra),
                    assumedFid, assumedDroplets, loadSignature(snap.slots())), List.of());
        }
        return new ResumeResult(null, null);
    }

    /** GO_KEG 到场对账：桶内容是否仍与计划一致（被玩家动过 → 本轮作废）。 */
    private boolean planMatches(KegAccess.KegSnap snap) {
        BrewPlan p = this.brewPlan;
        if (p == null) {
            return false;
        }
        KegAccess.FluidSnap f = snap.fluid();
        if (p.assumedDroplets() < 0) {
            if (f != null) {
                return false;
            }
        } else {
            if (f == null || !f.fluidId().equals(p.assumedFluidId())
                    || f.droplets() != p.assumedDroplets()) {
                return false;
            }
        }
        return loadSignature(snap.slots()).equals(p.assumedLoaded());
    }

    /** 这条配方从酒窖还拿不出什么（基液源 + 配料，各 1 个口径）。 */
    private List<ItemStack> missingFor(ServerLevel level, BlockPos cellar,
            KegRecipes.FermentRecipe r) {
        List<ItemStack> missing = new ArrayList<>();
        Container stock = containerAt(level, cellar);
        if (stock == null) {
            missing.add(new ItemStack(r.baseSource()));
            missing.addAll(r.ingredients());
            return missing;
        }
        int need = sourceCountFor(r);
        int have = KegAccess.countItem(stock, new ItemStack(r.baseSource()));
        if (have < need) {
            missing.add(new ItemStack(r.baseSource()));
        }
        for (ItemStack ing : r.ingredients()) {
            if (KegAccess.countItem(stock, ing) < 1) {
                missing.add(ing);
            }
        }
        return missing;
    }

    /** 基液源件数：水/奶 1 桶；蜂蜜 ceil(配方量 / 每瓶量)。 */
    private static int sourceCountFor(KegRecipes.FermentRecipe r) {
        long perSource = r.basePerSourceDroplets();
        long need = (long) r.baseAmountMb() * KegAccess.DROPLETS_PER_MB;
        return (int) Math.max(1L, (need + perSource - 1) / perSource);
    }

    /** FETCH：到酒窖取基液源 + 配料（真实取出）。 */
    private void fetchBrewSupplies(ServerLevel level) {
        if (this.cellarPos == null) {
            backToScan();
            return;
        }
        if (!this.resident.blockPosition().closerThan(this.cellarPos, OPEN_RANGE)) {
            // 同上：取料必须人到位（#66 实测）
            if (this.resident.getNavigation().isDone()) {
                navigateTo(this.cellarPos);
            }
            return;
        }
        this.resident.getNavigation().stop();
        // 开盖表演（同上，取酿造料也要看得见开箱）
        if (this.lidTimer == 0) {
            this.resident.containerFx(level, this.cellarPos, true);
            this.lidTimer = LID_TICKS;
            return;
        }
        if (--this.lidTimer > 0) {
            return;
        }
        this.resident.containerFx(level, this.cellarPos, false);
        Container stock = containerAt(level, this.cellarPos);
        if (stock == null || this.brewPlan == null) {
            backToScan();
            return;
        }
        // 只取差额（续桶=补剩余部分；新批次=足量，两者统一走计划）
        int need = this.brewPlan.extraSources();
        for (int i = 0; i < need; i++) {
            ItemStack taken = KegAccess.takeOne(stock,
                    new ItemStack(this.brewPlan.recipe().baseSource()));
            if (taken.isEmpty()) {
                returnCargoToCellar(level); // 有料被人拿走了：把手上的放回去
                backToScan();
                return;
            }
            cargo().add(taken);
        }
        for (ItemStack ing : this.brewPlan.extraIngredients()) {
            ItemStack taken = KegAccess.takeOne(stock, ing);
            if (taken.isEmpty()) {
                returnCargoToCellar(level);
                backToScan();
                return;
            }
            cargo().add(taken);
        }
        this.phase = Phase.GO_KEG;
        carryVisible(cargo().remove(0)); // 端着第一件料走（其余在货舱）
        navigateTo(this.kegPos);
    }

    /**
     * FETCH：到酒窖取 N 个空玻璃瓶（接瓶入窖用——#81 接一杯耗一瓶）。
     * 人到位+开盖表演同款口径；空瓶被别人拿走则手上货回窖作废。
     */
    private void fetchDrawEmpties(ServerLevel level) {
        if (this.cellarPos == null || this.kegPos == null || this.drawRemaining <= 0) {
            backToScan();
            return;
        }
        if (!this.resident.blockPosition().closerThan(this.cellarPos, OPEN_RANGE)) {
            if (this.resident.getNavigation().isDone()) {
                navigateTo(this.cellarPos);
            }
            return;
        }
        this.resident.getNavigation().stop();
        if (this.lidTimer == 0) {
            this.resident.containerFx(level, this.cellarPos, true);
            this.lidTimer = LID_TICKS;
            return;
        }
        if (--this.lidTimer > 0) {
            return;
        }
        this.resident.containerFx(level, this.cellarPos, false);
        Container stock = containerAt(level, this.cellarPos);
        if (stock == null) {
            backToScan();
            return;
        }
        for (int i = 0; i < this.drawRemaining; i++) {
            ItemStack taken = KegAccess.takeOne(stock,
                    new ItemStack(cupForKeg(level, this.kegPos)));
            if (taken.isEmpty()) {
                returnCargoToCellar(level); // 空杯不够了：手上的放回去
                backToScan();
                return;
            }
            cargo().add(taken);
        }
        this.phase = Phase.GO_KEG;
        carryVisible(cargo().remove(0)); // 端着空杯去接（看得见的"拿杯子"）
        navigateTo(this.kegPos);
    }

    /** FETCH：到酒窖取一个空杯（点单现场接酒用——#81/#83 杯型跟饮品走）。 */
    private void fetchServeKegEmpty(ServerLevel level) {
        if (this.cellarPos == null || this.kegPos == null) {
            backToScan();
            return;
        }
        if (!this.resident.blockPosition().closerThan(this.cellarPos, OPEN_RANGE)) {
            if (this.resident.getNavigation().isDone()) {
                navigateTo(this.cellarPos);
            }
            return;
        }
        this.resident.getNavigation().stop();
        if (this.lidTimer == 0) {
            this.resident.containerFx(level, this.cellarPos, true);
            this.lidTimer = LID_TICKS;
            return;
        }
        if (--this.lidTimer > 0) {
            return;
        }
        this.resident.containerFx(level, this.cellarPos, false);
        Container stock = containerAt(level, this.cellarPos);
        if (stock == null) {
            backToScan();
            return;
        }
        ItemStack taken = KegAccess.takeOne(stock,
                new ItemStack(cupForKeg(level, this.kegPos)));
        if (taken.isEmpty()) {
            backToScan(); // 空杯被拿走：客人超时自取兜底
            return;
        }
        carryVisible(taken); // 端着空杯去接
        this.phase = Phase.GO_KEG;
        navigateTo(this.kegPos);
    }

    // ------------------------------------------------------------
    //  GO_KEG：接酒（DRAW_STOCK / SERVE_KEG）或投料（BREW）
    // ------------------------------------------------------------

    private void tickGoKeg(ServerLevel level) {
        if (this.kegPos == null) {
            backToScan();
            return;
        }
        if (!this.carried.isEmpty() && !handCarrying()) {
            // 展示品被导演换下（投料途中遭袭）：本次作废，货回窖
            returnCargoToCellar(level);
            backToScan();
            return;
        }
        if (!this.resident.blockPosition().closerThan(this.kegPos, OPEN_RANGE)) {
            if (this.resident.getNavigation().isDone()) {
                navigateTo(this.kegPos);
                this.retryCooldown = 20;
            }
            return;
        }
        this.resident.getNavigation().stop();
        reclaimCarried(); // 展示品收回事列头（恢复 [基液..., 配料...] 取用顺序）
        switch (this.kind) {
            case DRAW_STOCK -> {
                // 接到目标量或空杯用完或桶见底为止（#81 每杯耗一杯；#83 杯型跟饮品走）
                Item cup = cupForKeg(level, this.kegPos);
                this.cellarPos = findStock(level);
                int drawn = 0;
                while (KegAccess.drawable(level, this.kegPos)
                        && drawn < Math.max(1, this.drawRemaining)) {
                    ItemStack empty = takeEmptyBottle(cup);
                    if (empty.isEmpty()) {
                        break; // 空杯用完：能接多少接多少
                    }
                    ItemStack bottle = KegAccess.drawOne(level, this.kegPos, empty);
                    if (bottle.isEmpty()) {
                        cargo().add(0, empty); // 没接成：空杯收回货列
                        break;
                    }
                    cargo().add(bottle);
                    drawn++;
                }
                if (cargo().isEmpty() || this.cellarPos == null) {
                    backToScan();
                } else {
                    carryVisible(cargo().remove(0)); // 端一瓶走给玩家看
                    this.phase = Phase.STORE;
                    navigateTo(this.cellarPos);
                }
            }
            case SERVE_KEG -> {
                ItemStack empty = takeEmptyBottle(cupForKeg(level, this.kegPos));
                if (empty.isEmpty()) {
                    returnCargoToCellar(level);
                    backToScan(); // 空杯没了：客人超时自取兜底
                } else {
                    ItemStack bottle = KegAccess.drawOne(level, this.kegPos, empty);
                    if (bottle.isEmpty()) {
                        cargo().add(0, empty); // 桶被抢空：空杯带回窖
                        returnCargoToCellar(level);
                        backToScan();
                    } else {
                        carryVisible(bottle);
                        this.phase = Phase.GO_PATRON;
                        navigateTo(this.patron != null
                                ? this.patron.blockPosition() : this.kegPos);
                    }
                }
            }
            case BREW -> {
                // 到场对账：桶内容必须仍与计划一致（被玩家先动过→本轮作废，
                // 货回窖，下轮 SCAN 重新对账续投——2026-11-18 续桶改造）
                KegAccess.KegSnap snap = KegAccess.read(level, this.kegPos);
                if (snap == null || !planMatches(snap)) {
                    returnCargoToCellar(level); // 桶被人先用了：货回窖
                    backToScan();
                } else {
                    this.phase = Phase.POUR;
                    this.pourIndex = 0;
                    this.pourCooldown = POUR_STEP_TICKS;
                }
            }
            default -> backToScan();
        }
    }

    /**
     * POUR：投桶表演（2026-11-17 用户点名"容器交互要有音效和动画"）——
     * 基液<b>一瓶/一桶一个动作</b>（挥手臂+倾倒音效，旧实现一 tick 连倒
     * 4 瓶听起来就是"倒了一次"），配料<b>一件一个动作</b>（挥手臂+放入音效）。
     * 中途失败（掺了别的流体/槽被占）货回窖本轮作废。
     */
    private void tickPour(ServerLevel level) {
        if (this.brewPlan == null || this.kegPos == null || cargo().isEmpty()) {
            backToScan();
            return;
        }
        if (!this.carried.isEmpty() && !handCarrying()) {
            // 展示品被导演换下（投料途中遭袭）：本次作废，货回窖
            returnCargoToCellar(level);
            backToScan();
            return;
        }
        if (!this.resident.blockPosition().closerThan(this.kegPos, OPEN_RANGE)) {
            if (this.resident.getNavigation().isDone()) {
                navigateTo(this.kegPos);
            }
            return;
        }
        this.resident.getNavigation().stop();
        if (this.pourCooldown > 0) {
            this.pourCooldown--;
            return;
        }
        int sources = this.brewPlan.extraSources();
        if (this.pourIndex < sources) {
            // —— 基液：第 pourIndex 瓶/桶（倒完原位换成余料容器，绝不吞）；
            //    pourBaseFluid 同种流体累加——续桶补差额天然成立——
            if (!KegAccess.pourBaseFluid(level, this.kegPos,
                    this.brewPlan.recipe().baseFluidId(),
                    this.brewPlan.recipe().basePerSourceDroplets())) {
                returnCargoToCellar(level);
                backToScan();
                return;
            }
            ItemStack src = cargo().get(this.pourIndex);
            if (src.is(Items.WATER_BUCKET) || src.is(Items.MILK_BUCKET)) {
                cargo().set(this.pourIndex, new ItemStack(Items.BUCKET));
            } else if (src.is(Items.HONEY_BOTTLE)) {
                cargo().set(this.pourIndex, new ItemStack(Items.GLASS_BOTTLE));
            }
            this.resident.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            this.pourIndex++;
            this.pourCooldown = POUR_STEP_TICKS;
            return;
        }
        int ing = this.pourIndex - sources;
        if (ing < this.brewPlan.extraIngredients().size()) {
            // —— 配料：第 ing 件（真实入槽）——
            if (!KegAccess.insertIngredients(level, this.kegPos,
                    List.of(this.brewPlan.extraIngredients().get(ing)))) {
                returnCargoToCellar(level);
                backToScan();
                return;
            }
            this.resident.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            level.playSound(null, this.kegPos, net.minecraft.sounds.SoundEvents.ITEM_FRAME_ADD_ITEM,
                    net.minecraft.sounds.SoundSource.NEUTRAL, 0.6F, 0.9F);
            this.pourIndex++;
            this.pourCooldown = INSERT_STEP_TICKS;
            return;
        }
        // —— 全部落桶：配料已入桶（计划副本入槽，原物件必须销账防复制回窖），
        //    只保留基液换出的空桶/空瓶带回酒窖。
        //    2026-11-18 实锤修复：旧实现 subList(0, pourIndex) 把空桶/空瓶
        //    一起清掉=真实丢物品——注释写"只带余料回窖"、代码却全清，且
        //    酒桶用例在 GameTest 环境自跳过（BAC 配置不加载），从未抓到 ——
        int keep = Math.min(sources, cargo().size());
        cargo().subList(keep, cargo().size()).clear();
        LOGGER.debug("[BmfTown][酒保] {} 投料开酿 {}（补 {} 份基液+{} 配料）",
                this.resident.getName().getString(), this.recipe.id(),
                sources, this.brewPlan.extraIngredients().size());
        this.phase = Phase.STORE;
        this.cellarPos = findStock(level);
        if (!cargo().isEmpty()) {
            carryVisible(cargo().remove(0)); // 端着余料走
        }
        navigateTo(this.cellarPos != null ? this.cellarPos : this.kegPos);
    }

    // ------------------------------------------------------------
    //  STORE：手上货物入窖（接的瓶 / 余料空桶空瓶）
    // ------------------------------------------------------------

    private void tickStore(ServerLevel level) {
        BlockPos store = this.cellarPos != null ? this.cellarPos : findStock(level);
        if (cargo().isEmpty() && this.carried.isEmpty()) {
            backToScan();
            return;
        }
        if (store == null) {
            // 窖没了：揣着（ addItem 兜底），绝不吞货
            reclaimCarried();
            for (ItemStack st : cargo()) {
                JobKit.stowOrDrop(this.resident, st);
            }
            cargo().clear();
            backToScan();
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
        // 开盖表演（入窖也看得见：盖子飞起来停一拍再放货）
        if (this.lidTimer == 0) {
            this.resident.containerFx(level, store, true);
            this.lidTimer = LID_TICKS;
            return;
        }
        if (--this.lidTimer > 0) {
            return;
        }
        this.resident.containerFx(level, store, false);
        reclaimCarried(); // 端来的展示品收回货列再入窖
        Container c = containerAt(level, store);
        if (c != null) {
            for (int i = cargo().size() - 1; i >= 0; i--) {
                if (KegAccess.insertInto(c, cargo().get(i))) {
                    cargo().remove(i);
                }
            }
        }
        // 插不进的部分：揣背包，再满掉脚边（绝不吞）
        for (ItemStack st : cargo()) {
            JobKit.stowOrDrop(this.resident, st);
        }
        cargo().clear();
        backToScan();
    }

    /** 手上的货就地放回酒窖（被打断/客人变卦的兜底）。 */
    private void returnCargoToCellar(ServerLevel level) {
        reclaimCarried(); // 主手展示品（或被导演收进背包的那份）先归队
        for (ItemStack st : cargo()) {
            boolean stored = false;
            for (BlockPos pos : cellarContainers(level)) {
                Container c = containerAt(level, pos);
                if (c != null && KegAccess.insertInto(c, st)) {
                    stored = true;
                    break;
                }
            }
            if (!stored) {
                JobKit.stowOrDrop(this.resident, st);
            }
        }
        cargo().clear();
    }

    private void backToScan() {
        this.patron = null;
        this.recipe = null;
        this.brewPlan = null;
        this.kegPos = null;
        this.cellarPos = null;
        this.kind = Kind.IDLE;
        this.phase = Phase.SCAN;
        this.retryCooldown = REST_TICKS;
        this.lidTimer = 0;
    }

    // ==========================================================
    //  主手展示（2026-11-15 用户拍板：送酒不许虚空转移）
    // ==========================================================

    /** 把货举到主手端着走（原有主手物品先归队，绝不吞）。 */
    /** 从货列取一个指定空杯（就地消耗——#81 接一杯耗一杯；#83 杯型跟饮品走）；
     *  没有返回 EMPTY。 */
    private ItemStack takeEmptyBottle(Item cup) {
        for (int i = 0; i < cargo().size(); i++) {
            ItemStack st = cargo().get(i);
            if (st.is(cup)) {
                ItemStack one = st.copyWithCount(1);
                st.shrink(1);
                if (st.isEmpty()) {
                    cargo().remove(i);
                }
                return one;
            }
        }
        return ItemStack.EMPTY;
    }

    private void carryVisible(ItemStack st) {
        ItemStack cur = this.resident.getMainHandItem();
        if (!cur.isEmpty()) {
            cargo().add(0, cur);
        }
        this.carried = st;
        this.resident.setItemInHand(InteractionHand.MAIN_HAND, st);
    }

    /** 还端着展示品吗（引用比较：被手装导演换装战斗抢手即 false）。 */
    private boolean handCarrying() {
        return !this.carried.isEmpty()
                && this.resident.getMainHandItem() == this.carried;
    }

    /**
     * 展示品回收归队（货列头部——恢复 [基液..., 配料...] 取用顺序）：
     * 还在手上就收回来；已被导演收进背包（store 是副本）就找回一份；
     * 找不到就认了（理论不发生），绝不把导演的武器挪走。
     */
    private void reclaimCarried() {
        if (this.carried.isEmpty()) {
            return;
        }
        if (handCarrying()) {
            cargo().add(0, this.carried);
            this.resident.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        } else {
            var inv = this.resident.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                if (ItemStack.isSameItemSameComponents(inv.getItem(i), this.carried)) {
                    cargo().add(0, inv.removeItem(i, 1));
                    break;
                }
            }
        }
        this.carried = ItemStack.EMPTY;
    }

    // ==========================================================
    //  发现（懒评估，SCAN 节流）
    // ==========================================================

    /** 酒窖饮品盘点：奶/蜜/反胃药水 + 瓶装酒按物品种类。
     *  空杯存量不在此记总账（#83 精确口径：接哪种酒耗哪种杯，按需 {@link #countCup}）。 */
    private record Stock(int milk, int honey, int nausea, Map<Item, Integer> bottled) {
    }

    private Stock cellarScan(ServerLevel level) {
        int milk = 0;
        int honey = 0;
        int nausea = 0;
        Map<Item, Integer> bottled = new HashMap<>();
        for (BlockPos pos : cellarContainers(level)) {
            Container c = containerAt(level, pos);
            if (c == null) {
                continue;
            }
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack st = c.getItem(i);
                if (st.isEmpty()) {
                    continue;
                }
                if (st.is(Items.MILK_BUCKET)) {
                    milk += st.getCount();
                } else if (st.is(Items.HONEY_BOTTLE)) {
                    honey += st.getCount();
                } else if (com.bmf.bmfstown.leisure.TavernVisitActivity.isDrink(st)) {
                    if (ItemStack.isSameItemSameComponents(st, NAUSEA_SAMPLE)) {
                        nausea += st.getCount();
                    } else {
                        bottled.merge(st.getItem(), st.getCount(), Integer::sum);
                    }
                }
            }
        }
        return new Stock(milk, honey, nausea, bottled);
    }

    /** 桶里酿好可接的饮品对应的空杯（流体→物品→合成余料，#83）；读不出回落玻璃瓶。 */
    private Item cupForKeg(ServerLevel level, BlockPos keg) {
        KegAccess.KegSnap snap = KegAccess.read(level, keg);
        if (snap == null || snap.fluid() == null) {
            return Items.GLASS_BOTTLE;
        }
        return KegAccess.containerForFluid(snap.fluid().fluidId());
    }

    /** 全酒窖里指定空杯的总数（#83：接酒容器跟饮品走，不再只认玻璃瓶）。 */
    private int countCup(ServerLevel level, Item cup) {
        int n = 0;
        for (BlockPos pos : cellarContainers(level)) {
            Container c = containerAt(level, pos);
            if (c == null) {
                continue;
            }
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack st = c.getItem(i);
                if (!st.isEmpty() && st.is(cup)) {
                    n += st.getCount();
                }
            }
        }
        return n;
    }

    private Town boundTown() {
        return this.resident.boundTown();
    }

    /** 本城镇全部酒馆区。 */
    private List<TownZone> tavernZones() {
        Town town = boundTown();
        return town == null ? List.of() : town.zones(TownWandMode.TAVERN);
    }

    /** 点单锚点：各区 min 角（客人点单时记的也是区 min 角）。 */
    private List<BlockPos> zoneAnchors() {
        List<BlockPos> out = new ArrayList<>();
        for (TownZone zone : tavernZones()) {
            out.add(zone.min());
        }
        return out;
    }

    /** 区内全部容器（酒窖候选）。 */
    private List<BlockPos> cellarContainers(ServerLevel level) {
        List<BlockPos> out = new ArrayList<>();
        for (TownZone zone : tavernZones()) {
            for (BlockPos pos : TownZone.iterate(zone)) {
                try {
                    if (level.getBlockEntity(pos) instanceof Container) {
                        out.add(pos.immutable());
                    }
                } catch (RuntimeException e) {
                    // 防御（#65）：外部 mod 的 BE 构造可能炸（BAC keg 在无配置
                    // 环境构造即抛）——跳过该位，别让酒保扫描炸服
                }
            }
        }
        return out;
    }

    /** 最近的、装着至少一瓶饮品的酒窖容器；无则 null。 */
    private BlockPos findDrinkStock(ServerLevel level) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : cellarContainers(level)) {
            Container c = containerAt(level, pos);
            if (c == null) {
                continue;
            }
            boolean has = false;
            for (int i = 0; i < c.getContainerSize(); i++) {
                if (com.bmf.bmfstown.leisure.TavernVisitActivity.isDrink(c.getItem(i))) {
                    has = true;
                    break;
                }
            }
            if (!has) {
                continue;
            }
            double d = this.resident.blockPosition().distSqr(pos);
            if (d < bestDist) {
                bestDist = d;
                best = pos;
            }
        }
        return best;
    }

    /** 最近的区内容器（取料/存酒/存余料用）。 */
    private BlockPos findStock(ServerLevel level) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : cellarContainers(level)) {
            double d = this.resident.blockPosition().distSqr(pos);
            if (d < bestDist) {
                bestDist = d;
                best = pos;
            }
        }
        return best;
    }

    /** BAC 酒桶方块（注册表直查，BAC 不在 = null）。 */
    private static volatile Block kegBlockCache;

    private static Block kegBlock() {
        Block b = kegBlockCache;
        if (b == null) {
            b = BuiltInRegistries.BLOCK.getOptional(
                    ResourceLocation.fromNamespaceAndPath("brewinandchewin", "keg"))
                    .orElse(null);
            kegBlockCache = b;
        }
        return b;
    }

    private enum KegState { DRAWABLE, EMPTY }

    /** 区内一只酒桶的快照（位置 + NBT 直读内容）。 */
    private record KegAt(BlockPos pos, KegAccess.KegSnap snap) {
    }

    /** 区内全部酒桶快照（一次扫描，{@link #findKeg} 与在途折算共用）。 */
    private List<KegAt> scanKegs(ServerLevel level) {
        List<KegAt> list = new ArrayList<>();
        Block keg = kegBlock();
        if (keg == null) {
            return list;
        }
        for (TownZone zone : tavernZones()) {
            for (BlockPos pos : TownZone.iterate(zone)) {
                if (!level.getBlockState(pos).is(keg)) {
                    continue;
                }
                KegAccess.KegSnap snap = KegAccess.read(level, pos);
                if (snap != null) {
                    list.add(new KegAt(pos.immutable(), snap));
                }
            }
        }
        return list;
    }

    /** 区内最近的处于目标状态的酒桶；无则 null。 */
    private BlockPos findKeg(ServerLevel level, KegState wanted) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (KegAt k : scanKegs(level)) {
            boolean ok = switch (wanted) {
                case DRAWABLE -> KegAccess.drawable(level, k.pos());
                case EMPTY -> k.snap().fluid() == null
                        && KegAccess.nonEmpty(k.snap().slots()).isEmpty();
            };
            if (!ok) {
                continue;
            }
            double d = this.resident.blockPosition().distSqr(k.pos());
            if (d < bestDist) {
                bestDist = d;
                best = k.pos();
            }
        }
        return best;
    }

    /**
     * 配方 {@code r} 的<b>在途瓶数</b>（2026-11-17 用户拍板：在酿的桶也要入账）：
     * 在酿桶（基液+配料已投）按整批瓶数记，酿好未接的桶（成品流体）按液量
     * 折算。没有这一笔，发酵期长 → 每轮盘点都"仍缺" → 把所有空桶全灌成
     * 同一款，酿完成倍超产。
     */
    private int inFlightBottles(List<KegAt> kegs, KegRecipes.FermentRecipe r) {
        int bottles = 0;
        for (KegAt k : kegs) {
            KegAccess.KegSnap snap = k.snap();
            if (snap.fluid() == null) {
                continue;
            }
            if (snap.fluid().fluidId().equals(r.resultFluidId())) {
                bottles += (int) (snap.fluid().droplets()
                        / KegAccess.TANKARD_DROPLETS);
            } else if (snap.fluid().fluidId().equals(r.baseFluidId())
                    && ingredientsLoaded(snap.slots(), r.ingredients())) {
                bottles += Math.max(1, (int) (r.basePerSourceDroplets()
                        * sourceCountFor(r) / KegAccess.TANKARD_DROPLETS));
            }
        }
        return bottles;
    }

    /** 桶投料槽的物品是否恰好配齐了配方的配料（发酵期配料留在桶里，BAC 口径）。 */
    private static boolean ingredientsLoaded(ItemStack[] slots, List<ItemStack> want) {
        List<ItemStack> have = new ArrayList<>();
        for (int i = 0; i <= 3 && i < slots.length; i++) { // 0~3 投料槽（KegAccess 口径）
            if (!slots[i].isEmpty()) {
                have.add(slots[i]);
            }
        }
        if (have.size() != want.size()) {
            return false;
        }
        for (ItemStack w : want) {
            boolean found = false;
            for (ItemStack h : have) {
                if (h.is(w.getItem()) && h.getCount() == w.getCount()) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    // ==========================================================
    //  工具方法
    // ==========================================================

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
        return JobKit.containerAt(level, pos); // #68 收编（BAC 安全同口径）
    }

    // ==========================================================
    //  缺货播报 + 秘传通道（决策 #64/#65）
    // ==========================================================

    /**
     * 缺货播报点名（#37 口径）：现在奶/蜜采集砍给了农业（还没实装）、反胃
     * 药水和酒类成品一直只能玩家补——所以所有缺口一律点名。半径/节流走
     * {@link JobKit#shortage}（#68 收编）。
     */
    private void notifyShortage(ServerLevel level, Stock stock) {
        int target = Math.max(1, Config.TAVERN_STOCK_TARGET.get());
        List<String> missing = new ArrayList<>();
        if (stock.milk() < target) {
            missing.add(JobKit.label(new ItemStack(Items.MILK_BUCKET), target - stock.milk()));
        }
        if (stock.honey() < target) {
            missing.add(JobKit.label(new ItemStack(Items.HONEY_BOTTLE), target - stock.honey()));
        }
        if (stock.nausea() < target) {
            missing.add(JobKit.label(NAUSEA_SAMPLE, target - stock.nausea()));
        }
        if (this.lastBrewMissing != null) {
            for (ItemStack st : this.lastBrewMissing) {
                missing.add(JobKit.label(st, 1));
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        JobKit.shortage(level, this.resident, net.minecraft.network.chat.Component
                .translatable("msg.bmfstown.tavern.shortage",
                        this.resident.getName().getString(), String.join("、", missing)));
    }

    /**
     * 酒保通道发货（决策 #64）：一本反胃秘传<b>成书</b>放进酒窖（有空位的
     * 区内容器；全满进背包、再满掉脚边——绝不吞）。放下的瞬间全局解锁：
     * 秘传书是酒保直接给的，不走"读完才解锁"。
     */
    private void issueNauseaSecret(ServerLevel level) {
        Town town = boundTown();
        if (town == null) {
            return;
        }
        var unlockId = com.bmf.bmfstown.alchemy.NauseaSecretLedger.NAUSEA_UNLOCK;
        // 即时成书（手记同款：startWriting(…, 1L) + withProgressAdded(1L)）
        var content = com.bmf.bmfstown.book.BookContent
                .startWriting(town.id(), this.resident.getName().getString(),
                        new com.bmf.bmfstown.book.BookDraft.Unlock(unlockId), 1L)
                .withProgressAdded(1L);
        ItemStack book = new ItemStack(com.bmf.bmfstown.ModItems.FINISHED_BOOK.get());
        book.set(com.bmf.bmfstown.ModDataComponents.BOOK_CONTENT.get(), content);
        boolean stored = false;
        outer:
        for (BlockPos pos : cellarContainers(level)) {
            Container c = containerAt(level, pos);
            if (c == null) {
                continue;
            }
            for (int i = 0; i < c.getContainerSize(); i++) {
                if (c.getItem(i).isEmpty()) {
                    c.setItem(i, book);
                    stored = !c.getItem(i).isEmpty();
                    break outer;
                }
            }
        }
        if (!stored) {
            JobKit.stowOrDrop(this.resident, book);
        }
        // 放下即全局解锁（幂等）：镇级解锁广播 + 全服酿造门禁
        com.bmf.bmfstown.unlock.UnlockState.get(level)
                .unlockIfNew(level, town.id(), unlockId);
        com.bmf.bmfstown.alchemy.AlchemyGate.open(level.getServer(), unlockId);
        LOGGER.info("[BmfTown][酒保] {} 从酒窖深处翻出一本《反胃秘传》，放进了酒窖",
                this.resident.getName().getString());
    }
}
