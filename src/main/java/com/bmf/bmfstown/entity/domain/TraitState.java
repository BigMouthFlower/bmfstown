package com.bmf.bmfstown.entity.domain;

import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.entity.profile.TraitType;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;

/**
 * 特性域：特性本体 + 特性行为的私有运行态 + GATTACA 模仿状态。
 *
 * <h2>六域重构（v0.8.2）</h2>
 * 旧版特性塞在 MoodState 里（类名骗人的重灾区）。现在独立成域：
 * <ul>
 *   <li>{@link #trait}：特性本体（存档里只有这个 id 字符串）；</li>
 *   <li>{@link #data}：通用 NBT 槽——<b>每条特性自己读写自己的键</b>，
 *       加新特性不再碰任何共享结构；</li>
 *   <li>{@link #mimicSnapshot} 等模仿四件套：GATTACA 专属状态。</li>
 * </ul>
 *
 * <p>轻浮计时器/末影步上次路径是纯运行态（不落 NBT），作为普通字段留在这。
 *
 * <p>特性<b>效果</b>的实现位置不变：散在效果发生处的代码分支
 * （Resident.hurt 查鼓手、Perks.canChoose 查大智若愚、面板查海象）。
 */
public final class TraitState {

    /** 特性本体；null = 老档未抽，首 tick 补抽（ensureTrait）。 */
    private TraitType trait;

    /** 通用数据槽：每条特性自己的持久化键值（当前为空，留给后续特性）。 */
    private final CompoundTag data = new CompoundTag();

    /** 轻浮：漂浮检定计时器（运行态，不落档）。 */
    private int flightyTimer;

    /** 末影步：上次已传送的路径（引用比较识别"新行程"；运行态，不落档）。 */
    private net.minecraft.world.level.pathfinder.Path enderstepLastPath;

    // ---- GATTACA 模仿状态（完全复制对方数据含特性生效，想法保留自己） ----

    /** 被模仿者名字（空 = 未在模仿）。 */
    private String mimicTargetName = "";
    /** 被模仿者的特性 id（UI 括号显示用）。 */
    private String mimicTraitId = "";
    /** 复制到期游戏日（从复制开始的一天后）。 */
    private int mimicExpireDay = Integer.MIN_VALUE;
    /**
     * 复制开始时自己的快照（复原用）：
     * Identity + Growth + Prefs + Status(hunger/mood) + Name 五段。
     */
    private CompoundTag mimicSnapshot;

    // ---- 读 ----

    @Nullable
    public TraitType trait() {
        return this.trait;
    }

    public boolean isMimicking() {
        return !this.mimicTargetName.isEmpty();
    }

    public String mimicTargetName() {
        return this.mimicTargetName;
    }

    /** 模仿中的特性 id（被模仿者的；UI 特性行显示它并括号注明 GATTACA）。 */
    public String mimicTraitId() {
        return this.mimicTraitId;
    }

    // ---- 写 ----

    /** 调试命令直设特性（/bmfstown trait；置 null 再补抽等价于重抽）。 */
    public void setTraitForCommand(TraitType trait) {
        this.trait = trait;
    }

    /**
     * 特性补抽：老档特性键缺失时靠每 tick 的 null 判断补抽（幂等、廉价）。
     */
    public void ensureTrait(RandomSource random) {
        if (this.trait == null) {
            this.trait = TraitType.weightedRandom(random);
        }
    }

