package com.bmf.bmfstown.entity;

import com.bmf.bmfstown.config.Config;
import com.bmf.bmfstown.entity.profile.SkillType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
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
 *       越界回岗，顺带按配置间隔结算警戒经验</li>
 *   <li>{@link MonsterTargetGoal}（targetSelector 2）：以锚点为中心扫描
 *       半径内的敌对生物并设为攻击目标；移动交给原版 {@code MeleeAttackGoal}</li>
 * </ul>
 *
 * <h2>互斥与失效</h2>
 * 两个移动 Goal 的 {@code canUse} 都先查 {@code workMode}，同一时刻只有
 * 一个能启动；工作模式被清掉（{@link WorkMode#NONE}）时 Goal 自然停转。
 * 锚点消失（玩家下线/岗哨数据为空）同样会让 Goal 立即退出。
 */
public final class WarriorGoals {

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
        /** 工作经验结算倒计时（tick），与站岗同款：真正跟随时才计时。 */
        private int vigilanceTimer;

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
            this.vigilanceTimer = Config.WARRIOR_VIGILANCE_INTERVAL.get();
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
                this.resident.teleportTo(player.getX(), player.getY(), player.getZ());
                this.resident.getNavigation().stop();
            } else if (dist > 3.0D) {
                this.resident.getNavigation().moveTo(player,
                        Config.WARRIOR_FOLLOW_SPEED.get());
            } else {
                this.resident.getNavigation().stop();
            }
            this.resident.getLookControl().setLookAt(player);

            // 工作经验（与站岗同一套配置）：只在真的跟着玩家时才计时，
            // 玩家下线/模式被清 → Goal 停转 → 不计时（睡觉/闲置自然不涨）。
            if (--this.vigilanceTimer <= 0) {
                this.vigilanceTimer = Config.WARRIOR_VIGILANCE_INTERVAL.get();
                this.resident.addSkillXp(SkillType.VIGILANCE, Config.WARRIOR_VIGILANCE_XP.get());
            }
        }
    }

    // ==========================================================
    //  站岗巡逻
    // ==========================================================

    static class GuardPostGoal extends Goal {

        private final Resident resident;
        /** 警戒经验结算倒计时（tick）。 */
        private int vigilanceTimer;
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
            this.vigilanceTimer = Config.WARRIOR_VIGILANCE_INTERVAL.get();
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
                }
            }

            // 站岗也是工作：按配置间隔涨警戒经验（干中学）
            if (--this.vigilanceTimer <= 0) {
                this.vigilanceTimer = Config.WARRIOR_VIGILANCE_INTERVAL.get();
                this.resident.addSkillXp(SkillType.VIGILANCE, Config.WARRIOR_VIGILANCE_XP.get());
            }
        }
    }

    // ==========================================================
    //  索敌：锚点半径内的敌对生物
    // ==========================================================

    static class MonsterTargetGoal extends Goal {

        private final Resident resident;
        private LivingEntity target;

        MonsterTargetGoal(Resident resident) {
            this.resident = resident;
            this.setFlags(EnumSet.of(Goal.Flag.TARGET));
        }

        @Override
        public boolean canUse() {
            if (this.resident.getWorkMode() == WorkMode.NONE) {
                return false;
            }
            // 每 10 tick 扫一次就够——索敌不需要每 tick 精确
            if (this.resident.tickCount % 10 != 0) {
                return false;
            }
            BlockPos post = anchor(this.resident);
            if (post == null) {
                return false;
            }
            double r = aggroRadius(this.resident);
            AABB box = new AABB(post).inflate(r, r * 0.5D, r);
            LivingEntity best = null;
            double bestDist = Double.MAX_VALUE;
            // 用 Monster.class 而不是 Enemy.class：getEntitiesOfClass 只收
            // Entity 的子类型，而 Enemy 是纯接口；Monster 是实体类且实现了
            // Enemy，覆盖所有敌对生物（苦力怕过滤见谓词）。
            for (var mob : this.resident.level().getEntitiesOfClass(
                    net.minecraft.world.entity.monster.Monster.class, box,
                    e -> e.isAlive() && !e.isAlliedTo(this.resident)
                            && (Config.WARRIOR_ATTACK_CREEPERS.get()
                                || !(e instanceof net.minecraft.world.entity.monster.Creeper)))) {
                double d = mob.distanceToSqr(this.resident);
                if (d < bestDist) {
                    bestDist = d;
                    best = (LivingEntity) mob;
                }
            }
            if (best == null) {
                return false;
            }
            this.target = best;
            this.resident.setTarget(best);
            return true;
        }

        @Override
        public boolean canContinueToUse() {
            BlockPos post = anchor(this.resident);
            if (this.target == null || !this.target.isAlive() || post == null) {
                return false;
            }
            double r = aggroRadius(this.resident);
            return this.target.distanceToSqr(
                    post.getX() + 0.5D, post.getY(), post.getZ() + 0.5D) <= r * r * 4.0D;
        }

        @Override
        public void stop() {
            this.target = null;
            this.resident.setTarget(null);
        }
    }
}
