package com.bmf.bmfstown.gametest;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.ModEntities;
import com.bmf.bmfstown.entity.KegAccess;
import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.entity.TavernkeepGoal;
import com.bmf.bmfstown.entity.profile.ProfessionType;
import com.bmf.bmfstown.town.TownWandMode;
import com.bmf.bmfstown.town.TownZone;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;

import java.util.List;
import java.util.UUID;

/**
 * 酒馆（决策 #63/#64/#65）：划区 + 消费 + 营业（决策 #65 营业版重做——
 * 酒保不再采集，改为「点单上酒 + 酿造循环 + 接瓶入窖」；BAC 就挂在
 * run/mods，GameTest 服会真实加载，酒桶与配方用例直接吃真数据）。
 * <p>复用 {@link AlchemyScaffold#prepareTown} 搭镇（其箱子即酒窖，容器池与
 * 酒馆无关——酒保只认 TAVERN 区内的容器方块，不认工坊登记池）。
 */
@GameTestHolder(BmfTown.MOD_ID)
public class TavernTests {

    /** 划满结构酒馆区 + 酒保入职（消费/营业用例共用）。 */
    private static AlchemyScaffold.Setup tavern(AlchemyScaffold.Setup s, GameTestHelper helper,
            Resident keeper) {
        s.town().addZone(TownWandMode.TAVERN, new TownZone(UUID.randomUUID(), "测试酒馆",
                helper.absolutePos(new BlockPos(1, 1, 1)),
                helper.absolutePos(new BlockPos(11, 1, 9))));
        // 时间（#67 实测教训）：先像炼金 forceWorkTime 一样把星期推到工作日
        // （≤8 次整天推进），再在【保持天数不变】前提下把钟点拨到 3500
        // ——晨夜轴个人偏移最高 ±1000t（夜猫子开工推迟到 3000），3500 保证
        // 任何偏移下都在工作窗口。绝不能直接 "time set 3500"：那会把全局
        // dayTime 打回纪元 day0=周日，正在跑的并行炼金/酒馆用例被掐成周末
        // 停工——炼金术士反复重启烧光燃料（"缺 Blaze Powder"卡死）、酒保
        // 秘传掷骰停摆，全是这一根因的下游症状。
        var level = helper.getLevel();
        var src = level.getServer().createCommandSourceStack().withPermission(4);
        var cal = com.bmf.bmfstown.calendar.TownCalendar.get(level);
        for (int i = 0; i < 8 && !cal.isWorkday(level); i++) {
            level.getServer().getCommands().performPrefixedCommand(src, "time add 24000");
        }
        long tod = level.getDayTime() % 24000L;
        level.getServer().getCommands().performPrefixedCommand(src, "time add " + (3500 - tod));
        if (keeper != null) {
            keeper.getIdentity().setProfession(ProfessionType.TAVERNKEEP);
            keeper.getIdentity().setTownId(s.town().id());
            keeper.syncClient();
            s.residents().add(keeper);
        }
        return s;
    }

    /**
     * 放一个 BAC 酒桶并确认 BE 能构造。GameTestServer 环境里 BAC 配置
     * （greenhouseconfig 'brewinandchewin-common'）不加载，keg BE 构造即抛
     * NPE（#65 实证）——此时跳过用例（succeed），酒桶路径靠真实环境覆盖。
     * 返回 null = 环境不支持，调用方直接 return。
     *
     * <p>2026-11-18 追记（反编译 greenhouseconfig 实锤，详见 .ai/pitfalls.md
     * 坑15）：配置加载挂 {@code isDedicatedServer()} 门 + ServerAboutToStart
     * 事件，GameTestServer 不是 dedicated → 配置永不加载；测试脚手架反射
     * 强调 {@code onServerStarting} 也走不通（holder.get() 内部仍查不到
     * 已加载实例）。所以酒桶用例在本环境<b>结构性自跳过</b>——续桶等
     * 酒桶判定逻辑的回归靠 {@code resumePlan*} 纯函数用例（empty3x3x3，
     * 不需要 BAC BE，永远真跑），BE 交互路径靠真实环境覆盖。
     */
    private static BlockPos placeKegOrSkip(GameTestHelper helper, BlockPos local) {
        Block keg = BuiltInRegistries.BLOCK.getOptional(
                ResourceLocation.fromNamespaceAndPath("brewinandchewin", "keg")).orElse(null);
        if (keg == null) {
            BmfTown.LOGGER.info("[TavernTests] BAC 未加载，酒桶用例跳过");
            helper.succeed();
            return null;
        }
        try {
            helper.setBlock(local, keg.defaultBlockState());
            BlockPos abs = helper.absolutePos(local);
            if (helper.getLevel().getBlockEntity(abs) == null) {
                throw new IllegalStateException("keg BE 缺失");
            }
            return abs;
        } catch (RuntimeException e) {
            BmfTown.LOGGER.info("[TavernTests] GameTest 环境构造 BAC 酒桶失败（配置缺失，{}），"
                    + "酒桶用例跳过——真实环境覆盖", e.toString());
            helper.succeed();
            return null;
        }
    }

