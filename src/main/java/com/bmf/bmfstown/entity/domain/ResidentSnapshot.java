package com.bmf.bmfstown.entity.domain;

import com.bmf.bmfstown.entity.profile.BlockMaterial;
import com.bmf.bmfstown.entity.profile.Hobbies;
import com.bmf.bmfstown.entity.profile.MobPref;
import com.bmf.bmfstown.entity.profile.PersonalityType;
import com.bmf.bmfstown.entity.profile.PrefAnimal;
import com.bmf.bmfstown.entity.profile.PrefColor;
import com.bmf.bmfstown.entity.profile.ProfessionType;
import com.bmf.bmfstown.entity.profile.SkillType;
import com.bmf.bmfstown.entity.profile.SkillTiers;
import com.bmf.bmfstown.entity.profile.WeatherPref;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 档案同步快照：Identity + Growth + Prefs + Status 四域的<b>只读视图</b>，
 * 替代旧 ResidentProfile 走 DATA_ARCHIVE 实体同步。
 *
 * <h2>为什么是快照不是活引用</h2>
 * SynchedEntityData 靠 {@code equals} 判断"值变了没有"再发包。
 * 域对象是可变的活数据，直接塞进去 equals 永远为 true（同一引用），客户端永远收不到更新。
 * 所以服务端每次变更后 {@code Resident.syncClient()} 构建一份<b>值拷贝</b>推进去；
 * 客户端从 entityData 读到的就是这份只读快照。
 *
 * <p>TraitState/JobState 不在快照里——它们走 MoodSyncPayload 扩展字段与
 * DATA_WORKER_STATUS 位打包两条已验证通道。
 *
 * <p>本类只存数据；等级/称号/心情档等派生值由字段现算。
 */
public final class ResidentSnapshot {

    // ---- Status ----
    private final int hunger;
    private final int mood;

    // ---- Identity ----
    private final ProfessionType profession;
    private final UUID townId;
    private final BlockPos homePos;
    private final UUID housingZoneId;
    private final long housingAssignedAt;

    // ---- Growth ----
    private final Map<SkillType, Integer> skills;
    private final Map<ProfessionType, List<String>> perks;

    // ---- Prefs ----
    private final Hobbies.Food hobbyFood;
    private final ProfessionType likedProfession;
    private final List<BlockMaterial> likedBlocks;
    private final PrefAnimal likedAnimal;
    private final PrefColor likedColor;
    private final boolean likesMusic;
    private final WeatherPref dislikedWeather;
    private final MobPref dislikedMob;
    private final PersonalityType personality;

    private ResidentSnapshot(int hunger, int mood,
            ProfessionType profession, UUID townId, BlockPos homePos,
            UUID housingZoneId, long housingAssignedAt,
            Map<SkillType, Integer> skills, Map<ProfessionType, List<String>> perks,
            Hobbies.Food hobbyFood, ProfessionType likedProfession,
            List<BlockMaterial> likedBlocks, PrefAnimal likedAnimal, PrefColor likedColor,
            boolean likesMusic, WeatherPref dislikedWeather, MobPref dislikedMob,
            PersonalityType personality) {
        this.hunger = hunger;
        this.mood = mood;
        this.profession = profession;
        this.townId = townId;
        this.homePos = homePos;
        this.housingZoneId = housingZoneId;
        this.housingAssignedAt = housingAssignedAt;
        this.skills = Map.copyOf(skills);
        this.perks = perks.isEmpty() ? Map.of() : Map.copyOf(perks);
        this.hobbyFood = hobbyFood;
        this.likedProfession = likedProfession;
        this.likedBlocks = List.copyOf(likedBlocks);
        this.likedAnimal = likedAnimal;
        this.likedColor = likedColor;
        this.likesMusic = likesMusic;
        this.dislikedWeather = dislikedWeather;
        this.dislikedMob = dislikedMob;
        this.personality = personality;
    }

