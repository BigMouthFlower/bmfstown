package com.bmf.bmfstown.entity;

import com.bmf.bmfstown.book.BookContent;
import com.bmf.bmfstown.book.BookDraft;
import com.bmf.bmfstown.book.BookDrafting;
import com.bmf.bmfstown.config.Config;
import com.bmf.bmfstown.entity.domain.JobState;
import com.bmf.bmfstown.town.Town;
import com.bmf.bmfstown.town.TownSaveData;
import com.bmf.bmfstown.town.TownZone;
import com.bmf.bmfstown.town.TownWandMode;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;

import java.util.EnumSet;
import java.util.UUID;

/**
 * 学者工作状态机（v0.11，**与 {@link WorkerGoal} 同构**——用户定稿：
 * 取料/开箱/寻路/存放交互必须与工人完全一致，之前自写的简陋版已废弃）：
 *
 * <pre>
 * 去料箱（图书馆区容器，自动认领并记住）→ 开箱取 1 书+1 墨（进背包随身）
 *   → 去讲台 → 消耗书墨合成"写作中的书"（此刻抽奖定内容，玩家不可见）
 *   → 写作（gameTime 在岗 tick × 效率；perk 书 50% 卡点）
 *   → 去雕纹书架 → 成书插入（复刻原版 updateState）/ 背包兜底
 * </pre>
 *
 * <p>认领的讲台/料箱记在 JobState（持久）；失效（方块没了/没料）自动重认领。
 * 写作中被打断（面板/夜幕）：材料随身、WIP 书在工作者页格子——恢复后
 * 从断点继续，任何人接手也一样（进度全在书组件里）。
 */
public class ScholarGoal extends Goal {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** 与讲台/箱子的交互距离（格）——工人同款。 */
    private static final double OPEN_RANGE = 3.0D;
    /** 待机重试间隔（tick）——工人同款。 */
    private static final int IDLE_RETRY_TICKS = 100;
    /** 开/关盖动画停留时长（tick）——工人同款。 */
    private static final int LID_TICKS = 10;
    /** 卡点提示台词冷却（gameTime）。 */
    private static final long STUCK_SAY_COOLDOWN = 12000L;

    private enum Phase {
        GO_INPUT, FETCH_OPEN, FETCH_TAKE, GO_LECTERN, WRITE, STORE_GO, STORE_PUT
    }

    private final Resident resident;
    private Phase phase = Phase.GO_INPUT;
    private BlockPos lecternPos;
    private BlockPos inputPos;
    private BlockPos shelfPos;
    private int lidTimer;
    private int retryCooldown;
    /** 写作效率小数桶（效率 <1 时积少成多）。 */
    private double fraction;
    private long lastStuckSay = Long.MIN_VALUE;

