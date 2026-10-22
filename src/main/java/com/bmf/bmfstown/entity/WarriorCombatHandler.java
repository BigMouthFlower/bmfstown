package com.bmf.bmfstown.entity;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.config.Config;
import com.bmf.bmfstown.entity.profile.SkillType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * 战士的"干中学"：击杀敌人结算格斗经验。
 *
 * <p>挂在 {@link LivingDeathEvent} 上而不是写在攻击流程里，
 * 因为"击杀"的判定权在原版（最后一击致死），自己数刀数会和
 * 原版判定打架。事件源实体是镇民且职业是战士才入账。
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
        if (event.getSource().getEntity() instanceof Resident resident && resident.isWarrior()) {
            resident.addSkillXp(SkillType.COMBAT, Config.WARRIOR_XP_PER_KILL.get());
        }
    }
}
