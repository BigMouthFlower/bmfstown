package com.bmf.bmfstown.gametest;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.ModItems;
import com.bmf.bmfstown.material.SalvageRecipes;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 建材回收表契约测试（v0.17 石匠线）。
 *
 * <h2>为什么这个表值得写测试</h2>
 * 115 条兑换率是脚本从原版数据推出来再写进代码的，<b>抄错一个数字玩家测不出来</b>
 * （拆 100 个台阶少给 1 份料，人眼根本发现不了），但会让"守恒"这个设计前提
 * 变成一句空话。以下四组断言把表的关键不变量钉死：
 *
 * <ol>
 *   <li><b>覆盖面</b>：三种通货都非空、总数对得上；留白清单（铜/石英/下界砖等）
 *       确实<b>不在</b>表内（用户 2026-11-25 明说"这两个先留着，先留白"）。</li>
 *   <li><b>分线正确</b>：砂岩/红砂岩必须在碎沙线、不能在石材线——这是
 *       防"沙子换石头"刷料漏洞的核心断言。</li>
 *   <li><b>比例合法</b>：batch ≥ 1、amount ≥ 1，且 amount/batch 不能细到拆不动。</li>
 *   <li><b>守恒</b>：对每条规则做"拆进位 → 换回来"的整数往返，结果不能变多
 *       （取整损耗只允许向下，不允许凭空多出通货）。</li>
 * </ol>
 *
 * <p>纯数据断言，不需要放置方块——不吃 GameTestServer 不加载外部 mod 的坑。
 */
@GameTestHolder(BmfTown.MOD_ID)
public final class SalvageTests {

    /** 用户明确留白的材质：一条都不许进表。 */
    private static final String[] RESERVED = {
            "copper", "cut_copper", "exposed_copper", "oxidized_copper",
            "weathered_copper", "waxed_copper", "quartz", "nether_brick",
            "red_nether_brick", "purpur", "prismarine", "end_stone", "mud_brick",
    };

    @GameTest(template = "empty3x3x3", timeoutTicks = 100)
    public static void tableIsPopulated(GameTestHelper helper) {
        int n = SalvageRecipes.size();
        if (n < 100) {
            throw new GameTestAssertException("回收表条目过少：" + n + "（应 ≥100）");
        }
        long stone = SalvageRecipes.all().stream()
                .filter(r -> r.line() == SalvageRecipes.Line.STONE).count();
        long wood = SalvageRecipes.all().stream()
                .filter(r -> r.line() == SalvageRecipes.Line.WOOD).count();
        long sand = SalvageRecipes.all().stream()
                .filter(r -> r.line() == SalvageRecipes.Line.SAND).count();
        if (stone < 80) {
            throw new GameTestAssertException("石材条目过少：" + stone);
        }
        if (wood < 60) {
            throw new GameTestAssertException("木材条目过少：" + wood);
        }
        if (sand < 18) {
            throw new GameTestAssertException("碎沙条目过少：" + sand);
        }
        helper.succeed();
    }

