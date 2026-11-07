package com.bmf.bmfstown.alchemy;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;

import javax.annotation.Nullable;

import java.util.List;

/**
 * 炼金纯度规则引擎（v0.12，决策 #27，设计终稿 .ai/designs/alchemist.md §3）。
 *
 * <h2>口径（用户逐条拍板）</h2>
 * <ul>
 *   <li><b>基础值</b>随合成技能段位 0.6 → 0.9 爬升；每炉随机浮动 ±0.1；
 *       <b>硬性 clamp ≤ 1.0</b>——满级镇民的单瓶也永不超越玩家手酿（"一直都是杂药"）；</li>
 *   <li>纯度<b>只影响时长</b>（主效果 duration ×P，取料时物化进 PotionContents），
 *       buff 等级（离散值）不动；</li>
 *   <li><b>杂质隐藏规则</b>：杂质效果与时长<b>不进 PotionContents</b>（原版 tooltip
 *       会显示、藏不住），喝下/命中结算时<b>现抽</b>——喝前不知道窜什么、多久；</li>
 *   <li><b>浊药必窜</b>（2026-11-07 拍板修订，原"概率=1−P"废弃）：只要打了浊名
 *       就一定窜味；纯度只影响杂质<b>时长</b>——秒数 = 20×(1−P)，P=0.5 恰 10 秒；</li>
 *   <li>窜味抽一种（单次单种）；持续型 7 种 + 瞬发 1 种；</li>
 *   <li>浊药名字一律"浑浊的××药水"；P≥1（追平玩家）显示正常名。</li>
 * </ul>
 *
 * <h2>杂质池（对称结构，作用于药水受用者）</h2>
 * 正面药 → 负面杂质（给饮用者）；负面药 → 正面杂质（给被命中的怪物——
 * "对怪物有用的正面效果"筛过，夜视/急迫/幸运/水肺/缓降/潮涌系对怪物无用不进池）。
 * 两池各 7 持续 + 1 瞬发，镜像对称。
 */
public final class PurityRules {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    // ---- 数值（提案值；config 化在 AlchemistGoal 实装时一起做） ----

    /** 入门基础值（段位 0）。 */
    public static final double BASE_MIN = 0.60D;
    /** 满段基础值（段位 10）。 */
    public static final double BASE_MAX = 0.90D;
    /** 每炉随机浮动半幅（±0.1）。 */
    public static final double FLOATING = 0.10D;
    /** 铁律封顶：任何纯度不得超过 1.0（决策 #27）。 */
    public static final double CEILING = 1.0D;
    /** 杂质持续时长基准系数：秒数 = SECONDS_AT_HALF × (1−P)，P=0.5 时恰为 10 秒。 */
    public static final double SECONDS_AT_HALF = 20.0D;

    private PurityRules() {
    }

    // ==========================================================
    //  纯度生成
    // ==========================================================

    /** 基础值：随段位线性爬升（tier ∈ [0, 10]）。 */
    public static double basePurity(int tier, int maxTier) {
        double t = Math.max(0, Math.min(maxTier, tier)) / (double) Math.max(1, maxTier);
        return BASE_MIN + (BASE_MAX - BASE_MIN) * t;
    }

    /** 掷一炉纯度：基础值 ± 浮动，clamp 到 [0.05, 1.0]。 */
    public static float rollPurity(double base, RandomSource random) {
        double p = base + (random.nextDouble() * 2 - 1) * FLOATING;
        p = Math.max(0.05D, Math.min(CEILING, p));
        return (float) p;
    }

    /** 是否浊药（P&lt;1；P≥1 视为追平玩家的纯药）。 */
    public static boolean isImpure(float purity) {
        return purity < CEILING;
    }

    /** 主效果时长乘区（物化进 PotionContents 用）。 */
    public static int scaledDuration(int originalTicks, float purity) {
        return Math.max(1, Math.round(originalTicks * purity));
    }

    // ==========================================================
    //  浊药表现
    // ==========================================================

    /** 浊药显示名："浑浊的" + 原名（translatable 组件，客户端按语言渲染）。 */
    public static MutableComponent impureName(Component baseName) {
        return Component.empty()
                .append(Component.translatable("gui.bmfstown.alchemy.impure_prefix"))
                .append(baseName);
    }

