package com.bmf.bmfstown.entity;

import com.bmf.bmfstown.entity.profile.PersonalityAxis;
import com.bmf.bmfstown.entity.profile.PersonalityFormulas;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * 勇气轴的行为 Goal（v0.14，决策 #44 / roadmap 待办 13）：
 * 还手与逃跑，全镇 50 分一刀切（用户 2026-11-08 定稿）。
 *
 * <h2>还手规则（{@link RetaliateGoal}，替换原 HurtByTargetGoal）</h2>
 * <ul>
 *   <li><b>对玩家零仇恨</b>：任何镇民（含战士）永不还手玩家——
 *       修"玩家打居民被居民打死"的历史问题；</li>
 *   <li>战士：被生物打一律自卫（保底本能不变）；</li>
 *   <li>非战士：不胆小（勇气轴 ≥35）就还手，胆小（&lt;35）转逃跑。</li>
 * </ul>
 *
 * <h2>逃跑规则（{@link CourageFleeGoal}）</h2>
 * 仅非战士且轴值 &lt;35（2026-11-08 分界线定稿）：敌对生物接近
 * {@code fleeDistance(v)} 格内即逃离（v=0 最远 8 格，v≥35 不逃）。
 * 战士不逃——胆小的战士改吃攻击力折损
 * （最多 −30%，中位以上不增伤，见 {@code Resident.doHurtTarget}）。
 *
 * <h2>群体支援（{@link AssistGoal}，v0.14 用户定稿）</h2>
 * 居民被袭击时向周围 16 格呼救：不胆小者（战士无条件、非战士勇气 ≥35）
 * 入场助战。胆小者只顾自己逃——见死不救是性格，不是 bug。
 */
public final class CourageGoals {

    private CourageGoals() {
    }

    /** 还手 Goal（targetSelector 优先级 3，原 HurtByTargetGoal 的替代品）。 */
    public static class RetaliateGoal extends TargetGoal {

        private final Resident resident;
        private LivingEntity pending;

        public RetaliateGoal(Resident resident) {
            super(resident, false, false); // 还手不要求视线/可达：背后挨闷棍也要回头
            this.resident = resident;
            this.setFlags(EnumSet.of(Goal.Flag.TARGET));
        }

        @Override
        public boolean canUse() {
            LivingEntity attacker = this.mob.getLastHurtByMob();
            if (attacker == null || !attacker.isAlive() || attacker == this.mob) {
                return false;
            }
            if (attacker instanceof Player) {
                return false; // NPC 对玩家零仇恨（用户定稿）
            }
            if (this.resident.isWarrior()) {
                this.pending = attacker; // 战士保底自卫（含非敌对生物误伤）
                return true;
            }
            if (this.resident.axisValue(PersonalityAxis.COURAGE)
                    >= PersonalityFormulas.COURAGE_SPLIT_LINE) {
                this.pending = attacker; // 非战士：不胆小（≥35）就还手
                return true;
            }
            return false; // 胆小端不还手，交给 CourageFleeGoal 逃
        }

        @Override
        public boolean canContinueToUse() {
            LivingEntity target = this.mob.getTarget();
            return target != null && target.isAlive();
        }

        @Override
        public void start() {
            this.resident.wakeUpForThreat(); // 睡觉被打先起床再还手（v0.14 修复）
            this.mob.setTarget(this.pending);
            this.pending = null;
            super.start();
        }

        @Override
        public void stop() {
            this.mob.setTarget(null);
            super.stop();
        }
    }

    /**
     * 逃跑 Goal（goalSelector 优先级 2：压过一切工作/漫步，压不过战斗 targetSelector）。
     * 仅非战士且轴值 &lt;35（分界线见 {@link PersonalityFormulas#COURAGE_SPLIT_LINE}）。
     */
    public static class CourageFleeGoal extends Goal {

        /** 威胁重扫节拍（tick）：与感知/索敌同量级，不每 tick 扫。 */
        private static final int RESCAN_INTERVAL = 10;

        private final Resident resident;
        private Monster threat;
        private int rescanCooldown = 0;

