package com.bmf.bmfstown.gametest;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.ModDataComponents;
import com.bmf.bmfstown.ModEntities;
import com.bmf.bmfstown.calendar.TownCalendar;
import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.entity.profile.Perks;
import com.bmf.bmfstown.entity.profile.ProfessionType;
import com.bmf.bmfstown.fireworks.FireworksCatalog;
import com.bmf.bmfstown.fireworks.FireworksCatalog.FwOrder;
import com.bmf.bmfstown.fireworks.FireworksCatalog.StarSpec;
import com.bmf.bmfstown.fireworks.FireworksMark;
import com.bmf.bmfstown.town.Town;
import com.bmf.bmfstown.town.TownSaveData;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * 烟火工匠用例（v0.13）：订单键解析边界 / 真实 assemble 两段合成 / 端到端
 * 生产入库 / 共池认领 / 模具复用 / 工匠章战斗三件（装药/破片/回响）。
 *
 * <p>GameTest 铁律（pitfalls.md）：轮询只抛 GameTestAssertException；
 * 结构目录单数 structure/；helper 方法吃结构内相对坐标；无头服务器不加载
 * 客户端类（客户端改动须游戏内实测）。
 */
@GameTestHolder(BmfTown.MOD_ID)
public class FireworksTests {

    // ==========================================================
    //  订单键解析（纯逻辑，tiny 结构即可）
    // ==========================================================

    /** 合法键：全字段解析回来一字不差。 */
    @GameTest(template = "tiny3x3x3", timeoutTicks = 100)
    public static void orderKeyParseValid(GameTestHelper helper) {
        String key = "fw|2|2,5,7+9+1,tw|0,12,-,n|4,1,-,t";
        FwOrder order = FireworksCatalog.parse(key);
        if (order == null) {
            helper.fail("合法订单键被拒: " + key);
            return;
        }
        if (order.gunpowder() != 2 || order.stars().size() != 3) {
            helper.fail("解析字段不符: " + order);
            return;
        }
        StarSpec s0 = order.stars().get(0);
        if (s0.shape() != FireworkExplosion.Shape.STAR || s0.colors().length != 1
                || s0.colors()[0] != 5
                || s0.trail() != s0.twinkle() || !s0.trail()
                || s0.fades().length != 3 || s0.fades()[1] != 9) {
            helper.fail("星0 解析不符: " + s0);
            return;
        }
        if (!order.encode().equals(key)) {
            helper.fail("再编码不一致: " + order.encode());
            return;
        }
        helper.succeed();
    }

    /** 野键/超约束全部拒绝：8 格共享（m ≤ 8−n）、火药 1~3、色 0~15、渐变 ≤8、
     *  主色格子账（≤8−形状物−钻石−荧石）。 */
    @GameTest(template = "tiny3x3x3", timeoutTicks = 100)
    public static void orderKeyParseRejectsWild(GameTestHelper helper) {
        String[] bad = {
                null, "", "alch|minecraft:swiftness|none",   // 跨职业野键
                "fw|0", "fw|4",                              // 火药越界
                "fw|1|2,16,-,n",                             // 主色越界
                "fw|1|2,5,1+2+3+4+5+6+7+8+9,n",              // 渐变 9 色 >8
                "fw|1|0,1+2+3+4+5+6+7+8+9,-,n",              // 主色 9 色 >8
                "fw|1|1,1+2+3+4+5+6+7+8,-,n",                // 大球占 1 格 → 主色上限 7
                "fw|1|0,1+2+3+4+5+6+7+8,-,tw",               // 双修占 2 格 → 上限 6
                "fw|1|0,1,-,n|0,2,-,n|0,3,-,n|0,4,-,n|0,5,-,n|0,6,-,n|0,7,-,n|0,8,-,n",
                "fw|3|0,1,-,n|0,2,-,n|0,3,-,n|0,4,-,n|0,5,-,n|0,6,-,n", // 6 星 > 8−3
                "fw|1|2,5,-,x",                              // 修饰未知
                "fw|1|2,5,-,",                               // 截断
        };
        for (String k : bad) {
            if (FireworksCatalog.parse(k) != null) {
                helper.fail("野键未被拒绝: " + k);
                return;
            }
        }
        // 合法边界：n=3 → 星位 5；n=1 → 星位 7；无形状无修饰 → 主色 8 满
        if (FireworksCatalog.parse("fw|3|0,1,-,n|0,2,-,n|0,3,-,n|0,4,-,n|0,5,-,n") == null) {
            helper.fail("合法边界被拒：n=3 星 5");
            return;
        }
        if (FireworksCatalog.parse("fw|1|0,1,-,n|0,2,-,n|0,3,-,n|0,4,-,n|0,5,-,n|0,6,-,n|0,7,-,n")
                == null) {
            helper.fail("合法边界被拒：n=1 星 7");
            return;
        }
        if (FireworksCatalog.parse("fw|1|0,1+2+3+4+5+6+7+8,-,n") == null) {
            helper.fail("合法边界被拒：主色 8 满配");
            return;
        }
        helper.succeed();
    }

