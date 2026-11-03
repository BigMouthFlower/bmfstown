package com.bmf.bmfstown.leisure;

import com.bmf.bmfstown.config.Config;
import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.town.Town;
import com.bmf.bmfstown.town.TownSaveData;
import com.bmf.bmfstown.town.TownZone;
import com.bmf.bmfstown.town.TownWandMode;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 图书馆看书（闲暇活动，v0.10.4 学识制）：走到图书馆 → 从书架**真的取一本书**
 * → 主手拿书站着读 → 读完/学成结算 → 把书放进**离他最近的书架空位**（就地
 * 入架；全满才留身上）。
 *
 * <h2>双书型（用户定稿：图书馆是唯一读书场所）</h2>
 * <ul>
 *   <li><b>技能书（特殊书）优先</b>：架上有"没学过且学识够"的特殊书就取它——
 *       阅读逐 tick 累计进 {@code KnowledgeState}（游戏刻、跨段续读、
 *       批注本 1.5 倍速），读满 learnTicks 学成：扣学识、进已学列表、
 *       单选生效；书不消耗，全镇人都能学；</li>
 *   <li><b>闲书</b>：没有可学的技能书时取——读完 600t 结算心情
 *       （批注本 ×1.5）+ 学识 +1（识字教材）。</li>
 * </ul>
 * 学识循环：闲书 +1 / 技能书 −5（{@code leisure.literacyCost}）——
 * 两种书结构性互锁，货架经济不会倒向任何一边。
 *
 * <p><b>收尾是一条直线</b>（v0.10.3 实测修复）：清主手 → 最近空位就地入架 →
 * 全满才留背包 → 按"同物品同组件"从背包找回原主手换回。<b>主手交换铁律</b>：
 * 拿书前原主手先进背包（绝不丢弃）；被打断当场还书（技能书进度已按 tick
 * 落档，不丢），绝不占着书过夜。
 */
public class LibraryReadActivity implements LeisureActivity {

    private enum Phase { WALK_TO_SHELF, READ }

    private Phase phase;
    private BlockPos sourceShelf;
    private ItemStack heldBook = ItemStack.EMPTY;
    /** 手里这本书是否为技能书（特殊书）；闲书 false。 */
    private boolean skillBook;
    /** 技能书的登记表 id（闲书为 null）。 */
    private String skillBookId;
    private ItemStack prevMainhand = ItemStack.EMPTY;
    /** 闲书的倒计时（600t，读完结算）。 */
    private int readTicksLeft;
    /**
     * 本段休闲的剩余时长（v0.10.4.1 用户定稿：技能书**同样受段限制**——
     * 到点还书，进度已按 tick 落档，下次接着读；此前误做成"读到学成或
     * 窗口关闭"，周末会站一整天猛读）。
     */
    private int sessionTicksLeft;
    private int bubbleIn;
    private int bubblesSaid;

    @Override
    public String toString() {
        return "library_read";
    }

    // ------------------------------------------------------------
    //  书架扫描（图书馆区 = WORK 选区，与学者共用空间）
    // ------------------------------------------------------------

    private List<BlockPos> libraryShelves(ServerLevel level, Resident resident) {
        List<BlockPos> out = new ArrayList<>();
        UUID townId = resident.getIdentity().townId();
        if (townId == null) {
            return out;
        }
        Town town = TownSaveData.get(level).findTown(townId.toString());
        if (town == null) {
            return out;
        }
        for (TownZone zone : town.zones(TownWandMode.WORK)) {
            for (BlockPos pos : TownZone.iterate(zone)) {
                if (level.getBlockEntity(pos) instanceof ChiseledBookShelfBlockEntity) {
                    out.add(pos.immutable());
                }
            }
        }
        return out;
    }

