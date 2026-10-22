package com.bmf.bmfstown.entity;

import com.bmf.bmfstown.BmfTown;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.List;

/**
 * 玩家点床叫醒镇民。
 *
 * <h2>为什么不依赖原版 BedBlock 的唤醒</h2>
 * 原版"占用的床右键会把占用者赶下来"这条路径只对村民有效
 * （占用者登记在村民脑里）；我们用 {@code startSleeping} 让镇民
 * 走原版睡姿，但原版 BedBlock 并不认识镇民——玩家右键只会看到
 * "床已被占用"，镇民继续装睡。所以在这里自己扫一遍床上的镇民。
 *
 * <h2>判定口径</h2>
 * 与 {@code HousingZones.isBedOccupied} 同款：床的 AABB 里有没有
 * {@code Pose.SLEEPING} 的镇民。命中就全部叫醒并<b>取消事件</b>——
 * 取消后原版不再弹"已占用"提示，玩家的这一下右键就是纯"赶人"。
 */
@EventBusSubscriber(modid = BmfTown.MOD_ID)
public final class SleepWakeHandler {

    private SleepWakeHandler() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        Level level = (Level) event.getLevel();
        BlockPos pos = event.getPos();
        if (!level.getBlockState(pos).is(BlockTags.BEDS)) {
            return;
        }

        // 外扩一格覆盖床的另一半（双人床点床尾也要能赶到睡在床头的人）
        AABB box = new AABB(pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1.0D, pos.getY() + 1.0D, pos.getZ() + 1.0D)
                .inflate(1.0D, 0.5D, 1.0D);
        List<Resident> sleepers = level.getEntitiesOfClass(
                Resident.class, box, r -> r.getPose() == Pose.SLEEPING);
        if (sleepers.isEmpty()) {
            return;
        }
        for (Resident resident : sleepers) {
            resident.kickFromBed();
        }
        event.setCanceled(true);
    }
}
