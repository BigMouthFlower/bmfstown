package com.bmf.bmfstown.client;

import com.bmf.bmfstown.BmfTown;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 魔法降雪的客户端表现：active 期间在圆盘内撒原版雪花粒子。
 *
 * <p>为什么不渲染"正规"的雪幕（改 {@code LevelRenderer.renderSnowAndRain}）：
 * 那条路径按群系判定雨/雪，要掺进去必须 mixin 全局渲染器——为一个魔法效果
 * 不值当。粒子方案零自定义资源、零 mixin，视觉上"魔法感"反而更强。
 *
 * <p>撒粒子策略：只给**在圆盘内或附近**的玩家撒（圆盘外的玩家看不见这场雪，
 * 白白耗帧）；每次 tick 在圆盘内随机取点、从玩家头顶上方 7~13 格落下，
 * 靠原版雪花粒子自带的飘落运动落体。密度 12 粒/tick ≈ 密雪，可调。
 */
@EventBusSubscriber(modid = BmfTown.MOD_ID, value = Dist.CLIENT)
public final class SnowfallClient {

    private SnowfallClient() {
    }

    private static volatile boolean active;
    private static volatile long centerPacked;
    private static volatile int radius;

    /** 收到服务端同步（开始/结束/中途进服）时调用。 */
    public static void set(boolean isActive, long packedCenter, int radiusIn) {
        active = isActive;
        centerPacked = packedCenter;
        radius = radiusIn;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!active) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        BlockPos center = BlockPos.of(centerPacked);
        Vec3 cam = mc.player.position();

        // 玩家不在圆盘附近：这场雪与他无关，省下粒子开销
        double dx = cam.x - (center.getX() + 0.5);
        double dz = cam.z - (center.getZ() + 0.5);
        if (dx * dx + dz * dz > (double) (radius + 16) * (radius + 16)) {
            return;
        }

        RandomSource random = mc.level.random;
        for (int i = 0; i < 12; i++) {
            double dist = Math.sqrt(random.nextDouble()) * radius;
            double angle = random.nextDouble() * Math.PI * 2;
            double x = center.getX() + 0.5 + Math.cos(angle) * dist;
            double z = center.getZ() + 0.5 + Math.sin(angle) * dist;
            double y = cam.y + 7 + random.nextDouble() * 6;
            mc.level.addParticle(ParticleTypes.SNOWFLAKE, x, y, z, 0, 0, 0);
        }
    }
}
