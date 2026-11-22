package com.bmf.bmfstown.entity;

import com.bmf.bmfstown.entity.domain.Status;
import com.bmf.bmfstown.town.ScanScheduler;
import com.bmf.bmfstown.town.TownZone;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 住宅区建材/颜色扫描（v0.8.1）。
 *
 * <h2>为什么摊派</h2>
 * 建材/颜色的每日结算原来是"以床为中心扫 9×5×9"——范围太小，玩家把
 * 客厅装修得漂漂亮亮也判定不到（实测方解石贴着放才能触发）。v0.8.1
 * 按用户决定改成<b>扫描分配的整个住宅区</b>。区的体积玩家说了算，
 * 不能保证小，所以扫描必须摊派（每 tick 限量、命中即停、按天过期）。
 *
 * <h2>调度</h2>
 * 队列、每 tick 预算、跨天丢弃、tick 去重全部交给 {@link ScanScheduler}。
 * 本类只负责两件事：<b>扫什么</b>（{@link HomeMaterialJob#advance}）
 * 和 <b>扫完怎么结算</b>（{@link HomeMaterialJob#onDone}）。
 *
 * <h2>结果回报</h2>
 * 扫描完成 → 立刻对委托它的镇民结算心情（建材/颜色各一条想法）。
 * 委托镇民中途被卸载/死亡则静默丢弃结果（它当天的结算已经发生过，
 * 想法损失可接受，不值得为它保持引用）。
 */
public final class HomeMaterialScanManager {

    /** 方块 → 注册名缓存：getBlockState 不分配，名字也没必要反复拼。 */
    private static final Map<Block, String> NAME_CACHE = new IdentityHashMap<>();

    private HomeMaterialScanManager() {
    }

    /**
     * 委托一次"本日住宅区材质扫描"。
     *
     * <p>每日结算时由 {@code Status.settleHomeMaterials} 调用。
     * 同镇民同日重复请求直接复用（幂等）；任务已完成则立刻用缓存结果结算。
     */
    public static void request(ServerLevel level, TownZone zone,
                               Resident resident, Status status) {
        HomeMaterialJob mine = ScanScheduler.find(level, HomeMaterialJob.class,
                j -> j.resident == resident);
        if (mine == null) {
            ScanScheduler.submit(level,
                    new HomeMaterialJob(zone, ScanScheduler.currentDay(level), resident, status));
            return;
        }
        if (mine.finished) {
            // 同日重复请求（reroll 等边缘场景）：直接用已扫出的结果
            mine.status.applyHomeMaterialResult(resident, level, mine.blockHit, mine.colorHit);
        }
        // 未完成：什么都不做——结果出来时会统一结算
    }

    /**
     * 推进一个任务至多 {@code budget} 格。
     *
     * @return 实际消耗的格数（未加载区块的格子跳过但也计数，保证有进度）
     */
    private static int scanChunkOfBlocks(HomeMaterialJob job, ServerLevel level, int budget) {
        int used = 0;
        while (used < budget && !job.finished) {
            BlockPos.MutableBlockPos pos = job.cursor;
            if (level.hasChunkAt(pos)) {
                String name = registryName(level.getBlockState(pos).getBlock());
                if (!job.blockHit && job.resident.getPrefs().likesBlock(name)) {
                    job.blockHit = true;
                }
                if (!job.colorHit && job.resident.getPrefs().likesColor(name)) {
                    job.colorHit = true;
                }
                if (job.blockHit && job.colorHit) {
                    job.finished = true; // 双命中，提前收工
                    break;
                }
            }
            used++;
            stepCursor(job);
        }
        return used;
    }

    /** 游标三重循环推进：x 最快，z 其次，y 最慢（水平层先扫，区块命中率高）。 */
    private static void stepCursor(HomeMaterialJob job) {
        job.cursor.setX(job.cursor.getX() + 1);
        if (job.cursor.getX() > job.maxX) {
            job.cursor.setX(job.minX);
            job.cursor.setZ(job.cursor.getZ() + 1);
            if (job.cursor.getZ() > job.maxZ) {
                job.cursor.setZ(job.minZ);
                job.cursor.setY(job.cursor.getY() + 1);
                if (job.cursor.getY() > job.maxY) {
                    job.finished = true; // 全区扫完（没找齐就是没有）
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

    /** 单个扫描任务：属于一个镇民，扫他自己归属的住宅区。 */
    private static final class HomeMaterialJob implements ScanScheduler.Task {
        final int day;
        final Resident resident;
        final Status status;
        final int minX, minY, minZ, maxX, maxY, maxZ;
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        boolean blockHit;
        boolean colorHit;
        boolean finished;

        HomeMaterialJob(TownZone zone, int day, Resident resident, Status status) {
            this.day = day;
            this.resident = resident;
            this.status = status;
            this.minX = zone.min().getX();
            this.minY = zone.min().getY();
            this.minZ = zone.min().getZ();
            this.maxX = zone.max().getX();
            this.maxY = zone.max().getY();
            this.maxZ = zone.max().getZ();
            this.cursor.set(minX, minY, minZ);
        }

        @Override
        public int day() {
            return this.day;
        }

        @Override
        public int advance(ServerLevel level, int budget) {
            return scanChunkOfBlocks(this, level, budget);
        }

        @Override
        public boolean done() {
            return this.finished;
        }

        @Override
        public void onDone(ServerLevel level) {
            if (!this.resident.isRemoved() && this.resident.isAlive()) {
                this.status.applyHomeMaterialResult(this.resident, level,
                        this.blockHit, this.colorHit);
            }
        }
    }
}