    /**
     * 书架上"可学技能书"的最佳槽位（未学过 + 学识够；进度最深的优先续读）；
     * 无则 -1。
     */
    private int skillSlot(ChiseledBookShelfBlockEntity shelf, Resident resident) {
        var knowledge = resident.getKnowledge();
        int cost = Config.LEISURE_LITERACY_COST.get();
        int bestSkill = -1;
        long bestProgress = -1L;
        for (int slot = 0; slot < shelf.getContainerSize(); slot++) {
            ItemStack stack = shelf.getItem(slot);
            var content = stack.get(com.bmf.bmfstown.ModDataComponents.BOOK_CONTENT.get());
            if (content == null) {
                continue;
            }
            String id = content.draft().bookId();
            if (id == null
                    || knowledge.isLearned(id)
                    || com.bmf.bmfstown.book.SpecialBookRegistry.byId(id) == null
                    || knowledge.literacy() < cost) {
                continue;
            }
            long progress = knowledge.progressOf(id);
            if (progress > bestProgress) {
                bestProgress = progress;
                bestSkill = slot;
            }
        }
        return bestSkill;
    }

    /** 书架上第一本"没读过的闲书"槽位；无则 -1（读过的不再取）。 */
    private int idleSlot(ChiseledBookShelfBlockEntity shelf, Resident resident) {
        var knowledge = resident.getKnowledge();
        for (int slot = 0; slot < shelf.getContainerSize(); slot++) {
            ItemStack stack = shelf.getItem(slot);
            var content = stack.get(com.bmf.bmfstown.ModDataComponents.BOOK_CONTENT.get());
            if (content != null && content.draft() instanceof com.bmf.bmfstown.book.BookDraft.Idle idle
                    && !knowledge.hasReadSeed(idle.seed())) {
                return slot;
            }
        }
        return -1;
    }

    /** 单架取书：技能书优先（学识够才会到这），否则未读闲书；都没有 -1。 */
    private int pickSlot(ChiseledBookShelfBlockEntity shelf, Resident resident) {
        int skill = skillSlot(shelf, resident);
        return skill >= 0 ? skill : idleSlot(shelf, resident);
    }

