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

    /**
     * 书的内容（v0.11 学者系统）：写作中的书与成书共用。
     * 作者链 + 密封内容单（{@link com.bmf.bmfstown.book.BookDraft}）+
     * 写作进度/目标 + 卡点解除标记。详见 {@link com.bmf.bmfstown.book.BookContent}。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<com.bmf.bmfstown.book.BookContent>> BOOK_CONTENT =
            COMPONENTS.register("book_content", () -> DataComponentType.<com.bmf.bmfstown.book.BookContent>builder()
                    .persistent(com.bmf.bmfstown.book.BookContent.CODEC)
                    .networkSynchronized(com.bmf.bmfstown.book.BookContent.STREAM_CODEC)
                    .build());

    /** 抄本标记（v0.10.4）：抄书产物 → 悬浮显示"抄本 · 抄工：X"。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> BOOK_COPIED =
            COMPONENTS.register("book_copied", () -> DataComponentType.<Boolean>builder()
                    .persistent(com.mojang.serialization.Codec.BOOL)
                    .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.BOOL)
                    .build());

    /**
     * 浊药纯度（v0.12 炼金，决策 #27）：0.0~1.0 连续值，炼金术士取料时打上。
     * P&lt;1 = 浊药（药效时长 ×P，喝下/命中时按 1−P 概率窜味——杂质不存组件、
     * 现抽保证喝前不可见）。组件缺省 = 非浊药（玩家手酿无此标记）。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Float>> PURITY =
            COMPONENTS.register("purity", () -> DataComponentType.<Float>builder()
                    .persistent(Codec.floatRange(0.0F, 1.0F))
                    .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.FLOAT)
                    .build());

    /**
     * 杂质豁免（v0.12 炼金，决策 #28 医师/毒石）：炼金术士盖章时若选了对称净化
     * 分支（医师=正面药免负面杂质 / 毒石=负面药免正面杂质），浊药打上此标记；
     * 喝下/命中时杂质现抽直接跳过。药效时长折扣不受影响。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> ALCHEMY_PURE =
            COMPONENTS.register("alchemy_pure", () -> DataComponentType.<Boolean>builder()
                    .persistent(com.mojang.serialization.Codec.BOOL)
                    .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.BOOL)
                    .build());

    /**
     * 烟火工匠章（v0.13）：工匠战斗向 perk 的产物标记——爆炸挂点（FireworkRocketEntityMixin）
     * 读章生效（破片半径/装药伤害/回响二段爆），无章火箭走原版伤害。与炼金 stamp 同模式：
     * 效果在出厂时固化进产物，工匠不在场照样生效。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<com.bmf.bmfstown.fireworks.FireworksMark>> FIREWORKS_MARK =
            COMPONENTS.register("fireworks_mark", () -> DataComponentType.<com.bmf.bmfstown.fireworks.FireworksMark>builder()
                    .persistent(com.bmf.bmfstown.fireworks.FireworksMark.CODEC)
                    .networkSynchronized(com.bmf.bmfstown.fireworks.FireworksMark.STREAM_CODEC)
                    .build());

    private ModDataComponents() {
    }

    public static void register(IEventBus modEventBus) {
        COMPONENTS.register(modEventBus);
    }
}