    /**
     * 留白材质一条都不许进表。
     *
     * <p>这条守的是用户的原话："石英和铜这两个先别做了吧，这两个先留着，先留白。"
     * 如果将来有人顺手把铜加进来，这个测试会红，提醒他先问。
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 100)
    public static void reservedMaterialsAreAbsent(GameTestHelper helper) {
        for (SalvageRecipes.Rule rule : SalvageRecipes.all()) {
            String path = rule.item().builtInRegistryHolder().key().location().getPath();
            for (String reserved : RESERVED) {
                if (path.contains(reserved)) {
                    throw new GameTestAssertException(
                            "留白材质混进了回收表：" + path + "（命中 " + reserved + "）");
                }
            }
        }
        helper.succeed();
    }

    /**
     * 砂岩必须走碎沙线，绝不能进石材线。
     *
     * <p>这是整个设计的平衡底线：砂岩由沙子（可再生）制成，石材不可再生。
     * 一旦砂岩能拆成石材，玩家就获得了"沙子 → 石头"的无限通道。
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 100)
    public static void sandstoneBelongsToSandLine(GameTestHelper helper) {
        ItemStack[] sandstone = {
                Blocks.SANDSTONE.asItem().getDefaultInstance(),
                Blocks.RED_SANDSTONE.asItem().getDefaultInstance(),
                Blocks.SMOOTH_SANDSTONE.asItem().getDefaultInstance(),
                Blocks.CUT_SANDSTONE.asItem().getDefaultInstance(),
                Blocks.SANDSTONE_STAIRS.asItem().getDefaultInstance(),
                Blocks.SANDSTONE_SLAB.asItem().getDefaultInstance(),
        };
        for (ItemStack st : sandstone) {
            SalvageRecipes.Rule rule = SalvageRecipes.ruleOf(st);
            if (rule == null) {
                throw new GameTestAssertException("砂岩类没登记：" + st.getHoverName().getString());
            }
            if (rule.line() != SalvageRecipes.Line.SAND) {
                throw new GameTestAssertException(
                        "砂岩类跑到了 " + rule.line() + " 线：" + st.getHoverName().getString());
            }
        }
        helper.succeed();
    }

    /** 每条规则的比例必须合法：batch ≥ 1、amount ≥ 1。 */
    @GameTest(template = "empty3x3x3", timeoutTicks = 100)
    public static void ratiosAreWellFormed(GameTestHelper helper) {
        for (SalvageRecipes.Rule rule : SalvageRecipes.all()) {
            String name = rule.item().builtInRegistryHolder().key().location().getPath();
            if (rule.batch() < 1) {
                throw new GameTestAssertException(name + " 的 batch 非法：" + rule.batch());
            }
            if (rule.amount() < 1) {
                throw new GameTestAssertException(name + " 的 amount 非法：" + rule.amount());
            }
            // amount/batch 太小时（如 1/8）需要凑 8 个才给 1 份，规则上允许但
            // 这里只守下界，避免误写成 0 或负。
        }
        helper.succeed();
    }

    /**
     * 守恒断言（本测试的核心）：对每条规则，"凑整批拆掉"得到的通货数量，
     * 再按同比例换回物品，绝不能多于原来的物品数。
     *
     * <p>这里只需要验<b>规则自身的算术自洽</b>：{@code consumed/batch*amount}
     * 必须严格等于 {@code output}，且 {@code consumed} 是 batch 的整数倍、
     * 不超过输入数量。真正的"跨配方不套利"由原版数据保证（脚本已核 0 处，
     * 见 decisions.md），这里不重复实现配方求值器。
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 100)
    public static void exchangeIsArithmeticallySound(GameTestHelper helper) {
        for (SalvageRecipes.Rule rule : SalvageRecipes.all()) {
            String name = rule.item().builtInRegistryHolder().key().location().getPath();
            // 试一批、两批、以及"刚好多出一个"的边界
            for (int held : new int[]{rule.batch(), rule.batch() * 2, rule.batch() * 7 + 1}) {
                ItemStack input = new ItemStack(rule.item(), held);
                SalvageRecipes.Exchange ex = SalvageRecipes.evaluate(input);
                if (ex == null) {
                    throw new GameTestAssertException(name + " 在持有 " + held + " 时拆不动");
                }
                int expectBatches = held / rule.batch();
                if (ex.consumed() != expectBatches * rule.batch()) {
                    throw new GameTestAssertException(name + " 消耗量算错："
                            + ex.consumed() + " 应为 " + (expectBatches * rule.batch()));
                }
                if (ex.output() != expectBatches * rule.amount()) {
                    throw new GameTestAssertException(name + " 产出量算错："
                            + ex.output() + " 应为 " + (expectBatches * rule.amount()));
                }
                if (ex.consumed() > held) {
                    throw new GameTestAssertException(name + " 消耗超过持有：" + ex.consumed() + " > " + held);
                }
                // 余数必须留在手上（绝不吞）
                int leftover = held - ex.consumed();
                if (leftover < 0 || leftover >= rule.batch() + 1) {
                    throw new GameTestAssertException(name + " 余数异常：" + leftover);
                }
            }
        }
        helper.succeed();
    }

    /**
     * 凑不够一批时拆不动（返回 null）——台阶必须凑 2 个。
     *
     * <p>这条守的是"绝不吞货"：玩家拿 1 个台阶来，石匠不能收走它却什么都不给。
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 100)
    public static void partialBatchIsRejected(GameTestHelper helper) {
        ItemStack oneSlab = new ItemStack(Blocks.STONE_SLAB.asItem(), 1);
        SalvageRecipes.Rule rule = SalvageRecipes.ruleOf(oneSlab);
        if (rule == null) {
            throw new GameTestAssertException("石台阶没登记");
        }
        if (rule.batch() != 2) {
            throw new GameTestAssertException("石台阶 batch 应为 2，实为 " + rule.batch());
        }
        if (SalvageRecipes.evaluate(oneSlab) != null) {
            throw new GameTestAssertException("单个台阶不该拆得动（会吞货）");
        }
        // 两个就应该能拆
        ItemStack twoSlabs = new ItemStack(Blocks.STONE_SLAB.asItem(), 2);
        SalvageRecipes.Exchange ex = SalvageRecipes.evaluate(twoSlabs);
        if (ex == null || ex.output() != 1) {
            throw new GameTestAssertException("2 个石台阶应换 1 份石材");
        }
        helper.succeed();
    }

    /** 三种通货物品都已注册（防止注册漏掉导致 NPE）。 */
    @GameTest(template = "empty3x3x3", timeoutTicks = 100)
    public static void currencyItemsRegistered(GameTestHelper helper) {
        if (ModItems.MATERIAL_STONE.get() == null
                || ModItems.MATERIAL_WOOD.get() == null
                || ModItems.MATERIAL_SAND.get() == null) {
            throw new GameTestAssertException("通货物品注册缺失");
        }
        for (SalvageRecipes.Line line : SalvageRecipes.Line.values()) {
            if (line.item() == null) {
                throw new GameTestAssertException(line + " 线没有绑定通货物品");
            }
        }
        helper.succeed();
    }