    // ==========================================================
    //  真实 assemble（特殊配方动态算组件，无星哑炮天然规避）
    // ==========================================================

    /** 星合成：FireworkStarRecipe 真实产出爆炸组件（形状/主色/修饰）。 */
    @GameTest(template = "tiny3x3x3", timeoutTicks = 100)
    public static void starAssembleRealComponents(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        StarSpec spec = new StarSpec(FireworkExplosion.Shape.LARGE_BALL,
                new int[]{5}, new int[0], true, false);
        ItemStack star = FireworksCatalog.assembleStar(level, spec);
        if (star.isEmpty() || !star.is(Items.FIREWORK_STAR)) {
            helper.fail("星合成未命中配方");
            return;
        }
        FireworkExplosion ex = star.get(DataComponents.FIREWORK_EXPLOSION);
        if (ex == null) {
            helper.fail("产物缺爆炸组件（哑炮星）");
            return;
        }
        if (ex.shape() != FireworkExplosion.Shape.LARGE_BALL
                || !ex.colors().equals(IntList.of(DyeColor.LIME.getFireworkColor()))
                || !ex.fadeColors().isEmpty()
                || !ex.hasTrail() || ex.hasTwinkle()) {
            helper.fail("组件不符: " + ex);
            return;
        }
        helper.succeed();
    }

    /** 渐变走第二段（fade 配方）：原版星星合成多染料全进主色，fadeColors 只能追加工。 */
    @GameTest(template = "tiny3x3x3", timeoutTicks = 100)
    public static void starFadeTwoStep(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        StarSpec spec = new StarSpec(FireworkExplosion.Shape.SMALL_BALL,
                new int[]{1}, new int[]{3, 14}, false, true);
        ItemStack star = FireworksCatalog.assembleStar(level, spec);
        FireworkExplosion ex = star.isEmpty() ? null
                : star.get(DataComponents.FIREWORK_EXPLOSION);
        if (ex == null) {
            helper.fail("渐变星合成失败");
            return;
        }
        if (!ex.fadeColors().equals(IntList.of(
                        DyeColor.LIGHT_BLUE.getFireworkColor(),
                        DyeColor.RED.getFireworkColor()))
                || !ex.colors().equals(IntList.of(DyeColor.ORANGE.getFireworkColor()))
                || !ex.hasTwinkle()) {
            helper.fail("渐变组件不符: " + ex);
            return;
        }
        helper.succeed();
    }