    /** 离 from 最近的、有空位的书架（还书用）。 */
    private BlockPos nearestShelfWithSpace(ServerLevel level, Resident resident, BlockPos from) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : libraryShelves(level, resident)) {
            var shelf = (ChiseledBookShelfBlockEntity) level.getBlockEntity(pos);
            boolean hasSpace = false;
            for (int slot = 0; slot < shelf.getContainerSize(); slot++) {
                if (shelf.getItem(slot).isEmpty()) {
                    hasSpace = true;
                    break;
                }
            }
            if (!hasSpace) {
                continue;
            }
            double d = from.distSqr(pos);
            if (d < bestDist) {
                bestDist = d;
                best = pos;
            }
        }
        return best;
    }

    // ------------------------------------------------------------
    //  生命周期
    // ------------------------------------------------------------

    @Override
    public boolean canStart(Resident resident, ServerLevel level) {
        // 馆里得有书架且架上至少有一本"值得读"的书
        for (BlockPos pos : libraryShelves(level, resident)) {
            var shelf = (ChiseledBookShelfBlockEntity) level.getBlockEntity(pos);
            if (shelf != null && pickSlot(shelf, resident) >= 0) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean start(Resident resident, ServerLevel level) {
        // ---- 全馆两遍扫描（v0.10.4.1 修复）：技能书优先于**全馆**，而不是
        // "最近的架子优先"——旧逻辑在最近的书架上拿到闲书就直接读，
        // 特殊书放在稍远的架子时永远轮不到（用户 20:54 实测踩中）。
        BlockPos best = null;
        int bestSlot = -1;
        double bestDist = Double.MAX_VALUE;
        // 第一遍：全馆找"可学的技能书"（最近的；同架内进度最深优先）
        for (BlockPos pos : libraryShelves(level, resident)) {
            var shelf = (ChiseledBookShelfBlockEntity) level.getBlockEntity(pos);
            if (shelf == null) {
                continue;
            }
            int slot = skillSlot(shelf, resident);
            if (slot < 0) {
                continue;
            }
            double d = resident.blockPosition().distSqr(pos);
            if (d < bestDist) {
                bestDist = d;
                best = pos;
                bestSlot = slot;
            }
        }
        // 第二遍：没有可学的技能书 → 最近的未读闲书
        if (best == null) {
            for (BlockPos pos : libraryShelves(level, resident)) {
                var shelf = (ChiseledBookShelfBlockEntity) level.getBlockEntity(pos);
                if (shelf == null) {
                    continue;
                }
                int slot = idleSlot(shelf, resident);
                if (slot < 0) {
                    continue;
                }
                double d = resident.blockPosition().distSqr(pos);
                if (d < bestDist) {
                    bestDist = d;
                    best = pos;
                    bestSlot = slot;
                }
            }
        }
        if (best == null) {
            return false;
        }
        this.sourceShelf = best;
        this.phase = Phase.WALK_TO_SHELF;
        resident.getNavigation().moveTo(
                best.getX() + 0.5D, best.getY(), best.getZ() + 0.5D, 0.9D);
        return true;
    }

    @Override
    public boolean tick(Resident resident, ServerLevel level) {
        return switch (this.phase) {
            case WALK_TO_SHELF -> tickWalkToShelf(resident, level);
            case READ -> tickRead(resident, level);
        };
    }

    private boolean tickWalkToShelf(Resident resident, ServerLevel level) {
        if (!(level.getBlockEntity(this.sourceShelf)
                instanceof ChiseledBookShelfBlockEntity shelf)) {
            return false; // 书架没了：本段换事
        }
        if (!resident.blockPosition().closerThan(this.sourceShelf, 2.5D)) {
            if (resident.getNavigation().isDone()) {
                resident.getNavigation().moveTo(
                        this.sourceShelf.getX() + 0.5D, this.sourceShelf.getY(),
                        this.sourceShelf.getZ() + 0.5D, 0.9D);
            }
            return true;
        }
        // ---- 取书：走原版 removeItem（自带 updateState，架上外观同步）----
        // 到达时重选（书可能刚被别人取走/学成状态变了）
        int slot = pickSlot(shelf, resident);
        if (slot < 0) {
            return false; // 没书可取：本段换事
        }
        // ---- 孤儿书回收（v0.10.4.1）：上次会话可能因存档/区块卸载中断，
        //      书滞留在主手（活动状态是瞬态的，装备却随实体落档）——
        //      开新一轮前先送回最近书架（架满进背包），绝不当成"原主手
        //      物品"在背包-主手之间无限流转（用户 21:16 报告的书滞留）----
        ItemStack orphan = resident.getMainHandItem();
        if (!orphan.isEmpty()
                && orphan.get(com.bmf.bmfstown.ModDataComponents.BOOK_CONTENT.get()) != null) {
            boolean shelved = false;
            BlockPos orphanTarget = nearestShelfWithSpace(level, resident, resident.blockPosition());
            if (orphanTarget != null && level.getBlockEntity(orphanTarget)
                    instanceof ChiseledBookShelfBlockEntity orphanShelf) {
                for (int os = 0; os < orphanShelf.getContainerSize(); os++) {
                    if (orphanShelf.getItem(os).isEmpty()) {
                        orphanShelf.setItem(os, orphan);
                        shelved = !orphanShelf.getItem(os).isEmpty();
                        break;
                    }
                }
            }
            if (!shelved) {
                resident.getInventory().addItem(orphan);
            }
            resident.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            com.bmf.bmfstown.BmfTown.LOGGER.info("[BmfTown][休闲] {} 回收了手中滞留的书（上次会话中断残留）",
                    resident.getName().getString());
        }
        this.heldBook = shelf.removeItem(slot, 1);
        var content = this.heldBook.get(com.bmf.bmfstown.ModDataComponents.BOOK_CONTENT.get());
        this.skillBookId = content == null ? null : content.draft().bookId();
        this.skillBook = this.skillBookId != null;
        // ---- 主手交换：原主手进背包，放不下 = 本次放弃，书回架 ----
        this.prevMainhand = resident.getMainHandItem().copy();
        if (!this.prevMainhand.isEmpty()
                && !resident.getInventory().addItem(this.prevMainhand).isEmpty()) {
            shelf.setItem(slot, this.heldBook); // 放回（原版 setItem 校验标签+updateState）
            this.heldBook = ItemStack.EMPTY;
            this.prevMainhand = ItemStack.EMPTY;
            return false;
        }
        resident.setItemInHand(InteractionHand.MAIN_HAND, this.heldBook);
        resident.getNavigation().stop();
        this.readTicksLeft = Config.LEISURE_IDLE_BOOK_READ.get();
        int sMin = Config.LEISURE_SESSION_MIN.get();
        int sMax = Math.max(sMin, Config.LEISURE_SESSION_MAX.get());
        this.sessionTicksLeft = sMin + resident.getRandom().nextInt(Math.max(1, sMax - sMin + 1));
        this.bubbleIn = 100 + resident.getRandom().nextInt(200);
        this.bubblesSaid = 0;
        this.phase = Phase.READ;
        return true;
    }

    private boolean tickRead(Resident resident, ServerLevel level) {
        resident.getNavigation().stop(); // 站着看书（用户定稿：先做成就不动）
        // ---- 看书气泡：偶尔浮一句（闲书=词位动态补全；技能书=书名感慨）----
        if (--this.bubbleIn <= 0 && this.bubblesSaid < 2) {
            sayBookLine(resident);
            this.bubblesSaid++;
            this.bubbleIn = 240 + resident.getRandom().nextInt(240);
        }
        // ---- 段限制（对两类书都生效；闲书 600t 必在段内读完，先到先结算）----
        if (--this.sessionTicksLeft <= 0) {
            return finishReading(resident, level); // 段到：还书（技能书进度已落档，不结算）
        }
        if (this.skillBook) {
            // ---- 技能书：逐 tick 累计（批注本 1.5 倍速），学成即结算 ----
            double gain = this.heldBook.get(com.bmf.bmfstown.ModDataComponents.BOOK_CONTENT.get())
                    .annotated() ? 1.5D : 1.0D;
            if (resident.getKnowledge().progressSkillBook(this.skillBookId, gain)) {
                if (resident.getKnowledge().onSkillBookFinished(
                        this.skillBookId, Config.LEISURE_LITERACY_COST.get())) {
                    resident.getStatus().skillBookLearned(level, this.skillBookId);
                    // 学会广播（每次都播）+ 发现兜底（管理员刷书等未走过的边缘）
                    com.bmf.bmfstown.book.BookBroadcast.discovered(
                            level, resident.getIdentity().townId(), this.skillBookId);
                    com.bmf.bmfstown.book.BookBroadcast.learned(
                            level, resident.getName().getString(), this.skillBookId);
                    // 隐藏解锁书：学成即触发该镇解锁广播（与学者写成同一路径）
                    var book = com.bmf.bmfstown.book.SpecialBookRegistry.byId(this.skillBookId);
                    if (book != null && book.draft() instanceof com.bmf.bmfstown.book.BookDraft.Unlock u) {
                        com.bmf.bmfstown.unlock.UnlockState.get(level).unlockIfNew(
                                level, resident.getIdentity().townId(), u.unlockId());
                    }
                    return finishReading(resident, level);
                }
            }
            return true; // 没读完：继续读到窗口关闭（进度已按 tick 落档）
        }
        // ---- 闲书：倒计时读完结算 ----
        if (--this.readTicksLeft > 0) {
            return true;
        }
        var content = this.heldBook.get(com.bmf.bmfstown.ModDataComponents.BOOK_CONTENT.get());
        if (content != null && content.draft() instanceof com.bmf.bmfstown.book.BookDraft.Idle idle) {
            int mood = Config.LEISURE_MOOD_BONUS.get();
            if (content.annotated()) {
                mood = Math.max(1, Math.round(mood * 1.5F)); // 批注本 1.5 倍（+5 → +8）
            }
            resident.getStatus().readEnjoyed(level, idle.seed(), mood);
            resident.getKnowledge().onIdleBookFinished(idle.seed()); // 学识+1 + 总阅读数+1 + 记入"读过"
        }
        return finishReading(resident, level);
    }

    /**
     * 收尾一条直线（结算已由调用方完成，这里只管还书）：
     * 清主手 → 把书放进<b>离他最近的书架空位</b>（就地入架，不寻路）→
     * 全满 → 书进背包（用户定稿的兜底）→ 按"同物品同组件"从背包找回
     * 原主手物品换回。
     */
    private boolean finishReading(Resident resident, ServerLevel level) {
        // 1. 清主手（书从这里拿下来）
        ItemStack book = resident.getMainHandItem();
        resident.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        if (book.isEmpty()) {
            book = this.heldBook; // 兜底：主手已空就用记录的那本
        }
        // 2. 最近的书架空位就地入架（原版 setItem 校验 bookshelf_books + updateState）
        boolean placed = false;
        BlockPos target = nearestShelfWithSpace(level, resident, resident.blockPosition());
        if (target != null && level.getBlockEntity(target)
                instanceof ChiseledBookShelfBlockEntity shelf) {
            for (int slot = 0; slot < shelf.getContainerSize(); slot++) {
                if (shelf.getItem(slot).isEmpty()) {
                    shelf.setItem(slot, book);
                    placed = !shelf.getItem(slot).isEmpty();
                    break;
                }
            }
        }
        // 3. 全满：留身上（用户定稿的兜底）——打日志以便与孤儿书区分
        if (!placed) {
            resident.getInventory().addItem(book);
            com.bmf.bmfstown.BmfTown.LOGGER.info("[BmfTown][休闲] {} 所在位置书架全满，一本 {} 暂留身上",
                    resident.getName().getString(), book.getItem().toString());
        }
        this.heldBook = ItemStack.EMPTY;
        // 4. 换回原主手
        restorePrevMainhand(resident);
        this.prevMainhand = ItemStack.EMPTY;
        return false; // 活动结束
    }

    /** 按"同物品同组件"从背包找回原主手物品换回；找不回就空手并记日志。 */
    private void restorePrevMainhand(Resident resident) {
        if (this.prevMainhand.isEmpty()) {
            return;
        }
        var inv = resident.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack candidate = inv.getItem(i);
            if (ItemStack.isSameItemSameComponents(candidate, this.prevMainhand)
                    && candidate.getCount() >= 1) {
                resident.setItemInHand(InteractionHand.MAIN_HAND, inv.removeItem(i, 1));
                return;
            }
        }
        com.bmf.bmfstown.BmfTown.LOGGER.warn("[BmfTown][休闲] {} 原主手物品未找回（应为 {}）",
                resident.getName().getString(), this.prevMainhand.getItem());
    }

    /** 看书气泡：闲书按书名词位填台词；技能书对着书名感慨。 */
    private void sayBookLine(Resident resident) {
        var content = this.heldBook.get(com.bmf.bmfstown.ModDataComponents.BOOK_CONTENT.get());
        if (content == null) {
            return;
        }
        if (!(content.draft() instanceof com.bmf.bmfstown.book.BookDraft.Idle idle)) {
            // 技能书：书名感慨（登记表 lang 键嵌套解析）
            String id = content.draft().bookId();
            if (id != null) {
                Component line = Component.translatable("dialog.bmfstown.reading.study",
                        Component.translatable("book.bmfstown.special." + id + ".name"));
                com.bmf.bmfstown.social.DialogEngine.sayRaw(resident, line.getString());
            }
            return;
        }
        var words = com.bmf.bmfstown.book.BookLexicon.deconstruct(idle.seed());
        Component line = switch (words.template()) {
            case 0 -> Component.translatable("dialog.bmfstown.reading.line.0",
                    words.word(0), words.word(1));
            case 1 -> Component.translatable("dialog.bmfstown.reading.line.1",
                    words.word(0), words.word(1));
            case 2 -> Component.translatable("dialog.bmfstown.reading.line.2",
                    words.word(0));
            case 3 -> Component.translatable("dialog.bmfstown.reading.line.3",
                    words.word(0), words.word(1));
            case 4 -> Component.translatable("dialog.bmfstown.reading.line.4",
                    words.word(0), words.word(1));
            default -> Component.translatable("dialog.bmfstown.reading.line.5",
                    words.word(0), words.word(1), words.word(2));
        };
        com.bmf.bmfstown.social.DialogEngine.sayRaw(resident, line.getString());
    }

    @Override
    public void abort(Resident resident, ServerLevel level) {
        // 被打断（天黑/开饭/上班）：当场还书、不结算——绝不占着书过夜。
        // 技能书的进度已逐 tick 落档（KnowledgeState），下次接着读。
        if (this.phase == Phase.READ) {
            finishReading(resident, level);
        }
        resident.getNavigation().stop();
        this.phase = Phase.WALK_TO_SHELF;
        this.heldBook = ItemStack.EMPTY;
        this.prevMainhand = ItemStack.EMPTY;
    }
}
