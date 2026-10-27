package com.bmf.bmfstown.entity.domain;

import com.bmf.bmfstown.entity.profile.BlockMaterial;
import com.bmf.bmfstown.entity.profile.Hobbies;
import com.bmf.bmfstown.entity.profile.MobPref;
import com.bmf.bmfstown.entity.profile.PersonalityType;
import com.bmf.bmfstown.entity.profile.PrefAnimal;
import com.bmf.bmfstown.entity.profile.PrefColor;
import com.bmf.bmfstown.entity.profile.ProfessionType;
import com.bmf.bmfstown.entity.profile.WeatherPref;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 偏好域：这个镇民"喜欢什么、讨厌什么"（含性格）。
 *
 * <h2>统一容器（v0.8.2 六域重构核心，用户拍板）</h2>
 * 旧版喜好散成 8 个平铺字段，每加一种喜好要动字段+NBT+reroll+GATTACA 复制+显示
 * 五处；现在收进 {@code Map<PrefKind, Object>}——加新喜好 = 加一个
 * {@link PrefKind} 枚举值 + 在对应系统加一个判定点，结构零改动。
 *
 * <p>性格（personality）不是"喜欢/讨厌"，但它是 reroll/GATTACA 复制/
 * 喜好页显示的同批数据，放本域单独字段。
 *
 * <p>懒生成：出生不抽，首次用到时 {@link #ensureGenerated} 补抽（老档兼容靠它）。
 */
public final class Prefs {

    /** 偏好类别。每个类别自带：抽取规则、NBT 读写。 */
    public enum PrefKind {
        /** 口味（必出：甜 或 肉）。判定点：FoodTraits.matches。 */
        FOOD {
            @Override
            Object roll(RandomSource random) {
                return Hobbies.Food.random(random);
            }

            @Override
            void saveValue(CompoundTag tag, Object v) {
                tag.putString("HobbyFood", ((Hobbies.Food) v).getSerializedName());
            }

            @Override
            Object loadValue(CompoundTag tag) {
                return Hobbies.Food.byName(tag.getString("HobbyFood"));
            }
        },
        /** 喜欢的职业（必出）。判定点：每日结算 jobLiked。 */
        LIKED_PROFESSION {
            @Override
            Object roll(RandomSource random) {
                return randomRealProfession(random, null);
            }

            @Override
            void saveValue(CompoundTag tag, Object v) {
                tag.putString("LikedProfession", ((ProfessionType) v).getSerializedName());
            }

            @Override
            Object loadValue(CompoundTag tag) {
                // 老档兜底：key 缺失/失效时随机补一个真实职业
                if (tag.contains("LikedProfession")) {
                    ProfessionType p = ProfessionType.byName(tag.getString("LikedProfession"));
                    if (p.isReal()) {
                        return p;
                    }
                }
                return randomRealProfession(RandomSource.create(), null);
            }
        },
        /** 喜欢的建筑材质（1/2 概率 × 2 次，0~2 种）。判定点：住宅材质扫描。 */
        BLOCK {
            @Override
            Object roll(RandomSource random) {
                List<BlockMaterial> blocks = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    if (random.nextBoolean()) {
                        BlockMaterial pick = BlockMaterial.random(random);
                        if (!blocks.contains(pick)) {
                            blocks.add(pick);
                        }
                    }
                }
                return List.copyOf(blocks);
            }

            @Override
            void saveValue(CompoundTag tag, Object v) {
                ListTag blocks = new ListTag();
                for (BlockMaterial m : (List<BlockMaterial>) v) {
                    blocks.add(net.minecraft.nbt.StringTag.valueOf(m.name()));
                }
                tag.put("LikedBlocks", blocks);
            }

            @Override
            Object loadValue(CompoundTag tag) {
                if (!tag.contains("LikedBlocks", Tag.TAG_LIST)) {
                    return List.of();
                }
                ListTag blocks = tag.getList("LikedBlocks", Tag.TAG_STRING);
                List<BlockMaterial> parsed = new ArrayList<>();
                for (int i = 0; i < blocks.size(); i++) {
                    try {
                        BlockMaterial m = BlockMaterial.valueOf(blocks.getString(i));
                        if (!parsed.contains(m)) {
                            parsed.add(m);
                        }
                    } catch (IllegalArgumentException ignored) {
                        // 单个材质失效就跳过，不影响其他偏好
                    }
                }
                return List.copyOf(parsed);
            }
        },
        /** 喜欢的动物（1/2 概率）。判定点：感知扫描。 */
        ANIMAL {
            @Override
            Object roll(RandomSource random) {
                return random.nextBoolean() ? PrefAnimal.random(random) : null;
            }

            @Override
            void saveValue(CompoundTag tag, Object v) {
                if (v != null) {
                    tag.putString("LikedAnimal", ((PrefAnimal) v).name());
                }
            }

            @Override
            Object loadValue(CompoundTag tag) {
                return getEnum(tag, "LikedAnimal", PrefAnimal.class, null);
            }
        },
        /** 喜欢的颜色（1/2 概率）。判定点：住宅颜色扫描。 */
        COLOR {
            @Override
            Object roll(RandomSource random) {
                return random.nextBoolean() ? PrefColor.random(random) : null;
            }

            @Override
            void saveValue(CompoundTag tag, Object v) {
                if (v != null) {
                    tag.putString("LikedColor", ((PrefColor) v).name());
                }
            }

            @Override
            Object loadValue(CompoundTag tag) {
                return getEnum(tag, "LikedColor", PrefColor.class, null);
            }
        },
        /** 喜欢音乐（1/2 概率）。判定点：唱片机感知。 */
        MUSIC {
            @Override
            Object roll(RandomSource random) {
                return random.nextBoolean();
            }

            @Override
            void saveValue(CompoundTag tag, Object v) {
                tag.putBoolean("LikesMusic", (Boolean) v);
            }

            @Override
            Object loadValue(CompoundTag tag) {
                return tag.getBoolean("LikesMusic");
            }
        },
        /** 讨厌的天气（1/3 概率，权重 晴1/雨15/雷暴5）。判定点：天气感知。 */
        WEATHER {
            @Override
            Object roll(RandomSource random) {
                return random.nextInt(3) == 0 ? WeatherPref.randomDisliked(random) : null;
            }

            @Override
            void saveValue(CompoundTag tag, Object v) {
                if (v != null) {
                    tag.putString("DislikedWeather", ((WeatherPref) v).name());
                }
            }

            @Override
            Object loadValue(CompoundTag tag) {
                return getEnum(tag, "DislikedWeather", WeatherPref.class, null);
            }
        },
        /** 讨厌的敌对生物（1/4 概率，14 选 1）。判定点：生物感知。 */
        MOB {
            @Override
            Object roll(RandomSource random) {
                return random.nextInt(4) == 0 ? MobPref.random(random) : null;
            }

            @Override
            void saveValue(CompoundTag tag, Object v) {
                if (v != null) {
                    tag.putString("DislikedMob", ((MobPref) v).name());
                }
            }

            @Override
            Object loadValue(CompoundTag tag) {
                return getEnum(tag, "DislikedMob", MobPref.class, null);
            }
        };

        /** 抽一个本类别的值（可空 = 该类别本胎没出）。 */
        abstract Object roll(RandomSource random);

        /** 写 NBT（key 约定与旧版平铺字段一致，老档天然兼容）。 */
        abstract void saveValue(CompoundTag tag, Object v);

        /** 读 NBT（缺 key 返回 null/false/空列表）。 */
        abstract Object loadValue(CompoundTag tag);

        /** 从真实职业里均匀抽一个，排除 exclude（好恶互斥用；当前只有"喜欢"）。 */
        static ProfessionType randomRealProfession(RandomSource random,
                ProfessionType exclude) {
            ProfessionType[] real = java.util.Arrays.stream(ProfessionType.values())
                    .filter(ProfessionType::isReal)
                    .toArray(ProfessionType[]::new);
            if (real.length <= 1 || real.length == 1 && real[0] == exclude) {
                return real.length == 0 ? ProfessionType.NONE : real[0];
            }
            while (true) {
                ProfessionType pick = real[random.nextInt(real.length)];
                if (pick != exclude) {
                    return pick;
                }
            }
        }
    }

    private static <E extends Enum<E>> E getEnum(CompoundTag tag, String key,
            Class<E> type, E fallback) {
        if (!tag.contains(key)) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, tag.getString(key));
        } catch (IllegalArgumentException ignored) {
            return fallback; // 枚举被改名/删值时的兜底
        }
    }

    /** 偏好值仓库：key = 类别，value 类型见各枚举值注释。 */
    private final Map<PrefKind, Object> values = new EnumMap<>(PrefKind.class);

    /** 性格标签（必出 1 条，9 选 1，终生不变——除非 reroll/GATTACA）。 */
    private PersonalityType personality = PersonalityType.OPTIMIST;

    /** 偏好是否已生成。false = 下次 ensureGenerated 时抽取并落档。 */
    private boolean generated = false;

    // ---- 懒生成 / 重摇 ----

    public boolean generated() {
        return this.generated;
    }

    /** 第一次用到时抽取全部偏好（幂等；已生成直接返回）。 */
    public void ensureGenerated(RandomSource random) {
        if (this.generated) {
            return;
        }
        this.generated = true;
        for (PrefKind kind : PrefKind.values()) {
            this.values.put(kind, kind.roll(random));
        }
        this.personality = PersonalityType.random(random);
    }

    /**
     * 重摇偏好与性格（调试命令 /bmfstown reroll 用）。
     * 打回未生成再抽一遍——等价于"这个镇民今天刚出生"。
     */
    public void reroll(RandomSource random) {
        this.generated = false;
        ensureGenerated(random);
    }

    // ---- 读（类型安全封装；调用前应先 ensureGenerated） ----

    @SuppressWarnings("unchecked")
    private <T> T get(PrefKind kind) {
        return (T) this.values.get(kind);
    }

    public Hobbies.Food hobbyFood() {
        Hobbies.Food v = get(PrefKind.FOOD);
        return v == null ? Hobbies.Food.SWEET : v;
    }

    public ProfessionType likedProfession() {
        ProfessionType v = get(PrefKind.LIKED_PROFESSION);
        return v == null ? ProfessionType.NONE : v;
    }

    @SuppressWarnings("unchecked")
    public List<BlockMaterial> likedBlocks() {
        List<BlockMaterial> v = get(PrefKind.BLOCK);
        return v == null ? List.of() : v;
    }

    public PrefAnimal likedAnimal() {
        return get(PrefKind.ANIMAL);
    }

    public PrefColor likedColor() {
        return get(PrefKind.COLOR);
    }

    public boolean likesMusic() {
        Boolean v = get(PrefKind.MUSIC);
        return v != null && v;
    }

    public WeatherPref dislikedWeather() {
        return get(PrefKind.WEATHER);
    }

    public MobPref dislikedMob() {
        return get(PrefKind.MOB);
    }

    public PersonalityType personality() {
        return this.personality;
    }

    // ---- 写（reroll/GATTACA 走整体替换；单独写入口留给将来） ----

    /** GATTACA 复制用：整体覆盖对方的偏好与性格。 */
    public void copyFrom(Prefs other) {
        this.values.clear();
        this.values.putAll(other.values);
        this.personality = other.personality;
        this.generated = true;
    }

    /** 实例级读回（GATTACA 快照复原用）。 */
    public void loadFrom(CompoundTag tag) {
        Prefs loaded = load(tag, false);
        copyFrom(loaded);
    }

    // ---- 判定 helper（住宅扫描逐格询问用） ----

    public boolean likesBlock(String registryName) {
        for (BlockMaterial m : likedBlocks()) {
            if (m.matches(registryName)) {
                return true;
            }
        }
        return false;
    }

    public boolean likesColor(String registryName) {
        PrefColor c = likedColor();
        return c != null && c.matches(registryName);
    }

    /**
     * 偏好的紧凑网络编码（MoodSyncPayload 用，格式与旧版完全一致）：
     * {@code blocks|animal|color|music|weather|mob|personality}，
     * blocks 段内逗号分隔；空段 = 没有这项偏好。
     */
    public String prefsString() {
        StringBuilder blocks = new StringBuilder();
        for (BlockMaterial m : likedBlocks()) {
            if (blocks.length() > 0) {
                blocks.append(',');
            }
            blocks.append(m.name());
        }
        return String.join("|",
                blocks.toString(),
                likedAnimal() != null ? likedAnimal().name() : "",
                likedColor() != null ? likedColor().name() : "",
                likesMusic() ? "1" : "0",
                dislikedWeather() != null ? dislikedWeather().name() : "",
                dislikedMob() != null ? dislikedMob().name() : "",
                this.personality.name());
    }

    // ==========================================================
    //  序列化（键名与旧版平铺字段一致 → 老档直接可读）
    // ==========================================================

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Generated", this.generated);
        if (this.generated) {
            for (PrefKind kind : PrefKind.values()) {
                Object v = this.values.get(kind);
                if (v != null) {
                    kind.saveValue(tag, v);
                }
            }
            tag.putString("Personality", this.personality.name());
        }
        return tag;
    }

    /** @param legacy true = 从旧版 MoodState 子标签读（键名相同）。 */
    public static Prefs load(CompoundTag tag, boolean legacy) {
        Prefs p = new Prefs();
        p.generated = legacy ? tag.getBoolean("PrefsGenerated") : tag.getBoolean("Generated");
        if (p.generated) {
            for (PrefKind kind : PrefKind.values()) {
                Object v = kind.loadValue(tag);
                if (v != null) {
                    p.values.put(kind, v);
                }
            }
            p.personality = getEnum(tag, "Personality", PersonalityType.class,
                    PersonalityType.OPTIMIST);
        }
        return p;
    }
}
