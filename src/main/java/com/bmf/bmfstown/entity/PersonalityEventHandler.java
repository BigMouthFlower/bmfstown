package com.bmf.bmfstown.entity;

import com.bmf.bmfstown.BmfTown;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

import java.util.List;

/**
 * 性格事件处理器（v0.8.1）：把"世界级事件"翻译给有对应性格的镇民。
 *
 * <h2>为什么用事件总线而不是镇民自己的 tick 扫描</h2>
 * 死亡和爆炸都是<b>瞬时事件</b>——镇民每 5 秒扫一轮世界（tickSenses），
 * 根本赶不上看。挂 NeoForge 事件总线，事件发生的那一 tick 精确送达，
 * 一点都不漏，也不用任何轮询开销。
 *
 * <h2>覆盖范围</h2>
 * 事件点周围 {@link #NOTICE_RADIUS} 格内的镇民都会被通知（16 格 =
 * 与胆小/生物厌恶同款半径，体感统一）。范围值是写死的常量——
 * 想调的用户是 modulation 级需求，不值得开配置项。
 */
@EventBusSubscriber(modid = BmfTown.MOD_ID)
public final class PersonalityEventHandler {

    /** 镇民"看得到"事件的范围（格）。与胆小/生物厌恶半径保持一致。 */
    private static final double NOTICE_RADIUS = 16.0D;

    private PersonalityEventHandler() {
    }

    /**
     * 附近有生物死亡 → 和平主义者记一次负面想法。
     *
     * <p><b>任何</b>死亡都算（用户定稿："自己杀人也是不行的，就看不了死人"）：
     * 被怪杀、被玩家杀、战士击杀、甚至和平主义者自己作为战士出手——
     * 死亡点 16 格内的和平主义者一律受影响。死亡者本人除外。
     */
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) {
            return; // 客户端侧的事件直接忽略
        }
        AABB box = event.getEntity().getBoundingBox().inflate(NOTICE_RADIUS);
        List<Resident> residents = level.getEntitiesOfClass(Resident.class, box);
        for (Resident resident : residents) {
            if (resident == event.getEntity() || !resident.isAlive()) {
                continue; // 死者本人 / 已死亡实体不处理
            }
            resident.getStatus().noteDeathNearby(resident, level);
        }
    }

    /**
     * 附近发生爆炸 → 爆炸主义者记一次正面想法。
     *
     * <p>用 {@code Detonate}（爆炸结算时）而不是 {@code Start}——
     * 每个 explosion 精确触发一次。苦力怕自爆、TNT、下界床爆炸统统算数。
     */
    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Vec3 center = event.getExplosion().center();
        AABB box = new AABB(
                center.x - NOTICE_RADIUS, center.y - NOTICE_RADIUS, center.z - NOTICE_RADIUS,
                center.x + NOTICE_RADIUS, center.y + NOTICE_RADIUS, center.z + NOTICE_RADIUS);
        List<Resident> residents = level.getEntitiesOfClass(Resident.class, box);
        for (Resident resident : residents) {
            if (resident.isAlive()) {
                resident.getStatus().noteExplosionNearby(resident, level);
            }
        }
    }
}
