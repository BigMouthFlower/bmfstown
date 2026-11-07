package com.bmf.bmfstown.gametest;

import com.bmf.bmfstown.ModDataComponents;
import com.bmf.bmfstown.ModEntities;
import com.bmf.bmfstown.calendar.TownCalendar;
import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.entity.profile.ProfessionType;
import com.bmf.bmfstown.town.Town;
import com.bmf.bmfstown.town.TownSaveData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 炼金行为测试的**世界脚手架**（第二层）：把炼金逻辑依赖的一切在测试结构里
 * 用代码摆出来——城镇（存档）、登记的酿造台/容器、活镇民、订单、工作时段。
 * 一次搭建，所有行为类用例复用（换职业/换订单 = 换参数，不动架子）。
 */
public final class AlchemyScaffold {

    /** 一套搭好的场景。 */
    public record Setup(Town town, List<Resident> residents, List<BlockPos> standRels,
                        BlockPos chestRel, BlockPos floorMin, BlockPos floorMax) {

        public ChestBlockEntity chest(GameTestHelper helper) {
            var be = helper.getLevel().getBlockEntity(helper.absolutePos(this.chestRel()));
            return be instanceof ChestBlockEntity chest ? chest : null;
        }

        public BrewingStandBlockEntity stand(GameTestHelper helper, BlockPos standRel) {
            var be = helper.getLevel().getBlockEntity(helper.absolutePos(standRel));
            return be instanceof BrewingStandBlockEntity s ? s : null;
        }

        public List<BrewingStandBlockEntity> stands(GameTestHelper helper) {
            List<BrewingStandBlockEntity> out = new ArrayList<>();
            for (BlockPos p : this.standRels()) {
                var s = stand(helper, p);
                if (s != null) {
                    out.add(s);
                }
            }
            return out;
        }

        public List<BlockPos> standAbs() {
            List<BlockPos> out = new ArrayList<>();
            for (BlockPos p : this.standRels()) {
                out.add(p);
            }
            return out;
        }
    }

    private AlchemyScaffold() {
    }

    /**
     * 搭镇 + 地面 + 方块 + 登记（不生成镇民）。
     *
     * @param standCount 酿造台数量（沿 z 排开）
     */
    public static Setup prepareTown(GameTestHelper helper, BlockPos floorMin, BlockPos floorMax,
                                    int standCount) {
        ServerLevel level = helper.getLevel();

        // 1. 地面（y=0 层铺石砖）
        for (int x = floorMin.getX(); x <= floorMax.getX(); x++) {
            for (int z = floorMin.getZ(); z <= floorMax.getZ(); z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.SMOOTH_STONE);
            }
        }

        // 2. 方块：容器（左下）+ N 个酿造台（右下沿 x 排开）
        BlockPos chestRel = new BlockPos(floorMin.getX() + 1, 1, floorMax.getZ() - 1);
        helper.setBlock(chestRel, Blocks.CHEST);
        List<BlockPos> standRels = new ArrayList<>();
        for (int i = 0; i < standCount; i++) {
            BlockPos p = new BlockPos(floorMax.getX() - 1 - i * 2, 1, floorMax.getZ() - 1);
            helper.setBlock(p, Blocks.BREWING_STAND);
            standRels.add(p);
        }

        // 3. 城镇（存档级，覆盖结构区+2）+ 登记
        UUID townId = UUID.randomUUID();
        Town town = new Town(townId, "测试镇-" + townId.toString().substring(0, 8), "tester",
                level.dimension().location().toString(),
                helper.absolutePos(floorMin),
                helper.absolutePos(new BlockPos(floorMax.getX(),
                        floorMax.getY() + 2, floorMax.getZ())));
        TownSaveData.get(level).add(town);
        town.workshop().containers.add(helper.absolutePos(chestRel).asLong());
        for (BlockPos p : standRels) {
            town.workshop().brewStations.add(helper.absolutePos(p).asLong());
        }

        // 4. 时间：强制工作时段 + 工作日（跳过昼夜/周末）
        forceWorkTime(helper);

