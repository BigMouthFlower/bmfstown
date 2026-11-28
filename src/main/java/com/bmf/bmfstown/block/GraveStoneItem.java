package com.bmf.bmfstown.block;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.block.Block;

import javax.annotation.Nullable;

/**
 * 墓碑物品（2026-11-28 决策 #144）。
 *
 * <h2>为什么必须自己写一个 BlockItem</h2>
 * 8 种石材<b>共用一个 Item</b>（决策 #143 定的，否则物品栏被 8 个条目塞满），
 * 材质存在原版 {@code block_state} 组件里。原版 {@code BlockItem.getName} 恒返回
 * {@code translatable(getDescriptionId())} = "墓碑"，<b>不看组件</b> ⇒ 玩家在物品栏里
 * 看到 8 个一模一样的"墓碑"，根本分不出哪块是深板岩。
 *
 * <p>这里按组件里的 {@code variant} 取对应的 lang key。那 8 个键
 * （{@code block.bmfstown.grave_stone.deepslate_bricks} 等）<b>早就写进 lang 了</b>，
 * 只是一直没人读——本类就是把它们接上的那根线。
 *
 * <h2>为什么图标也要单独接（不进本类）</h2>
 * 物品图标由客户端 item model 分派，服务端代码管不着。见
 * {@code BmfTownClient.onClientSetup} 里注册的 {@code bmfstown:grave_variant}
 * 属性 + {@code models/item/grave_stone.json} 的 {@code overrides}。
 * 1.21.1 确实没有 {@code minecraft:select}（那是 1.21.4 的），但
 * {@code overrides} + {@code ItemProperties.register} 在 1.21.1 是完整可用的，
 * 只是 NeoForge 把 {@code register} 从 private 改成了 public——这是本方案成立的前提。
 */
public class GraveStoneItem extends BlockItem {

    public GraveStoneItem(Block block, Properties properties) {
        super(block, properties);
    }

    /**
     * 这块碑是哪种石材。
     *
     * <p>三种输入都兜底成石砖：没有组件（创造栏/命令给的裸物品）、组件里没有
     * {@code variant} 键、键值不认识（手改存档）。<b>绝不抛异常</b>——
     * 一个物品名解析失败会连锁到整个工具提示/创造栏渲染。
     */
    public static GraveStoneVariant variantOf(ItemStack stack) {
        BlockItemStateProperties props = stack.get(DataComponents.BLOCK_STATE);
        if (props == null) {
            return GraveStoneVariant.STONE_BRICKS;
        }
        GraveStoneVariant v = props.get(GraveStoneBlock.VARIANT);
        return v == null ? GraveStoneVariant.STONE_BRICKS : v;
    }

    /**
     * 名字按材质变：组件说深板岩 ⇒ "深板岩砖墓碑"。
     *
     * <p>与 {@link net.minecraft.world.item.Item#getName(ItemStack)} 同一条链：
     * 客户端渲染、铁砧、告示牌、死亡消息全都读这里，改一处就全对。
     */
    @Override
    public Component getName(ItemStack stack) {
        GraveStoneVariant v = variantOf(stack);
        // 枚举的 8 个 id 与 lang 键后缀由 GraveStoneVariant 统一持有，
        // 这里只拼前缀——加第 9 种石头时这里一个字不用改。
        return Component.translatable(
                "block.bmfstown.grave_stone." + v.getSerializedName());
    }

    /**
     * 给指定材质造一个物品堆（创造栏 + 切石机配方同口径）。
     *
     * <p>公开成静态方法是为了让创造栏与用例<b>共用同一段构造</b>：
     * 手抄 `BlockItemStateProperties.EMPTY.with(...)` 两遍，迟早有一遍写错键名，
     * 而写错的后果是<b>静默</b>变成石砖（{@code BlockItemStateProperties.apply}
     * 不认识键就跳过，不报错）。
     */
    public static ItemStack stackFor(@Nullable GraveStoneVariant variant) {
        ItemStack stack = new ItemStack(ModBlocks.GRAVE_STONE.get().asItem());
        GraveStoneVariant v = variant == null ? GraveStoneVariant.STONE_BRICKS : variant;
        stack.set(DataComponents.BLOCK_STATE,
                BlockItemStateProperties.EMPTY.with(GraveStoneBlock.VARIANT, v));
        return stack;
    }
}