package com.bmf.bmfstown;

import com.bmf.bmfstown.town.TownWandMode;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 物品数据组件注册。
 *
 * <h2>为什么用数据组件而不是 NBT 字段</h2>
 * 1.20.5 之后物品的自定义数据统一走 {@link DataComponentType}：
 * <ul>
 *   <li>自动随物品同步到客户端（放进背包立刻能读，不用手写同步包）</li>
 *   <li>自动随物品存进存档（掉落、箱子、复制都不会丢）</li>
 *   <li>类型安全，{@code get()} 出来就是强类型</li>
 * </ul>
 *
 * <p>魔杖的状态（当前模式、已选的点）全存在魔杖自己的组件上，
 * 两根魔杖互不干扰，把魔杖扔在地上再捡起来数据还在——
 * 只有"离开玩家主手"时才由 {@link TownWandItem#inventoryTick} 主动清掉选点。
 */
public final class ModDataComponents {

    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, BmfTown.MOD_ID);

    /**
     * 魔杖当前模式。存的是 {@link TownWandMode#ordinal()}。
     *
     * <p>组件缺省（没 set 过）视为 0 = TOWN。
     * persistent 用 intRange 限定范围，坏数据直接被拒载，不会崩在读档时。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> WAND_MODE =
            COMPONENTS.register("wand_mode", () -> DataComponentType.<Integer>builder()
                    .persistent(Codec.intRange(0, TownWandMode.values().length - 1))
                    .networkSynchronized(ByteBufCodecs.VAR_INT)
                    .build());

    /**
     * 选定的第一个角（对角选立方体的第一点）。
     *
     * <p>BlockPos 自带现成的 {@link BlockPos#CODEC}（存档）和
     * {@link BlockPos#STREAM_CODEC}（网络），不用自己写编解码。
     * 组件缺省 = 还没选第一个点。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BlockPos>> WAND_POINT_A =
            COMPONENTS.register("wand_point_a", () -> DataComponentType.<BlockPos>builder()
                    .persistent(BlockPos.CODEC)
                    .networkSynchronized(BlockPos.STREAM_CODEC)
                    .build());

    /**
     * 选定的第二个角。两点齐全时预览盒固定；只有 A 时预览盒跟随准心。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BlockPos>> WAND_POINT_B =
            COMPONENTS.register("wand_point_b", () -> DataComponentType.<BlockPos>builder()
                    .persistent(BlockPos.CODEC)
                    .networkSynchronized(BlockPos.STREAM_CODEC)
                    .build());

    private ModDataComponents() {
    }

    public static void register(IEventBus modEventBus) {
        COMPONENTS.register(modEventBus);
    }
}