    /** 浊度掺灰：按 (1−P) 比例把药水颜色向灰去饱和（P=1 原色）。argb 输入输出。 */
    public static int tintTowardsGray(int argb, float purity) {
        double gray = 1.0D - purity;
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        int grayV = (r + g + b) / 3;
        r = mix(r, grayV, gray);
        g = mix(g, grayV, gray);
        b = mix(b, grayV, gray);
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private static int mix(int channel, int gray, double ratio) {
        return (int) Math.round(channel * (1 - ratio) + gray * ratio);
    }

    // ==========================================================
    //  盖章（炼金术士取瓶时刻，Goal 侧调用；生效结算由 ImpurityEvents 认章）
    // ==========================================================

    /**
     * 给一瓶真酿造产物盖章：写纯度组件；浊药（P&lt;1）连带做三件外观事——
     * ①主效果时长 ×P 物化（potion holder 展开为 customEffects 并丢弃 holder，
     * 防原版双份施加）；②customColor 掺灰；③CUSTOM_NAME 改"浑浊的××药水"。
     * P≥1（追平玩家）只盖章，外观与玩家手酿完全一致。
     */
    public static void stamp(net.minecraft.world.item.ItemStack stack, float purity,
            @Nullable com.bmf.bmfstown.entity.Resident brewer) {
        PotionContents contents = stack.getOrDefault(
                net.minecraft.core.component.DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
        if (!contents.hasEffects()) {
            return; // 水瓶/平凡/粗制：无效果可藏杂质，不打章（打了章的水瓶喝下去也会窜味）
        }
        // 浓缩（boost_one）：对纯药同样生效（2026-11-07 GameTest 抓出——原实现纯药
        // 提前返回，浓缩 perk 对 P≥1 的瓶完全不生效）。纯药物化不掺灰不改名，
        // 仅 holder→customEffects（等级+1、时长不变）防原版双份施加。
        int ampBonus = brewer != null
                && brewer.hasPerk(com.bmf.bmfstown.entity.profile.Perks.NODE_AL_LEGEND, "boost_one")
                ? 1 : 0;
        stack.set(com.bmf.bmfstown.ModDataComponents.PURITY.get(), purity);
        if (!isImpure(purity)) {
            if (ampBonus > 0) {
                // 物化（丢 holder）后原版会把它显示成"不可合成的药水"
                // （effect.empty 键，2026-11-07 用户实测）——必须用**原 holder 的正常名**
                // 打 CUSTOM_NAME 保住观感（不带"浑浊的"前缀，纯药=干净药）
                MutableComponent baseName = Component.translatable(Potion.getName(
                        contents.potion(), stack.getItem().getDescriptionId() + ".effect."));
                stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                        baseName.withStyle(s -> s.withItalic(false)));
                List<MobEffectInstance> boosted = new java.util.ArrayList<>();
                for (MobEffectInstance e : contents.getAllEffects()) {
                    boosted.add(new MobEffectInstance(e.getEffect(), e.getDuration(),
                            e.getAmplifier() + ampBonus, e.isAmbient(), e.isVisible()));
                }
                stack.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,
                        new PotionContents(java.util.Optional.empty(), java.util.Optional.empty(), boosted));
            }
            return;
        }
        boolean positive = isPositivePotion(contents);
        // ① 时长物化：holder 效果展开为 customEffects（×P，缓释再 ×2），丢弃 holder；
        //    浓缩：效果等级 +1（perk 直改产出，纯度机制不碰等级，决策 #27 语义不冲突）
        double durFactor = purity * (brewer != null
                && brewer.hasPerk(com.bmf.bmfstown.entity.profile.Perks.NODE_AL_LEGEND, "double_time")
                ? 2.0D : 1.0D);
        List<MobEffectInstance> scaled = new java.util.ArrayList<>();
        for (MobEffectInstance e : contents.getAllEffects()) {
            scaled.add(new MobEffectInstance(e.getEffect(),
                    Math.max(1, (int) Math.round(e.getDuration() * durFactor)),
                    e.getAmplifier() + ampBonus, e.isAmbient(), e.isVisible()));
        }
        // ④ 杂质豁免（医师=正面药 / 毒师=负面药）：浊药打纯标，现抽时直接跳过。
        //    豁免药**不掺灰不改名**（2026-11-07 拍板）：天赋药在玩家眼里就是一瓶
        //    干净药——浊名只会让人误以为它还窜味。纯度仍影响时长（×P 已物化）。
        boolean exempt = false;
        if (brewer != null) {
            exempt = positive
                    ? brewer.hasPerk(com.bmf.bmfstown.entity.profile.Perks.NODE_AL_MASTER, "medic_stone")
                    : brewer.hasPerk(com.bmf.bmfstown.entity.profile.Perks.NODE_AL_MASTER, "poison_stone");
            if (exempt) {
                stack.set(com.bmf.bmfstown.ModDataComponents.ALCHEMY_PURE.get(), true);
                // 物化（丢 holder）后原版对无 holder 的瓶显示"不可合成的药水"——
                // **原版合成不到的秘传药 100% 中招**（2026-11-07 用户实测）。
                // 豁免药打"正常名"CUSTOM_NAME（无"浑浊的"前缀、无斜体）——
                // 观感与原版完全一致，"不改名"指不加浊名，不是不保名。
                MutableComponent baseName = Component.translatable(Potion.getName(
                        contents.potion(), stack.getItem().getDescriptionId() + ".effect."));
                stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                        baseName.withStyle(s -> s.withItalic(false)));
                // 物化时长（丢 holder 防双份施加），无 customColor = 原版观感
                stack.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,
                        new PotionContents(java.util.Optional.empty(), java.util.Optional.empty(), scaled));
                return;
            }
        }
        // ② 颜色掺灰
        int tinted = tintTowardsGray(contents.getColor(), purity);
        stack.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,
                new PotionContents(java.util.Optional.empty(), java.util.Optional.of(tinted), scaled));
        // ③ 浊名（translatable 组件进 CUSTOM_NAME，客户端按语言渲染；去斜体）
        // 前缀必须按**瓶物品**取（POTION/SPLASH_POTION/LINGERING_POTION 各有原版键）：
        // 不能用 stack.getDescriptionId()——PotionItem 重载了带 ItemStack 的版本，
        // 而此时内容刚被换成"无 holder 的 customEffects"，它会把前缀污染成
        // "…effect.empty"，再拼路径就露出 item.minecraft.potion.effect.empty.effect.<x>
        // 这样的死键名（2026-11-06 截图实锤）
        Component baseName = Component.translatable(Potion.getName(
                contents.potion(), stack.getItem().getDescriptionId() + ".effect."));
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                impureName(baseName).withStyle(s -> s.withItalic(false)));
    }

    /** 药水方向判定：全部效果 HARMFUL → 负面药；其余（含水瓶）→ 正面药。 */
    public static boolean isPositivePotion(PotionContents contents) {
        if (contents == null) {
            return true;
        }
        for (MobEffectInstance e : contents.getAllEffects()) {
            if (e.getEffect().value().getCategory() != MobEffectCategory.HARMFUL) {
                return true;
            }
        }
        return false;
    }

    // ==========================================================
    //  杂质结算（喝下/命中时现抽——不在酿造时预存）
    // ==========================================================

    /** 负面杂质池（正面药的窜味，作用于饮用者）：反胃/虚弱/饥饿/失明/黑暗/中毒/漂浮。 */
    private static final List<Holder<MobEffect>> NEGATIVE_IMPURITIES = List.of(
            MobEffects.CONFUSION, MobEffects.WEAKNESS, MobEffects.HUNGER,
            MobEffects.BLINDNESS, MobEffects.DARKNESS, MobEffects.POISON, MobEffects.LEVITATION);

    /** 正面杂质池（负面药的窜味，作用于被命中的怪物）：再生/力量/速度/抗性/隐身/吸收/生命提升。 */
    private static final List<Holder<MobEffect>> POSITIVE_IMPURITIES = List.of(
            MobEffects.REGENERATION, MobEffects.DAMAGE_BOOST, MobEffects.MOVEMENT_SPEED,
            MobEffects.DAMAGE_RESISTANCE, MobEffects.INVISIBILITY, MobEffects.ABSORPTION,
            MobEffects.HEALTH_BOOST);

    /** 瞬发型杂质：负面药 ↔ 给怪物回血；正面药 ↔ 给饮用者掉血（对称结构）。 */
    private static final Holder<MobEffect> INSTANT_HARM = MobEffects.HARM;
    private static final Holder<MobEffect> INSTANT_HEAL = MobEffects.HEAL;

    /**
     * 杂质结算入口（饮用/命中时调用）。**浊药必窜**（2026-11-07 拍板修订：
     * 原"概率=1−P"废弃——浊名承诺必须兑现，纯度只影响杂质**时长**，
     * 秒数 = {@link #SECONDS_AT_HALF} × (1−P)）。
     *
     * @param positivePotion true = 正面药（杂质走负面池，作用于饮用者）；
     *                       false = 负面药（杂质走正面池，作用于被命中的怪物）
     * @return true = 本次窜了味（必为 true，保留返回值兼容调用侧）
     */
    public static boolean maybeApplyImpurity(ServerLevel level, LivingEntity target,
            float purity, boolean positivePotion, RandomSource random) {
        if (!isImpure(purity)) {
            return false;
        }
        boolean instant = random.nextInt(NEGATIVE_IMPURITIES.size() + 1)
                == NEGATIVE_IMPURITIES.size(); // 8 选 1：第 8 格 = 瞬发
        Holder<MobEffect> effect;
        if (positivePotion) {
            effect = instant ? INSTANT_HARM
                    : NEGATIVE_IMPURITIES.get(random.nextInt(NEGATIVE_IMPURITIES.size()));
        } else {
            effect = instant ? INSTANT_HEAL
                    : POSITIVE_IMPURITIES.get(random.nextInt(POSITIVE_IMPURITIES.size()));
        }
        int seconds = instant ? 1 : (int) Math.max(1, Math.round(SECONDS_AT_HALF * (1.0D - purity)));
        target.addEffect(new MobEffectInstance(effect, seconds * 20, 0));
        LOGGER.info("[BmfTown][炼金] 杂质窜味：目标={}，纯度={}，{} {}秒",
                target.getName().getString(), String.format("%.2f", purity),
                BuiltInRegistries.MOB_EFFECT.getKey(effect.value()).toString(), seconds);
        return true;
    }
}