    public ScholarGoal(Resident resident) {
        this.resident = resident;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    // ==========================================================
    //  Goal 生命周期
    // ==========================================================

    @Override
    public boolean canUse() {
        if (this.resident.getProfession() != com.bmf.bmfstown.entity.profile.ProfessionType.SCHOLAR
                || this.resident.isSleeping() || this.resident.isForaging()
                || this.resident.getFollowResidentId() != null) {
            return false; // 跟随观察中不写书
        }
        ServerLevel level = serverLevel();
        return level != null && inWorkHours(level)
                && this.resident.getIdentity().townId() != null;
    }

    @Override
    public boolean canContinueToUse() {
        ServerLevel level = serverLevel();
        return level != null && !this.resident.isSleeping() && !this.resident.isForaging()
                && inWorkHours(level)
                && this.resident.getFollowResidentId() == null
                && this.resident.getProfession()
                == com.bmf.bmfstown.entity.profile.ProfessionType.SCHOLAR;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.retryCooldown = 0;
        this.phase = Phase.GO_INPUT;
        this.resident.setScholarVisual(false);
        LOGGER.info("[BmfTown][学者] {} 开工", this.resident.getName().getString());
    }

    @Override
    public void stop() {
        this.resident.setScholarVisual(false);
        // 材料随身（已进背包）、WIP 书在工作页格子——恢复后从断点继续。
        // 与工人一致：不强制退料（书墨本来就要随身带去讲台）。
        this.phase = Phase.GO_INPUT;
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
            case GO_INPUT -> tickGoInput(level);
            case FETCH_OPEN -> tickFetchOpen(level);
            case FETCH_TAKE -> tickFetchTake(level);
            case GO_LECTERN -> tickGoLectern(level);
            case WRITE -> tickWrite(level);
            case STORE_GO -> tickStoreGo(level);
            case STORE_PUT -> tickStorePut(level);
        }
        this.resident.setScholarVisual(this.phase == Phase.WRITE);

        if (this.resident.tickCount % 100 == 0) {
            LOGGER.info("[BmfTown][学者] {} phase={} lectern={} input={} shelf={} wip={} progress={}",
                    this.resident.getName().getString(), this.phase, this.lecternPos,
                    this.inputPos, this.shelfPos,
                    this.resident.getWritingBook().isEmpty() ? "无" : "有",
                    this.resident.getWritingBook().get(
                            com.bmf.bmfstown.ModDataComponents.BOOK_CONTENT.get()) == null ? "-"
                            : this.resident.getWritingBook().get(
                                    com.bmf.bmfstown.ModDataComponents.BOOK_CONTENT.get()).progress());
        }
    }

    // ==========================================================
    //  阶段
    // ==========================================================

    /** 去料箱：没认领（或失效）先在图书馆区里认领一个含书墨的容器。 */
    private void tickGoInput(ServerLevel level) {
        // 断点续写：WIP 已含书墨（取料时合成过），不用再去料箱
        if (!this.resident.getWritingBook().isEmpty()) {
            this.phase = Phase.GO_LECTERN;
            return;
        }
        if (!validInput(level)) {
            if (!claimSupplies(level)) {
                this.retryCooldown = IDLE_RETRY_TICKS;
                return; // 馆里没有书墨：待机等补料
            }
        }
        if (!this.resident.blockPosition().closerThan(this.inputPos, OPEN_RANGE)) {
            if (this.resident.getNavigation().isDone()) {
                this.resident.getNavigation().moveTo(
                        this.inputPos.getX() + 0.5D, this.inputPos.getY(),
                        this.inputPos.getZ() + 0.5D, Config.STROLL_SPEED.get());
            }
            return;
        }
        this.phase = Phase.FETCH_OPEN;
    }

    /** 开料箱（盖子动画）——工人同款。 */
    private void tickFetchOpen(ServerLevel level) {
        if (!this.resident.blockPosition().closerThan(this.inputPos, OPEN_RANGE)) {
            this.phase = Phase.GO_INPUT;
            return;
        }
        this.resident.getNavigation().stop();
        if (containerAt(this.inputPos) == null) {
            this.jobState().putLibraryInput(null); // 箱子没了：重新认领
            this.retryCooldown = IDLE_RETRY_TICKS;
            this.phase = Phase.GO_INPUT;
            return;
        }
        this.resident.containerFx(level, this.inputPos, true);
        this.lidTimer = LID_TICKS;
        this.phase = Phase.FETCH_TAKE;
    }

    /** 取料：1 书 + 1 墨囊 **真进背包**（随身带去讲台）——工人同款语义。 */
    private void tickFetchTake(ServerLevel level) {
        if (--this.lidTimer > 0) {
            return;
        }
        // 工人同款"背包优先"：上次被打断揣走的书墨先算数，不从箱里重复取
        boolean hasBook = hasItem(this.resident.getInventory(), Items.BOOK);
        boolean hasInk = hasItem(this.resident.getInventory(), Items.INK_SAC);
        if (hasBook && hasInk) {
            this.resident.containerFx(level, this.inputPos, false);
            this.phase = Phase.GO_LECTERN;
            return;
        }
        Container input = containerAt(this.inputPos);
        this.resident.containerFx(level, this.inputPos, false);
        if (input == null
                || (!hasBook && !takeFromContainer(input, Items.BOOK))
                || (!hasInk && !takeFromContainer(input, Items.INK_SAC))) {
            this.jobState().putLibraryInput(null); // 料没了：换一个容器
            this.retryCooldown = IDLE_RETRY_TICKS;
            this.phase = Phase.GO_INPUT;
            return;
        }
        this.phase = Phase.GO_LECTERN;
    }

