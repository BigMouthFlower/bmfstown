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
    /** 手上的货（取回的瓶/料/接的酒；入窖前持有）。 */
    private final List<ItemStack> cargo = new ArrayList<>();
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

    private enum Phase { SCAN, FETCH, GO_KEG, GO_PATRON, STORE }
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
        if (level != null && (!this.cargo.isEmpty() || !this.carried.isEmpty())) {
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
        this.kegPos = null;
        this.cellarPos = null;
        this.kind = Kind.IDLE;
        this.phase = Phase.SCAN;
        this.lidTimer = 0;
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
            case GO_PATRON -> tickGoPatron(level);
            case STORE -> tickStore(level);
        }
    }

    /** FETCH 分发：上瓶装酒取瓶 / 酿造取料。 */
    private void tickFetch(ServerLevel level) {
        switch (this.kind) {
            case SERVE_BOTTLE -> fetchServeBottle(level);
            case BREW -> fetchBrewSupplies(level);
            default -> backToScan();
        }
    }

    // ==========================================================
    //  SCAN：调度一轮
    // ==========================================================

    private void tickScan(ServerLevel level) {
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
            this.kind = Kind.SERVE_KEG;
            this.phase = Phase.GO_KEG;
            navigateTo(keg);
        }
        return true;
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
        int have = cellarScan(level).bottled().getOrDefault(bottled, 0);
        int target = Math.max(1, Config.TAVERN_STOCK_TARGET.get());
        if (have >= target) {
            return false; // 瓶装够了：流体留给点单现场接
        }
        this.drawRemaining = target - have;
        this.kegPos = keg;
        this.kind = Kind.DRAW_STOCK;
        this.phase = Phase.GO_KEG;
        navigateTo(keg);
        return true;
    }

    // ------------------------------------------------------------
    //  酿造循环
    // ------------------------------------------------------------

    private boolean tryBrew(ServerLevel level) {
        List<KegRecipes.FermentRecipe> recipes = KegRecipes.all(level);
        if (recipes.isEmpty()) {
            return false; // BAC 不在：酿造循环整体停用
        }
        BlockPos keg = findKeg(level, KegState.EMPTY);
        if (keg == null) {
            this.lastBrewMissing = null;
            return false; // 没空桶（都在酿/都酿好了）：上面 DRAW_STOCK 会处理
        }
        this.lastBrewMissing = null;
        int target = Math.max(1, Config.TAVERN_STOCK_TARGET.get());
        Stock stock = cellarScan(level);
        BlockPos cellar = findStock(level);
        if (cellar == null) {
            return false;
        }
        KegRecipes.FermentRecipe best = null;
        int bestMissing = Integer.MAX_VALUE;
        for (KegRecipes.FermentRecipe r : recipes) {
            Item bottled = BuiltInRegistries.ITEM.get(
                    ResourceLocation.tryParse(r.resultFluidId()));
            int have = stock.bottled().getOrDefault(bottled, 0);
            if (have >= target) {
                continue; // 这个酒够了
            }
            List<ItemStack> missing = missingFor(level, cellar, r);
            if (missing.size() < bestMissing) {
                bestMissing = missing.size();
                if (missing.isEmpty()) {
                    best = r;
                }
                this.lastBrewMissing = missing; // 记缺口最少的（播报用）
            }
        }
        if (best == null) {
            return false; // 全都够/全都缺料：缺料走播报
        }
        this.recipe = best;
        this.kegPos = keg;
        this.cellarPos = cellar;
        this.kind = Kind.BREW;
        this.phase = Phase.FETCH;
        navigateTo(cellar);
        return true;
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
        if (stock == null || this.recipe == null) {
            backToScan();
            return;
        }
        int need = sourceCountFor(this.recipe);
        for (int i = 0; i < need; i++) {
            ItemStack taken = KegAccess.takeOne(stock, new ItemStack(this.recipe.baseSource()));
            if (taken.isEmpty()) {
                returnCargoToCellar(level); // 有料被人拿走了：把手上的放回去
                backToScan();
                return;
            }
            this.cargo.add(taken);
        }
        for (ItemStack ing : this.recipe.ingredients()) {
            ItemStack taken = KegAccess.takeOne(stock, ing);
            if (taken.isEmpty()) {
                returnCargoToCellar(level);
                backToScan();
                return;
            }
            this.cargo.add(taken);
        }
        this.phase = Phase.GO_KEG;
        carryVisible(this.cargo.remove(0)); // 端着第一件料走（其余在货舱）
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
                // 接到目标量或桶见底为止
                this.cellarPos = findStock(level);
                int drawn = 0;
                while (KegAccess.drawable(level, this.kegPos)
                        && drawn < Math.max(1, this.drawRemaining)) {
                    ItemStack bottle = KegAccess.drawOne(level, this.kegPos);
                    if (bottle.isEmpty()) {
                        break;
                    }
                    this.cargo.add(bottle);
                    drawn++;
                }
                if (this.cargo.isEmpty() || this.cellarPos == null) {
                    backToScan();
                } else {
                    carryVisible(this.cargo.remove(0)); // 端一瓶走给玩家看
                    this.phase = Phase.STORE;
                    navigateTo(this.cellarPos);
                }
            }
            case SERVE_KEG -> {
                ItemStack bottle = KegAccess.drawOne(level, this.kegPos);
                if (bottle.isEmpty()) {
                    backToScan(); // 桶被抢空了：客人超时自取兜底
                } else {
                    carryVisible(bottle);
                    this.phase = Phase.GO_PATRON;
                    navigateTo(this.patron != null
                            ? this.patron.blockPosition() : this.kegPos);
                }
            }
            case BREW -> {
                if (pourAndLoad(level)) {
                    this.phase = Phase.STORE;
                    this.cellarPos = findStock(level); // 余料（空桶/空瓶）回窖
                    if (!this.cargo.isEmpty()) {
                        carryVisible(this.cargo.remove(0)); // 端着余料走
                    }
                    navigateTo(this.cellarPos != null ? this.cellarPos : this.kegPos);
                } else {
                    returnCargoToCellar(level);
                    backToScan();
                }
            }
            default -> backToScan();
        }
    }

    /** 倒基液 + 投配料。失败（桶被占/掺了别的）返回 false，货回窖。 */
    private boolean pourAndLoad(ServerLevel level) {
        if (this.recipe == null || this.cargo.isEmpty()) {
            return false;
        }
        KegAccess.KegSnap snap = KegAccess.read(level, this.kegPos);
        if (snap == null || snap.fluid() != null
                || KegAccess.nonEmpty(snap.slots()).size() > 0) {
            return false; // 桶不是空的（被人先用了）
        }
        long per = this.recipe.basePerSourceDroplets();
        int sources = sourceCountFor(this.recipe);
        for (int i = 0; i < sources; i++) {
            if (!KegAccess.pourBaseFluid(level, this.kegPos,
                    this.recipe.baseFluidId(), per)) {
                return false;
            }
        }
        List<ItemStack> ings = new ArrayList<>();
        for (int i = sources; i < this.cargo.size(); i++) {
            ings.add(this.cargo.get(i));
        }
        if (!KegAccess.insertIngredients(level, this.kegPos, ings)) {
            return false;
        }
        // 源物品换成余料容器：水/奶桶→空桶，蜂蜜瓶→空瓶（还杯铁律：绝不吞）
        for (int i = 0; i < sources && i < this.cargo.size(); i++) {
            ItemStack src = this.cargo.get(i);
            if (src.is(Items.WATER_BUCKET) || src.is(Items.MILK_BUCKET)) {
                this.cargo.set(i, new ItemStack(Items.BUCKET));
            } else if (src.is(Items.HONEY_BOTTLE)) {
                this.cargo.set(i, new ItemStack(Items.GLASS_BOTTLE));
            }
        }
        this.cargo.subList(sources, this.cargo.size()).clear(); // 配料已入桶
        LOGGER.debug("[BmfTown][酒保] {} 投料开酿 {}（{} 份基液+{} 配料）",
                this.resident.getName().getString(), this.recipe.id(),
                sources, this.recipe.ingredients().size());
        return true;
    }

    // ------------------------------------------------------------
    //  STORE：手上货物入窖（接的瓶 / 余料空桶空瓶）
    // ------------------------------------------------------------

    private void tickStore(ServerLevel level) {
        BlockPos store = this.cellarPos != null ? this.cellarPos : findStock(level);
        if (this.cargo.isEmpty() && this.carried.isEmpty()) {
            backToScan();
            return;
        }
        if (store == null) {
            // 窖没了：揣着（ addItem 兜底），绝不吞货
            reclaimCarried();
            for (ItemStack st : this.cargo) {
                JobKit.stowOrDrop(this.resident, st);
            }
            this.cargo.clear();
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
            for (int i = this.cargo.size() - 1; i >= 0; i--) {
                if (KegAccess.insertInto(c, this.cargo.get(i))) {
                    this.cargo.remove(i);
                }
            }
        }
        // 插不进的部分：揣背包，再满掉脚边（绝不吞）
        for (ItemStack st : this.cargo) {
            JobKit.stowOrDrop(this.resident, st);
        }
        this.cargo.clear();
        backToScan();
    }

    /** 手上的货就地放回酒窖（被打断/客人变卦的兜底）。 */
    private void returnCargoToCellar(ServerLevel level) {
        reclaimCarried(); // 主手展示品（或被导演收进背包的那份）先归队
        for (ItemStack st : this.cargo) {
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
        this.cargo.clear();
    }

    private void backToScan() {
        this.patron = null;
        this.recipe = null;
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
    private void carryVisible(ItemStack st) {
        ItemStack cur = this.resident.getMainHandItem();
        if (!cur.isEmpty()) {
            this.cargo.add(0, cur);
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
            this.cargo.add(0, this.carried);
            this.resident.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        } else {
            var inv = this.resident.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                if (ItemStack.isSameItemSameComponents(inv.getItem(i), this.carried)) {
                    this.cargo.add(0, inv.removeItem(i, 1));
                    break;
                }
            }
        }
        this.carried = ItemStack.EMPTY;
    }

    // ==========================================================
    //  发现（懒评估，SCAN 节流）
    // ==========================================================

    /** 酒窖饮品盘点：奶/蜜/反胃药水 + 瓶装酒按物品种类。 */
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

    /** 区内最近的处于目标状态的酒桶；无则 null。 */
    private BlockPos findKeg(ServerLevel level, KegState wanted) {
        Block keg = kegBlock();
        if (keg == null) {
            return null;
        }
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (TownZone zone : tavernZones()) {
            for (BlockPos pos : TownZone.iterate(zone)) {
                if (!level.getBlockState(pos).is(keg)) {
                    continue;
                }
                KegAccess.KegSnap snap = KegAccess.read(level, pos);
                if (snap == null) {
                    continue;
                }
                boolean ok = switch (wanted) {
                    case DRAWABLE -> KegAccess.drawable(level, pos);
                    case EMPTY -> snap.fluid() == null
                            && KegAccess.nonEmpty(snap.slots()).isEmpty();
                };
                if (!ok) {
                    continue;
                }
                double d = this.resident.blockPosition().distSqr(pos);
                if (d < bestDist) {
                    bestDist = d;
                    best = pos.immutable();
                }
            }
        }
        return best;
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
