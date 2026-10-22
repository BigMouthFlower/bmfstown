package com.bmf.bmfstown.entity.profile;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 口味判定：这个食物属于哪一类。
 *
 * <h2>数据来源</h2>
 * 全部用 NeoForge 的跨模组通用标签 {@code c:foods/*}（见需求文档 §4.2.3）。
 * 好处是：<b>农夫乐事、Create 这些模组只要遵守 c 标签约定，就自动被识别</b>，
 * 我们一行兼容代码都不用写。
 *
 * <h2>边界：只有两类口味</h2>
 * 当前 {@link Hobbies.Food} 只有 {@code SWEET} / {@code MEAT} 两个值
 * （用户要求"每个类别有两种就行"）。所以这里也只判定两类。
 * 以后扩到 荤/素/生/熟/鱼/果 时，只需在这里加判定方法、在枚举里加值，
 * 其他代码（存档、随机、UI）都不用改。
 *
 * <h2>为什么用 TagKey 而不是遍历背包找物品</h2>
 * {@code stack.is(TagKey)} 走的是服务端的标签索引（{@code Holder} 集合），
 * 比"拿到物品 id 再比对一张表"快得多，而且标签内容是数据包可改的——
 * 玩家加个数据包就能让某样东西变成"甜食"。
 */
public final class FoodTraits {

    /** 甜食。原版加工甜食由本 mod 自己补（见 data/c/tags/item/foods/candy.json）。 */
    private static final TagKey<Item> CANDY = cTag("foods/candy");
    private static final TagKey<Item> COOKIE = cTag("foods/cookie");
    private static final TagKey<Item> PIE = cTag("foods/pie");
    private static final TagKey<Item> BREAD = cTag("foods/bread");

    /** 肉类（生 + 熟）。 */
    private static final TagKey<Item> RAW_MEAT = cTag("foods/raw_meat");
    private static final TagKey<Item> COOKED_MEAT = cTag("foods/cooked_meat");

    private FoodTraits() {
    }

    /**
     * 拼一个 {@code c:} 命名空间的物品标签。
     *
     * <p>{@code c} 是 NeoForge 的通用标签命名空间（Common Tags），
     * 路径按约定写成 {@code foods/candy} 这种带斜杠的形式。
     */
    private static TagKey<Item> cTag(String path) {
        return TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("c", path));
    }

    /** 是不是甜食。 */
    public static boolean isSweet(ItemStack stack) {
        return stack.is(CANDY) || stack.is(COOKIE) || stack.is(PIE) || stack.is(BREAD);
    }

    /** 是不是肉类。 */
    public static boolean isMeat(ItemStack stack) {
        return stack.is(RAW_MEAT) || stack.is(COOKED_MEAT);
    }

    /**
     * 这个食物能不能投 {@code hobby} 所好。
     *
     * <p>注意"不算讨厌"和"算喜欢"是两回事：
     * 不是他喜欢的那类，只是<b>不加分</b>，不代表讨厌。
     * 讨厌目前只在环境/物品维度存在（见 {@link Hobbies.DislikedItem}）。
     */
    public static boolean matches(Hobbies.Food hobby, ItemStack stack) {
        if (hobby == null) {
            return false;
        }
        return switch (hobby) {
            case SWEET -> isSweet(stack);
            case MEAT -> isMeat(stack);
        };
    }
}
