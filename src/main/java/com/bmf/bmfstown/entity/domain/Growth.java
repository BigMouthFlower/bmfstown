package com.bmf.bmfstown.entity.domain;

import com.bmf.bmfstown.entity.profile.Perks;
import com.bmf.bmfstown.entity.profile.ProfessionType;
import com.bmf.bmfstown.entity.profile.SkillType;
import com.bmf.bmfstown.entity.profile.SkillTiers;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 成长域：技能经验与 perk 选档（只增不减）。
 *
 * <h2>perk 按职业分格（v0.8.2 六域重构，用户拍板）</h2>
 * 旧版是平铺 List，战士/工人的选档混在一个袋子里靠 id 前缀区分；
 * 现在改为 {@code Map<职业, List<"节点:分支">>}——每棵树各装各的袋，
 * 加新职业（矿工/厨师）天然隔离。
 *
 * <p>老存档兼容：旧 flat List 按 id 前缀（"warrior."/"worker."）分拣进对应职业格。
 */
public final class Growth {

    /** 每个技能一条独立经验条，只增不减。 */
    private final Map<SkillType, Integer> skills = new EnumMap<>(SkillType.class);

    /** 每个职业一棵 perk 树的选档（元素格式 {@code "节点id:分支id"}）。 */
    private final Map<ProfessionType, List<String>> perks = new EnumMap<>(ProfessionType.class);

    /** 每个职业树的选档只读视图（快照用）。 */
    public Map<ProfessionType, List<String>> perksView() {
        return java.util.Collections.unmodifiableMap(this.perks);
    }

    // ---- 技能经验 ----

    /** 某个技能的累计经验（没涨过返回 0）。 */
    public int skillXp(SkillType type) {
        return type == null ? 0 : this.skills.getOrDefault(type, 0);
    }

    /** 某个技能的当前段位（0 生疏 ~ 9 神话，见 SkillTiers）。 */
    public int skillTier(SkillType type) {
        return SkillTiers.tierOf(this.skillXp(type));
    }

    /** 只读视图（UI/快照用）。 */
    public Map<SkillType, Integer> skillsView() {
        return java.util.Collections.unmodifiableMap(this.skills);
    }

    /** 增加技能经验（amount 只增不减，负数按 0 处理）。 */
    public void addSkillXp(SkillType type, int amount) {
        if (type == null || amount <= 0) {
            return;
        }
        this.skills.put(type, this.skillXp(type) + amount);
    }

    /** 直接设置某个技能的经验（命令用；常规成长走 addSkillXp）。 */
    public void setSkillXp(SkillType type, int value) {
        if (type != null) {
            this.skills.put(type, Math.max(0, value));
        }
    }

    // ---- perk 选档 ----

    /** 某职业树的全部选档（只读；没选过返回空列表）。 */
    public List<String> perkList(ProfessionType profession) {
        List<String> list = profession == null ? null : this.perks.get(profession);
        return list == null ? List.of() : List.copyOf(list);
    }

    /** 某节点当前选中的分支 id；未选返回 null。 */
    public String perkChoice(ProfessionType profession, String node) {
        List<String> list = profession == null ? null : this.perks.get(profession);
        if (list == null) {
            return null;
        }
        String prefix = node + ":";
        for (String p : list) {
            if (p.startsWith(prefix)) {
                return p.substring(prefix.length());
            }
        }
        return null;
    }

    /**
     * 选定某节点的一个分支，覆盖同节点的旧选择（免费改选，不锁死）。
     *
     * @param profession 选档归属的职业树（服务端已按 {@link Perks#canChoose} 复核）
     */
    public void setPerkChoice(ProfessionType profession, String node, String branch) {
        if (profession == null || node == null || branch == null) {
            return;
        }
        List<String> list = new ArrayList<>(this.perks.getOrDefault(profession, List.of()));
        String prefix = node + ":";
        list.removeIf(p -> p.startsWith(prefix));
        list.add(prefix + branch);
        this.perks.put(profession, list);
    }

