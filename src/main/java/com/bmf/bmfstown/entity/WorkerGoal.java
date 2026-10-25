package com.bmf.bmfstown.entity;

import com.bmf.bmfstown.config.Config;
import com.bmf.bmfstown.entity.profile.SkillType;
import com.bmf.bmfstown.entity.profile.WorkshopCraft;
import com.bmf.bmfstown.entity.profile.WorkshopCrafts;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.item.crafting.Ingredient;
import org.slf4j.Logger;

import java.util.EnumSet;

/**
 * 工人的工作 Goal（v0.8.1）：白天在绑定工坊按订单合成红石元件。
 *
 * <h2>材料来源（用户定稿）</h2>
 * 材料/产量来自 {@code RecipeManager} 真实配方反查（见 {@link WorkshopCrafts}），
 * 白名单只圈产物域——没有任何手写配方。取料/消耗按原版
 * {@code Ingredient} 口径匹配（支持标签，如任意木板）。
 *
 * <h2>节奏（用户定稿：三件一批）</h2>
 * 拿最多 3 套原料 → 合成这批（产量 = 配方单次产量 × 套数）→
 * 打开产物箱入库 → 循环。有多少拿多少；缺料原地待机。
 *
 * <h2>背包优先（v0.8.1 实测修复）</h2>
 * 材料取到背包就跟着人走：被面板/觅食打断的 stop 不退料（仍是工作时间），
 * 恢复后先盘背包再决定开不开箱——不会出现"开箱→拿料→被顶→退料→再拿"循环。
 * 只有下班/解绑/停工才退料。
 */
public class WorkerGoal extends Goal {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 与工作方块/箱子的交互距离（格）。 */
    private static final double OPEN_RANGE = 3.0D;

    /** 待机重试间隔（tick）。 */
    private static final int IDLE_RETRY_TICKS = 100;

    /** 开/关盖动画停留时长（tick）。 */
    private static final int LID_TICKS = 10;

    /** 一批最多做几套（用户定稿：三件一批）。 */
    private static final int BATCH_MAX = 3;

    private enum Phase {
        GO_TABLE, FETCH_OPEN, FETCH_TAKE, CRAFT, STORE_OPEN, STORE_PUT
    }

    private final Resident resident;

    private BlockPos tablePos;
    private BlockPos inputPos;
    private BlockPos outputPos;
    private WorkshopCraft craft;
    private Phase phase = Phase.GO_TABLE;
    private int craftTicksLeft;
    private int lidTimer;
    private int batch;
    private int retryCooldown;
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

    // ==========================================================
    //  Goal 生命周期
    // ==========================================================

    @Override
    public boolean canUse() {
        if (this.resident.getProfession() != com.bmf.bmfstown.entity.profile.ProfessionType.WORKER
                || this.resident.isSleeping() || this.resident.isForaging()) {
            return false;
        }
        ServerLevel level = serverLevel();
        if (level == null || !inWorkHours(level)) {
            return false;
        }
        MoodState mood = this.resident.getMoodState();
        if (mood.craftTablePos() == 0L || mood.inputChestPos() == 0L
                || mood.outputChestPos() == 0L || !mood.workerEnabled()
                || mood.orderItem().isEmpty()) {
            return false;
        }
        this.tablePos = BlockPos.of(mood.craftTablePos());
        this.inputPos = BlockPos.of(mood.inputChestPos());
        this.outputPos = BlockPos.of(mood.outputChestPos());
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        ServerLevel level = serverLevel();
        if (level == null || this.resident.isSleeping() || this.resident.isForaging()
                || !inWorkHours(level)) {
            return false;
        }
        MoodState mood = this.resident.getMoodState();
        return mood.workerEnabled() && mood.craftTablePos() != 0L
                && !mood.orderItem().isEmpty()
                && this.resident.getProfession()
                == com.bmf.bmfstown.entity.profile.ProfessionType.WORKER;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.running = true;
        this.phase = Phase.GO_TABLE;
        this.retryCooldown = 0;
        navigateToTable();
        LOGGER.debug("[BmfTown][工人] {} 开工（订单={}）",
                this.resident.getName().getString(), this.resident.getMoodState().orderItem());
    }

