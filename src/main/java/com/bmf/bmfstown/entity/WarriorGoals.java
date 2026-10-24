package com.bmf.bmfstown.entity;

import com.bmf.bmfstown.config.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;

/**
 * 战士工作模式的三个 AI 目标。
 *
 * <h2>分工</h2>
 * <ul>
 *   <li>{@link FollowPlayerGoal}（goalSelector 4）：跟随指派他的玩家，
 *       距离太远直接传送归队，允许离开领地</li>
 *   <li>{@link GuardPostGoal}（goalSelector 4）：在岗哨方块周围巡逻，
 *       越界回岗</li>
 *   <li>{@link MonsterTargetGoal}（targetSelector 2）：以锚点为中心扫描
 *       半径内的敌对生物并设为攻击目标；移动交给原版 {@code MeleeAttackGoal}</li>
 * </ul>
 *
 * <h2>互斥与失效</h2>
 * 两个移动 Goal 的 {@code canUse} 都先查 {@code workMode}，同一时刻只有
 * 一个能启动；工作模式被清掉（{@link WorkMode#NONE}）时 Goal 自然停转。
 * 锚点消失（玩家下线/岗哨数据为空）同样会让 Goal 立即退出。
 *
 * <h2>日志级别约定</h2>
 * 索敌链路的常态输出全部走 {@code DEBUG}——正常游戏时不能刷屏。
 * 需要排查"某个战士不打人"时，把 {@code com.bmf.bmfstown} 日志级别调到
 * DEBUG，就能看到每个判定点的结论（为什么不打、卡在哪一步）。
 * 只有"该打却没打"的异常分支才用 {@code INFO}（见 {@link #logAnomaly}）。
 */
public final class WarriorGoals {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private WarriorGoals() {
    }

    /** 工作锚点：站岗 = 岗哨位置；跟随 = 玩家位置；无工作 = null。 */
    private static BlockPos anchor(Resident resident) {
        return switch (resident.getWorkMode()) {
            case GUARD_POST -> resident.getGuardPost();
            case FOLLOW -> {
                Player p = followedPlayer(resident);
                yield p != null ? p.blockPosition() : null;
            }
            case NONE -> null;
        };
    }

    /** 索敌半径：站岗用警戒半径，跟随用同半径（都在锚点周围）。 */
    private static double aggroRadius(Resident resident) {
        return Config.WARRIOR_GUARD_RADIUS.get();
    }

    /** 跟随目标的玩家（已校验维度一致）；不在本维度/已下线返回 null。 */
    static Player followedPlayer(Resident resident) {
        if (!(resident.level() instanceof ServerLevel level) || resident.getGuardFollowPlayerId() == null) {
            return null;
        }
        ServerPlayer player = level.getServer().getPlayerList()
                .getPlayer(resident.getGuardFollowPlayerId());
        return player != null && player.level() == level ? player : null;
    }

    // ==========================================================
    //  跟随玩家
    // ==========================================================

    static class FollowPlayerGoal extends Goal {

        private final Resident resident;

        FollowPlayerGoal(Resident resident) {
            this.resident = resident;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return this.resident.getWorkMode() == WorkMode.FOLLOW
                    && followedPlayer(this.resident) != null;
        }

        @Override
        public boolean canContinueToUse() {
            return this.canUse();
        }

        @Override
        public void start() {
            this.resident.getNavigation().stop();
        }

