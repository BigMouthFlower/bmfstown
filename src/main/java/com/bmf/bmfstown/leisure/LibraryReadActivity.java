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
 * 图书馆看闲书（闲暇活动，v0.10.3）：走到图书馆 → 从书架**真的取一本闲书**
 * → 主手拿书站着读 → 读完结算心情（批注本 1.5 倍）→ 把书放进**离他最近的
 * 书架空位**（就地入架；全满才留身上）。
 *
 * <p><b>只取闲书</b>——技能书的研读走知识页研读槽（与闲暇无关的另一条线，
 * 用户定稿的物理隔离）。
 *
 * <p><b>收尾是一条直线</b>（v0.10.3 实测修复：旧版在三个方法间倒腾
 * 主手/背包/字段，把书留在背包没还架）：清主手 → 最近空位就地入架 →
 * 全满才留背包 → 按"同物品同组件"从背包找回原主手换回。
 * <b>主手交换铁律</b>：拿书前原主手先进背包（绝不丢弃）；被打断
 * 当场还书（不结算心情），绝不占着书过夜。
 */
public class LibraryReadActivity implements LeisureActivity {

    private enum Phase { WALK_TO_SHELF, READ }

    private Phase phase;
    private BlockPos sourceShelf;
    private ItemStack heldBook = ItemStack.EMPTY;
    private ItemStack prevMainhand = ItemStack.EMPTY;
    private int readTicksLeft;
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

    /** 书架上一个"闲书"槽位（只取闲书——技能书走知识槽研读，物理隔离）；无则 -1。 */
    private int idleBookSlot(ChiseledBookShelfBlockEntity shelf) {
        for (int slot = 0; slot < shelf.getContainerSize(); slot++) {
            ItemStack stack = shelf.getItem(slot);
            var content = stack.get(com.bmf.bmfstown.ModDataComponents.BOOK_CONTENT.get());
            if (content != null && content.draft() instanceof com.bmf.bmfstown.book.BookDraft.Idle) {
                return slot;
            }
        }
        return -1;
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
        // 馆里得有书架且架上至少有一本闲书
        for (BlockPos pos : libraryShelves(level, resident)) {
            var shelf = (ChiseledBookShelfBlockEntity) level.getBlockEntity(pos);
            if (shelf != null && idleBookSlot(shelf) >= 0) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean start(Resident resident, ServerLevel level) {
        BlockPos best = null;
        int bestSlot = -1;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : libraryShelves(level, resident)) {
            var shelf = (ChiseledBookShelfBlockEntity) level.getBlockEntity(pos);
            if (shelf == null) {
                continue;
            }
            int slot = idleBookSlot(shelf);
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
        int slot = idleBookSlot(shelf);
        if (slot < 0) {
            return false; // 书刚被别人取走：本段换事
        }
        this.heldBook = shelf.removeItem(slot, 1);
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
        this.bubbleIn = 100 + resident.getRandom().nextInt(200);
        this.bubblesSaid = 0;
        this.phase = Phase.READ;
        return true;
    }

    private boolean tickRead(Resident resident, ServerLevel level) {
        resident.getNavigation().stop(); // 站着看书（用户定稿：先做成就不动）
        // ---- 看书气泡：偶尔浮一句书中的内容（词位动态补全）----
        if (--this.bubbleIn <= 0 && this.bubblesSaid < 2) {
            sayBookLine(resident);
            this.bubblesSaid++;
            this.bubbleIn = 240 + resident.getRandom().nextInt(240);
        }
        if (--this.readTicksLeft > 0) {
            return true;
        }
        return finishReading(resident, level, true);
    }

    /**
     * 收尾一条直线：
     * <ol>
     *   <li>结算心情（仅正常读完；被打断的 abort 不结算）；</li>
     *   <li>清主手 → 把书放进<b>离他最近的书架空位</b>（就地入架，不寻路）；</li>
     *   <li>全满 → 书进背包（用户定稿的兜底）；</li>
     *   <li>按"同物品同组件"从背包找回原主手物品换回。</li>
     * </ol>
     */
    private boolean finishReading(Resident resident, ServerLevel level, boolean settle) {
        if (settle) {
            var content = this.heldBook.get(com.bmf.bmfstown.ModDataComponents.BOOK_CONTENT.get());
            if (content != null && content.draft() instanceof com.bmf.bmfstown.book.BookDraft.Idle idle) {
                int mood = Config.LEISURE_MOOD_BONUS.get();
                if (content.annotated()) {
                    mood = Math.max(1, Math.round(mood * 1.5F)); // 批注本 1.5 倍（+5 → +8）
                }
                resident.getStatus().readEnjoyed(level, idle.seed(), mood);
            }
        }
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
        // 3. 全满：留身上（用户定稿的兜底）
        if (!placed) {
            resident.getInventory().addItem(book);
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

    /** 看书气泡：按书名词位填台词（"嗅探兽有意思"式动态补全）。 */
    private void sayBookLine(Resident resident) {
        var content = this.heldBook.get(com.bmf.bmfstown.ModDataComponents.BOOK_CONTENT.get());
        if (content == null || !(content.draft() instanceof com.bmf.bmfstown.book.BookDraft.Idle idle)) {
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
        // 被打断（天黑/开饭/上班）：当场还书、不结算心情——绝不占着书过夜
        if (this.phase == Phase.READ) {
            finishReading(resident, level, false);
        }
        resident.getNavigation().stop();
        this.phase = Phase.WALK_TO_SHELF;
        this.heldBook = ItemStack.EMPTY;
        this.prevMainhand = ItemStack.EMPTY;
    }
}