    /** 去讲台：没认领先认领；到位后开写（消耗随身书墨合成 WIP）。 */
    private void tickGoLectern(ServerLevel level) {
        if (!validLectern(level)) {
            if (!claimLectern(level)) {
                this.retryCooldown = IDLE_RETRY_TICKS;
                return; // 馆里没有讲台
            }
        }
        if (!this.resident.blockPosition().closerThan(this.lecternPos, OPEN_RANGE)) {
            if (this.resident.getNavigation().isDone()) {
                this.resident.getNavigation().moveTo(
                        this.lecternPos.getX() + 0.5D, this.lecternPos.getY(),
                        this.lecternPos.getZ() + 0.5D, Config.STROLL_SPEED.get());
            }
            return;
        }
        this.resident.getNavigation().stop();
        // 开写：随身书墨合成 WIP（非空=断点续写，直接进写作）
        if (this.resident.getWritingBook().isEmpty()) {
            if (!consumeFromInventory(Items.BOOK)
                    || !consumeFromInventory(Items.INK_SAC)) {
                this.phase = Phase.GO_INPUT; // 随身料没了：回料箱
                return;
            }
            UUID townId = this.resident.getIdentity().townId();
            BookDraft draft = BookDrafting.roll(level, townId, this.resident.getRandom());
            BookContent content = BookContent.startWriting(townId,
                    this.resident.getName().getString(), draft,
                    BookDrafting.targetTicks(draft));
            ItemStack stack = new ItemStack(com.bmf.bmfstown.ModItems.WRITING_BOOK.get());
            stack.set(com.bmf.bmfstown.ModDataComponents.BOOK_CONTENT.get(), content);
            this.resident.setWritingBook(stack);
        }
        this.phase = Phase.WRITE;
    }

    /** 写作：在岗 tick 累计（效率乘数）；perk 书 50% 卡点。 */
    private void tickWrite(ServerLevel level) {
        ItemStack stack = this.resident.getWritingBook();
        BookContent content = stack.get(com.bmf.bmfstown.ModDataComponents.BOOK_CONTENT.get());
        if (content == null || content.isFinished()) {
            this.phase = content != null ? Phase.STORE_GO : Phase.GO_INPUT;
            return;
        }
        // ---- 50% 卡点（仅 perk 书）：传奇判定 + 自动跟随实践 ----
        if (content.isStuck() && !legendPresent(level, content)) {
            sayStuckOnce(level);
            pickPracticeTarget(level, content);
            return; // 本 Goal 让位给 ScholarFollowGoal
        }

        // ---- 在岗 tick 累计（效率乘数，小数桶积少成多）----
        this.fraction += efficiency();
        long amount = (long) this.fraction;
        if (amount > 0) {
            this.fraction -= amount;
            content = content.withProgressAdded(amount);
            stack.set(com.bmf.bmfstown.ModDataComponents.BOOK_CONTENT.get(), content);
            this.resident.syncClient(); // 进度推快照（equals 去重，同值无流量）
        }
        if (content.isFinished()) {
            LOGGER.info("[BmfTown][学者] {} 写成一本，去书架入库",
                    this.resident.getName().getString());
            this.phase = Phase.STORE_GO;
        }
    }

