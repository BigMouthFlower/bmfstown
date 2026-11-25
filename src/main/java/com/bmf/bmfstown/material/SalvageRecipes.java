package com.bmf.bmfstown.material;

import com.bmf.bmfstown.ModItems;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 建材回收表（v0.17 石匠线）：旧建材 → 通货的兑换率，唯一权威来源。
 *
 * <h2>三种通货，互不通兑（2026-11-25 用户拍板）</h2>
 * <ul>
 *   <li><b>石材</b>：石类建材的料。花岗岩/深板岩/凝灰岩/黑石拆出来都是它。</li>
 *   <li><b>木材</b>：木类建材的料。各树种、各木制建材拆出来都是它。</li>
 *   <li><b>碎沙</b>：沙类建材的料。<b>为什么不并入石材</b>：砂岩的原版来源是
 *       沙子（4 沙 → 1 砂岩），沙子可再生、石头不可再生。若砂岩拆成石材，
 *       等于开了一条"沙子 → 石头"的无限刷料通道，直接击穿平衡。
 *       用户 2026-11-25 原话："沙子类的东西和石头的东西能够互化，我觉得不太好"。</li>
 * </ul>
 *
 * <h2>兑换率怎么来的（不是拍脑袋，是从原版数据反推的）</h2>
 * <p><b>石材组</b>：读原版 {@code minecraft:stonecutting} 配方全部 250 条，
 * 按"1 份基础原料 → N 个成品"反推每个成品值多少料（{@code 1/N}）。
 * 因此天然守恒——玩家把料切成台阶再拆回来，一个来回既不赚也不亏，
 * 只是花掉了石匠的上班时间。台阶是 {@code count=2}，所以"2 个台阶 = 1 份料"，
 * 拆单个台阶不给料（{@link #batchOf} 就是为此存在）。
 *
 * <p><b>碎沙组</b>：以"1 砂岩块 = 1 份碎沙"为基准，与石材线"1 石 = 1 份石材"同构
 * （2026-11-25 用户拍板推翻初版的"1 砂岩 = 4 碎沙"）。
 * <b>为什么不能用 4</b>：4 沙才能合成 1 砂岩，若 1 砂岩拆出 4 份碎沙，
 * 就意味着"1 份碎沙 = 1/4 块砂岩"，而切石机下限是 1 个方块、造不出分数个成品，
 * 这条线会当场地穿（只能退化成工作台配方，用户明确否决："应该跟石料布料一样，
 * 在切石机中做它们所属属性的那些东西"）。用 1 则三线同构：拆出来 1 份、
 * 再切回去正好 1 个方块，来回守恒，代价只是石匠的上班时间。
 *
 * <p><b>木材组</b>：原版<b>没有任何木类切石配方</b>（250 条全是石类），
 * 所以木材没有可反推的官方账，改按"木板为基准"从原版合成配方反推：
 * {@code 1 原木 → 4 木板}，而 1 原木 = 1 个方块体积，故 <b>1 木材 ≡ 1 木板</b>，
 * 再据此推出台阶 6/3=2、楼梯 4/6≈2/3、门 3/6=1/2 等。见 {@link #WOOD_RATIOS}。
 *
 * <h2>留白清单（用户 2026-11-25："这两个先留着，先留白"）</h2>
 * <p>以下材质<b>刻意不做</b>，将来要开只要往表里加条目即可：
 * <b>铜</b>（金属）、<b>石英</b>（矿物）、<b>下界砖</b>、<b>紫珀</b>、
 * <b>海晶石</b>、<b>末地石</b>、<b>泥砖</b>、<b>红砖</b>——它们来源各不相同
 * （金属/矿物/下界岩/紫颂果/海底神殿/末地/泥巴/黏土），
 * 与"地下挖的石头"不是一类，混在一起会出戏（也会让石匠变成万能粉碎机）。
 *
 * <h2>守恒性怎么保证</h2>
 * <p>全表用一个标量"份"衡量价值：每个物品值 {@code amount/batch} 份。
 * 只要原版<b>任何一条其他配方</b>（工作台合成等）产出同一物品的成本
 * 都不低于这个值，就不存在"用工作台造、用石匠拆"的套利环。
 * 已用脚本核过原版数据：0 处套利（见 .ai/decisions.md 石匠条）。
 */
public final class SalvageRecipes {

    /** 一份通货属于哪条线。 */
    public enum Line {
        STONE(ModItems.MATERIAL_STONE),
        WOOD(ModItems.MATERIAL_WOOD),
        SAND(ModItems.MATERIAL_SAND);

        private final net.neoforged.neoforge.registries.DeferredItem<Item> item;

        Line(net.neoforged.neoforge.registries.DeferredItem<Item> item) {
            this.item = item;
        }

        /** 该线的通货物品。 */
        public Item item() {
            return this.item.get();
        }

        /** 该线的通货物品堆（给容器插入/发放用）。 */
        public ItemStack stack(int count) {
            return new ItemStack(this.item(), count);
        }
    }

    /** 一条兑换规则：{@code batch} 个 {@code item} → {@code amount} 份通货。 */
    public record Rule(Item item, Line line, int batch, int amount) {
        /** 这条规则能一次拆多少个（受手上数量与 batch 约束）。 */
        public int batches(int held) {
            return held / this.batch;
        }
    }

    /** 物品 → 规则（主索引，O(1) 查）。 */
    private static final Map<Item, Rule> BY_ITEM = new LinkedHashMap<>();

    /** 全表（面板展示/测试遍历用，保序）。 */
    private static final List<Rule> ALL = new ArrayList<>();

    static {
        // ---- 石材组：从原版 stonecutting 反推（batch = 该配方的产出数，amount = 1）----
        // 说明：count=2 的（台阶）→ 2 个换 1 份；count=1 的 → 1 个换 1 份。
        // 铜/石英/下界砖/紫珀/海晶石/末地石/泥砖/红砖全部不在表内（留白）。
            add(Blocks.ANDESITE, 1, 1);
            add(Blocks.ANDESITE_SLAB, 2, 1);
            add(Blocks.ANDESITE_STAIRS, 1, 1);
            add(Blocks.ANDESITE_WALL, 1, 1);
            add(Blocks.BASALT, 1, 1);
            add(Blocks.BLACKSTONE, 1, 1);
            add(Blocks.BLACKSTONE_SLAB, 2, 1);
            add(Blocks.BLACKSTONE_STAIRS, 1, 1);
            add(Blocks.BLACKSTONE_WALL, 1, 1);
            add(Blocks.CHISELED_DEEPSLATE, 1, 1);
            add(Blocks.CHISELED_POLISHED_BLACKSTONE, 1, 1);
            add(Blocks.CHISELED_STONE_BRICKS, 1, 1);
            add(Blocks.CHISELED_TUFF, 1, 1);
            add(Blocks.CHISELED_TUFF_BRICKS, 1, 1);
            add(Blocks.COBBLED_DEEPSLATE, 1, 1);
            add(Blocks.COBBLED_DEEPSLATE_SLAB, 2, 1);
            add(Blocks.COBBLED_DEEPSLATE_STAIRS, 1, 1);
            add(Blocks.COBBLED_DEEPSLATE_WALL, 1, 1);
            add(Blocks.COBBLESTONE, 1, 1);
            add(Blocks.COBBLESTONE_SLAB, 2, 1);
            add(Blocks.COBBLESTONE_STAIRS, 1, 1);
            add(Blocks.COBBLESTONE_WALL, 1, 1);
            add(Blocks.DEEPSLATE, 1, 1);
            add(Blocks.DEEPSLATE_BRICK_SLAB, 2, 1);
            add(Blocks.DEEPSLATE_BRICK_STAIRS, 1, 1);
            add(Blocks.DEEPSLATE_BRICK_WALL, 1, 1);
            add(Blocks.DEEPSLATE_BRICKS, 1, 1);
            add(Blocks.DEEPSLATE_TILE_SLAB, 2, 1);
            add(Blocks.DEEPSLATE_TILE_STAIRS, 1, 1);
            add(Blocks.DEEPSLATE_TILE_WALL, 1, 1);
            add(Blocks.DEEPSLATE_TILES, 1, 1);
            add(Blocks.DIORITE, 1, 1);
            add(Blocks.DIORITE_SLAB, 2, 1);
            add(Blocks.DIORITE_STAIRS, 1, 1);
            add(Blocks.DIORITE_WALL, 1, 1);
            add(Blocks.GRANITE, 1, 1);
            add(Blocks.GRANITE_SLAB, 2, 1);
            add(Blocks.GRANITE_STAIRS, 1, 1);
            add(Blocks.GRANITE_WALL, 1, 1);
            add(Blocks.MOSSY_COBBLESTONE, 1, 1);
            add(Blocks.MOSSY_COBBLESTONE_SLAB, 2, 1);
            add(Blocks.MOSSY_COBBLESTONE_STAIRS, 1, 1);
            add(Blocks.MOSSY_COBBLESTONE_WALL, 1, 1);
            add(Blocks.MOSSY_STONE_BRICK_SLAB, 2, 1);
            add(Blocks.MOSSY_STONE_BRICK_STAIRS, 1, 1);
            add(Blocks.MOSSY_STONE_BRICK_WALL, 1, 1);
            add(Blocks.MOSSY_STONE_BRICKS, 1, 1);
            add(Blocks.POLISHED_ANDESITE, 1, 1);
            add(Blocks.POLISHED_ANDESITE_SLAB, 2, 1);
            add(Blocks.POLISHED_ANDESITE_STAIRS, 1, 1);
            add(Blocks.POLISHED_BASALT, 1, 1);
            add(Blocks.POLISHED_BLACKSTONE, 1, 1);
            add(Blocks.POLISHED_BLACKSTONE_BRICK_SLAB, 2, 1);
            add(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, 1, 1);
            add(Blocks.POLISHED_BLACKSTONE_BRICK_WALL, 1, 1);
            add(Blocks.POLISHED_BLACKSTONE_BRICKS, 1, 1);
            add(Blocks.POLISHED_BLACKSTONE_SLAB, 2, 1);
            add(Blocks.POLISHED_BLACKSTONE_STAIRS, 1, 1);
            add(Blocks.POLISHED_BLACKSTONE_WALL, 1, 1);
            add(Blocks.POLISHED_DEEPSLATE, 1, 1);
            add(Blocks.POLISHED_DEEPSLATE_SLAB, 2, 1);
            add(Blocks.POLISHED_DEEPSLATE_STAIRS, 1, 1);
            add(Blocks.POLISHED_DEEPSLATE_WALL, 1, 1);
            add(Blocks.POLISHED_DIORITE, 1, 1);
            add(Blocks.POLISHED_DIORITE_SLAB, 2, 1);
            add(Blocks.POLISHED_DIORITE_STAIRS, 1, 1);
            add(Blocks.POLISHED_GRANITE, 1, 1);
            add(Blocks.POLISHED_GRANITE_SLAB, 2, 1);
            add(Blocks.POLISHED_GRANITE_STAIRS, 1, 1);
            add(Blocks.POLISHED_TUFF, 1, 1);
            add(Blocks.POLISHED_TUFF_SLAB, 2, 1);
            add(Blocks.POLISHED_TUFF_STAIRS, 1, 1);
            add(Blocks.POLISHED_TUFF_WALL, 1, 1);
            add(Blocks.SMOOTH_STONE, 1, 1);
            add(Blocks.SMOOTH_STONE_SLAB, 2, 1);
            add(Blocks.STONE, 1, 1);
            add(Blocks.STONE_BRICK_SLAB, 2, 1);
            add(Blocks.STONE_BRICK_STAIRS, 1, 1);
            add(Blocks.STONE_BRICK_WALL, 1, 1);
            add(Blocks.STONE_BRICKS, 1, 1);
            add(Blocks.STONE_SLAB, 2, 1);
            add(Blocks.STONE_STAIRS, 1, 1);
            add(Blocks.TUFF, 1, 1);
            add(Blocks.TUFF_BRICK_SLAB, 2, 1);
            add(Blocks.TUFF_BRICK_STAIRS, 1, 1);
            add(Blocks.TUFF_BRICK_WALL, 1, 1);
            add(Blocks.TUFF_BRICKS, 1, 1);
            add(Blocks.TUFF_SLAB, 2, 1);
            add(Blocks.TUFF_STAIRS, 1, 1);
            add(Blocks.TUFF_WALL, 1, 1);

        // ---- 碎沙组：1 砂岩块 = 1 碎沙（v0.17 修正基准，2026-11-25 用户拍板）----
        // 旧基准是「1 砂岩 = 4 碎沙」（依原版 4 沙 → 1 砂岩），结果是
        // **碎沙在切石机里一条配方都没有**：1 碎沙只值 1/4 个砂岩，而切石机
        // 一次只能吃 1 个输入物品（原版 inputSlot.remove(1)），造不出 1/4 个方块。
        // 用户原话："它应该跟石料木材一样，在切石机中做它们所属属性的那些东西。"
        // ⇒ 基准改成「1 碎沙 = 1 砂岩块」，与「1 石材 = 1 石头」「1 木材 = 1 原木」
        // 完全同构，反向配方 count 直接照抄原版切石配方，零手填。
        //
        // 守恒性：4 沙 → 1 砂岩（原版工作台）→ 拆 1 碎沙 → 只能切回 1 砂岩/它的形态。
        // 沙的 4:1 压缩是不可逆的（原版没有"砂岩 → 沙"），所以碎沙实际是"压缩沙料"。
            addSand(Blocks.SANDSTONE, 1, 1);            // 基准：1 砂岩 = 1 碎沙
            addSand(Blocks.SANDSTONE_SLAB, 2, 1);       // 1 砂岩切 2 台阶
            addSand(Blocks.SANDSTONE_STAIRS, 1, 1);
            addSand(Blocks.SANDSTONE_WALL, 1, 1);
            addSand(Blocks.CHISELED_SANDSTONE, 1, 1);
            addSand(Blocks.CUT_SANDSTONE, 1, 1);
            addSand(Blocks.CUT_SANDSTONE_SLAB, 2, 1);
            addSand(Blocks.SMOOTH_SANDSTONE, 1, 1);     // 原版烧制产物，与石材线"平滑石"同口径
            addSand(Blocks.SMOOTH_SANDSTONE_SLAB, 2, 1);
            addSand(Blocks.SMOOTH_SANDSTONE_STAIRS, 1, 1);
            addSand(Blocks.RED_SANDSTONE, 1, 1);
            addSand(Blocks.RED_SANDSTONE_SLAB, 2, 1);
            addSand(Blocks.RED_SANDSTONE_STAIRS, 1, 1);
            addSand(Blocks.RED_SANDSTONE_WALL, 1, 1);
            addSand(Blocks.CHISELED_RED_SANDSTONE, 1, 1);
            addSand(Blocks.CUT_RED_SANDSTONE, 1, 1);
            addSand(Blocks.CUT_RED_SANDSTONE_SLAB, 2, 1);
            addSand(Blocks.SMOOTH_RED_SANDSTONE, 1, 1);
            addSand(Blocks.SMOOTH_RED_SANDSTONE_SLAB, 2, 1);
            addSand(Blocks.SMOOTH_RED_SANDSTONE_STAIRS, 1, 1);

        // ---- 木材组：木板为基准（1 木材 ≡ 1 木板），从原版合成配方反推 ----
        // 全部能整除的分母只有 2/3/6 三种，见每条的注释。
        // 基准（v0.17 修正）：**1 木材 = 1 原木 = 4 木板**，即 1 木板 = 1/4 木材。
        // 为什么不是"1 木材 = 1 木板"：切石机每切一次只消耗 1 个输入物品
        // （原版 inputSlot.remove(1)），若 1 木材只值 1 木板，那楼梯(需 1.5 板)、
        // 门(2 板)、活板门(3 板) 等 7 种建材**一份木材根本造不出来**；
        // 而强行给"1 木材 → 1 门"又会让玩家切完拆回净赚 1 木材（套利）。
        // 改成 1 木材 = 1 原木后：反向配方全部成立，且逐条核过 0 套利
        // （8 条平衡，楼梯/活板门因向下取整略微亏，见 decisions #124 修正条）。
        addWood(Blocks.OAK_LOG, 1, 1);              // 1 原木 = 1 木材（基准!）
        addWood(Blocks.OAK_WOOD, 1, 1);
        addWood(Blocks.STRIPPED_OAK_LOG, 1, 1);
        addWood(Blocks.STRIPPED_OAK_WOOD, 1, 1);
        addWood(Blocks.OAK_PLANKS, 4, 1);           // 4 板 = 1 木材
        addWood(Blocks.OAK_SLAB, 8, 1);             // 3 板→6 台阶 ⇒ 8 台阶 = 1 木材
        addWood(Blocks.OAK_STAIRS, 8, 3);           // 6 板→4 楼梯 ⇒ 8 楼梯 = 3 木材
        addWood(Blocks.OAK_DOOR, 2, 1);             // 6 板→3 门 ⇒ 2 门 = 1 木材
        addWood(Blocks.OAK_TRAPDOOR, 4, 3);         // 6 板→2 活板门 ⇒ 4 活板 = 3 木材
        addWood(Blocks.OAK_FENCE_GATE, 1, 1);       // 2 板+4 棍(=2 板)→1 门 ⇒ 1 门 = 1 木材
        addWood(Blocks.OAK_PRESSURE_PLATE, 2, 1);   // 2 板→1 板 ⇒ 2 板 = 1 木材
        addWood(Blocks.OAK_BUTTON, 4, 1);           // 1 板→1 按钮 ⇒ 4 按钮 = 1 木材
        // 下面两条精确值是 5/12 与 13/24，凑整批要 12 个/24 个，玩家手上很难有那么多。
        // 按"留损耗"口径向下取整成友好批次（亏 4%/7.7%），且**绝不向上取整**——
        // 向上取整会让"原版工作台造 → 石匠拆"变成正收益环（已核算过会套利）。
        addWood(Blocks.OAK_FENCE, 5, 2);            // 5 栅栏 = 2 木材（精确 5/12≈0.417，取 0.400）
        addWood(Blocks.OAK_SIGN, 2, 1);             // 2 牌 = 1 木材（精确 13/24≈0.542，取 0.500）
        // 其余树种：同比例（原版各树种配方完全同构），统一映射到木材，见 addWoodLike。
        // 竹系例外：原版 1 竹块 → 2 竹板（其余树种是 4），所以 **2 竹块 = 1 木材**。
        // 套利核过：2 竹块 → 4 竹板 → 拆回 1 木材，平衡（不赚不亏）。
        addWood(Blocks.BAMBOO_BLOCK, 2, 1);
        addWood(Blocks.STRIPPED_BAMBOO_BLOCK, 2, 1);
        // 竹马赛克：原版 2 竹台阶 → 1 马赛克方块。竹台阶 8 换 1 木材 ⇒
        // 1 马赛克 = 2 台阶 = 1/4 木材，与木板同价，故 4 换 1。
        addWood(Blocks.BAMBOO_MOSAIC, 4, 1);
        addWood(Blocks.BAMBOO_MOSAIC_SLAB, 8, 1);
        addWood(Blocks.BAMBOO_MOSAIC_STAIRS, 8, 3);
        for (Block b : new Block[]{
                Blocks.SPRUCE_LOG, Blocks.SPRUCE_WOOD, Blocks.STRIPPED_SPRUCE_LOG,
                Blocks.STRIPPED_SPRUCE_WOOD, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_SLAB,
                Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_DOOR, Blocks.SPRUCE_TRAPDOOR,
                Blocks.SPRUCE_FENCE, Blocks.SPRUCE_FENCE_GATE, Blocks.SPRUCE_PRESSURE_PLATE,
                Blocks.SPRUCE_BUTTON, Blocks.SPRUCE_SIGN,
                Blocks.BIRCH_LOG, Blocks.BIRCH_WOOD, Blocks.STRIPPED_BIRCH_LOG,
                Blocks.STRIPPED_BIRCH_WOOD, Blocks.BIRCH_PLANKS, Blocks.BIRCH_SLAB,
                Blocks.BIRCH_STAIRS, Blocks.BIRCH_DOOR, Blocks.BIRCH_TRAPDOOR,
                Blocks.BIRCH_FENCE, Blocks.BIRCH_FENCE_GATE, Blocks.BIRCH_PRESSURE_PLATE,
                Blocks.BIRCH_BUTTON, Blocks.BIRCH_SIGN,
                Blocks.JUNGLE_LOG, Blocks.JUNGLE_WOOD, Blocks.STRIPPED_JUNGLE_LOG,
                Blocks.STRIPPED_JUNGLE_WOOD, Blocks.JUNGLE_PLANKS, Blocks.JUNGLE_SLAB,
                Blocks.JUNGLE_STAIRS, Blocks.JUNGLE_DOOR, Blocks.JUNGLE_TRAPDOOR,
                Blocks.JUNGLE_FENCE, Blocks.JUNGLE_FENCE_GATE, Blocks.JUNGLE_PRESSURE_PLATE,
                Blocks.JUNGLE_BUTTON, Blocks.JUNGLE_SIGN,
                Blocks.ACACIA_LOG, Blocks.ACACIA_WOOD, Blocks.STRIPPED_ACACIA_LOG,
                Blocks.STRIPPED_ACACIA_WOOD, Blocks.ACACIA_PLANKS, Blocks.ACACIA_SLAB,
                Blocks.ACACIA_STAIRS, Blocks.ACACIA_DOOR, Blocks.ACACIA_TRAPDOOR,
                Blocks.ACACIA_FENCE, Blocks.ACACIA_FENCE_GATE, Blocks.ACACIA_PRESSURE_PLATE,
                Blocks.ACACIA_BUTTON, Blocks.ACACIA_SIGN,
                Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_WOOD, Blocks.STRIPPED_DARK_OAK_LOG,
                Blocks.STRIPPED_DARK_OAK_WOOD, Blocks.DARK_OAK_PLANKS, Blocks.DARK_OAK_SLAB,
                Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_DOOR, Blocks.DARK_OAK_TRAPDOOR,
                Blocks.DARK_OAK_FENCE, Blocks.DARK_OAK_FENCE_GATE, Blocks.DARK_OAK_PRESSURE_PLATE,
                Blocks.DARK_OAK_BUTTON, Blocks.DARK_OAK_SIGN,
                Blocks.MANGROVE_LOG, Blocks.MANGROVE_WOOD, Blocks.STRIPPED_MANGROVE_LOG,
                Blocks.STRIPPED_MANGROVE_WOOD, Blocks.MANGROVE_PLANKS, Blocks.MANGROVE_SLAB,
                Blocks.MANGROVE_STAIRS, Blocks.MANGROVE_DOOR, Blocks.MANGROVE_TRAPDOOR,
                Blocks.MANGROVE_FENCE, Blocks.MANGROVE_FENCE_GATE, Blocks.MANGROVE_PRESSURE_PLATE,
                Blocks.MANGROVE_BUTTON, Blocks.MANGROVE_SIGN,
                Blocks.CHERRY_LOG, Blocks.CHERRY_WOOD, Blocks.STRIPPED_CHERRY_LOG,
                Blocks.STRIPPED_CHERRY_WOOD, Blocks.CHERRY_PLANKS, Blocks.CHERRY_SLAB,
                Blocks.CHERRY_STAIRS, Blocks.CHERRY_DOOR, Blocks.CHERRY_TRAPDOOR,
                Blocks.CHERRY_FENCE, Blocks.CHERRY_FENCE_GATE, Blocks.CHERRY_PRESSURE_PLATE,
                Blocks.CHERRY_BUTTON, Blocks.CHERRY_SIGN,
                Blocks.BAMBOO_PLANKS, Blocks.BAMBOO_SLAB, Blocks.BAMBOO_STAIRS,
                Blocks.BAMBOO_DOOR, Blocks.BAMBOO_TRAPDOOR, Blocks.BAMBOO_FENCE,
                Blocks.BAMBOO_FENCE_GATE, Blocks.BAMBOO_PRESSURE_PLATE, Blocks.BAMBOO_BUTTON,
        }) {
            addWoodLike(b);
        }
    }

    private SalvageRecipes() {
    }

    // ==========================================================
    //  建表辅助
    // ==========================================================

    private static void add(Block block, int batch, int amount) {
        addRule(block.asItem(), Line.STONE, batch, amount);
    }

    private static void addSand(Block block, int batch, int amount) {
        addRule(block.asItem(), Line.SAND, batch, amount);
    }

    private static void addWood(Block block, int batch, int amount) {
        addRule(block.asItem(), Line.WOOD, batch, amount);
    }

    /**
     * 按方块名后缀推断木材比例（各树种配方同构，只有方种类别不同）。
     * 用于批量登记 8 个树种的同款建材——避免抄 100+ 行同样的数字。
     */
    private static void addWoodLike(Block block) {
        String n = block.builtInRegistryHolder().key().location().getPath();
        // 比例与上面橡木那段逐条对齐（1 木材 = 1 原木 = 4 木板 的基准）
        if (n.endsWith("_planks")) {
            addWood(block, 4, 1);
        } else if (n.endsWith("_slab")) {
            addWood(block, 8, 1);
        } else if (n.endsWith("_stairs")) {
            addWood(block, 8, 3);
        } else if (n.endsWith("_door")) {
            addWood(block, 2, 1);
        } else if (n.endsWith("_trapdoor")) {
            addWood(block, 4, 3);
        } else if (n.endsWith("_fence_gate")) {
            addWood(block, 1, 1);
        } else if (n.endsWith("_fence")) {
            addWood(block, 5, 2);
        } else if (n.endsWith("_pressure_plate")) {
            addWood(block, 2, 1);
        } else if (n.endsWith("_button")) {
            addWood(block, 4, 1);
        } else if (n.endsWith("_sign")) {
            addWood(block, 2, 1);
        } else if (n.endsWith("_log") || n.endsWith("_wood")) {
            addWood(block, 1, 1);
        }
        // 未登记的形态静默跳过（如悬挂牌，原版配方掺锁链，不纳入）
    }

    private static void addRule(Item item, Line line, int batch, int amount) {
        Rule rule = new Rule(item, line, batch, amount);
        Rule old = BY_ITEM.putIfAbsent(item, rule);
        if (old == null) {
            ALL.add(rule);
        }
    }

    // ==========================================================
    //  查询
    // ==========================================================

    /** 这个物品能不能拆（拆不出通货的物品返回 null）。 */
    @Nullable
    public static Rule ruleOf(ItemStack stack) {
        return stack.isEmpty() ? null : BY_ITEM.get(stack.getItem());
    }

    /** 这个物品的兑换规则（按物品查）。 */
    @Nullable
    public static Rule ruleOf(Item item) {
        return BY_ITEM.get(item);
    }

    /** 全表（只读，保序）。 */
    public static List<Rule> all() {
        return List.copyOf(ALL);
    }

    /** 表里登记了多少种物品（测试/面板用）。 */
    public static int size() {
        return ALL.size();
    }

    /**
     * 这条规则需要凑几个才算一批（拆单只不够数时，石匠会等下一样补上）。
     *
     * <p>v0.17 修正基准后，最大批次是 8（木板 4 个一批、台阶 8 个一批），
     * 玩家手上的零碎建材通常都能凑上；石材线最大是 2（台阶）。
     */
    public static int batchOf(ItemStack stack) {
        Rule r = ruleOf(stack);
        return r == null ? 1 : r.batch();
    }

    /**
     * 手上这堆能拆出几份通货 + 消耗几个（一次结算的完整换算）。
     *
     * <p>返回 null 表示拆不动（不在表内 / 数量凑不够一批）。
     * 结果里的 {@code consumed} 是实际用掉的物品数（一定是 batch 的整数倍），
     * 余数留在手上——绝不吞。
     */
    @Nullable
    public static Exchange evaluate(ItemStack stack) {
        Rule r = ruleOf(stack);
        if (r == null) {
            return null;
        }
        int batches = stack.getCount() / r.batch();
        if (batches <= 0) {
            return null;
        }
        return new Exchange(r, batches * r.batch(), batches * r.amount());
    }

    /** 一次兑换结算：消耗 {@code consumed} 个，产出 {@code output} 份该线通货。 */
    public record Exchange(Rule rule, int consumed, int output) {
        public Line line() {
            return this.rule.line();
        }
    }
}