    /**
     * 特性逐 tick 行为（每 tick 从 customServerAiStep 调入，内部自持计时器）。
     */
    public void tickTraits(Resident resident) {
        // GATTACA 模仿到期检查。判据必须是 isMimicking() 而不是 trait==GATTACA——
        // 模仿开始时特性已被换成对方的，按 trait 查永远不命中（v0.8.1 实测教训：
        // 状态机检查不能依赖会被自身行为修改的状态）。
        if (isMimicking() && this.mimicExpireDay != Integer.MIN_VALUE
                && resident.level() instanceof ServerLevel sl
                && (int) (sl.getDayTime() / 24000L) >= this.mimicExpireDay) {
            restoreMimic(resident);
        }
        if (this.trait == TraitType.FLIGHTY) {
            // 每 30 秒检定一次 1/8 概率 → 平均约 4 分钟浮一次，3 秒（60 tick）
            if (++this.flightyTimer >= 600) {
                this.flightyTimer = 0;
                if (resident.getRandom().nextInt(8) == 0) {
                    resident.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                            net.minecraft.world.effect.MobEffects.LEVITATION, 60, 0,
                            false, true));
                }
            }
        } else if (this.trait == TraitType.ENDERSTEP) {
            tickEnderstep(resident);
        }
    }

    /**
     * 末影步（用户定稿 v2）：别人寻路是走过去，他是起点直接瞬移到终点，
     * 不掉血，起点终点粒子特效，无 CD 无概率。距离不足 3 格不传。
     */
    private void tickEnderstep(Resident resident) {
        if (!(resident.level() instanceof ServerLevel level)) {
            return;
        }
        var navigation = resident.getNavigation();
        net.minecraft.world.level.pathfinder.Path path = navigation.getPath();
        if (path == null) {
            this.enderstepLastPath = null;
            return; // 没在赶路（stop/到达后 getPath 为 null）
        }
        if (path != this.enderstepLastPath) {
            this.enderstepLastPath = path;
        } else {
            return; // 同一段路：已处理过，不重复传
        }
        if (path.isDone() || path.getNodeCount() <= 0) {
            return;
        }
        net.minecraft.world.level.pathfinder.Node end = path.getNode(path.getNodeCount() - 1);
        if (resident.distanceToSqr(end.x, end.y, end.z) < 3.0D * 3.0D) {
            return; // 太近：直接走
        }

        // 起点特效：一撮传送门粒子 + 音效
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.PORTAL,
                resident.getX(), resident.getY() + 0.8D, resident.getZ(),
                30, 0.4D, 0.8D, 0.4D, 0.3D);
        level.playSound(null, resident.blockPosition(),
                net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT,
                net.minecraft.sounds.SoundSource.NEUTRAL, 1.0F, 1.0F);

        if (resident.randomTeleport(end.x + 0.5D, end.y, end.z + 0.5D, false)) {
            // 终点特效：同款粒子 + 音效
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.PORTAL,
                    resident.getX(), resident.getY() + 0.8D, resident.getZ(),
                    20, 0.4D, 0.8D, 0.4D, 0.3D);
            level.playSound(null, resident.blockPosition(),
                    net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT,
                    net.minecraft.sounds.SoundSource.NEUTRAL, 1.0F, 1.0F);
            // 已在目的地：清掉旧路径，上层 Goal 的 isDone 检测自然接手下一阶段
            navigation.stop();
        }
        // randomTeleport 失败：不传，老实走（enderstepLastPath 已标记，不会重试）
    }

    // ==========================================================
    //  GATTACA 模仿（用户定稿）
    // ==========================================================

    /**
     * 开始模仿：
     * <ul>
     *   <li>快照自己的 Identity/Growth/Prefs/Status(hunger,mood)/名字；</li>
     *   <li>复制对方的成长（技能/perk）、偏好与性格、心情/饥饿值、特性、名字；</li>
     *   <li>归属四件套与想法保留自己（身份不随模仿变）。</li>
     * </ul>
     */
    public void startMimic(Resident self, Resident target, int currentDay) {
        CompoundTag snap = new CompoundTag();
        snap.put("Identity", self.getIdentity().save());
        snap.put("Growth", self.getGrowth().save());
        snap.put("Prefs", self.getPrefs().save());
        CompoundTag st = new CompoundTag();
        st.putInt("Hunger", self.getStatus().hunger());
        st.putInt("Mood", self.getStatus().mood());
        snap.put("StatusLite", st);
        snap.putString("Name", self.getName().getString());
        this.mimicSnapshot = snap;

        String targetName = target.getName().getString();
        // 复制对方的成长、偏好、心情/饥饿
        self.getGrowth().copyFrom(target.getGrowth());
        self.getPrefs().copyFrom(target.getPrefs());
        self.getStatus().setHunger(target.getStatus().hunger());
        self.getStatus().setMood(target.getStatus().mood());
        // 特性复制并生效
        this.mimicTraitId = target.getTraitState().trait() == null
                ? "" : target.getTraitState().trait().id();
        if (target.getTraitState().trait() != null) {
            this.trait = target.getTraitState().trait();
        }
        // 名字也变
        self.setCustomName(net.minecraft.network.chat.Component.literal(targetName));

        this.mimicTargetName = targetName;
        this.mimicExpireDay = currentDay + 1;
        self.syncClient();
    }

    /**
     * 复制到期复原：快照覆盖回复制前的数据（期间获取的一并丢弃）。
     * 想法不读快照——模仿期间产生的新想法保留。
     */
    public void restoreMimic(Resident self) {
        CompoundTag snap = this.mimicSnapshot;
        if (snap != null) {
            self.getIdentity().loadFrom(snap.getCompound("Identity"));
            self.getGrowth().loadFrom(snap.getCompound("Growth"));
            self.getPrefs().loadFrom(snap.getCompound("Prefs"));
            CompoundTag st = snap.getCompound("StatusLite");
            self.getStatus().setHunger(st.contains("Hunger") ? st.getInt("Hunger") : Status.MAX_HUNGER);
            self.getStatus().setMood(st.contains("Mood") ? st.getInt("Mood") : 50);
            if (snap.contains("Name")) {
                self.setCustomName(net.minecraft.network.chat.Component.literal(snap.getString("Name")));
            }
        }
        // 双保险：复原后本尊特性恒为 GATTACA
        this.trait = TraitType.GATTACA;
        this.mimicTargetName = "";
        this.mimicTraitId = "";
        this.mimicExpireDay = Integer.MIN_VALUE;
        this.mimicSnapshot = null;
        self.syncClient();
    }

    // ==========================================================
    //  序列化
    // ==========================================================

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("TraitId", this.trait == null ? "" : this.trait.id());
        tag.putString("MimicTarget", this.mimicTargetName);
        tag.putString("MimicTraitId", this.mimicTraitId);
        tag.putInt("MimicExpireDay", this.mimicExpireDay);
        if (this.mimicSnapshot != null) {
            tag.put("MimicSnapshot", this.mimicSnapshot);
        }
        if (!this.data.isEmpty()) {
            tag.put("Data", this.data);
        }
        return tag;
    }

    /** 实例级读回（六域常规读档 / GATTACA 快照复原用）。 */
    public void loadFrom(CompoundTag tag) {
        TraitState loaded = load(tag, false);
        this.trait = loaded.trait;
        this.mimicTargetName = loaded.mimicTargetName;
        this.mimicTraitId = loaded.mimicTraitId;
        this.mimicExpireDay = loaded.mimicExpireDay;
        this.mimicSnapshot = loaded.mimicSnapshot;
        for (String k : this.data.getAllKeys()) {
            this.data.remove(k);
        }
        this.data.merge(loaded.data);
    }

    /** @param legacy true = 从旧版 MoodState 子标签读（键名相同）。 */
    public static TraitState load(CompoundTag tag, boolean legacy) {
        TraitState t = new TraitState();
        String traitId = tag.contains("TraitId") ? tag.getString("TraitId") : "";
        t.trait = traitId.isEmpty() ? null : TraitType.byId(traitId);
        t.mimicTargetName = tag.contains("MimicTarget") ? tag.getString("MimicTarget") : "";
        t.mimicTraitId = tag.getString("MimicTraitId");
        t.mimicExpireDay = tag.contains("MimicExpireDay")
                ? tag.getInt("MimicExpireDay") : Integer.MIN_VALUE;
        t.mimicSnapshot = tag.contains("MimicSnapshot")
                ? tag.getCompound("MimicSnapshot").copy() : null;
        if (tag.contains("Data")) {
            t.data.merge(tag.getCompound("Data"));
        }
        return t;
    }
}
