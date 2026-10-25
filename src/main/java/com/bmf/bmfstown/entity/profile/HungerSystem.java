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
 *   <li><b>吃哪个</b>：爱吃组优先（v0.8.1 背包偏好进食）——背包里还有
 *       爱吃的东西时<b>拒绝吃普通食物</b>，宁可等饥饿降下来再吃爱的；
 *       爱吃组内挑营养最低。没有爱吃的，才在普通食物里挑营养最低。</li>
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
        // 工人'效率/节能'分支的饥饿速度修正（效率饿得快 ×1.5 / 节能省粮 ×0.5）
        points = applyWorkerHungerTrait(profile, points);
        // 0 是下限，已经饿到底就不再往下掉
        int newHunger = Math.max(0, profile.hunger() - points);
        if (newHunger == profile.hunger()) {
            return null;
        }
        return profile.withHunger(newHunger);
    }

    /**
     * 工人精通分支的饥饿速度修正（v0.8.1）。
     * 效率：饿得快（×1.5，力量换饭量）；节能：省粮（×0.5，慢工出细活）。
     */
    private static int applyWorkerHungerTrait(ResidentProfile profile, int points) {
        if (profile.profession() != ProfessionType.WORKER) {
            return points;
        }
        String choice = profile.perkChoice(Perks.NODE_W_ADEPT);
        if ("efficiency".equals(choice)) {
            return Math.max(1, points * 3 / 2);
        }
        if ("frugal".equals(choice)) {
            return Math.max(1, points / 2);
        }
        return points;
    }

    /**
     * 在背包里找"该吃的那个"。
     *
     * <p>规则：爱吃组优先、组内看营养，且 {@code canEatWithoutWaste} 通过。
     *
     * @return 背包槽位索引；没有能吃的就返回 {@code -1}
     */
    public static int findFoodSlot(ResidentInventory inventory, ResidentProfile profile) {
        return findFoodSlot(inventory, profile, false);
    }

    /**
     * 在背包里找"该吃的那个"（带"回血优先"放宽）。
     *
     * <p>{@code allowWaste} 为 true 时跳过"不浪费"门槛——用于
     * "血没满且饥饿够不到回血线"的场合：这时哪怕撑溢出也要吃，
     * 否则饥饿 16 拿着牛排(8)的镇民会因为"溢出"永远不开口，
     * 卡在回血线（18）下面，一点点血都回不上。
     *
     * <h2>为什么"挑食"要挑到底（v0.8.1 背包偏好进食）</h2>
     * "不浪费"门槛下，低营养食物永远先可吃：饥饿 18 时曲奇(2)能吃、
     * 牛排(8)不能——如果"没有能吃的爱吃的就退回普通"，爱吃肉的镇民
     * 会一直在 18~20 区间啃曲奇，饥饿永远到不了 12，牛排永远轮不到，
     * "爱吃组优先"被彻底架空。
     *
     * <p>所以规则是：背包里<b>还有</b>爱吃的东西（哪怕现在吃了会浪费），
     * 就拒绝吃普通食物——宁可饿着等，等饥饿降下来牛排能吃了再开口。
     * 等待不浪费任何东西（每一口仍然过"不浪费"门槛），只是挑食；
     * 挑食期间 {@code hasAnyFood()} 仍为 true，也不会误触发觅食。
     * 背包里根本没有爱吃的，才按老规则吃普通食物。
     *
     * <p>{@code allowWaste}（回血优先/医疗进食）无视挑食——保命第一。
     *
     * @return 背包槽位索引；没有能吃的就返回 {@code -1}
     */
    public static int findFoodSlot(ResidentInventory inventory, ResidentProfile profile,
                                   boolean allowWaste) {
        Hobbies.Food hobby = profile.hobbyFood();
        // 第一轮：爱吃组里挑能吃的（组内看营养最低）
        int favoriteSlot = scanFood(inventory, profile, hobby, true, !allowWaste);
        if (favoriteSlot >= 0) {
            return favoriteSlot;
        }
        // 背包里还有爱吃的东西（现在吃会浪费）→ 挑食，拒绝普通食物
        if (scanFood(inventory, profile, hobby, true, false) >= 0) {
            return -1;
        }
        // 背包里根本没有爱吃的 → 按老规则吃普通
        return scanFood(inventory, profile, hobby, false, !allowWaste);
    }

    /**
     * 扫一遍背包挑食物。
     *
     * @param favoriteOnly true = 只挑对胃口的（{@link FoodTraits#matches}）；
     *                     false = 只挑普通食物。两轮互补，
     *                     任何食物恰好属于其中一轮，不会重复扫描。
     * @param respectGate  true = "不浪费"门槛生效（{@code canEatWithoutWaste}）；
     *                     false = 无视门槛（用于"有没有爱吃的东西"的存在性检查，
     *                     以及回血优先/医疗进食）
     * @return 背包槽位索引；这一轮没有能吃的就返回 {@code -1}
     */
    private static int scanFood(ResidentInventory inventory, ResidentProfile profile,
                                Hobbies.Food hobby, boolean favoriteOnly, boolean respectGate) {
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
            // 爱吃轮跳过不合胃口的 / 普通轮跳过爱吃的（两轮互补）
            if (favoriteOnly != FoodTraits.matches(hobby, stack)) {
                continue;
            }
            int nutrition = food.nutrition();
            // "不浪费"门槛（存在性检查或回血优先时关闭）
            if (respectGate && !profile.canEatWithoutWaste(nutrition)) {
                continue;
            }
            // 组内优先营养值最低的
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
