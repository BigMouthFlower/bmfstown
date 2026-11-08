package com.bmf.bmfstown.fireworks;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.ModDataComponents;
import com.bmf.bmfstown.config.Config;
import com.bmf.bmfstown.mixin.FireworkRocketEntityAccessor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工匠章伤害结算（v0.13）：复刻原版 {@code dealExplosionDamage} 公式，
 * 叠加工匠章加成（破片半径 / 装药倍率 / 回响二段爆）。
 *
 * <h2>原版公式（21.1.262 反编译口径）</h2>
 * 基础伤害 f = 5 + 星数×2（无星 = 0，不结算）；AOE 半径 5.0；
 * 视线检查（两高度点 clip）；衰减 = f × sqrt((R − dist) / R)；
 * 挂靠实体（鞘翅骑乘）直伤 5 + 星数×2。
 *
 * <h2>章加成</h2>
 * 破片 = 半径 +1（衰减/排除同步用新半径）；装药足量 = 全伤害 ×1.25；
 * 回响 = 首爆后延迟 N tick 对同一批受击实体再伤一次（伤害 = 首爆实际伤害×0.5，
 * 只伤实体不破坏方块——直接 hurt 无爆炸，天然满足）。
 *
 * <p>回调返回 true = 已按章结算（mixin 取消原版逻辑）；false = 无章走原版。
 */
@EventBusSubscriber(modid = BmfTown.MOD_ID)
public final class FireworksMarkHandler {

    /** 装药足量：爆炸伤害 +25%（决策 #32 用户定稿数值）。 */
    public static final float PAYLOAD_MULT = 1.25F;

    /** 回响：二段伤害 = 首爆实际伤害 × 0.5（"第一次的一半"）。 */
    public static final float ECHO_FRACTION = 0.5F;

    /**
     * 回响延迟任务（服务端内存级；重启丢失 = 二段爆消失，无副作用）。
     * 实体引用不落 NBT——目标按 entityId 取，取不到/死了就跳过。
     */
    private record EchoTask(ServerLevel level, int targetId, float damage,
                            long dueTick, @Nullable Entity owner) {
    }

    private static final List<EchoTask> ECHOES = new ArrayList<>();

    /**
     * 章火箭伤害结算入口（mixin 调用）。
     *
     * @return true = 有章已结算（取消原版）；false = 无章走原版逻辑
     */
    public static boolean handleExplode(FireworkRocketEntity rocket) {
        if (rocket.level().isClientSide) {
            return false;
        }
        ItemStack item = rocket.getItem();
        FireworksMark mark = item.get(ModDataComponents.FIREWORKS_MARK.get());
        if (mark == null) {
            return false;
        }
        if (!(rocket.level() instanceof ServerLevel level)) {
            return true;
        }
        List<FireworkExplosion> explosions = explosionsOf(item);
        float base = explosions.isEmpty() ? 0.0F
                : 5.0F + explosions.size() * 2.0F;
        if (base > 0.0F) {
            float payloadMult = mark.payload() ? PAYLOAD_MULT : 1.0F;
            LivingEntity attached = ((FireworkRocketEntityAccessor) rocket)
                    .bmfstown$getAttachedTo();
            if (attached != null) {
                attached.hurt(rocket.damageSources().fireworks(rocket, rocket.getOwner()),
                        base * payloadMult);
            }
            // 破片：半径 +1（衰减与排除距离同步用新半径）
            double radius = mark.shrapnel() ? 6.0 : 5.0;
            Vec3 center = rocket.position();
            Map<Integer, Float> firstHits = new LinkedHashMap<>();
            for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
                    rocket.getBoundingBox().inflate(radius))) {
                if (target == attached || rocket.distanceToSqr(target) > radius * radius) {
                    continue;
                }
                if (!hasLineOfSight(level, rocket, center, target)) {
                    continue;
                }
                float damage = base
                        * (float) Math.sqrt((radius - rocket.distanceTo(target)) / radius)
                        * payloadMult;
                target.hurt(rocket.damageSources().fireworks(rocket, rocket.getOwner()),
                        damage);
                firstHits.put(target.getId(), damage);
            }
            // 回响：首爆后延迟再爆一次（每实体=首爆实际伤害的一半）
            if (mark.echo() && !firstHits.isEmpty()) {
                long due = level.getGameTime() + Config.FIREWORK_ECHO_DELAY.get();
                for (var e : firstHits.entrySet()) {
                    ECHOES.add(new EchoTask(level, e.getKey(),
                            e.getValue() * ECHO_FRACTION, due, rocket.getOwner()));
                }
            }
        }
        return true;
    }

    /** 原版口径：两高度点视线检查（任一点 MISS = 可命中）。 */
    private static boolean hasLineOfSight(ServerLevel level, FireworkRocketEntity rocket,
            Vec3 center, LivingEntity target) {
        for (int i = 0; i < 2; i++) {
            Vec3 to = new Vec3(target.getX(), target.getY(0.5 * i), target.getZ());
            HitResult hit = level.clip(new ClipContext(center, to,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, rocket));
            if (hit.getType() == HitResult.Type.MISS) {
                return true;
            }
        }
        return false;
    }

    private static List<FireworkExplosion> explosionsOf(ItemStack item) {
        Fireworks fw = item.get(DataComponents.FIREWORKS);
        return fw != null ? fw.explosions() : List.of();
    }

    /** 服务端 tick：结算到期的回响二段爆（只伤实体不破坏方块）。 */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (ECHOES.isEmpty()) {
            return;
        }
        Iterator<EchoTask> it = ECHOES.iterator();
        while (it.hasNext()) {
            EchoTask task = it.next();
            if (task.dueTick() > task.level().getGameTime()) {
                continue;
            }
            it.remove();
            Entity target = task.level().getEntity(task.targetId());
            if (!(target instanceof LivingEntity living) || !living.isAlive()
                    || living.isRemoved()) {
                continue;
            }
            // 二段爆观感：一闷响 + 半伤（不破坏方块——直接 hurt 无爆炸）
            DamageSource source = task.level().damageSources().fireworks(null, task.owner());
            living.hurt(source, task.damage());
            task.level().playSound(null, living.getX(), living.getY(), living.getZ(),
                    SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, SoundSource.AMBIENT,
                    1.0F, 0.7F);
        }
    }

    private FireworksMarkHandler() {
    }
}