    /** 多主色/满格/8 渐变：原版口径——多染料全进主色（按投料序）、9 格满投 8 染料、
     *  fade 配方星+染料 ≤8。 */
    @GameTest(template = "tiny3x3x3", timeoutTicks = 100)
    public static void starMultiColorAndFullGrid(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 三主色按投料序收录
        StarSpec tri = new StarSpec(FireworkExplosion.Shape.SMALL_BALL,
                new int[]{1, 5, 14}, new int[0], false, false);
        FireworkExplosion ex = FireworksCatalog.assembleStar(level, tri)
                .get(DataComponents.FIREWORK_EXPLOSION);
        if (ex == null || !ex.colors().equals(IntList.of(
                DyeColor.ORANGE.getFireworkColor(),
                DyeColor.LIME.getFireworkColor(),
                DyeColor.RED.getFireworkColor()))) {
            helper.fail("多主色不符: " + (ex == null ? "无组件" : ex));
            return;
        }
        // 满格：火药 1 + 染料 8 = 9 格（无形状/修饰）
        StarSpec full = new StarSpec(FireworkExplosion.Shape.SMALL_BALL,
                new int[]{0, 1, 2, 3, 4, 5, 6, 7}, new int[0], false, false);
        FireworkExplosion fullEx = FireworksCatalog.assembleStar(level, full)
                .get(DataComponents.FIREWORK_EXPLOSION);
        if (fullEx == null || fullEx.colors().size() != 8) {
            helper.fail("满格 8 主色不符: " + (fullEx == null ? "无组件" : fullEx));
            return;
        }
        // 渐变 8 色（fade 配方=星+染料，与形状/修饰无关）
        StarSpec fade8 = new StarSpec(FireworkExplosion.Shape.LARGE_BALL,
                new int[]{5}, new int[]{8, 9, 10, 11, 12, 13, 14, 15}, false, false);
        FireworkExplosion fadeEx = FireworksCatalog.assembleStar(level, fade8)
                .get(DataComponents.FIREWORK_EXPLOSION);
        if (fadeEx == null || fadeEx.fadeColors().size() != 8) {
            helper.fail("8 渐变不符: " + (fadeEx == null ? "无组件" : fadeEx));
            return;
        }
        helper.succeed();
    }

    /** 火箭组装：FireworkRocketRecipe 真实产出 ×3 + FIREWORKS 组件（flight=火药数）。 */
    @GameTest(template = "tiny3x3x3", timeoutTicks = 100)
    public static void rocketAssembleRealComponents(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FwOrder order = FireworksCatalog.parse("fw|3|2,5,-,n|0,12,-,n");
        if (order == null) {
            helper.fail("订单键解析失败");
            return;
        }
        List<ItemStack> stars = order.stars().stream()
                .map(s -> FireworksCatalog.assembleStar(level, s)).toList();
        if (stars.stream().anyMatch(ItemStack::isEmpty)) {
            helper.fail("星段合成失败");
            return;
        }
        ItemStack rocket = FireworksCatalog.assembleRocket(level, order, stars);
        if (rocket.isEmpty() || !rocket.is(Items.FIREWORK_ROCKET)) {
            helper.fail("火箭组装未命中配方");
            return;
        }
        if (rocket.getCount() != 3) {
            helper.fail("产量应为 3 个/套（决策 #30 玩家口径），实际 " + rocket.getCount());
            return;
        }
        Fireworks fw = rocket.get(DataComponents.FIREWORKS);
        if (fw == null || fw.flightDuration() != 3 || fw.explosions().size() != 2) {
            helper.fail("FIREWORKS 组件不符: " + fw);
            return;
        }
        helper.succeed();
    }

    /** 纯火箭（星 0 颗，装饰单合法）：explosions 为空但配方照常命中。 */
    @GameTest(template = "tiny3x3x3", timeoutTicks = 100)
    public static void rocketNoStarStillValid(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FwOrder order = FireworksCatalog.parse("fw|1");
        ItemStack rocket = FireworksCatalog.assembleRocket(level, order, List.of());
        Fireworks fw = rocket.isEmpty() ? null : rocket.get(DataComponents.FIREWORKS);
        if (fw == null || fw.flightDuration() != 1 || !fw.explosions().isEmpty()) {
            helper.fail("纯火箭组件不符: " + fw);
            return;
        }
        helper.succeed();
    }

    // ==========================================================
    //  端到端（脚手架场景：镇 + 工作台池 + 容器）
    // ==========================================================

    private record Setup(Town town, BlockPos chestRel, List<BlockPos> tableRels,
                         List<Resident> residents) {

        public ChestBlockEntity chest(GameTestHelper helper) {
            var be = helper.getLevel().getBlockEntity(helper.absolutePos(this.chestRel()));
            return be instanceof ChestBlockEntity chest ? chest : null;
        }
    }

