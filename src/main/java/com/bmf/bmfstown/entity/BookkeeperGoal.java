package com.bmf.bmfstown.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * 簿记员的"带路"Goal（v0.8.1）：走到目标容器旁，对它撒 10 秒绿色粒子
 * （原版堆肥"施肥"粒子），玩家跟着走就能找到东西。
 *
 * <h2>触发</h2>
 * 面板查询弹层里点了某个物品 → 服务端在簿记员身上挂目标（
 * {@link Resident#setBookkeeperFindTarget}），本 Goal 的 canUse 立即激活。
 * 到达并撒完粒子后自动清目标收工。
 *
 * <h2>边界</h2>
 * 带路是一次性差事：不挡吃饭睡觉（睡眠 Goal 照常），觅食优先；
 * 玩家跟不上就自己看 F3 坐标——簿记员不等人的（v1 简化）。
 */
public class BookkeeperGoal extends Goal {

    /** 到达判定距离（格）。 */
    private static final double ARRIVE_RANGE = 3.0D;

    /** 到达后撒粒子的时长（tick）：10 秒。 */
    private static final int MARK_TICKS = 200;

    /** 撒粒子的间隔（tick）。 */
    private static final int PARTICLE_INTERVAL = 5;

    private final Resident resident;
    private boolean running;
    private int markTimer;
    private int particleCooldown;

    /** 正在执行的差事目标：撒粒子途中被换单时用于识别并丢弃旧进度。 */
    @Nullable
    private BlockPos activeTarget;

    public BookkeeperGoal(Resident resident) {
        this.resident = resident;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return this.resident.getProfession()
                == com.bmf.bmfstown.entity.profile.ProfessionType.BOOKKEEPER
                && this.resident.getBookkeeperFindTarget() != null
                // 夜里被点单时服务端会先 wakeUpForErrand() 叫醒（用户定稿：叫醒就去带路）
                && !this.resident.isSleeping()
                && !this.resident.isForaging();
    }

    @Override
    public boolean canContinueToUse() {
        return this.resident.getBookkeeperFindTarget() != null
                && this.resident.getProfession()
                == com.bmf.bmfstown.entity.profile.ProfessionType.BOOKKEEPER;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.running = true;
        // 清残留计时器：上次标记中途被顶会留下 markTimer，
        // 不清的话新差事直接跳进"撒粒子"分支原地站桩（v0.8.1 实测 bug）
        this.markTimer = 0;
        this.particleCooldown = 0;
        BlockPos target = this.resident.getBookkeeperFindTarget();
        this.activeTarget = target;
        com.mojang.logging.LogUtils.getLogger().info(
                "[BmfTown][簿记] {} 接单出发 → {}（开始计算路径）",
                this.resident.getName().getString(), target.toShortString());
        this.resident.getNavigation().moveTo(
                target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D,
                com.bmf.bmfstown.config.Config.STROLL_SPEED.get());
        com.mojang.logging.LogUtils.getLogger().debug(
                "[BmfTown][簿记] {} 带路出发 → {}", this.resident.getName().getString(),
                target.toShortString());
    }

    @Override
    public void stop() {
        if (this.running) {
            com.mojang.logging.LogUtils.getLogger().info(
                    "[BmfTown][簿记] {} 带路结束（撒粒子剩余={} tick）",
                    this.resident.getName().getString(), Math.max(this.markTimer, 0));
        }
        this.running = false;
    }

    @Override
    public void tick() {
        BlockPos target = this.resident.getBookkeeperFindTarget();
        if (target == null) {
            return;
        }
        ServerLevel level = (ServerLevel) this.resident.level();

        // 撒粒子途中被换单（玩家又点了新物品）：Goal 没有重启，残留的
        // markTimer 会朝新目标撒粒子并用 navigation.stop() 把人钉在原地——
        // "先亮绿光几秒后才走"实测根因。目标一变立即丢弃旧进度重新走。
        if (!target.equals(this.activeTarget)) {
            this.activeTarget = target;
            this.markTimer = 0;
            this.particleCooldown = 0;
            this.resident.getNavigation().moveTo(
                    target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D,
                    com.bmf.bmfstown.config.Config.STROLL_SPEED.get());
            com.mojang.logging.LogUtils.getLogger().info(
                    "[BmfTown][簿记] {} 换单 → {}（重新出发）",
                    this.resident.getName().getString(), target.toShortString());
        }

        if (markTimer > 0) {
            // 已到达：持续撒施肥绿粒子
            if (markTimer == MARK_TICKS - 1) {
                com.mojang.logging.LogUtils.getLogger().info(
                        "[BmfTown][簿记] {} 到达目标，开始标记", this.resident.getName().getString());
            }
            markTimer--;
            if (--this.particleCooldown <= 0) {
                this.particleCooldown = PARTICLE_INTERVAL;
                level.sendParticles(ParticleTypes.COMPOSTER,
                        target.getX() + 0.5D, target.getY() + 0.6D, target.getZ() + 0.5D,
                        3, 0.3D, 0.3D, 0.3D, 0.0D);
            }
            this.resident.getNavigation().stop();
            if (markTimer <= 0) {
                this.resident.setBookkeeperFindTarget(null); // 差事完成
            }
            return;
        }

        // 前往目标容器
        if (this.resident.blockPosition().closerThan(target, 3.0D)) {
            this.resident.getNavigation().stop();
            this.markTimer = MARK_TICKS;
            this.particleCooldown = 0;
            return;
        }
        if (this.resident.getNavigation().isDone()) {
            this.resident.getNavigation().moveTo(
                    target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D,
                    com.bmf.bmfstown.config.Config.STROLL_SPEED.get());
        }
    }
}
