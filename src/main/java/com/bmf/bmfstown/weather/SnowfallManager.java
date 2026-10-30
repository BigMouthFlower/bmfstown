package com.bmf.bmfstown.weather;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.network.ModNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.List;

/**
 * 魔法降雪（v0.9.1）：在**任何群系**限时、局部地下雪——不碰原版的
 * "温度决定雨雪"判定，纯 mod 自演：
 *
 * <ul>
 *   <li>**服务端**：active 期间每 tick 向半径圆盘内随机采样若干列，
 *       在地表铺 {@link SnowLayerBlock}（最多叠 {@value #MAX_LAYERS} 层），
 *       并记录自己铺过的位置；到点后转入**消融**——只融化自己铺的雪，
 *       玩家搭的雪不受影响（消融时校验方块仍是雪层，不是就跳过）；</li>
 *   <li>**客户端**：{@link com.bmf.bmfstown.client.SnowfallClient} 收到同步后
 *       在圆盘内撒雪花粒子（原版 snowflake 粒子，零自定义渲染资源）；</li>
 *   <li>**触发**：当前只有 {@code /bmfstown snowfall} 调试命令；
 *       {@link #start} 是公开入口，将来接法术/事件系统直接调。</li>
 * </ul>
 *
 * <p>与原版天气的关系：**并存**——原版在下雨时魔法雪会叠加（视觉上是
 * 雨夹雪，魔法效果可以接受）；原版判定下不下雪跟这里完全无关。
 */
@EventBusSubscriber(modid = BmfTown.MOD_ID)
public final class SnowfallManager {

    private SnowfallManager() {
    }

    // ---- 数值（魔法效果，先不进配置；将来成系统再数据化） ----
    /** 每 tick 采样铺雪的列数（4/tick ≈ 80 列/秒）。 */
    private static final int SNOW_PER_TICK = 4;
    /** 雪层最大厚度（层）。原版雪层 1~8，魔法雪封顶 3，够表达"积起来"即可。 */
    private static final int MAX_LAYERS = 3;
    /** 消融速度：每 tick 处理的列数（16/tick ≈ 半径 32 的雪 20 秒化完）。 */
    private static final int MELT_PER_TICK = 16;
    /** 半径与时长上限（防手滑把服务器铺穿）。 */
    private static final int MAX_RADIUS = 128;
    private static final int MAX_SECONDS = 600;

    // ==========================================================
    //  公开入口
    // ==========================================================

    /**
     * 开始一场魔法降雪。
     *
     * @return false = 参数非法（半径/时长超上限）
     */
    public static boolean start(ServerLevel level, BlockPos center, int radius, int seconds) {
        if (radius < 1 || radius > MAX_RADIUS || seconds < 1 || seconds > MAX_SECONDS) {
            return false;
        }
        SnowfallSavedData data = SnowfallSavedData.get(level);
        data.start(center, radius, level.getGameTime() + seconds * 20L);
        ModNetworking.sendSnowfallSync(level, true, center.asLong(), radius);
        return true;
    }

