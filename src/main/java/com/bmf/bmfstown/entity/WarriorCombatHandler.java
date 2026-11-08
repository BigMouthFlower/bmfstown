package com.bmf.bmfstown.entity;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.config.Config;
import com.bmf.bmfstown.entity.profile.SkillType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * "干中学"：击杀敌人结算格斗经验。
 *
 * <p>挂在 {@link LivingDeathEvent} 上而不是写在攻击流程里，
 * 因为"击杀"的判定权在原版（最后一击致死），自己数刀数会和
 * 原版判定打架。
 *
 * <p><b>全民入账（用户定稿 2026-11-08）</b>：v0.14 群体支援上线后
 * 非战士也会还手/助战击杀——他们同样在干战士的活，就该涨战士的
 * 经验，入账不再区分职业。
 */
@EventBusSubscriber(modid = BmfTown.MOD_ID)
public final class WarriorCombatHandler {

    private WarriorCombatHandler() {
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        if (event.getSource().getEntity() instanceof Resident resident) {
            int gain = Config.WARRIOR_XP_PER_KILL.get();
            resident.addSkillXp(SkillType.COMBAT, gain);
            // 观察期用 debug（验证完毕调回；需要时临时升 INFO）
            BmfTown.LOGGER.debug("[BmfTown][调试] {} 击杀 {}：格斗经验 +{}（累计 {}）",
                    resident.getName().getString(), event.getEntity().getType().toShortString(),
                    gain, resident.getArchive().skillXp(SkillType.COMBAT));
        }
        // 学者实践观察（v0.11）：跟随中的学者看到战士杀敌 → 书上计数 +1
        if (event.getSource().getEntity() instanceof Resident resident && resident.isWarrior()
                && resident.level() instanceof net.minecraft.server.level.ServerLevel level) {
            PracticeObserver.observe(level, resident, com.bmf.bmfstown.entity.profile.ProfessionType.WARRIOR);
        }
    }
}
