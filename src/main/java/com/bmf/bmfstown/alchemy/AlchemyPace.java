package com.bmf.bmfstown.alchemy;

import com.bmf.bmfstown.book.SpecialBookEffect;
import com.bmf.bmfstown.entity.AlchemistGoal;
import com.bmf.bmfstown.entity.profile.EfficiencyMath;
import com.bmf.bmfstown.entity.profile.Perks;
import com.bmf.bmfstown.entity.profile.ProfessionType;
import com.bmf.bmfstown.entity.profile.SkillType;
import com.bmf.bmfstown.entity.Resident;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;

import javax.annotation.Nullable;

/**
 * 镇民酿造节奏（v0.12 炼金，决策 #28）：真酿造的"效率"落点——被炼金术士
 * 认领的酿造台，起始酿造时间 400t ÷ 效率乘区（等级/心情/急火/低温/特殊书）。
 * 玩家自己的酿造台不在认领表里，原版速度不受影响。
 *
 * <p>由 {@code BrewingStandBlockEntityMixin} 在 serverTick 调用；查询走
 * {@link AlchemistGoal} 的认领表（静态，服务器内存级）。
 */
public final class AlchemyPace {

    private AlchemyPace() {
    }

    /** 该酿造台是否被低温熟成炼金认领（燃料永不消耗）。 */
    public static boolean isColdBrew(BrewingStandBlockEntity stand) {
        Resident r = AlchemistGoal.claimedResidentAt(stand.getBlockPos());
        return r != null && r.hasPerk(Perks.NODE_AL_ADEPT, "cold_brew");
    }

    /** 起始酿造时间：400 ÷ 效率（下限 100t，防瞬间出药）。 */
    public static int brewStartTicks(int original, net.minecraft.world.level.Level level,
            net.minecraft.core.BlockPos pos, BrewingStandBlockEntity stand) {
        double factor = speedFactor(stand);
        if (factor <= 1.0001D) {
            return original; // 玩家/未认领：原版常量原样返回
        }
        return Math.max(100, (int) Math.round(original / factor));
    }

    /** 效率乘区：合成技能等级 × 心情 × 急火 ×1.3 / 低温 ×0.7 × CRAFT_SPEED 特殊书。 */
    private static double speedFactor(BrewingStandBlockEntity stand) {
        Resident r = AlchemistGoal.claimedResidentAt(stand.getBlockPos());
        if (r == null || r.getProfession() != ProfessionType.ALCHEMIST) {
            return 1.0D;
        }
        double eff = EfficiencyMath.total(
                r.getGrowth().skillXp(SkillType.CRAFTING), r.getStatus().mood(), false,
                r.axisValue(com.bmf.bmfstown.entity.profile.PersonalityAxis.GRIT),
                r.axisValue(com.bmf.bmfstown.entity.profile.PersonalityAxis.FOCUS));
        eff *= 1.0D + r.learnedEffectBonus(SpecialBookEffect.Type.CRAFT_SPEED) / 100.0D;
        if (r.hasPerk(Perks.NODE_AL_ADEPT, "quickfire")) {
            eff *= 1.3D;
        }
        if (r.hasPerk(Perks.NODE_AL_ADEPT, "cold_brew")) {
            eff *= 0.7D;
        }
        return Math.max(0.1D, eff);
    }
}