    // ==========================================================
    //  每 tick 推进
    // ==========================================================

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            tickLevel(level);
        }
    }

    private static void tickLevel(ServerLevel level) {
        SnowfallSavedData data = SnowfallSavedData.peek(level);
        if (data == null) {
            return; // 这个维度从没用过魔法雪
        }
        if (data.isActive()) {
            placeSnow(level, data);
            if (level.getGameTime() >= data.endGameTime()) {
                data.beginMelt();
                ModNetworking.sendSnowfallSync(level, false, 0, 0);
                for (ServerPlayer player : level.players()) {
                    player.displayClientMessage(
                            net.minecraft.network.chat.Component.translatable(
                                    "msg.bmfstown.snowfall.ended"), false);
                }
            }
        } else {
            melt(level, data);
        }
    }

    // ------------------------------------------------------------
    //  铺雪
    // ------------------------------------------------------------

    private static void placeSnow(ServerLevel level, SnowfallSavedData data) {
        RandomSource random = level.getRandom();
        BlockPos center = BlockPos.of(data.centerPacked());
        int radius = data.radius();
        int cx = center.getX();
        int cz = center.getZ();

        for (int i = 0; i < SNOW_PER_TICK; i++) {
            // 均匀圆盘采样：r ∝ sqrt(u) 保证圆内密度均匀，不会堆在圆心
            double dist = Math.sqrt(random.nextDouble()) * radius;
            double angle = random.nextDouble() * Math.PI * 2;
            int x = cx + (int) Math.round(Math.cos(angle) * dist);
            int z = cz + (int) Math.round(Math.sin(angle) * dist);

            BlockPos sample = new BlockPos(x, 0, z);
            if (!level.hasChunkAt(sample)) {
                continue; // 未加载区块：这 tick 跳过
            }
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos ground = new BlockPos(x, y - 1, z);
            BlockPos top = new BlockPos(x, y, z);
            BlockState groundState = level.getBlockState(ground);
            BlockState topState = level.getBlockState(top);

            // 情形 1：地表本身是雪层（上层列还没化完/已堆着）→ 继续加厚
            if (groundState.getBlock() instanceof SnowLayerBlock) {
                if (tryThicken(level, ground, groundState, data)) {
                    continue;
                }
            }
            // 情形 2：地表上方是雪层（heightmap 顶格就是雪）→ 加厚
            if (topState.getBlock() instanceof SnowLayerBlock) {
                tryThicken(level, top, topState, data);
                continue;
            }
            // 情形 3：空地 → 需要地面顶面结实（排除水面/树叶顶/悬空）
            if (!topState.isAir()) {
                continue;
            }
            if (!groundState.isFaceSturdy(level, ground, Direction.UP)) {
                continue; // 水面、树叶上不放——原版雪也不积在这些地方
            }
            level.setBlock(top, Blocks.SNOW.defaultBlockState(), 3);
            data.recordPlaced(top);
        }
    }

    /** 加厚一层雪（到 {@value #MAX_LAYERS} 层封顶）。成功返回 true。 */
    private static boolean tryThicken(ServerLevel level, BlockPos pos,
                                      BlockState state, SnowfallSavedData data) {
        int layers = state.getValue(SnowLayerBlock.LAYERS);
        if (layers >= MAX_LAYERS) {
            return false;
        }
        level.setBlock(pos, state.setValue(SnowLayerBlock.LAYERS, layers + 1), 3);
        data.recordPlaced(pos);
        return true;
    }

    // ------------------------------------------------------------
    //  消融
    // ------------------------------------------------------------

    private static void melt(ServerLevel level, SnowfallSavedData data) {
        if (!data.hasMelting()) {
            return;
        }
        List<Long> batch = data.popMelting(MELT_PER_TICK);
        for (long packed : batch) {
            BlockPos pos = BlockPos.of(packed);
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof SnowLayerBlock)) {
                continue; // 雪被人挖了/被方块盖了：跳过，不还原任何东西
            }
            int layers = state.getValue(SnowLayerBlock.LAYERS);
            if (layers > 1) {
                // 逐层化：减一层后重新排队，多层雪比单层雪多挺一会儿
                level.setBlock(pos, state.setValue(SnowLayerBlock.LAYERS, layers - 1), 3);
                data.requeueMelting(pos);
            } else {
                level.removeBlock(pos, false);
            }
        }
    }

    // ------------------------------------------------------------
    //  中途进世界的玩家补同步（不然看不到这场雪）
    // ------------------------------------------------------------

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        SnowfallSavedData data = SnowfallSavedData.peek(player.serverLevel());
        if (data != null && data.isActive()) {
            ModNetworking.sendSnowfallSync(player.serverLevel(), true,
                    data.centerPacked(), data.radius());
        }
    }
}
