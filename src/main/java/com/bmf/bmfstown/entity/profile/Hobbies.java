package com.bmf.bmfstown.entity.profile;

import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;

/**
 * 镇民的档案枚举集合。
 *
 * <h2>v0.7 偏好体系重写</h2>
 * 旧的 HomeItem / Environment / Ambience / DislikedItem / DislikedEnvironment
 * 五个枚举已移除——它们是没有任何逻辑读取的死数据，且在新偏好区划
 * （食物 / 方块 / 物品 / 天气 / 职业）里被拆分重组。方块、天气维度
 * 以后讨论定稿后在此追加新枚举。
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
     * 例：{@code hobby.bmfstown.food.sweet}。
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
     *   <li>{@link #SWEET} → {@code c:foods/candy} + cookie + pie + bread</li>
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
}
