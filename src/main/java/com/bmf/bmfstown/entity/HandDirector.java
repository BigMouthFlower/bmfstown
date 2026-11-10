package com.bmf.bmfstown.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import javax.annotation.Nullable;

/**
 * 手装导演（决策 #49，2026-11-14 设计稿 / 2026-11-09 用户拍板实装）：
 * 主手物品的<b>唯一决策者</b>。
 *
 * <h2>职责铁律</h2>
 * <ul>
 *   <li><b>行为只管自己物品的去向</b>：进食收尾把余量归位后主手清空；
 *       看书收尾把书还架后主手清空——主手上放什么，行为无权过问；</li>
 *   <li><b>导演管主手该拿什么</b>：有战斗目标 → 换武器上主手；
 *       目标消失 → 武器回背包、主手清空待命。空手是合法默认态。</li>
 * </ul>
 *
 * <h2>触发时机：目标变化瞬间 + 主手空档补装</h2>
 * 每 tick 只做一次引用级 target diff（{@code getTarget() == seenTarget}），
 * <b>不在 tick 里轮询算伤害</b>——附魔判伤只在目标变化或主手空档时算一轮。
 *
 * <h2>优先级链（用户定稿"战斗不主动打断进食"）</h2>
 * <ol>
 *   <li>行为持有期（{@code isUsingItem()}，进食等）导演<b>绝不动手</b>；
 *       看书不占用 use-item，但看书期间目标出现会先由优先级竞争停掉
 *       LeisureGoal（MeleeAttackGoal 3 &lt; LeisureGoal 5）→ abort 还书，
 *       书先离手导演才介入，两者天然不相撞；</li>
 *   <li>睡觉不换装（睡着打什么架；真有威胁 RetaliateGoal 会先叫醒）；</li>
 *   <li>有目标 → 换武器；目标没变但主手空了（如进食刚收尾）→ 补装；</li>
 *   <li>目标消失 → 导演持有物回背包。</li>
 * </ol>
 *
 * <h2>选武器规则（统一比较器，用户 2026-11-09 拍板口径）</h2>
 * 期望伤害 = 镇民裸攻击属性 + 物品主手加成
 * （{@code ItemAttributeModifiers.compute(0, MAINHAND)}）
 * + 附魔对目标的加成（原版 {@code EnchantmentHelper.modifyDamage}，
 * vanilla {@code Mob.doHurtTarget} 同款调用——亡灵杀手打僵尸加分、
 * 锋利对所有目标加分，全部由原版公式算出，零漂移）。
 * <ul>
 *   <li>战士带偏好类别 → 先在类别内选（{@link WeaponPref}）；
 *       类别里没有 → 回落全背包（含工具，宁可拿镐不空手）；</li>
 *   <li>非战士 / 无偏好 → 全背包直接选；</li>
 *   <li>严格高于空手（裸属性）才有资格参选——面包棍永远选不上。</li>
 * </ul>
 *
 * <h2>防吞物品</h2>
 * 装填时记录"导演持有物"引用；卸下时只有主手仍是导演持有物才回背包，
 * 玩家中途换上的物品不会被误判吞掉。
 */
public final class HandDirector {

    private final Resident resident;
    /** 上一次 tick 看到的攻击目标（引用比较，null ↔ null 不算变化）。 */
    @Nullable
    private LivingEntity seenTarget;
    /** 导演装填到主手的武器（引用跟踪，防吞行为物品/玩家手放的东西）。 */
    @Nullable
    private ItemStack armedWeapon;

    public HandDirector(Resident resident) {
        this.resident = resident;
    }

    /** 每 tick 调用（{@code customServerAiStep}，仅服务端）。 */
    public void tick() {
        LivingEntity target = this.resident.getTarget();
        boolean targetChanged = target != this.seenTarget; // 引用比较
        this.seenTarget = target;

        // ---- 行为持有期：绝不动手（进食的对调状态机最怕半路被抢手）----
        if (this.resident.isUsingItem() || this.resident.isSleeping()) {
            return;
        }

        if (target == null) {
            this.disarm();
            return;
        }

        // 目标刚出现/刚换 → 重新选武器；目标没变但主手空了（进食/看书
        // 刚收尾）→ 补装。目标没变且主手有东西 → 不折腾。
        if (targetChanged || this.resident.getMainHandItem().isEmpty()) {
            this.arm(target);
        }
    }

    // ------------------------------------------------------------
    //  装填 / 卸下
    // ------------------------------------------------------------