        return new Setup(town, new ArrayList<>(), standRels, chestRel, floorMin, floorMax);
    }

    /** 往场景里加一个炼金术士镇民（绑定镇、设职业与订单）。 */
    public static Resident spawnAlchemist(Setup setup, GameTestHelper helper,
                                          String orderKey, BlockPos spawnRel) {
        ServerLevel level = helper.getLevel();
        Resident resident = helper.spawn(ModEntities.RESIDENT.get(), spawnRel);
        UUID townId = setup.town().id();
        resident.getIdentity().setProfession(ProfessionType.ALCHEMIST);
        resident.getIdentity().setTownId(townId);
        resident.getJobState().setOrderItem(orderKey);
        setup.residents().add(resident);
        return resident;
    }

    /** 把时间设到工作时段内（workStart=2000），并把星期推进到工作日。 */
    public static void forceWorkTime(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        var src = server.createCommandSourceStack().withPermission(4);
        server.getCommands().performPrefixedCommand(src, "time set 2500");
        var cal = TownCalendar.get(level);
        for (int i = 0; i < 8 && !cal.isWorkday(level); i++) {
            server.getCommands().performPrefixedCommand(src, "time add 24000");
        }
    }

    /** 给场景箱子装基础原料（可选龙息；sets = 份量倍数，多镇民场景传 2）。 */
    public static void stockChest(Setup setup, GameTestHelper helper, boolean withDragonBreath,
                                  int sets) {
        ChestBlockEntity chest = setup.chest(helper);
        if (chest == null) {
            return;
        }
        int slot = 0;
        for (int set = 0; set < sets; set++) {
            for (int i = 0; i < 3 && slot < chest.getContainerSize(); i++, slot++) {
                chest.setItem(slot, net.minecraft.world.item.alchemy.PotionContents
                        .createItemStack(Items.POTION, net.minecraft.world.item.alchemy.Potions.WATER));
            }
            chest.setItem(slot++, new ItemStack(Items.NETHER_WART));
            chest.setItem(slot++, new ItemStack(Items.SUGAR));
            chest.setItem(slot++, new ItemStack(Items.GUNPOWDER));
            chest.setItem(slot++, new ItemStack(Items.REDSTONE));
            if (withDragonBreath) {
                chest.setItem(slot++, new ItemStack(Items.DRAGON_BREATH));
            }
            chest.setItem(slot++, new ItemStack(Items.BLAZE_POWDER));
        }
        chest.setChanged();
    }

    /** 统计箱子+全部镇民背包里"带纯度章的 指定瓶×迅捷药"数量。
     *  注意识别方式：盖章后的瓶**没有 potion holder**（防原版双份施加），
     *  只能按 customEffects 里的速度效果识别——holder 判定永远数到 0。 */
    public static int countStamped(Setup setup, GameTestHelper helper,
                                   net.minecraft.world.item.Item bottle) {
        int count = 0;
        List<net.minecraft.world.Container> containers = new ArrayList<>();
        var chest = setup.chest(helper);
        if (chest != null) {
            containers.add(chest);
        }
        for (Resident r : setup.residents()) {
            containers.add(r.getInventory());
        }
        for (var c : containers) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack st = c.getItem(i);
                if (!st.is(bottle)) {
                    continue;
                }
                var contents = st.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
                boolean hasSpeed = contents.customEffects().stream()
                        .anyMatch(e -> e.getEffect() == net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED);
                if (hasSpeed && st.get(ModDataComponents.PURITY.get()) != null) {
                    count++;
                }
            }
        }
        return count;
    }

    /** 箱子里某物品还剩几件（原料消耗断言用）。 */
    public static int countInChest(Setup setup, GameTestHelper helper, net.minecraft.world.item.Item item) {
        var chest = setup.chest(helper);
        if (chest == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            if (chest.getItem(i).is(item)) {
                count += chest.getItem(i).getCount();
            }
        }
        return count;
    }
}