    /** 容器里某物品总数（按物品，忽略组件差异）。 */
    private static int countItem(ChestBlockEntity c, net.minecraft.world.item.Item item) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            if (c.getItem(i).is(item)) {
                n += c.getItem(i).getCount();
            }
        }
        return n;
    }

    // ==========================================================
    //  消费：点单 → 上酒 → 喝 → 还杯（决策 #65）
    // ==========================================================

    /**
     * 消费闭环（决策 #65 营业版）：镇民落座点单 → 酒保送酒到主手 →
     * 喝掉（想法留痕+每日账）→ <b>还杯铁律</b>：空桶就近放回酒窖。
     * 测试镇民身兼酒保（唯一镇民；点单后他自己的酒保 Goal 会认领自己的单
     * ——单人服务闭环，正是无头服能测的最小营业形态）。
     */
    @GameTest(template = "room11x4x11", timeoutTicks = 12000)
    public static void patronOrdersKeeperServesCupReturns(GameTestHelper helper) {
        AlchemyScaffold.Setup s = AlchemyScaffold.prepareTown(
                helper, new BlockPos(1, 1, 1), new BlockPos(11, 1, 9), 0);
        Resident patron = helper.spawn(ModEntities.RESIDENT.get(), new BlockPos(6, 1, 5));
        tavern(s, helper, patron);
        var chest = s.chest(helper);
        chest.setItem(0, new ItemStack(Items.MILK_BUCKET));
        chest.setChanged();

        com.bmf.bmfstown.leisure.TavernVisitActivity activity =
                new com.bmf.bmfstown.leisure.TavernVisitActivity();
        helper.startSequence()
                .thenExecute(() -> {
                    if (!activity.canStart(patron, helper.getLevel())) {
                        helper.fail("开局今日未喝，canStart 应为 true");
                    }
                    if (!activity.start(patron, helper.getLevel())) {
                        helper.fail("酒窖有奶，start 应成功");
                    }
                })
                // 每 tick 手动推进活动，直到喝下——"已喝"标记用想法留痕（日数
                // 无关：并行测试会 time set/add 把全局日顶走，lastTavernDay
                // 比对开局 day 会假死，#65 实证）
                .thenWaitUntil(() -> {
                    activity.tick(patron, helper.getLevel());
                    if (!hasTavernThought(patron)) {
                        throw new GameTestAssertException("等待镇民喝下这杯");
                    }
                })
                .thenExecute(() -> {
                    if (countItem(s.chest(helper), Items.MILK_BUCKET) != 0) {
                        helper.fail("奶桶没有被真实消耗");
                    }
                })
                // 还杯：继续推进到空桶回窖
                .thenWaitUntil(() -> {
                    activity.tick(patron, helper.getLevel());
                    if (countItem(s.chest(helper), Items.BUCKET) < 1) {
                        throw new GameTestAssertException("等待空桶还回酒窖（还杯铁律）");
                    }
                })
                .thenSucceed();
    }

    /** 喝下结算会落"酒馆想法"——日数无关的"已喝"标记。 */
    private static boolean hasTavernThought(Resident patron) {
        for (var t : patron.getStatus().thoughts()) {
            if ("mood.bmfstown.thought.tavern".equals(t.key())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 找椅子坐下（#67 坐姿最小版）：区内有楼梯 → 客人走到座位<b>真坐上</b>
     * （隐形骑乘 {@link com.bmf.bmfstown.entity.SeatEntity}）→ 坐下立即点单
     * → 到手即喝 → 起身还杯 → <b>回座位坐下</b>逗留 → abort 后座位拆除、
     * 客人起身。客人身兼酒保（单人闭环，同上）。
     */
    @GameTest(template = "room11x4x11", timeoutTicks = 12000)
    public static void patronSitsOnStairOrdersAndLingers(GameTestHelper helper) {
        AlchemyScaffold.Setup s = AlchemyScaffold.prepareTown(
                helper, new BlockPos(1, 1, 1), new BlockPos(11, 1, 9), 0);
        Resident patron = helper.spawn(ModEntities.RESIDENT.get(), new BlockPos(6, 1, 5));
        tavern(s, helper, patron);
        BlockPos anchor = helper.absolutePos(new BlockPos(1, 1, 1));
        // 座位：出生点旁的楼梯（不考验寻路，进场即坐）
        helper.setBlock(new BlockPos(7, 1, 5),
                net.minecraft.world.level.block.Blocks.OAK_STAIRS.defaultBlockState());
        var chest = s.chest(helper);
        chest.setItem(0, new ItemStack(Items.MILK_BUCKET));
        chest.setChanged();

        com.bmf.bmfstown.leisure.TavernVisitActivity activity =
                new com.bmf.bmfstown.leisure.TavernVisitActivity();
        helper.startSequence()
                .thenExecute(() -> {
                    if (!activity.start(patron, helper.getLevel())) {
                        helper.fail("酒窖有奶，start 应成功");
                    }
                })
                .thenWaitUntil(() -> {
                    activity.tick(patron, helper.getLevel());
                    if (!(patron.getVehicle() instanceof com.bmf.bmfstown.entity.SeatEntity)) {
                        throw new GameTestAssertException("等待客人坐上楼梯座位");
                    }
                    if (!com.bmf.bmfstown.entity.TavernOrders.hasOrderNear(
                            helper.getLevel(), java.util.List.of(anchor))) {
                        throw new GameTestAssertException("坐下后应立即点单");
                    }
                })
                .thenWaitUntil(() -> {
                    activity.tick(patron, helper.getLevel());
                    if (!hasTavernThought(patron)) {
                        throw new GameTestAssertException("等待喝下这杯（到手即喝）");
                    }
                })
                .thenWaitUntil(() -> {
                    activity.tick(patron, helper.getLevel());
                    if (countItem(s.chest(helper), Items.BUCKET) < 1) {
                        throw new GameTestAssertException("等待空桶还回酒窖（还杯铁律）");
                    }
                })
                .thenWaitUntil(() -> {
                    activity.tick(patron, helper.getLevel());
                    if (!patron.isPassenger()) {
                        throw new GameTestAssertException("还完杯应回座位坐下逗留");
                    }
                })
                .thenExecute(() -> {
                    activity.abort(patron, helper.getLevel());
                    if (patron.isPassenger()) {
                        helper.fail("abort 后客人应起身");
                    }
                    if (!helper.getLevel().getEntitiesOfClass(
                            com.bmf.bmfstown.entity.SeatEntity.class,
                            new net.minecraft.world.phys.AABB(new BlockPos(0, 0, 0))
                                    .inflate(64.0D)).isEmpty()) {
                        helper.fail("abort 后座位实体应被拆除");
                    }
                })
                .thenSucceed();
    }

    /** 反胃药水也是酒馆饮品（#64 拍板"喝大了也是生活"）：喝下真反胃 + 空瓶回窖。 */
    @GameTest(template = "room11x4x11", timeoutTicks = 12000)
    public static void patronDrinksNauseaPotion(GameTestHelper helper) {
        AlchemyScaffold.Setup s = AlchemyScaffold.prepareTown(
                helper, new BlockPos(1, 1, 1), new BlockPos(11, 1, 9), 0);
        Resident patron = helper.spawn(ModEntities.RESIDENT.get(), new BlockPos(6, 1, 5));
        tavern(s, helper, patron);
        var chest = s.chest(helper);
        chest.setItem(0, net.minecraft.world.item.alchemy.PotionContents
                .createItemStack(Items.POTION, com.bmf.bmfstown.alchemy.ModPotions.NAUSEA));
        chest.setChanged();

        com.bmf.bmfstown.leisure.TavernVisitActivity activity =
                new com.bmf.bmfstown.leisure.TavernVisitActivity();
        helper.startSequence()
                .thenExecute(() -> {
                    if (!activity.start(patron, helper.getLevel())) {
                        helper.fail("反胃药水应被认作饮品，start 应成功");
                    }
                })
                .thenWaitUntil(() -> {
                    activity.tick(patron, helper.getLevel());
                    if (!patron.hasEffect(net.minecraft.world.effect.MobEffects.CONFUSION)) {
                        throw new GameTestAssertException("等待镇民喝下反胃特调");
                    }
                })
                .thenWaitUntil(() -> {
                    activity.tick(patron, helper.getLevel());
                    if (countItem(s.chest(helper), Items.GLASS_BOTTLE) < 1) {
                        throw new GameTestAssertException("等待空瓶还回酒窖（还杯铁律）");
                    }
                })
                .thenSucceed();
    }

    // ==========================================================
    //  酒保营业：酿造循环 / 接瓶入窖（决策 #65，BAC 真数据）
    // ==========================================================

    /**
     * 酿造循环（决策 #65）：酒窖备水桶+啤酒配料 → 酒保取料投进空酒桶 →
     * 基液入桶、配料入槽（发酵由 BAC 每刻自动推进）→ <b>余料空桶回窖</b>。
     */
    @GameTest(template = "room11x4x11", timeoutTicks = 12000)
    public static void keeperBrewsBeerFromSupplies(GameTestHelper helper) {
        AlchemyScaffold.Setup s = AlchemyScaffold.prepareTown(
                helper, new BlockPos(1, 1, 1), new BlockPos(11, 1, 9), 0);
        Resident keeper = helper.spawn(ModEntities.RESIDENT.get(), new BlockPos(4, 1, 5));
        tavern(s, helper, keeper);
        BlockPos kegAbs = placeKegOrSkip(helper, new BlockPos(9, 1, 4));
        if (kegAbs == null) {
            return;
        }
        var chest = s.chest(helper);
        // 啤酒配方（beer_from_water）：水桶 + 小麦 + 小麦种子 + 棕色蘑菇
        chest.setItem(0, new ItemStack(Items.WATER_BUCKET));
        chest.setItem(1, new ItemStack(Items.WHEAT));
        chest.setItem(2, new ItemStack(Items.WHEAT_SEEDS));
        chest.setItem(3, new ItemStack(Items.BROWN_MUSHROOM));
        chest.setChanged();

        helper.startSequence()
                // 1) 基液入桶
                .thenWaitUntil(() -> {
                    var snap = com.bmf.bmfstown.entity.KegAccess.read(
                            helper.getLevel(), kegAbs);
                    if (snap == null || snap.fluid() == null
                            || !"minecraft:water".equals(snap.fluid().fluidId())) {
                        throw new GameTestAssertException("等待酒保把水倒进酒桶");
                    }
                })
                // 2) 配料入槽
                .thenWaitUntil(() -> {
                    var snap = com.bmf.bmfstown.entity.KegAccess.read(
                            helper.getLevel(), kegAbs);
                    if (snap == null || com.bmf.bmfstown.entity.KegAccess
                            .nonEmpty(snap.slots()).size() < 3) {
                        throw new GameTestAssertException("等待酒保把啤酒配料投进酒桶");
                    }
                })
                // 3) 余料（空桶）回窖——还杯铁律同源
                .thenWaitUntil(() -> {
                    if (countItem(s.chest(helper), Items.BUCKET) < 1) {
                        throw new GameTestAssertException("等待倒完水的空桶回窖");
                    }
                })
                .thenSucceed();
    }

    /**
     * 续桶（2026-11-18 用户拍板 #82，炼金 reconcileStand 同思想）：桶里已灌了
     * 半桶基液（模拟投料中途被打断的现场——旧实现把这桶当死桶永远绕开）→
     * 酒保必须认出这是可续的半成品，<b>续倒差额基液 + 投配料补完</b>，空桶照常回窖。
     */
    @GameTest(template = "room11x4x11", timeoutTicks = 12000)
    public static void keeperResumesInterruptedKeg(GameTestHelper helper) {
        AlchemyScaffold.Setup s = AlchemyScaffold.prepareTown(
                helper, new BlockPos(1, 1, 1), new BlockPos(11, 1, 9), 0);
        Resident keeper = helper.spawn(ModEntities.RESIDENT.get(), new BlockPos(4, 1, 5));
        tavern(s, helper, keeper);
        BlockPos kegAbs = placeKegOrSkip(helper, new BlockPos(9, 1, 4));
        if (kegAbs == null) {
            return;
        }
        var level = helper.getLevel();
        // 预投半桶水（500mB = 配方 1000mB 的一半——"灌了一半被打断"的定格）
        if (!com.bmf.bmfstown.entity.KegAccess.pourBaseFluid(level, kegAbs,
                "minecraft:water", 500L * com.bmf.bmfstown.entity.KegAccess.DROPLETS_PER_MB)) {
            helper.fail("预投半桶水失败（BAC 应已加载）");
        }
        var chest = s.chest(helper);
        // 啤酒配方（beer_from_water）全部差额料：1 水桶（补半桶差额）+ 3 配料
        chest.setItem(0, new ItemStack(Items.WATER_BUCKET));
        chest.setItem(1, new ItemStack(Items.WHEAT));
        chest.setItem(2, new ItemStack(Items.WHEAT_SEEDS));
        chest.setItem(3, new ItemStack(Items.BROWN_MUSHROOM));
        chest.setChanged();

        helper.startSequence()
                // 1) 配料被续投进这只【非空】桶——旧实现对非空桶绝不投料，
                //    配料进得来就证明走的是续桶路径
                .thenWaitUntil(() -> {
                    var snap = com.bmf.bmfstown.entity.KegAccess.read(level, kegAbs);
                    if (snap == null || com.bmf.bmfstown.entity.KegAccess
                            .nonEmpty(snap.slots()).size() < 3) {
                        throw new GameTestAssertException("等待酒保续投配料（半成品桶应被接上）");
                    }
                })
                // 2) 基液补足整桶 1000mB（半桶 500mB + 续倒 1 桶），空桶回窖
                .thenExecute(() -> {
                    var snap = com.bmf.bmfstown.entity.KegAccess.read(level, kegAbs);
                    if (snap == null || snap.fluid() == null
                            || snap.fluid().droplets() != 1000L
                                    * com.bmf.bmfstown.entity.KegAccess.DROPLETS_PER_MB) {
                        helper.fail("续桶后基液应补足整桶 1000mB（实际 "
                                + (snap == null || snap.fluid() == null
                                        ? "无流体" : snap.fluid().droplets() + " droplets）"));
                    }
                    if (!"minecraft:water".equals(snap.fluid().fluidId())) {
                        helper.fail("流体 id 异常：" + snap.fluid().fluidId());
                    }
                    if (countItem(s.chest(helper), Items.BUCKET) < 1) {
                        helper.fail("续倒完的空桶没回窖（余料归还失效）");
                    }
                })
                .thenSucceed();
    }

    /**
     * 排空入窖（2026-11-18 用户拍板"桶里有多少就取多少"）：酒桶里有酿好的
     * 酒（直接写啤酒流体 1000mB=4 杯）→ 酒保整杯全接入窖（受空杯数限制）。
     * 守恒断言：窖瓶 + 桶剩整杯数 == 4——不关心脚手架有几个空杯、酒保搬到
     * 哪一步，任何静止时刻都成立（搬运途中瓶子在货列里，窗口瞬时，等到
     * 守恒式成立即可）。
     */
    @GameTest(template = "room11x4x11", timeoutTicks = 12000)
    public static void keeperDrainsKegIntoCellar(GameTestHelper helper) {
        AlchemyScaffold.Setup s = AlchemyScaffold.prepareTown(
                helper, new BlockPos(1, 1, 1), new BlockPos(11, 1, 9), 0);
        Resident keeper = helper.spawn(ModEntities.RESIDENT.get(), new BlockPos(4, 1, 5));
        tavern(s, helper, keeper);
        BlockPos kegAbs = placeKegOrSkip(helper, new BlockPos(9, 1, 4));
        if (kegAbs == null) {
            return;
        }
        var level = helper.getLevel();
        // 直接写一整桶啤酒流体（1000mB = 4 杯量）
        if (!com.bmf.bmfstown.entity.KegAccess.pourBaseFluid(level, kegAbs,
                "brewinandchewin:beer",
                1000L * com.bmf.bmfstown.entity.KegAccess.DROPLETS_PER_MB)) {
            helper.fail("啤酒流体写入失败（BAC 应已加载）");
        }
        Item beer = BuiltInRegistries.ITEM.get(
                ResourceLocation.fromNamespaceAndPath("brewinandchewin", "beer"));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (countItem(s.chest(helper), beer) < 3) {
                        throw new GameTestAssertException("等待酒保排空接瓶入窖（啤酒 ≥3）");
                    }
                })
                .thenWaitUntil(() -> {
                    var snap = com.bmf.bmfstown.entity.KegAccess.read(level, kegAbs);
                    if (snap == null) {
                        helper.fail("酒桶 BE 读取失败");
                        return;
                    }
                    long left = snap.fluid() == null ? 0
                            : snap.fluid().droplets()
                                    / com.bmf.bmfstown.entity.KegAccess.TANKARD_DROPLETS;
                    long inCellar = countItem(s.chest(helper), beer);
                    if (inCellar + left != 4) {
                        // 搬运途中瓶子在货列里，守恒式暂时不成立——继续等静止
                        throw new GameTestAssertException("排空守恒未定：窖 " + inCellar
                                + " + 桶剩 " + left + " ≠ 4");
                    }
                })
                .thenSucceed();
    }

    // ==========================================================
    //  秘传双通道（决策 #64）
    // ==========================================================

    /**
     * 酒保通道（决策 #64）：掷骰概率临时拉满 → 酒保把一本反胃秘传<b>成书</b>
     * 放进酒窖，且放下即全局解锁。测完把配置还原（两通道共用上限，先放大防
     * 其它测试的计数干扰）。
     */
    @GameTest(template = "room11x4x11", timeoutTicks = 6000)
    public static void tavernkeeperIssuesNauseaSecret(GameTestHelper helper) {
        AlchemyScaffold.Setup s = AlchemyScaffold.prepareTown(
                helper, new BlockPos(1, 1, 1), new BlockPos(11, 1, 9), 0);
        // 概率/上限必须在酒保出生【之前】设好（2026-11-16 #68 回归实证）：
        // 酒保出生下一 tick 就会 SCAN 掷骰——若还停在默认 5% 而落空，
        // "落空也记账"的每日闸门会把本镇当天锁死，之后只能靠别的测试推日
        // 翻天才翻身，赶不上 6000t 超时就是抖动失败
        com.bmf.bmfstown.config.Config.NAUSEA_ASK_CHANCE.set(100);
        com.bmf.bmfstown.config.Config.NAUSEA_BOOK_CAP.set(1000);
        Resident keeper = helper.spawn(ModEntities.RESIDENT.get(), new BlockPos(4, 1, 5));
        tavern(s, helper, keeper);

        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (findUnlockBook(helper, s,
                            com.bmf.bmfstown.alchemy.NauseaSecretLedger.NAUSEA_UNLOCK) == null) {
                        throw new GameTestAssertException("等待酒保把反胃秘传放进酒窖");
                    }
                })
                .thenExecute(() -> {
                    if (!com.bmf.bmfstown.alchemy.AlchemyGate.isOpen(
                            com.bmf.bmfstown.alchemy.NauseaSecretLedger.NAUSEA_UNLOCK)) {
                        helper.fail("秘传书已入窖但全局酿造门禁没开");
                    }
                    com.bmf.bmfstown.config.Config.NAUSEA_ASK_CHANCE.set(5);
                    com.bmf.bmfstown.config.Config.NAUSEA_BOOK_CAP.set(2);
                })
                .thenSucceed();
    }

    /**
     * 写书通道（决策 #64）：概率临时拉满 → 学者本次开写被定死为反胃秘传；
     * 同镇同日第二次开写必须落空回落闲书（每日闸门）。测完还原配置。
     * set+掷骰+还原收进同一个回调（原子窗口）：全服静态 Config 被并行用例
     * 共享，分回调之间有空隙，别的用例中途还原概率/上限会打死本次命中
     * （#67 实证抖动）。
     */
    @GameTest(template = "room11x4x11", timeoutTicks = 2000)
    public static void writeChannelForcesNauseaDraft(GameTestHelper helper) {
        AlchemyScaffold.Setup s = AlchemyScaffold.prepareTown(
                helper, new BlockPos(1, 1, 1), new BlockPos(11, 1, 9), 0);
        helper.startSequence()
                .thenExecute(() -> {
                    var level = helper.getLevel();
                    var townId = s.town().id();
                    com.bmf.bmfstown.config.Config.NAUSEA_WRITE_CHANCE.set(100);
                    // 上限一起放大（1000，理由同酒保通道用例）：全服计数跨测试
                    // 共享，并行推日风暴下默认上限会被静默吃掉本次命中
                    com.bmf.bmfstown.config.Config.NAUSEA_BOOK_CAP.set(1000);
                    var ledger = com.bmf.bmfstown.alchemy.NauseaSecretLedger.get(level);
                    int issuedBefore = ledger.issuedCount();
                    var d1 = com.bmf.bmfstown.book.BookDrafting.roll(
                            level, townId, net.minecraft.util.RandomSource.create(), null);
                    if (!(d1 instanceof com.bmf.bmfstown.book.BookDraft.Unlock u)
                            || !com.bmf.bmfstown.alchemy.NauseaSecretLedger.NAUSEA_UNLOCK
                                    .equals(u.unlockId())) {
                        helper.fail("命中写书通道却没定死反胃秘传（实际 " + d1 + "）");
                    }
                    var d2 = com.bmf.bmfstown.book.BookDrafting.roll(
                            level, townId, net.minecraft.util.RandomSource.create(), null);
                    // 每日闸门的机制精确断言（2026-11-16 修正：旧断言"d2 再出反胃
                    // 就算闸门失效"是错的——d1 命中后奖池自动补登记反胃，d2 从
                    // 奖池【正常抽中】反胃是合法抽取，与闸门无关。通道是唯一会动
                    // 全服 issued 计数的路径：闸门拦住第二次 roll ⟺ issued 不变）
                    if (com.bmf.bmfstown.alchemy.NauseaSecretLedger.get(level)
                            .issuedCount() != issuedBefore + 1) {
                        helper.fail("同镇同日二次写书仍走到了秘传通道（每日闸门失效）");
                    }
                    com.bmf.bmfstown.config.Config.NAUSEA_WRITE_CHANCE.set(2);
                    com.bmf.bmfstown.config.Config.NAUSEA_BOOK_CAP.set(2);
                })
                .thenSucceed();
    }

    /** 酒窖里找 unlockId 匹配的成书；无则 null。 */
    private static ItemStack findUnlockBook(GameTestHelper helper, AlchemyScaffold.Setup s,
            String unlockId) {
        var chest = s.chest(helper);
        if (chest == null) {
            return null;
        }
        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack st = chest.getItem(i);
            var content = st.get(com.bmf.bmfstown.ModDataComponents.BOOK_CONTENT.get());
            if (content != null && content.draft() instanceof com.bmf.bmfstown.book.BookDraft.Unlock u
                    && u.unlockId().equals(unlockId)) {
                return st;
            }
        }
        return null;
    }

    // ==========================================================
    //  续桶对账纯函数用例（2026-11-18 #82——不依赖 BAC BE，永远真跑；
    //  酒桶 BE 用例在本环境结构性自跳过，见 placeKegOrSkip 追记）
    // ==========================================================

    /** 测试配方：啤酒口径（水 1000mB=整桶×1 + 小麦/小麦种子/棕蘑菇 各 1）。 */
    private static com.bmf.bmfstown.entity.KegRecipes.FermentRecipe beerRecipe() {
        return new com.bmf.bmfstown.entity.KegRecipes.FermentRecipe(
                ResourceLocation.fromNamespaceAndPath("bmfstown", "test_beer"),
                "brewinandchewin:beer", 1, true,
                "minecraft:water", 1000,
                Items.WATER_BUCKET,
                1000L * com.bmf.bmfstown.entity.KegAccess.DROPLETS_PER_MB,
                false, 0,
                List.of(ing(new ItemStack(Items.WHEAT)),
                        ing(new ItemStack(Items.WHEAT_SEEDS)),
                        ing(new ItemStack(Items.BROWN_MUSHROOM))));
    }

    /**
     * 测试配方：酒倒酒口径（#85 阶段一）——基液=酒（1000mB 配方 / 每份 250mB
     * 累加 = 4 杯），基液源用苹果模拟"酒窖里的瓶装酒"（纯函数不查注册表）。
     */
    private static com.bmf.bmfstown.entity.KegRecipes.FermentRecipe drinkBaseRecipe() {
        return new com.bmf.bmfstown.entity.KegRecipes.FermentRecipe(
                ResourceLocation.fromNamespaceAndPath("bmfstown", "test_strongroot"),
                "brewinandchewin:strongroot_ale", 1, true,
                "brewinandchewin:beer", 1000,
                Items.APPLE,
                com.bmf.bmfstown.entity.KegAccess.TANKARD_DROPLETS,
                true, 0,
                List.of(ing(new ItemStack(Items.BEETROOT)),
                        ing(new ItemStack(Items.POTATO)),
                        ing(new ItemStack(Items.BROWN_MUSHROOM))));
    }

    /** 测试配方：直酿口径（#85 阶段二）——无基液配料直投，物品产物 x3。 */
    private static com.bmf.bmfstown.entity.KegRecipes.FermentRecipe foodRecipe() {
        return new com.bmf.bmfstown.entity.KegRecipes.FermentRecipe(
                ResourceLocation.fromNamespaceAndPath("bmfstown", "test_jerky"),
                "brewinandchewin:jerky", 3, false,
                null, 0, null, 0, false, 4,
                List.of(ing(new ItemStack(Items.BEEF)),
                        ing(new ItemStack(Items.BEEF)),
                        ing(new ItemStack(Items.BEEF))));
    }

    /** 测试配料：item 直指口径（无 tag）。 */
    private static com.bmf.bmfstown.entity.KegRecipes.KegIng ing(ItemStack st) {
        return new com.bmf.bmfstown.entity.KegRecipes.KegIng(st, null);
    }

    /** 造酒桶快照（投料槽 0~3 依次填入，其余槽空；温度档默认 0=不判档）。 */
    private static com.bmf.bmfstown.entity.KegAccess.KegSnap kegSnap(
            com.bmf.bmfstown.entity.KegAccess.FluidSnap fluid, ItemStack... inputSlots) {
        return kegSnap(fluid, 0, inputSlots);
    }

    /** 同上，带温度档（#85 阶段二）。 */
    private static com.bmf.bmfstown.entity.KegAccess.KegSnap kegSnap(
            com.bmf.bmfstown.entity.KegAccess.FluidSnap fluid, int temperature,
            ItemStack... inputSlots) {
        ItemStack[] slots = new ItemStack[6];
        for (int i = 0; i < 6; i++) {
            slots[i] = ItemStack.EMPTY;
        }
        for (int i = 0; i < inputSlots.length && i < 4; i++) {
            slots[i] = inputSlots[i];
        }
        return new com.bmf.bmfstown.entity.KegAccess.KegSnap(fluid, slots, temperature);
    }

    private static com.bmf.bmfstown.entity.TavernkeepGoal.ResumeResult resume(
            com.bmf.bmfstown.entity.KegAccess.KegSnap snap,
            net.minecraft.world.SimpleContainer cellar) {
        return com.bmf.bmfstown.entity.TavernkeepGoal.computeResumePlan(
                snap, List.of(beerRecipe()), cellar);
    }

    /** 半桶基液被打断 → 续桶计划应补 1 桶差额 + 全部 3 件配料，对账基线正确。 */
    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void resumePlanTopsUpHalfBase(GameTestHelper helper) {
        var cellar = new net.minecraft.world.SimpleContainer(
                new ItemStack(Items.WATER_BUCKET), new ItemStack(Items.WHEAT),
                new ItemStack(Items.WHEAT_SEEDS), new ItemStack(Items.BROWN_MUSHROOM));
        var snap = kegSnap(new com.bmf.bmfstown.entity.KegAccess.FluidSnap(
                "minecraft:water", 500L * com.bmf.bmfstown.entity.KegAccess.DROPLETS_PER_MB));
        var rr = resume(snap, cellar);
        if (rr.plan() == null) {
            helper.fail("半桶基液应可续桶（missing=" + rr.missing() + "）");
            return;
        }
        var p = rr.plan();
        if (p.extraSources() != 1) {
            helper.fail("应补 1 桶基液差额，实际 " + p.extraSources());
        }
        if (p.extraIngredients().size() != 3
                || p.extraIngredients().get(0).displayItem() != Items.WHEAT) {
            helper.fail("应补全部 3 件配料（已投为空）");
        }
        if (p.assumedDroplets() != 500L * com.bmf.bmfstown.entity.KegAccess.DROPLETS_PER_MB
                || !"minecraft:water".equals(p.assumedFluidId())) {
            helper.fail("对账基线（流体量/id）不正确：" + p.assumedDroplets() + " " + p.assumedFluidId());
        }
        helper.succeed();
    }

    /** 超量基液（玩家手倒）/ 投了配方外配料 / 已配齐在酿——一律不碰、不报缺口。 */
    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void resumePlanRefusesForeignOverfullAndComplete(GameTestHelper helper) {
        var cellar = new net.minecraft.world.SimpleContainer(
                new ItemStack(Items.WATER_BUCKET), new ItemStack(Items.WHEAT),
                new ItemStack(Items.WHEAT_SEEDS), new ItemStack(Items.BROWN_MUSHROOM));
        var droplets = com.bmf.bmfstown.entity.KegAccess.DROPLETS_PER_MB;
        // ① 基液超配方量（1500mB > 1000mB）
        var over = kegSnap(new com.bmf.bmfstown.entity.KegAccess.FluidSnap(
                "minecraft:water", 1500L * droplets));
        if (resume(over, cellar).plan() != null) {
            helper.fail("基液超量（多半是玩家手倒）不应被接手");
        }
        // ② 配方外配料（金胡萝卜）混进投料槽
        var foreign = kegSnap(new com.bmf.bmfstown.entity.KegAccess.FluidSnap(
                        "minecraft:water", 500L * droplets),
                new ItemStack(Items.WHEAT), new ItemStack(Items.GOLDEN_CARROT));
        var rrForeign = resume(foreign, cellar);
        if (rrForeign.plan() != null || rrForeign.missing() != null) {
            helper.fail("投了配方外配料不应被接手，也不该报缺口（尊重玩家操作）");
        }
        // ③ 别种流体（蜂蜜 ≠ 水基）
        var wrongFluid = kegSnap(new com.bmf.bmfstown.entity.KegAccess.FluidSnap(
                "brewinandchewin:honey", 250L * droplets));
        if (resume(wrongFluid, cellar).plan() != null) {
            helper.fail("别种流体不该被水基配方接手");
        }
        // ④ 已配齐在酿（足量基液+全部配料）——归在途，非续桶
        var complete = kegSnap(new com.bmf.bmfstown.entity.KegAccess.FluidSnap(
                        "minecraft:water", 1000L * droplets),
                new ItemStack(Items.WHEAT), new ItemStack(Items.WHEAT_SEEDS),
                new ItemStack(Items.BROWN_MUSHROOM));
        var rrComplete = resume(complete, cellar);
        if (rrComplete.plan() != null || rrComplete.missing() != null) {
            helper.fail("已配齐在酿的桶既非续桶也不该报缺口");
        }
        helper.succeed();
    }

    /** 差额补不起（空酒窖）→ 不接手，缺口点名水桶（播报更精准的依据）。 */
    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void resumePlanMissingMaterialsReportsGap(GameTestHelper helper) {
        var cellar = new net.minecraft.world.SimpleContainer(); // 空酒窖
        var snap = kegSnap(new com.bmf.bmfstown.entity.KegAccess.FluidSnap(
                "minecraft:water", 500L * com.bmf.bmfstown.entity.KegAccess.DROPLETS_PER_MB));
        var rr = resume(snap, cellar);
        if (rr.plan() != null) {
            helper.fail("差额补不起不应接手（否则取一半卡半路又是死局）");
        }
        if (rr.missing() == null
                || rr.missing().stream().noneMatch(st -> st.is(Items.WATER_BUCKET))) {
            helper.fail("缺口应点名水桶（半成品差额播报）");
        }
        helper.succeed();
    }

    /** 新批次计划 = 进度为零的续桶：假定桶全空、取足量基液+全部配料。 */
    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void freshPlanAssumesEmptyKeg(GameTestHelper helper) {
        var p = com.bmf.bmfstown.entity.TavernkeepGoal.BrewPlan.fresh(beerRecipe());
        if (p.extraSources() != 1) {
            helper.fail("新批次应取足量基液（1 桶），实际 " + p.extraSources());
        }
        if (p.extraIngredients().size() != 3) {
            helper.fail("新批次应取全部配料");
        }
        if (p.assumedDroplets() != -1L || p.assumedFluidId() != null
                || !p.assumedLoaded().isEmpty()) {
            helper.fail("新批次应假定桶全空（流体无+投料槽空）");
        }
        helper.succeed();
    }

    /**
     * #83 接酒容器口径：BAC 酒的容器 = 饮品自己的合成余料（tankard），
     * 未知流体回落玻璃瓶。BAC 不在（物品未注册）时跳过。
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void kegDrawContainerFollowsDrink(GameTestHelper helper) {
        var tankard = BuiltInRegistries.ITEM.getOptional(
                ResourceLocation.fromNamespaceAndPath("brewinandchewin", "tankard"));
        if (tankard.isEmpty()) {
            helper.succeed(); // BAC 未加载：口径用例无从验证，跳过
            return;
        }
        if (com.bmf.bmfstown.entity.KegAccess.containerForFluid("brewinandchewin:beer")
                != tankard.get()) {
            helper.fail("接啤酒应耗空酒杯 tankard（合成余料口径，#83）");
        }
        if (com.bmf.bmfstown.entity.KegAccess.containerForFluid("minecraft:does_not_exist")
                != Items.GLASS_BOTTLE) {
            helper.fail("未知流体应回落玻璃瓶");
        }
        helper.succeed();
    }

    // ==========================================================
    //  #85 二阶段扩展纯函数用例（温度档 / 酒倒酒续桶 / 直酿续桶）
    // ==========================================================

    /**
     * 温度档匹配全表（照抄 BAC isValidTemp 字节码，#85 阶段二）：
     * 1→≤1 / 2→≤2 / 3→1~4 / 4→≥4 / 5→≥5 / 0（无要求）→恒真。
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void tempOkMatchesBacBytecode(GameTestHelper helper) {
        for (int keg = 0; keg <= 6; keg++) {
            if (!com.bmf.bmfstown.entity.KegAccess.tempOk(keg, 0)) {
                helper.fail("无温度要求（0）应对任意桶温恒真（桶温=" + keg + "）");
            }
            if (com.bmf.bmfstown.entity.KegAccess.tempOk(keg, 1) != (keg <= 1)) {
                helper.fail("档1 应为 keg≤1（桶温=" + keg + "）");
            }
            if (com.bmf.bmfstown.entity.KegAccess.tempOk(keg, 2) != (keg <= 2)) {
                helper.fail("档2 应为 keg≤2（桶温=" + keg + "）");
            }
            if (com.bmf.bmfstown.entity.KegAccess.tempOk(keg, 3) != (keg >= 1 && keg < 5)) {
                helper.fail("档3 应为 1≤keg<5（桶温=" + keg + "）");
            }
            if (com.bmf.bmfstown.entity.KegAccess.tempOk(keg, 4) != (keg >= 4)) {
                helper.fail("档4 应为 keg≥4（桶温=" + keg + "）");
            }
            if (com.bmf.bmfstown.entity.KegAccess.tempOk(keg, 5) != (keg >= 5)) {
                helper.fail("档5 应为 keg≥5（桶温=" + keg + "）");
            }
        }
        helper.succeed();
    }

    /** 直酿配方（无基液）：仅配料半成品 → 补全配料；配料全齐在酿 → 不接手。 */
    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void resumePlanDirectFermentFood(GameTestHelper helper) {
        var cellar = new net.minecraft.world.SimpleContainer(
                new ItemStack(Items.BEEF));
        // 半成品：只投了 1 份牛肉（配方要 3 份）→ 补 2 份，基液差额为 0
        var half = kegSnap(null, new ItemStack(Items.BEEF));
        var rr = com.bmf.bmfstown.entity.TavernkeepGoal.computeResumePlan(
                half, List.of(foodRecipe()), cellar);
        if (rr.plan() == null) {
            helper.fail("直酿半成品应可续桶（missing=" + rr.missing() + "）");
            return;
        }
        var p = rr.plan();
        if (p.extraSources() != 0) {
            helper.fail("直酿配方不应有基液份数，实际 " + p.extraSources());
        }
        if (p.extraIngredients().size() != 2) {
            helper.fail("应补 2 条差额（配方 3 条牛肉逐条扣额度，已投 1 条），实际 "
                    + p.extraIngredients());
        }
        int beefTotal = (int) p.extraIngredients().stream()
                .filter(ki -> ki.displayItem() == Items.BEEF).count();
        if (beefTotal != 2) {
            helper.fail("差额牛肉总数应为 2 份，实际 " + beefTotal);
        }
        // 配料全齐在酿 → 已配齐，不接手也不报缺口
        var complete = kegSnap(null, new ItemStack(Items.BEEF),
                new ItemStack(Items.BEEF), new ItemStack(Items.BEEF));
        var rr2 = com.bmf.bmfstown.entity.TavernkeepGoal.computeResumePlan(
                complete, List.of(foodRecipe()), cellar);
        if (rr2.plan() != null || rr2.missing() != null) {
            helper.fail("直酿已配齐在酿的桶既非续桶也不该报缺口");
        }
        helper.succeed();
    }

    /**
     * 酒倒酒配方（#85 阶段一）：基液=酒（250mB/杯累加）——半桶基酒 →
     * 补差额杯数 + 全部配料；基酒别种流体不接手。
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void resumePlanDrinkBaseTopsUp(GameTestHelper helper) {
        var cellar = new net.minecraft.world.SimpleContainer(
                new ItemStack(Items.APPLE), new ItemStack(Items.APPLE),
                new ItemStack(Items.APPLE),
                new ItemStack(Items.BEETROOT), new ItemStack(Items.POTATO),
                new ItemStack(Items.BROWN_MUSHROOM));
        var half = kegSnap(new com.bmf.bmfstown.entity.KegAccess.FluidSnap(
                "brewinandchewin:beer", 500L * com.bmf.bmfstown.entity.KegAccess.DROPLETS_PER_MB));
        var rr = com.bmf.bmfstown.entity.TavernkeepGoal.computeResumePlan(
                half, List.of(drinkBaseRecipe()), cellar);
        if (rr.plan() == null) {
            helper.fail("半桶基酒应可续桶（missing=" + rr.missing() + "）");
            return;
        }
        var p = rr.plan();
        if (p.extraSources() != 2) {
            helper.fail("500mB/250mB 应补 2 杯基酒，实际 " + p.extraSources());
        }
        if (p.extraIngredients().size() != 3) {
            helper.fail("应补全部 3 件配料（已投为空）");
        }
        // 基酒超量（玩家手倒 1500mB > 1000mB 配方量）：不碰
        var over = kegSnap(new com.bmf.bmfstown.entity.KegAccess.FluidSnap(
                "brewinandchewin:beer", 1500L * com.bmf.bmfstown.entity.KegAccess.DROPLETS_PER_MB));
        if (com.bmf.bmfstown.entity.TavernkeepGoal.computeResumePlan(
                over, List.of(drinkBaseRecipe()), cellar).plan() != null) {
            helper.fail("基酒超量不应被接手（尊重玩家手动操作）");
        }
        // 别种流体（水 ≠ 啤酒基）
        var wrong = kegSnap(new com.bmf.bmfstown.entity.KegAccess.FluidSnap(
                "minecraft:water", 500L * com.bmf.bmfstown.entity.KegAccess.DROPLETS_PER_MB));
        if (com.bmf.bmfstown.entity.TavernkeepGoal.computeResumePlan(
                wrong, List.of(drinkBaseRecipe()), cellar).plan() != null) {
            helper.fail("水基桶不该被酒基配方接手");
        }
        helper.succeed();
    }

    /** 新批次口径（#85）：直酿配方 extraSources=0；酒倒酒配方 4 杯；温度档进计划外判定。 */
    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void freshPlanCoversNewRecipeShapes(GameTestHelper helper) {
        var food = com.bmf.bmfstown.entity.TavernkeepGoal.BrewPlan.fresh(foodRecipe());
        if (food.extraSources() != 0 || food.extraIngredients().size() != 3) {
            helper.fail("直酿新批次应取 0 份基液+全部配料");
        }
        var drink = com.bmf.bmfstown.entity.TavernkeepGoal.BrewPlan.fresh(drinkBaseRecipe());
        if (drink.extraSources() != 4) {
            helper.fail("酒倒酒新批次（1000mB/250mB 每杯）应取 4 杯基酒，实际 "
                    + drink.extraSources());
        }
        helper.succeed();
    }

    /**
     * 水源站取水计划（#87 口径 + #101 混用）：<b>现成水桶 &gt; 现成水瓶；
     * 有水源站时水瓶+空瓶按总水量混用</b>（水瓶优先、差额空瓶到站装）；
     * 全不满足=不可行。1000mB=1 桶=4 瓶。
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void waterPlanPriority(GameTestHelper helper) {
        var p = com.bmf.bmfstown.entity.TavernkeepGoal.planWater(1000, 1, 0, 0, false);
        if (!p.feasible() || p.buckets() != 1 || p.waterBottles() != 0
                || p.emptyToFill() != 0) {
            helper.fail("桶够应走桶路径（1 桶），实际 " + p);
        }
        p = com.bmf.bmfstown.entity.TavernkeepGoal.planWater(1000, 0, 4, 9, false);
        if (!p.feasible() || p.waterBottles() != 4 || p.needsStation()) {
            helper.fail("无桶有水瓶应走水瓶路径（4 瓶、不去站），实际 " + p);
        }
        // 混用（#101）：水瓶不够时用现成水瓶优先、差额取空瓶到站装
        p = com.bmf.bmfstown.entity.TavernkeepGoal.planWater(1000, 0, 2, 4, true);
        if (!p.feasible() || p.waterBottles() != 2 || p.emptyToFill() != 2
                || !p.needsStation()) {
            helper.fail("2 水瓶+4 空瓶+站应混用（2 现成+2 装水），实际 " + p);
        }
        // 用户实测场景（2026-11-20）：打断归窖的 3 水瓶+1 空瓶，合计正好 1000mB——
        // 旧单模式口径误判缺料停工
        p = com.bmf.bmfstown.entity.TavernkeepGoal.planWater(1000, 0, 3, 1, true);
        if (!p.feasible() || p.waterBottles() != 3 || p.emptyToFill() != 1) {
            helper.fail("3 水瓶+1 空瓶+站必须可行（3 现成+1 装水），实际 " + p);
        }
        p = com.bmf.bmfstown.entity.TavernkeepGoal.planWater(1000, 0, 2, 1, true);
        if (p.feasible()) {
            helper.fail("2 水瓶+1 空瓶（3<4 瓶水量）即使有站也不可行，实际 " + p);
        }
        p = com.bmf.bmfstown.entity.TavernkeepGoal.planWater(1000, 0, 0, 9, false);
        if (p.feasible() || p.needsStation()) {
            helper.fail("没登记水源站空瓶再多也不可行（老路径=水桶），实际 " + p);
        }
        p = com.bmf.bmfstown.entity.TavernkeepGoal.planWater(500, 0, 2, 0, false);
        if (!p.feasible() || p.waterBottles() != 2) {
            helper.fail("500mB 应折 2 瓶（向上取整），实际 " + p);
        }
        helper.succeed();
    }

    /**
     * 配料 tag 感知（#88，2026-11-18 实测：肉干配料 {@code foods/jerky_meat}
     * =腐肉+各种生肉，旧实现锁"tag 首成员"——囤生牛肉就永远缺料）：
     * tag 匹配任意成员 + 续桶对账认玩家手投的 tag 成员。
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void kegIngredientTagMatchesAnyMember(GameTestHelper helper) {
        var planks = net.minecraft.tags.TagKey.create(
                net.minecraft.core.registries.Registries.ITEM,
                ResourceLocation.withDefaultNamespace("planks"));
        var spec = new com.bmf.bmfstown.entity.KegRecipes.KegIng(
                new ItemStack(Items.OAK_PLANKS), planks);
        if (!spec.matches(new ItemStack(Items.SPRUCE_PLANKS))) {
            helper.fail("tag 配料应匹配任意成员（云杉木板）");
        }
        if (!spec.matches(new ItemStack(Items.OAK_PLANKS))) {
            helper.fail("tag 配料应匹配代表物品（橡木木板）");
        }
        if (spec.matches(new ItemStack(Items.STONE)) || spec.matches(ItemStack.EMPTY)) {
            helper.fail("tag 配料不应匹配 tag 外物品/空堆");
        }
        // 续桶对账：配方要 3 条 tag 配料，玩家手投了 1 块云杉木板 →
        // 补 2 条差额，且差额能用橡木板从酒窖补出（成员不锁死）
        var food = new com.bmf.bmfstown.entity.KegRecipes.FermentRecipe(
                ResourceLocation.fromNamespaceAndPath("bmfstown", "test_tagfood"),
                "brewinandchewin:jerky", 3, false, null, 0, null, 0, false, 0,
                List.of(spec, spec, spec));
        var cellar = new net.minecraft.world.SimpleContainer(new ItemStack(Items.OAK_PLANKS));
        var half = kegSnap(null, new ItemStack(Items.SPRUCE_PLANKS));
        var rr = com.bmf.bmfstown.entity.TavernkeepGoal.computeResumePlan(
                half, List.of(food), cellar);
        if (rr.plan() == null || rr.plan().extraIngredients().size() != 2) {
            helper.fail("tag 配料半成品应可续桶补 2 条差额（missing=" + rr.missing() + "）");
        }
        helper.succeed();
    }

    /**
     * 排空优先的闸门（2026-11-18 用户拍板"桶里有多少就取多少"的配套）：
     * 发酵中/投料中途的二层酒桶（基液酒流体+配料槽全是该配方配料）必须被
     * 接瓶通道跳过——否则刚倒进去的啤酒又被接回窖、配料晾在桶里，倒出/接回
     * 死循环；配料槽全空的同流体桶（成品酒/倒完基液没投料）不拦。
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void midBrewKegGate(GameTestHelper helper) {
        var droplets = com.bmf.bmfstown.entity.KegAccess.DROPLETS_PER_MB;
        var recipes = List.of(drinkBaseRecipe(), beerRecipe());
        // ① 发酵中的强根艾尔：啤酒流体+配料齐 → 必须拦
        var fermenting = kegSnap(new com.bmf.bmfstown.entity.KegAccess.FluidSnap(
                        "brewinandchewin:beer", 1000L * droplets),
                new ItemStack(Items.BEETROOT), new ItemStack(Items.POTATO),
                new ItemStack(Items.BROWN_MUSHROOM));
        if (!com.bmf.bmfstown.entity.TavernkeepGoal.midBrewKeg(fermenting, recipes)) {
            helper.fail("发酵中的二层酒桶（基液+配料）应被拦下");
        }
        // ② 成品啤酒桶（流体+配料槽全空）→ 不拦，排空
        var finished = kegSnap(new com.bmf.bmfstown.entity.KegAccess.FluidSnap(
                "brewinandchewin:beer", 1000L * droplets));
        if (com.bmf.bmfstown.entity.TavernkeepGoal.midBrewKeg(finished, recipes)) {
            helper.fail("成品酒桶（配料槽空）不该被拦");
        }
        // ③ 倒完基液还没投料（半量啤酒、槽空）→ 不拦（接回窖也只是多跑一趟）
        var midPour = kegSnap(new com.bmf.bmfstown.entity.KegAccess.FluidSnap(
                "brewinandchewin:beer", 500L * droplets));
        if (com.bmf.bmfstown.entity.TavernkeepGoal.midBrewKeg(midPour, recipes)) {
            helper.fail("投料中途（槽还空）不该被拦——续桶下轮自愈");
        }
        // ④ 槽里混了配方外的东西（金胡萝卜）→ 不拦（不认为是发酵中）
        var foreign = kegSnap(new com.bmf.bmfstown.entity.KegAccess.FluidSnap(
                        "brewinandchewin:beer", 1000L * droplets),
                new ItemStack(Items.BEETROOT), new ItemStack(Items.GOLDEN_CARROT));
        if (com.bmf.bmfstown.entity.TavernkeepGoal.midBrewKeg(foreign, recipes)) {
            helper.fail("投了配方外物品的桶不该被拦（尊重玩家操作）");
        }
        // ⑤ 水基配方（啤酒本体）发酵中：流体是水、配料齐 → 水没有瓶装
        //    物品本来就不进接瓶通道，midBrewKeg 对水基配方不认（恒不拦）
        var waterBase = kegSnap(new com.bmf.bmfstown.entity.KegAccess.FluidSnap(
                        "minecraft:water", 1000L * droplets),
                new ItemStack(Items.WHEAT), new ItemStack(Items.WHEAT_SEEDS),
                new ItemStack(Items.BROWN_MUSHROOM));
        if (com.bmf.bmfstown.entity.TavernkeepGoal.midBrewKeg(waterBase, recipes)) {
            helper.fail("水基发酵桶不该由本闸门拦（接瓶通道对无瓶装流体的桶不感兴趣）");
        }
        helper.succeed();
    }

    /**
     * 温度档换算（坑 22，2026-11-18 实测"无适配桶(要温度档4)"实锤）：BAC NBT
     * {@code Temperature} 存的是<b>原始分数</b>（热源分−冷源分），档位要按配置
     * 阈值换算（默认 cold=2/chilly=1/warm=1/hot=2）——1 个 heating cask=1 分
     * =档4，把分数直接当档位喂匹配表就永远够不上档 4。
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void temperatureTierConvertsRawScore(GameTestHelper helper) {
        int[][] cases = { // {原始分数, 期望档位}（默认阈值 cold=2 chilly=1 warm=1 hot=2）
                {-3, 1}, {-2, 1}, {-1, 2}, {0, 3}, {1, 4}, {2, 5}, {5, 5}};
        for (int[] c : cases) {
            int tier = com.bmf.bmfstown.entity.KegAccess.temperatureTier(
                    c[0], com.bmf.bmfstown.entity.KegAccess.CFG_COLD,
                    com.bmf.bmfstown.entity.KegAccess.CFG_CHILLY,
                    com.bmf.bmfstown.entity.KegAccess.CFG_WARM,
                    com.bmf.bmfstown.entity.KegAccess.CFG_HOT);
            if (tier != c[1]) {
                helper.fail("分数 " + c[0] + " 应为档 " + c[1] + "，实际档 " + tier);
            }
        }
        // 交叉验证：换算出的档位喂 isValidTemp 同款表，档4 配方认 1 分温暖桶
        if (!com.bmf.bmfstown.entity.KegAccess.tempOk(
                com.bmf.bmfstown.entity.KegAccess.temperatureTier(1,
                        com.bmf.bmfstown.entity.KegAccess.CFG_COLD,
                        com.bmf.bmfstown.entity.KegAccess.CFG_CHILLY,
                        com.bmf.bmfstown.entity.KegAccess.CFG_WARM,
                        com.bmf.bmfstown.entity.KegAccess.CFG_HOT), 4)) {
            helper.fail("1 分（单 cask）换算档4 后应满足档4 配方");
        }
        helper.succeed();
    }

    /**
     * 居民属性表必须含 ATTACK_SPEED：BAC 狂暴附着物（客人喝酒喝出来的
     * Raging）tick 时无判空直接 getAttribute(ATTACK_SPEED).addTransientModifier，
     * 缺属性=居民 tick 即 NPE 崩服（2026-11-19 崩溃报告实锤）。
     */
    @GameTest(template = "room11x4x11", timeoutTicks = 60)
    public static void residentHasAttackSpeedAttribute(GameTestHelper helper) {
        Resident r = helper.spawn(ModEntities.RESIDENT.get(), new BlockPos(2, 1, 2));
        if (r.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED) == null) {
            helper.fail("居民必须注册 ATTACK_SPEED——BAC 狂暴附着物 tick 无判空直取，缺了就崩服");
        }
        helper.succeed();
    }

    /**
     * 步高 1.0（2026-11-19 实测"跳不起来"正解）：原版 0.6 上不去满格方块，
     * 1 格高的酒桶/台阶全靠寻路跳节点+楔死自救兜底。1.0=玩家自动跳跃同款，
     * collision 步进直接迈上去。马同款值；低于 1.0 一律 fail。
     */
    @GameTest(template = "room11x4x11", timeoutTicks = 60)
    public static void residentStepHeightIsOneBlock(GameTestHelper helper) {
        Resident r = helper.spawn(ModEntities.RESIDENT.get(), new BlockPos(2, 1, 2));
        double step = r.getAttribute(
                net.minecraft.world.entity.ai.attributes.Attributes.STEP_HEIGHT) == null
                ? 0.0D
                : r.getAttributeValue(
                        net.minecraft.world.entity.ai.attributes.Attributes.STEP_HEIGHT);
        if (step < 1.0D) {
            helper.fail("居民步高必须 ≥1.0（实测 " + step
                    + "）——0.6 上不去 1 格高的酒桶，楔死问题在移动层就不该发生");
        }
        helper.succeed();
    }

    /**
     * 基液倒完余料口径（2026-11-19"吞瓶"实测）：水瓶没有合成余料，
     * pourRemainder 必须补回空玻璃瓶——否则投 4 水瓶做一批酒瓶子全灭，
     * vodka 永远缺玻璃瓶、排产停手。对照组：桶类与蜂蜜瓶照旧走合成余料。
     */
    @GameTest(template = "room11x4x11", timeoutTicks = 60)
    public static void waterBottlePourReturnsEmptyBottle(GameTestHelper helper) {
        ItemStack water = TavernkeepGoal.waterBottleTemplate();
        ItemStack rem = TavernkeepGoal.pourRemainder(water);
        if (rem.isEmpty() || !rem.is(Items.GLASS_BOTTLE)) {
            helper.fail("水瓶倒完必须补回空玻璃瓶，实际 " + rem);
        }
        ItemStack bucket = new ItemStack(Items.WATER_BUCKET);
        if (!TavernkeepGoal.pourRemainder(bucket).is(Items.BUCKET)) {
            helper.fail("水桶倒完余料=空桶（合成余料口径不变）");
        }
        ItemStack honey = new ItemStack(Items.HONEY_BOTTLE);
        if (!TavernkeepGoal.pourRemainder(honey).is(Items.GLASS_BOTTLE)) {
            helper.fail("蜂蜜瓶倒完余料=玻璃瓶（合成余料口径不变）");
        }
        helper.succeed();
    }

    /**
     * 酒窖聚合视图跨容器计数/取货（#99，2026-11-20 实机根因）：旧实现排产
     * 与取料只读"最近一口容器"，物料分装在别的箱子时读数恒 0（日志实锤
     * 缺料[glass_bottle×4] 整数缺口=单容器计数 0，而瓶子和杯子明明在窖里）。
     * 两口箱子分装 2+2 瓶：聚合视图必须数出 4、取走一件必须落到对应箱子上。
     */
    @GameTest(template = "room11x4x11", timeoutTicks = 60)
    public static void cellarViewCountsAcrossContainers(GameTestHelper helper) {
        helper.setBlock(new BlockPos(2, 1, 2), net.minecraft.world.level.block.Blocks.CHEST);
        helper.setBlock(new BlockPos(8, 1, 2), net.minecraft.world.level.block.Blocks.CHEST);
        helper.startSequence().thenExecute(() -> {
            ChestBlockEntity c1 = (ChestBlockEntity) helper.getLevel()
                    .getBlockEntity(helper.absolutePos(new BlockPos(2, 1, 2)));
            ChestBlockEntity c2 = (ChestBlockEntity) helper.getLevel()
                    .getBlockEntity(helper.absolutePos(new BlockPos(8, 1, 2)));
            if (c1 == null || c2 == null) {
                helper.fail("两口箱子必须就位");
            }
            c1.setItem(0, new net.minecraft.world.item.ItemStack(
                    net.minecraft.world.item.Items.GLASS_BOTTLE, 2));
            c2.setItem(0, new net.minecraft.world.item.ItemStack(
                    net.minecraft.world.item.Items.GLASS_BOTTLE, 2));
            TavernkeepGoal.CellarView view = new TavernkeepGoal.CellarView(
                    java.util.List.of(c1, c2));
            int n = KegAccess.countItem(view,
                    new net.minecraft.world.item.ItemStack(
                            net.minecraft.world.item.Items.GLASS_BOTTLE));
            if (n != 4) {
                helper.fail("跨容器计数必须 2+2=4，实测 " + n
                        + "（单容器口径=2，正是 #99 的缺料误判）");
            }
            // 取一件：必须真落到对应箱子上（shrink 生效）
            ItemStack taken = KegAccess.takeOne(view,
                    new net.minecraft.world.item.ItemStack(
                            net.minecraft.world.item.Items.GLASS_BOTTLE));
            if (taken.isEmpty()) {
                helper.fail("聚合视图必须能跨容器取货");
            }
            if (countItem(c1, net.minecraft.world.item.Items.GLASS_BOTTLE)
                    + countItem(c2, net.minecraft.world.item.Items.GLASS_BOTTLE) != 3) {
                helper.fail("取走的瓶子必须真实扣减底层容器");
            }
        }).thenSucceed();
    }
}