    /**
     * 换武器上主手。主手若已有物品（导演持有物或杂物）先送回背包。
     */
    private void arm(LivingEntity target) {
        if (!(this.resident.level() instanceof ServerLevel level)) {
            return;
        }
        Candidate best = this.selectBest(level, target);
        ItemStack main = this.resident.getMainHandItem();

        if (best == null) {
            return; // 全背包都没有比空手强的东西：保持现状
        }
        if (best.slot < 0) {
            return; // 最优的那把已经在主手上（scan 把主手也作为候选参选）
        }
        if (best.stack == main) {
            return; // 引用相等（防御，正常不会走到）
        }
        // 主手清位：物品去向 = 背包（满则掉脚边——导演不吞任何东西）
        if (!main.isEmpty()) {
            this.store(main);
            this.resident.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
        ItemStack drawn = this.resident.getInventory().removeItem(best.slot, best.stack.getCount());
        if (!drawn.isEmpty()) {
            this.resident.setItemSlot(EquipmentSlot.MAINHAND, drawn);
            this.armedWeapon = drawn;
        }
    }

    /**
     * 目标消失：导演持有物回背包，主手清空。
     * 主手不是导演持有物（玩家/其他系统换过）则只清标记，不挪物品。
     */
    private void disarm() {
        ItemStack main = this.resident.getMainHandItem();
        if (this.armedWeapon != null && isDirectorWeapon(main)) {
            this.store(main);
            this.resident.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
        this.armedWeapon = null;
    }

    private boolean isDirectorWeapon(ItemStack main) {
        return main == this.armedWeapon
                || ItemStack.isSameItemSameComponents(main, this.armedWeapon);
    }

    /** 把物品放进背包；放不下把剩余掉在镇民脚边（绝不消失）。 */
    private void store(ItemStack stack) {
        ItemStack over = this.resident.getInventory().addItem(stack.copy());
        if (!over.isEmpty()) {
            this.resident.spawnAtLocation(over);
        }
    }

    // ------------------------------------------------------------
    //  选武器（统一比较器）
    // ------------------------------------------------------------

    private record Candidate(int slot, ItemStack stack, double score) {
    }

    /**
     * 全背包 + 主手范围内挑"对当前目标期望伤害最高"的物品。
     *
     * <p>两轮扫描：战士偏好类别优先；没有匹配再全背包回落
     * （用户 2026-11-09 拍板：回落统一比较器，工具也算）。
     * 严格高于空手（裸属性）才有资格——否则干脆空手。
     */
    @Nullable
    private Candidate selectBest(ServerLevel level, LivingEntity target) {
        double naked = this.nakedAttackDamage();
        DamageSource source = this.resident.damageSources().mobAttack(this.resident);

        // 第一轮：战士偏好类别过滤（非战士 / NONE 跳过）
        WeaponPref pref = this.resident.isWarrior()
                ? this.resident.getJobState().weaponPref() : WeaponPref.NONE;
        if (pref != WeaponPref.NONE) {
            Candidate hit = this.scan(level, target, source, naked, pref::matches);
            if (hit != null) {
                return hit;
            }
            // 类别里没有 → 回落全背包（不空手——用户定稿）
        }
        return this.scan(level, target, source, naked, stack -> true);
    }

    /**
     * 扫一遍候选（主手 + 全背包），过滤谓词选中的按期望伤害取最高。
     *
     * @param filter 只有通过谓词的物品参选
     * @return 最优候选；全部弱于空手时 null
     */
    @Nullable
    private Candidate scan(ServerLevel level, LivingEntity target, DamageSource source,
                           double naked, java.util.function.Predicate<ItemStack> filter) {
        Candidate best = null;
        double bestScore = naked; // 严格高于空手才有资格

        // 主手物品也参选（已拿在手里的武器不该因为"不在背包"而落选）
        ItemStack main = this.resident.getMainHandItem();
        if (!main.isEmpty() && filter.test(main)) {
            double score = this.expectedDamage(level, main, target, source, naked);
            if (score > bestScore) {
                bestScore = score;
                best = new Candidate(-1, main, score); // slot -1 = 已在主手
            }
        }

        var inv = this.resident.getInventory();
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack.isEmpty() || !filter.test(stack)) {
                continue;
            }
            double score = this.expectedDamage(level, stack, target, source, naked);
            if (score > bestScore) {
                bestScore = score;
                best = new Candidate(slot, stack, score);
            }
        }
        return best;
    }

    /**
     * 期望伤害 = 裸属性 + 物品主手加成 + 附魔对目标的加成。
     *
     * <p>{@code modifyDamage} 的调用方式与原版 {@code Mob.doHurtTarget}
     * 逐参一致（victim 传给 {@code entity} 形参——亡灵杀手等目标条件
     * 按受害者判定）。基座用"裸属性+物品加成"而非 {@code getAttributeValue}：
     * 候选武器不在手上，属性表里此刻挂的是当前主手的加成。
     */
    private double expectedDamage(ServerLevel level, ItemStack stack, LivingEntity target,
                                  DamageSource source, double naked) {
        double base = naked + mainhandAttackBonus(stack);
        return EnchantmentHelper.modifyDamage(level, stack, target, source, (float) base);
    }

    /**
     * 物品在主手位提供的攻击伤害加成（面板上那把"攻击伤害 +7"的部分）。
     * 优先读栈上的 {@code attribute_modifiers} 组件（玩家可自定义的栈覆盖），
     * 没有组件才回落到物品默认表（NeoForge 按 stack 取的补丁方法）。
     */
    private static double mainhandAttackBonus(ItemStack stack) {
        var component = stack.get(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS);
        var modifiers = component != null
                ? component
                : stack.getItem().getDefaultAttributeModifiers(stack);
        return modifiers.compute(0.0D, EquipmentSlot.MAINHAND);
    }

    /** 镇民裸攻击属性 = 属性表现值 − 当前主手物品的加成（还原出没拿武器时的底子）。 */
    private double nakedAttackDamage() {
        double current = this.resident.getAttributeValue(Attributes.ATTACK_DAMAGE);
        ItemStack main = this.resident.getMainHandItem();
        double mainBonus = main.isEmpty() ? 0.0D : mainhandAttackBonus(main);
        return current - mainBonus;
    }
}
