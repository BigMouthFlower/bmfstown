package com.bmf.bmfstown.entity;

import com.bmf.bmfstown.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * 隐形座位实体（0.16 坐姿最小版，#67）：居民 {@code startRiding} 上来就是
 * 真坐姿（原版村民坐船同款先例），不碰模型渲染层。
 *
 * <h2>生命周期</h2>
 * <pre>
 * spawn（{@link #seat}）→ 居民骑乘 → 活动 {@link #ping()} 续坐
 *   → 活动结束/座位方块被挖/没人续坐 → {@link #evict()} 弹人自毁
 * </pre>
 *
 * <h2>三条设计规矩</h2>
 * <ul>
 *   <li><b>续坐制</b>：座位不认识活动，活动每 tick {@link #ping()} 一次；
 *       40t 没人续坐（活动被掐、存档重载后无人认领）→ 自动弹人自毁——
 *       绝不产生"永远坐在空气里的镇民"（存档重载兜底是硬需求）。</li>
 *   <li><b>零存档状态</b>：座位本身不写任何 NBT（位置由实体系统存）；
 *       读档后没有活动来 ping，40t 内自灭，旧档干净。</li>
 *   <li><b>不可交互</b>：无碰撞、不可选中、不可推动、不可伤害——玩家
 *       右键/攻击穿透它，直接作用到坐着的居民身上。</li>
 * </ul>
 */
public class SeatEntity extends Entity {

    /** 续坐超时：40t 没人 ping（2 秒）就当活动已经没了。 */
    private static final int PING_TIMEOUT_TICKS = 40;

    /**
     * 坐高偏移（相对座位方块底面）。实体要放在座面下方一点且<b>不得为负</b>：
     * 负值会让实体的 blockPosition 落进地板方块（2026-11-16 实测：坐姿用例
     * 的超时自取路径里居民卡在地板里走不到酒窖）。骑乘坐姿的髋部在模型
     * 空间里比实体坐标高约 0.7 格（HumanoidModel 腿根 12px），多出的那截
     * 由渲染层补偿——见 {@code ResidentRenderer} 的 SeatEntity 平移。
     * 调试指令 {@code /bmfstown seat <offset>} 可实时改并重放既有座位；
     * 改定后把值焊死在这里。
     */
    private static double yOffset = 0.45D;

    /** 座位方块锚点（创建时的方块位置；实体会被放到方块外，不能用
     *  blockPosition() 判断座位存活）。瞬态不存档——读档后无人续坐自毁。 */
    private BlockPos anchor;

    /** 最近一次续坐的 gameTime。新建即视为刚 ping 过；读档重载后无人 ping
     *  → 40t 内自毁（旧档/异常档兜底）。 */
    private long lastPingTick;

    public SeatEntity(EntityType<? extends SeatEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setInvisible(true);
        this.lastPingTick = this.level().getGameTime();
    }

    /** 调试指令用：改全局坐高并立即重放既有座位（乘客下 tick 自动跟随）。 */
    public static void setYOffset(double v) {
        yOffset = v;
    }

    public static double yOffset() {
        return yOffset;
    }

    /** 按当前全局坐高重放自己的位置（指令调参用，不换实体不断续坐）。 */
    public void applyOffset() {
        if (this.anchor != null && !this.level().isClientSide) {
            this.moveTo(this.anchor.getX() + 0.5D,
                    this.anchor.getY() + yOffset, this.anchor.getZ() + 0.5D);
        }
    }

    /** 在座位方块上放座位并让居民坐上（服务端）。返回 null = 骑乘失败。 */
    public static SeatEntity seat(Resident rider, BlockPos seatPos) {
        if (!(rider.level() instanceof ServerLevel sl)) {
            return null;
        }
        SeatEntity seat = ModEntities.SEAT.get().create(sl);
        if (seat == null) {
            return null;
        }
        seat.anchor = seatPos.immutable();
        // 坐高：骑乘模型髋部比实体坐标高约 0.7 格，实体要放到座面之下
        // （yOffset 详注见字段）
        seat.moveTo(seatPos.getX() + 0.5D, seatPos.getY() + yOffset,
                seatPos.getZ() + 0.5D, 0.0F, 0.0F);
        sl.addFreshEntity(seat);
        if (!rider.startRiding(seat, true)) {
            seat.discard();
            return null;
        }
        return seat;
    }

    /** 活动每 tick 续坐（断供 = 40t 后自毁）。 */
    public void ping() {
        this.lastPingTick = this.level().getGameTime();
    }

    /** 弹掉乘客并自毁（活动结束/座位方块没了/没人续坐）。 */
    public void evict() {
        if (!this.level().isClientSide) {
            this.ejectPassengers();
            this.discard();
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            return;
        }
        if (this.level().getGameTime() - this.lastPingTick > PING_TIMEOUT_TICKS) {
            this.evict();
            return;
        }
        // 座位方块没了（被挖/被改成别的）：弹人自毁——用锚点判，实体本体
        // 在负偏移下 blockPosition 已经落到隔壁方块了
        if (this.anchor == null
                || !SeatBlocks.isSeat(this.level().getBlockState(this.anchor))) {
            this.evict();
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        // 无同步数据
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        // 零存档状态：读档后无人续坐，40t 内自毁
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        // 同上
    }
}