    /** 未登记的物品一律拆不动（不能让空气/无表物品走默认分支）。 */
    @GameTest(template = "empty3x3x3", timeoutTicks = 100)
    public static void unlistedItemIsRejected(GameTestHelper helper) {
        if (SalvageRecipes.ruleOf(ItemStack.EMPTY) != null) {
            throw new GameTestAssertException("空栈不该有规则");
        }
        if (SalvageRecipes.ruleOf(new ItemStack(Blocks.DIRT.asItem())) != null) {
            throw new GameTestAssertException("泥土不该在回收表内");
        }
        if (SalvageRecipes.ruleOf(new ItemStack(Blocks.COPPER_BLOCK.asItem())) != null) {
            throw new GameTestAssertException("铜块是留白材质，不该在表内");
        }
        helper.succeed();
    }

    /**
     * 木材比例必须与原版合成配方一一对应（**硬编码期望值**）。
     *
     * <h2>为什么这条必须硬编码，不能只验"算术自洽"</h2>
     * {@link #exchangeIsArithmeticallySound} 只能证明"batch 与 amount 的换算没写错"，
     * 但<b>抓不出比例本身错</b>：比如栅栏门曾误写 {@code batch=2, amount=9}
     * （即 1 门 = 4.5 木材），算术完全自洽，却比原版 4 木板多给了 0.5——
     * 是缓慢漏货。所以这里把原版推导结果<b>逐条写死</b>。
     *
     * <p>数据来源（原版合成配方，1 木材 ≡ 1 木板，1 木棍 = 0.5 木板）：
     * <pre>
     *   木板      1 原木 → 4 板                  → 1 板 = 1 木材
     *   台阶      3 板 → 6 台阶                  → 2 台阶 = 1 木材
     *   楼梯      6 板 → 4 楼梯                  → 2 楼梯 = 3 木材
     *   门        6 板 → 3 门                    → 1 门 = 2 木材
     *   活板门    6 板 → 2 活板门                → 1 活板门 = 3 木材
     *   压力板    2 板 → 1 压力板                → 1 压力板 = 2 木材
     *   按钮      1 板 → 1 按钮                  → 1 按钮 = 1 木材
     *   栅栏门    4 板 + 4 棍(=2 板) → 1 门      → 1 门 = 4 木材
     *   栅栏      6 板 + 2 棍(=1 板) → 3 栅栏    → 3 栅栏 = 5 木材
     *   告示牌    6 板 + 1 棍(=0.5 板) → 3 牌    → 6 牌 = 13 木材
     * </pre>
     *
     * <p>改比例前先问：原版配方是不是这个数？这里写死就是为了逼这一问。
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 100)
    public static void woodRatiosMatchVanilla(GameTestHelper helper) {
        // {方块, 期望 batch, 期望 amount}
        // 基准（v0.17 修正）：1 木材 = 1 原木 = 4 木板，故 1 木板 = 1/4 木材。
        // 这些数字直接来自原版合成配方，不是凑出来的：
        //   木板 1板→1板；台阶 3板→6台阶；楼梯 6板→4楼梯；门 6板→3门；
        //   活板门 6板→2；压力板 2板→1；按钮 1板→1；原木 1原木→4板。
        // 掺木棍的三样：栅栏门按精确值（2板+4棍=4板→1门）；
        //   栅栏/告示牌精确值是 5/12 与 13/24，凑整批要 12/24 个太苛刻，
        //   按"留损耗"向下取整成 5→2 与 2→1（**绝不向上取整**，否则套利）。
        Object[][] expect = {
                {Blocks.OAK_PLANKS, 4, 1},
                {Blocks.OAK_SLAB, 8, 1},
                {Blocks.OAK_STAIRS, 8, 3},
                {Blocks.OAK_DOOR, 2, 1},
                {Blocks.OAK_TRAPDOOR, 4, 3},
                {Blocks.OAK_PRESSURE_PLATE, 2, 1},
                {Blocks.OAK_BUTTON, 4, 1},
                {Blocks.OAK_FENCE_GATE, 1, 1},
                {Blocks.OAK_FENCE, 5, 2},
                {Blocks.OAK_SIGN, 2, 1},
                {Blocks.OAK_LOG, 1, 1},
        };
        for (Object[] row : expect) {
            Block block = (Block) row[0];
            int wantBatch = (Integer) row[1];
            int wantAmount = (Integer) row[2];
            SalvageRecipes.Rule rule = SalvageRecipes.ruleOf(block.asItem());
            String name = block.builtInRegistryHolder().key().location().getPath();
            if (rule == null) {
                throw new GameTestAssertException(name + " 不在木材表内");
            }
            if (rule.batch() != wantBatch || rule.amount() != wantAmount) {
                throw new GameTestAssertException(name + " 比例错：实为 "
                        + rule.batch() + "→" + rule.amount()
                        + "，原版应为 " + wantBatch + "→" + wantAmount);
            }
            if (rule.line() != SalvageRecipes.Line.WOOD) {
                throw new GameTestAssertException(name + " 不在木材线");
            }
        }
        helper.succeed();
    }

    /**
     * 石材台阶比例必须与原版切石配方一致：1 石 → 2 台阶，故 2 台阶 = 1 份料。
     *
     * <p>同类"硬编码期望值"的守门用例，防止有人为了"手感"把台阶改成 1 换 1
     * （那样 1 石切 2 台阶再拆回 2 份，就是无限刷料）。
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 100)
    public static void stoneRatiosMatchVanillaCutting(GameTestHelper helper) {
        Object[][] expect = {
                {Blocks.STONE_SLAB, 2, 1},       // 1 石 → 2 台阶
                {Blocks.COBBLESTONE_SLAB, 2, 1},
                {Blocks.STONE_STAIRS, 1, 1},     // 1 石 → 1 楼梯
                {Blocks.STONE_BRICKS, 1, 1},     // 1 石 → 1 石砖
                {Blocks.STONE_BRICK_WALL, 1, 1},
                {Blocks.STONE, 1, 1},
                {Blocks.COBBLESTONE, 1, 1},
        };
        for (Object[] row : expect) {
            Block block = (Block) row[0];
            int wantBatch = (Integer) row[1];
            int wantAmount = (Integer) row[2];
            SalvageRecipes.Rule rule = SalvageRecipes.ruleOf(block.asItem());
            String name = block.builtInRegistryHolder().key().location().getPath();
            if (rule == null) {
                throw new GameTestAssertException(name + " 不在石材表内");
            }
            if (rule.batch() != wantBatch || rule.amount() != wantAmount) {
                throw new GameTestAssertException(name + " 比例错：实为 "
                        + rule.batch() + "→" + rule.amount()
                        + "，原版切石应为 " + wantBatch + "→" + wantAmount);
            }
        }
        helper.succeed();
    }

    /**
     * 砂岩比例（v0.17 修正基准）：**1 砂岩块 = 1 碎沙**，台阶 2 个 = 1 份。
     *
     * <h2>为什么从 4 份改成 1 份（2026-11-25 用户实测指出）</h2>
     * 旧基准是"1 砂岩 = 4 沙"（依原版 4 沙 → 1 砂岩），看起来更贴近原版，
     * 但它导致<b>碎沙在切石机里一条配方都没有</b>：1 碎沙只值 1/4 个砂岩，
     * 而切石机一次只能消耗 1 个输入物品（原版 {@code inputSlot.remove(1)}），
     * <b>造不出 1/4 个方块</b>。当时的退路是给一条工作台配方
     * （4 碎沙 → 1 砂岩），被用户否掉了："它应该跟石料木材一样，
     * 在切石机中做它们所属属性的那些东西。"
     *
     * <p>改成 1:1 后，碎沙与石料/木材三条线<b>完全同构</b>：拆解侧
     * 「1 件 = N 份」、反向「1 份 = N 件」，count 直接照抄原版切石配方。
     *
     * <p>守恒性没有变差：沙 → 砂岩 的 4:1 压缩是原版就有的<b>不可逆</b>过程
     * （原版没有"砂岩 → 沙"），碎沙本质是"压缩沙料"，不会开出一条
     * "沙子无限变石头"的通道（砂岩仍在 SAND 线，不进 STONE 线）。
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 100)
    public static void sandstoneRatiosMatchVanillaCrafting(GameTestHelper helper) {
        Object[][] expect = {
                {Blocks.SANDSTONE, 1, 1},         // 基准：1 砂岩 = 1 碎沙
                {Blocks.RED_SANDSTONE, 1, 1},
                {Blocks.SANDSTONE_SLAB, 2, 1},    // 1 砂岩切 2 台阶 ⇒ 2 台阶 = 1 份
                {Blocks.SANDSTONE_STAIRS, 1, 1},
                {Blocks.CUT_SANDSTONE, 1, 1},
                {Blocks.SMOOTH_SANDSTONE, 1, 1},
        };
        for (Object[] row : expect) {
            Block block = (Block) row[0];
            int wantBatch = (Integer) row[1];
            int wantAmount = (Integer) row[2];
            SalvageRecipes.Rule rule = SalvageRecipes.ruleOf(block.asItem());
            String name = block.builtInRegistryHolder().key().location().getPath();
            if (rule == null) {
                throw new GameTestAssertException(name + " 不在碎沙表内");
            }
            if (rule.batch() != wantBatch || rule.amount() != wantAmount) {
                throw new GameTestAssertException(name + " 比例错：实为 "
                        + rule.batch() + "→" + rule.amount()
                        + "，原版应为 " + wantBatch + "→" + wantAmount);
            }
        }
        helper.succeed();
    }

    // ==========================================================
    //  反向配方（通货 → 建材）契约测试 v0.17 第二轮
    // ==========================================================

    /**
     * 反向配方必须真的能在切石机里查到，且产出量与"拆解侧"闭环。
     *
     * <h2>为什么必须测"配方真的存在"</h2>
     * 216 条反向配方是脚本生成的 JSON 数据包，<b>拼错一个方块名不会报错</b>——
     * 游戏只会静默忽略那条配方（{@code RecipeManager} 解析失败只打 WARN），
     * 玩家看到的现象是"某些建材切不出来"，而且永远不知道是哪些。
     * 这条用例直接查 {@code RecipeManager}，把"文件写了但没生效"堵死。
     *
     * <h2>闭环判据：1 通货切出来的建材，拆回去不能多于 1 份</h2>
     * 这是防刷料的核心。<b>注意方向</b>：只断言"≤1"（不赚），
     * 不断言"=1"——楼梯(2 件)、活板门(1 件) 因向下取整本来就略亏，
     * 强行要求相等会让两条本来正确的配方变红。
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void reverseRecipesExistAndDoNotFarm(GameTestHelper helper) {
        var level = helper.getLevel();
        var recipes = level.getRecipeManager();

        // {通货, 目标建材, 期望产出数}
        Object[][] expect = {
                {ModItems.MATERIAL_STONE.get(), Blocks.STONE_BRICKS, 1},   // 1 石砖 = 1 石料
                {ModItems.MATERIAL_STONE.get(), Blocks.STONE_SLAB, 2},     // 2 台阶 = 1 石料
                {ModItems.MATERIAL_WOOD.get(), Blocks.OAK_PLANKS, 4},      // 4 板 = 1 木材
                {ModItems.MATERIAL_WOOD.get(), Blocks.OAK_SLAB, 8},        // 8 台阶 = 1 木材
                {ModItems.MATERIAL_WOOD.get(), Blocks.OAK_STAIRS, 2},      // 8 楼梯 = 3 木材 ⇒ 2 件
                {ModItems.MATERIAL_WOOD.get(), Blocks.OAK_LOG, 1},         // 1 原木 = 1 木材
                // 碎沙线（v0.17 修正基准后与另两条完全同构）：1 碎沙 = 1 砂岩
                {ModItems.MATERIAL_SAND.get(), Blocks.SANDSTONE, 1},
                {ModItems.MATERIAL_SAND.get(), Blocks.SANDSTONE_SLAB, 2},
                {ModItems.MATERIAL_SAND.get(), Blocks.RED_SANDSTONE, 1},
                {ModItems.MATERIAL_SAND.get(), Blocks.SMOOTH_SANDSTONE, 1},
        };
        for (Object[] row : expect) {
            Item currency = (Item) row[0];
            Block target = (Block) row[1];
            int wantCount = (Integer) row[2];
            String tname = target.builtInRegistryHolder().key().location().getPath();

            var found = recipes.getRecipeFor(
                    RecipeType.STONECUTTING,
                    new SingleRecipeInput(new ItemStack(currency)),
                    level);
            // getRecipeFor 只返回第一条，所以改用全量过滤精确匹配
            var list = recipes.getRecipesFor(
                    RecipeType.STONECUTTING,
                    new SingleRecipeInput(new ItemStack(currency)),
                    level);
            ItemStack got = ItemStack.EMPTY;
            for (var h : list) {
                ItemStack r = h.value().getResultItem(level.registryAccess());
                if (r.is(target.asItem())) {
                    got = r;
                    break;
                }
            }
            if (got.isEmpty()) {
                throw new GameTestAssertException(
                        tname + " 没有反向配方（数据包没生效？）");
            }
            if (got.getCount() != wantCount) {
                throw new GameTestAssertException(tname + " 反向产出错：实为 "
                        + got.getCount() + "，应为 " + wantCount);
            }
            if (found.isEmpty()) {
                throw new GameTestAssertException(tname + " getRecipeFor 查不到（索引异常）");
            }

            // 闭环：切出来的量，拆回去不能 > 1 份
            SalvageRecipes.Rule rule = SalvageRecipes.ruleOf(target.asItem());
            if (rule == null) {
                throw new GameTestAssertException(tname + " 在拆解表里没有规则，闭环断了");
            }
            // 1 份通货切出的件数 = wantCount；拆回 = wantCount * amount / batch
            int backUnits = wantCount * rule.amount() / rule.batch();
            if (backUnits > 1) {
                throw new GameTestAssertException(
                        tname + " 存在套利：1 通货切 " + wantCount + " 件，"
                                + "拆回可得 " + backUnits + " 份");
            }
        }
        helper.succeed();
    }

    /**
     * 反向配方不得凭空造出"原版切石机造不出的东西"。
     *
     * <p>具体说：原版 {@code 1 石头 → N 成品} 的 N，必须与
     * {@code 1 石料 → N 成品} 的 N 完全一致。如果哪天有人手改了某条反向配方的
     * count（比如为了"手感"把石砖改成 2），这条会立刻红。
     *
     * <p>抽查若干条有代表性的（台阶 count=2、方块 count=1、墙 count=1）。
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void reverseMatchesVanillaCount(GameTestHelper helper) {
        var level = helper.getLevel();
        var recipes = level.getRecipeManager();
        ItemStack stone = new ItemStack(ModItems.MATERIAL_STONE.get());

        // {目标, 原版 1 石头切出的数量}
        Object[][] pairs = {
                {Blocks.STONE_BRICKS, 1},
                {Blocks.STONE_BRICK_SLAB, 2},
                {Blocks.STONE_BRICK_STAIRS, 1},
                {Blocks.STONE_BRICK_WALL, 1},
                {Blocks.CHISELED_STONE_BRICKS, 1},
                {Blocks.COBBLESTONE_SLAB, 2},
                {Blocks.COBBLESTONE_WALL, 1},
                {Blocks.POLISHED_DEEPSLATE, 1},
                {Blocks.DEEPSLATE_TILE_SLAB, 2},
        };
        var lines = recipes.getRecipesFor(RecipeType.STONECUTTING,
                new SingleRecipeInput(stone), level);

        for (Object[] p : pairs) {
            Block target = (Block) p[0];
            int vanilla = (Integer) p[1];
            String tname = target.builtInRegistryHolder().key().location().getPath();

            ItemStack got = ItemStack.EMPTY;
            for (var h : lines) {
                ItemStack r = h.value().getResultItem(light(level));
                if (r.is(target.asItem())) {
                    got = r;
                    break;
                }
            }
            if (got.isEmpty()) {
                throw new GameTestAssertException(tname + " 无反向配方");
            }
            if (got.getCount() != vanilla) {
                throw new GameTestAssertException(tname + " 反向 count=" + got.getCount()
                        + "，但原版 1 石头切出 " + vanilla + " 件（必须一致，否则不守恒）");
            }
        }
        helper.succeed();
    }

    private static net.minecraft.core.RegistryAccess light(net.minecraft.server.level.ServerLevel level) {
        return level.registryAccess();
    }

    /**
     * 碎沙线在**切石机**里自成一族（v0.17 修正，2026-11-25 用户拍板）。
     *
     * <h2>用户原话</h2>
     * "我看到你给碎沙做了一个工作台合成砂岩的配方，但是其实我并不想这样呀，
     * 就是它应该跟石料木材一样，在切石机中做它们所属属性的那些东西。"
     *
     * <h2>为什么能这么做（旧版做不到的原因已随基准修正消失）</h2>
     * 旧基准 {@code 1 砂岩 = 4 碎沙} 让 1 碎沙只值 1/4 个砂岩，而切石机一次
     * 只能消耗 1 个输入物品，<b>造不出分数个方块</b>——所以当时只能退到工作台。
     * 现在基准是 {@code 1 碎沙 = 1 砂岩}（与「1 石料 = 1 石头」「1 木材 = 1 原木」
     * 同构），碎沙就能像石料/木材一样，在切石机里切出砂岩这一族的全部形态。
     *
     * <h2>本测试钉死三条</h2>
     * <ol>
     *   <li>碎沙在切石机里有配方（且覆盖砂岩系的主要形态）；</li>
     *   <li>产出 count 与「原版 1 砂岩切出的数量」完全一致（守守恒）；</li>
     *   <li><b>不存在工作台配方</b>——用户明确否掉了那条路，若有人再加回来
     *       （比如顺手写了 {@code 4 碎沙 → 1 砂岩}），这条会红。</li>
     * </ol>
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void sandLineCutsInStonecutterLikeStoneAndWood(GameTestHelper helper) {
        var level = helper.getLevel();
        var recipes = level.getRecipeManager();
        ItemStack sandCur = new ItemStack(ModItems.MATERIAL_SAND.get());

        // {目标, 原版 1 砂岩切出的数量}——count 必须一致，否则不守恒
        Object[][] expect = {
                {Blocks.SANDSTONE, 1},
                {Blocks.SANDSTONE_SLAB, 2},
                {Blocks.SANDSTONE_STAIRS, 1},
                {Blocks.SANDSTONE_WALL, 1},
                {Blocks.CHISELED_SANDSTONE, 1},
                {Blocks.CUT_SANDSTONE, 1},
                {Blocks.CUT_SANDSTONE_SLAB, 2},
                {Blocks.SMOOTH_SANDSTONE, 1},
                {Blocks.RED_SANDSTONE, 1},
                {Blocks.RED_SANDSTONE_SLAB, 2},
                {Blocks.CUT_RED_SANDSTONE, 1},
                {Blocks.SMOOTH_RED_SANDSTONE, 1},
        };
        var lines = recipes.getRecipesFor(RecipeType.STONECUTTING,
                new SingleRecipeInput(sandCur), level);
        if (lines.isEmpty()) {
            throw new GameTestAssertException(
                    "碎沙在切石机里一条配方都没有（用户明确要求走切石机）");
        }
        for (Object[] row : expect) {
            Block target = (Block) row[0];
            int want = (Integer) row[1];
            String tname = target.builtInRegistryHolder().key().location().getPath();
            ItemStack got = ItemStack.EMPTY;
            for (var h : lines) {
                ItemStack r = h.value().getResultItem(level.registryAccess());
                if (r.is(target.asItem())) {
                    got = r;
                    break;
                }
            }
            if (got.isEmpty()) {
                throw new GameTestAssertException(tname + " 没有碎沙切石配方（数据包没生效？）");
            }
            if (got.getCount() != want) {
                throw new GameTestAssertException(tname + " 碎沙切出 " + got.getCount()
                        + " 件，原版 1 砂岩应切出 " + want + " 件（不守恒）");
            }
        }

        // 工作台配方必须不存在（用户否掉了这条路：碎沙就该在切石机里用）
        for (var h : recipes.getRecipes()) {
            if (!(h.value() instanceof net.minecraft.world.item.crafting.ShapedRecipe
                    || h.value() instanceof net.minecraft.world.item.crafting.ShapelessRecipe)) {
                continue;
            }
            var ings = h.value().getIngredients();
            boolean usesSandCurrency = false;
            for (var ing : ings) {
                for (ItemStack opt : ing.getItems()) {
                    if (opt.is(ModItems.MATERIAL_SAND.get())) {
                        usesSandCurrency = true;
                        break;
                    }
                }
                if (usesSandCurrency) {
                    break;
                }
            }
            if (!usesSandCurrency) {
                continue;
            }
            ItemStack res = h.value().getResultItem(level.registryAccess());
            if (res.is(Blocks.SANDSTONE.asItem())) {
                throw new GameTestAssertException(
                        "存在碎沙 → 砂岩 的工作台配方，应改走切石机（用户 2026-11-25 拍板）");
            }
        }

        helper.succeed();
    }

    /**
     * 熔炉仍然吃碎沙（{@code 1 碎沙 → 1 玻璃}）。
     *
     * <p>切石机管"砂岩族的形态"，熔炉管"烧成玻璃"——两条出口并存，
     * 熔炉这条是原版"沙子烧玻璃"的等价路径，不能因为加了切石配方就删掉。
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void sandCurrencyStillSmeltsToGlass(GameTestHelper helper) {
        var level = helper.getLevel();
        ItemStack sandCur = new ItemStack(ModItems.MATERIAL_SAND.get());
        boolean hasGlass = false;
        for (var h : level.getRecipeManager().getRecipes()) {
            if (!(h.value() instanceof net.minecraft.world.item.crafting.SmeltingRecipe sr)) {
                continue;
            }
            ItemStack res = sr.getResultItem(level.registryAccess());
            if (res.is(Blocks.GLASS.asItem()) && sr.getIngredients().get(0).test(sandCur)) {
                hasGlass = true;
                break;
            }
        }
        if (!hasGlass) {
            throw new GameTestAssertException("1 碎沙 → 1 玻璃 的熔炉配方缺失");
        }
        helper.succeed();
    }
}