    /** 服务端：从六域中的四域构建同步快照。 */
    public static ResidentSnapshot of(Identity identity, Growth growth,
            Prefs prefs, Status status) {
        return new ResidentSnapshot(
                status.hunger(), status.mood(),
                identity.profession(), identity.townId(), identity.homePos(),
                identity.housingZoneId(), identity.housingAssignedAt(),
                growth.skillsView(), growth.perksView(),
                prefs.hobbyFood(), prefs.likedProfession(),
                prefs.likedBlocks(), prefs.likedAnimal(), prefs.likedColor(),
                prefs.likesMusic(), prefs.dislikedWeather(), prefs.dislikedMob(),
                prefs.personality());
    }

    /** 客户端兜底默认（readNbt 返回 null / 新实体 define 时）。 */
    public static ResidentSnapshot defaults() {
        return new ResidentSnapshot(Status.MAX_HUNGER, 50,
                ProfessionType.NONE, null, null, null, 0L,
                Map.of(), Map.of(),
                Hobbies.Food.SWEET, ProfessionType.NONE,
                List.of(), null, null, false, null, null,
                PersonalityType.OPTIMIST);
    }

    // ==========================================================
    //  读接口（与旧 ResidentProfile 同名，调用点平移成本低）
    // ==========================================================

    public int hunger() {
        return this.hunger;
    }

    public int mood() {
        return this.mood;
    }

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

    /** 某个技能的累计经验（没涨过返回 0）。 */
    public int skillXp(SkillType type) {
        return type == null ? 0 : this.skills.getOrDefault(type, 0);
    }

    /** 某个技能的当前段位。 */
    public int skillTier(SkillType type) {
        return SkillTiers.tierOf(this.skillXp(type));
    }

    public Map<SkillType, Integer> skills() {
        return this.skills;
    }