    /** GATTACA 复制用：整体覆盖对方的成长数据。 */
    public void copyFrom(Growth other) {
        this.skills.clear();
        this.skills.putAll(other.skills);
        this.perks.clear();
        this.perks.putAll(other.perks);
    }

    /** 实例级读回（GATTACA 快照复原用）。 */
    public void loadFrom(CompoundTag tag) {
        Growth loaded = load(tag, false);
        copyFrom(loaded);
    }

    // ==========================================================
    //  序列化
    // ==========================================================

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        CompoundTag skillTag = new CompoundTag();
        for (Map.Entry<SkillType, Integer> e : this.skills.entrySet()) {
            if (e.getValue() > 0) {
                skillTag.putInt(e.getKey().getSerializedName(), e.getValue());
            }
        }
        if (!skillTag.isEmpty()) {
            tag.put("Skills", skillTag);
        }
        if (!this.perks.isEmpty()) {
            CompoundTag perkTag = new CompoundTag();
            for (Map.Entry<ProfessionType, List<String>> e : this.perks.entrySet()) {
                if (e.getValue().isEmpty()) {
                    continue;
                }
                ListTag list = new ListTag();
                for (String id : e.getValue()) {
                    list.add(net.minecraft.nbt.StringTag.valueOf(id));
                }
                perkTag.put(e.getKey().getSerializedName(), list);
            }
            if (!perkTag.isEmpty()) {
                tag.put("Perks", perkTag);
            }
        }
        return tag;
    }

    /**
     * 从 NBT 读回。
     *
     * @param legacy true = 旧版 {@code ResidentProfile} 子标签：技能键名相同；
     *               perk 是平铺 List，按 id 前缀分拣进职业格
     */
    public static Growth load(CompoundTag tag, boolean legacy) {
        Growth g = new Growth();
        if (tag.contains("Skills")) {
            CompoundTag skillTag = tag.getCompound("Skills");
            for (SkillType type : SkillType.values()) {
                if (skillTag.contains(type.getSerializedName())) {
                    g.skills.put(type, Math.max(0, skillTag.getInt(type.getSerializedName())));
                }
            }
        }
        if (legacy) {
            loadLegacyFlatPerks(g, tag);
        } else if (tag.contains("Perks")) {
            CompoundTag perkTag = tag.getCompound("Perks");
            for (ProfessionType p : ProfessionType.values()) {
                if (!perkTag.contains(p.getSerializedName(), Tag.TAG_LIST)) {
                    continue;
                }
                ListTag list = perkTag.getList(p.getSerializedName(), Tag.TAG_STRING);
                List<String> ids = new ArrayList<>();
                for (int i = 0; i < list.size(); i++) {
                    ids.add(list.getString(i));
                }
                if (!ids.isEmpty()) {
                    g.perks.put(p, ids);
                }
            }
        }
        return g;
    }

    /** 旧版平铺 perk List → 按前缀分拣（"warrior."→战士，"worker."→工人）。 */
    private static void loadLegacyFlatPerks(Growth g, CompoundTag tag) {
        if (!tag.contains("Perks", Tag.TAG_LIST)) {
            return;
        }
        ListTag perkTag = tag.getList("Perks", Tag.TAG_STRING);
        for (int i = 0; i < perkTag.size(); i++) {
            String id = perkTag.getString(i);
            ProfessionType owner = id.startsWith("worker.") ? ProfessionType.WORKER
                    : id.startsWith("warrior.") ? ProfessionType.WARRIOR : null;
            if (owner == null) {
                continue; // 未知前缀：丢弃（老数据里也只有这两类）
            }
            List<String> list = new ArrayList<>(g.perks.getOrDefault(owner, List.of()));
            list.add(id);
            g.perks.put(owner, list);
        }
    }
}
