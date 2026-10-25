package com.bmf.bmfstown.town;

import com.bmf.bmfstown.BmfTown;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 城镇物资统计管理器（v0.8.1）：簿记员的数据底座。
 *
 * <h2>成本设计（用户定稿：一天更新一次）</h2>
 * 扫描 = 遍历城镇外框内的所有方块找容器 + 读格子内容。全量扫很贵，
 * 所以：<b>每 tick 限量摊派</b>（{@link #BLOCKS_PER_TICK}，与建材扫描同款），
 * 中型镇几秒扫完，任何单 tick 开销毫秒级。统计结果缓存到第二天。
 *
 * <h2>数据形状</h2>
 * <ul>
 *   <li>{@code totals}：物品 → 全镇总数（统计页展示，精度按簿记员段位）；</li>
 *   <li>{@code where}：物品 → 装着它的容器坐标（寻物带路用，每物品留前 32 处）。</li>
 * </ul>
 *
 * <h2>驱动</h2>
 * 与建材扫描同款：{@code Resident.customServerAiStep} 顺手调
 * {@link #tick(ServerLevel)}（内部按 gameTime 去重）。不持久化——
 * 加载后首次查询自动重扫（几秒的事，不值得写盘）。
 */
public final class TownCensusManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 每 tick 全服扫描总量（格）。 */
    private static final int BLOCKS_PER_TICK = 4096;

    /** 每个物品最多记录多少个容器位置（寻物用，防海量箱子撑爆内存）。 */
    private static final int MAX_POSITIONS_PER_ITEM = 32;

    /** 进行中的扫描任务，按维度分桶。 */
    private static final Map<String, List<Job>> JOBS = new HashMap<>();

    /** 已完成的统计：城镇 UUID → 结果。内存态，读档后首次查询重扫。 */
    private static final Map<UUID, Census> RESULTS = new HashMap<>();

    /** tick 去重（与建材扫描同款）。 */
    private static ServerLevel lastTickedLevel;
    private static long lastTickedGameTime = Long.MIN_VALUE;

    private TownCensusManager() {
    }

    /** 一次统计结果：总数 + 容器位置索引。 */
    public record Census(int day, Map<Item, Integer> totals, Map<Item, List<BlockPos>> where) {
    }

    /**
     * 请求统计：当天已有新鲜结果直接返回；否则委托扫描（幂等），
     * 扫描完成后自动推送给请求者（不用再点第二次）。
     *
     * @return 当天的新鲜结果，没有（正在扫/还没扫过）返回 null
     */
    public static Census request(ServerLevel level, Town town,
            com.bmf.bmfstown.entity.Resident bookkeeper,
            net.minecraft.server.level.ServerPlayer requester) {
        Census fresh = RESULTS.get(town.id());
        if (fresh != null && fresh.day() == currentDay(level)) {
            return fresh;
        }
        int day = currentDay(level);
        List<Job> list = JOBS.computeIfAbsent(level.dimension().location().toString(),
                k -> new ArrayList<>());
        for (Job job : list) {
            if (job.townId.equals(town.id())) {
                return null; // 今天已经在扫了
            }
        }
        list.add(new Job(town, day, bookkeeper, requester));
        LOGGER.info("[BmfTown][簿记] 城镇 {} 委托物资统计（当日首次，完成后推送给 {}）",
                town.name(), requester.getName().getString());
        return null;
    }

    /** 直接取已有结果（不关心新鲜度，查询历史用）。 */
    public static Census peek(Town town) {
        return RESULTS.get(town.id());
    }

    /** 清掉某镇的统计（强制下次重扫）。 */
    public static void invalidate(Town town) {
        RESULTS.remove(town.id());
    }

    /**
     * 每 tick 推进扫描。由镇民 AI tick 顺手驱动（内部按 gameTime 去重）。
     */
    public static void tick(ServerLevel level) {
        if (level == lastTickedLevel && level.getGameTime() == lastTickedGameTime) {
            return;
        }
        lastTickedLevel = level;
        lastTickedGameTime = level.getGameTime();

        List<Job> list = JOBS.get(level.dimension().location().toString());
        if (list == null || list.isEmpty()) {
            return;
        }
        int budget = BLOCKS_PER_TICK;
        Iterator<Job> it = list.iterator();
        while (it.hasNext() && budget > 0) {
            Job job = it.next();
            budget -= scanBlocks(job, level, budget);
            if (job.done) {
                it.remove();
                Census census = job.finish();
                RESULTS.put(job.townId, census);
                LOGGER.info("[BmfTown][簿记] 城镇 {} 统计完成：{} 种物品",
                        job.townName, job.totals.size());
                // 完成即推送：请求者在线就直接收到数据并弹出界面
                if (job.requester != null && !job.requester.hasDisconnected()
                        && job.bookkeeper != null && job.bookkeeper.isAlive()) {
                    com.bmf.bmfstown.network.ModNetworking.playCensusSync(
                            job.requester, job.bookkeeper, job.town, census);
                }
            }
        }
    }

    /** 推进一个任务至多 budget 格。 */
    private static int scanBlocks(Job job, ServerLevel level, int budget) {
        int used = 0;
        while (used < budget && !job.done) {
            BlockPos.MutableBlockPos pos = job.cursor;
            if (level.hasChunkAt(pos) && level.isLoaded(pos)) {
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof Container container) {
                    recordContainer(job, pos.immutable(), container);
                } else if (be instanceof net.minecraft.world.level.block.entity.JukeboxBlockEntity juke) {
                    // 唱片机不是 Container（唱片机里的唱片会被漏统计），单独收录
                    ItemStack disc = juke.getTheItem();
                    if (!disc.isEmpty()) {
                        recordStack(job, pos.immutable(), disc);
                    }
                }
            }
            used++;
            stepCursor(job);
        }
        return used;
    }

    /** 读一个容器的全部格子，聚合进统计。 */
    private static void recordContainer(Job job, BlockPos pos, Container container) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            recordStack(job, pos, container.getItem(i));
        }
    }

    /** 聚合一个物品堆。 */
    private static void recordStack(Job job, BlockPos pos, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        Item item = stack.getItem();
        job.totals.merge(item, stack.getCount(), Integer::sum);
        List<BlockPos> where = job.where.computeIfAbsent(item, k -> new ArrayList<>());
        if (where.size() < MAX_POSITIONS_PER_ITEM && !where.contains(pos)) {
            where.add(pos);
        }
    }

    /** 游标三重循环推进（与建材扫描同款：x 快 z 次 y 慢）。 */
    private static void stepCursor(Job job) {
        BlockPos.MutableBlockPos cursor = job.cursor;
        cursor.setX(cursor.getX() + 1);
        if (cursor.getX() > job.maxX) {
            cursor.setX(job.minX);
            cursor.setZ(cursor.getZ() + 1);
            if (cursor.getZ() > job.maxZ) {
                cursor.setZ(job.minZ);
                cursor.setY(cursor.getY() + 1);
                if (cursor.getY() > job.maxY) {
                    job.done = true;
                }
            }
        }
    }

    private static int currentDay(ServerLevel level) {
        return (int) (level.getDayTime() / 24000L);
    }

    /** 单个扫描任务：扫一个城镇的外框。 */
    private static final class Job {
        final UUID townId;
        final String townName;
        final int day;
        final com.bmf.bmfstown.entity.Resident bookkeeper;
        final net.minecraft.server.level.ServerPlayer requester;
        final Town town;
        final int minX, minY, minZ, maxX, maxY, maxZ;
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        final Map<Item, Integer> totals = new LinkedHashMap<>();
        final Map<Item, List<BlockPos>> where = new LinkedHashMap<>();
        boolean done;

        Job(Town town, int day, com.bmf.bmfstown.entity.Resident bookkeeper,
                net.minecraft.server.level.ServerPlayer requester) {
            this.townId = town.id();
            this.townName = town.name();
            this.day = day;
            this.town = town;
            this.bookkeeper = bookkeeper;
            this.requester = requester;
            this.minX = town.min().getX();
            this.minY = town.min().getY();
            this.minZ = town.min().getZ();
            this.maxX = town.max().getX();
            this.maxY = town.max().getY();
            this.maxZ = town.max().getZ();
            this.cursor.set(minX, minY, minZ);
        }

        Census finish() {
            return new Census(day, Map.copyOf(totals), Map.copyOf(where));
        }
    }
}
