package com.bmf.bmfstown.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * 归还待办执行 Goal（决策 #72，2026-11-17）。
 *
 * <p>背景：{@link JobKit#giveBack} 加了可达距离判定
 * （{@link JobKit#RETURN_REACH}）后，停工时人不在容器边就还不出货——
 * 旧版会"隔空放进去"（用户实测酒保在大厅停工、货进了十几格外的酒窖，
 * 开盖动画也在远处播，玩家看到的就是手一挥东西没了）。为守住
 * "下班不揣料"铁律又不违反物理真实，giveBack 够不着时挂
 * {@link Resident.PendingReturn}，本 Goal 负责走过去再还。
 *
 * <h2>运行节奏</h2>
 * <ol>
 *   <li>每 tick 先做<b>到位结算</b>：可达范围内的登记容器挨个卸
 *       （沿路路过箱子也顺手卸——接力归还的 Goal 版）；</li>
 *   <li>卸空 → 清待还、停导航，Goal 自然退出；</li>
 *   <li>还有剩 → 挑池子里最近的、没试死过的容器发导航（每 20t 重发，
 *       路径被门/障碍打断后自愈）；"收不进"（满箱）的记入失败名单，
 *       全部试死 = 池子全满，清待还留身上（#70 兜底口径）。</li>
 * </ol>
 *
 * <h2>优先级</h2>
 * 3：压过工作(4)与闲暇(5)——还完货再干别的；让位开门/逃跑(2)——
 * 先顾命，逃完回来接着还（待还在，Goal 会被重新选中）。
 *
 * <h2>生命周期注意</h2>
 * 待还任务仅内存态（不落盘）：存档重载后丢失，材料留在身上，
 * 下次 stop() 理货自然重试——与"全满留身上"同一兜底口径。
 */
public class ReturnCargoGoal extends Goal {

    private final Resident resident;

    /** 导航重发倒计时（tick）。 */
    private int retargetTimer;

    /** 当前导航目标（仅作导航用途；结算永远按实时距离算）。 */
    @Nullable
    private BlockPos dest;

    /**
     * 自上次成功送达以来"收不进"的容器（满箱）。卸进任何一格就清空——
     * 满是动态的（别人可能取走东西），重置后值得再试。
     */
    private final Set<BlockPos> failed = new HashSet<>();

    public ReturnCargoGoal(Resident resident) {
        this.resident = resident;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return this.resident.getPendingReturn() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.resident.getPendingReturn() != null;
    }

    @Override
    public void start() {
        this.retargetTimer = 0; // 立即发第一次导航
        this.dest = null;
        this.failed.clear();
    }

    @Override
    public void stop() {
        this.dest = null;
        this.resident.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        Resident.PendingReturn pending = this.resident.getPendingReturn();
        if (pending == null
                || !(this.resident.level() instanceof ServerLevel level)) {
            return;
        }

        // ---- 1. 到位结算：可达范围内的容器挨个卸（沿路路过也顺手卸） ----
        settleInRange(level, pending);

        if (!JobKit.hasMatching(this.resident.getInventory(), pending.filter())) {
            this.resident.clearPendingReturn();
            this.resident.getNavigation().stop();
            this.dest = null;
            return;
        }

        // ---- 2. 选下一个目标：最近的、没试死过的 ----
        BlockPos next = pickDest(pending);
        if (next == null) {
            // 池子全部试死（满/被拆）= #70 兜底口径：留身上，清待还
            this.resident.clearPendingReturn();
            this.resident.getNavigation().stop();
            this.dest = null;
            return;
        }
        this.dest = next;

        // ---- 3. 导航（每 20t 重发自愈；到位由下一 tick 的 settleInRange 结算） ----
        if (--this.retargetTimer <= 0) {
            this.retargetTimer = 20;
            this.resident.getNavigation().moveTo(
                    next.getX() + 0.5, next.getY(), next.getZ() + 0.5,
                    com.bmf.bmfstown.config.Speeds.normal());
        }
    }

    /** 可达范围内所有登记容器挨个卸；收不进的记失败名单，送达的清名单。 */
    private void settleInRange(ServerLevel level, Resident.PendingReturn pending) {
        double reachSqr = JobKit.RETURN_REACH * JobKit.RETURN_REACH;
        for (BlockPos pos : sortedByDistance(pending.pool())) {
            if (!JobKit.hasMatching(this.resident.getInventory(), pending.filter())) {
                return; // 卸空了
            }
            if (pos.distSqr(this.resident.blockPosition()) > reachSqr) {
                break; // 后面只会更远
            }
            Container target = JobKit.containerAt(level, pos);
            if (target == null) {
                this.failed.add(pos); // 被拆了：当次会话内别再选它
                continue;
            }
            int before = JobKit.countMatching(this.resident.getInventory(), pending.filter());
            JobKit.unloadInto(target, this.resident.getInventory(), pending.filter());
            if (JobKit.countMatching(this.resident.getInventory(), pending.filter()) < before) {
                this.resident.containerFxOpenThenClose(level, pos, 10);
                this.failed.remove(pos);
            } else {
                this.failed.add(pos); // 收不进（满箱）
            }
        }
    }

    /** 池子里距离最近的、没试死过的容器；全试死返回 null。 */
    @Nullable
    private BlockPos pickDest(Resident.PendingReturn pending) {
        for (BlockPos pos : sortedByDistance(pending.pool())) {
            if (!this.failed.contains(pos)) {
                return pos;
            }
        }
        return null;
    }

    private java.util.List<BlockPos> sortedByDistance(java.util.List<BlockPos> pool) {
        java.util.List<BlockPos> sorted = new ArrayList<>(pool);
        sorted.sort(Comparator.comparingDouble(p -> p.distSqr(this.resident.blockPosition())));
        return sorted;
    }
}