    @Override
    public void stop() {
        this.running = false;
        this.resident.setWorkerVisual(false);
        ServerLevel level = serverLevel();
        boolean resumable = level != null && inWorkHours(level)
                && this.resident.getMoodState().workerEnabled()
                && this.resident.getMoodState().craftTablePos() != 0L;
        if ((this.phase == Phase.CRAFT || this.phase == Phase.FETCH_TAKE
                || this.phase == Phase.STORE_PUT) && !resumable) {
            giveBackHeldMaterials();
            LOGGER.debug("[BmfTown][工人] {} 下班/停工，原料退回原料箱",
                    this.resident.getName().getString());
        } else if (this.phase == Phase.CRAFT || this.phase == Phase.FETCH_TAKE) {
            LOGGER.debug("[BmfTown][工人] {} 被临时打断，材料随身保留",
                    this.resident.getName().getString());
        }
        this.craft = null;
        this.phase = Phase.GO_TABLE;
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
        switch (this.phase) {
            case GO_TABLE -> tickGoTable();
            case FETCH_OPEN -> tickFetchOpen(level);
            case FETCH_TAKE -> tickFetchTake(level);
            case CRAFT -> tickCraft();
            case STORE_OPEN -> tickStoreOpen(level);
            case STORE_PUT -> tickStorePut(level);
        }
        // 可视化标记：进工坊干活才亮，走路去工坊不亮
        this.resident.setWorkerVisual(this.phase != Phase.GO_TABLE);
    }

    // ==========================================================
    //  阶段
    // ==========================================================

    private void tickGoTable() {
        if (this.resident.blockPosition().closerThan(this.tablePos, OPEN_RANGE)
                || this.resident.getNavigation().isDone()) {
            this.phase = Phase.FETCH_OPEN;
        }
    }

    private void tickFetchOpen(ServerLevel level) {
        if (!this.resident.blockPosition().closerThan(this.inputPos, OPEN_RANGE)) {
            if (this.resident.getNavigation().isDone()) {
                this.resident.getNavigation().moveTo(
                        this.inputPos.getX() + 0.5D, this.inputPos.getY(),
                        this.inputPos.getZ() + 0.5D, Config.STROLL_SPEED.get());
            }
            return;
        }
        this.resident.getNavigation().stop();
        if (containerAt(this.inputPos) == null) {
            this.retryCooldown = IDLE_RETRY_TICKS;
            this.phase = Phase.GO_TABLE;
            return;
        }
        this.resident.containerFx(level, this.inputPos, true);
        this.lidTimer = LID_TICKS;
        this.phase = Phase.FETCH_TAKE;
    }

