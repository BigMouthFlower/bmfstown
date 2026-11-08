package com.bmf.bmfstown.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * 仇恨系统修正（v0.14，roadmap 待办 13，用户 2026-11-08 拍板）：
 * 战士索敌表内的敌对生物<b>对居民开放仇恨</b>——怪物会主动攻击镇民。
 *
 * <h2>排除名单（全部用户定稿）</h2>
 * <ul>
 *   <li><b>苦力怕</b>：不开放仇恨——不然满镇乱炸受不了；</li>
 *   <li><b>中立生物</b>（末影人、僵尸猪灵）：不主动攻击 NPC，避免全镇仇恨值过高——
 *       它们只在被攻击/被激怒时才还手（原版激怒逻辑不受影响）；</li>
 *   <li>实现 {@code Monster} 抽象类之外的非群居敌对（末影龙、幻翼等）不注入——
 *       它们走各自的自定义 AI，塞 Goal 无效。</li>
 * </ul>
 *
 * <p>注入方式：{@code EntityJoinLevelEvent} 时给怪挂一个
 * {@link NearestAttackableTargetGoal}（{@code Resident} 目标，randomInterval=10 与
 * 原版村民-僵尸同款节奏）；已注入过的怪跳过（换维度重进不重复加）。
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
        if (!(event.getEntity() instanceof Monster monster)) {
            return;
        }
        if (monster instanceof Creeper || monster instanceof EnderMan
                || monster instanceof ZombifiedPiglin) {
            return; // 苦力怕不炸镇民；中立生物不主动攻击 NPC
        }
        for (net.minecraft.world.entity.ai.goal.WrappedGoal wrapped
                : monster.targetSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof ResidentTargetGoal) {
                return; // 已注入（换维度重进等场景）
            }
        }
        monster.targetSelector.addGoal(3, new ResidentTargetGoal(monster));
    }

    /** 怪物 → 居民的索敌 Goal。玩家目标在场时不抢（围玩家的怪不散开）。 */
    static class ResidentTargetGoal extends NearestAttackableTargetGoal<Resident> {

        ResidentTargetGoal(Monster mob) {
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
