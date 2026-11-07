package com.bmf.bmfstown.alchemy;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.alchemy.ModPotions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;

/**
 * 秘传门禁的全局开关（v0.12 炼金）：配方书发现（写成即 unlockIfNew，
 * ScholarGoal 挂钩调用 {@link #open}）→ 全服开放该配方的原版酿造。
 * 状态持久化在主世界 SavedData；mixin（{@code PotionBrewingMixin}）经
 * 静态缓存查询——实例未挂上（极端时序）按锁定处理（只影响秘传，原版无感）。
 */
@EventBusSubscriber(modid = BmfTown.MOD_ID)
public final class AlchemyGate extends SavedData {

    private static final String DATA_NAME = "bmfstown_alchemy_gate";

    /** 已解锁的秘传 unlockId（持久化）。 */
    private final Set<String> unlocked = new HashSet<>();

    /** mixin 查询用的静态策略：活状态查 SavedData，未挂上时全锁。 */
    private interface Strategy {
        boolean locked(String secretId);
    }

    private static final Strategy LOCKED_ALL = id -> true;
    private static volatile Strategy strategy = LOCKED_ALL;

    public AlchemyGate() {
    }

    private AlchemyGate(CompoundTag tag) {
        ListTag list = tag.getList("Unlocked", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            this.unlocked.add(list.getString(i));
        }
    }

    // ---- 生命周期 ----

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        attach(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        strategy = LOCKED_ALL;
    }

    private static void attach(MinecraftServer server) {
        AlchemyGate gate = server.overworld().getDataStorage()
                .computeIfAbsent(new SavedData.Factory<>(AlchemyGate::new,
                        AlchemyGate::load, null), DATA_NAME);
        strategy = secretId -> !gate.unlocked.contains(secretId);
    }

    /** 解锁某条秘传配方（ScholarGoal 的 Unlock 结算挂钩调用；幂等）。 */
    public static void open(MinecraftServer server, String unlockId) {
        if (!ModPotions.SECRETS.containsKey(unlockId)) {
            return;
        }
        AlchemyGate gate = server.overworld().getDataStorage()
                .computeIfAbsent(new SavedData.Factory<>(AlchemyGate::new,
                        AlchemyGate::load, null), DATA_NAME);
        if (gate.unlocked.add(unlockId)) {
            gate.setDirty();
        }
    }

    // ---- 查询 ----

    /** 该秘传配方当前是否已全局解锁（Goal 侧门禁用）。 */
    public static boolean isOpen(String secretId) {
        return !strategy.locked(secretId);
    }

    // ---- mixin 查询 ----

    /** 这对"（底药， 催化剂）"是否仍被门禁锁着（true = 拦下 hasMix）。 */
    public static boolean isLockedCombo(ItemStack from, ItemStack reagent) {
        if (!ModPotions.isSecretCatalyst(reagent.getItem())) {
            return false; // 快速预筛：催化剂与秘传无关
        }
        String secretId = secretIdOfCombo(from, reagent);
        return secretId != null && strategy.locked(secretId);
    }

    /** 由"粗制底药+催化剂"反查秘传条目；非秘传组合返回 null。 */
    @Nullable
    private static String secretIdOfCombo(ItemStack from, ItemStack reagent) {
        var fromPotion = from.getOrDefault(
                net.minecraft.core.component.DataComponents.POTION_CONTENTS,
                PotionContents.EMPTY).potion().orElse(null);
        if (fromPotion == null || fromPotion.value() != Potions.AWKWARD.value()) {
            return null; // 秘传 mix 的 from 恒为粗制药水
        }
        for (var e : ModPotions.SECRETS.entrySet()) {
            if (e.getValue().catalyst() == reagent.getItem()) {
                return e.getKey();
            }
        }
        return null;
    }

    // ---- SavedData ----

    private static AlchemyGate load(CompoundTag tag,
            net.minecraft.core.HolderLookup.Provider registries) {
        return new AlchemyGate(tag);
    }

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (String id : this.unlocked) {
            list.add(StringTag.valueOf(id));
        }
        tag.put("Unlocked", list);
        return tag;
    }
}
