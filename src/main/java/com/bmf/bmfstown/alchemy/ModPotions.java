package com.bmf.bmfstown.alchemy;

import com.bmf.bmfstown.BmfTown;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 秘传药水注册（v0.12 炼金，决策 #25/#27）：原版**不存在/不可酿**的 10 种药水。
 * 产物都是单效果基础档；mix 在酿造注册事件里恒挂上（见
 * {@link SecretRecipesBootstrap}），未解锁时由 mixin 门禁拦住
 * （{@code PotionBrewingMixin} 拦 {@code hasMix}——料放进去无反应、不烧燃料）。
 *
 * <p>名字 lang 键 = {@code item.minecraft.potion.effect.<name>}（Potion#getName
 * 用注册时传的名字串拼前缀，原版无同名键，空闲可用）。
 */
public final class ModPotions {

    public static final DeferredRegister<Potion> POTIONS =
            DeferredRegister.create(Registries.POTION, BmfTown.MOD_ID);

    // ---- 药师线（正面） ----
    public static final DeferredHolder<Potion, Potion> HASTE =
            POTIONS.register("bmfstown_haste", () -> new Potion("bmfstown_haste",
                    new MobEffectInstance(MobEffects.DIG_SPEED, 3600, 0)));
    public static final DeferredHolder<Potion, Potion> RESISTANCE =
            POTIONS.register("bmfstown_resistance", () -> new Potion("bmfstown_resistance",
                    new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 3600, 0)));
    public static final DeferredHolder<Potion, Potion> ABSORPTION =
            POTIONS.register("bmfstown_absorption", () -> new Potion("bmfstown_absorption",
                    new MobEffectInstance(MobEffects.ABSORPTION, 1800, 0)));
    public static final DeferredHolder<Potion, Potion> HEALTH_BOOST =
            POTIONS.register("bmfstown_health_boost", () -> new Potion("bmfstown_health_boost",
                    new MobEffectInstance(MobEffects.HEALTH_BOOST, 3600, 0)));
    public static final DeferredHolder<Potion, Potion> LUCK =
            POTIONS.register("bmfstown_luck", () -> new Potion("bmfstown_luck",
                    new MobEffectInstance(MobEffects.LUCK, 6000, 0)));

    // ---- 毒师线（负面） ----
    public static final DeferredHolder<Potion, Potion> WITHER =
            POTIONS.register("bmfstown_wither", () -> new Potion("bmfstown_wither",
                    new MobEffectInstance(MobEffects.WITHER, 400, 0)));
    public static final DeferredHolder<Potion, Potion> BLINDNESS =
            POTIONS.register("bmfstown_blindness", () -> new Potion("bmfstown_blindness",
                    new MobEffectInstance(MobEffects.BLINDNESS, 200, 0)));
    public static final DeferredHolder<Potion, Potion> DARKNESS =
            POTIONS.register("bmfstown_darkness", () -> new Potion("bmfstown_darkness",
                    new MobEffectInstance(MobEffects.DARKNESS, 260, 0)));
    public static final DeferredHolder<Potion, Potion> NAUSEA =
            POTIONS.register("bmfstown_nausea", () -> new Potion("bmfstown_nausea",
                    new MobEffectInstance(MobEffects.CONFUSION, 400, 0)));
    public static final DeferredHolder<Potion, Potion> MINING_FATIGUE =
            POTIONS.register("bmfstown_mining_fatigue", () -> new Potion("bmfstown_mining_fatigue",
                    new MobEffectInstance(MobEffects.DIG_SLOWDOWN, 600, 0)));
    public static final DeferredHolder<Potion, Potion> LEVITATION =
            POTIONS.register("bmfstown_levitation", () -> new Potion("bmfstown_levitation",
                    new MobEffectInstance(MobEffects.LEVITATION, 200, 0)));

    /** 秘传条目：解锁 id →（产物 potion, 催化剂）。LinkedHashMap 保登记顺序。 */
    public static final Map<String, SecretEntry> SECRETS = new LinkedHashMap<>();

    public record SecretEntry(DeferredHolder<Potion, Potion> potion, Item catalyst) {
    }

    static {
        SECRETS.put("unlock.alchemy.haste",
                new SecretEntry(HASTE, Items.QUARTZ));
        SECRETS.put("unlock.alchemy.resistance",
                new SecretEntry(RESISTANCE, Items.OBSIDIAN));
        SECRETS.put("unlock.alchemy.absorption",
                new SecretEntry(ABSORPTION, Items.GOLD_INGOT));
        SECRETS.put("unlock.alchemy.health_boost",
                new SecretEntry(HEALTH_BOOST, Items.HONEY_BOTTLE));
        SECRETS.put("unlock.alchemy.luck",
                new SecretEntry(LUCK, Items.DANDELION));
        SECRETS.put("unlock.alchemy.wither",
                new SecretEntry(WITHER, Items.SOUL_SAND));
        SECRETS.put("unlock.alchemy.blindness",
                new SecretEntry(BLINDNESS, Items.INK_SAC));
        SECRETS.put("unlock.alchemy.darkness",
                new SecretEntry(DARKNESS, Items.SCULK));
        SECRETS.put("unlock.alchemy.nausea",
                new SecretEntry(NAUSEA, Items.ROTTEN_FLESH));
        SECRETS.put("unlock.alchemy.mining_fatigue",
                new SecretEntry(MINING_FATIGUE, Items.DRIPSTONE_BLOCK));
        SECRETS.put("unlock.alchemy.levitation",
                new SecretEntry(LEVITATION, Items.SHULKER_SHELL));
    }

    /** 该产物药水对应的解锁 id（mixin 门禁用；非秘传药水 = null）。 */
    @javax.annotation.Nullable
    public static String secretIdOf(Holder<Potion> potion) {
        for (var e : SECRETS.entrySet()) {
            if (e.getValue().potion().get() == potion.value()) {
                return e.getKey();
            }
        }
        return null;
    }

    /** 由产物药水反查秘传条目（目录反查用；非秘传药水 = null）。 */
    @javax.annotation.Nullable
    public static SecretEntry entryOf(Holder<Potion> potion) {
        for (var e : SECRETS.entrySet()) {
            if (e.getValue().potion().get() == potion.value()) {
                return e.getValue();
            }
        }
        return null;
    }

    /** 该催化剂是否参与某条秘传组合（hasMix 门禁的快速预筛）。 */
    public static boolean isSecretCatalyst(Item item) {
        return SECRETS.values().stream().anyMatch(e -> e.catalyst() == item);
    }

    private ModPotions() {
    }
}