    /** 去书架：实时在图书馆区找有空槽的雕纹书架。 */
    private void tickStoreGo(ServerLevel level) {
        if (this.shelfPos == null || !validShelf(level, this.shelfPos)) {
            this.shelfPos = findShelfWithSpace(level);
            if (this.shelfPos == null) {
                // 没有空书架：成书进背包，继续写下一本
                storeToInventory(level);
                this.phase = Phase.GO_INPUT;
                return;
            }
        }
        if (!this.resident.blockPosition().closerThan(this.shelfPos, OPEN_RANGE)) {
            if (this.resident.getNavigation().isDone()) {
                this.resident.getNavigation().moveTo(
                        this.shelfPos.getX() + 0.5D, this.shelfPos.getY(),
                        this.shelfPos.getZ() + 0.5D, Config.STROLL_SPEED.get());
            }
            return;
        }
        this.resident.getNavigation().stop();
        this.phase = Phase.STORE_PUT;
    }

    /** 插书（复刻原版 updateState）→ 回料箱写下一本。 */
    private void tickStorePut(ServerLevel level) {
        ItemStack wip = this.resident.getWritingBook();
        BookContent content = wip.get(com.bmf.bmfstown.ModDataComponents.BOOK_CONTENT.get());
        this.resident.setWritingBook(ItemStack.EMPTY);
        this.resident.setScholarVisual(false);
        if (content != null) {
            ItemStack finished = new ItemStack(com.bmf.bmfstown.ModItems.FINISHED_BOOK.get());
            finished.set(com.bmf.bmfstown.ModDataComponents.BOOK_CONTENT.get(), content);
            if (this.shelfPos != null && insertShelf(level, this.shelfPos, finished)) {
                LOGGER.info("[BmfTown][学者] {} 成书入架 @{}",
                        this.resident.getName().getString(), this.shelfPos);
            } else {
                this.resident.getInventory().addItem(finished);
                LOGGER.info("[BmfTown][学者] {} 书架满/失效，成书进背包",
                        this.resident.getName().getString());
            }
            this.resident.getGrowth().addSkillXp(
                    this.resident.getProfession().mainSkill(), 30);
            this.resident.syncClient();
        }
        this.phase = Phase.GO_INPUT;
    }

    // ==========================================================
    //  认领与工具
    // ==========================================================

    private JobState jobState() {
        return this.resident.getJobState();
    }

    private boolean validInput(ServerLevel level) {
        return this.inputPos != null && level.getBlockEntity(this.inputPos)
                instanceof Container;
    }

    private boolean validLectern(ServerLevel level) {
        return this.lecternPos != null
                && level.getBlockState(this.lecternPos).getBlock() == Blocks.LECTERN;
    }