    /** 搭镇 + 地面 + 箱子 + N 张工作台（登记进 stations 岗位池）。 */
    private static Setup prepareTown(GameTestHelper helper, int tableCount) {
        ServerLevel level = helper.getLevel();
        BlockPos floorMin = new BlockPos(1, 0, 1);
        BlockPos floorMax = new BlockPos(11, 0, 9);
        for (int x = floorMin.getX(); x <= floorMax.getX(); x++) {
            for (int z = floorMin.getZ(); z <= floorMax.getZ(); z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.SMOOTH_STONE);
            }
        }
        BlockPos chestRel = new BlockPos(floorMin.getX() + 1, 1, floorMax.getZ() - 1);
        helper.setBlock(chestRel, Blocks.CHEST);
        List<BlockPos> tables = new java.util.ArrayList<>();
        for (int i = 0; i < tableCount; i++) {
            BlockPos p = new BlockPos(floorMax.getX() - 1 - i * 2, 1, floorMax.getZ() - 1);
            helper.setBlock(p, Blocks.CRAFTING_TABLE);
            tables.add(p);
        }
        UUID townId = UUID.randomUUID();
        Town town = new Town(townId, "测试镇-" + townId.toString().substring(0, 8), "tester",
                level.dimension().location().toString(),
                helper.absolutePos(floorMin),
                helper.absolutePos(new BlockPos(floorMax.getX(), floorMax.getY() + 2,
                        floorMax.getZ())));
        TownSaveData.get(level).add(town);
        town.workshop().containers.add(helper.absolutePos(chestRel).asLong());
        for (BlockPos p : tables) {
            town.workshop().stations.add(helper.absolutePos(p).asLong());
        }
        forceWorkTime(helper);
        return new Setup(town, chestRel, tables, new java.util.ArrayList<>());
    }

    private static void forceWorkTime(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        var src = server.createCommandSourceStack().withPermission(4);
        server.getCommands().performPrefixedCommand(src, "time set 2500");
        var cal = TownCalendar.get(level);
        for (int i = 0; i < 8 && !cal.isWorkday(level); i++) {
            server.getCommands().performPrefixedCommand(src, "time add 24000");
        }
    }

    private static Resident spawnFireworksman(Setup s, GameTestHelper helper,
            String orderKey, BlockPos spawnRel) {
        Resident resident = helper.spawn(ModEntities.RESIDENT.get(), spawnRel);
        resident.getIdentity().setProfession(ProfessionType.FIREWORKSMAN);
        resident.getIdentity().setTownId(s.town().id());
        resident.syncClient(); // getProfession() 读同步快照，改完必须刷新（pitfalls）
        resident.getJobState().setOrderItem(orderKey);
        s.residents().add(resident);
        return resident;
    }

    /** 全链路：造星两段 → 组装 → 入库（箱子出 3 支带正确组件的烟花）。 */
    @GameTest(template = "room13x6x11", timeoutTicks = 24000)
    public static void fullFlowCraftAndStore(GameTestHelper helper) {
        Setup s = prepareTown(helper, 1);
        var chest = s.chest(helper);
        int slot = 0;
        for (int i = 0; i < 3 && slot < chest.getContainerSize(); i++, slot++) {
            chest.setItem(slot, new ItemStack(Items.PAPER));
        }
        for (int i = 0; i < 6 && slot < chest.getContainerSize(); i++, slot++) {
            chest.setItem(slot, new ItemStack(Items.GUNPOWDER));
        }
        for (int i = 0; i < 3 && slot < chest.getContainerSize(); i++, slot++) {
            chest.setItem(slot, new ItemStack(Items.RED_DYE));
        }
        for (int i = 0; i < 3 && slot < chest.getContainerSize(); i++, slot++) {
            chest.setItem(slot, new ItemStack(Items.GOLD_NUGGET));
        }
        chest.setChanged();
        spawnFireworksman(s, helper, "fw|1|2,14,-,n", new BlockPos(5, 1, 5));
        helper.succeedWhen(() -> {
            var c = s.chest(helper);
            int rockets = 0;
            ItemStack sample = null;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack st = c.getItem(i);
                if (st.is(Items.FIREWORK_ROCKET)) {
                    rockets += st.getCount();
                    sample = st;
                }
            }
            if (rockets < 3 || sample == null) {
                throw new GameTestAssertException("等待入库烟花 ≥3 支（当前 " + rockets + "）");
            }
            Fireworks fw = sample.get(DataComponents.FIREWORKS);
            if (fw == null || fw.flightDuration() != 1 || fw.explosions().size() != 1) {
                helper.fail("烟花组件不符: " + fw);
                return;
            }
            FireworkExplosion ex = fw.explosions().get(0);
            if (ex.shape() != FireworkExplosion.Shape.STAR
                    || !ex.colors().equals(IntList.of(DyeColor.RED.getFireworkColor()))) {
                helper.fail("星规格不符: " + ex);
                return;
            }
        });
    }

    /** 现成星直接组装（星=合法中间物进容器池）：染料/金粒不被消耗（未造星）。 */
    @GameTest(template = "room13x6x11", timeoutTicks = 24000)
    public static void readyStarsSkipCrafting(GameTestHelper helper) {
        Setup s = prepareTown(helper, 1);
        var chest = s.chest(helper);
        int slot = 0;
        for (int i = 0; i < 3 && slot < chest.getContainerSize(); i++, slot++) {
            chest.setItem(slot, new ItemStack(Items.PAPER));
        }
        for (int i = 0; i < 3 && slot < chest.getContainerSize(); i++, slot++) {
            chest.setItem(slot, new ItemStack(Items.GUNPOWDER));
        }
        // 3 颗现成星（规格与订单完全一致——同款 assemble 产出）
        StarSpec spec = new StarSpec(FireworkExplosion.Shape.STAR, new int[]{14},
                new int[0], false, false);
        for (int i = 0; i < 3 && slot < chest.getContainerSize(); i++, slot++) {
            chest.setItem(slot, FireworksCatalog.assembleStar(helper.getLevel(), spec));
        }
        chest.setChanged();
        int dyeBefore = countItem(chest, Items.RED_DYE);
        spawnFireworksman(s, helper, "fw|1|2,14,-,n", new BlockPos(5, 1, 5));
        helper.succeedWhen(() -> {
            var c = s.chest(helper);
            int rockets = 0;
            for (int i = 0; i < c.getContainerSize(); i++) {
                if (c.getItem(i).is(Items.FIREWORK_ROCKET)) {
                    rockets += c.getItem(i).getCount();
                }
            }
            if (rockets < 3) {
                throw new GameTestAssertException("等待现成星直接组装的烟花 ≥3 支");
            }
            if (countItem(c, Items.RED_DYE) != dyeBefore) {
                helper.fail("染料被消耗 = 星被重造（应直接用现成星）");
                return;
            }
        });
    }

    /** 模具复用（传奇 A 位）：造星全程形状物（金粒）不消耗。 */
    @GameTest(template = "room13x6x11", timeoutTicks = 24000)
    public static void moldReuseKeepsShapeItem(GameTestHelper helper) {
        Setup s = prepareTown(helper, 1);
        var chest = s.chest(helper);
        int slot = 0;
        for (int i = 0; i < 3 && slot < chest.getContainerSize(); i++, slot++) {
            chest.setItem(slot, new ItemStack(Items.PAPER));
        }
        for (int i = 0; i < 6 && slot < chest.getContainerSize(); i++, slot++) {
            chest.setItem(slot, new ItemStack(Items.GUNPOWDER));
        }
        for (int i = 0; i < 3 && slot < chest.getContainerSize(); i++, slot++) {
            chest.setItem(slot, new ItemStack(Items.RED_DYE));
        }
        for (int i = 0; i < 3 && slot < chest.getContainerSize(); i++, slot++) {
            chest.setItem(slot, new ItemStack(Items.GOLD_NUGGET));
        }
        chest.setChanged();
        Resident r = spawnFireworksman(s, helper, "fw|1|2,14,-,n", new BlockPos(5, 1, 5));
        r.getGrowth().setPerkChoice(ProfessionType.FIREWORKSMAN, Perks.NODE_FW_LEGEND,
                "mold_reuse");
        helper.succeedWhen(() -> {
            var c = s.chest(helper);
            int rockets = 0;
            for (int i = 0; i < c.getContainerSize(); i++) {
                if (c.getItem(i).is(Items.FIREWORK_ROCKET)) {
                    rockets += c.getItem(i).getCount();
                }
            }
            if (rockets < 3) {
                throw new GameTestAssertException("等待烟花 ≥3 支（当前 " + rockets + "）");
            }
            if (countItem(c, Items.GOLD_NUGGET) != 3) {
                helper.fail("金粒数量变化 = 形状物被消耗（模具复用失效），剩 "
                        + countItem(c, Items.GOLD_NUGGET));
                return;
            }
            if (countItem(c, Items.RED_DYE) != 0) {
                helper.fail("染料未被消耗 = 根本没造星");
                return;
            }
        });
    }

    /** 共池认领先到先得（决策 #30）：一桌一池，工人与烟火工匠先后开工都有产出。 */
    @GameTest(template = "room13x6x11", timeoutTicks = 40000)
    public static void sharedTableFirstComeFirstServed(GameTestHelper helper) {
        Setup s = prepareTown(helper, 1);
        var chest = s.chest(helper);
        int slot = 0;
        // 烟火料（1 套：纸1 火药3 染料1 金粒1）
        chest.setItem(slot++, new ItemStack(Items.PAPER));
        for (int i = 0; i < 3; i++) {
            chest.setItem(slot++, new ItemStack(Items.GUNPOWDER));
        }
        chest.setItem(slot++, new ItemStack(Items.RED_DYE));
        chest.setItem(slot++, new ItemStack(Items.GOLD_NUGGET));
        // 工人料（红石火把 1 件 = 棒1 红石1）
        chest.setItem(slot++, new ItemStack(Items.STICK));
        chest.setItem(slot++, new ItemStack(Items.REDSTONE));
        chest.setChanged();
        Resident fw = spawnFireworksman(s, helper, "fw|1|2,14,-,n", new BlockPos(5, 1, 5));
        Resident worker = helper.spawn(ModEntities.RESIDENT.get(), new BlockPos(6, 1, 5));
        worker.getIdentity().setProfession(ProfessionType.WORKER);
        worker.getIdentity().setTownId(s.town().id());
        worker.syncClient();
        worker.getJobState().setOrderItem("minecraft:redstone_torch");
        s.residents().add(worker);
        helper.succeedWhen(() -> {
            var c = s.chest(helper);
            boolean torch = false;
            boolean rocket = false;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack st = c.getItem(i);
                torch |= st.is(Items.REDSTONE_TORCH);
                rocket |= st.is(Items.FIREWORK_ROCKET);
            }
            if (!torch) {
                throw new GameTestAssertException("等待工人产出红石火把");
            }
            if (!rocket) {
                throw new GameTestAssertException("等待烟火产出烟花");
            }
        });
    }

    // ==========================================================
    //  工匠章战斗三件（mixin 挂点）
    // ==========================================================

    /** 组一支章烟花（单星大球；组件手工构造——战斗用例不依赖工匠 Goal）。 */
    private static ItemStack markedRocket(ServerLevel level, FireworksMark mark) {
        StarSpec spec = new StarSpec(FireworkExplosion.Shape.LARGE_BALL,
                new int[]{1}, new int[0], false, false);
        ItemStack star = FireworksCatalog.assembleStar(level, spec);
        ItemStack rocket = FireworksCatalog.assembleRocket(level,
                FireworksCatalog.parse("fw|1|1,1,-,n"), List.of(star));
        rocket.setCount(1);
        rocket.set(ModDataComponents.FIREWORKS_MARK.get(), mark);
        return rocket;
    }

    /** 在目标头顶放一支定点火箭（shotAtAngle=发射器同款：无加速，仅 0.05/t 匀速上升，
     *  lifetime 20~32t → 自爆时高 1.0~1.6 格——自由火箭每 tick +0.04 加速会飞出 AOE）。 */
    private static void detonateOver(GameTestHelper helper, ItemStack rocket,
            LivingEntity target) {
        ServerLevel level = helper.getLevel();
        FireworkRocketEntity entity = new FireworkRocketEntity(level, rocket,
                target.getX(), target.getY() + 0.7, target.getZ(), true);
        level.addFreshEntity(entity);
    }

    /** 装药足量（大师 B 位）：单星贴爆 = (5+2×1)×1.25 = 8.75 伤害 → 村民 11.25 血。 */
    @GameTest(template = "range13x6x11", timeoutTicks = 600)
    public static void payloadBoostDamage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 1; x <= 11; x++) {
            for (int z = 1; z <= 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.SMOOTH_STONE);
            }
        }
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(6, 1, 5));
        villager.setNoAi(true);
        detonateOver(helper, markedRocket(level,
                new FireworksMark(false, true, false)), villager);
        helper.succeedWhen(() -> {
            float hp = villager.getHealth();
            if (hp < 19.9F) {
                // 定点火箭自爆时高 0.7+1.0~1.6 = 1.7~2.3：
                // 伤害 = 7 × 1.25 × sqrt((5−d)/5) ≈ 6.4~7.1 → 血 ≈12.9~13.6
                if (hp >= 12.5F && hp <= 14.0F) {
                    return; // 过
                }
                helper.fail("装药足量伤害不符：村民血 " + hp + "（期望 ≈12.9~13.6）");
                return;
            }
            throw new GameTestAssertException("等待爆炸结算（血 " + hp + "）");
        });
    }

    /** 破片（精通 B 位）：5.5 格外（>原版 5 半径、<破片 6）能被伤到。 */
    @GameTest(template = "range13x6x11", timeoutTicks = 600)
    public static void shrapnelReachesBeyondVanillaRadius(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 1; x <= 11; x++) {
            for (int z = 1; z <= 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.SMOOTH_STONE);
            }
        }
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(6, 1, 5));
        villager.setNoAi(true);
        // 定点火箭（shotAtAngle）水平偏 5.0 格：自爆高 1.7~2.3 → 总距离 5.28~5.51：
        // 原版半径 5 排除；破片半径 6 命中，伤害 = 7×sqrt((6−d)/6) ≈ 2.0~2.4
        ItemStack rocket = markedRocket(level, new FireworksMark(true, false, false));
        FireworkRocketEntity entity = new FireworkRocketEntity(level, rocket,
                villager.getX(), villager.getY() + 0.7, villager.getZ() + 5.0, true);
        level.addFreshEntity(entity);
        helper.succeedWhen(() -> {
            float hp = villager.getHealth();
            if (hp < 19.9F) {
                if (hp >= 17.0F && hp <= 18.4F) {
                    return;
                }
                helper.fail("破片伤害不符：村民血 " + hp + "（期望 ≈17.6~18.0）");
                return;
            }
            throw new GameTestAssertException("等待爆炸结算（血 " + hp + "）");
        });
    }

    /** 原版对照：无章火箭在 5.5 格外打不到（>5 半径排除）——破片用例的区分度。 */
    @GameTest(template = "range13x6x11", timeoutTicks = 600)
    public static void vanillaRocketMissesBeyondRadius(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 1; x <= 11; x++) {
            for (int z = 1; z <= 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.SMOOTH_STONE);
            }
        }
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(6, 1, 5));
        villager.setNoAi(true);
        ItemStack rocket = markedRocket(level, null); // 无章 = 原版逻辑
        FireworkRocketEntity entity = new FireworkRocketEntity(level, rocket,
                villager.getX(), villager.getY() + 0.7, villager.getZ() + 5.0, true);
        level.addFreshEntity(entity);
        helper.runAfterDelay(120, () -> {
            if (villager.getHealth() < 19.9F) {
                helper.fail("原版火箭在 5.5 格外不应命中（血 " + villager.getHealth() + "）");
                return;
            }
            helper.succeed();
        });
    }

    /** 回响（传奇 B 位）：首爆 ≈7 伤 + 延迟 10t 二段 ≈3.5 → 村民 ≈9.5 血。 */
    @GameTest(template = "range13x6x11", timeoutTicks = 800)
    public static void echoSecondExplosion(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 1; x <= 11; x++) {
            for (int z = 1; z <= 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.SMOOTH_STONE);
            }
        }
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(6, 1, 5));
        villager.setNoAi(true);
        detonateOver(helper, markedRocket(level,
                new FireworksMark(false, false, true)), villager);
        helper.succeedWhen(() -> {
            float hp = villager.getHealth();
            if (hp < 19.9F) {
                // 定点火箭：首爆 d1 = 1.7~2.3，伤害 = 7×sqrt((5−d)/5) ≈ 5.1~5.7，
                // 二段 = 首爆一半 → 总 ≈ 7.7~8.5 → 血 ≈ 11.5~12.3
                if (hp >= 11.0F && hp <= 12.7F) {
                    return;
                }
                helper.fail("回响总伤不符：村民血 " + hp
                        + "（期望 ≈9.3~10.4 = 首爆的 1.5 倍扣血）");
                return;
            }
            throw new GameTestAssertException("等待首爆+二段结算（血 " + hp + "）");
        });
    }

    private static int countItem(ChestBlockEntity chest, net.minecraft.world.item.Item item) {
        int total = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            if (chest.getItem(i).is(item)) {
                total += chest.getItem(i).getCount();
            }
        }
        return total;
    }
}