        @Override
        public void stop() {
            this.resident.getNavigation().stop();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            Player player = followedPlayer(this.resident);
            if (player == null) {
                return;
            }
            double dist = this.resident.distanceTo(player);
            int teleportDist = Config.WARRIOR_TELEPORT_DIST.get();
            if (dist > teleportDist) {
                // 掉队太远（玩家疾跑/传送）：直接挪过去，别让他一路追着看
                LOGGER.info("[BmfTown][跟随] {} 距离 {} 超出传送阈值 {}，传送归队",
                        this.resident.getName().getString(),
                        String.format("%.1f", dist), teleportDist);
                this.resident.teleportTo(player.getX(), player.getY(), player.getZ());
                this.resident.getNavigation().stop();
            } else if (dist > 3.0D) {
                // 战斗中距离不算离谱时把移动权让给 MeleeAttackGoal（优先级 3）：
                // 否则这里每 tick 一次 moveTo 会把战士从敌人身边拉回玩家脚下，
                // 造成"追着追着突然回头"的抖动。但玩家走远了仍要跟上，
                // 不能为了打架把人丢在原地。
                double chaseLimit = Config.attackReachOrDefault() * 1.5D;
                if (this.resident.getTarget() != null && dist <= chaseLimit) {
                    LOGGER.debug("[BmfTown][跟随] {} 交战中（目标 {}），原地作战，距离玩家 {} 格",
                            this.resident.getName().getString(),
                            this.resident.getTarget().getType().toShortString(),
                            String.format("%.1f", dist));
                    return;
                }
                this.resident.getNavigation().moveTo(player,
                        Config.WARRIOR_FOLLOW_SPEED.get());
                LOGGER.debug("[BmfTown][跟随] {} 追向玩家（距离 {}，速度 {}）{}",
                        this.resident.getName().getString(),
                        String.format("%.1f", dist), Config.WARRIOR_FOLLOW_SPEED.get(),
                        this.resident.getTarget() != null ? "，同时带着目标" : "");
            } else {
                this.resident.getNavigation().stop();
            }
            // 战斗时不看玩家，否则脑袋会从敌人身上转开
            if (this.resident.getTarget() == null) {
                this.resident.getLookControl().setLookAt(player);
            }
        }
    }

    // ==========================================================
    //  站岗巡逻
    // ==========================================================

    static class GuardPostGoal extends Goal {

        private final Resident resident;
        /** 巡逻重选目标的倒计时（tick）。 */
        private int wanderTimer;

        GuardPostGoal(Resident resident) {
            this.resident = resident;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return this.resident.getWorkMode() == WorkMode.GUARD_POST
                    && this.resident.getGuardPost() != null;
        }

        @Override
        public boolean canContinueToUse() {
            return this.canUse();
        }

        @Override
        public void start() {
            this.wanderTimer = 0;
        }

        @Override
        public void stop() {
            this.resident.getNavigation().stop();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            BlockPos post = this.resident.getGuardPost();
            if (post == null) {
                return;
            }
            double radius = Config.WARRIOR_GUARD_RADIUS.get();

            // 离岗哨太远（被人推走/传送）： straight back
            if (this.resident.distanceToSqr(
                    post.getX() + 0.5D, post.getY(), post.getZ() + 0.5D) > radius * radius * 2.25D) {
                LOGGER.debug("[BmfTown][站岗] {} 距岗哨超过 {} 格，回岗",
                        this.resident.getName().getString(), radius * 1.5D);
                this.resident.getNavigation().moveTo(
                        post.getX() + 0.5D, post.getY(), post.getZ() + 0.5D,
                        Config.WARRIOR_GUARD_SPEED.get());
                return;
            }

            // 到了导航终点就站着眺望；隔一阵在半径内换个位置溜达
            if (--this.wanderTimer <= 0) {
                this.wanderTimer = 60 + this.resident.getRandom().nextInt(120);
                if (this.resident.getNavigation().isDone()) {
                    double angle = this.resident.getRandom().nextDouble() * Math.PI * 2.0D;
                    double r = this.resident.getRandom().nextDouble() * radius * 0.6D;
                    double x = post.getX() + 0.5D + Math.cos(angle) * r;
                    double z = post.getZ() + 0.5D + Math.sin(angle) * r;
                    this.resident.getNavigation().moveTo(x, post.getY(), z,
                            Config.WARRIOR_GUARD_SPEED.get());
                    LOGGER.debug("[BmfTown][站岗] {} 巡逻选点 ({}, {}, {})",
                            this.resident.getName().getString(),
                            String.format("%.1f", x), post.getY(), String.format("%.1f", z));
                }
            }
        }
    }

    // ==========================================================
    //  索敌：锚点半径内的敌对生物
    // ==========================================================

