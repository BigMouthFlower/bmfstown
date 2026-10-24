package com.bmf.bmfstown.entity;

import com.bmf.bmfstown.town.TownZone;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 住宅区建材/颜色扫描管理器（v0.8.1）。
 *
 * <h2>为什么存在</h2>
 * 建材/颜色的每日结算原来是"以床为中心扫 9×5×9"——范围太小，玩家把
 * 客厅装修得漂漂亮亮也判定不到（实测方解石贴着放才能触发）。v0.8.1
 * 按用户决定改成<b>扫描分配的整个住宅区</b>。区的体积玩家说了算，
 * 不能保证小，所以扫描必须<b>摊派</b>：
 *
 * <ul>
 *   <li><b>每 tick 限量</b>：全服所有扫描任务每 tick 合计最多
 *       {@link #BLOCKS_PER_TICK} 格（4096 格 ≈ 2 个区块柱的表面），
 *       大区花几秒扫完，任何单 tick 的开销都是毫秒级；</li>
 *   <li><b>命中即停</b>：建材与颜色各自命中一个就不再看后面的格子
 *       （"家里没有这种材料"才是最坏情况——满扫）;</li>
 *   <li><b>按天过期</b>：任务带结算日戳，跨天自动丢弃，不会积累。</li>
 * </ul>
 *
 * <h2>驱动方式</h2>
 * 没有独立的事件总线——{@link Resident} 每 tick 在
 * {@code customServerAiStep} 里顺手调 {@link #tick(ServerLevel)}
 * （内部按 gameTime 去重，N 个镇民同 tick 调用只执行一次）。
 * 没有镇民加载时任务暂停，恢复加载后继续，不丢结果。
 *
 * <h2>结果回报</h2>
 * 扫描完成 → 立刻对委托它的镇民结算心情（建材/颜色各一条想法）。
 * 委托镇民中途被卸载/死亡则静默丢弃结果（它当天的结算已经发生过，
 * 想法损失可接受，不值得为它保持引用）。
 */
public final class HomeMaterialScanManager {

    /** 每 tick 全服扫描总量（格）。4096 = 一圈区块柱的表皮量级，毫秒级开销。 */
    private static final int BLOCKS_PER_TICK = 4096;

    /** 进行中的任务，按维度分桶。 */
    private static final Map<ResourceKey<Level>, List<Job>> JOBS = new HashMap<>();

    /** 方块 → 注册名缓存：getBlockState 不分配，名字也没必要反复拼。 */
    private static final Map<Block, String> NAME_CACHE = new IdentityHashMap<>();

    /** tick 去重：同维度同 gameTime 只执行一次。 */
    private static ServerLevel lastTickedLevel;
    private static long lastTickedGameTime = Long.MIN_VALUE;

    private HomeMaterialScanManager() {
    }

    /**
     * 委托一次"本日住宅区材质扫描"。
     *
     * <p>每日结算时由 {@code MoodState.settleHomeMaterials} 调用。
     * 同镇民同日重复请求直接复用（幂等）；任务已完成则立刻用缓存结果结算。
     *
     * @return 委托是否成功（镇民无效时返回 false）
     */
    public static void request(ServerLevel level, TownZone zone,
                               Resident resident, MoodState mood) {
        int day = currentDay(level);
        List<Job> list = JOBS.computeIfAbsent(level.dimension(), k -> new ArrayList<>());

        Job mine = null;
        Iterator<Job> it = list.iterator();
        while (it.hasNext()) {
            Job job = it.next();
            if (job.day != day) {
                it.remove(); // 跨天的旧任务直接丢，今天的结算会重新委托
                continue;
            }
            if (job.resident == resident) {
                mine = job;
            }
        }

        if (mine == null) {
            mine = new Job(zone, day, resident, mood);
            list.add(mine);
            return;
        }
        if (mine.done) {
            // 同日重复请求（reroll 等边缘场景）：直接用已扫出的结果
            mine.mood().applyHomeMaterialResult(resident, level,
                    mine.blockHit, mine.colorHit);
        }
        // 未完成：什么都不做——结果出来时会统一结算
    }

    /**
     * 每 tick 推进扫描。由镇民的 AI tick 顺手驱动（内部去重）。
     */
    public static void tick(ServerLevel level) {
        if (level == lastTickedLevel && level.getGameTime() == lastTickedGameTime) {
            return; // 同一 tick 里 N 个镇民各调了一次，只执行第一次
        }
        lastTickedLevel = level;
        lastTickedGameTime = level.getGameTime();

        List<Job> list = JOBS.get(level.dimension());
        if (list == null || list.isEmpty()) {
            return;
        }

        int budget = BLOCKS_PER_TICK;
        Iterator<Job> it = list.iterator();
        while (it.hasNext() && budget > 0) {
            Job job = it.next();
            if (job.day != currentDay(level)) {
                it.remove(); // 跨天弃扫：今天的结算会重新委托
                continue;
            }
            budget -= scanChunkOfBlocks(job, level, budget);
            if (job.done) {
                it.remove();
                if (!job.resident.isRemoved() && job.resident.isAlive()) {
                    job.mood().applyHomeMaterialResult(job.resident, level,
                            job.blockHit, job.colorHit);
                }
            }
        }
    }

    /**
     * 推进一个任务至多 {@code budget} 格。
     *
     * @return 实际消耗的格数（未加载区块的格子跳过但也计数，保证有进度）
     */
    private static int scanChunkOfBlocks(Job job, ServerLevel level, int budget) {
        int used = 0;
        while (used < budget && !job.done) {
            BlockPos.MutableBlockPos pos = job.cursor;
            if (level.hasChunkAt(pos)) {
                String name = registryName(level.getBlockState(pos).getBlock());
                if (!job.blockHit && job.mood().likesBlock(name)) {
                    job.blockHit = true;
                }
                if (!job.colorHit && job.mood().likesColor(name)) {
                    job.colorHit = true;
                }
                if (job.blockHit && job.colorHit) {
                    job.done = true; // 双命中，提前收工
                    break;
                }
            }
            used++;
            stepCursor(job);
        }
        return used;
    }

    /** 游标三重循环推进：x 最快，z 其次，y 最慢（水平层先扫，区块命中率高）。 */
    private static void stepCursor(Job job) {
        job.cursor.setX(job.cursor.getX() + 1);
        if (job.cursor.getX() > job.maxX) {
            job.cursor.setX(job.minX);
            job.cursor.setZ(job.cursor.getZ() + 1);
            if (job.cursor.getZ() > job.maxZ) {
                job.cursor.setZ(job.minZ);
                job.cursor.setY(job.cursor.getY() + 1);
                if (job.cursor.getY() > job.maxY) {
                    job.done = true; // 全区扫完（没找齐就是没有）
                }
            }
        }
    }

    /** 方块注册名（带缓存）。 */
    private static String registryName(Block block) {
        String name = NAME_CACHE.get(block);
        if (name == null) {
            name = BuiltInRegistries.BLOCK.getKey(block).getPath();
            NAME_CACHE.put(block, name);
        }
        return name;
    }

    private static int currentDay(ServerLevel level) {
        return (int) (level.getDayTime() / 24000L);
    }

    /** 单个扫描任务：属于一个镇民，扫他自己归属的住宅区。 */
    private static final class Job {
        final TownZone zone;
        final int day;
        final Resident resident;
        final MoodState mood;
        final int minX, minY, minZ, maxX, maxY, maxZ;
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        boolean blockHit;
        boolean colorHit;
        boolean done;

        Job(TownZone zone, int day, Resident resident, MoodState mood) {
            this.zone = zone;
            this.day = day;
            this.resident = resident;
            this.mood = mood;
            this.minX = zone.min().getX();
            this.minY = zone.min().getY();
            this.minZ = zone.min().getZ();
            this.maxX = zone.max().getX();
            this.maxY = zone.max().getY();
            this.maxZ = zone.max().getZ();
            this.cursor.set(minX, minY, minZ);
        }

        MoodState mood() {
            return this.mood;
        }
    }
}