        public CourageFleeGoal(Resident resident) {
            this.resident = resident;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE));
        }

        /** 本居民当前的逃跑触发距离；0 = 不逃（战士或轴值≥50）。 */
        private double fleeDist() {
            if (this.resident.isWarrior()) {
                return 0;
            }
            return PersonalityFormulas.fleeDistance(
                    this.resident.axisValue(PersonalityAxis.COURAGE));
        }

        @Override
        public boolean canUse() {
            double dist = this.fleeDist();
            if (dist <= 0) {
                return false;
            }
            if (--this.rescanCooldown > 0 && this.threat != null && this.threat.isAlive()
                    && this.threat.distanceToSqr(this.resident) < dist * dist * 2.25D) {
                return true; // 已知威胁仍在附近，免扫
            }
            this.rescanCooldown = RESCAN_INTERVAL;
            this.threat = null;
            double best = Double.MAX_VALUE;
            for (Monster m : this.resident.level().getEntitiesOfClass(Monster.class,
                    this.resident.getBoundingBox().inflate(dist))) {
                if (!m.isAlive()) {
                    continue;
                }
                double d = m.distanceToSqr(this.resident);
                if (d < dist * dist && d < best) {
                    best = d;
                    this.threat = m;
                }
            }
            return this.threat != null;
        }

        @Override
        public boolean canContinueToUse() {
            double dist = this.fleeDist();
            return dist > 0 && this.threat != null && this.threat.isAlive()
                    && this.threat.distanceToSqr(this.resident) < dist * dist * 2.25D;
        }

        @Override
        public void start() {
            this.resident.wakeUpForThreat(); // 先起床再跑，不许躺着滑行（v0.14 修复）
            this.setNavigationTarget();
        }

        @Override
        public void tick() {
            if (--this.rescanCooldown <= 0) {
                this.rescanCooldown = RESCAN_INTERVAL;
                this.setNavigationTarget();
            }
        }

        @Override
        public void stop() {
            this.resident.getNavigation().stop();
        }

        /** 朝威胁反方向跑：目标点 = 自身 + (自身−威胁)·归一 × 6 格。 */
        private void setNavigationTarget() {
            if (this.threat == null) {
                return;
            }
            Vec3 away = this.resident.position().subtract(this.threat.position());
            away = (away.lengthSqr() < 1.0E-4 ? new Vec3(0.0D, 0.0D, 1.0D) : away.normalize()).scale(6.0D);
            Vec3 target = this.resident.position().add(away);
            this.resident.getNavigation().moveTo(target.x, target.y, target.z, 1.15D);
        }
    }

    /**
     * 助战 Goal（targetSelector 优先级 4，v0.14 群体支援）：
     * 邻居被敌对生物袭击时（{@link Resident#raiseAlarm} 呼救），不胆小者入场。
     *
     * <ul>
     *   <li>参战资格：战士无条件；非战士勇气轴 ≥35（与还手同一条分界线，
     *       "不胆小"口径一致）；</li>
     *   <li>锁敌稳定：已有攻击目标时不响应呼救——不中途换目标（用户定稿
     *       "正常敌对生物那种逻辑"）；目标 start() 一次定格，之后交给
     *       原版 {@code MeleeAttackGoal} 追打，目标死亡才收手；</li>
     *   <li>呼救 5 秒有效：离得远跑不动的自然过期，不无限记仇。</li>
     * </ul>
     */
    public static class AssistGoal extends TargetGoal {

        private final Resident resident;

        public AssistGoal(Resident resident) {
            super(resident, false, false);
            this.resident = resident;
            this.setFlags(EnumSet.of(Goal.Flag.TARGET));
        }

        @Override
        public boolean canUse() {
            LivingEntity attacker = this.resident.currentAlarm();
            if (attacker == null || !attacker.isAlive()) {
                return false;
            }
            if (this.mob.getTarget() != null) {
                return false; // 已经在打架：不中途换目标（锁敌稳定）
            }
            return this.resident.isWarrior()
                    || this.resident.axisValue(PersonalityAxis.COURAGE)
                            >= PersonalityFormulas.COURAGE_SPLIT_LINE;
        }

        @Override
        public boolean canContinueToUse() {
            LivingEntity target = this.mob.getTarget();
            return target != null && target.isAlive();
        }

        @Override
        public void start() {
            LivingEntity attacker = this.resident.currentAlarm();
            if (attacker != null && attacker.isAlive()) {
                this.resident.wakeUpForThreat(); // 睡着的邻居被叫醒再参战
                this.mob.setTarget(attacker);
            }
            super.start();
        }

        @Override
        public void stop() {
            this.resident.clearAlarm();
            this.mob.setTarget(null);
            super.stop();
        }
    }
}
