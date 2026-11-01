package com.bmf.bmfstown.entity;

import com.bmf.bmfstown.book.BookContent;
import com.bmf.bmfstown.book.BookDraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * 阅读行为（v0.11）：镇民闲逛时偶尔走到雕纹书架前"读书"——
 * <ul>
 *   <li>架上书有 {@code BOOK_CONTENT}（学者的成书）：闲书 → 图一乐
 *       （心情表现后续接需求/记忆线）；职业强化/解锁书 → **装备进书槽**
 *       开始研读（书槽唯一，后读的替前读的）；</li>
 *   <li>架上是普通原版书：当作翻翻看（无效果）。</li>
 * </ul>
 * 稀有启动（每次判定 1/12），与漫步同优先级——图书馆有了书才会有人来读。
 */
public class ReadingGoal extends Goal {

    private static final double READ_RANGE = 24.0D;
    private static final int READ_TICKS = 80;

    private final Resident resident;
    private BlockPos shelfPos;
    private int readTimer;

    public ReadingGoal(Resident resident) {
        this.resident = resident;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (this.resident.getIdentity().townId() == null
                || this.resident.getRandom().nextInt(12) != 0) {
            return false;
        }
        ServerLevel level = this.serverLevel();
        if (level == null) {
            return false;
        }
        this.shelfPos = findShelf(level);
        return this.shelfPos != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.readTimer > 0;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.readTimer = READ_TICKS;
        this.resident.getNavigation().moveTo(
                this.shelfPos.getX() + 0.5, this.shelfPos.getY(),
                this.shelfPos.getZ() + 0.5, 0.9D);
    }

    @Override
    public void tick() {
        ServerLevel level = this.serverLevel();
        if (level == null || this.shelfPos == null) {
            this.readTimer = 0;
            return;
        }
        double dist = this.resident.distanceToSqr(
                this.shelfPos.getX() + 0.5, this.shelfPos.getY(),
                this.shelfPos.getZ() + 0.5);
        if (dist > 4.0D) {
            this.resident.getNavigation().moveTo(
                    this.shelfPos.getX() + 0.5, this.shelfPos.getY(),
                    this.shelfPos.getZ() + 0.5, 0.9D);
            return;
        }
        this.readTimer--;
        if (this.readTimer == 0) {
            readShelf(level); // 读完了
        }
    }

    /** 读架上随机一本：特殊书装备进书槽；其余图一乐（效果线后续接入）。 */
    private void readShelf(ServerLevel level) {
        BlockEntity be = level.getBlockEntity(this.shelfPos);
        if (!(be instanceof ChiseledBookShelfBlockEntity shelf)) {
            return;
        }
        List<Integer> filled = new ArrayList<>();
        for (int slot = 0; slot < shelf.getContainerSize(); slot++) {
            if (!shelf.getItem(slot).isEmpty()) {
                filled.add(slot);
            }
        }
        if (filled.isEmpty()) {
            return;
        }
        ItemStack book = shelf.getItem(
                filled.get(this.resident.getRandom().nextInt(filled.size())));
        BookContent content = book.get(com.bmf.bmfstown.ModDataComponents.BOOK_CONTENT.get());
        if (content == null) {
            return; // 原版普通书：翻翻看
        }
        switch (content.draft()) {
            case BookDraft.Perk p ->
                    this.resident.getKnowledge().equip(p.perkId()); // 书槽唯一：后读替前读
            case BookDraft.Unlock u ->
                    this.resident.getKnowledge().equip(u.unlockId());
            case BookDraft.Idle i -> {
                // 图一乐（用户定稿）：心情 +5 + 想法"他读了《XX》，感觉很有意思"
                if (level != null) {
                    this.resident.getStatus().readEnjoyed(level, i.seed());
                }
            }
        }
    }

    /** 找附近雕纹书架（盒式扫描取最近；canUse 时扫一次，结果缓存到实例）。 */
    private BlockPos findShelf(ServerLevel level) {
        BlockPos center = this.resident.blockPosition();
        BlockPos best = null;
        double bestDist = READ_RANGE * READ_RANGE;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-READ_RANGE_I, -4, -READ_RANGE_I),
                center.offset(READ_RANGE_I, 4, READ_RANGE_I))) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() == Blocks.CHISELED_BOOKSHELF) {
                double d = pos.distSqr(center);
                if (d < bestDist) {
                    bestDist = d;
                    best = pos.immutable();
                }
            }
        }
        return best;
    }

    private static final int READ_RANGE_I = 24;

    private ServerLevel serverLevel() {
        return this.resident.level() instanceof ServerLevel sl ? sl : null;
    }
}