    /**
     * 继承原版 {@link TargetGoal}——这是铁傀儡 {@code DefendVillageTargetGoal}、
     * 狼 {@code BegGoal} 一族用的标准基类。相比普通 {@code Goal} 的关键差别：
     *
     * <ul>
     *   <li>{@code canContinueToUse()} 走 {@code mob.getTarget()} 而不是自持字段，
     *       目标状态以实体为准，杜绝"Goal 内部状态和实体状态两张皮"；</li>
     *   <li>原版 {@code stop()} 负责清 {@code setTarget(null)}，与
     *       {@code HurtByTargetGoal} 等兄弟 Goal 的生命周期语义完全一致。</li>
     * </ul>
     *
     * <h2>为什么不打：判定顺序</h2>
     * 索敌失败的每一种原因都在这里有一条 DEBUG 日志，按顺序排查即可：
     * <ol>
     *   <li>工作模式是 NONE → {@code 未分配工作}</li>
     *   <li>锚点为空 → {@code 锚点丢失}（跟随玩家下线 / 岗哨数据为空）</li>
     *   <li>范围内没有敌对生物 → {@code 范围内敌对生物 0 个}</li>
     *   <li>有生物但全被过滤（苦力怕 / 同为队友）→ {@code 全部被过滤}</li>
     *   <li>已锁定 → {@code 锁定 xxx}</li>
     * </ol>
     */
    static class MonsterTargetGoal extends TargetGoal {

        /** 距下次扫描的 tick 数（canUse 每次调用递减，归零触发扫描）。 */
        private int scanCooldown;

        MonsterTargetGoal(Resident resident) {
            super(resident, false);
        }

        /**
         * 节流为什么用 Goal 自持计数器而不是 {@code mob.tickCount % 10}：
         *
         * <p>原版 {@code Mob.serverAiStep} 按 {@code (tickCount + entityId) % 2}
         * 奇偶交替调度——只有偶数侧的 tick 才会调用 {@code canUse()}。
         * {@code tickCount % 10 == 0} 恰好只出现在偶数 tick 上，
         * 于是 <b>实体 ID 为奇数的战士两个条件永远无法同时满足，
         * 一次都扫不了</b>——这正是"只有一半 NPC 会索敌、换谁打人纯看脸"
         * 的元凶。Goal 自持计数器与 tick 奇偶无关，所有实体一律公平。
         */
        @Override
        public boolean canUse() {
            Resident resident = (Resident) this.mob;
            if (--this.scanCooldown > 0) {
                return false;
            }
            this.scanCooldown = 10;
            WorkMode mode = resident.getWorkMode();
            if (mode == WorkMode.NONE) {
                LOGGER.debug("[BmfTown][索敌] {} 跳过：未分配工作", name());
                return false;
            }
            BlockPos post = anchor(resident);
            if (post == null) {
                // 有工作模式却没锚点 = 真异常（跟随玩家下线时会短暂出现，属正常）
                LOGGER.debug("[BmfTown][索敌] {} 跳过：模式={} 但锚点为空（玩家不在本维度/已下线？）",
                        name(), mode);
                return false;
            }
            double r = aggroRadius(resident);
            AABB box = new AABB(post).inflate(r, r * 0.5D, r);
            LivingEntity best = null;
            double bestDist = Double.MAX_VALUE;
            int seen = 0;
            int filtered = 0;
            // 用 Monster.class 而不是 Enemy.class：getEntitiesOfClass 只收
            // Entity 的子类型，而 Enemy 是纯接口；Monster 是实体类且实现了
            // Enemy，覆盖所有敌对生物（苦力怕过滤见谓词）。
            for (Monster mob : this.mob.level().getEntitiesOfClass(Monster.class, box)) {
                if (!mob.isAlive() || mob.isAlliedTo(this.mob)) {
                    continue;
                }
                seen++;
                if (!Config.WARRIOR_ATTACK_CREEPERS.get() && mob instanceof Creeper) {
                    filtered++;
                    LOGGER.debug("[BmfTown][索敌] {} 过滤 {}：苦力怕（配置 warrior.attackCreepers=false）",
                            name(), mob.getType().toShortString());
                    continue;
                }
                double d = mob.distanceToSqr(this.mob);
                LOGGER.debug("[BmfTown][索敌] {} 候选 {}：距自己 {} 格，距锚点 {} 格",
                        name(), mob.getType().toShortString(),
                        String.format("%.1f", Math.sqrt(d)),
                        String.format("%.1f", Math.sqrt(mob.distanceToSqr(
                                post.getX() + 0.5D, post.getY(), post.getZ() + 0.5D))));
                if (d < bestDist) {
                    bestDist = d;
                    best = mob;
                }
            }
            if (best == null) {
                if (seen == 0) {
                    LOGGER.debug("[BmfTown][索敌] {} 模式={} 锚点={} 半径={}：范围内敌对生物 0 个",
                            name(), mode, post.toShortString(), r);
                } else {
                    // 有敌对生物却一个都没进候选 = 全被过滤，这是"该打没打"的异常
                    LOGGER.debug("[BmfTown][索敌] {} 模式={} 锚点={} 半径={}：{} 个敌对生物全部被过滤",
                            name(), mode, post.toShortString(), r, seen);
                    if (filtered == 0) {
                        // seen>0 但 filtered==0 却没选中，逻辑上不该发生
                        logAnomaly("{} 有 {} 个未过滤候选却未选中任何目标，请检查过滤逻辑",
                                name(), seen);
                    }
                }
                return false;
            }
            this.mob.setTarget(best);
            LOGGER.debug("[BmfTown][索敌] {} 锁定 {}（模式={}，锚点={}，半径={}，距自己 {} 格）",
                    name(), best.getType().toShortString(), mode, post.toShortString(), r,
                    String.format("%.1f", Math.sqrt(bestDist)));
            return true;
        }