    /** 某职业树的全部选档。 */
    public List<String> perks(ProfessionType profession) {
        List<String> list = profession == null ? null : this.perks.get(profession);
        return list == null ? List.of() : list;
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

    public Hobbies.Food hobbyFood() {
        return this.hobbyFood;
    }

    public ProfessionType likedProfession() {
        return this.likedProfession;
    }

    public List<BlockMaterial> likedBlocks() {
        return this.likedBlocks;
    }

    public PrefAnimal likedAnimal() {
        return this.likedAnimal;
    }

    public PrefColor likedColor() {
        return this.likedColor;
    }

    public boolean likesMusic() {
        return this.likesMusic;
    }

    public WeatherPref dislikedWeather() {
        return this.dislikedWeather;
    }

    public MobPref dislikedMob() {
        return this.dislikedMob;
    }

    public PersonalityType personality() {
        return this.personality;
    }

    // ---- 派生值 ----

    /** 当前职业主技能等级（由技能经验实时推导）。NONE 返回 0。 */
    public int level() {
        var skill = this.profession == null ? null : this.profession.mainSkill();
        if (skill == null) {
            return 0;
        }
        return SkillTiers.levelOf(this.skillXp(skill));
    }

    /** 是否已达该职业上限。 */
    public boolean isMaxLevel() {
        return this.profession != null && this.profession.isReal()
                && this.profession.maxLevel() > 0
                && level() >= this.profession.maxLevel();
    }

    /** 称号。用于 UI 显示，不暴露数字。 */
    public String title() {
        int lv = level();
        if (lv <= 0) {
            return "无";
        }
        if (lv <= 2) {
            return "新手";
        }
        if (lv <= 5) {
            return "熟练";
        }
        if (lv <= 8) {
            return "老手";
        }
        return "大师";
    }

    /** 称号的语言文件 key。 */
    public String titleKey() {
        int lv = level();
        String suffix = lv <= 0 ? "none"
                : lv <= 2 ? "novice"
                : lv <= 5 ? "skilled"
                : lv <= 8 ? "veteran"
                : "master";
        return "title.bmfstown." + suffix;
    }

    /** 缺口：距离吃饱还差多少点。 */
    public int hungerMissing() {
        return Status.MAX_HUNGER - this.hunger;
    }

    /** 能不能吃下某个营养值的食物而不浪费。 */
    public boolean canEatWithoutWaste(int nutrition) {
        return this.hunger + nutrition <= Status.MAX_HUNGER;
    }

    /** 心情分档。 */
    public Status.MoodTier moodTier() {
        return Status.moodTierOf(this.mood);
    }

    // ==========================================================
    //  NBT 往返（网络传输用，不落盘；落盘走域对象各自的 save）
    // ==========================================================

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Hunger", this.hunger);
        tag.putInt("Mood", this.mood);
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
                ListTag list = new ListTag();
                for (String id : e.getValue()) {
                    list.add(net.minecraft.nbt.StringTag.valueOf(id));
                }
                perkTag.put(e.getKey().getSerializedName(), list);
            }
            tag.put("Perks", perkTag);
        }

        tag.putString("HobbyFood", this.hobbyFood.getSerializedName());
        tag.putString("LikedProfession", this.likedProfession.getSerializedName());
        if (!this.likedBlocks.isEmpty()) {
            ListTag blocks = new ListTag();
            for (BlockMaterial m : this.likedBlocks) {
                blocks.add(net.minecraft.nbt.StringTag.valueOf(m.name()));
            }
            tag.put("LikedBlocks", blocks);
        }
        if (this.likedAnimal != null) {
            tag.putString("LikedAnimal", this.likedAnimal.name());
        }
        if (this.likedColor != null) {
            tag.putString("LikedColor", this.likedColor.name());
        }
        tag.putBoolean("LikesMusic", this.likesMusic);
        if (this.dislikedWeather != null) {
            tag.putString("DislikedWeather", this.dislikedWeather.name());
        }
        if (this.dislikedMob != null) {
            tag.putString("DislikedMob", this.dislikedMob.name());
        }
        tag.putString("Personality", this.personality.name());
        return tag;
    }

    public static ResidentSnapshot load(CompoundTag tag) {
        Map<SkillType, Integer> skills = new HashMap<>();
        if (tag.contains("Skills")) {
            CompoundTag skillTag = tag.getCompound("Skills");
            for (SkillType type : SkillType.values()) {
                if (skillTag.contains(type.getSerializedName())) {
                    skills.put(type, Math.max(0, skillTag.getInt(type.getSerializedName())));
                }
            }
        }
        Map<ProfessionType, List<String>> perks = new HashMap<>();
        if (tag.contains("Perks")) {
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
                perks.put(p, ids);
            }
        }
        List<BlockMaterial> blocks = new ArrayList<>();
        if (tag.contains("LikedBlocks", Tag.TAG_LIST)) {
            ListTag blockList = tag.getList("LikedBlocks", Tag.TAG_STRING);
            for (int i = 0; i < blockList.size(); i++) {
                try {
                    blocks.add(BlockMaterial.valueOf(blockList.getString(i)));
                } catch (IllegalArgumentException ignored) {
                    // 单个材质失效就跳过
                }
            }
        }
        return new ResidentSnapshot(
                tag.contains("Hunger") ? tag.getInt("Hunger") : Status.MAX_HUNGER,
                tag.contains("Mood") ? tag.getInt("Mood") : 50,
                ProfessionType.byName(tag.getString("Profession")),
                tag.hasUUID("TownId") ? tag.getUUID("TownId") : null,
                tag.contains("HomePos", Tag.TAG_ANY_NUMERIC)
                        ? BlockPos.of(tag.getLong("HomePos")) : null,
                tag.hasUUID("HousingZoneId") ? tag.getUUID("HousingZoneId") : null,
                tag.getLong("HousingAssignedAt"),
                skills, perks,
                Hobbies.Food.byName(tag.getString("HobbyFood")),
                ProfessionType.byName(tag.getString("LikedProfession")),
                blocks,
                getEnum(tag, "LikedAnimal", PrefAnimal.class),
                getEnum(tag, "LikedColor", PrefColor.class),
                tag.getBoolean("LikesMusic"),
                getEnum(tag, "DislikedWeather", WeatherPref.class),
                getEnum(tag, "DislikedMob", MobPref.class),
                getEnum(tag, "Personality", PersonalityType.class, PersonalityType.OPTIMIST));
    }

    private static <E extends Enum<E>> E getEnum(CompoundTag tag, String key,
            Class<E> type) {
        return getEnum(tag, key, type, null);
    }

    private static <E extends Enum<E>> E getEnum(CompoundTag tag, String key,
            Class<E> type, E fallback) {
        if (!tag.contains(key)) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, tag.getString(key));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
