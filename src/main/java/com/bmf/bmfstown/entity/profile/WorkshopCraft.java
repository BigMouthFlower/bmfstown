package com.bmf.bmfstown.entity.profile;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.List;

/**
 * 工坊配方（v0.8.1）：一条"可生产产物 + 真实材料需求"的记录。
 *
 * <h2>来源：游戏配方反查，不是手写</h2>
 * 材料（{@link Req}）与产量（{@code resultCount}）都是从
 * {@code RecipeManager} 里的<b>真实原版配方</b>反查出来的——
 * 原版配方改了/数据包改了，这里自动跟着对，不存在"猜配方"。
 * 白名单（能做哪些产物）在 {@link WorkshopCrafts}。
 *
 * <h2>材料匹配</h2>
 * {@link Req#ingredient} 是原版 {@code Ingredient}——支持物品也支持
 * 物品标签（如 #minecraft:planks）：箱子里任何匹配的材料都算数，
 * 按原版配方的真实口径取料。
 */
public record WorkshopCraft(Item result, int resultCount, int tier, List<Req> requirements) {

    /** 一条材料需求：原版 Ingredient + 每套需要的数量。 */
    public record Req(Ingredient ingredient, int count) {

        /** 该需求是否接受这个物品堆（空堆不算）。 */
        public boolean matches(ItemStack stack) {
            return !stack.isEmpty() && this.ingredient.test(stack);
        }
    }

    /** 难度线：0 = 信号源（基础）/ 1 = 布线 / 2 = 机关。 */
    public int tier() {
        return this.tier;
    }
}