        /**
         * 目标是否还值得继续打。
         *
         * <p>退出条件逐条记 DEBUG：目标死了、锚点没了、跑出半径
         * （半径判定放宽到 {@code r*2}——追击时允许脱离锚点范围一点，
         * 否则刚开打就断目标，战士会在原地反复接战-脱战）。
         */
        @Override
        public boolean canContinueToUse() {
            Resident resident = (Resident) this.mob;
            LivingEntity target = this.mob.getTarget();
            if (target == null) {
                return false; // 正常，被 stop 清过
            }
            if (!target.isAlive()) {
                LOGGER.debug("[BmfTown][索敌] {} 目标 {} 已死亡，结束追击",
                        name(), target.getType().toShortString());
                return false;
            }
            WorkMode mode = resident.getWorkMode();
            if (mode == WorkMode.NONE) {
                LOGGER.debug("[BmfTown][索敌] {} 工作被取消，放弃目标 {}", name(),
                        target.getType().toShortString());
                return false;
            }
            BlockPos post = anchor(resident);
            if (post == null) {
                LOGGER.debug("[BmfTown][索敌] {} 锚点丢失（模式={}），放弃目标 {}",
                        name(), mode, target.getType().toShortString());
                return false;
            }
            double r = aggroRadius(resident);
            double targetDistSqr = target.distanceToSqr(
                    post.getX() + 0.5D, post.getY(), post.getZ() + 0.5D);
            if (targetDistSqr > r * r * 4.0D) {
                LOGGER.debug("[BmfTown][索敌] {} 目标 {} 距锚点 {} 格，超出追击上限 {} 格，放弃",
                        name(), target.getType().toShortString(),
                        String.format("%.1f", Math.sqrt(targetDistSqr)), r * 2.0D);
                return false;
            }
            return true;
        }

        private String name() {
            return this.mob.getName().getString();
        }
    }

    /**
     * 打一条"该打却没打"的异常日志。
     *
     * <p>用 INFO 而不是 DEBUG：这类情况玩家在游戏里就能感觉到不对劲
     * （战士站着不动），默认日志级别下也应该留下痕迹。
     */
    private static void logAnomaly(String fmt, Object... args) {
        LOGGER.debug("[BmfTown][索敌][异常] " + fmt, args);
    }
}
