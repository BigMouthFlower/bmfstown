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
 *        ──有桶酿好了──▶ 排空入窖（整杯全接，发酵中桶不动）──▶ SCAN
 *        ──出料槽积了成品──▶ 收货入窖（肉干/泡菜等物品产物，#85）──▶ SCAN
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
 *   <li><b>酿造循环</b>（#85 二阶段扩展，覆盖 BAC 全型配方）：一层酒（水/奶/蜜
 *       基液）、二层酒（<b>酒倒酒</b>——窖里的瓶装酒按杯倒进新桶，强根艾尔链）、
 *       直酿（无基液配料直投，肉干/泡菜）；产物是流体走接瓶入窖，是物品走
 *       出料槽收货；温度档配方先读桶温（BAC NBT {@code Temperature}）判档，
 *       不满足不投料（投了也不开酿）；</li>
 *   <li><b>酒吧菜单</b>（决策 #86）：排产/在途入账/缺料播报只认菜单内产物；
 *       二层酒要的原料酒经菜单闭包豁免（想要强根艾尔 → 啤酒按需补料，不占
 *       独立目标量）；菜单未配置 = 全维护（老档兼容）；</li>
 *   <li><b>续桶对账</b>（2026-11-18 用户拍板 #82，炼金 reconcileStand 同思想）：
 *       半成品桶（被打断/部分投料）优先续投补完——基液同种补差额、配料是
 *       配方子集就补缺口；对不上任何配方（玩家手动酿的）绝不碰；续桶不做
 *       菜单门/温度门（料已半进桶，弃之死局，BAC 自己等温度）；在途料
 *       （货列）持久化在镇民身上，读档后孤儿回收交还酒窖再由续桶接上；</li>
 *   <li><b>余料归还</b>：倒完的基液源按<b>合成余料</b>原位换回（水桶/奶桶→空桶、
 *       蜂蜜瓶→玻璃瓶、酒瓶→酒杯）当场放回酒窖（还杯铁律同源：绝不吞容器）；</li>
 *   <li><b>缺货播报</b>（#37 口径：点名缺什么）：奶/蜜/反胃药水低于目标量
 *       或可酿配方的原料有缺口 → 16 格内 actionbar，30 秒节流——缺口全部
 *       只能玩家（或农业）补，所以一律点名；菜单外/温度不满足的配方<b>不记
 *       缺口不播报</b>（#86 痛点：通用材料被不想要的酒霸占还天天喊缺）；</li>
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
    /** BAC 常温档（getTemperature 字节码： chilly~warm 阈值之间=档3）。 */
    private static final int NORMAL_TEMP = 3;
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
    /** 上次排产停手的原因摘要（诊断日志只在变化时输出——#88 实测三轮排障驱动）。 */
    private java.util.Map<String, String> lastPickReasons;
    /** 诊断日志去重（key→上次内容；内容变化才输出——链路全程可见且不刷屏）。 */
    private final java.util.Map<String, String> diagLog = new HashMap<>();

    /** 链路诊断：同一 key 的消息内容变化才落 INFO——排障看日志即可，不用猜。 */
    private void diag(String key, String msg) {
        if (!msg.equals(diagLog.get(key))) {
            diagLog.put(key, msg);
            LOGGER.info("[BmfTown][酒保] {} {}: {}", this.resident
                    .getName().getString(), key, msg);
        }
    }
    /** 本轮接瓶杯数（桶内整杯数与空杯数取小——排空优先）。 */
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
    /** 水基配方的取水计划（决策 #87；null=非水基或走水桶老路径）。 */
    private WaterPlan waterPlan;
    /** 水源站目标（GO_WATER 相位用；复用工坊登记池，炼金 #34 同口径）。 */
    private BlockPos waterTarget;
    /** 当前水源站的落脚点（站旁可站立格；换站即清）。 */
    private BlockPos waterSpot;

    private enum Phase { SCAN, FETCH, GO_WATER, GO_KEG, POUR, GO_PATRON, STORE }
    private enum Kind { IDLE, SERVE_BOTTLE, SERVE_KEG, BREW, DRAW_STOCK, TAKE_FOOD }

    public TavernkeepGoal(Resident resident) {
        this.resident = resident;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    // ==========================================================
    //  Goal 生命周期
    // ==========================================================

    @Override
    public boolean canUse() {
        if (this.resident.jobSuspended()) {
            return false; // 城外回城期间挂起（2026-11-19 #91b）
        }
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
        if (this.resident.jobSuspended()) {
            return false; // 城外回城期间挂起（2026-11-19 #91b）
        }
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
            case GO_WATER -> tickGoWater(level);
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
        // 2) 有桶成品酒：排空入窖（发酵中桶不动，见 tryDrawStock）
        if (tryDrawStock(level)) {
            return;
        }
        // 2.5) 有桶出料槽积了成品（肉干/泡菜等物品产物，#85 阶段二）：收货入窖。
        //      必须排在酿造之前——滞留产物的桶绝不投新料（BAC 完成分支
        //      insertItem(5,...) 塞不进会丢产物）
        if (tryTakeFood(level)) {
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
        //    主城里一动不动看着像没上班）：没事干就走回酒馆区站岗。
        //    NavOps 统一口径（#90）：岗位到不了就放弃站回原地，拉黑期内不撞墙。
        if (!inTavernZone()) {
            BlockPos post = nearestPost();
            if (post != null) {
                NavOps.approach(this.resident, post, 2.0D,
                        Config.STROLL_SPEED.get());
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

    /** 最近酒馆区的可站岗位（几何中心换算可站立格——中心落在酒桶/墙里就就近外扩找站处）。 */
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
        if (best == null) {
            return null;
        }
        BlockPos center = new BlockPos(
                (best.min().getX() + best.max().getX()) / 2,
                best.min().getY(),
                (best.min().getZ() + best.max().getZ()) / 2);
        ServerLevel level = serverLevel();
        if (level != null) {
            BlockPos stand = NavOps.findStandable(level, center, 4,
                    best.min().getY() - 1, best.max().getY() + 1);
            if (stand != null) {
                return stand;
            }
        }
        return center;
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
            if (NavOps.blocked(this.resident, pos)) {
                continue; // 寻路黑名单：放弃过的容器 10 秒内不选
            }
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
        switch (NavOps.approach(this.resident, this.cellarPos,
                OPEN_RANGE, Config.STROLL_SPEED.get())) {
            case STUCK -> {
                backToScan(); // 窖到不了：放弃本单，容器拉黑 10 秒
                return;
            }
            case MOVING -> {
                return;
            }
            case ARRIVED -> {
            }
        }
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
        Container stock = cellarView(level);
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
        switch (NavOps.approach(this.resident, ppos,
                OPEN_RANGE, Config.STROLL_SPEED.get())) {
            case STUCK -> {
                // 客人卡进到不了的地方：本次服务作废，货回窖
                returnCargoToCellar(level);
                backToScan();
                return;
            }
            case MOVING -> {
                return;
            }
            case ARRIVED -> {
            }
        }
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
    //  接瓶入窖（排空优先）
    // ------------------------------------------------------------

    /**
     * 桶里的流体是不是某条配方的<b>发酵中/投料中途</b>半成品：流体是该配方
     * 基液、且投料槽里的东西全是该配方配料——排空优先的闸门（静态纯函数，
     * GameTest 直测）。二层酒发酵期间桶里装的是基液酒（强根艾尔桶里是啤酒
     * 流体），不拦的话接瓶通道会把刚倒进去的基液接回窖、把配料晾在桶里，
     * 倒出/接回死循环。配料槽全空的同流体桶（成品酒/刚倒完基液还没投料）
     * 不拦——前者就该排空，后者被接回窖也只是多跑一趟，续桶下轮自愈
     * （在途入账不会因此超产）。
     */
    public static boolean midBrewKeg(KegAccess.KegSnap snap,
            List<KegRecipes.FermentRecipe> recipes) {
        KegAccess.FluidSnap f = snap.fluid();
        if (f == null) {
            return false;
        }
        boolean anyLoaded = false;
        for (int i = 0; i <= 3 && i < snap.slots().length; i++) {
            if (!snap.slots()[i].isEmpty()) {
                anyLoaded = true;
                break;
            }
        }
        if (!anyLoaded) {
            return false; // 配料槽全空：不是发酵中
        }
        for (KegRecipes.FermentRecipe r : recipes) {
            if (!r.baseIsDrink() || r.baseFluidId() == null
                    || !r.baseFluidId().equals(f.fluidId())) {
                continue;
            }
            boolean allMatch = true;
            for (int i = 0; i <= 3 && i < snap.slots().length; i++) {
                ItemStack l = snap.slots()[i];
                if (l.isEmpty()) {
                    continue;
                }
                boolean hit = false;
                for (KegRecipes.KegIng w : r.ingredients()) {
                    if (w.matches(l)) {
                        hit = true;
                        break;
                    }
                }
                if (!hit) {
                    allMatch = false; // 投了配方外的东西：不认为是发酵中
                    break;
                }
            }
            if (allMatch) {
                return true;
            }
        }
        return false;
    }

    /**
     * 接瓶入窖（2026-11-18 <b>排空优先</b>改版，用户拍板"桶里有多少就取多少"）：
     * 桶里酿好的成品酒不再按"目标量 − 存量"掐着接——那正是坑 20 残液死锁的
     * 根源（窖里够量就不接了，桶底剩 1 杯残液把桶堵死；上酒本来优先走窖瓶，
     * "流体留在桶里现场接"并不成立）。现在只要桶里有整杯成品就全接走
     * （受空杯数限制，能接多少接多少），桶排空立刻能开新批次。窖存超目标
     * 最多多出一批的瓶数，无害：酿造停手判定把窖瓶+在途一起入账
     * （{@link #productOnHand}），排空只是把量从"在途"挪到"窖瓶"，总数不变，
     * 不会引发多酿。不足一整杯（&lt;250mB）的残液任何杯都接不出来，物理上限。
     * 发酵中/投料中途的桶见 {@link #midBrewKeg}，绝不动。
     */
    private boolean tryDrawStock(ServerLevel level) {
        List<KegRecipes.FermentRecipe> recipes = KegRecipes.all(level);
        KegAt pick = null;
        double bestDist = Double.MAX_VALUE;
        for (KegAt k : scanKegs(level)) {
            KegAccess.FluidSnap f = k.snap().fluid();
            if (f == null || f.droplets() < KegAccess.TANKARD_DROPLETS) {
                continue; // 没流体/不足一整杯：接不出来
            }
            ResourceLocation fid = ResourceLocation.tryParse(f.fluidId());
            if (fid == null || BuiltInRegistries.ITEM.getOptional(fid).isEmpty()) {
                continue; // 流体没有对应瓶装物品（水等）：不属于接瓶通道
            }
            if (midBrewKeg(k.snap(), recipes)) {
                continue; // 发酵中/投料中途：归酿造通道
            }
            if (NavOps.blocked(this.resident, k.pos())) {
                continue; // 拉黑中的桶不接单（2026-11-19 实测：弃桶 3 秒后又被
                          // 选回，落脚点雪崩重演=桶顶转圈的推手）
            }
            double d = this.resident.blockPosition().distSqr(k.pos());
            if (d < bestDist) {
                bestDist = d;
                pick = k;
            }
        }
        if (pick == null) {
            return false;
        }
        KegAccess.FluidSnap f = pick.snap().fluid();
        Item bottled = BuiltInRegistries.ITEM
                .get(ResourceLocation.tryParse(f.fluidId()));
        Item cup = KegAccess.containerForFluid(f.fluidId()); // #83 接酒容器跟饮品走
        int kegServings = (int) (f.droplets() / KegAccess.TANKARD_DROPLETS);
        int cups = countCup(level, cup);
        if (cups <= 0) {
            diag("接瓶", BuiltInRegistries.ITEM.getKey(bottled).getPath()
                    + " 待排空但全区没空杯（客人喝完还杯后自然恢复）");
            return false; // 没空杯接不了（#81/#83）：客人喝完还杯回窖后自然恢复
        }
        this.drawRemaining = Math.min(kegServings, cups);
        this.kegPos = pick.pos();
        this.kind = Kind.DRAW_STOCK;
        this.cellarPos = findEmptyCupStock(level, cup); // 空杯从酒窖取
        if (this.cellarPos == null) {
            diag("接瓶", BuiltInRegistries.ITEM.getKey(bottled).getPath()
                    + " 待排空但找不到能收空杯的容器");
            return false;
        }
        diag("接瓶", "排空 " + BuiltInRegistries.ITEM.getKey(bottled).getPath()
                + "：桶内 " + kegServings + " 杯全接"
                + (kegServings > cups ? "（空杯只够 " + cups + "，余下轮续接）" : "")
                + "（桶 " + pick.pos().toShortString() + "）");
        this.phase = Phase.FETCH; // 先取空杯再接（#81：接一杯耗一杯）
        navigateTo(this.cellarPos);
        return true;
    }

    // ------------------------------------------------------------
    //  收货（#85 阶段二：物品产物——肉干/泡菜/熏鱼等）
    // ------------------------------------------------------------

    /**
     * 区内有没有桶的出料槽（5 号）积了<b>已收录配方</b>的物品产物——只认配方
     * 产物（防把玩家手放在出料槽的东西收走）；BAC 完成分支把产物塞 5 号槽，
     * 流体产物不落槽（走接瓶通道）。有的话就去取一件入窖。
     */
    private boolean tryTakeFood(ServerLevel level) {
        java.util.Set<Item> outputs = itemOutputs(KegRecipes.all(level));
        if (outputs.isEmpty()) {
            return false;
        }
        for (KegAt k : scanKegs(level)) {
            if (NavOps.blocked(this.resident, k.pos())) {
                continue; // 拉黑中的桶不接单（同 tryDrawStock）
            }
            ItemStack out = k.snap().slots()[KegAccess.OUTPUT_SLOT];
            if (!out.isEmpty() && outputs.contains(out.getItem())) {
                this.kegPos = k.pos();
                this.kind = Kind.TAKE_FOOD;
                this.phase = Phase.GO_KEG;
                navigateTo(k.pos());
                return true;
            }
        }
        return false;
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
            List<KegRecipes.KegIng> extraIngredients,
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

    /** 配方产物 id → 物品（流体产物==物品同名；物品产物直查）。调用方保证已注册。 */
    private static Item resultItem(KegRecipes.FermentRecipe r) {
        return BuiltInRegistries.ITEM.get(
                ResourceLocation.tryParse(r.resultFluidId()));
    }

    /** 桶内流体是否某配方的成品（酿好/在酿完成态——归接瓶通道，不归续桶）。 */
    private static boolean isResultFluid(List<KegRecipes.FermentRecipe> recipes,
            String fluidId) {
        for (KegRecipes.FermentRecipe r : recipes) {
            if (r.resultIsFluid() && r.resultFluidId().equals(fluidId)) {
                return true;
            }
        }
        return false;
    }

    /** 已收录配方里的全部物品产物（收货通道只认这些，防拿玩家手放的东西）。 */
    private static java.util.Set<Item> itemOutputs(List<KegRecipes.FermentRecipe> recipes) {
        java.util.Set<Item> out = new java.util.HashSet<>();
        for (KegRecipes.FermentRecipe r : recipes) {
            if (!r.resultIsFluid()) {
                out.add(resultItem(r));
            }
        }
        return out;
    }

    /** 投料槽 0~3 是否全空。 */
    private static boolean inputSlotsEmpty(KegAccess.KegSnap snap) {
        for (int i = 0; i <= 3 && i < snap.slots().length; i++) {
            if (!snap.slots()[i].isEmpty()) {
                return false;
            }
        }
        return true;
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
        Container cellarC = cellarView(level); // 聚合视图：跨容器计数/取货（#99）
        Stock stock = cellarScan(level);
        int defaultTarget = Math.max(1, Config.TAVERN_STOCK_TARGET.get());
        // 酒吧菜单（决策 #86）：null=未配置全维护；非 null=严格按菜单
        Map<Item, Integer> menu = tavernMenu();

        // ① 续桶优先（2026-11-18 用户拍板，炼金 reconcileStand 同思想）：
        //    被打断/有半成品的桶必须接上——料已经进去一半，弃之既浪费又留死桶。
        //    续桶<b>不做菜单门/温度门</b>（料已半进桶，弃之死局；BAC 自己等温度），
        //    但出料槽有货的桶除外——先走收货通道（#85：滞留产物+投新料=丢产物）。
        BlockPos keg = null;
        BrewPlan plan = null;
        double bestDist = Double.MAX_VALUE;
        for (KegAt k : kegs) {
            if (NavOps.blocked(this.resident, k.pos())) {
                continue; // 拉黑中的桶不接单（同 tryDrawStock）
            }
            if (k.snap().fluid() == null && inputSlotsEmpty(k.snap())
                    && k.snap().slots()[KegAccess.OUTPUT_SLOT].isEmpty()) {
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
        // ② 空桶新批次：菜单闭包 + 温度门（#85 阶段二）+ 原料预留（#85 阶段一）。
        //    lastBrewMissing 优先保留 ① 记下的续桶缺口（半成品差额更精准可达成）。
        if (keg == null) {
            // 菜单闭包（决策 #86）：直接产物 ∈ 菜单 + 二层酒要的原料酒豁免进池
            // （想要强根艾尔 → 啤酒自动豁免——它没有独立目标量，按需补料即可）。
            java.util.Set<Item> wanted = menuClosure(level, menu);
            // 原料预留（#85 阶段一）：产物未达标的闭包内配方，把它"能自己酿
            // 出来"的输入计入需求——基液酒（强根艾尔要 4 杯啤酒→啤酒衍生
            // 目标量）与配料产物（强根艾尔要肉干→肉干衍生目标量）；非产物的
            // 配料（甜菜等）永远是玩家/农业补，不预留。不等配料齐才预留
            // （2026-11-18 二次实测：等齐=链条前段继续死锁）——啤酒先酿出来
            // 本就能上酒给客人，肉干酿好本就入窖，都不算浪费。
            Map<Item, Integer> reserve = new HashMap<>();
            for (KegRecipes.FermentRecipe r : recipes) {
                if (wanted != null && !wanted.contains(resultItem(r))) {
                    continue;
                }
                Item res = resultItem(r);
                int rTarget = wanted == null ? defaultTarget
                        : menu.getOrDefault(res, 0);
                if (productOnHand(r, stock, kegs, cellarC) >= rTarget) {
                    continue;
                }
                if (r.baseIsDrink() && r.baseSource() != null) {
                    reserve.merge(r.baseSource(), sourceCountFor(r), Integer::sum);
                }
                for (KegRecipes.KegIng ing : r.ingredients()) {
                    if (isProductOf(recipes, ing)) {
                        reserve.merge(ing.displayItem(), 1, Integer::sum);
                    }
                }
            }
            // 特种桶认领（决策 #88，2026-11-18 实测：啤酒没有温度要求、挑桶
            // 只看距离，温暖桶往往最近——啤酒一轮轮霸占特种桶，肉干/泡菜
            // 永远抢不到）。先让"要特定温度且未达标"的配方认领满足档位的桶，
            // 无温度要求的配方再挑：常温桶（档3）> 未认领特种桶 > 认领了的
            // 特种桶（最后兜底防断流）。
            java.util.Set<BlockPos> claimed = new java.util.HashSet<>();
            for (KegAt k : kegs) {
                if (NavOps.blocked(this.resident, k.pos())) {
                    continue; // 拉黑中的桶不认领（同 tryDrawStock）
                }
                if (k.snap().fluid() != null || !inputSlotsEmpty(k.snap())
                        || !k.snap().slots()[KegAccess.OUTPUT_SLOT].isEmpty()) {
                    continue;
                }
                for (KegRecipes.FermentRecipe r : recipes) {
                    if (r.temperature() <= 0
                            || (wanted != null && !wanted.contains(resultItem(r)))
                            || !KegAccess.tempOk(k.snap().temperature(), r.temperature())) {
                        continue;
                    }
                    int rt = wanted == null ? defaultTarget
                            : menu.getOrDefault(resultItem(r), 0);
                    if (productOnHand(r, stock, kegs, cellarC)
                            < rt + reserve.getOrDefault(resultItem(r), 0)) {
                        claimed.add(k.pos());
                        break;
                    }
                }
            }
            // 配方主选：每条达标配方按 优先级(温度贴合) + 距离 挑桶；
            // 顺带给每条记跳过原因（停手时输出诊断——2026-11-18 实测三轮
            // 排障全靠脑补，这里让日志直接说人话）
            KegRecipes.FermentRecipe best = null;
            BlockPos bestKeg = null;
            double bestPickDist = Double.MAX_VALUE;
            int bestTier = Integer.MAX_VALUE;
            java.util.LinkedHashMap<String, String> reasons = new java.util.LinkedHashMap<>();
            for (KegRecipes.FermentRecipe r : recipes) {
                String rid = BuiltInRegistries.ITEM.getKey(resultItem(r)).getPath();
                if (wanted != null && !wanted.contains(resultItem(r))) {
                    continue; // 菜单外：不酿、不播报、不记（#86 痛点：不骚扰）
                }
                Item bottled = resultItem(r);
                int t = wanted == null ? defaultTarget
                        : menu.getOrDefault(bottled, 0);
                // 在途也入账（2026-11-17 用户拍板）：饮品=窖瓶+在酿/酿好未接
                // 折算；物品产物（肉干等）=窖内件数+出料槽/在酿整批折算
                int have = productOnHand(r, stock, kegs, cellarC);
                int need = t + reserve.getOrDefault(bottled, 0);
                if (have >= need) {
                    // 目标 0 的闭包成员（beer/jerky 等：父酒够量就不囤料）不进
                    // 诊断行——"够量 4/0"是纯噪音，2026-11-18 实测用户被这行
                    // 搞得以为跨酒串了原料
                    if (need > 0) {
                        reasons.put(rid, "够量 " + have + "/" + need
                                + (t == 0 ? "（原料预留）" : ""));
                    }
                    continue; // 这个产物够了（含预留的原料需求）
                }
                List<ItemStack> missing = missingFor(level, cellarC, r);
                if (this.lastBrewMissing == null
                        || missing.size() < this.lastBrewMissing.size()) {
                    this.lastBrewMissing = missing; // 记缺口最少的（播报用）
                }
                if (!missing.isEmpty()) {
                    List<String> labels = new ArrayList<>();
                    for (ItemStack m : missing) {
                        labels.add(BuiltInRegistries.ITEM.getKey(m.getItem()).getPath()
                                + (m.getCount() > 1 ? "×" + m.getCount() : ""));
                    }
                    reasons.put(rid, "缺料[" + String.join(",", labels) + "]");
                    continue;
                }
                BlockPos chosen = null;
                double chosenDist = Double.MAX_VALUE;
                int chosenTier = Integer.MAX_VALUE;
                for (KegAt k : kegs) {
                    if (NavOps.blocked(this.resident, k.pos())) {
                        continue; // 拉黑中的桶不接单（同 tryDrawStock）
                    }
                    if (k.snap().fluid() != null || !inputSlotsEmpty(k.snap())
                            || !k.snap().slots()[KegAccess.OUTPUT_SLOT].isEmpty()) {
                        continue; // 只挑全空桶（出料槽有货=归收货通道）
                    }
                    if (!KegAccess.tempOk(k.snap().temperature(), r.temperature())) {
                        continue; // 温度不满足：投了也不开酿，跳过（环境等玩家建）
                    }
                    int kt = k.snap().temperature();
                    int tier;
                    if (r.temperature() > 0) {
                        tier = claimed.contains(k.pos()) ? 1 : 0;
                    } else if (kt == NORMAL_TEMP) {
                        tier = 0; // 常温桶给无温度要求的配方（啤酒/蜂蜜酒/强根艾尔）
                    } else {
                        tier = claimed.contains(k.pos()) ? 2 : 1; // 特种桶：认领的垫底
                    }
                    double d = this.resident.blockPosition().distSqr(k.pos());
                    if (tier < chosenTier || (tier == chosenTier && d < chosenDist)) {
                        chosenTier = tier;
                        chosenDist = d;
                        chosen = k.pos();
                    }
                }
                if (chosen == null) {
                    reasons.put(rid, "无适配桶(要温度档" + r.temperature() + ")");
                    continue;
                }
                // 跨配方裁决=(tier, 距离)字典序——只比距离时，无温度要求的配方
                // （啤酒）若先遍历会在同桶同距离的平局里永远压住特种配方（肉干），
                // 单桶布局=肉干被无限插队（2026-11-18 实测"啤酒一直酿肉干不动"）
                if (chosenTier < bestTier
                        || (chosenTier == bestTier && chosenDist < bestPickDist)) {
                    bestPickDist = chosenDist;
                    bestTier = chosenTier;
                    best = r;
                    bestKeg = chosen;
                }
            }
            if (best == null) {
                // 停手诊断：原因有变化才输出（不刷屏；排障直接看这行）
                if (!reasons.isEmpty() && !reasons.equals(this.lastPickReasons)) {
                    LOGGER.info("[BmfTown][酒保] {} 排产停手：{}", this.resident
                            .getName().getString(), reasons);
                    this.lastPickReasons = reasons;
                }
                return false; // 全都够/全都缺料/温度不满足/菜单外：缺料走播报
            }
            this.lastPickReasons = null;
            keg = bestKeg;
            plan = BrewPlan.fresh(best);
        }
        // 水基取水计划（决策 #87）：仅新批次走（fresh 的 assumedFluidId=null）；
        // 续桶基液补差仍走水桶老路径。计划与 missingFor 同源，结论矛盾=竞态，
        // 下轮重算。瓶/桶口径不同→份数改写进计划（POUR 按份数循环）。
        this.waterPlan = null;
        this.waterTarget = null;
        this.waterSpot = null;
        if (plan.assumedFluidId() == null && isWaterBase(plan.recipe())) {
            Container cc = cellarView(level);
            WaterPlan wp = planWater(plan.recipe().baseAmountMb(),
                    KegAccess.countItem(cc, new ItemStack(Items.WATER_BUCKET)),
                    KegAccess.countItem(cc, waterBottleTemplate()),
                    KegAccess.countItem(cc, new ItemStack(Items.GLASS_BOTTLE)),
                    waterStationAt(level) != null);
            if (!wp.feasible()) {
                return false;
            }
            int sourceCount = wp.buckets() + wp.waterBottles() + wp.emptyToFill();
            plan = new BrewPlan(plan.recipe(), sourceCount,
                    plan.extraIngredients(), plan.assumedFluidId(),
                    plan.assumedDroplets(), plan.assumedLoaded());
            this.waterPlan = wp;
        }
        this.recipe = plan.recipe();
        this.brewPlan = plan;
        this.kegPos = keg;
        this.cellarPos = cellar;
        this.kind = Kind.BREW;
        this.phase = Phase.FETCH;
        diag("开工", BuiltInRegistries.ITEM.getKey(resultItem(plan.recipe())).getPath()
                + " → 桶 " + keg.toShortString() + "（要温度档 "
                + plan.recipe().temperature() + "）");
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
            // 配料对账：已投部分必须恰好是配方配料的子集（#88 tag 感知——
            // 玩家手投生牛肉/腐肉都算数，逐条扣额度）
            List<ItemStack> loaded = new ArrayList<>();
            for (int i = 0; i <= 3 && i < snap.slots().length; i++) {
                ItemStack l = snap.slots()[i];
                if (!l.isEmpty()) {
                    loaded.add(l.copy());
                }
            }
            List<KegRecipes.KegIng> extra = new ArrayList<>();
            boolean ok = true;
            for (KegRecipes.KegIng w : r.ingredients()) {
                boolean consumed = false;
                for (ItemStack l : loaded) {
                    if (l.getCount() > 0 && w.matches(l)) {
                        l.setCount(0);
                        consumed = true;
                        break;
                    }
                }
                if (!consumed) {
                    extra.add(w);
                }
            }
            for (ItemStack l : loaded) {
                if (l.getCount() > 0) {
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
            if (r.baseSource() != null) {
                int haveBase = KegAccess.countItem(cellar, new ItemStack(r.baseSource()));
                if (extraSources > 0 && haveBase < extraSources) {
                    missing.add(new ItemStack(r.baseSource()));
                }
            }
            for (KegRecipes.KegIng e : extra) {
                if (KegAccess.countIf(cellar, e::matches) < 1) {
                    missing.add(e.rep());
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

    /** 这条配方从酒窖还拿不出什么（基液源 + 配料，各 1 个口径；无基液配方跳过基液项）。 */
    private List<ItemStack> missingFor(ServerLevel level, Container stock,
            KegRecipes.FermentRecipe r) {
        List<ItemStack> missing = new ArrayList<>();
        if (stock == null) {
            if (r.baseSource() != null) {
                missing.add(new ItemStack(r.baseSource(),
                        Math.max(1, sourceCountFor(r))));
            }
            for (KegRecipes.KegIng ing : r.ingredients()) {
                missing.add(ing.rep());
            }
            return missing;
        }
        int need = sourceCountFor(r);
        if (need > 0) {
            if (isWaterBase(r)) {
                // 水基（决策 #87）：水源站登记后水可以自取（空瓶→站上装水），
                // 不再"缺水桶"卡死——计划可行就不算缺
                BlockPos station = waterStationAt(level);
                int haveBuckets = KegAccess.countItem(stock,
                        new ItemStack(Items.WATER_BUCKET));
                int haveWater = KegAccess.countItem(stock, waterBottleTemplate());
                int haveEmpty = KegAccess.countItem(stock,
                        new ItemStack(Items.GLASS_BOTTLE));
                WaterPlan wp = planWater(r.baseAmountMb(), haveBuckets,
                        haveWater, haveEmpty, station != null);
                if (!wp.feasible()) {
                    int bucketsNeeded = (int) Math.ceil(
                            r.baseAmountMb() / 1000.0D);
                    int bottlesNeeded = (int) Math.ceil(
                            r.baseAmountMb() / 250.0D);
                    if (station == null) {
                        // 没水源站：现成水瓶也算水量，差额折回水桶数播报
                        int deficitBottles = bottlesNeeded - haveWater;
                        missing.add(new ItemStack(Items.WATER_BUCKET,
                                Math.max(1, (int) Math.ceil(
                                        deficitBottles * 250.0D / 1000.0D))));
                    } else {
                        // 有水源站：瓶装水单位（现成水瓶+空瓶）一起对账（#101）——
                        // 3 水瓶+0 空瓶报"缺 1 瓶"而不是"缺 4 瓶"
                        missing.add(new ItemStack(Items.GLASS_BOTTLE,
                                Math.max(1, bottlesNeeded - haveWater
                                        - haveEmpty)));
                    }
                }
            } else {
                int haveBase = KegAccess.countItem(stock,
                        new ItemStack(r.baseSource()));
                if (haveBase < need) {
                    // 缺口数量口径（2026-11-18 实测）：差几杯报几杯，不恒写 ×1
                    missing.add(new ItemStack(r.baseSource(), need - haveBase));
                }
            }
        }
        missing.addAll(missingIngredients(stock, r));
        return missing;
    }

    /**
     * 配料缺什么（不含基液源；#88 tag 感知——tag 配料按成员匹配）。
     * 2026-11-18 实测修：相同配料条目会重复出现（肉干=3×生肉 tag），共享
     * 同一个酒窖池——逐条各查"≥1"会把 2 份存货判成 3 条全齐，取货时第 3 份
     * 才失败=无限空跑。必须聚合成"规格→需求条数"，按总件数对账。
     */
    private List<ItemStack> missingIngredients(Container stock,
            KegRecipes.FermentRecipe r) {
        java.util.LinkedHashMap<KegRecipes.KegIng, Integer> need =
                new java.util.LinkedHashMap<>();
        for (KegRecipes.KegIng ing : r.ingredients()) {
            need.merge(ing, 1, Integer::sum);
        }
        List<ItemStack> missing = new ArrayList<>();
        for (java.util.Map.Entry<KegRecipes.KegIng, Integer> e : need.entrySet()) {
            int have = KegAccess.countIf(stock, e.getKey()::matches);
            if (have < e.getValue()) {
                // 缺口数量口径：差几份报几份（播报可见）
                missing.add(e.getKey().rep().copyWithCount(e.getValue() - have));
            }
        }
        return missing;
    }

    /** 基液源件数：水/奶 1 桶；蜂蜜/酒 ceil(配方量 / 每份量)；无基液（直酿）= 0。 */
    private static int sourceCountFor(KegRecipes.FermentRecipe r) {
        if (r.baseSource() == null) {
            return 0;
        }
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
        // 取料必须人到位（#66 实测）；NavOps 有限重试，窖到不了就放弃本单
        switch (NavOps.approach(this.resident, this.cellarPos,
                OPEN_RANGE, Config.STROLL_SPEED.get())) {
            case STUCK -> {
                backToScan();
                return;
            }
            case MOVING -> {
                return;
            }
            case ARRIVED -> {
            }
        }
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
        Container stock = cellarView(level);
        if (this.brewPlan == null) {
            backToScan();
            return;
        }
        // 只取差额（续桶=补剩余部分；新批次=足量，两者统一走计划）。
        // 基液取料清单跟取水计划走（#87/#101）：水桶老路径=水桶；瓶路径=
        // 现成水瓶优先、差额取空瓶（到站装水）——三来源可混用，按段取。
        // 直酿配方（肉干/泡菜，无基液）extraSources=0——基液清单为空。
        int need = this.brewPlan.extraSources();
        java.util.List<ItemStack> baseWanted = new ArrayList<>();
        if (this.waterPlan != null) {
            for (int i = 0; i < this.waterPlan.buckets(); i++) {
                baseWanted.add(new ItemStack(Items.WATER_BUCKET));
            }
            for (int i = 0; i < this.waterPlan.waterBottles(); i++) {
                baseWanted.add(waterBottleTemplate());
            }
            for (int i = 0; i < this.waterPlan.emptyToFill(); i++) {
                baseWanted.add(new ItemStack(Items.GLASS_BOTTLE));
            }
        } else if (need > 0 && this.brewPlan.recipe().baseSource() != null) {
            ItemStack t = new ItemStack(this.brewPlan.recipe().baseSource());
            for (int i = 0; i < need; i++) {
                baseWanted.add(t);
            }
        }
        for (int i = 0; i < baseWanted.size(); i++) {
            ItemStack template = baseWanted.get(i);
            ItemStack taken = KegAccess.takeOne(stock, template);
            if (taken.isEmpty()) {
                diag("取料", "基液 " + BuiltInRegistries.ITEM.getKey(
                        template.getItem()).getPath() + " 第 " + (i + 1)
                        + "/" + baseWanted.size() + " 份取空——排产时够、取时被拿走，货回窖重排");
                returnCargoToCellar(level); // 有料被人拿走了：把手上的放回去
                backToScan();
                return;
            }
            cargo().add(taken);
        }
        for (KegRecipes.KegIng ing : this.brewPlan.extraIngredients()) {
            ItemStack taken = KegAccess.takeIf(stock, ing::matches);
            if (taken.isEmpty()) {
                diag("取料", "配料 " + BuiltInRegistries.ITEM.getKey(
                        ing.rep().getItem()).getPath() + " 取空——货回窖重排");
                returnCargoToCellar(level);
                backToScan();
                return;
            }
            cargo().add(taken);
        }
        carryVisible(cargo().remove(0)); // 端着第一件料走（其余在货舱）
        if (this.waterPlan != null && this.waterPlan.needsStation()) {
            this.phase = Phase.GO_WATER; // 先去水源站装水，再投桶
            this.waterTarget = null; // tickGoWater 定位
            this.waterSpot = null;
            return;
        }
        this.phase = Phase.GO_KEG;
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
        switch (NavOps.approach(this.resident, this.cellarPos,
                OPEN_RANGE, Config.STROLL_SPEED.get())) {
            case STUCK -> {
                backToScan();
                return;
            }
            case MOVING -> {
                return;
            }
            case ARRIVED -> {
            }
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
        Container stock = cellarView(level);
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
        switch (NavOps.approach(this.resident, this.cellarPos,
                OPEN_RANGE, Config.STROLL_SPEED.get())) {
            case STUCK -> {
                backToScan();
                return;
            }
            case MOVING -> {
                return;
            }
            case ARRIVED -> {
            }
        }
        if (this.lidTimer == 0) {
            this.resident.containerFx(level, this.cellarPos, true);
            this.lidTimer = LID_TICKS;
            return;
        }
        if (--this.lidTimer > 0) {
            return;
        }
        this.resident.containerFx(level, this.cellarPos, false);
        Container stock = cellarView(level);
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
        // 导航目标=桶旁可站立落脚点而非桶本身（坑37 口径，2026-11-19 实测：
        // 酒保反复楔死在酒桶旁 1/16 缝里——20,69,21 紧贴桶 20,69,20）。
        // allowAbove=false（2026-11-20 实测"封桶顶他站盖板上隔着交互"）：
        // 桶顶/盖板顶永远不是工作位，交互只在桶侧同层或更低——侧面全围死
        // 就弃桶，绝不爬顶。
        // 落脚点被拉黑=spotNear 自动换下一个（桶的另一面）；全失败才弃桶回窖。
        BlockPos spot = NavOps.spotNear(level, this.resident, this.kegPos,
                2, 2, OPEN_RANGE, false);
        if (spot == null) {
            // 桶周围全被拉黑/围死：货回窖重排，桶拉黑，SCAN 换别的桶/别的活
            NavOps.blacklist(this.resident, this.kegPos);
            returnCargoToCellar(level);
            backToScan();
            return;
        }
        switch (NavOps.approach(this.resident, spot,
                OPEN_RANGE, Config.STROLL_SPEED.get(), 0)) {
            case STUCK -> {
                // 落脚点级联：approach 已把 spot 拉黑，下 tick spotNear 换新点；
                // 全部落脚点烧完才走上面的 null 分支弃桶
                return;
            }
            case MOVING -> {
                return;
            }
            case ARRIVED -> {
            }
        }
        reclaimCarried(); // 展示品收回事列头（恢复 [基液..., 配料...] 取用顺序）
        switch (this.kind) {
            case DRAW_STOCK -> {
                // 接到本轮额度（桶内整杯数×空杯数取小）或桶见底为止（#81 每杯耗一杯；#83 杯型跟饮品走）
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
                    diag("到场", "桶 " + this.kegPos.toShortString()
                            + " 内容与计划不符（被玩家先动了），货回窖重排");
                    returnCargoToCellar(level); // 桶被人先用了：货回窖
                    backToScan();
                } else {
                    this.phase = Phase.POUR;
                    this.pourIndex = 0;
                    this.pourCooldown = POUR_STEP_TICKS;
                }
            }
            case TAKE_FOOD -> {
                // 收货（#85 阶段二）：取走 5 号出料槽的物品产物入窖
                ItemStack taken = KegAccess.takeOutput(level, this.kegPos);
                if (taken.isEmpty()) {
                    backToScan(); // 被玩家先收了：本轮作废
                } else {
                    diag("收货", BuiltInRegistries.ITEM.getKey(taken.getItem())
                            .getPath() + "×" + taken.getCount() + " 出料槽入窖");
                    cargo().add(taken);
                    carryVisible(cargo().remove(0));
                    this.cellarPos = findStock(level);
                    this.phase = Phase.STORE;
                    navigateTo(this.cellarPos != null ? this.cellarPos : this.kegPos);
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
        switch (NavOps.approach(this.resident, this.kegPos,
                OPEN_RANGE, Config.STROLL_SPEED.get(), 0)) {
            case STUCK -> {
                // 投料途中桶被围死：手上货回窖（已倒进桶的基液归续桶自愈）
                returnCargoToCellar(level);
                backToScan();
                return;
            }
            case MOVING -> {
                return;
            }
            case ARRIVED -> {
            }
        }
        if (this.pourCooldown > 0) {
            this.pourCooldown--;
            return;
        }
        int sources = this.brewPlan.extraSources();
        if (this.pourIndex < sources) {
            // —— 基液：第 pourIndex 瓶/桶（倒完原位换成余料容器，绝不吞）；
            //    余料口径=pourRemainder（水桶→空桶、奶桶→空桶、蜂蜜瓶→玻璃瓶、
            //    酒瓶→酒杯——BAC 全系酒 craftRemainder=tankard，#85 酒倒酒同规；
            //    水瓶没有合成余料，倒完补回空玻璃瓶——见 pourRemainder），
            //    每份口径<b>跟着货物走</b>（#87）：水桶=1000mB（配方口径）、
            //    水瓶=250mB——pourBaseFluid 同种流体累加——续桶补差额天然成立——
            ItemStack src = cargo().get(this.pourIndex);
            long perSource = isWaterBottle(src)
                    ? 250L * KegAccess.DROPLETS_PER_MB
                    : this.brewPlan.recipe().basePerSourceDroplets();
            if (!KegAccess.pourBaseFluid(level, this.kegPos,
                    this.brewPlan.recipe().baseFluidId(), perSource)) {
                returnCargoToCellar(level);
                backToScan();
                return;
            }
            ItemStack rem = pourRemainder(src);
            cargo().set(this.pourIndex,
                    rem == null || rem.isEmpty() ? ItemStack.EMPTY : rem);
            this.resident.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            this.pourIndex++;
            this.pourCooldown = POUR_STEP_TICKS;
            return;
        }
        int ing = this.pourIndex - sources;
        if (ing < this.brewPlan.extraIngredients().size()) {
            // —— 配料：第 ing 件（#88：入桶的是<b>真实取来的货物</b>——tag 配料
            //    玩家囤生牛肉就投生牛肉，不再用代表物品模板替换；货列里删除
            //    防复制，基液余料都在前 sources 格不受影响）——
            ItemStack real = cargo().get(sources + ing);
            if (real.isEmpty()) {
                returnCargoToCellar(level);
                backToScan();
                return;
            }
            if (!KegAccess.insertIngredients(level, this.kegPos, List.of(real))) {
                returnCargoToCellar(level);
                backToScan();
                return;
            }
            cargo().set(sources + ing, ItemStack.EMPTY); // 原物件销账防复制回窖
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
        switch (NavOps.approach(this.resident, store,
                OPEN_RANGE, Config.STROLL_SPEED.get())) {
            case STUCK -> {
                // 窖到不了：货留在货列（持久化），绝不清不丢——下轮孤儿回收重试
                backToScan();
                return;
            }
            case MOVING -> {
                return;
            }
            case ARRIVED -> {
            }
        }
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
            if (st.isEmpty()) {
                continue; // #88：投桶销账的空占位（真实配料已入桶），跳过
            }
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
        this.waterPlan = null;
        this.waterTarget = null;
        this.waterSpot = null;
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

    /**
     * 全酒窖<b>聚合视图</b>（#99，2026-11-20 实测"瓶子和杯子明明在窖里却永远
     * 缺料"）：旧实现排产计数/取料只读 {@code findStock} 选出的<b>最近一口
     * 容器</b>，物料分装在别的箱子（或最近的箱子被寻路拉黑跳过）时读数恒 0
     * ——日志实锤 {@code 缺料[glass_bottle×4]} 整数缺口=单容器计数为 0。
     * 排产/对账/取货统一改走本视图：跨容器计数与取货，slot 线性映射到子
     * 容器的<b>活栈</b>（takeIf 的 shrink 直改底层，setChanged 遍历落盘）。
     * BAC 酿造桶排除——桶内半成品不是窖存（收货通道管）。
     */
    private Container cellarView(ServerLevel level) {
        List<Container> parts = new ArrayList<>();
        Block keg = kegBlock();
        for (BlockPos pos : cellarContainers(level)) {
            if (keg != null && level.getBlockState(pos).is(keg)) {
                continue; // 酿造桶不是储物箱
            }
            Container c = containerAt(level, pos);
            if (c != null) {
                parts.add(c);
            }
        }
        return new CellarView(parts);
    }

    public static final class CellarView implements Container {
        private final List<Container> parts;

        public CellarView(List<Container> parts) {
            this.parts = parts;
        }

        @Override
        public int getContainerSize() {
            int n = 0;
            for (Container c : this.parts) {
                n += c.getContainerSize();
            }
            return n;
        }

        @Override
        public ItemStack getItem(int slot) {
            int base = 0;
            for (Container c : this.parts) {
                int s = c.getContainerSize();
                if (slot >= base && slot < base + s) {
                    return c.getItem(slot - base);
                }
                base += s;
            }
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            int base = 0;
            for (Container c : this.parts) {
                int s = c.getContainerSize();
                if (slot >= base && slot < base + s) {
                    return c.removeItem(slot - base, amount);
                }
                base += s;
            }
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            int base = 0;
            for (Container c : this.parts) {
                int s = c.getContainerSize();
                if (slot >= base && slot < base + s) {
                    return c.removeItemNoUpdate(slot - base);
                }
                base += s;
            }
            return ItemStack.EMPTY;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            int base = 0;
            for (Container c : this.parts) {
                int s = c.getContainerSize();
                if (slot >= base && slot < base + s) {
                    c.setItem(slot - base, stack);
                    return;
                }
                base += s;
            }
        }

        @Override
        public void setChanged() {
            for (Container c : this.parts) {
                c.setChanged();
            }
        }

        @Override
        public boolean stillValid(net.minecraft.world.entity.player.Player player) {
            for (Container c : this.parts) {
                if (c.stillValid(player)) {
                    return true;
                }
            }
            return this.parts.isEmpty();
        }

        @Override
        public boolean isEmpty() {
            for (Container c : this.parts) {
                if (!c.isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public void clearContent() {
            for (Container c : this.parts) {
                c.clearContent();
            }
        }
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
            if (NavOps.blocked(this.resident, pos)) {
                continue; // 寻路黑名单：放弃过的容器 10 秒内不选
            }
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
                if (NavOps.blocked(this.resident, pos)) {
                    continue; // 寻路黑名单：放弃过的桶 10 秒内不选
                }
                KegAccess.KegSnap snap = KegAccess.read(level, pos);
                if (snap != null) {
                    list.add(new KegAt(pos.immutable(), snap));
                }
            }
        }
        return list;
    }

    /** 区内最近的处于目标状态的酒桶；无则 null。发酵中/投料中途的桶
     *  （{@link #midBrewKeg}）对 DRAWABLE 恒不可见——现场接客人的酒也
     *  不能从二层酒桶里舀基液。 */
    private BlockPos findKeg(ServerLevel level, KegState wanted) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        List<KegRecipes.FermentRecipe> recipes = KegRecipes.all(level);
        for (KegAt k : scanKegs(level)) {
            boolean ok = switch (wanted) {
                case DRAWABLE -> KegAccess.drawable(level, k.pos())
                        && !midBrewKeg(k.snap(), recipes);
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
    /** 产物在手量（2026-11-18 口径扩展）：饮品产物=窖瓶折算+在途瓶；
     *  物品产物（肉干等）=窖内件数+出料槽积货+在酿整批折算——否则物品
     *  产物恒 0，衍生目标量永远"没够过"，链式预留死循环。 */
    private int productOnHand(KegRecipes.FermentRecipe r, Stock stock,
            List<KegAt> kegs, Container cellarC) {
        Item res = resultItem(r);
        if (r.resultIsFluid()) {
            return stock.bottled().getOrDefault(res, 0) + inFlightBottles(kegs, r);
        }
        int have = cellarC == null ? 0
                : KegAccess.countItem(cellarC, new ItemStack(res));
        for (KegAt k : kegs) {
            ItemStack out = k.snap().slots()[KegAccess.OUTPUT_SLOT];
            if (!out.isEmpty() && out.is(res)) {
                have += out.getCount(); // 酿好未收
            } else if (k.snap().fluid() == null
                    && ingredientsLoaded(k.snap().slots(), r.ingredients())) {
                have += Math.max(1, r.resultCount()); // 在酿整批折算
            }
        }
        return have;
    }

    private int inFlightBottles(List<KegAt> kegs, KegRecipes.FermentRecipe r) {
        int bottles = 0;
        for (KegAt k : kegs) {
            KegAccess.KegSnap snap = k.snap();
            if (snap.fluid() == null) {
                continue;
            }
            if (r.resultIsFluid() && snap.fluid().fluidId().equals(r.resultFluidId())) {
                bottles += (int) (snap.fluid().droplets()
                        / KegAccess.TANKARD_DROPLETS);
            } else if (r.baseFluidId() != null
                    && snap.fluid().fluidId().equals(r.baseFluidId())
                    && ingredientsLoaded(snap.slots(), r.ingredients())) {
                bottles += Math.max(1, (int) (r.basePerSourceDroplets()
                        * sourceCountFor(r) / KegAccess.TANKARD_DROPLETS));
            }
        }
        return bottles;
    }

    /** 桶投料槽的物品是否恰好配齐了配方的配料（#88 tag 感知；发酵期配料留在桶里）。 */
    private static boolean ingredientsLoaded(ItemStack[] slots,
            List<KegRecipes.KegIng> want) {
        List<ItemStack> have = new ArrayList<>();
        for (int i = 0; i <= 3 && i < slots.length; i++) { // 0~3 投料槽（KegAccess 口径）
            if (!slots[i].isEmpty()) {
                have.add(slots[i]);
            }
        }
        if (have.size() != want.size()) {
            return false;
        }
        for (KegRecipes.KegIng w : want) {
            boolean found = false;
            for (ItemStack h : have) {
                if (h.getCount() == 1 && w.matches(h)) {
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
    //  酒吧菜单（决策 #86）
    // ==========================================================

    /**
     * 本镇菜单：产物物品 → 目标量；<b>null = 未配置</b>（全维护，目标量走
     * 全局默认）。菜单 key 是任意产物物品 id——饮品与物品产物（肉干等酿造
     * 原料）通吃：肉干不进菜单就没人自动做，强根艾尔链会永远缺料。
     * 未知 id（mod 卸了）静默跳过，降级安全。
     */
    private Map<Item, Integer> tavernMenu() {
        Town town = boundTown();
        if (town == null || town.tavernMenu().isEmpty()) {
            return null;
        }
        Map<Item, Integer> out = new HashMap<>();
        for (Map.Entry<String, Integer> e : town.tavernMenu().entrySet()) {
            ResourceLocation rl = ResourceLocation.tryParse(e.getKey());
            Item it = rl == null ? Items.AIR : BuiltInRegistries.ITEM.get(rl);
            if (it != Items.AIR) {
                out.put(it, e.getValue());
            }
        }
        return out;
    }

    /**
     * 菜单闭包（决策 #86）：直接产物 ∈ 菜单 + 二层酒要的原料酒<b>递归</b>
     * 豁免进池（想要强根艾尔 → 啤酒进池；啤酒若是别的高层酒基液则继续豁免）。
     * {@code menu == null}（未配置全维护）→ 返回 null（= 不过滤）。
     * 排产预留与缺料播报共用同一口径。
     */
    private java.util.Set<Item> menuClosure(ServerLevel level,
            Map<Item, Integer> menu) {
        if (menu == null) {
            return null;
        }
        java.util.Set<Item> wanted = new java.util.HashSet<>(menu.keySet());
        boolean grew = true;
        List<KegRecipes.FermentRecipe> recipes = KegRecipes.all(level);
        while (grew) {
            grew = false;
            for (KegRecipes.FermentRecipe r : recipes) {
                if (!wanted.contains(resultItem(r))) {
                    continue;
                }
                // 基液是酒 → 原料酒豁免（强根艾尔 → 啤酒）
                if (r.baseIsDrink() && r.baseSource() != null
                        && wanted.add(r.baseSource())) {
                    grew = true;
                }
                // 配料本身是可酿产物 → 配料豁免（强根艾尔 → 肉干；2026-11-18
                // 实测：只豁免基液时肉干不在菜单就没人酿，链条前段卡死）
                for (KegRecipes.KegIng ing : r.ingredients()) {
                    if (isProductOf(recipes, ing)
                            && wanted.add(ing.displayItem())) {
                        grew = true;
                    }
                }
            }
        }
        return wanted;
    }

    /** 某配方的产物能否充当这条配料（#88 tag 感知：产物物品按配料口径匹配）。 */
    private static boolean isProductOf(List<KegRecipes.FermentRecipe> recipes,
            KegRecipes.KegIng ing) {
        for (KegRecipes.FermentRecipe r : recipes) {
            if (ing.matches(new ItemStack(resultItem(r)))) {
                return true;
            }
        }
        return false;
    }

    /** 闭包内是否存在以 {@code source} 为基液的配方（奶/蜜维护播报的门）。 */
    private boolean closureUsesSource(ServerLevel level,
            java.util.Set<Item> wanted, Item source) {
        if (wanted == null) {
            return true; // 未配置全维护：奶/蜜照旧维护
        }
        for (KegRecipes.FermentRecipe r : KegRecipes.all(level)) {
            if (source.equals(r.baseSource()) && wanted.contains(resultItem(r))) {
                return true;
            }
        }
        return false;
    }

    // ==========================================================
    //  水源站补水（决策 #87，复用工坊登记池 + 炼金 #34 口径）
    // ==========================================================

    /**
     * 水基配方的取水计划（<b>纯函数出口，GameTest 直测</b>）。优先级：
     * 现成水桶（1 桶=1000mB，老路径）&gt; 现成水瓶（250mB/瓶）&gt;
     * 空瓶+水源站就地装水；都不够=不可行（缺料播报）。
     *
     * <p>#101（2026-11-20 实测口径）：三来源<b>可混用</b>——打断归窖的
     * 水瓶是现成水量，"3 水瓶+1 空瓶+水源站"合计正好 1000mB，旧实现
     * 只认单一模式（全水瓶/全空瓶）把这种组合误判成缺料停工。混用计划：
     * 现成水瓶优先用，差额取空瓶到站装。
     */
    public record WaterPlan(boolean feasible, int buckets, int waterBottles,
            int emptyToFill) {
        /** 是否要去水源站装水（FETCH 取的是空瓶）。 */
        public boolean needsStation() {
            return this.emptyToFill > 0;
        }
    }

    public static WaterPlan planWater(int needMb, int haveBuckets,
            int haveWaterBottles, int haveEmptyBottles,
            boolean stationRegistered) {
        int bucketsNeeded = (int) Math.ceil(needMb / 1000.0D);
        if (haveBuckets >= bucketsNeeded) {
            return new WaterPlan(true, bucketsNeeded, 0, 0);
        }
        int bottlesNeeded = (int) Math.ceil(needMb / 250.0D);
        if (haveWaterBottles >= bottlesNeeded) {
            return new WaterPlan(true, 0, bottlesNeeded, 0);
        }
        // 混用：现成水瓶优先，差额空瓶到站装（#101）
        if (stationRegistered
                && haveWaterBottles + haveEmptyBottles >= bottlesNeeded) {
            int useWater = Math.min(haveWaterBottles, bottlesNeeded);
            return new WaterPlan(true, 0, useWater, bottlesNeeded - useWater);
        }
        return new WaterPlan(false, 0, 0, 0);
    }

    private static boolean isWaterBase(KegRecipes.FermentRecipe r) {
        return "minecraft:water".equals(r.baseFluidId());
    }

    /** 水瓶样本（isSameItemSameComponents 口径；与炼金装出的水瓶完全一致）。 */
    public static ItemStack waterBottleTemplate() {
        return net.minecraft.world.item.alchemy.PotionContents.createItemStack(
                Items.POTION, net.minecraft.world.item.alchemy.Potions.WATER);
    }

    private static boolean isWaterBottle(ItemStack st) {
        return st.is(Items.POTION) && st.getOrDefault(
                net.minecraft.core.component.DataComponents.POTION_CONTENTS,
                net.minecraft.world.item.alchemy.PotionContents.EMPTY)
                .is(net.minecraft.world.item.alchemy.Potions.WATER);
    }

    /**
     * 基液倒完的余料容器（纯函数，GameTest 锁定）。
     *
     * <p>默认口径=<b>合成余料</b>（水桶/奶桶→空桶、蜂蜜瓶→玻璃瓶、酒瓶→
     * 酒杯——BAC 全系酒 craftRemainder=tankard）。水瓶是唯一例外：原版
     * 玻璃瓶<b>没有</b>合成余料，旧实现照抄 {@code getCraftingRemainingItem()}
     * 拿到空就把槽清空——瓶子整个被吞（2026-11-19 实测：投 4 水瓶做一批
     * 啤酒，瓶子全灭，vodka 永远缺 3 瓶，"排产停手"）。装水路径是我们
     * 自建的（水源站空瓶→水瓶），倒完补回空瓶才闭环。物理上也好理解：
     * 把瓶子里的水倒进桶，瓶子还在手里。
     */
    public static ItemStack pourRemainder(ItemStack src) {
        ItemStack rem = src.getCraftingRemainingItem();
        if ((rem == null || rem.isEmpty()) && isWaterBottle(src)) {
            rem = new ItemStack(Items.GLASS_BOTTLE);
        }
        return rem == null ? ItemStack.EMPTY : rem;
    }

    /** 最近的有效水源站（复用 {@code town.workshop().waterSources} 登记池，
     *  炼金 #34 同口径：含水炼药锅/水源方块/含水方块，登记表实时校验）。 */
    private BlockPos waterStationAt(ServerLevel level) {
        Town town = boundTown();
        if (town == null) {
            return null;
        }
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (long p : town.workshop().waterSources) {
            BlockPos pos = BlockPos.of(p);
            if (!isValidWaterStation(level, pos)) {
                continue;
            }
            if (NavOps.blocked(this.resident, pos)) {
                continue; // 寻路黑名单：放弃过的水源站 10 秒内不选
            }
            double d = this.resident.blockPosition().distSqr(pos);
            if (d < bestD) {
                bestD = d;
                best = pos;
            }
        }
        return best;
    }

    /** 水源站有效性：含水炼药锅 / 水源方块 / 含水方块（炼金同款一行一个口径）。 */
    private static boolean isValidWaterStation(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        net.minecraft.world.level.block.state.BlockState state =
                level.getBlockState(pos);
        if (state.is(net.minecraft.world.level.block.Blocks.WATER_CAULDRON)) {
            return true;
        }
        if (state.is(net.minecraft.world.level.block.Blocks.WATER)
                && state.getFluidState().isSource()) {
            return true;
        }
        return state.hasProperty(net.minecraft.world.level.block.state.properties
                        .BlockStateProperties.WATERLOGGED)
                && state.getValue(net.minecraft.world.level.block.state.properties
                        .BlockStateProperties.WATERLOGGED);
    }

    /**
     * GO_WATER：走去水源站。2026-11-19 重做：不再寻路到水源方块/含水树叶
     * <b>本身</b>（被箱子/酒桶排围挡时路径退化、把人推进方块缝楔死，任务
     * 永远完不成），改寻"站旁可站立落脚点"——落脚点被拉黑自动换下一个，
     * 全部失败才整站拉黑换下一站（站旁装水是模拟记账，不需要点击面）。
     */
    private void tickGoWater(ServerLevel level) {
        if (this.waterTarget == null) {
            this.waterTarget = waterStationAt(level);
            if (this.waterTarget == null) {
                returnCargoToCellar(level); // 站被拆了/全部到不了：空瓶回窖，下轮重算
                backToScan();
                return;
            }
            this.waterSpot = null;
        }
        if (this.waterSpot == null) {
            // 半径 3/范围 4.0（2026-11-19 实测：半径 2 时唯一可站格在围挡
            // 走廊深处，路径必穿桶排缝=必楔死；装水是模拟记账不需要贴身，
            // 放宽后站这侧的空位也能选中，不必硬穿走廊）
            this.waterSpot = NavOps.spotNear(level, this.resident, this.waterTarget,
                    3, 1, 4.0D);
            if (this.waterSpot == null) {
                // 站周围没有可站且不被拉黑的格子：整站拉黑（递增退避），换下一站
                NavOps.blacklist(this.resident, this.waterTarget);
                this.waterTarget = null;
                returnCargoToCellar(level);
                backToScan();
                return;
            }
        }
        switch (NavOps.approach(this.resident, this.waterSpot,
                1.5D, Config.STROLL_SPEED.get())) {
            case STUCK -> {
                // 这个落脚点到不了（已被 NavOps 拉黑）：换下一个落脚点；
                // 站和货列都不动——别的落脚点也许能到（站的另一面）
                this.waterSpot = null;
                return;
            }
            case MOVING -> {
                return;
            }
            case ARRIVED -> {
            }
        }
        reclaimCarried(); // 端着的空瓶收回事列头——装水按货列逐瓶转化
        tickWaterFill(level);
    }

    /**
     * 到站装水：空瓶→水瓶逐瓶转化（炼金 #34 同款）。水源/含水方块无限
     * （不消耗方块）；炼药锅按原版口径 <b>1 格水 = 1 瓶</b>（level 递减，
     * 取完变空锅）——材料账与玩家手动右键完全一致。
     */
    private void tickWaterFill(ServerLevel level) {
        // 货物列里数玻璃瓶（装水前 FETCH 只拿了空瓶）
        int empties = 0;
        for (ItemStack st : cargo()) {
            if (st.is(Items.GLASS_BOTTLE)) {
                empties += st.getCount();
            }
        }
        if (empties <= 0) {
            this.waterTarget = null;
            this.waterSpot = null;
            this.phase = Phase.GO_KEG;
            navigateTo(this.kegPos);
            return;
        }
        net.minecraft.world.level.block.state.BlockState state =
                level.getBlockState(this.waterTarget);
        boolean cauldron = state.is(net.minecraft.world.level.block.Blocks.WATER_CAULDRON);
        int level3 = cauldron ? state.getValue(net.minecraft.world.level.block
                .LayeredCauldronBlock.LEVEL) : 0;
        int canTake = cauldron ? Math.min(empties, level3) : empties;
        if (canTake <= 0) {
            // 锅被舀干：重找下一个水源站（下 tick tickGoWater 重定位）
            this.waterTarget = null;
            this.waterSpot = null;
            this.retryCooldown = 20;
            return;
        }
        int filled = 0;
        for (int i = cargo().size() - 1; i >= 0 && filled < canTake; i--) {
            ItemStack st = cargo().get(i);
            if (!st.is(Items.GLASS_BOTTLE)) {
                continue;
            }
            int take = Math.min(st.getCount(), canTake - filled);
            st.shrink(take);
            if (st.isEmpty()) {
                cargo().remove(i);
            }
            // 水瓶<b>逐瓶独立成栈</b>（POTION maxStack=1；POUR 按份数逐个读
            // 货列，合堆会让 get(pourIndex) 错位读到配料）
            for (int b = 0; b < take; b++) {
                cargo().add(i, waterBottleTemplate());
            }
            filled += take;
        }
        if (cauldron) {
            int remain = level3 - filled;
            level.setBlock(this.waterTarget, remain <= 0
                    ? net.minecraft.world.level.block.Blocks.CAULDRON.defaultBlockState()
                    : state.setValue(net.minecraft.world.level.block
                            .LayeredCauldronBlock.LEVEL, remain),
                    net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
        LOGGER.info("[BmfTown][酒保] {} 水源站装水 ×{}（{}）",
                this.resident.getName().getString(), filled,
                cauldron ? "炼药锅扣格" : "水源/含水方块");
        this.waterTarget = null;
        this.waterSpot = null;
        this.phase = Phase.GO_KEG;
        navigateTo(this.kegPos);
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
        // 奶/蜜维护过菜单门（2026-11-18 实测）：蛋酒不在菜单就不该喊缺奶——
        // 基液需求跟着闭包走，菜单没这道菜，原料就不维护。
        java.util.Set<Item> wanted = menuClosure(level, tavernMenu());
        if (closureUsesSource(level, wanted, Items.MILK_BUCKET)
                && stock.milk() < target) {
            missing.add(JobKit.label(new ItemStack(Items.MILK_BUCKET), target - stock.milk()));
        }
        if (closureUsesSource(level, wanted, Items.HONEY_BOTTLE)
                && stock.honey() < target) {
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
