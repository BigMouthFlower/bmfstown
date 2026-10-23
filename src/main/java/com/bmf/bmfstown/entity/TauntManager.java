package com.bmf.bmfstown.entity;

import com.bmf.bmfstown.BmfTown;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 嘲讽 perk 的瞬态注册表：记录"哪只怪被谁嘲讽到什么时候"。
 *
 * <p><b>为什么不放在怪物身上</b>：嘲讽目标（镇民）和被嘲讽者（原版怪）
 * 是两个实体，把状态塞进原版怪要么用 persistent data（要自己管清理），
 * 要么改原版 AI（要 mixin）。集中一个静态表最简单：
 * 存 UUID 和截止游戏时间，到点、死亡、换维度自动过期。
 *
 * <p><b>瞬态不落档</b>：嘲讽最长 10 秒，重启归零无伤大雅——和坚盾/
 * 追猎冷却同样的取舍。
 *
 * <p><b>强制索敌的两条腿</b>：
 * <ol>
 *   <li>{@link #onChangeTarget} —— 被嘲讽的怪想换目标时直接取消事件，
 *       拦在索敌 AI 的源头；</li>
 *   <li>{@link #onLevelTick} —— 兜底每 10 tick 核对一次：目标被歪了
 *       就扳回来，过期/死亡的条目顺手清掉。</li>
 * </ol>
 */
@EventBusSubscriber(modid = BmfTown.MOD_ID)
public final class TauntManager {

    /** 一条嘲讽记录。 */
    private record Taunt(UUID taunterId, long untilGameTime) {
    }

    /** 被嘲讽怪 UUID → 记录。全局表：只在服务端线程读写。 */
    private static final Map<UUID, Taunt> TAUNTED = new HashMap<>();

    private TauntManager() {
    }

    /**
     * 施放嘲讽：把 {@code target} 的仇恨钉在 {@code taunter} 身上。
     *
     * @return 是否成功登记（怪已死/不在服务端返回 false）
     */
    public static boolean taunt(Monster target, LivingEntity taunter, int durationTicks) {
        if (target.level() instanceof ServerLevel level && taunter.level() == target.level()) {
            long until = level.getGameTime() + durationTicks;
            TAUNTED.put(target.getUUID(), new Taunt(taunter.getUUID(), until));
            target.setTarget(taunter);
            // 同步给附近客户端：渲染层据此给怪罩红
            var payload = new com.bmf.bmfstown.network.ModNetworking.TauntSyncPayload(
                    target.getUUID().toString(), until);
            int sent = 0;
            for (net.minecraft.server.level.ServerPlayer sp : level.players()) {
                if (sp.distanceToSqr(target) < 64 * 64) {
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, payload);
                    sent++;
                }
            }
            com.bmf.bmfstown.BmfTown.LOGGER.info(
                    "[BmfTown][perk] 嘲讽红罩同步：已发给 {} 名玩家（{} 直至 {}）",
                    sent, target.getType().toShortString(), until);
            return true;
        }
        return false;
    }

    /** 查询：这只怪当前是否被嘲讽着。 */
    public static boolean isTaunted(Monster monster) {
        return TAUNTED.containsKey(monster.getUUID());
    }

    // ==========================================================
    //  事件：强制索敌
    // ==========================================================

    /**
     * 被嘲讽的怪不许换目标：索敌 AI（HurtByTarget / 自定义扫描）每 tick
     * 都可能调 setTarget，这里直接拦下，只放行"换回嘲讽者"这一个方向。
     */
    @SubscribeEvent
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        if (!(event.getEntity() instanceof Monster monster) || !isTaunted(monster)) {
            return;
        }
        Taunt taunt = TAUNTED.get(monster.getUUID());
        LivingEntity newTarget = event.getNewAboutToBeSetTarget();
        if (newTarget == null || !newTarget.getUUID().equals(taunt.taunterId())) {
            event.setCanceled(true);
        }
    }

    /**
     * 每 10 tick 兜底巡检：过期清理 + 目标扳正。
     */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || TAUNTED.isEmpty()) {
            return;
        }
        if (level.getGameTime() % 10 != 0) {
            return;
        }
        Iterator<Map.Entry<UUID, Taunt>> it = TAUNTED.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Taunt> entry = it.next();
            Taunt taunt = entry.getValue();
            Monster monster = level.getEntity(entry.getKey()) instanceof Monster m ? m : null;
            // 过期 / 怪没了 / 怪死了：注销
            if (monster == null || !monster.isAlive() || level.getGameTime() >= taunt.untilGameTime()) {
                it.remove();
                continue;
            }
            LivingEntity taunter = level.getEntity(taunt.taunterId()) instanceof LivingEntity le
                    && le.isAlive() ? le : null;
            // 嘲讽者没了：嘲讽失效（怪爱打谁打谁）
            if (taunter == null) {
                it.remove();
                continue;
            }
            // 目标被歪了：扳回来（onChangeTarget 拦不住的边缘情况兜底）
            if (monster.getTarget() != taunter) {
                monster.setTarget(taunter);
            }
        }
    }
}
