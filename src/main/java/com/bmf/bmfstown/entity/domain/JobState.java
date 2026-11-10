package com.bmf.bmfstown.entity.domain;

import com.bmf.bmfstown.entity.WorkMode;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.UUID;

/**
 * 岗位域：当前职业这份工作的"资产与开关"。
 *
 * <h2>六域重构（v0.8.2）</h2>
 * 旧版工人三件套绑定塞在 MoodState 里。现在独立成域，且用通用数据槽：
 * 每个职业的岗位资产自己存自己的键（工人写 order/enabled——几何上收到
 * 镇级工坊区登记表（v0.12），矿工写 mineTarget、厨师写 stove），
 * 换职业时互不干扰。
 *
 * <p>同时收纳工作模式（战士跟随/站岗选档——用户拍板：这是"这份工作怎么干"，
 * 属于岗位态不属于身份）与战士的岗哨点/跟随目标。
 *
 * <p>本域<b>不参与</b>档案同步包（客户端面板读的是 DATA_WORK /
 * DATA_WORKER_STATUS 位打包两条已验证通道）。
 */
public final class JobState {

    /** 工作模式（NONE/FOLLOW/GUARD_POST；将来 WORKER 等岗位扩展在这）。 */
    private WorkMode workMode = WorkMode.NONE;

    // ---- 战士岗位锚点（跟随目标/岗哨方块；NONE 时为空） ----

    private BlockPos guardPost;
    private UUID followPlayerId;

    // ---- 工人（通用槽键名；0/""/true = 缺省） ----

    /** 通用数据槽：各职业自己的岗位资产。 */
    private final CompoundTag data = new CompoundTag();

    // v0.12 工人重做：三件套（CraftTablePos/InputChestPos/OutputChestPos）已删——
    // 几何上收到镇级工坊区登记表（Town.WorkshopRegister），NPC 只留订单与开关。

    public static final String KEY_ORDER_ITEM = "OrderItem";
    public static final String KEY_WORKER_ENABLED = "WorkerEnabled";

    // ---- 读 ----

    public WorkMode workMode() {
        return this.workMode;
    }

    // ---- 武器偏好（决策 #49 手装导演，2026-11-09 用户拍板四档） ----

    /** 通用槽键：战士武器类别偏好（存枚举名；""=无偏好）。 */
    public static final String KEY_WEAPON_PREF = "WeaponPref";

    /** 战士武器偏好；未设/非法名回落 NONE（旧档兼容）。 */
    public com.bmf.bmfstown.entity.WeaponPref weaponPref() {
        return com.bmf.bmfstown.entity.WeaponPref.parse(this.data.getString(KEY_WEAPON_PREF));
    }

    public void setWeaponPref(com.bmf.bmfstown.entity.WeaponPref pref) {
        this.data.putString(KEY_WEAPON_PREF,
                pref == null || pref == com.bmf.bmfstown.entity.WeaponPref.NONE
                        ? "" : pref.name());
    }

    public BlockPos guardPost() {
        return this.guardPost;
    }

    public UUID followPlayerId() {
        return this.followPlayerId;
    }

    /** 当前订单（产物注册名；空 = 未设订单）。 */
    public String orderItem() {
        return this.data.getString(KEY_ORDER_ITEM);
    }

    /** 工人开工开关（false = 已停工，Goal 不激活）。 */
    public boolean workerEnabled() {
        return !this.data.contains(KEY_WORKER_ENABLED) || this.data.getBoolean(KEY_WORKER_ENABLED);
    }

    // ---- 写 ----

    public void setWorkMode(WorkMode mode) {
        this.workMode = mode == null ? WorkMode.NONE : mode;
    }

    public void setGuardPost(BlockPos pos) {
        this.guardPost = pos;
    }

    public void setFollowPlayerId(UUID id) {
        this.followPlayerId = id;
    }

    // ---- 学者图书馆认领（v0.11：自动认领，0 = 未认领）----

    public static final String KEY_LIBRARY_LECTERN = "LibraryLecternPos";
    public static final String KEY_LIBRARY_INPUT = "LibraryInputPos";

    public long libraryLecternPos() {
        return this.data.getLong(KEY_LIBRARY_LECTERN);
    }

    public long libraryInputPos() {
        return this.data.getLong(KEY_LIBRARY_INPUT);
    }

    public void putLibraryLectern(BlockPos pos) {
        this.data.putLong(KEY_LIBRARY_LECTERN, pos == null ? 0L : pos.asLong());
    }

    public void putLibraryInput(BlockPos pos) {
        this.data.putLong(KEY_LIBRARY_INPUT, pos == null ? 0L : pos.asLong());
    }

    /** 设置当前订单（产物注册名；服务端已复核白名单与线别）。 */
    public void setOrderItem(String product) {
        this.data.putString(KEY_ORDER_ITEM, product == null ? "" : product);
    }

    public void setWorkerEnabled(boolean enabled) {
        this.data.putBoolean(KEY_WORKER_ENABLED, enabled);
    }

    // ==========================================================
    //  序列化
    // ==========================================================

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("WorkMode", this.workMode.name());
        if (this.guardPost != null) {
            tag.putLong("GuardPost", this.guardPost.asLong());
        }
        if (this.followPlayerId != null) {
            tag.putUUID("FollowPlayer", this.followPlayerId);
        }
        if (!this.data.isEmpty()) {
            tag.put("Data", this.data);
        }
        return tag;
    }

    /** 实例级读回（六域常规读档 / GATTACA 快照复原用）。 */
    public void loadFrom(CompoundTag tag, CompoundTag legacyMoodTag, boolean legacy) {
        JobState j = load(tag, legacyMoodTag, legacy);
        this.workMode = j.workMode;
        this.guardPost = j.guardPost;
        this.followPlayerId = j.followPlayerId;
        for (String k : this.data.getAllKeys()) {
            this.data.remove(k);
        }
        this.data.merge(j.data);
    }

    /**
     * 从 NBT 读回。
     *
     * @param legacy true = 从旧键读：WorkMode/GuardPost/FollowPlayer 原是
     *               Resident 顶层键，工人绑定在 MoodState 子标签里
     *               （resident 传 null 时忽略那部分）
     */
    public static JobState load(CompoundTag tag, CompoundTag legacyMoodTag, boolean legacy) {
        JobState j = new JobState();
        WorkMode mode = WorkMode.NONE;
        if (tag.contains("WorkMode")) {
            try {
                mode = WorkMode.valueOf(tag.getString("WorkMode"));
            } catch (IllegalArgumentException ignored) {
                // 未知模式名：当作无工作
            }
        }
        j.workMode = mode;
        j.guardPost = tag.contains("GuardPost", Tag.TAG_ANY_NUMERIC)
                ? BlockPos.of(tag.getLong("GuardPost")) : null;
        j.followPlayerId = tag.hasUUID("FollowPlayer") ? tag.getUUID("FollowPlayer") : null;
        if (tag.contains("Data")) {
            j.data.merge(tag.getCompound("Data"));
        }
        // 旧版工人绑定在 MoodState 子标签（三件套键已废弃不再读；订单/开关保留）
        if (legacy && legacyMoodTag != null && legacyMoodTag.getBoolean("PrefsGenerated")) {
            j.data.putString(KEY_ORDER_ITEM,
                    legacyMoodTag.contains("OrderItem") ? legacyMoodTag.getString("OrderItem") : "");
            j.data.putBoolean(KEY_WORKER_ENABLED,
                    !legacyMoodTag.contains("WorkerEnabled") || legacyMoodTag.getBoolean("WorkerEnabled"));
        }
        return j;
    }
}
