package com.bmf.bmfstown.entity.domain;

import com.bmf.bmfstown.entity.profile.ProfessionType;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.UUID;

/**
 * 身份域：这个镇民"是谁、属于谁"。
 *
 * <p>六域重构（v0.8.2）后 ResidentProfile 解散，归属四件套与职业落在这里。
 * 变动极少——只有建镇入镇、分房退房、指派职业时写。
 *
 * <p><b>设计约束</b>：本类只存数据不存逻辑；职业怎么"干活"是 Goal 层的事，
 * 这里只有"他是什么职业"这一个事实。
 */
public final class Identity {

    /** 职业。NONE = 无业。指派职业在面板/命令改这里。 */
    private ProfessionType profession = ProfessionType.NONE;

    /** 所属城镇；null = 无城镇（野镇民）。 */
    private UUID townId;
    /** 绑定的床（HEAD 坐标）；null = 没绑床。 */
    private BlockPos homePos;
    /** 绑定的住宅区 ID；null = 未分配。 */
    private UUID housingZoneId;
    /** 分配住宅的时间戳（毫秒）——床数变少时据此踢出"最新加入的"。 */
    private long housingAssignedAt;

    // ---- 读 ----

    public ProfessionType profession() {
        return this.profession;
    }

    public UUID townId() {
        return this.townId;
    }

    public BlockPos homePos() {
        return this.homePos;
    }

    public UUID housingZoneId() {
        return this.housingZoneId;
    }

    public long housingAssignedAt() {
        return this.housingAssignedAt;
    }

    // ---- 写（调用方负责随后 syncClient） ----

    public void setProfession(ProfessionType type) {
        this.profession = type == null ? ProfessionType.NONE : type;
    }

    public void setTownId(UUID value) {
        this.townId = value;
    }

    public void setHomePos(BlockPos value) {
        this.homePos = value;
    }

    /** 绑定/解绑住宅区。解绑（传 null）时顺手清分配时间戳。 */
    public void setHousingZoneId(UUID value) {
        this.housingZoneId = value;
        if (value == null) {
            this.housingAssignedAt = 0L;
        }
    }

    public void setHousingAssignedAt(long value) {
        this.housingAssignedAt = value;
    }

    /** 一次设置住宅绑定 + 分配时间（服务端分配入口用）。 */
    public void setHousing(UUID zoneId, long assignedAt) {
        this.housingZoneId = zoneId;
        this.housingAssignedAt = zoneId == null ? 0L : assignedAt;
    }

    /** 退镇：清空全部归属（镇被删/被踢时调用）。 */
    public void clearOwnership() {
        this.townId = null;
        this.homePos = null;
        this.housingZoneId = null;
        this.housingAssignedAt = 0L;
    }

    /** 实例级读回（GATTACA 快照复原用）。 */
    public void loadFrom(CompoundTag tag) {
        Identity loaded = load(tag, false);
        this.profession = loaded.profession;
        this.townId = loaded.townId;
        this.homePos = loaded.homePos;
        this.housingZoneId = loaded.housingZoneId;
        this.housingAssignedAt = loaded.housingAssignedAt;
    }

    // ==========================================================
    //  序列化
    // ==========================================================

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Profession", this.profession.getSerializedName());
        if (this.townId != null) {
            tag.putUUID("TownId", this.townId);
        }
        if (this.homePos != null) {
            tag.putLong("HomePos", this.homePos.asLong());
        }
        if (this.housingZoneId != null) {
            tag.putUUID("HousingZoneId", this.housingZoneId);
        }
        tag.putLong("HousingAssignedAt", this.housingAssignedAt);
        return tag;
    }

    /**
     * 从 NBT 读回。
     *
     * @param legacy true = 从旧版 {@code ResidentProfile} 子标签读（键名相同，
     *               只是外壳不同），兼容老存档
     */
    public static Identity load(CompoundTag tag, boolean legacy) {
        Identity id = new Identity();
        id.profession = ProfessionType.byName(tag.getString("Profession"));
        id.townId = tag.hasUUID("TownId") ? tag.getUUID("TownId") : null;
        id.homePos = tag.contains("HomePos", Tag.TAG_ANY_NUMERIC)
                ? BlockPos.of(tag.getLong("HomePos")) : null;
        id.housingZoneId = tag.hasUUID("HousingZoneId") ? tag.getUUID("HousingZoneId") : null;
        id.housingAssignedAt = tag.getLong("HousingAssignedAt");
        return id;
    }
}
