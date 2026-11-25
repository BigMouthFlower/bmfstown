package com.bmf.bmfstown.gametest;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.ModItems;
import com.bmf.bmfstown.material.SalvageRecipes;
import com.bmf.bmfstown.material.TownStock;
import com.bmf.bmfstown.town.Town;
import com.bmf.bmfstown.town.TownSaveData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;

import java.util.UUID;

/**
 * 镇库存取用契约测试（v0.17 石匠线）。
 *
 * <h2>测什么</h2>
 * {@link TownStock} 是"切石机吃镇库存"这条链的唯一取料口。它有三个
 * 必须钉死的性质，错一个都会毁掉经济：
 *
 * <ol>
 *   <li><b>只取通货</b>——箱子里的普通建材（石头、木板）绝不能被顺走，
 *       否则玩家切个石头，箱子里存的原木就没了；</li>
 *   <li><b>严格 1:1 扣减</b>——取走 1 个，箱子就少 1 个（不能"读到就算"）；</li>
 *   <li><b>镇外不取</b>——切石机不在城镇框内时一律取不到（用户要求
 *       "位于城镇内部"才生效）。</li>
 * </ol>
 *
 * <h2>踩坑提醒（写在测试里防后人）</h2>
 * 直接 {@code new Town} 必须 {@code TownSaveData.get(level).add(town)}，
 * 否则 {@code allTowns()} 扫不到它——那时"取不到料"会伪装成测试通过
 * （假绿），本测试特意在取料前断言城镇已登记（pitfalls 有记载）。
 */
@GameTestHolder(BmfTown.MOD_ID)
public final class TownStockTests {

    /** 一块空地：柱状三格，放箱子用。 */
    private static final BlockPos CHEST = new BlockPos(1, 1, 1);
    private static final BlockPos CUTTER = new BlockPos(2, 1, 1);

    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void drawsCurrencyFromRegisteredContainer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town town = makeTown(helper, level, helper.absolutePos(CHEST));
        // 登记容器：放一个箱子
        helper.setBlock(CHEST, Blocks.CHEST);
        BlockPos chestAbs = helper.absolutePos(CHEST);
        town.workshop().containers.add(chestAbs.asLong());
        // 往里塞 5 个石材 + 3 个石头（石头不该被取）
        Container c = level.getBlockEntity(chestAbs) instanceof Container cc ? cc : null;
        if (c == null) {
            throw new GameTestAssertException("箱子没建成容器");
        }
        c.setItem(0, new ItemStack(ModItems.MATERIAL_STONE.get(), 5));
        c.setItem(1, new ItemStack(Blocks.STONE.asItem(), 3));
        c.setChanged();

        // 切石机也放上（切石机本身不是容器，但取料判定要看方块）
        helper.setBlock(CUTTER, Blocks.STONECUTTER);
        BlockPos cutterAbs = helper.absolutePos(CUTTER);

