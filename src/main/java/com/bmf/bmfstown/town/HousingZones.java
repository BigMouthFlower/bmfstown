package com.bmf.bmfstown.town;

import com.bmf.bmfstown.entity.Resident;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * 住宅区的容量与占用查询。
 *
 * <p><b>为什么单独提一个类：</b>"一个住宅区能住几个人"这个判断
 * 有两处要用——服务端分配校验（{@code ResidentMenu}）、
 * 村民睡觉时找床（{@code Resident}）。两处必须口径一致，
 * 否则会出现"能分配但住不进去"这种怪事。
 *
 * <h2>口径约定</h2>
 * <ul>
 *   <li><b>容量</b>：区域内的床方块数。只数 {@link BedPart#HEAD}，
 *       否则一张双人床会被算成两个床位。没有床 = 容量 0（住不了人）。</li>
 *   <li><b>入住人数</b>：{@code ResidentProfile.housingZoneId} 指向该区的村民数
 *       ——按<b>分配记录</b>统计而不是"人此刻在哪"：镇民出门干活/逛酒馆/跟玩家
 *       走远了也照算（2026-11-17 用户实测"显示的入住数对不上分配"修正）。</li>
 *   <li><b>床位被占</b>：床的上方一格 AABB 内有没有 {@link Pose#SLEEPING} 的实体。
 *       这是原版 {@code BedBlock} 判占用的同款口径，所以玩家躺上去也算占用。</li>
 * </ul>
 *
 * <p>全部运行时计算，不落盘——玩家拆一张床，容量立刻减一，
 * 不存在"存档里写着 3 张床但实际只有 2 张"的不一致。
 */
public final class HousingZones {

    /** 床位判定向上抬的高度：床方块本身 + 其上方一格（躺下时实体贴着床上方）。 */
    private static final double BED_OCCUPANT_HEIGHT = 1.0D;

    /** 扫描上限：40×40×40，容得下很大的住宅，又不会让单次 tick 卡住。 */
    private static final int MAX_SCAN = 40 * 40 * 40;

    private HousingZones() {
    }

    // ==========================================================
    //  床位扫描
    // ==========================================================

    /**
     * 区域内所有床的 HEAD 位置（未排序）。
     *
     * <p>超过 {@link #MAX_SCAN} 个方块就不再往下扫，直接返回已扫到的部分——
     * 宁可少算几张床，也不让一次扫描卡住整个服务器 tick。
     */
    public static List<BlockPos> bedsIn(ServerLevel level, TownZone zone) {
        List<BlockPos> beds = new ArrayList<>();
        int scanned = 0;
        for (BlockPos pos : BlockPos.betweenClosed(zone.min(), zone.max())) {
            if (++scanned > MAX_SCAN) {
                break;
            }
            BlockState state = level.getBlockState(pos);
            if (!state.is(BlockTags.BEDS)) {
                continue;
            }
            // 双人床有两格，只认 HEAD，否则容量翻倍
            if (state.hasProperty(BedBlock.PART) && state.getValue(BedBlock.PART) != BedPart.HEAD) {
                continue;
            }
            beds.add(pos.immutable()); // betweenClosed 复用可变对象，必须 immutable 收藏
        }
        return beds;
    }

    /** 区域容量 = 床的张数（HEAD 去重）。 */
    public static int capacity(ServerLevel level, TownZone zone) {
        return bedsIn(level, zone).size();
    }

    // ==========================================================
    //  占用查询
    // ==========================================================

    /**
     * 这张床是否已经有人躺着了。
     *
     * <p>用 {@link Pose#SLEEPING} 判定而不是自己维护一张占用表：
     * 玩家躺上去时 {@code Pose} 也是 SLEEPING，于是玩家和村民共用同一套
     * 互斥逻辑，天然不会出现"村民和玩家躺同一张床"。
     */
    public static boolean isBedOccupied(ServerLevel level, BlockPos bed) {
        AABB box = new AABB(
                bed.getX(), bed.getY(), bed.getZ(),
                bed.getX() + 1.0D, bed.getY() + BED_OCCUPANT_HEIGHT, bed.getZ() + 1.0D);
        return !level.getEntitiesOfClass(
                LivingEntity.class, box, e -> e.getPose() == Pose.SLEEPING).isEmpty();
    }

    /**
     * 该住宅区已入住的人数（按 {@code housingZoneId} 分配记录统计）。
     *
     * <p><b>口径（2026-11-17 修正）</b>：遍历本维度所有已加载镇民，数
     * {@code identity.housingZoneId} 指向该区的——与"谁住这儿"的账本一致，
     * 跟人此刻的物理位置无关。旧实现只扫住宅区外扩 16 格的在场实体，
     * 出门干活的镇民被漏掉，UI 显示的 n/床数 随位置漂移、还放过了超员分配。
     *
     * <p>唯一残余盲区：镇民所在区块未加载时实体不在列表里（少算不超员——
     * 分配校验宁可松不可错杀，重进游戏加载后数字即回归真实）。
     */
    public static int occupantCount(ServerLevel level, TownZone zone) {
        int count = 0;
        for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
            if (e instanceof Resident resident
                    && zone.id().equals(resident.getIdentity().housingZoneId())) {
                count++;
            }
        }
        return count;
    }

    /** 该住宅区还剩几个空位（超员时为负数，调用方自行决定怎么显示）。 */
    public static int freeSlots(ServerLevel level, TownZone zone) {
        return capacity(level, zone) - occupantCount(level, zone);
    }
}