    private void tickFetchTake(ServerLevel level) {
        if (--this.lidTimer > 0) {
            return;
        }
        Container input = containerAt(this.inputPos);
        if (input == null) {
            this.retryCooldown = IDLE_RETRY_TICKS;
            this.phase = Phase.GO_TABLE;
            return;
        }

        // 订单：按产物注册名从真实配方目录反查
        String orderItem = this.resident.getMoodState().orderItem();
        WorkshopCraft order = findOrder(level, orderItem);
        if (order == null) {
            this.resident.containerFx(level, this.inputPos, false);
            this.retryCooldown = IDLE_RETRY_TICKS;
            LOGGER.info("[BmfTown][工人] {} 订单 {} 没有对应配方，待机",
                    this.resident.getName().getString(), orderItem);
            return;
        }
        if (!WorkshopCrafts.lineAllowed(this.resident.getProfile(), order.result())) {
            this.resident.containerFx(level, this.inputPos, false);
            this.retryCooldown = IDLE_RETRY_TICKS;
            return;
        }

        // 背包优先：上次被打断揣走的原料先算进本批
        int invSets = inventorySets(order);
        int chestSets = maxAffordableSets(input, order);
        int newBatch = Math.min(BATCH_MAX, invSets + chestSets);
        if (newBatch <= 0) {
            this.resident.containerFx(level, this.inputPos, false);
            this.retryCooldown = IDLE_RETRY_TICKS;
            LOGGER.debug("[BmfTown][工人] {} 没料可做，待机等补料（背包 {} 套/箱 {} 套）",
                    this.resident.getName().getString(), invSets, chestSets);
            return;
        }

        boolean thrift = this.resident.hasPerk(
                com.bmf.bmfstown.entity.profile.Perks.NODE_W_LEGEND, "thrift")
                && this.resident.getRandom().nextInt(100) < Config.CRAFT_THRIFT_PERCENT.get();
        int needFromChest = Math.max(0, newBatch - invSets);
        if (!thrift && needFromChest > 0) {
            if (!hasIngredientsFor(input, order, needFromChest)
                    || !takeIngredients(input, order, needFromChest)) {
                this.resident.containerFx(level, this.inputPos, false);
                this.retryCooldown = IDLE_RETRY_TICKS;
                LOGGER.info("[BmfTown][工人] {} 取料失败（材料中途变动），稍后重试",
                        this.resident.getName().getString());
                return;
            }
        }

        this.batch = newBatch;
        this.craft = order;
        this.craftTicksLeft = craftTicks() * this.batch;
        this.resident.containerFx(level, this.inputPos, false);
        this.phase = Phase.CRAFT;
        LOGGER.debug("[BmfTown][工人] {} 取料完成：{} 套（背包 {} + 箱 {}），开始合成",
                this.resident.getName().getString(), this.batch, invSets, needFromChest);
    }

    private void tickCraft() {
        if (--this.craftTicksLeft > 0) {
            this.resident.getNavigation().stop();
            return;
        }
        // 消耗与产出绑定在同一处：扣不出料就不产出（结构上杜绝无料出货）
        boolean thrift = this.resident.hasPerk(
                com.bmf.bmfstown.entity.profile.Perks.NODE_W_LEGEND, "thrift")
                && this.resident.getRandom().nextInt(100) < Config.CRAFT_THRIFT_PERCENT.get();
        if (!thrift && !consumeFromInventory(this.craft, this.batch)) {
            this.retryCooldown = IDLE_RETRY_TICKS;
            this.phase = Phase.FETCH_OPEN;
            LOGGER.info("[BmfTown][工人] {} 合成完成但背包材料对不上，本批作废",
                    this.resident.getName().getString());
            return;
        }

        // 产量 = 配方单次产量 × 套数；开源 perk：每套概率额外 +1 个
        int count = this.craft.resultCount() * this.batch;
        if (this.resident.hasPerk(
                com.bmf.bmfstown.entity.profile.Perks.NODE_W_LEGEND, "opensource")) {
            for (int i = 0; i < this.batch; i++) {
                if (this.resident.getRandom().nextInt(100) < Config.CRAFT_OPEN_SOURCE_PERCENT.get()) {
                    count += this.craft.resultCount();
                }
            }
        }
        ItemStack out = new ItemStack(this.craft.result(), count);
        this.resident.getInventory().addItem(out);
        this.resident.addSkillXp(SkillType.CRAFTING, Config.CRAFT_XP_PER_ITEM.get() * this.batch);
        LOGGER.debug("[BmfTown][工人] {} 合成 {} x{}，去产物箱入库",
                this.resident.getName().getString(),
                this.craft.result().getDefaultInstance().getHoverName().getString(), count);

        this.phase = Phase.STORE_OPEN;
    }

    private void tickStoreOpen(ServerLevel level) {
        if (!this.resident.blockPosition().closerThan(this.outputPos, OPEN_RANGE)) {
            if (this.resident.getNavigation().isDone()) {
                this.resident.getNavigation().moveTo(
                        this.outputPos.getX() + 0.5D, this.outputPos.getY(),
                        this.outputPos.getZ() + 0.5D, Config.STROLL_SPEED.get());
            }
            return;
        }
        this.resident.getNavigation().stop();
        if (containerAt(this.outputPos) == null) {
            this.retryCooldown = IDLE_RETRY_TICKS;
            this.phase = Phase.GO_TABLE;
            return;
        }
        this.resident.containerFx(level, this.outputPos, true);
        this.lidTimer = LID_TICKS;
        this.phase = Phase.STORE_PUT;
    }

