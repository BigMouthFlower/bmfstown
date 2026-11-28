package com.bmf.bmfstown.gametest;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.block.GraveStoneBlock;
import com.bmf.bmfstown.block.GraveStoneVariant;
import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.town.GraveText;
import com.bmf.bmfstown.town.GraveyardData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;

/**
 * 墓碑方块（2026-11-28 决策 #143）。
 *
 * <p>每条守一个"实现容易写错且自测会绿"的点：
 * 挖碑清位、材质往返、形状不是整块、碑文取最大三条。
 */
@GameTestHolder(BmfTown.MOD_ID)
@PrefixGameTestTemplate(false)
public class GraveStoneTests {

    /**
     * 测试用的世界历纪元。**取一个正值**，让 {@code deathDay − epochDay}
     * 落在 0 附近（碑文日期会是"公元 1 年"上下）——
     * 数据用例只关心"有没有把日期拼进去"，不关心具体几年几月。
     */
    private static final long EPOCH = 1000L;

    private record Scene(com.bmf.bmfstown.town.Town town, Resident resident) {
    }

    private static Scene scene(GameTestHelper helper) {
        var s = AlchemyScaffold.prepareTown(
                helper, new BlockPos(1, 0, 1), new BlockPos(7, 0, 7), 0);
        Resident r = helper.spawn(com.bmf.bmfstown.ModEntities.RESIDENT.get(),
                new BlockPos(3, 1, 4));
        r.setNoAi(true);
        return new Scene(s.town(), r);
    }

    // ==========================================================
    //  形状
    // ==========================================================

