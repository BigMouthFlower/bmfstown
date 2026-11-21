package com.bmf.bmfstown.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * 仇恨系统修正（v0.14，roadmap 待办 13，用户 2026-11-08 拍板）：
 * 战士索敌表内的敌对生物<b>对居民开放仇恨</b>——怪物会主动攻击镇民。
 *
 * <h2>排除名单（全部用户定稿）</h2>
 * <ul>
 *   <li><b>苦力怕</b>：不开放仇恨——不然满镇乱炸受不了；</li>
 *   <li><b>中立生物</b>（末影人、僵尸猪灵）：不主动攻击 NPC，避免全镇仇恨值过高——
 *       它们只在被攻击/被激怒时才还手（原版激怒逻辑不受影响）；</li>
 *   <li><b>AI 自定义、塞 Goal 无效的敌对</b>（幻翼、恶魂、潜影贝、末影龙等）不注入——
 *       它们不是 {@code Monster} 子类且不走标准 targetSelector，塞 Goal 无效；
 *       战士索敌仍会打它们（Enemy 接口口径），只是它们保持原版 AI 不主动找镇民。</li>
 * </ul>
 *
 * <p>注入面（2026-11-21 扩展）：{@code Monster} 全子类 + <b>史莱姆家族</b>（史莱姆/
 * 岩浆怪——{@code Mob} 直接实现 {@code Enemy}，此前被 {@code instanceof Monster}
 * 漏掉，造成"战士会打史莱姆、史莱姆却不打镇民"的口径差）。史莱姆家族虽不走
 * {@code Monster}，但用标准 targetSelector（原版对玩家/铁傀儡即
 * {@link NearestAttackableTargetGoal}），注入有效。</p>
 *
 * <p>注入方式：{@code EntityJoinLevelEvent} 时给怪挂一个
 * {@link NearestAttackableTargetGoal}（{@code Resident} 目标，randomInterval=10 与
 * 原版村民-僵尸同款节奏）；已注入过的怪跳过（换维度重进不重复加）。</p>
 */
@EventBusSubscriber(modid = com.bmf.bmfstown.BmfTown.MOD_ID)
public final class ResidentAggroHandler {

    private ResidentAggroHandler() {
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()
                || event.getLevel().dimension() != Level.OVERWORLD) {
            return; // 居民只活在主世界；客户端不注入
        }
        // 注入口径：Monster 全子类 + 史莱姆家族（2026-11-21 扩展，见类 javadoc）——
        // 后者是 Mob 直接实现 Enemy 的"非标准怪物"，原 instanceof Monster 漏掉
        if (!(event.getEntity() instanceof Monster)
                && !(event.getEntity() instanceof Slime)) {
            return;
        }
        net.minecraft.world.entity.Mob mob = (net.minecraft.world.entity.Mob) event.getEntity();
        if (mob instanceof Creeper || mob instanceof EnderMan
                || mob instanceof ZombifiedPiglin) {
            return; // 苦力怕不炸镇民；中立生物不主动攻击 NPC
        }
        for (net.minecraft.world.entity.ai.goal.WrappedGoal wrapped
                : mob.targetSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof ResidentTargetGoal) {
                return; // 已注入（换维度重进等场景）
            }
        }
        mob.targetSelector.addGoal(3, new ResidentTargetGoal(mob));
    }

    /**
     * 史莱姆碰撞补刀（2026-11-21，#107 追记）：原版 {@code dealDamage} 只有两条
     * 触发路径——{@code playerTouch}（只认玩家）与 {@code push}（{@code instanceof
     * IronGolem} 守卫）——史莱姆原版不主动打村民，压根没有"打居民"的碰撞伤害
     * 路径。注入仇恨后史莱姆会追镇民，但跳到居民身上不造成任何伤害
     * （="有仇恨没伤害"）。这里在史莱姆 tick 末尾复刻原版 dealDamage 条件
     * （存活+近战范围+视线，伤害=ATTACK_DAMAGE 属性值=size），无敌帧由
     * {@code LivingEntity.hurt} 内建节流，不会每 tick 连掉血。
     */
    @SubscribeEvent
    public static void onSlimeTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Slime slime) || slime.level().isClientSide()) {
            return;
        }
        // 复刻原版 isDealsDamage（protected）：非微小 + AI 生效
        if (slime.isTiny() || !slime.isEffectiveAi() || !slime.isAlive()) {
            return;
        }
        for (Resident resident : slime.level().getEntitiesOfClass(
                Resident.class, slime.getBoundingBox().inflate(1.0))) {
            if (slime.isWithinMeleeAttackRange(resident) && slime.hasLineOfSight(resident)
                    && resident.hurt(slime.damageSources().mobAttack(slime),
                            (float) slime.getAttributeValue(Attributes.ATTACK_DAMAGE))) {
                slime.playSound(net.minecraft.sounds.SoundEvents.SLIME_ATTACK, 1.0F,
                        (slime.getRandom().nextFloat() - slime.getRandom().nextFloat())
                                * 0.2F + 1.0F);
            }
        }
    }

    /** 怪物 → 居民的索敌 Goal。玩家目标在场时不抢（围玩家的怪不散开）。 */
    static class ResidentTargetGoal extends NearestAttackableTargetGoal<Resident> {

        ResidentTargetGoal(net.minecraft.world.entity.Mob mob) {
            super(mob, Resident.class, 10, true, false, null);
        }

        @Override
        public boolean canUse() {
            if (this.mob.getTarget() instanceof Player) {
                return false; // 玩家仇恨优先：不把盯着玩家的怪拉去打居民
            }
            return super.canUse();
        }
    }

    /** 仅供测试/调试确认排除名单语义。 */
    public static boolean isExcludedType(EntityType<?> type) {
        return type == EntityType.CREEPER || type == EntityType.ENDERMAN
                || type == EntityType.ZOMBIFIED_PIGLIN;
    }
}