    private void tickStorePut(ServerLevel level) {
        if (--this.lidTimer > 0) {
            return;
        }
        Container output = containerAt(this.outputPos);
        if (output == null) {
            this.retryCooldown = IDLE_RETRY_TICKS;
            this.phase = Phase.GO_TABLE;
            return;
        }
        if (this.craft != null) {
            moveAllFromInventory(output, this.craft.result());
        }
        this.resident.containerFx(level, this.outputPos, false);
        this.phase = Phase.FETCH_OPEN;
        LOGGER.debug("[BmfTown][工人] {} 成品入库，继续下一批",
                this.resident.getName().getString());
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
                this.resident.getProfile().skillXp(skill),
                this.resident.getProfile().mood(), false);
        efficiency *= com.bmf.bmfstown.entity.profile.EfficiencyMath.workerSpeedMultiplier(
                this.resident.getProfile().perkChoice(
                        com.bmf.bmfstown.entity.profile.Perks.NODE_W_ADEPT));
        if (efficiency < 0.1D) {
            efficiency = 0.1D;
        }
        return Math.max(1, (int) Math.round(
                Config.CRAFT_TICKS_PER_ITEM.get() / efficiency));
    }

    private ServerLevel serverLevel() {
        return this.resident.level() instanceof ServerLevel sl ? sl : null;
    }

    private void navigateToTable() {
        this.resident.getNavigation().moveTo(
                this.tablePos.getX() + 0.5D, this.tablePos.getY(), this.tablePos.getZ() + 0.5D,
                Config.STROLL_SPEED.get());
    }

    private Container containerAt(BlockPos pos) {
        ServerLevel level = serverLevel();
        if (level == null) {
            return null;
        }
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof Container container ? container : null;
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

    /** 箱内材料够几套。 */
    private int maxAffordableSets(Container chest, WorkshopCraft craft) {
        int sets = Integer.MAX_VALUE;
        for (WorkshopCraft.Req req : craft.requirements()) {
            sets = Math.min(sets, countMatching(chest, req.ingredient()) / req.count());
        }
        return sets == Integer.MAX_VALUE ? 0 : sets;
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

    private boolean hasIngredientsFor(Container chest, WorkshopCraft craft, int sets) {
        for (WorkshopCraft.Req req : craft.requirements()) {
            if (countMatching(chest, req.ingredient()) < req.count() * sets) {
                return false;
            }
        }
        return true;
    }

    private boolean takeIngredients(Container chest, WorkshopCraft craft, int sets) {
        for (WorkshopCraft.Req req : craft.requirements()) {
            int remaining = req.count() * sets;
            for (int i = 0; i < chest.getContainerSize() && remaining > 0; i++) {
                ItemStack stack = chest.getItem(i);
                if (!req.matches(stack)) {
                    continue;
                }
                int take = Math.min(remaining, stack.getCount());
                chest.removeItem(i, take);
                remaining -= take;
                ItemStack grab = stack.copy();
                grab.setCount(take);
                ItemStack leftover = this.resident.getInventory().addItem(grab);
                if (!leftover.isEmpty()) {
                    storeItem(chest, leftover);
                    return false;
                }
            }
            if (remaining > 0) {
                return false;
            }
        }
        return true;
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

    private void giveBackHeldMaterials() {
        if (this.craft == null || this.batch <= 0) {
            return;
        }
        Container input = containerAt(this.inputPos);
        if (input == null) {
            return;
        }
        for (WorkshopCraft.Req req : this.craft.requirements()) {
            int remaining = req.count() * this.batch;
            for (int i = 0; i < this.resident.getInventory().getContainerSize() && remaining > 0; i++) {
                ItemStack stack = this.resident.getInventory().getItem(i);
                if (!req.matches(stack)) {
                    continue;
                }
                int give = Math.min(remaining, stack.getCount());
                storeItem(input, stack.split(give));
                remaining -= give;
            }
        }
    }
}