    /**
     * 碑不是整块：碰撞盒必须明显小于 1 格，且高度到不了顶部。
     *
     * <p><b>为什么专门测这个</b>：如果哪天有人把 {@code getShape} 删掉、
     * 退回默认整块，游戏里"看起来还行"（贴图还是碑），但玩家会撞在
     * 空气上、从上方走不过去——这种"能跑但手感不对"的退化最难发现。
     *
     * <p>反向验证：让 {@code getShape} 返回 {@code Shapes.block()} ⇒ 此条红。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void shapeIsNotFullBlock(GameTestHelper helper) {
        BlockState state = com.bmf.bmfstown.block.ModBlocks.GRAVE_STONE.get()
                .defaultBlockState()
                .setValue(GraveStoneBlock.FACING, Direction.SOUTH);
        VoxelShape shape = state.getShape(helper.getLevel(),
                helper.absolutePos(new BlockPos(1, 1, 1)));
        var box = shape.bounds();
        // ⚠ VoxelShape 的坐标是**归一化到 0~1** 的（Block.box 的 1/16 单位会被压掉），
        // 不是 0~16。第一版按 0~16 写，断言"碑应 13px"变成永远不成立。
        double volume = (box.maxX - box.minX) * (box.maxY - box.minY) * (box.maxZ - box.minZ);
        if (volume >= 1.0D) {
            helper.fail("墓碑不该占满整格，实测包围盒体积 " + volume);
        }
        if (box.maxY >= 1.0D) {
            helper.fail("碑应比整格矮（人能从上方跨过），实测顶 y=" + box.maxY);
        }
        // 碑身高度 13px ⇒ 13/16 = 0.8125
        if (box.maxY < 0.75D) {
            helper.fail("碑太矮了，设计是 13px(0.8125)，实测 " + box.maxY);
        }
        helper.succeed();
    }

    /** 四个朝向都有形状，且朝东/朝西时板沿 Z 展开（不是转错了方向）。 */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void shapeFollowsFacing(GameTestHelper helper) {
        Block block = com.bmf.bmfstown.block.ModBlocks.GRAVE_STONE.get();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH,
                Direction.EAST, Direction.WEST}) {
            BlockState state = block.defaultBlockState()
                    .setValue(GraveStoneBlock.FACING, d);
            VoxelShape shape = state.getShape(helper.getLevel(), pos);
            if (shape.isEmpty()) {
                helper.fail("朝向 " + d + " 没有碰撞盒");
            }
        }
        // 朝南/朝北：X 方向宽（8px），Z 方向薄
        var ns = block.defaultBlockState().setValue(GraveStoneBlock.FACING, Direction.NORTH)
                .getShape(helper.getLevel(), pos).bounds();
        double nsX = ns.maxX - ns.minX;
        double nsZ = ns.maxZ - ns.minZ;
        if (!(nsX > nsZ)) {
            helper.fail("朝南北时板应沿 X 展开：实测 X=" + nsX + " Z=" + nsZ);
        }
        // 朝东/朝西：反过来
        var ew = block.defaultBlockState().setValue(GraveStoneBlock.FACING, Direction.EAST)
                .getShape(helper.getLevel(), pos).bounds();
        double ewX = ew.maxX - ew.minX;
        double ewZ = ew.maxZ - ew.minZ;
        if (!(ewZ > ewX)) {
            helper.fail("朝东西时板应沿 Z 展开：实测 X=" + ewX + " Z=" + ewZ);
        }
        helper.succeed();
    }

    // ==========================================================
    //  变体
    // ==========================================================

    /** 8 个变体都能从序列化名找回自己（blockstate 与枚举必须一一对应）。 */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void allVariantsRoundtrip(GameTestHelper helper) {
        GraveStoneVariant[] all = GraveStoneVariant.values();
        if (all.length != 8) {
            helper.fail("设计是 8 种石材，实测 " + all.length);
        }
        for (GraveStoneVariant v : all) {
            GraveStoneVariant back = GraveStoneVariant.byId(v.getSerializedName());
            if (back != v) {
                helper.fail("变体 " + v.getSerializedName()
                        + " 反查回来变成了 " + back.getSerializedName());
            }
        }
        // 序列化名不许重复（重复会让 blockstate 里两个 variant 撞车）
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (GraveStoneVariant v : all) {
            if (!ids.add(v.getSerializedName())) {
                helper.fail("变体序列化名重复：" + v.getSerializedName());
            }
        }
        helper.succeed();
    }

    /** 不认识的材质名要兜底成石砖，不能抛异常（老档/手改数据）。 */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void unknownVariantFallsBack(GameTestHelper helper) {
        if (GraveStoneVariant.byId("not_a_stone") != GraveStoneVariant.STONE_BRICKS) {
            helper.fail("未知材质应兜底为石砖");
        }
        if (GraveStoneVariant.byId(null) != GraveStoneVariant.STONE_BRICKS) {
            helper.fail("null 材质应兜底为石砖");
        }
        // 大小写不敏感（手改 JSON 常写成大写）
        if (GraveStoneVariant.byId("STONE_BRICKS") != GraveStoneVariant.STONE_BRICKS) {
            helper.fail("材质名应大小写不敏感");
        }
        helper.succeed();
    }

    /** 方块状态能接受全部 8 个变体（状态定义真的注册了它们）。 */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void blockAcceptsAllVariants(GameTestHelper helper) {
        Block block = com.bmf.bmfstown.block.ModBlocks.GRAVE_STONE.get();
        for (GraveStoneVariant v : GraveStoneVariant.values()) {
            BlockState state = block.defaultBlockState().setValue(GraveStoneBlock.VARIANT, v);
            if (state.getValue(GraveStoneBlock.VARIANT) != v) {
                helper.fail("方块状态吞掉了变体 " + v.getSerializedName());
            }
        }
        helper.succeed();
    }

    // ==========================================================
    //  清位（最容易出错的地方）
    // ==========================================================

    /**
     * 碑被挖掉 ⇒ 碑位表清干净，人回到待葬。
     *
     * <p><b>这是本批最重要的一条</b>：漏清位会出现"新碑顶着死人的名字"。
     * 走<b>真实的 {@code onRemove} 路径</b>（{@code level.destroyBlock}），
     * 不是手动调 {@code clearSlot}——手动调等于没测到这个集成点（坑72）。
     *
     * <p>反向验证：注掉 {@code onRemove} 里的 {@code clearSlot} ⇒ 此条红。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void diggingClearsSlot(GameTestHelper helper) {
        Scene s = scene(helper);
        Resident a = s.resident();
        ServerLevel level = helper.getLevel();
        a.getIdentity().setTownId(s.town().id());
        GraveyardData data = GraveyardData.get(level);
        data.record(a, level, a.damageSources().genericKill());

        BlockPos rel = new BlockPos(4, 1, 4);
        BlockPos abs = helper.absolutePos(rel);
        level.setBlockAndUpdate(abs, com.bmf.bmfstown.block.ModBlocks.GRAVE_STONE.get()
                .defaultBlockState());
        data.bindSlot(abs, a.getUUID());
        if (data.graveAt(abs) == null) {
            helper.fail("前置：碑应该已经认得他");
        }

        // 走真实破坏路径（会触发 onRemove）
        level.destroyBlock(abs, false);

        if (data.graveAt(abs) != null) {
            helper.fail("挖掉碑后碑位表必须清空，否则新碑会顶着死人的名字");
        }
        if (!isUnburied(data, a.getUUID())) {
            helper.fail("挖碑后他应回到待葬（名册一个字不动）");
        }
        if (data.find(a.getUUID()) == null) {
            helper.fail("挖碑不该删名册记录");
        }
        helper.succeed();
    }

    /** 换成别的方块也算"碑没了"（不只是被挖空成空气）。 */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void replacingBlockClearsSlot(GameTestHelper helper) {
        Scene s = scene(helper);
        Resident a = s.resident();
        ServerLevel level = helper.getLevel();
        a.getIdentity().setTownId(s.town().id());
        GraveyardData data = GraveyardData.get(level);
        data.record(a, level, a.damageSources().genericKill());

        BlockPos abs = helper.absolutePos(new BlockPos(2, 1, 6));
        level.setBlockAndUpdate(abs, com.bmf.bmfstown.block.ModBlocks.GRAVE_STONE.get()
                .defaultBlockState());
        data.bindSlot(abs, a.getUUID());
        // 直接盖成石头
        level.setBlockAndUpdate(abs, net.minecraft.world.level.block.Blocks.STONE
                .defaultBlockState());
        if (data.graveAt(abs) != null) {
            helper.fail("碑位被别的方块盖掉后必须清位");
        }
        helper.succeed();
    }

    /** 只改朝向（还是墓碑）不该清位 —— 否则玩家转个方向就把名字弄丢了。 */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void changingFacingKeepsSlot(GameTestHelper helper) {
        Scene s = scene(helper);
        Resident a = s.resident();
        ServerLevel level = helper.getLevel();
        a.getIdentity().setTownId(s.town().id());
        GraveyardData data = GraveyardData.get(level);
        data.record(a, level, a.damageSources().genericKill());

        BlockPos abs = helper.absolutePos(new BlockPos(5, 1, 5));
        Block block = com.bmf.bmfstown.block.ModBlocks.GRAVE_STONE.get();
        level.setBlockAndUpdate(abs, block.defaultBlockState()
                .setValue(GraveStoneBlock.FACING, Direction.NORTH));
        data.bindSlot(abs, a.getUUID());

        // 同方块换状态（朝向变了）
        level.setBlockAndUpdate(abs, block.defaultBlockState()
                .setValue(GraveStoneBlock.FACING, Direction.EAST));

        if (data.graveAt(abs) == null) {
            helper.fail("只改朝向不该清位（onRemove 判据要用方块本身，不能用 state 不等）");
        }
        helper.succeed();
    }

    // ==========================================================
    //  碑文
    // ==========================================================

    /**
     * 碑文只取次数最大的三条，且被过滤掉零值。
     *
     * <p>反向验证：把排序改成正序（升序）⇒ 取到的是最小的三条 ⇒ 此条红。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void epitaphTakesTopThree(GameTestHelper helper) {
        Map<String, Integer> stats = new java.util.LinkedHashMap<>();
        stats.put("mobs_killed", 214);
        stats.put("items_crafted", 88);
        stats.put("books_written", 3);
        stats.put("harvests", 0);        // 零值不许出现
        stats.put("drinks_served", 41);

        var top = GraveText.topStats(stats, 3);
        if (top.size() != 3) {
            helper.fail("应取 3 条，实测 " + top.size());
        }
        if (!top.get(0).key().equals("mobs_killed") || top.get(0).value() != 214) {
            helper.fail("第一条应是击杀 214，实测 " + top.get(0));
        }
        if (!top.get(1).key().equals("items_crafted")) {
            helper.fail("第二条应是做成 88，实测 " + top.get(1));
        }
        if (!top.get(2).key().equals("drinks_served")) {
            helper.fail("第三条应是上酒 41，实测 " + top.get(2));
        }
        if (top.stream().anyMatch(e -> e.value() <= 0)) {
            helper.fail("零值不该出现在碑文里");
        }
        helper.succeed();
    }

    /** 同分值时按 key 字典序，保证两次渲染顺序一致。 */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void tiesAreStable(GameTestHelper helper) {
        Map<String, Integer> a = new java.util.LinkedHashMap<>();
        a.put("mobs_killed", 5);
        a.put("books_written", 5);
        Map<String, Integer> b = new java.util.LinkedHashMap<>();
        b.put("books_written", 5);
        b.put("mobs_killed", 5);
        var la = GraveText.topStats(a, 2);
        var lb = GraveText.topStats(b, 2);
        if (!la.get(0).key().equals(lb.get(0).key())) {
            helper.fail("同分值顺序不稳定：" + la.get(0).key() + " vs " + lb.get(0).key());
        }
        helper.succeed();
    }

    /** 老档没有生日/入镇日时，碑文不许出现 -1。 */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void unknownDatesAreOmitted(GameTestHelper helper) {
        // ⚠ 2026-11-28：未知哨兵是 UNKNOWN_DAY，不是 -1
        // （生日投影可为负，用 -1 会把合法负生日判成未知）
        long unknown = com.bmf.bmfstown.entity.domain.AgingRolls.UNKNOWN_DAY;
        var grave = new GraveyardData.Grave(
                java.util.UUID.randomUUID(), "无名", "none", null,
                100L, "other", "" , null,
                unknown, -1L, "", 0, unknown,
                java.util.List.of(), Map.of());
        if (grave.ageYears() != -1) {
            helper.fail("没有生日时享年应为 -1，实测 " + grave.ageYears());
        }
        if (grave.stayYears() != -1) {
            helper.fail("没有入镇日时在镇年数应为 -1，实测 " + grave.stayYears());
        }
        // summary 里不该出现 "-1"
        String text = GraveText.summary(grave, EPOCH).getString();
        if (text.contains("-1")) {
            helper.fail("老档碑文里出现了 -1：" + text);
        }
        helper.succeed();
    }

    /** 物品栏里那块碑是同一个物品，材质不随变体新开物品。 */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void singleItemCoversAllVariants(GameTestHelper helper) {
        ItemStack stack = new ItemStack(
                com.bmf.bmfstown.block.ModBlocks.GRAVE_STONE.get().asItem());
        if (stack.isEmpty()) {
            helper.fail("墓碑物品必须存在");
        }
        if (com.bmf.bmfstown.block.ModBlocks.GRAVE_STONE.get().asItem()
                != com.bmf.bmfstown.ModItems.GRAVE_STONE.get()) {
            helper.fail("方块与物品应是同一个（8 变体共用一个物品）");
        }
        helper.succeed();
    }

    // ==========================================================
    //  物品形态的材质辨识（决策 #144）
    // ==========================================================

    /**
     * {@link com.bmf.bmfstown.block.GraveStoneItem#stackFor} 造出来的堆，
     * 读回来的变体必须就是那一种。
     *
     * <p><b>为什么要专门测"读写往返"</b>：{@code BlockItemStateProperties}
     * 的键是<b>方块属性名</b>（这里是 {@code variant}），值必须能被
     * {@code Property.getValue(String)} 解析。<b>键名或值写错的后果是静默的</b>
     * ——{@code apply} 遇到不认识的键会跳过、不报错，落到默认值。
     * 所以"我写了组件"不等于"组件生效了"。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void itemStackCarriesVariant(GameTestHelper helper) {
        for (GraveStoneVariant v : GraveStoneVariant.values()) {
            ItemStack stack = com.bmf.bmfstown.block.GraveStoneItem.stackFor(v);
            GraveStoneVariant back =
                    com.bmf.bmfstown.block.GraveStoneItem.variantOf(stack);
            if (back != v) {
                helper.fail("材质 " + v.getSerializedName() + " 往返变成了 "
                        + back.getSerializedName() + "（组件键名/值写错会静默落默认）");
            }
        }
        helper.succeed();
    }

    /**
     * 8 种材质的物品名字<b>互不相同</b>，而且都能解析出真实文本。
     *
     * <p><b>这条守的是"玩家能不能在物品栏里分辨"</b>（2026-11-28 被问到）。
     * 反向验证：把 {@code GraveStoneItem.getName} 改回 {@code super.getName}
     * ⇒ 8 个名字全变成"墓碑" ⇒ 此条红并报出重复的名字。
     *
     * <p>⚠ 名字在服务端解析：GameTest 跑在服务端，lang 用的是服务端加载的
     * en_us（原版 {@code Language} 默认美式英语）。所以这里断言的是英文名，
     * 判据只能是"互不相同 + 不含未翻译的 key 原样"。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void itemNamesDifferPerVariant(GameTestHelper helper) {
        java.util.Map<String, String> seen = new java.util.LinkedHashMap<>();
        for (GraveStoneVariant v : GraveStoneVariant.values()) {
            ItemStack stack = com.bmf.bmfstown.block.GraveStoneItem.stackFor(v);
            String name = stack.getHoverName().getString();
            if (name.startsWith("block.bmfstown.")) {
                helper.fail("材质 " + v.getSerializedName()
                        + " 的名没有翻译，显示成了 key 原文：" + name);
            }
            String prev = seen.put(name, v.getSerializedName());
            if (prev != null) {
                helper.fail("材质 " + prev + " 和 " + v.getSerializedName()
                        + " 的物品名相同（都是 " + name + "）⇒ 物品栏里分不出");
            }
        }
        if (seen.size() != GraveStoneVariant.values().length) {
            helper.fail("8 种材质应产生 8 个不同名字，实测 " + seen.size());
        }
        helper.succeed();
    }

    /**
     * 没带组件的裸物品（{@code new ItemStack(item)}）要兜底成石砖，不许抛异常。
     *
     * <p>创造栏旧版、命令 {@code /give} 不带组件、老档里的物品都会走到这里。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void bareItemFallsBackToStoneBricks(GameTestHelper helper) {
        ItemStack bare = new ItemStack(com.bmf.bmfstown.ModItems.GRAVE_STONE.get());
        if (com.bmf.bmfstown.block.GraveStoneItem.variantOf(bare)
                != GraveStoneVariant.STONE_BRICKS) {
            helper.fail("没有 block_state 组件的墓碑应兜底为石砖");
        }
        // 名字也得能出来，不能是空串
        if (bare.getHoverName().getString().isBlank()) {
            helper.fail("裸物品的名字是空的");
        }
        helper.succeed();
    }

    /**
     * 模型分派值：石砖 = 0，其余各不相同（overrides 靠它挑模型）。
     *
     * <p>反向验证：把 {@code modelIndex()} 改成恒返回 0
     * ⇒ 8 个值全一样 ⇒ 此条红。这条守的是"物品图标真的按材质换"，
     * 而不是"我写了个方法"。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void modelIndexIsDistinct(GameTestHelper helper) {
        if (GraveStoneVariant.STONE_BRICKS.modelIndex() != 0.0F) {
            helper.fail("石砖必须是 0（它是物品模型的 parent，不写 override），实测 "
                    + GraveStoneVariant.STONE_BRICKS.modelIndex());
        }
        java.util.Set<Float> seen = new java.util.HashSet<>();
        for (GraveStoneVariant v : GraveStoneVariant.values()) {
            if (!seen.add(v.modelIndex())) {
                helper.fail("材质 " + v.getSerializedName() + " 的模型分派值重复："
                        + v.modelIndex() + " ⇒ 两个材质会显示同一个图标");
            }
        }
        helper.succeed();
    }

    // ==========================================================
    //  刻碑复核（决策 #144：三重复核）
    // ==========================================================

    /**
     * 三条复核各自独立生效：位置不是碑 / 碑已有主 / 人不待葬。
     *
     * <p><b>为什么这三条必须有用例</b>：它们唯一的调用方是网络处理器，
     * 而 GameTest 跑不了网络层。抽成 {@code GraveCarving.check} 就是为了
     * 让这三条能被验证——否则"客户端能刻任意碑"这种漏洞永远没人知道。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void carvingGuards(GameTestHelper helper) {
        Scene s = scene(helper);
        Resident a = s.resident();
        ServerLevel level = helper.getLevel();
        a.getIdentity().setTownId(s.town().id());
        GraveyardData data = GraveyardData.get(level);
        data.record(a, level, a.damageSources().genericKill());

        BlockPos pos = helper.absolutePos(new BlockPos(3, 1, 3));

        // ① 位置不是墓碑（世界还没放方块）
        if (com.bmf.bmfstown.town.GraveCarving.check(data, pos, false, a.getUUID())
                != com.bmf.bmfstown.town.GraveCarving.Result.NOT_A_STONE) {
            helper.fail("位置不是墓碑时必须拒绝");
        }

        // ② 位置是碑、人也待葬 ⇒ 放行
        if (com.bmf.bmfstown.town.GraveCarving.check(data, pos, true, a.getUUID())
                != com.bmf.bmfstown.town.GraveCarving.Result.OK) {
            helper.fail("碑位空闲 + 人待葬时应该放行");
        }

        // ③ 这块碑已经刻了人
        data.bindSlot(pos, a.getUUID());
        var other = helper.spawn(com.bmf.bmfstown.ModEntities.RESIDENT.get(),
                new BlockPos(5, 1, 5));
        other.setNoAi(true);
        other.getIdentity().setTownId(s.town().id());
        data.record(other, level, other.damageSources().genericKill());
        if (com.bmf.bmfstown.town.GraveCarving.check(data, pos, true, other.getUUID())
                != com.bmf.bmfstown.town.GraveCarving.Result.ALREADY_CARVED) {
            helper.fail("碑已有主时必须拒绝（否则会一个人两行碑文）");
        }
        data.clearSlot(pos);

        // ④ 人已经有碑了（在别处）⇒ 不许立第二块
        BlockPos elsewhere = helper.absolutePos(new BlockPos(2, 1, 6));
        data.bindSlot(elsewhere, a.getUUID());
        if (com.bmf.bmfstown.town.GraveCarving.check(data, pos, true, a.getUUID())
                != com.bmf.bmfstown.town.GraveCarving.Result.NOT_WAITING) {
            helper.fail("已经在别处立过碑的人不许再立（一人一碑）");
        }
        data.clearSlot(elsewhere);

        // ⑤ 名册里根本没有这个人
        if (com.bmf.bmfstown.town.GraveCarving.check(data, pos, true,
                java.util.UUID.randomUUID())
                != com.bmf.bmfstown.town.GraveCarving.Result.NOT_WAITING) {
            helper.fail("名册里没有的 UUID 必须拒绝");
        }
        // ⑥ null 也一样，不许 NPE
        if (com.bmf.bmfstown.town.GraveCarving.check(data, pos, true, null)
                != com.bmf.bmfstown.town.GraveCarving.Result.NOT_WAITING) {
            helper.fail("null UUID 必须拒绝而不是抛异常");
        }
        helper.succeed();
    }

    /**
     * 待葬名单只出本镇的人，不串镇（野外立的碑不该能刻到别镇的人）。
     *
     * <p><b>这条用例调的是生产同一个方法</b>（{@code unburiedOfTown}）。
     * 第一版我写成了"在用例里自己 for 循环过滤一遍"——那是坑72 的原始形态：
     * 真代码改错、用例照旧绿，因为用例测的是它自己抄的那份逻辑。
     *
     * <p>反向验证：把 {@code unburiedOfTown} 的 sameTown 判定改成恒 {@code true}
     * ⇒ 别镇的逝者出现在本镇名单里 ⇒ 此条红并报出那个 UUID。
     *
     * <p>⚠ 名册是全局单例（坑81），不能断言"名单里只有 1 个"，
     * 只能断言"包含/不包含特定 UUID"。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void candidatesAreScopedToTown(GameTestHelper helper) {
        Scene s = scene(helper);
        Resident ours = s.resident();
        ServerLevel level = helper.getLevel();
        ours.getIdentity().setTownId(s.town().id());
        GraveyardData data = GraveyardData.get(level);
        data.record(ours, level, ours.damageSources().genericKill());

        // 一个不属于任何镇的逝者（过客/刷怪蛋放的居民，你没赋归属就死掉）
        Resident outsider = helper.spawn(com.bmf.bmfstown.ModEntities.RESIDENT.get(),
                new BlockPos(5, 1, 2));
        outsider.setNoAi(true);
        // 故意不赋 townId
        data.record(outsider, level, outsider.damageSources().genericKill());
        if (outsider.getIdentity().townId() != null) {
            helper.fail("前置：这个居民不该有城镇归属");
        }

        // ← 生产方法
        var list = data.unburiedOfTown(s.town().id());
        boolean hasOur = list.stream().anyMatch(g -> g.id().equals(ours.getUUID()));
        boolean hasOutsider = list.stream().anyMatch(g -> g.id().equals(outsider.getUUID()));
        if (!hasOur) {
            helper.fail("本镇待葬者必须在名单里");
        }
        if (hasOutsider) {
            helper.fail("无归属的逝者不该进本镇名单（串镇：" + outsider.getUUID() + "）");
        }

        // 野外（碑不在任何镇内）只应看到无归属的那位
        var wild = data.unburiedOfTown(null);
        if (wild.stream().anyMatch(g -> g.id().equals(ours.getUUID()))) {
            helper.fail("野外碑不该列出本镇的人");
        }
        helper.succeed();
    }

    /** 该 UUID 是否在待葬列表里（全局单例下唯一可靠的判据，见坑81）。 */
    private static boolean isUnburied(GraveyardData data, java.util.UUID id) {
        return data.unburied().stream().anyMatch(g -> g.id().equals(id));
    }

    // ==========================================================
    //  名单带生平（2026-11-28 决策 #145）
    // ==========================================================

    /**
     * 职业必须走 lang，不能印序列化 id。
     *
     * <p><b>反向验证</b>：把 {@code GraveText.professionKey} 换成直接拼 id
     * （改成 {@code Component.literal(id)}）⇒ 断言里会出现 "warrior" ⇒ 红。
     *
     * <p>为什么这条必须存在：名册里存的是 {@code "warrior"}，
     * 而 lang 里是 {@code profession.bmfstown.warrior = 战士}。
     * 直接拼 id 不会报错、不会崩，只是碑上印一个英文单词——
     * <b>静默错误，只能靠断言守</b>。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 40)
    public static void professionUsesLangNotRawId(GameTestHelper helper) {
        String key = GraveText.professionKey("warrior");
        if (!"profession.bmfstown.warrior".equals(key)) {
            helper.fail("职业 key 拼错了：" + key);
        }
        // 组件里必须是 translatable，不能是 literal
        var grave = new GraveyardData.Grave(
                java.util.UUID.randomUUID(), "战士甲", "warrior", null,
                200L, "killed", "", null, 100L, 36500L, "", 5, 100L,
                java.util.List.of(), Map.of("mobs_killed", 12));
        String text = GraveText.summary(grave, EPOCH).getString();
        if (text.contains("warrior")) {
            helper.fail("碑文里印出了原始职业 id（应走 lang）：" + text);
        }
        helper.succeed();
    }

    /**
     * 名单每一行必须自带生平，而且生平里不能是空的。
     *
     * <p><b>反向验证</b>：把 {@code profileOf} 改成返回 {@code Component.empty()}
     * ⇒ "生平不能是空"这条红。
     *
     * <p>守的是用户当场提的那条："底下也会显示他的生平信息和墓志铭"。
     * 只发名字的话玩家没法判断该刻谁——这正是 #144 第一版被打回的原因。
     */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void candidateCarriesProfile(GameTestHelper helper) {
        Scene s = scene(helper);
        Resident a = s.resident();
        ServerLevel level = helper.getLevel();
        a.getIdentity().setTownId(s.town().id());
        var data = GraveyardData.get(level);
        data.record(a, level, a.damageSources().genericKill());

        var grave = data.find(a.getUUID());
        if (grave == null) {
            helper.fail("前置：他应该已经进名册");
            return;
        }
        var profile = com.bmf.bmfstown.network.ModNetworking.profileOf(grave, EPOCH);
        // 2026-11-28：profileOf 改成返回"行数组"（界面要按宽度折行）。
        // 断言口径同步跟着改——把各行拼起来看内容，不再当它是单个组件。
        StringBuilder joined = new StringBuilder();
        for (var line : profile) {
            joined.append(line.getString()).append('\n');
        }
        String text = joined.toString();
        if (text.isBlank()) {
            helper.fail("名单行的生平不能为空（玩家要靠它判断该刻谁）");
        }
        // 死因一定在（classify 必给三选一），享年/在镇可能未知。
        // ⚠ 2026-11-28 #147 起死因不再是 "death.bmfstown.cause.*" 三选一，
        //   而是<b>原版整句</b> "death.attack.<msgId>"（用户打回"横死"两字）。
        //   这里断言的是"有死因那一行"，判据 = 原版死亡键 or 寿终兜底键。
        String damageId = grave.damageId();
        boolean hasCause = profile.toString().contains("death.attack.")
                || profile.toString().contains("grave.bmfstown.death.old_age")
                || text.contains("death.attack.");
        if (!hasCause) {
            helper.fail("生平里连死因都没有（伤害源 " + damageId + "），组件内容：" + profile);
        }
        helper.succeed();
    }
}