    private boolean validShelf(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof ChiseledBookShelfBlockEntity shelf)) {
            return false;
        }
        for (int slot = 0; slot < shelf.getContainerSize(); slot++) {
            if (shelf.getItem(slot).isEmpty()) {
                return true; // 还有空槽
            }
        }
        return false;
    }

    /** 认领料箱：图书馆区内容器里找一个"同时含书和墨"的。 */
    private boolean claimSupplies(ServerLevel level) {
        for (BlockPos pos : libraryContainers(level)) {
            Container c = containerAt(pos);
            if (c == null) {
                continue;
            }
            boolean book = false;
            boolean ink = false;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack st = c.getItem(i);
                book |= st.is(Items.BOOK);
                ink |= st.is(Items.INK_SAC);
            }
            if (book && ink) {
                this.inputPos = pos;
                this.jobState().putLibraryInput(pos);
                return true;
            }
        }
        return false;
    }

    /** 认领讲台：图书馆区里第一个讲台。 */
    private boolean claimLectern(ServerLevel level) {
        for (BlockPos pos : libraryBlocks(level, Blocks.LECTERN)) {
            this.lecternPos = pos;
            this.jobState().putLibraryLectern(pos);
            return true;
        }
        return false;
    }

    private void storeToInventory(ServerLevel level) {
        ItemStack wip = this.resident.getWritingBook();
        BookContent content = wip.get(com.bmf.bmfstown.ModDataComponents.BOOK_CONTENT.get());
        this.resident.setWritingBook(ItemStack.EMPTY);
        if (content != null) {
            ItemStack finished = new ItemStack(com.bmf.bmfstown.ModItems.FINISHED_BOOK.get());
            finished.set(com.bmf.bmfstown.ModDataComponents.BOOK_CONTENT.get(), content);
            this.resident.getInventory().addItem(finished);
        }
        this.resident.getGrowth().addSkillXp(
                this.resident.getProfession().mainSkill(), 30);
        this.resident.syncClient();
    }

    /** 图书馆区（WORK 选区）内的所有指定方块位置。 */
    private java.util.List<BlockPos> libraryBlocks(ServerLevel level,
            net.minecraft.world.level.block.Block match) {
        Town town = townOf(level);
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        if (town != null) {
            for (TownZone zone : town.zones(TownWandMode.WORK)) {
                for (BlockPos pos : TownZone.iterate(zone)) {
                    if (level.getBlockState(pos).getBlock() == match) {
                        out.add(pos.immutable());
                    }
                }
            }
        }
        return out;
    }

    /** 图书馆区内的容器（取料候选）。 */
    private java.util.List<BlockPos> libraryContainers(ServerLevel level) {
        Town town = townOf(level);
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        if (town != null) {
            for (TownZone zone : town.zones(TownWandMode.WORK)) {
                for (BlockPos pos : TownZone.iterate(zone)) {
                    if (level.getBlockEntity(pos) instanceof Container) {
                        out.add(pos.immutable());
                    }
                }
            }
        }
        return out;
    }

    /** 找有空槽的雕纹书架（图书馆区内，取最近）。 */
    private BlockPos findShelfWithSpace(ServerLevel level) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : libraryBlocks(level, Blocks.CHISELED_BOOKSHELF)) {
            if (!validShelf(level, pos)) {
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

    /**
     * 插书：走原版 {@link ChiseledBookShelfBlockEntity#setItem}——其内部自带
     * updateState（occupied 属性 + gameEvent），无需手写同步。
     * 原版对非 minecraft:bookshelf_books 标签的物品会**静默丢弃**，
     * 所以先预检标签，不过关直接返回 false（成书退背包兜底，绝不蒸发）。
     */
    private boolean insertShelf(ServerLevel level, BlockPos pos, ItemStack book) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof ChiseledBookShelfBlockEntity shelf)) {
            return false;
        }
        if (!book.is(net.minecraft.tags.ItemTags.BOOKSHELF_BOOKS)) {
            LOGGER.warn("[BmfTown][学者] 成书 {} 不在原版 bookshelf_books 标签内，无法入架",
                    book.getItem().toString());
            return false;
        }
        for (int slot = 0; slot < shelf.getContainerSize(); slot++) {
            if (shelf.getItem(slot).isEmpty()) {
                shelf.setItem(slot, book.split(1));
                shelf.setChanged();
                return true;
            }
        }
        return false;
    }

    /** 位置处的容器（工人同款判定）。 */
    private Container containerAt(BlockPos pos) {
        BlockEntity be = this.resident.level().getBlockEntity(pos);
        return be instanceof Container c ? c : null;
    }

    /** 从容器取一件指定物品 → **真进背包随身**（工人同款；旧版 shrink 直接近似吞料，已废）。 */
    private boolean takeFromContainer(Container container, net.minecraft.world.item.Item item) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                this.resident.getInventory().addItem(stack.split(1));
                container.setChanged();
                return true;
            }
        }
        return false;
    }

    /** 背包里有没有指定物品（工人"背包优先"判定用）。 */
    private boolean hasItem(Container container, net.minecraft.world.item.Item item) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (!container.getItem(i).isEmpty() && container.getItem(i).is(item)) {
                return true;
            }
        }
        return false;
    }

    /** 从背包消耗一件指定物品（讲台开写扣书墨；纯扣减，不搬运）。 */
    private boolean consumeFromInventory(net.minecraft.world.item.Item item) {
        var inv = this.resident.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                stack.shrink(1);
                return true;
            }
        }
        return false;
    }

    /** 卡点解除判定：自己该职业传奇，或附近有同镇该职业传奇在场。 */
    private boolean legendPresent(ServerLevel level, BookContent content) {
        if (content.checkpointPassed()) {
            return true;
        }
        if (!(content.draft() instanceof BookDraft.Perk perk)) {
            return true;
        }
        var profession = com.bmf.bmfstown.entity.profile.ProfessionType.byName(perk.professionId());
        UUID townId = this.resident.getIdentity().townId();
        var skill = profession.mainSkill();
        if (this.resident.getProfession() == profession
                && skill != null
                && this.resident.getGrowth().skillXp(skill) >= 7500) {
            return true;
        }
        for (var other : level.getEntitiesOfClass(Resident.class,
                this.resident.getBoundingBox().inflate(16.0D))) {
            if (other == this.resident || other.getProfession() != profession) {
                continue;
            }
            if (townId != null && !townId.equals(other.getIdentity().townId())) {
                continue;
            }
            if (skill != null && other.getGrowth().skillXp(skill) >= 7500) {
                return true; // 传奇在场口述，学者代笔
            }
        }
        return false;
    }

    /** 卡点自动选实践目标：优先同镇该职业传奇，否则该职业任意从业者。 */
    private void pickPracticeTarget(ServerLevel level, BookContent content) {
        if (this.resident.getFollowResidentId() != null
                || !(content.draft() instanceof BookDraft.Perk perk)) {
            return;
        }
        var profession = com.bmf.bmfstown.entity.profile.ProfessionType.byName(perk.professionId());
        UUID townId = this.resident.getIdentity().townId();
        Resident legend = null;
        Resident anyone = null;
        for (var other : level.getEntitiesOfClass(Resident.class,
                this.resident.getBoundingBox().inflate(48.0D))) {
            if (other == this.resident || other.getProfession() != profession) {
                continue;
            }
            if (townId != null && !townId.equals(other.getIdentity().townId())) {
                continue;
            }
            var skill = profession.mainSkill();
            if (legend == null && skill != null
                    && other.getGrowth().skillXp(skill) >= 7500) {
                legend = other;
            }
            if (anyone == null) {
                anyone = other;
            }
        }
        Resident target = legend != null ? legend : anyone;
        if (target != null) {
            this.resident.setFollowResidentId(target.getUUID());
        }
    }

    private void sayStuckOnce(ServerLevel level) {
        long now = level.getGameTime();
        if (this.lastStuckSay != Long.MIN_VALUE && now - this.lastStuckSay < STUCK_SAY_COOLDOWN) {
            return;
        }
        this.lastStuckSay = now;
        com.bmf.bmfstown.social.DialogEngine.sayRaw(this.resident,
                net.minecraft.network.chat.Component.translatable(
                        "dialog.bmfstown.scholar.stuck").getString());
    }

    /** 写作效率：与工人同口径（段位效率 × 心情），下限 0.1。 */
    private double efficiency() {
        var skill = this.resident.getProfession().mainSkill();
        double efficiency = com.bmf.bmfstown.entity.profile.EfficiencyMath.total(
                this.resident.getGrowth().skillXp(skill),
                this.resident.getStatus().mood(), false);
        return Math.max(0.1D, efficiency);
    }

    private boolean inWorkHours(ServerLevel level) {
        long t = level.getDayTime() % 24000L;
        int start = Config.SCHOLAR_WORK_START.get();
        int end = Config.SCHOLAR_WORK_END.get();
        return start <= end ? (t >= start && t < end) : (t >= start || t < end);
    }

    private Town townOf(ServerLevel level) {
        UUID townId = this.resident.getIdentity().townId();
        return townId == null ? null : TownSaveData.get(level).findTown(townId.toString());
    }

    private ServerLevel serverLevel() {
        return this.resident.level() instanceof ServerLevel sl ? sl : null;
    }
}
