package com.bmf.bmfstown.entity.profile;

import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;

/**
 * 镇民的档案枚举集合。
 *
 * <h2>为什么用枚举而不是数据驱动</h2>
 * v0 阶段先写死枚举，理由：
 * <ul>
 *   <li>枚举可以用 {@code switch}，编译期就能检查漏写</li>
 *   <li>序列化只需要存一个名字字符串，不会因为重命名而崩存档</li>
 *   <li>以后要数据包化时，把枚举换成"从注册表查"即可，存档格式不用变</li>
 * </ul>
 *
 * <h2>扩充方式</h2>
 * 每个枚举末尾加一个值即可。注意：
 * <ul>
 *   <li>必须实现 {@link StringRepresentable}，{@code getSerializedName()} 返回值会写进存档</li>
 *   <li><b>已发布的值不要改名、不要删除</b>，否则老存档读不回来（会走 fallback）</li>
 *   <li>新值追加到末尾，不要插到中间（虽然不影响序列化，但影响随机权重）</li>
 * </ul>
 */
public final class Hobbies {

    private Hobbies() {
    }

    /** 从数组里随机取一个。 */
    private static <T> T pick(T[] values, RandomSource random) {
        return values[random.nextInt(values.length)];
    }

    /**
     * 拼语言文件 key。
     *
     * <p>枚举常量名（{@code HOME_ITEM}）和存档名（{@code home_item}）不一定一样，
     * 所以这里统一用 <b>序列化名</b>，避免 UI 里查不到翻译。
     * 例：{@code hobby.bmfstown.home_item.flower}。
     */
    private static String langKey(String group, String serializedName) {
        return "hobby.bmfstown." + group + "." + serializedName;
    }

    // ==========================================================
    //  喜欢的食物
    // ==========================================================

    /**
     * 喜欢的食物类别。
     *
     * <p>对应 {@code c:foods/*} 标签（见需求文档 §4.2.3）：
     * <ul>
     *   <li>{@link #SWEET} → {@code c:foods/candy} + code + pie + bread</li>
     *   <li>{@link #MEAT} → {@code c:foods/raw_meat} + {@code c:foods/cooked_meat}</li>
     * </ul>
     */
    public enum Food implements StringRepresentable {
        SWEET("sweet", "甜食"),
        MEAT("meat", "肉类");

        private static final Food[] VALUES = values();

        private final String name;
        private final String zhName;

        Food(String name, String zhName) {
            this.name = name;
            this.zhName = zhName;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }

        /** 中文显示名。硬编码，保证即使语言文件缺键也能显示中文。 */
        public String zhName() {
            return this.zhName;
        }

        /** 语言文件 key，UI 用 {@code Component.translatable(displayName())} 取译文。 */
        public String displayName() {
            return langKey("food", this.name);
        }

        public static Food random(RandomSource random) {
            return pick(VALUES, random);
        }

        public static Food byName(String name) {
            for (Food v : VALUES) {
                if (v.name.equals(name)) {
                    return v;
                }
            }
            return SWEET;
        }
    }

    // ==========================================================
    //  喜欢的家中物品
    // ==========================================================

    /**
     * 喜欢的家中物品。
     *
     * <p>注意"物品"在这里指<b>方块</b>——判定方式是在住所半径内找方块。
     */
    public enum HomeItem implements StringRepresentable {
        FLOWER("flower", "花"),
        LIGHT("light", "灯与火把");

        private static final HomeItem[] VALUES = values();

        private final String name;
        private final String zhName;

        HomeItem(String name, String zhName) {
            this.name = name;
            this.zhName = zhName;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }

        public String zhName() {
            return this.zhName;
        }

        /** 语言文件 key，UI 用 {@code Component.translatable(displayName())} 取译文。 */
        public String displayName() {
            return langKey("home_item", this.name);
        }

        public static HomeItem random(RandomSource random) {
            return pick(VALUES, random);
        }

        public static HomeItem byName(String name) {
            for (HomeItem v : VALUES) {
                if (v.name.equals(name)) {
                    return v;
                }
            }
            return FLOWER;
        }
    }

