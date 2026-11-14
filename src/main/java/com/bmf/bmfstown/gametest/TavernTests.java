package com.bmf.bmfstown.gametest;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.ModEntities;
import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.entity.profile.ProfessionType;
import com.bmf.bmfstown.town.TownWandMode;
import com.bmf.bmfstown.town.TownZone;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.BeehiveBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;

import java.util.UUID;

/**
 * 酒馆第一步（决策 #63）：TAVERN 划区 + 区内容器为酒窖 + 区内牛/蜂箱为资源，
 * 酒保采集入库（挤奶→奶桶、采蜜→蜂蜜瓶，空桶/空瓶从酒窖取）。
 * <p>复用 {@link AlchemyScaffold#prepareTown} 搭镇（其箱子即酒窖，容器池与酒馆无关——
 * 酒保只认 TAVERN 区内的容器方块，不认工坊登记池）。
 */
@GameTestHolder(BmfTown.MOD_ID)
public class TavernTests {

    /** 端到端：酒窖放桶+瓶 → 区内放牛+满蜜蜂箱 → 酒保产出奶桶+蜂蜜瓶入窖。 */
    @GameTest(template = "room11x4x11", timeoutTicks = 20000)
    public static void tavernkeeperCollectsMilkAndHoney(GameTestHelper helper) {
        AlchemyScaffold.Setup s = AlchemyScaffold.prepareTown(
                helper, new BlockPos(1, 1, 1), new BlockPos(11, 1, 9), 0);
        // 划酒馆区（覆盖全结构，箱子=酒窖，牛/蜂箱都在区内）
        s.town().addZone(TownWandMode.TAVERN, new TownZone(UUID.randomUUID(), "测试酒馆",
                helper.absolutePos(new BlockPos(1, 1, 1)),
                helper.absolutePos(new BlockPos(11, 1, 9))));
        // 酒窖备工具：空桶 ×1、空瓶 ×2（瓶备两支防第一支循环卡住）
        var chest = s.chest(helper);
        chest.setItem(0, new ItemStack(Items.BUCKET));
        chest.setItem(1, new ItemStack(Items.GLASS_BOTTLE));
        chest.setItem(2, new ItemStack(Items.GLASS_BOTTLE));
        chest.setChanged();
        // 资源：一头牛 + 一只满蜜级蜂箱（区内 y=1 地面上）
        helper.spawn(EntityType.COW, new BlockPos(6, 1, 5));
        helper.setBlock(new BlockPos(9, 1, 4), Blocks.BEEHIVE.defaultBlockState()
                .setValue(BeehiveBlock.HONEY_LEVEL, 5));
        // 酒保入职
        Resident keeper = helper.spawn(ModEntities.RESIDENT.get(), new BlockPos(4, 1, 5));
        keeper.getIdentity().setProfession(ProfessionType.TAVERNKEEP);
        keeper.getIdentity().setTownId(s.town().id());
        keeper.syncClient();
        s.residents().add(keeper);

        helper.succeedWhen(() -> {
            ChestBlockEntity c = s.chest(helper);
            if (c == null) {
                throw new GameTestAssertException("酒窖容器丢失");
            }
            int milk = 0;
            int honey = 0;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack st = c.getItem(i);
                if (st.is(Items.MILK_BUCKET)) {
                    milk += st.getCount();
                }
                if (st.is(Items.HONEY_BOTTLE)) {
                    honey += st.getCount();
                }
            }
            if (milk < 1) {
                throw new GameTestAssertException("等待酒保挤奶入库（奶桶 ≥1）");
            }
            if (honey < 1) {
                throw new GameTestAssertException("等待酒保采蜜入库（蜂蜜瓶 ≥1）");
            }
        });
    }

    /** 奶牛日限：挤过一次后当天不再产奶（桶只剩一支、断言窖里奶桶恒为 1）。 */
    @GameTest(template = "room11x4x11", timeoutTicks = 12000)
    public static void cowMilkOncePerDay(GameTestHelper helper) {
        AlchemyScaffold.Setup s = AlchemyScaffold.prepareTown(
                helper, new BlockPos(1, 1, 1), new BlockPos(11, 1, 9), 0);
        s.town().addZone(TownWandMode.TAVERN, new TownZone(UUID.randomUUID(), "测试酒馆",
                helper.absolutePos(new BlockPos(1, 1, 1)),
                helper.absolutePos(new BlockPos(11, 1, 9))));
        var chest = s.chest(helper);
        chest.setItem(0, new ItemStack(Items.BUCKET));
        chest.setItem(1, new ItemStack(Items.BUCKET));
        chest.setItem(2, new ItemStack(Items.BUCKET));
        chest.setChanged();
        helper.spawn(EntityType.COW, new BlockPos(6, 1, 5));
        Resident keeper = helper.spawn(ModEntities.RESIDENT.get(), new BlockPos(4, 1, 5));
        keeper.getIdentity().setProfession(ProfessionType.TAVERNKEEP);
        keeper.getIdentity().setTownId(s.town().id());
        keeper.syncClient();
        s.residents().add(keeper);

        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (AlchemyScaffold.countInChest(s, helper, Items.MILK_BUCKET) < 1) {
                        throw new GameTestAssertException("等待第一桶奶入库");
                    }
                })
                .thenExecuteAfter(600, () -> {
                    // 30 秒后仍是 1 桶：同一天不允许挤同一头牛第二次
                    if (AlchemyScaffold.countInChest(s, helper, Items.MILK_BUCKET) != 1) {
                        helper.fail("同一游戏日内同头牛被挤了两次（日限失效）");
                    }
                })
                .thenSucceed();
    }
}
