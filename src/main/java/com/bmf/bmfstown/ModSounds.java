package com.bmf.bmfstown;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * mod 音效注册。四个都是 perk 的反馈音（声音文件在
 * {@code assets/bmfstown/sounds/}，映射关系见 {@code sounds.json}）。
 *
 * <p>播放一律走 {@code level.playSound(null, 实体, ModSounds.X.get(), ...)}
 * ——第一个参数 null 表示"按监听距离广播"（而不是只发给某个玩家）。
 */
public final class ModSounds {

    private static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, BmfTown.MOD_ID);

    /** 嘲讽施放：沉重的打击声（用户定稿：不做人声模仿，用打击感）。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> TAUNT_IMPACT =
            SOUNDS.register("perk.taunt_impact",
                    () -> SoundEvent.createVariableRangeEvent(
                            ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "perk.taunt_impact")));

    /** 斩杀触发：斧头砍肉的闷响+噗哧。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> EXECUTE_CHOP =
            SOUNDS.register("perk.execute_chop",
                    () -> SoundEvent.createVariableRangeEvent(
                            ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "perk.execute_chop")));

    /** 追猎施放：金属撞击叮。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> HUNT_CLANG =
            SOUNDS.register("perk.hunt_clang",
                    () -> SoundEvent.createVariableRangeEvent(
                            ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "perk.hunt_clang")));

    /** 坚盾施放：武器打到盾牌上。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> BULWARK_HIT =
            SOUNDS.register("perk.bulwark_hit",
                    () -> SoundEvent.createVariableRangeEvent(
                            ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "perk.bulwark_hit")));

    public static void register(IEventBus modEventBus) {
        SOUNDS.register(modEventBus);
    }

    private ModSounds() {
    }
}