    // ==========================================================
    //  喜欢的居所环境
    // ==========================================================

    /** 喜欢的居所环境。判定方式是空间查询（光照、与水的距离、封闭度）。 */
    public enum Environment implements StringRepresentable {
        WATERSIDE("waterside", "水边"),
        HEIGHT("height", "高处");

        private static final Environment[] VALUES = values();

        private final String name;
        private final String zhName;

        Environment(String name, String zhName) {
            this.name = name;
            this.zhName = zhName;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }

        public String zhName() {
            return this.zhName;
        }

        /** 语言文件 key，UI 用 {@code Component.translatable(displayName())} 取译文。 */
        public String displayName() {
            return langKey("environment", this.name);
        }

        public static Environment random(RandomSource random) {
            return pick(VALUES, random);
        }

        public static Environment byName(String name) {
            for (Environment v : VALUES) {
                if (v.name.equals(name)) {
                    return v;
                }
            }
            return WATERSIDE;
        }
    }

    // ==========================================================
    //  喜欢的氛围（工作时的环境偏好）
    // ==========================================================

    /** 喜欢的氛围。決定他喜欢在热闹还是安静的地方干活。 */
    public enum Ambience implements StringRepresentable {
        QUIET("quiet", "安静"),
        LIVELY("lively", "热闹");

        private static final Ambience[] VALUES = values();

        private final String name;
        private final String zhName;

        Ambience(String name, String zhName) {
            this.name = name;
            this.zhName = zhName;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }

        public String zhName() {
            return this.zhName;
        }

        /** 语言文件 key，UI 用 {@code Component.translatable(displayName())} 取译文。 */
        public String displayName() {
            return langKey("ambience", this.name);
        }

        public static Ambience random(RandomSource random) {
            return pick(VALUES, random);
        }

        public static Ambience byName(String name) {
            for (Ambience v : VALUES) {
                if (v.name.equals(name)) {
                    return v;
                }
            }
            return QUIET;
        }
    }

    // ==========================================================
    //  讨厌的物品 / 环境（按需求文档：厌恶不能为空）
    // ==========================================================

    /**
     * 讨厌的物品。
     *
     * <p>需求文档 §4.1.3 第 2 条："必须有不想干的事"。
     * 因此这个字段<b>永远有值，不允许为 null</b>。
     */
    public enum DislikedItem implements StringRepresentable {
        NOISE("noise", "噪音（熔炉、铁砧）"),
        DARKNESS("darkness", "阴暗角落");

        private static final DislikedItem[] VALUES = values();

        private final String name;
        private final String zhName;

        DislikedItem(String name, String zhName) {
            this.name = name;
            this.zhName = zhName;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }

        public String zhName() {
            return this.zhName;
        }

        /** 语言文件 key，UI 用 {@code Component.translatable(displayName())} 取译文。 */
        public String displayName() {
            return "dislike.bmfstown.item." + this.name;
        }

        public static DislikedItem random(RandomSource random) {
            return pick(VALUES, random);
        }

        public static DislikedItem byName(String name) {
            for (DislikedItem v : VALUES) {
                if (v.name.equals(name)) {
                    return v;
                }
            }
            return NOISE;
        }
    }

    /** 讨厌的环境。同样不允许为 null。 */
    public enum DislikedEnvironment implements StringRepresentable {
        CROWDED("crowded", "拥挤"),
        DAMP("damp", "潮湿（近水）");

        private static final DislikedEnvironment[] VALUES = values();

        private final String name;
        private final String zhName;

        DislikedEnvironment(String name, String zhName) {
            this.name = name;
            this.zhName = zhName;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }

        public String zhName() {
            return this.zhName;
        }

        /** 语言文件 key，UI 用 {@code Component.translatable(displayName())} 取译文。 */
        public String displayName() {
            return "dislike.bmfstown.environment." + this.name;
        }

        public static DislikedEnvironment random(RandomSource random) {
            return pick(VALUES, random);
        }

        public static DislikedEnvironment byName(String name) {
            for (DislikedEnvironment v : VALUES) {
                if (v.name.equals(name)) {
                    return v;
                }
            }
            return CROWDED;
        }
    }
}