        ItemStack got = TownStock.withdrawForStonecutter(level, cutterAbs);
        if (got.isEmpty()) {
            throw new GameTestAssertException("没取到通货（城镇/容器登记是否生效？）");
        }
        if (!got.is(ModItems.MATERIAL_STONE.get())) {
            throw new GameTestAssertException("取错了东西：" + got.getHoverName().getString());
        }
        if (got.getCount() != 1) {
            throw new GameTestAssertException("一次应只取 1 个，实取 " + got.getCount());
        }
        // 扣减必须真实发生
        if (!c.getItem(0).is(ModItems.MATERIAL_STONE.get()) || c.getItem(0).getCount() != 4) {
            throw new GameTestAssertException("扣减不对：剩 "
                    + c.getItem(0).getCount() + "（应为 4）");
        }
        // 普通建材一个都不能少
        if (c.getItem(1).getCount() != 3) {
            throw new GameTestAssertException("普通建材被误取：" + c.getItem(1).getCount());
        }
        helper.succeed();
    }

    /** 箱子里只有普通建材时，取不到任何东西（不能拿原木去切）。 */
    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void ignoresNonCurrencyItems(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town town = makeTown(helper, level, helper.absolutePos(CHEST));
        helper.setBlock(CHEST, Blocks.CHEST);
        BlockPos chestAbs = helper.absolutePos(CHEST);
        town.workshop().containers.add(chestAbs.asLong());
        Container c = level.getBlockEntity(chestAbs) instanceof Container cc ? cc : null;
        if (c == null) {
            throw new GameTestAssertException("箱子没建成容器");
        }
        c.setItem(0, new ItemStack(Blocks.OAK_PLANKS.asItem(), 64));
        c.setItem(1, new ItemStack(Items.DIAMOND, 64));
        c.setChanged();

        helper.setBlock(CUTTER, Blocks.STONECUTTER);
        ItemStack got = TownStock.withdrawForStonecutter(level, helper.absolutePos(CUTTER));
        if (!got.isEmpty()) {
            throw new GameTestAssertException("非通货被取走了：" + got.getHoverName().getString());
        }
        // 箱子内容原样
        if (c.getItem(0).getCount() != 64 || c.getItem(1).getCount() != 64) {
            throw new GameTestAssertException("箱子里的东西被动了");
        }
        helper.succeed();
    }

    /** 切石机在城镇框外 → 一个都取不到（用户要求"位于城镇内部"才生效）。 */
    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void doesNotDrawOutsideTown(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 造一个只覆盖箱子、不含切石机的小镇
        BlockPos chestAbs = helper.absolutePos(CHEST);
        Town town = new Town(UUID.randomUUID(), "测试镇", "tester",
                level.dimension().location().toString(),
                chestAbs, chestAbs);
        TownSaveData.get(level).add(town); // 必须登记，否则 allTowns 扫不到
        helper.setBlock(CHEST, Blocks.CHEST);
        town.workshop().containers.add(chestAbs.asLong());
        Container c = level.getBlockEntity(chestAbs) instanceof Container cc ? cc : null;
        if (c == null) {
            throw new GameTestAssertException("箱子没建成容器");
        }
        c.setItem(0, new ItemStack(ModItems.MATERIAL_WOOD.get(), 8));
        c.setChanged();

        // 切石机放在镇框外的远角（empty3x3x3 里 6,1,6 也在结构内但与镇框无关）
        BlockPos far = new BlockPos(6, 1, 6);
        helper.setBlock(far, Blocks.STONECUTTER);
        ItemStack got = TownStock.withdrawForStonecutter(level, helper.absolutePos(far));
        if (!got.isEmpty()) {
            throw new GameTestAssertException("镇外切石机居然取到了料：" + got.getHoverName().getString());
        }
        if (c.getItem(0).getCount() != 8) {
            throw new GameTestAssertException("镇外取料却动了箱子");
        }
        helper.succeed();
    }

    /** 三种通货都认得（防止只登记了一种）。 */
    @GameTest(template = "empty3x3x3", timeoutTicks = 100)
    public static void recognizesAllThreeCurrencies(GameTestHelper helper) {
        if (!TownStock.isCurrency(ModItems.MATERIAL_STONE.get())) {
            throw new GameTestAssertException("石材不算通货");
        }
        if (!TownStock.isCurrency(ModItems.MATERIAL_WOOD.get())) {
            throw new GameTestAssertException("木材不算通货");
        }
        if (!TownStock.isCurrency(ModItems.MATERIAL_SAND.get())) {
            throw new GameTestAssertException("碎沙不算通货");
        }
        if (TownStock.isCurrency(Blocks.STONE.asItem())) {
            throw new GameTestAssertException("普通石头被当成通货了");
        }
        if (TownStock.isCurrency(Items.DIAMOND)) {
            throw new GameTestAssertException("钻石被当成通货了");
        }
        helper.succeed();
    }

    /** 每条线都真的绑到了注册物品上。 */
    @GameTest(template = "empty3x3x3", timeoutTicks = 100)
    public static void linesAreBound(GameTestHelper helper) {
        for (SalvageRecipes.Line line : SalvageRecipes.Line.values()) {
            ItemStack st = line.stack(1);
            if (st.isEmpty()) {
                throw new GameTestAssertException(line + " 线拿不出物品堆");
            }
            if (!TownStock.isCurrency(st.getItem())) {
                throw new GameTestAssertException(line + " 线绑的物品不是通货");
            }
        }
        if (SalvageRecipes.Line.STONE.item() == SalvageRecipes.Line.WOOD.item()) {
            throw new GameTestAssertException("石材与木材绑了同一个物品");
        }
        helper.succeed();
    }

    // ---- 辅助 ----

    /** 造一个覆盖整个测试结构的小镇并登记（不登记就是假绿，见类注释）。 */
    private static Town makeTown(GameTestHelper helper, ServerLevel level, BlockPos anchor) {
        BlockPos min = helper.absolutePos(new BlockPos(0, 0, 0));
        BlockPos max = helper.absolutePos(new BlockPos(7, 4, 7));
        Town town = new Town(UUID.randomUUID(), "测试镇", "tester",
                level.dimension().location().toString(), min, max);
        TownSaveData.get(level).add(town);
        return town;
    }
}