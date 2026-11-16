package com.bmf.bmfstown.gametest;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.ModEntities;
import com.bmf.bmfstown.entity.Resident;
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
     * 接瓶入窖 + 阈值（决策 #65）：酒桶里有酿好的酒（直接写啤酒流体）→
     * 酒保接瓶入窖，到目标量（默认 3）停手——桶里剩的流体留给点单现场接。
     */
    @GameTest(template = "room11x4x11", timeoutTicks = 12000)
    public static void keeperDrawsBottlesToTarget(GameTestHelper helper) {
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
                        throw new GameTestAssertException("等待酒保接瓶入窖（啤酒 ≥3）");
                    }
                })
                .thenExecute(() -> {
                    var snap = com.bmf.bmfstown.entity.KegAccess.read(level, kegAbs);
                    if (snap == null || snap.fluid() == null) {
                        helper.fail("接瓶后酒桶流体状态异常");
                    }
                    // 3 杯 = 60750 droplets，剩 20250（≥1 杯留作点单现场接）
                    if (snap.fluid().droplets() > 2
                            * com.bmf.bmfstown.entity.KegAccess.TANKARD_DROPLETS) {
                        helper.fail("超过目标量仍在接瓶（阈值失效）：剩 "
                                + snap.fluid().droplets());
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
}
