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
 * 每个职业的岗位资产自己存自己的键（工人写 craftTable/input/output/order/enabled，
 * 将来矿工写 mineTarget、厨师写 stove），换职业时互不干扰。
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

    // ---- 工人绑定（通用槽键名；0/""/true = 缺省） ----

    /** 通用数据槽：各职业自己的岗位资产。 */
    private final CompoundTag data = new CompoundTag();

    public static final String KEY_CRAFT_TABLE = "CraftTablePos";
    public static final String KEY_INPUT_CHEST = "InputChestPos";
    public static final String KEY_OUTPUT_CHEST = "OutputChestPos";
    public static final String KEY_ORDER_ITEM = "OrderItem";
    public static final String KEY_WORKER_ENABLED = "WorkerEnabled";

    // ---- 读 ----

    public WorkMode workMode() {
        return this.workMode;
    }

    public BlockPos guardPost() {
        return this.guardPost;
    }

    public UUID followPlayerId() {
        return this.followPlayerId;
    }

    public long craftTablePos() {
        return this.data.getLong(KEY_CRAFT_TABLE);
    }

    public long inputChestPos() {
        return this.data.getLong(KEY_INPUT_CHEST);
    }

    public long outputChestPos() {
        return this.data.getLong(KEY_OUTPUT_CHEST);
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

    /** 一键落账工坊三件套（绑定流程完成时服务端调用）。 */
    public void setWorkshop(long table, long input, long output) {
        this.data.putLong(KEY_CRAFT_TABLE, table);
        this.data.putLong(KEY_INPUT_CHEST, input);
        this.data.putLong(KEY_OUTPUT_CHEST, output);
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
        // 旧版工人绑定在 MoodState 子标签
        if (legacy && legacyMoodTag != null && legacyMoodTag.getBoolean("PrefsGenerated")) {
            j.data.putLong(KEY_CRAFT_TABLE,
                    legacyMoodTag.contains("CraftTablePos") ? legacyMoodTag.getLong("CraftTablePos") : 0L);
            j.data.putLong(KEY_INPUT_CHEST,
                    legacyMoodTag.contains("InputChestPos") ? legacyMoodTag.getLong("InputChestPos") : 0L);
            j.data.putLong(KEY_OUTPUT_CHEST,
                    legacyMoodTag.contains("OutputChestPos") ? legacyMoodTag.getLong("OutputChestPos") : 0L);
            j.data.putString(KEY_ORDER_ITEM,
                    legacyMoodTag.contains("OrderItem") ? legacyMoodTag.getString("OrderItem") : "");
            j.data.putBoolean(KEY_WORKER_ENABLED,
                    !legacyMoodTag.contains("WorkerEnabled") || legacyMoodTag.getBoolean("WorkerEnabled"));
        }
        return j;
    }
}
