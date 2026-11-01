package com.bmf.bmfstown.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * 学者跟随（v0.11，途径 B 的移动层）：跟着城镇中指定的镇民（复用
 * "模仿特性"式的贴身观察思路——跟在人家身边，自然看到工作）。
 *
 * <p>目标由卡点时的自动选择设定（{@link ScholarGoal}：优先该职业传奇，
 * 否则该职业任意从业者）；跟随期间不写书（ScholarGoal 让位）。
 */
public class ScholarFollowGoal extends Goal {

    private final Resident resident;

    public ScholarFollowGoal(Resident resident) {
        this.resident = resident;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return this.resident.getProfession() == com.bmf.bmfstown.entity.profile.ProfessionType.SCHOLAR
                && this.resident.getFollowResidentId() != null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (!(this.resident.level() instanceof ServerLevel level)) {
            return;
        }
        var targetId = this.resident.getFollowResidentId();
        if (targetId == null) {
            return;
        }
        var target = findTarget(level, targetId);
        if (target == null || !target.isAlive()) {
            this.resident.setFollowResidentId(null); // 目标没了：结束跟随
            return;
        }
        double dist = this.resident.distanceToSqr(target);
        if (dist > 6.0D * 6.0D) {
            this.resident.getNavigation().moveTo(target, 1.0D);
        } else {
            this.resident.getNavigation().stop();
            this.resident.getLookControl().setLookAt(target);
        }
    }

    private Resident findTarget(ServerLevel level, java.util.UUID id) {
        for (var entity : level.getEntitiesOfClass(Resident.class,
                this.resident.getBoundingBox().inflate(64.0D))) {
            if (entity.getUUID().equals(id)) {
                return entity;
            }
        }
        return null;
    }
}
