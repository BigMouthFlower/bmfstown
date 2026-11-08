package com.bmf.bmfstown.entity;

import com.bmf.bmfstown.alchemy.AlchemyCatalog;
import com.bmf.bmfstown.alchemy.PurityRules;
import com.bmf.bmfstown.config.Config;
import com.bmf.bmfstown.entity.domain.JobState;
import com.bmf.bmfstown.entity.profile.Perks;
import com.bmf.bmfstown.entity.profile.ProfessionType;
import com.bmf.bmfstown.entity.profile.SkillType;
import com.bmf.bmfstown.town.Town;
import com.bmf.bmfstown.town.TownSaveData;
import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 炼金术士的工作 Goal（v0.12）：在城镇登记的**酿造台**上真酿造药水。
 *
 * <h2>真酿造（设计终稿 §2）</h2>
 * 不做模拟计时——NPC 把水瓶/主料/燃料**真放进酿造台 BlockEntity**，
 * 原版 tick 自己跑（真燃料、真 400t、真视觉），NPC 在旁等待。完成判定
 * = 料槽（slot 3）变空。批次链：粗制（地狱疣）→ 主料 →（可选）红石/荧石修饰。
 *
 * <h2>4 瓶/套（决策 #27）</h2>
 * 一套原料与玩家完全相同（水瓶×3+地狱疣+主料[+修饰]），取出 3 瓶打纯度后
 * **手艺补第 4 瓶**（零附加成本，同批同规则各自掷纯度）。
 *
 * <h2>盖章→认章</h2>
 * 取瓶时 {@link PurityRules#stamp}（纯度组件+浊名+掺灰+时长×P 物化）；
 * 喝下/命中时的杂质现抽在 {@code ImpurityEvents} 认章。
 *
 * <h2>岗位认领</h2>
 * 与工人同款：酿造台池（{@code Town.WorkshopRegister.brewStations}）自动认领、
 * 一台一炼、30 秒心跳互斥、拆了自动重找。容器统一池共用（取料+入库就近）。
 *
 * <h2>订单</h2>
 * {@code JobState.orderItem}，键 {@code alch|<potion>|<none|long|strong>}，
 * 目录由 {@link AlchemyCatalog} 从原版 PotionBrewing 反查（零手写）。
 */
public class AlchemistGoal extends Goal {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final double OPEN_RANGE = 3.0D;
    private static final int IDLE_RETRY_TICKS = 100;
    private static final long CLAIM_STALE_MS = 30_000L;
    /** 临时打断（面板/被查看）期间认领续期：心跳停摆，靠 stop() 里预续的时间窗顶住，
     *  防止长会话浏览面板时台被 30 秒过期规则切给别的炼金术士。 */
    private static final long INTERRUPT_GRACE_MS = 240_000L;
    /** 料槽没起酿的宽限（原版 serverTick 下个 tick 就该起酿；40t 仍没起 = 这步酿不动）。 */
    private static final int BREW_NOT_STARTED_TICKS = 40;
    /** 龙息 NPC 专属合成配比（决策 #25 立项方向实装）：1紫颂果+1烈焰粉+1玻璃瓶 → N 龙息。 */
    private static final int DRAGON_BREATH_PER_CRAFT = 2;
    /** 连续"收走重装"失败次数上限：超过视为这台整体不对，整台回收重凑。 */
    private static final int STALL_RETRIES_MAX = 3;

    /** 酿造台占用记录（服务端内存级；重启清空重认领）。持 Resident 引用供节奏 mixin 反查。 */
    private record Claim(Resident resident, long at) {

        UUID holder() {
            return this.resident.getUUID();
        }
    }

    private static final Map<Long, Claim> BREW_CLAIMS = new HashMap<>();

    /** 节奏 mixin 查询：该酿造台当前被哪位炼金术士认领（无 = null）。 */
    @Nullable
    public static Resident claimedResidentAt(BlockPos pos) {
        Claim claim = BREW_CLAIMS.get(pos.asLong());
        return claim == null ? null : claim.resident();
    }

    private enum Phase {
        GO_POST, FETCH_GO, FETCH_TAKE, WATER_GO, WATER_FILL, LOAD, BREW, TAKE,
        STORE_GO, STORE_PUT
    }

    private final Resident resident;

    // ---- 认领上下文 ----
    private UUID townId;
    private BlockPos standPos;
    /** 水源站补水（决策 #34）：目标站位（水源方块/含水方块/含水炼药锅）。 */
    @Nullable
    private BlockPos waterTarget;
    /** 缺水瓶时先去容器取空瓶（取完转 WATER_GO）。 */
    private boolean fetchForWater;

    // ---- 订单 ----
    private AlchemyCatalog.Recipe recipe;
    private String orderMod = "none";
    /** 当前生效的订单键（换单检测用：与 JobState 不一致即触发重解析+回台对账）。 */
    private String currentOrderKey = "";
    private Holder<net.minecraft.world.item.alchemy.Potion> finalPotion;
    /** 本批酿造步骤（粗制地狱疣 → [底药中间步] → 主料 → 修饰？）。 */
    private List<Item> steps = List.of();
    /** 秘传底药（决策 #33）：底药≠粗制时非空（悬浮=缓降）——瓶内要先酿到这一档。 */
    @Nullable
    private Holder<net.minecraft.world.item.alchemy.Potion> baseStepPotion;
    /** 底药链中间步的投料（底药自身的主料，如缓降=幻翼膜）；无中间步 = null。 */
    @Nullable
    private Item baseStepItem;
    /** 本批选中的等价酿造路径（决策 #37 柔性链）：按箱里现有什么料选，无中间步 = null。 */
    @Nullable
    private com.bmf.bmfstown.alchemy.AlchemyCatalog.Chain chosenChain;
    /** 下一个要投进料槽的步骤下标。 */
    private int stepIndex;
    /** 缺某一步的料时的定点补料需求（非空 = 只补这一件，台上的瓶不动）。 */
    @Nullable
    private Need pendingNeed;
    private int brewStallTicks;
    /** 连续"收走重装"失败计数（起酿成功即清零）。 */
    private int stallRetries;

    // ---- 运行态 ----
    private Phase phase = Phase.GO_POST;
    private BlockPos fetchTarget;
    private BlockPos storeTarget;
    private int lidTimer;
    private int retryCooldown;
    private long nextScanAt;
    private boolean running;

    public AlchemistGoal(Resident resident) {
        this.resident = resident;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /** 面板状态显示用：Goal 是否在执行。 */
    public boolean isRunningGoal() {
        return this.running;
    }

    /** 面板状态显示用：是否已认领酿造台。 */
    public boolean hasClaim() {
        return this.running && this.standPos != null;
    }

    // ==========================================================
    //  Goal 生命周期
    // ==========================================================

    @Override
    public boolean canUse() {
        if (this.resident.getProfession() != ProfessionType.ALCHEMIST
                || this.resident.isSleeping() || this.resident.isForaging()) {
            return false;
        }
        ServerLevel level = serverLevel();
        if (level == null || !inWorkHours(level)
                || !com.bmf.bmfstown.calendar.TownCalendar.get(level).isWorkday(level)) {
            return false;
        }
        JobState job = this.resident.getJobState();
        if (!job.workerEnabled() || !job.orderItem().startsWith("alch|")) {
            return false;
        }
        if (this.resident.boundTown() == null
                || this.resident.boundTown().workshop().brewStations.isEmpty()) {
            this.nextScanAt = this.resident.tickCount + 60;
            return false;
        }
        if (this.resident.tickCount < this.nextScanAt) {
            return false;
        }
        this.recipe = AlchemyCatalog.resolve(level, job.orderItem());
        if (this.recipe == null) {
            this.nextScanAt = this.resident.tickCount + 200;
            return false;
        }
        this.currentOrderKey = job.orderItem();
        this.orderMod = job.orderItem().split("\\|")[2];
        this.finalPotion = this.recipe.finalPotion(this.orderMod);
        if (this.finalPotion == null) {
            this.nextScanAt = this.resident.tickCount + 200;
            return false;
        }
        // 秘传门禁（决策 #25）：配方书未发现 → 不酿
        String secretId = com.bmf.bmfstown.alchemy.ModPotions.secretIdOf(this.finalPotion);
        if (secretId != null && !com.bmf.bmfstown.alchemy.AlchemyGate.isOpen(secretId)) {
            this.nextScanAt = this.resident.tickCount + 200;
            return false;
        }
        // 批次链：地狱疣 → 主料 → 药水修饰（红石/荧石）→ 容器修饰（火药→龙息）
        rebuildSteps(level);
        // 续作优先：已认领的台还活着且认领权在自己手里 → 原台接着用；
        // 只有没台/台被拆/认领已被别人接管才重新认领（防"换台双份原料"）
        if (this.standPos != null && isBrewingStand(level, this.standPos)) {
            Claim claim = BREW_CLAIMS.get(this.standPos.asLong());
            boolean stillOurs = claim != null && claim.holder().equals(this.resident.getUUID());
            if (!stillOurs && !claimStand(level)) {
                this.nextScanAt = this.resident.tickCount + 60;
                return false;
            }
            if (stillOurs) {
                BREW_CLAIMS.put(this.standPos.asLong(),
                        new Claim(this.resident, System.currentTimeMillis()));
            }
        } else if (!claimStand(level)) {
            this.nextScanAt = this.resident.tickCount + 60;
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        ServerLevel level = serverLevel();
        if (level == null || this.resident.isSleeping() || this.resident.isForaging()
                || !inWorkHours(level)
                || this.resident.getProfession() != ProfessionType.ALCHEMIST) {
            return false;
        }
        JobState job = this.resident.getJobState();
        return job.workerEnabled() && job.orderItem().startsWith("alch|")
                && townAlive(level)
                && (this.standPos == null || isBrewingStand(level, this.standPos));
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.running = true;
        this.retryCooldown = 0;
        this.brewStallTicks = 0;
        this.stallRetries = 0;
        this.phase = Phase.GO_POST;
        // 步进状态（stepIndex/steps/recipe）不清零：回台后 reconcileStand
        // 按台上实物裁决进度，被打断/隔夜/换单统一走这条恢复路
        navigateToStand();
        LOGGER.debug("[BmfTown][炼金] {} 开工（订单={}，台={}）",
                this.resident.getName().getString(),
                this.resident.getJobState().orderItem(),
                this.standPos == null ? "?" : this.standPos.toShortString());
    }

    @Override
    public void stop() {
        this.running = false;
        this.resident.setWorkerVisual(false);
        ServerLevel level = serverLevel();
        JobState job = this.resident.getJobState();
        boolean resumable = level != null && inWorkHours(level)
                && job.workerEnabled() && job.orderItem().startsWith("alch|")
                && townAlive(level);
        if (resumable && this.standPos != null && isBrewingStand(level, this.standPos)) {
            // 临时打断（面板打开/FaceInteractingPlayerGoal 抢旗标）：认领保留并续期，
            // 台上的瓶/料/燃料**一概不动**——真方块清了重装就是"玩家药水"+双份原料
            // （2026-11-06 实锤的卡死根源）。回来后 reconcileStand 按实物续作。
            BREW_CLAIMS.put(this.standPos.asLong(), new Claim(this.resident,
                    System.currentTimeMillis() + INTERRUPT_GRACE_MS));
        } else {
            // 下班/停工/城镇没了：释放认领；台上东西原地留着——
            // 原版会继续酿，明天回来对账接得上（酿完的成品照常盖章入库）
            releaseClaim();
            this.stepIndex = 0;
        }
        this.fetchTarget = null;
        this.storeTarget = null;
        this.waterTarget = null;
        this.fetchForWater = false;
        this.brewStallTicks = 0;
        this.stallRetries = 0;
        this.phase = Phase.GO_POST;
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
        if (this.standPos != null) {
            BREW_CLAIMS.put(this.standPos.asLong(),
                    new Claim(this.resident, System.currentTimeMillis()));
        }
        // 换单立即生效（2026-11-07 修：之前配方/步骤只在开工时解析，运行中换单
        // NPC 会按旧订单一直做下去）：重解析 + 回台对账——台上产物与新链兼容
        // 就复用（普通→喷溅：基础药直接接着转火药），不兼容整台回收进箱子
        String orderKey = this.resident.getJobState().orderItem();
        if (!orderKey.equals(this.currentOrderKey)) {
            this.currentOrderKey = orderKey;
            onOrderChanged(level);
        }
        switch (this.phase) {
            case GO_POST -> tickGoPost(level);
            case FETCH_GO -> tickFetchGo(level);
            case FETCH_TAKE -> tickFetchTake(level);
            case WATER_GO -> tickWaterGo(level);
            case WATER_FILL -> tickWaterFill(level);
            case LOAD -> tickLoad(level);
            case BREW -> tickBrew(level);
            case TAKE -> tickTake(level);
            case STORE_GO -> tickStoreGo(level);
            case STORE_PUT -> tickStorePut(level);
        }
        this.resident.setWorkerVisual(this.phase != Phase.GO_POST);
    }

    // ==========================================================
    //  阶段
    // ==========================================================

    private void tickGoPost(ServerLevel level) {
        if (this.standPos == null) {
            this.retryCooldown = IDLE_RETRY_TICKS;
            return;
        }        if (this.resident.blockPosition().closerThan(this.standPos, OPEN_RANGE)) {
            this.resident.getNavigation().stop();
            BrewingStandBlockEntity stand = standAt(level);
            if (stand == null) {
                this.retryCooldown = IDLE_RETRY_TICKS;
                return;
            }
            // 回台第一件事：对账（不管是不是被打断过）——台上实物是唯一权威，
            // 隔夜酿完的、被打断半路的、被玩家动过的，都在这里统一裁决
            reconcileStand(stand);
            return;
        }
        if (this.resident.getNavigation().isDone()) {
            navigateToStand();
            this.retryCooldown = 20;
        }
    }
    // ==========================================================
    //  回台对账（打断/隔夜/换单恢复的统一入口）
    // ==========================================================

    /**
     * 按台上实物校正进度与阶段（每条路径都负责落定 stepIndex）：
     * <ol>
     *   <li>瓶位全是"空或水瓶"→ 新批次（进度归零）；料槽已有地狱疣就先等它酿完；</li>
     *   <li>三瓶齐全且符合链条最深处 → 续作：stepIndex = 深度+1，投下一步或收瓶；
     *       料槽已被塞了别的东西 → 收回背包再装；</li>
     *   <li>以上全不匹配（玩家抽瓶/换料/塞了不相干的东西）→ 整台回收重凑。</li>
     * </ol>
     */
    private void reconcileStand(BrewingStandBlockEntity stand) {
        ItemStack ing = stand.getItem(3);
        boolean waterPhase = true;
        for (int slot = 0; slot < 3; slot++) {
            ItemStack b = stand.getItem(slot);
            if (!b.isEmpty() && !isWaterBottle(b)) {
                waterPhase = false;
                break;
            }
        }
        // ① 新批次
        if (waterPhase) {
            this.stepIndex = 0;
            LOGGER.info("[BmfTown][炼金] {} 对账：新批次（订单={}）",
                    this.resident.getName().getString(), this.currentOrderKey);
            if (ing.isEmpty()) {
                this.phase = planNeeds().isEmpty() ? Phase.LOAD : Phase.FETCH_GO;
            } else if (ing.is(this.steps.get(0))) {
                this.brewStallTicks = 0;
                this.phase = Phase.BREW; // 第一剂已投（可能是打断前投的），等原版酿完
            } else {
                reclaimSlot3(stand, ing);
                this.phase = Phase.LOAD;
            }
            return;
        }
        // ② 深度续作：找最大 k，使"完成 k 步后的预期（瓶物品+药水）"与台上三瓶一致
        int best = -1;
        for (int k = 0; k < this.steps.size(); k++) {
            Holder<Potion> p = expectedAfterStep(k);
            if (p != null && bottlesMatch(stand, containerAfterSteps(this.steps, k), p)) {
                best = k;
            }
        }
        if (best >= 0) {
            this.stepIndex = best + 1;
            LOGGER.info("[BmfTown][炼金] {} 对账：复用台进（已完成 {} 步），续作",
                    this.resident.getName().getString(), best + 1);
            if (this.stepIndex >= this.steps.size()) {
                this.phase = Phase.TAKE;
                return;
            }
            if (!ing.isEmpty()) {
                if (ing.is(this.steps.get(this.stepIndex))) {
                    this.brewStallTicks = 0;
                    this.phase = Phase.BREW; // 下一剂已投（打断前），等原版酿完
                    return;
                }
                reclaimSlot3(stand, ing);
            }
            this.phase = Phase.LOAD;
            return;
        }
        // ③ 全不匹配：整台回收重凑（回收的药水也照常盖章，绝不产"无章药水"）
        LOGGER.info("[BmfTown][炼金] {} 对账不符，整台回收重凑",
                this.resident.getName().getString());
        salvageStand(stand);
        this.stepIndex = 0;
        this.retryCooldown = IDLE_RETRY_TICKS;
        this.phase = Phase.FETCH_GO;
    }

    /** 料槽被塞了不相干的东西：收一台民的背包（满了掉脚边），继续装自己的。 */
    private void reclaimSlot3(BrewingStandBlockEntity stand, ItemStack ing) {
        ItemStack back = stand.removeItem(3, ing.getCount());
        if (!back.isEmpty()) {
            ItemStack leftover = this.resident.getInventory().addItem(back);
            if (!leftover.isEmpty()) {
                this.resident.spawnAtLocation(leftover);
            }
        }
    }

    /**
     * 运行中换单：重解析订单并回台对账（认领保留）。
     * 台上产物与新链兼容 → 按 {@code reconcileStand} 的深度匹配**复用**；
     * 不兼容 → 整台回收（进箱子，绝不蒸发）再按新链重凑。
     * 解析失败/秘传未解锁 → 视同无单待机（认领释放，等玩家重下）。
     */
    private void onOrderChanged(ServerLevel level) {
        this.pendingNeed = null;
        var recipe = AlchemyCatalog.resolve(level, this.currentOrderKey);
        Holder<Potion> finalPotion = recipe == null
                ? null : recipe.finalPotion(this.currentOrderKey.split("\\|")[2]);
        String secretId = finalPotion == null ? null
                : com.bmf.bmfstown.alchemy.ModPotions.secretIdOf(finalPotion);
        if (recipe == null || (secretId != null
                && !com.bmf.bmfstown.alchemy.AlchemyGate.isOpen(secretId))) {
            LOGGER.info("[BmfTown][炼金] {} 订单变更：{} 解析失败（野键/秘传未解锁），待机",
                    this.resident.getName().getString(), this.currentOrderKey);
            releaseClaim();
            this.standPos = null;
            this.phase = Phase.GO_POST;
            this.retryCooldown = IDLE_RETRY_TICKS;
            return;
        }
        this.recipe = recipe;
        this.orderMod = this.currentOrderKey.split("\\|")[2];
        this.finalPotion = this.recipe.finalPotion(this.orderMod);
        rebuildSteps(level);
        LOGGER.info("[BmfTown][炼金] {} 订单变更：{}，回台对账复用/回收",
                this.resident.getName().getString(), this.currentOrderKey);
        this.phase = Phase.GO_POST;
        navigateToStand();
    }

    /** 一条缺料需求：匹配器 + 还差几件（对库存求过差额）+ 展示名（播报点名用）。 */
    private record Need(java.util.function.Predicate<ItemStack> test, int count,
                        net.minecraft.network.chat.Component label) {
    }

    /** 物品展示名（translatable 保持不解析，渲染时按玩家语言取词）。 */
    private static net.minecraft.network.chat.Component itemName(Item item) {
        return net.minecraft.network.chat.Component.translatable(item.getDescriptionId());
    }

    /** 本周期缺料清单（对库存求差额）：水瓶×3+地狱疣+[底药中间步]+主料+（修饰）+（烈焰粉缺则 1）。 */
    private List<Need> planNeeds() {
        var inv = this.resident.getInventory();
        List<Need> needs = new ArrayList<>();
        addNeed(needs, inv, waterBottleTest(), 3,
                net.minecraft.network.chat.Component.translatable("item.minecraft.potion.effect.water"));
        if (this.recipe.startPotion() == null) {
            // 水瓶直酿族（虚弱）不进地狱疣步，其余配方恒需 1 根
            addNeed(needs, inv, stack -> stack.is(Items.NETHER_WART), 1, itemName(Items.NETHER_WART));
        }
        if (this.baseStepItem != null) {
            addNeed(needs, inv, stack -> stack.is(this.baseStepItem), 1,
                    itemName(this.baseStepItem)); // 底药链（决策 #33）
        }
        addNeed(needs, inv, stack -> stack.is(ingredientItem()), 1,
                itemName(ingredientItem()));
        for (Item mod : modifierItems(this.orderMod)) {
            addNeed(needs, inv, stack -> stack.is(mod), 1, itemName(mod));
        }
        // 冷萃：燃料永不消耗 → 不再索要烈焰粉（2026-11-07 用户实测抓出：
        // 冷萃镇民会因为箱里没烈焰粉而永远"凑不齐料"卡死）
        if (!this.resident.hasPerk(com.bmf.bmfstown.entity.profile.Perks.NODE_AL_ADEPT, "cold_brew")) {
            addNeed(needs, inv, stack -> stack.is(Items.BLAZE_POWDER), 1,
                    itemName(Items.BLAZE_POWDER));
        }
        return needs;
    }

    /**
     * 龙息 NPC 专属合成（决策 #25 立项，2026-11-07 实装；配比 1紫颂果+1烈焰粉+1玻璃瓶
     * → 2龙息，写死常量可调）：滞留订单缺龙息且容器里也找不到时，炼金术士用
     * 紫颂果+烈焰粉+玻璃瓶就地合成——**玩家无此配方**（"天赋造原料"叙事）。
     *
     * @return true = 已合成（龙息缺口解除，继续取料流程）
     */
    private boolean craftDragonBreath(ServerLevel level, List<Need> needs) {
        boolean needDragon = needs.stream().anyMatch(n ->
                n.test().test(new ItemStack(Items.DRAGON_BREATH)));
        if (!needDragon) {
            return false;
        }
        // 容器+背包里找一套合成材料
        List<net.minecraft.world.Container> sources = new ArrayList<>();
        for (BlockPos pos : validContainers(level)) {
            var be = level.getBlockEntity(pos);
            if (be instanceof net.minecraft.world.Container c) {
                sources.add(c);
            }
        }
        sources.add(this.resident.getInventory());
        int haveChorus = 0, haveBlaze = 0, haveBottle = 0;
        for (var c : sources) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack st = c.getItem(i);
                if (st.is(Items.CHORUS_FRUIT)) {
                    haveChorus += st.getCount();
                } else if (st.is(Items.BLAZE_POWDER)) {
                    haveBlaze += st.getCount();
                } else if (st.is(Items.GLASS_BOTTLE)) {
                    haveBottle += st.getCount();
                }
            }
        }
        if (haveChorus < 1 || haveBlaze < 1 || haveBottle < 1) {
            return false; // 材料不够：老实等补料
        }
        // 消耗一套材料（先容器后背包）
        consumeOne(sources, Items.CHORUS_FRUIT);
        consumeOne(sources, Items.BLAZE_POWDER);
        consumeOne(sources, Items.GLASS_BOTTLE);
        ItemStack dragon = new ItemStack(Items.DRAGON_BREATH, DRAGON_BREATH_PER_CRAFT);
        ItemStack leftover = this.resident.getInventory().addItem(dragon);
        if (!leftover.isEmpty()) {
            this.resident.spawnAtLocation(leftover);
        }
        LOGGER.info("[BmfTown][炼金] {} 合成龙息 ×{}（紫颂果+烈焰粉+玻璃瓶，NPC 专属配方）",
                this.resident.getName().getString(), DRAGON_BREATH_PER_CRAFT);
        return true;
    }

    /** 从容器列表里消耗一件指定物品（先扫到的先扣；扣不到静默跳过）。 */
    private static void consumeOne(List<net.minecraft.world.Container> sources,
                                   net.minecraft.world.item.Item item) {
        for (var c : sources) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack st = c.getItem(i);
                if (st.is(item)) {
                    c.removeItem(i, 1);
                    c.setChanged();
                    return;
                }
            }
        }
    }

    /** 等料节流字段（30 秒一次播报）。 */
    private long lastShortageNotifyMs;

    /**
     * 等料播报：16 格内玩家 actionbar 提示镇民缺什么（30 秒节流）。
     * 龙息缺料且合成材料也不足时，把**配方**一并告诉玩家（"天赋造原料"，
     * 玩家无从得知配比——2026-11-07 用户实测卡在这里）。
     */
    private void notifyShortage(ServerLevel level, List<Need> needs) {
        long now = System.currentTimeMillis();
        if (now - this.lastShortageNotifyMs < 30_000L) {
            return;
        }
        this.lastShortageNotifyMs = now;
        boolean dragonMissing = needs.stream()
                .anyMatch(n -> n.test().test(new ItemStack(Items.DRAGON_BREATH)));
        // 点名缺什么（2026-11-07 用户抓出信息断层："等补货"不说缺什么，没法对症下药）
        StringBuilder missing = new StringBuilder();
        for (Need n : needs) {
            if (missing.length() > 0) {
                missing.append("、");
            }
            missing.append(n.label().getString()).append('×').append(n.count());
        }
        String key = "msg.bmfstown.alchemy.waiting_materials";
        if (dragonMissing) {
            key = "msg.bmfstown.alchemy.waiting_dragon_materials";
        }
        var msg = net.minecraft.network.chat.Component.translatable(key,
                this.resident.getName().getString(), missing.toString());
        for (var p : level.players()) {
            if (p instanceof net.minecraft.server.level.ServerPlayer sp
                    && sp.distanceToSqr(this.resident) < 256.0D) {
                sp.displayClientMessage(msg, true);
            }
        }
    }

    /** 缺口 = 需求 − 库存；缺口 ≤ 0 不记。 */
    private void addNeed(List<Need> needs, Container inv,
            java.util.function.Predicate<ItemStack> test, int want,
            net.minecraft.network.chat.Component label) {
        int have = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (test.test(inv.getItem(i))) {
                have += inv.getItem(i).getCount();
            }
        }
        int deficit = want - have;
        if (deficit > 0) {
            needs.add(new Need(test, deficit, label));
        }
    }

    /** 本周期缺什么料 → 去最近的登记容器拿（统一池）。定点补料模式只补缺的那件。 */
    private void tickFetchGo(ServerLevel level) {
        // 链路柔性切换（决策 #37）：当前链料背包/容器都找不到、第 0 步未投料
        // → 换一条有料的等价路径（伤害=剧毒/蜘蛛眼 ↔ 治疗/闪烁西瓜，玩家备哪样走哪样）
        if (this.baseStepItem != null && this.stepIndex == 0
                && countIn(this.resident.getInventory(), s -> s.is(this.baseStepItem)) == 0
                && !itemInAnyContainer(level, this.baseStepItem)) {
            var stand = standAt(level);
            if (stand == null || stand.getItem(3).isEmpty()) {
                rebuildSteps(level);
                // 定点缺口若指向旧链料（或链已变）就清掉，让下面的 planNeeds 重新求缺口
                if (this.pendingNeed != null && (this.baseStepItem == null
                        || !this.pendingNeed.test().test(new ItemStack(this.baseStepItem)))) {
                    this.pendingNeed = null;
                }
            }
        }
        List<Need> needs = this.pendingNeed != null
                ? List.of(this.pendingNeed) : planNeeds();
        if (needs.isEmpty()) {
            this.phase = Phase.LOAD;
            return;
        }
        // 水源站补水（决策 #34）：水瓶缺口 + 容器里没有水瓶 + 登记了有效水源站
        // → 拿空瓶去站上就地装水（模拟但材料账做实：炼药锅 1 格水=1 瓶，水源/含水无限）。
        // 有水瓶优先用水瓶——容器找得到水瓶时绝不走这条路（用户定稿）
        if (this.pendingNeed == null) {
            var inv = this.resident.getInventory();
            int waterHave = countIn(inv, waterBottleTest());
            if (waterHave < 3 && !waterInAnyContainer(level)
                    && nearestWaterStation(level) != null) {
                int deficit = 3 - waterHave;
                int empties = countIn(inv, stack -> stack.is(Items.GLASS_BOTTLE));
                if (empties < deficit) {
                    int emptiesInChests = 0;
                    for (var pos : validContainers(level)) {
                        var be = level.getBlockEntity(pos);
                        if (be instanceof net.minecraft.world.Container c) {
                            emptiesInChests += countIn(c,
                                    stack -> stack.is(Items.GLASS_BOTTLE));
                        }
                    }
                    if (empties + emptiesInChests < deficit) {
                        this.fetchForWater = false; // 空瓶也不够：老实等补料
                    } else {
                        this.fetchForWater = true;
                        this.pendingNeed = new Need(
                                stack -> stack.is(Items.GLASS_BOTTLE), deficit - empties,
                                itemName(Items.GLASS_BOTTLE));
                        needs = List.of(this.pendingNeed); // 本趟只取空瓶
                    }
                } else {
                    this.phase = Phase.WATER_GO; // 空瓶随身够用：直接去装水
                    return;
                }
            }
        }
        // 龙息合成（NPC 专属）：缺口里有龙息、容器找不到，但合成材料齐 → 就地合成
        if (this.pendingNeed == null) {
            boolean dragonStuck = needs.stream()
                    .anyMatch(n -> n.test().test(new ItemStack(Items.DRAGON_BREATH)));
            boolean dragonInAnyContainer = dragonStuck && validContainers(level).stream()
                    .flatMap(pos -> {
                        var be = level.getBlockEntity(pos);
                        return be instanceof net.minecraft.world.Container c
                                ? java.util.stream.IntStream.range(0, c.getContainerSize())
                                        .mapToObj(c::getItem) : java.util.stream.Stream.<ItemStack>empty();
                    })
                    .anyMatch(st -> st.is(Items.DRAGON_BREATH));
            if (dragonStuck && !dragonInAnyContainer
                    && craftDragonBreath(level, needs)) {
                needs = planNeeds(); // 重新求缺口（龙息已在背包）
            }
        }
        if (needs.isEmpty()) {
            this.phase = Phase.LOAD;
            return;
        }
        BlockPos target = nearestContainerFulfilling(level, needs);
        if (target == null) {
            this.retryCooldown = IDLE_RETRY_TICKS;
            var valid = validContainers(level);
            StringBuilder needDesc = new StringBuilder();
            for (Need n : needs) {
                needDesc.append(n.label().getString()).append('×')
                        .append(n.count()).append(';');
            }
            LOGGER.info("[BmfTown][炼金] {} 凑不齐料，待机等补料（登记容器={}，缺口={}）",
                    this.resident.getName().getString(), valid.size(), needDesc);
            // 等料可视化（2026-11-07 用户抓出信息断层）：附近玩家 30 秒节流播报缺什么
            notifyShortage(level, needs);
            return;
        }
        this.fetchTarget = target;
        navigateTo(this.fetchTarget);
        this.phase = Phase.FETCH_TAKE;
    }

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
            this.lidTimer = 10;
            return;
        }
        if (--this.lidTimer > 0) {
            return;
        }
        var chest = containerAt(level, this.fetchTarget);
        this.resident.containerFx(level, this.fetchTarget, false);
        if (chest == null) {
            this.fetchTarget = null;
            this.phase = Phase.FETCH_GO;
            return;
        }
        boolean wasPending = this.pendingNeed != null;
        List<Need> toTake = new ArrayList<>(planNeeds());
        if (this.pendingNeed != null) {
            toTake.add(this.pendingNeed); // 空瓶补水缺口（决策 #34）：planNeeds 不含空瓶，必须定点带上
        }
        for (Need need : toTake) {
            takeFromChest(chest, need);
        }
        this.pendingNeed = null;
        this.fetchTarget = null;
        // 定点补料：补到就直接回台继续（不做全量凑料核对——瓶还在台上等着）
        // 补的是空瓶（水源站补水，决策 #34）→ 转水源站装水
        this.phase = wasPending
                ? (this.fetchForWater ? Phase.WATER_GO : Phase.LOAD)
                : (planNeeds().isEmpty() ? Phase.LOAD : Phase.FETCH_GO);
        this.fetchForWater = false;
    }

    // ==========================================================
    //  水源站补水（决策 #34）：模拟但材料账做实
    // ==========================================================

    private void tickWaterGo(ServerLevel level) {
        if (this.waterTarget == null || !isValidWaterStation(level, this.waterTarget)) {
            this.waterTarget = nearestWaterStation(level);
            if (this.waterTarget == null) {
                // 水源站没了（被拆/被舀干）：回常规凑料（缺水瓶就走等料播报）
                this.retryCooldown = IDLE_RETRY_TICKS;
                this.phase = Phase.FETCH_GO;
                return;
            }
        }
        if (!this.resident.blockPosition().closerThan(this.waterTarget, OPEN_RANGE)) {
            if (this.resident.getNavigation().isDone()) {
                navigateTo(this.waterTarget);
                this.retryCooldown = 20;
            }
            return;
        }
        this.resident.getNavigation().stop();
        this.phase = Phase.WATER_FILL;
    }

    /**
     * 到站装水：空瓶→水瓶逐瓶转化。水源/含水方块无限（不消耗方块）；
     * 炼药锅按原版口径 **1 格水 = 1 瓶**（level 递减，1 级取完变空锅）——
     * 材料账与玩家手动右键完全一致，只是不走"对方块使用物品"的真实交互路径
     * （NPC 寻路到精确点击面成本高、收益为零，模拟的账实相符即可）。
     */
    private void tickWaterFill(ServerLevel level) {
        var inv = this.resident.getInventory();
        int deficit = 3 - countIn(inv, waterBottleTest());
        if (deficit <= 0) {
            this.waterTarget = null;
            this.phase = Phase.FETCH_GO;
            return;
        }
        BlockState state = level.getBlockState(this.waterTarget);
        boolean cauldron = state.is(Blocks.WATER_CAULDRON);
        int level3 = cauldron ? state.getValue(net.minecraft.world.level.block.LayeredCauldronBlock.LEVEL) : 0;
        int canTake = cauldron ? Math.min(deficit, level3) : deficit;
        if (canTake <= 0) {
            // 锅被舀干（非本次操作也可能）：重找下一个水源站
            this.waterTarget = null;
            this.retryCooldown = 20;
            this.phase = Phase.WATER_GO;
            return;
        }
        int filled = 0;
        for (int i = 0; i < inv.getContainerSize() && filled < canTake; i++) {
            ItemStack st = inv.getItem(i);
            if (!st.is(Items.GLASS_BOTTLE)) {
                continue;
            }
            int take = Math.min(st.getCount(), canTake - filled);
            inv.removeItem(i, take);
            if (inv.getItem(i).isEmpty()) {
                inv.setItem(i, ItemStack.EMPTY);
            }
            ItemStack water = PotionContents.createItemStack(Items.POTION, Potions.WATER);
            water.setCount(take);
            var leftover = inv.addItem(water);
            if (!leftover.isEmpty()) {
                this.resident.spawnAtLocation(leftover); // 背包满也不吞水瓶
            }
            filled += take;
        }
        if (cauldron) {
            int remain = level3 - filled;
            level.setBlock(this.waterTarget, remain <= 0
                    ? Blocks.CAULDRON.defaultBlockState()
                    : state.setValue(net.minecraft.world.level.block.LayeredCauldronBlock.LEVEL, remain),
                    net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
        LOGGER.info("[BmfTown][炼金] {} 水源站装水 ×{}（{}）",
                this.resident.getName().getString(), filled,
                cauldron ? "炼药锅扣格" : "水源/含水方块");
        this.waterTarget = null;
        this.phase = Phase.FETCH_GO;
    }

    /** 统计容器里匹配的物品总数。 */
    private static int countIn(Container inv, java.util.function.Predicate<ItemStack> test) {
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (test.test(inv.getItem(i))) {
                n += inv.getItem(i).getCount();
            }
        }
        return n;
    }

    /** 容器池里有没有水瓶（有 → 优先直接取，不走水源站）。 */
    private boolean waterInAnyContainer(ServerLevel level) {
        for (var pos : validContainers(level)) {
            var be = level.getBlockEntity(pos);
            if (be instanceof Container c && countIn(c, waterBottleTest()) > 0) {
                return true;
            }
        }
        return false;
    }

    /** 最近的有效水源站（水源方块/含水方块/含水炼药锅，登记表实时校验）。 */
    @Nullable
    private BlockPos nearestWaterStation(ServerLevel level) {
        var town = this.resident.boundTown();
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
            double d = this.resident.blockPosition().distSqr(pos);
            if (d < bestD) {
                bestD = d;
                best = pos;
            }
        }
        return best;
    }

    /** 水源站有效性：含水炼药锅 / 水源方块 / 含水方块（state 判定，一行一个口径）。 */
    private static boolean isValidWaterStation(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.WATER_CAULDRON)) {
            return true;
        }
        if (state.is(Blocks.WATER) && state.getFluidState().isSource()) {
            return true;
        }
        return state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
                && state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED);
    }

    /** 把库存里的料装进酿造台（瓶 0-2、地狱疣 3、烈焰粉 4 若空）。 */
    private void tickLoad(ServerLevel level) {
        BrewingStandBlockEntity stand = standAt(level);
        if (stand == null) {
            this.retryCooldown = IDLE_RETRY_TICKS;
            this.phase = Phase.GO_POST;
            return;
        }
        var inv = this.resident.getInventory();
        int loaded = 0;
        for (int slot = 0; slot < 3 && loaded < 3; slot++) {
            if (stand.getItem(slot).isEmpty()) {
                int from = findItem(inv, waterBottleTest());
                if (from < 0) {
                    break;
                }
                ItemStack bottle = inv.getItem(from).split(1);
                if (inv.getItem(from).isEmpty()) {
                    inv.setItem(from, ItemStack.EMPTY);
                }
                stand.setItem(slot, bottle);
                loaded++;
            }
        }
        if (stand.getItem(4).isEmpty()) {
            int from = findItem(inv, stack -> stack.is(Items.BLAZE_POWDER));
            if (from >= 0) {
                stand.setItem(4, inv.getItem(from).split(1));
                if (inv.getItem(from).isEmpty()) {
                    inv.setItem(from, ItemStack.EMPTY);
                }
            }
        }
        if (stand.getItem(3).isEmpty()) {
            if (this.stepIndex >= this.steps.size()) {
                this.phase = Phase.BREW;
                return;
            }
            int from = findItem(inv, stack -> stack.is(this.steps.get(this.stepIndex)));
            if (from < 0) {
                // 缺这一步的料：**绝不清台**——瓶留在台上，只去补缺的这件
                // （2026-11-07 修：之前整台回收重凑 = "收走再放回来"整链重来的根源）
                this.pendingNeed = new Need(
                        stack -> stack.is(this.steps.get(this.stepIndex)), 1,
                        itemName(this.steps.get(this.stepIndex)));
                LOGGER.info("[BmfTown][炼金] {} 缺步骤料 {}，只补这一件（台不动）",
                        this.resident.getName().getString(), this.steps.get(this.stepIndex));
                this.phase = Phase.FETCH_GO;
                return;
            }
            stand.setItem(3, inv.getItem(from).split(1));
            if (inv.getItem(from).isEmpty()) {
                inv.setItem(from, ItemStack.EMPTY);
            }
            this.brewStallTicks = 0;
        }
        this.phase = Phase.BREW;
    }

    /**
     * 等待/判定酿造。完成判定**不依赖料槽清空**——原版龙息带
     * craftRemainder=玻璃瓶（2026-11-07 实锤），每步龙息酿完料槽都会留个空瓶；
     * 旧逻辑"料槽非空=还在酿"被它骗了，成品在台上干等 40 秒兜底才被超时回收入库。
     * 现在用原版 brewTime 计时器（accessor）判定：
     * <ul>
     *   <li>brewTime &gt; 0 = 正在酿，安静等；</li>
     *   <li>brewTime == 0 且瓶槽已是预期产物 = 这步完成（含剩余物占料槽的情况），立即推进；</li>
     *   <li>brewTime == 0 且瓶槽不是预期产物 = 没起酿（没燃料/料不对/被玩家动过），
     *       宽限 {@value #BREW_NOT_STARTED_TICKS}t 收走料槽重装，连续
     *       {@value #STALL_RETRIES_MAX} 次失败整台回收。</li>
     * </ul>
     */
    private void tickBrew(ServerLevel level) {
        BrewingStandBlockEntity stand = standAt(level);
        if (stand == null) {
            this.retryCooldown = IDLE_RETRY_TICKS;
            this.phase = Phase.GO_POST;
            return;
        }
        this.resident.getNavigation().stop();
        int brewTime = ((com.bmf.bmfstown.mixin.BrewingStandBlockEntityAccessor)
                (Object) stand).bmfstown$getBrewTime();
        if (!stand.getItem(3).isEmpty() && brewTime > 0) {
            this.brewStallTicks = 0; // 正在酿，安静等
            return;
        }
        // 这一步已结束（料槽空 = 正常完成；料槽剩玻璃瓶等剩余物 = 完成但没清槽）。
        // 瓶槽必须已是预期产物，否则是"没起酿"而不是"完成了"
        Holder<Potion> expected = expectedAfterStep(this.stepIndex);
        if (expected == null || !bottlesMatch(stand,
                containerAfterSteps(this.steps, this.stepIndex), expected)) {
            // 没起酿：宽限 40t（覆盖 LOAD 放料 → serverTick 起酿的 1-2 tick 间隙），
            // 然后收走料槽重装；燃料不足顺手定点补
            if (++this.brewStallTicks > BREW_NOT_STARTED_TICKS) {
                this.brewStallTicks = 0;
                this.stallRetries++;
                ItemStack stuck = stand.getItem(3);
                LOGGER.info("[BmfTown][炼金] {} 步骤 {} 没起酿（料槽={}），第 {}/{} 次收走重装",
                        this.resident.getName().getString(), this.stepIndex,
                        stuck.isEmpty() ? "空" : stuck.getItem(),
                        this.stallRetries, STALL_RETRIES_MAX);
                if (!stuck.isEmpty()) {
                    reclaimSlot3(stand, stuck);
                }
                var acc = (com.bmf.bmfstown.mixin.BrewingStandBlockEntityAccessor)
                        (Object) stand;
                if (acc.bmfstown$getFuel() <= 0 && stand.getItem(4).isEmpty()
                        && findItem(this.resident.getInventory(),
                                stack -> stack.is(Items.BLAZE_POWDER)) < 0) {
                    this.pendingNeed = new Need(
                            stack -> stack.is(Items.BLAZE_POWDER), 1,
                            itemName(Items.BLAZE_POWDER));
                }
                if (this.stallRetries >= STALL_RETRIES_MAX) {
                    LOGGER.info("[BmfTown][炼金] {} 连续 {} 次没起酿，整台回收重凑",
                            this.resident.getName().getString(), this.stallRetries);
                    salvageStand(stand);
                    this.stepIndex = 0;
                    this.stallRetries = 0;
                    this.retryCooldown = IDLE_RETRY_TICKS;
                    this.phase = Phase.FETCH_GO;
                    return;
                }
                this.phase = Phase.LOAD;
            }
            return;
        }
        this.stallRetries = 0;
        this.stepIndex++;
        if (this.stepIndex < this.steps.size()) {
            this.phase = Phase.LOAD; // 投下一步（红石/荧石/火药/龙息）
            return;
        }
        this.phase = Phase.TAKE;
    }

    /** 收瓶：3 瓶逐瓶盖章 + 手艺补第 4 瓶（决策 #27：一套原料 4 瓶）。 */
    private void tickTake(ServerLevel level) {
        BrewingStandBlockEntity stand = standAt(level);
        if (stand == null) {
            this.retryCooldown = IDLE_RETRY_TICKS;
            this.phase = Phase.GO_POST;
            return;
        }
        Holder<Potion> expected = expectedAfterStep(this.steps.size() - 1);
        if (expected == null || !bottlesMatch(stand,
                containerAfterSteps(this.steps, this.steps.size() - 1), expected)) {
            salvageStand(stand);
            this.stepIndex = 0;
            this.retryCooldown = IDLE_RETRY_TICKS;
            this.phase = Phase.FETCH_GO;
            return;
        }
        var inv = this.resident.getInventory();
        int bottles = 0;
        for (int slot = 0; slot < 3; slot++) {
            ItemStack potion = stand.removeItem(slot, 1);
            if (potion.isEmpty()) {
                continue;
            }
            PurityRules.stamp(potion, rollPurity(), this.resident);
            inv.addItem(potion);
            bottles++;
        }
        // 料槽剩余物（原版龙息 craftRemainder=玻璃瓶——每步龙息酿完料槽会留一个
        // 空瓶，不收走就占着料槽，下一炉地狱疣永远放不进去，卡 40 秒兜底才解锁）
        if (!stand.getItem(3).isEmpty()) {
            reclaimSlot3(stand, stand.getItem(3));
        }
        // 手艺第 4 瓶：零附加成本，与前三瓶同规则（各自掷纯度）
        ItemStack extra = PotionContents.createItemStack(
                finalContainer(containerMod(this.orderMod)), this.finalPotion);
        PurityRules.stamp(extra, rollPurity(), this.resident);
        inv.addItem(extra);
        bottles++;
        // 经验按链长结算（决策 #33 修订 2026-11-07，用户拍板）：步是整批一次的劳动
        // （一次投料 3 瓶同时酿），一批只付一次——批经验 = 步数 × 每步经验，不乘瓶数
        int xp = Config.CRAFT_XP_PER_BREW_STEP.get() * this.steps.size();
        this.resident.addSkillXp(SkillType.CRAFTING, xp);
        PracticeObserver.observe(level, this.resident, ProfessionType.ALCHEMIST);
        LOGGER.info("[BmfTown][炼金] {} 整批 {} 瓶入库（订单={}，链长 {} 步，经验 +{}）",
                this.resident.getName().getString(), bottles, this.currentOrderKey,
                this.steps.size(), xp);
        this.storeTarget = null;
        this.phase = Phase.STORE_GO;
    }

    /** 入库：离酿造台最近的、装得下成品药水的登记容器。 */
    private void tickStoreGo(ServerLevel level) {
        if (this.storeTarget == null) {
            this.storeTarget = nearestContainerWithSpace(level, Items.POTION); // 药水物品家族共用判定
            if (this.storeTarget == null) {
                this.retryCooldown = IDLE_RETRY_TICKS;
                return; // 抱着成品等清箱
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
        this.lidTimer = 10;
        this.phase = Phase.STORE_PUT;
    }

    private void tickStorePut(ServerLevel level) {
        if (--this.lidTimer > 0) {
            return;
        }
        var output = containerAt(level, this.storeTarget);
        this.resident.containerFx(level, this.storeTarget, false);
        if (output == null) {
            this.storeTarget = null;
            this.phase = Phase.STORE_GO;
            return;
        }
        movePotionsTo(output);
        this.storeTarget = null;
        this.stepIndex = 0;
        this.phase = Phase.FETCH_GO; // 下一批
    }

    // ==========================================================
    //  工具
    // ==========================================================

    private java.util.function.Predicate<ItemStack> waterBottleTest() {
        return stack -> stack.is(Items.POTION)
                && stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY)
                        .is(Potions.WATER);
    }

    @Nullable
    /** 组合修饰键（"long+splash"）→ 按酿造链顺序的修饰物品。
     * <p>容器档必须按原版链路走两步（1.21.1 PotionBrewing 仅此两条容器混合）：
     * 普通瓶+火药→喷溅、喷溅+龙息→滞留——<b>没有</b>"普通瓶直接加龙息"的配方，
     * 直接投龙息料槽永远不清空、整批卡死（2026-11-06 实锤）。 */
    private static List<Item> modifierItems(String combo) {
        List<Item> out = new ArrayList<>();
        String potionMod = potionMod(combo);
        if (potionMod.equals("long")) {
            out.add(Items.REDSTONE);
        }
        if (potionMod.equals("strong")) {
            out.add(Items.GLOWSTONE_DUST);
        }
        String containerMod = containerMod(combo);
        if (containerMod.equals("splash")) {
            out.add(Items.GUNPOWDER);
        } else if (containerMod.equals("lingering")) {
            out.add(Items.GUNPOWDER);      // 第一步：先转喷溅
            out.add(Items.DRAGON_BREATH);  // 第二步：喷溅再转滞留
        }
        return out;
    }

    /**
     * 重建本批酿造步链（决策 #33 立项、#36 泛化、#37 柔性链）：
     * 地狱疣 → [中间底药步] → 主料 → 修饰。
     * 中间步由目录携带的等价路径（chains）中**按现有库存现选**一条：秘传底药≠粗制
     * （悬浮=缓降+幻翼膜）与原版两步腐化链（伤害=剧毒/治疗 + 腐蛛眼）走同一消费路径。
     * 水瓶直酿族（虚弱=水瓶+腐蛛眼）没有地狱疣步——瓶里本就是水，直接上主料。
     */
    private void rebuildSteps(ServerLevel level) {
        List<Item> steps = new ArrayList<>();
        if (this.recipe.startPotion() != null) {
            // 水瓶直酿：水瓶不进酿造台变粗制，直接从主料步开始
            this.baseStepPotion = null;
            this.baseStepItem = null;
            this.chosenChain = null;
            steps.add(ingredientItem());
            this.steps = List.copyOf(steps);
            return;
        }
        steps.add(Items.NETHER_WART);
        chooseChain(level);
        if (this.baseStepItem != null) {
            steps.add(this.baseStepItem);
        }
        steps.add(ingredientItem());
        steps.addAll(modifierItems(this.orderMod));
        this.steps = List.copyOf(steps);
    }

    /**
     * 柔性选链（决策 #37）：多条等价路径时选"背包或登记容器里已有链料"的那条；
     * 都没有则回落首选（按注册名排序的第一条，确定可复现）。玩家备哪样走哪样——
     * 死认一条会把另一条合法料变成"莫名缺料"（2026-11-07 实测）。
     */
    private void chooseChain(ServerLevel level) {
        this.baseStepPotion = null;
        this.baseStepItem = null;
        this.chosenChain = null;
        var chains = this.recipe.chains();
        if (chains.isEmpty()) {
            return;
        }
        com.bmf.bmfstown.alchemy.AlchemyCatalog.Chain chosen = chains.get(0);
        for (var c : chains) {
            if (countIn(this.resident.getInventory(), s -> s.is(c.item())) > 0
                    || itemInAnyContainer(level, c.item())) {
                chosen = c;
                break;
            }
        }
        this.chosenChain = chosen;
        this.baseStepPotion = chosen.basePotion();
        this.baseStepItem = chosen.item();
    }

    /** 最终主料：柔性链时用选中路径的主料（各路径通常相同，但不假设）。 */
    private Item ingredientItem() {
        return this.chosenChain != null ? this.chosenChain.ingredient()
                : this.recipe.ingredient();
    }

    /** 登记容器池里有没有指定物品（未认领城镇时只按背包口径，返回 false）。 */
    private boolean itemInAnyContainer(ServerLevel level, Item item) {
        if (this.townId == null) {
            // canUse 预检阶段也会走 rebuildSteps→chooseChain，此时还没认领城镇
            return false;
        }
        for (var pos : validContainers(level)) {
            var be = level.getBlockEntity(pos);
            if (be instanceof net.minecraft.world.Container c
                    && countIn(c, s -> s.is(item)) > 0) {
                return true;
            }
        }
        return false;
    }

    /** 组合键中的药水修饰档（none|long|strong）。 */
    private static String potionMod(String combo) {
        for (String part : combo.split("\\+")) {
            if (part.equals("long") || part.equals("strong")) {
                return part;
            }
        }
        return "none";
    }

    /** 组合键中的容器修饰档（none|splash|lingering）。 */
    private static String containerMod(String combo) {
        for (String part : combo.split("\\+")) {
            if (part.equals("splash") || part.equals("lingering")) {
                return part;
            }
        }
        return "none";
    }

    /** 按修饰档取最终容器物品（喷溅/滞留转换后瓶子物品变了）。 */
    private static Item finalContainer(String mod) {
        return switch (mod) {
            case "splash" -> Items.SPLASH_POTION;
            case "lingering" -> Items.LINGERING_POTION;
            default -> Items.POTION;
        };
    }

    /** 完成某步后瓶内应有的药水：地狱疣→粗制；[底药中间步→底药]；主料→基础药（秘传=秘传药）；修饰→最终变体。 */
    @Nullable
    private Holder<Potion> expectedAfterStep(int completedStep) {
        // 水瓶直酿族（虚弱，决策 #36）：没有地狱疣步，第 0 步就是主料步
        if (this.recipe.startPotion() != null) {
            return completedStep == 0
                    ? this.recipe.base()
                    : this.recipe.finalPotion(this.orderMod);
        }
        if (completedStep == 0) {
            return Potions.AWKWARD;
        }
        int ingredientIndex = this.baseStepItem == null ? 1 : 2;
        if (completedStep < ingredientIndex) {
            return this.baseStepPotion; // 底药中间步完成 → 瓶内为底药（如缓降/剧毒）
        }
        if (completedStep == ingredientIndex) {
            return this.recipe.base();
        }
        // 喷溅/滞留只换容器不换药水 → 预期药水仍是基础档
        return this.recipe.finalPotion(this.orderMod);
    }

    /** 三个瓶位是否全部是预期状态（瓶物品 + 药水内容双校验；空瓶/被换/被抽走都算不符）。
     *  只校验药水内容不够：喷溅/滞留步不改内容只换瓶子，没有瓶物品校验就分不清"酿完基础待转容器"和"全部完成"。 */
    private boolean bottlesMatch(BrewingStandBlockEntity stand, Item expectedContainer,
            Holder<Potion> expected) {
        for (int slot = 0; slot < 3; slot++) {
            ItemStack bottle = stand.getItem(slot);
            if (bottle.isEmpty() || !bottle.is(expectedContainer)) {
                return false;
            }
            var potion = bottle.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY)
                    .potion().orElse(null);
            if (potion == null || potion.value() != expected.value()) {
                return false;
            }
        }
        return true;
    }

    /** 完成第 completedStep 步后的瓶物品（火药→喷溅、龙息→滞留，其余步不动瓶）。 */
    private static Item containerAfterSteps(List<Item> steps, int completedStep) {
        Item container = Items.POTION;
        for (int i = 0; i <= completedStep && i < steps.size(); i++) {
            Item s = steps.get(i);
            if (s == Items.GUNPOWDER) {
                container = Items.SPLASH_POTION;
            } else if (s == Items.DRAGON_BREATH) {
                container = Items.LINGERING_POTION;
            }
        }
        return container;
    }

    /** 瓶内药水是否为指定 holder。 */
    private static boolean potionIs(ItemStack bottle, Holder<Potion> expected) {
        return bottle.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY)
                .potion().orElse(null) == expected;
    }

    /** 是否水瓶（含容错）。 */
    private static boolean isWaterBottle(ItemStack stack) {
        return stack.is(Items.POTION) && stack.getOrDefault(
                DataComponents.POTION_CONTENTS, PotionContents.EMPTY).is(Potions.WATER);
    }

    /** 掷本瓶纯度（基础值随段位 + 浮动，clamp ≤1，决策 #27）。 */
    private float rollPurity() {
        var skill = ProfessionType.ALCHEMIST.mainSkill();
        int tier = com.bmf.bmfstown.entity.profile.SkillTiers.tierOf(
                this.resident.getGrowth().skillXp(skill));
        double base = PurityRules.basePurity(tier,
                com.bmf.bmfstown.entity.profile.SkillTiers.MAX_TIER);
        if (this.resident.hasPerk(Perks.NODE_AL_ADEPT, "quickfire")) {
            base -= 0.3D; // 急火快酿（决策 #28）：纯度换效率
        }
        return PurityRules.rollPurity(base, this.resident.getRandom());
    }

    private void takeFromChest(Container chest, Need need) {
        var inv = this.resident.getInventory();
        int want = need.count();
        for (int i = 0; i < chest.getContainerSize() && want > 0; i++) {
            ItemStack stack = chest.getItem(i);
            if (!need.test().test(stack)) {
                continue;
            }
            int take = Math.min(want, stack.getCount());
            // removeItem 的返回值就是拆出的那一份——不要用"活引用 copy + setCount"：
            // 取走整槽时活引用变空堆，copy() 按原版语义返回共享 EMPTY 单例，
            // setCount 改的是空堆本身 → 物品凭空消失（水瓶堆叠 1 必触发，2026-11-06 实锤）
            ItemStack grab = chest.removeItem(i, take);
            if (grab.isEmpty()) {
                continue;
            }
            want -= take;
            ItemStack leftover = inv.addItem(grab);
            if (!leftover.isEmpty()) {
                chest.setItem(i, mergeInto(chest, leftover));
                return;
            }
        }
    }

    private ItemStack mergeInto(Container chest, ItemStack stack) {
        for (int i = 0; i < chest.getContainerSize() && !stack.isEmpty(); i++) {
            ItemStack slot = chest.getItem(i);
            if (slot.isEmpty()) {
                chest.setItem(i, stack.copy());
                stack.setCount(0);
            } else if (ItemStack.isSameItemSameComponents(slot, stack)) {
                int space = slot.getMaxStackSize() - slot.getCount();
                int move = Math.min(space, stack.getCount());
                slot.grow(move);
                stack.shrink(move);
            }
        }
        return stack;
    }

    /** 卡死/干扰回收：把酿造台里的瓶/料全部退走——**优先最近的登记容器**（工人
     *  giveBackHeldMaterials 同款），背包兜底，掉地面是最后手段。
     *  （2026-11-07 修：之前只进背包，反复回收塞满后剩余物掉地上 5 分钟消失
     *  = 用户看到的"药水被吞掉，什么都没剩下"）。回收的药水照常盖章。 */
    private void salvageStand(BrewingStandBlockEntity stand) {
        ServerLevel level = serverLevel();
        Container out = null;
        if (level != null && this.standPos != null) {
            BlockPos target = nearestContainerWithSpace(level, Items.POTION);
            if (target != null) {
                out = containerAt(level, target);
            }
        }
        var inv = this.resident.getInventory();
        for (int slot = 0; slot < stand.getContainerSize(); slot++) {
            ItemStack stack = stand.removeItem(slot, stand.getItem(slot).getCount());
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.getItem() instanceof net.minecraft.world.item.PotionItem
                    && stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY)
                            .hasEffects()) {
                PurityRules.stamp(stack, rollPurity(), this.resident);
            }
            if (out != null) {
                storeItem(out, stack);
            }
            if (!stack.isEmpty()) {
                ItemStack leftover = inv.addItem(stack);
                if (!leftover.isEmpty()) {
                    this.resident.spawnAtLocation(leftover);
                }
            }
        }
        if (out != null) {
            LOGGER.info("[BmfTown][炼金] {} 整台回收：{}（进登记容器，不蒸发）",
                    this.resident.getName().getString(),
                    this.standPos == null ? "?" : this.standPos.toShortString());
        }
    }

    // ==========================================================
    //  岗位认领与登记表
    // ==========================================================

    private boolean claimStand(ServerLevel level) {
        releaseClaim();
        Town town = this.resident.boundTown();
        if (town == null) {
            return false;
        }
        UUID self = this.resident.getUUID();
        // 旧档迁移（幂等）：容器池中误登的酿造台先挪正，再扫岗位池
        town.workshop().migrateBrewStands(level);
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (Long p : town.workshop().brewStations) {
            BlockPos pos = BlockPos.of(p);
            if (!isBrewingStand(level, pos)) {
                continue;
            }
            Claim claim = BREW_CLAIMS.get(p);
            boolean taken = claim != null && !claim.holder().equals(self)
                    && System.currentTimeMillis() - claim.at() < CLAIM_STALE_MS;
            if (taken) {
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
        BREW_CLAIMS.put(best.asLong(), new Claim(this.resident, System.currentTimeMillis()));
        this.standPos = best;
        this.townId = town.id();
        return true;
    }

    private void releaseClaim() {
        if (this.standPos != null) {
            long key = this.standPos.asLong();
            Claim claim = BREW_CLAIMS.get(key);
            if (claim != null && this.resident.getUUID().equals(claim.holder())) {
                BREW_CLAIMS.remove(key);
                // 冷萃收尾：认领释放时把钉死的燃料清零（2026-11-07 用户抓出——
                // 冷萃期间 fuel 恒 20，收工不清零 = 下一个用台的人白嫖 20 次酿造）
                if (this.resident.hasPerk(com.bmf.bmfstown.entity.profile.Perks.NODE_AL_ADEPT, "cold_brew")) {
                    ServerLevel level = serverLevel();
                    if (level != null && isBrewingStand(level, this.standPos)) {
                        var be = level.getBlockEntity(this.standPos);
                        if (be instanceof net.minecraft.world.level.block.entity.BrewingStandBlockEntity stand) {
                            ((com.bmf.bmfstown.mixin.BrewingStandBlockEntityAccessor) stand)
                                    .bmfstown$setFuel(0);
                        }
                    }
                }
            }
            this.standPos = null;
        }
    }

    private boolean townAlive(ServerLevel level) {
        Town town = TownSaveData.get(level).findTown(this.townId.toString());
        return town != null && town.dimension()
                .equals(level.dimension().location().toString());
    }

    private boolean inWorkHours(ServerLevel level) {
        long t = level.getDayTime() % 24000L;
        int[] window = this.resident.workWindow(); // v0.14：边界带晨夜轴个人偏移
        int start = window[0];
        int end = window[1];
        return start <= end ? (t >= start && t < end) : (t >= start || t < end);
    }

    private static boolean isBrewingStand(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(Blocks.BREWING_STAND);
    }

    @Nullable
    private BrewingStandBlockEntity standAt(ServerLevel level) {
        return level.getBlockEntity(this.standPos) instanceof BrewingStandBlockEntity stand
                ? stand : null;
    }

    @Nullable
    private Container containerAt(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof Container c ? c : null;
    }

    /** 离自己最近的、能凑齐任一缺料的登记容器。 */
    @Nullable
    private BlockPos nearestContainerFulfilling(ServerLevel level, List<Need> needs) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : validContainers(level)) {
            Container c = containerAt(level, pos);
            if (c == null) {
                continue;
            }
            boolean useful = false;
            for (Need need : needs) {
                for (int i = 0; i < c.getContainerSize(); i++) {
                    if (need.test().test(c.getItem(i))) {
                        useful = true;
                        break;
                    }
                }
                if (useful) {
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

    /** 登记容器（顺手惰性清理被拆的——工人同款）。 */
    private List<BlockPos> validContainers(ServerLevel level) {
        List<BlockPos> out = new ArrayList<>();
        Town town = TownSaveData.get(level).findTown(this.townId.toString());
        if (town == null) {
            return out;
        }
        List<Long> broken = null;
        for (Long p : town.workshop().containers) {
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
            town.workshop().containers.removeAll(broken);
            TownSaveData.get(level).setDirty();
        }
        return out;
    }

    /** 离酿造台最近的、装得下成品药水的登记容器。 */
    @Nullable
    private BlockPos nearestContainerWithSpace(ServerLevel level, Item item) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : validContainers(level)) {
            Container c = containerAt(level, pos);
            if (c == null || !hasSpaceFor(c, item)) {
                continue;
            }
            double d = this.standPos == null
                    ? pos.distSqr(this.resident.blockPosition())
                    : pos.distSqr(this.standPos);
            if (d < bestDist) {
                bestDist = d;
                best = pos;
            }
        }
        return best;
    }

    private static boolean hasSpaceFor(Container c, Item item) {
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack slot = c.getItem(i);
            if (slot.isEmpty() || (slot.is(item) && slot.getCount() < slot.getMaxStackSize())) {
                return true;
            }
        }
        return false;
    }

    /** 把背包里的成品药水搬进容器（装不下的留身上换下一箱）。 */
    private void movePotionsTo(Container output) {
        var inv = this.resident.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!(stack.getItem() instanceof net.minecraft.world.item.PotionItem)) {
                continue; // 四种药水瓶（普通/喷溅/滞留）都入库
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
                int move = Math.min(space, stack.getCount());
                slot.grow(move);
                stack.shrink(move);
            }
        }
    }

    private int findItem(net.minecraft.world.Container inv,
            java.util.function.Predicate<ItemStack> test) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (test.test(inv.getItem(i))) {
                return i;
            }
        }
        return -1;
    }

    private ServerLevel serverLevel() {
        return this.resident.level() instanceof ServerLevel sl ? sl : null;
    }

    private void navigateToStand() {
        if (this.standPos != null) {
            navigateTo(this.standPos);
        }
    }

    private void navigateTo(BlockPos pos) {
        this.resident.getNavigation().moveTo(
                pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D,
                Config.STROLL_SPEED.get());
    }
}
