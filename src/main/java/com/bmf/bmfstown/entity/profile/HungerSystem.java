package com.bmf.bmfstown.entity.profile;

import com.bmf.bmfstown.config.Config;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

/**
 * 饥饿推进逻辑。
 *
 * <h2>规则（全部由用户定义）</h2>
 * <ol>
 *   <li><b>消耗</b>：按真实时间，每 3 分钟掉 3 点（= 每分钟 1 点）。</li>
 *   <li><b>补充</b>：与玩家吃下去完全一致，用食物的 {@code nutrition}。</li>
 *   <li><b>吃哪个</b>：永远优先吃<b>营养值最低</b>的那个。</li>
 *   <li><b>何时吃</b>：只有当他"吃下去不会溢出"时才吃。
 *       即 {@code hunger + nutrition <= MAX_HUNGER}。</li>
 *   <li><b>吃不下时</b>：什么都不做，继续饿着。</li>
 * </ol>
 *
 * <h2>为什么触发条件不设阈值</h2>
 * 用户的原话是"饥饿掉到能吃低营养了且吃了不浪费就吃"。
 * 这意味着<b>没有"低于 80% 才吃"这种阈值</b>——触发条件就是
 * "缺口 ≥ 最低营养值"。饥饿 20 时缺口 0，什么都不吃；
 * 饥饿 18 时缺口 2，可以吃曲奇(2)。
 *
 * <h2>为什么"最低营养优先"能保证不浪费</h2>
 * 因为他永远从最小的开始吃，缺口只会被最小步长填补。
 * 唯一"吃不下"的情况是背包里最小的一份都比缺口大，
 * 那时他会等——等缺口涨到够为止，而不是硬塞。
 */
public final class HungerSystem {

    /** 掉饥饿速度已配置化：{@link com.bmf.bmfstown.config.Config#HUNGER_TICKS_PER_POINT}。 */

    /**
     * 两次进食之间的最小间隔（tick）。
     *
     * <p>没有这个间隔的话，一个 {@code hunger=0} 的镇民会在同一 tick 内
     * 把背包里的 5 个胡萝卜全部吃掉（因为每吃完一个又会立刻满足条件）。
     * 20 tick = 1 秒，视觉上能看到"他一口一口吃"。
     */
    /** 进食间隔已配置化：{@link com.bmf.bmfstown.config.Config#EAT_COOLDOWN}。 */

    private HungerSystem() {
    }

    /**
     * 推进饥饿计时，返回是否需要更新档案。
     *
     * @param elapsedTicks 上次检查到现在经过的 tick 数
     * @return 传入了新的档案则返回它；没变化则返回 {@code null}
     */
    public static ResidentProfile tickHunger(ResidentProfile profile, int elapsedTicks) {
        int ticksPerPoint = Config.HUNGER_TICKS_PER_POINT.get();
        if (elapsedTicks < ticksPerPoint) {
            return null;
        }
        int points = elapsedTicks / ticksPerPoint;
        // 0 是下限，已经饿到底就不再往下掉
        int newHunger = Math.max(0, profile.hunger() - points);
        if (newHunger == profile.hunger()) {
            return null;
        }
        return profile.withHunger(newHunger);
    }

    /**
     * 在背包里找"该吃的那个"。
     *
     * <p>规则：营养值最低，且 {@code canEatWithoutWaste} 通过。
     *
     * @return 背包槽位索引；没有能吃的就返回 {@code -1}
     */
    public static int findFoodSlot(ResidentInventory inventory, ResidentProfile profile) {
        int bestSlot = -1;
        int bestNutrition = Integer.MAX_VALUE;

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food == null) {
                continue;
            }
            int nutrition = food.nutrition();
            // 必须"吃了不浪费"
            if (!profile.canEatWithoutWaste(nutrition)) {
                continue;
            }
            // 优先营养值最低的
            if (nutrition < bestNutrition) {
                bestNutrition = nutrition;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    /**
     * 执行一次进食。调用前应先 {@link #findFoodSlot} 确认有得吃。
     *
     * @return 进食后的档案；没能吃则返回 {@code null}
     */
    public static ResidentProfile eat(ResidentProfile profile, ResidentInventory inventory, int slot) {
        ItemStack stack = inventory.getItem(slot);
        if (stack.isEmpty()) {
            return null;
        }
        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food == null) {
            return null;
        }
        int nutrition = food.nutrition();
        if (!profile.canEatWithoutWaste(nutrition)) {
            return null;
        }

        inventory.extractOne(slot);
        return profile.withHunger(profile.hunger() + nutrition);
    }
}
