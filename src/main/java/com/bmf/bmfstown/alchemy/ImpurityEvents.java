package com.bmf.bmfstown.alchemy;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.ModDataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;

/**
 * 浊药杂质结算的接线（v0.12 炼金，决策 #27；设计终稿 .ai/designs/alchemist.md §3）。
 *
 * <h2>盖章→认章→现抽</h2>
 * <ul>
 *   <li><b>盖章</b>：炼金术士从酿造台取瓶时打 {@code bmfstown:purity} 组件（Goal 侧代码）；
 *       玩家手酿的原版药水没有此组件——组件本身就是判据，无章一概不结算；</li>
 *   <li><b>认章+现抽</b>：杂质效果不进 PotionContents（原版 tooltip 会显示、藏不住），
 *       在"药水生效瞬间"由本类现抽——**浊药必窜**（2026-11-07 拍板），纯度只影响
 *       杂质时长（秒数=20×(1−P)）；现抽决定的是窜哪种、瞬发还是持续；</li>
 * </ul>
 *
 * <h2>两个生效入口</h2>
 * <ul>
 *   <li><b>饮用</b>：{@link LivingEntityUseItemEvent.Finish}——文档明说触发时 Potion
 *       主效果已应用、{@code getItem()} 是使用前副本（纯度还在）→ 杂质补在同一瞬间；</li>
 *   <li><b>投掷</b>：{@link ProjectileImpactEvent}（喷溅/滞留，含发射器）——命中点周围
 *       与原版喷溅同 AABB 的受用者，逐人独立现抽。滞留云的持续覆盖 v1 简化为命中时一次
 *       （杂质本就是"窜一次"的语义）。</li>
 * </ul>
 */
@EventBusSubscriber(modid = BmfTown.MOD_ID)
public final class ImpurityEvents {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private ImpurityEvents() {
    }

    /** 饮用路径：喝下浊药的瞬间，对饮用者现抽杂质。 */
    @SubscribeEvent
    public static void onDrinkFinish(LivingEntityUseItemEvent.Finish event) {
        ItemStack stack = event.getItem();
        Float purity = stack.get(ModDataComponents.PURITY.get());
        if (purity == null) {
            return; // 无章 = 非炼金产物（玩家手酿等），不结算
        }
        if (Boolean.TRUE.equals(stack.get(ModDataComponents.ALCHEMY_PURE.get()))) {
            return; // 医师/毒石豁免：此瓶不窜味
        }
        if (!(event.getEntity().level() instanceof ServerLevel level)) {
            return; // 杂质只服务端结算
        }
        LOGGER.info("[BmfTown][炼金] 饮用结算：喝者={}，纯度={}（链路已触发）",
                event.getEntity().getName().getString(), String.format("%.2f", purity));
        PurityRules.maybeApplyImpurity(level, event.getEntity(), purity,
                PurityRules.isPositivePotion(stack.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS)),
                level.random);
    }

    /** 投掷路径：浊药瓶碎裂的瞬间，对命中点周围的受用者逐人现抽。 */
    @SubscribeEvent
    public static void onPotionImpact(ProjectileImpactEvent event) {
        if (!(event.getProjectile() instanceof ThrownPotion potion)) {
            return;
        }
        ItemStack stack = potion.getItem();
        Float purity = stack.get(ModDataComponents.PURITY.get());
        if (purity == null) {
            return;
        }
        if (Boolean.TRUE.equals(stack.get(ModDataComponents.ALCHEMY_PURE.get()))) {
            return; // 医师/毒石豁免
        }
        if (!(potion.level() instanceof ServerLevel level)) {
            return;
        }
        HitResult ray = event.getRayTraceResult();
        Vec3 pos = ray.getLocation();
        boolean positive = PurityRules.isPositivePotion(stack.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS));
        // 与原版喷溅同尺寸的判定盒（4/2/4），受用者逐人结算——
        // **对所有人都生效**（2026-11-07 拍板：含投掷者自己，原"投掷者豁免"废弃）
        AABB box = new AABB(pos, pos).inflate(4.0D, 2.0D, 4.0D);
        int hit = 0;
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (!target.isAlive() || target.isSpectator()) {
                continue;
            }
            hit++;
            PurityRules.maybeApplyImpurity(level, target, purity, positive, level.random);
        }
        LOGGER.info("[BmfTown][炼金] 投掷结算：纯度={}，受用者 {} 人",
                String.format("%.2f", purity), hit);
    }

}
