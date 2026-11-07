package com.bmf.bmfstown.gametest;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.ModDataComponents;
import com.bmf.bmfstown.entity.AlchemistGoal;
import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.entity.profile.Perks;
import com.bmf.bmfstown.entity.profile.ProfessionType;
import com.bmf.bmfstown.entity.profile.SkillType;
import com.bmf.bmfstown.mixin.BrewingStandBlockEntityAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.neoforged.neoforge.gametest.GameTestHolder;

import javax.annotation.Nullable;

/**
 * Perk 六件套 + 秘传门禁 + 燃料/涨经验用例（2026-11-07 用户委托清单）。
 */
@GameTestHolder(BmfTown.MOD_ID)
public class AlchemyPerkGateTests {

    private static BlockPos standAbs(GameTestHelper helper) {
        return helper.absolutePos(new BlockPos(2, 1, 2));
    }

    /** 摆一个"粗制瓶×3 + 石英 + 烈焰粉"的酿造台（秘传门禁的玩家手酿场景）。 */
    private static void setupLockedSecretStand(GameTestHelper helper) {
        BlockPos stand = new BlockPos(2, 1, 2);
        helper.setBlock(stand, net.minecraft.world.level.block.Blocks.BREWING_STAND);
        var be = helper.getLevel().getBlockEntity(helper.absolutePos(stand));
        if (!(be instanceof net.minecraft.world.level.block.entity.BrewingStandBlockEntity bs)) {
            helper.fail("酿造台 BE 未创建");
            return;
        }
        for (int i = 0; i < 3; i++) {
            bs.setItem(i, PotionContents.createItemStack(Items.POTION, Potions.AWKWARD));
        }
        bs.setItem(3, new ItemStack(Items.QUARTZ)); // 急迫秘传的催化剂
        bs.setItem(4, new ItemStack(Items.BLAZE_POWDER));
        bs.setChanged();
    }

    /** 门禁·玩家面：未解锁的秘传组合——料放进去 200t 内不起酿（mixin 拦 hasMix）。 */
    @GameTest(template = "tiny3x3x3", timeoutTicks = 400)
    public static void gateBlocksLockedSecretBrewing(GameTestHelper helper) {
        setupLockedSecretStand(helper);
        helper.startSequence().thenExecuteAfter(200, () -> {
            var be = helper.getLevel().getBlockEntity(standAbs(helper));
            if (!(be instanceof net.minecraft.world.level.block.entity.BrewingStandBlockEntity b)) {
                helper.fail("酿造台 BE 丢失");
                return;
            }
            ItemStack slot0 = b.getItem(0);
            var c = slot0.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
            if (c.potion().orElse(null) != Potions.AWKWARD) {
                helper.fail("未解锁的秘传被酿出来了（门禁失效）");
                return;
            }
        }).thenSucceed();
    }

    /** 门禁·解锁后：AlchemyGate.open 即放行（同一结构里把料换成已解锁组合）。 */
    @GameTest(template = "tiny3x3x3", timeoutTicks = 1200)
    public static void gateOpensAfterUnlock(GameTestHelper helper) {
        com.bmf.bmfstown.alchemy.AlchemyGate.open(helper.getLevel().getServer(),
                "unlock.alchemy.haste");
        setupLockedSecretStand(helper);
        helper.succeedWhen(() -> {
            var bs = helper.getLevel().getBlockEntity(standAbs(helper));
            if (!(bs instanceof net.minecraft.world.level.block.entity.BrewingStandBlockEntity b)) {
                throw new net.minecraft.gametest.framework.GameTestAssertException("等 BE");
            }
            ItemStack slot0 = b.getItem(0);
            var c = slot0.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
            var potionHolder = c.potion().orElse(null);
            if (potionHolder != null
                    && potionHolder.value() == com.bmf.bmfstown.alchemy.ModPotions.HASTE.get()) {
                return; // 酿成了
            }
            if (!com.bmf.bmfstown.alchemy.AlchemyGate.isOpen("unlock.alchemy.haste")) {
                helper.fail("AlchemyGate.open 未生效（isOpen=false）——门禁解锁链路断");
            }
            StringBuilder st = new StringBuilder("[");
            for (int i = 0; i < 5; i++) {
                ItemStack it = b.getItem(i);
                st.append(it.isEmpty() ? "-" : it.getItem() + "x" + it.getCount());
                if (i < 4) {
                    st.append(", ");
                }
            }
            throw new net.minecraft.gametest.framework.GameTestAssertException(
                    "已解锁但未起酿 stand=" + st + " bottle0=" + c);
        });
    }

    /** 医师：正面浊药打豁免章——且**不掺灰不改名**（玩家视角=干净药，2026-11-07 拍板）。 */
    @GameTest(template = "tiny3x3x3", timeoutTicks = 400)
    public static void medicStoneExemptsPositivePotion(GameTestHelper helper) {
        Resident brewer = helper.spawn(com.bmf.bmfstown.ModEntities.RESIDENT.get(),
                new BlockPos(1, 1, 1));
        brewer.getIdentity().setProfession(ProfessionType.ALCHEMIST);
        brewer.getGrowth().setPerkChoice(ProfessionType.ALCHEMIST,
                Perks.NODE_AL_MASTER, "medic_stone");
        ItemStack stack = PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS);
        com.bmf.bmfstown.alchemy.PurityRules.stamp(stack, 0.6F, brewer);
        if (!Boolean.TRUE.equals(stack.get(ModDataComponents.ALCHEMY_PURE.get()))) {
            helper.fail("医师豁免章未打上");
            return;
        }
        if (!nameKeyIs(stack, "item.minecraft.potion.effect.swiftness")) {
            helper.fail("豁免药名字键错误（会显示'不可合成'）: " + nameKeyOf(stack));
            return;
        }
        var contents = stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
        if (contents.customColor().isPresent()) {
            helper.fail("豁免药不应掺灰");
            return;
        }
        if (contents.potion().isPresent()) {
            helper.fail("豁免药仍应物化（丢 holder）");
            return;
        }
        // 丢 holder 后名字键必须兜住（否则显示"不可合成"——秘传药全中招，2026-11-07）
        if (!nameKeyIs(stack, "item.minecraft.potion.effect.swiftness")) {
            helper.fail("豁免药名字键错误: " + nameKeyOf(stack));
            return;
        }
        helper.succeed();
    }

    /** 毒师：负面浊药打豁免章。 */
    @GameTest(template = "tiny3x3x3", timeoutTicks = 400)
    public static void poisonStoneExemptsNegativePotion(GameTestHelper helper) {
        Resident brewer = helper.spawn(com.bmf.bmfstown.ModEntities.RESIDENT.get(),
                new BlockPos(1, 1, 1));
        brewer.getIdentity().setProfession(ProfessionType.ALCHEMIST);
        brewer.getGrowth().setPerkChoice(ProfessionType.ALCHEMIST,
                Perks.NODE_AL_MASTER, "poison_stone");
        ItemStack stack = PotionContents.createItemStack(Items.POTION, Potions.HARMING);
        com.bmf.bmfstown.alchemy.PurityRules.stamp(stack, 0.6F, brewer);
        if (!Boolean.TRUE.equals(stack.get(ModDataComponents.ALCHEMY_PURE.get()))) {
            helper.fail("毒师豁免章未打上");
            return;
        }
        if (stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY)
                .customColor().isPresent()) {
            helper.fail("毒师豁免药不应掺灰");
            return;
        }
        if (!nameKeyIs(stack, "item.minecraft.potion.effect.harming")) {
            helper.fail("豁免药名字键错误（会显示'不可合成'）: " + nameKeyOf(stack));
            return;
        }
        helper.succeed();
    }

    /** 毒师对悬浮（**原版合成不到的秘传**）豁免：名字键必须兜住（否则露"不可合成"）。 */
    @GameTest(template = "tiny3x3x3", timeoutTicks = 400)
    public static void poisonStoneExemptsLevitation(GameTestHelper helper) {
        Resident brewer = helper.spawn(com.bmf.bmfstown.ModEntities.RESIDENT.get(),
                new BlockPos(1, 1, 1));
        brewer.getIdentity().setProfession(ProfessionType.ALCHEMIST);
        brewer.getGrowth().setPerkChoice(ProfessionType.ALCHEMIST,
                Perks.NODE_AL_MASTER, "poison_stone");
        ItemStack stack = PotionContents.createItemStack(Items.POTION,
                com.bmf.bmfstown.alchemy.ModPotions.LEVITATION);
        com.bmf.bmfstown.alchemy.PurityRules.stamp(stack, 0.6F, brewer);
        if (!Boolean.TRUE.equals(stack.get(ModDataComponents.ALCHEMY_PURE.get()))) {
            helper.fail("悬浮豁免章未打上");
            return;
        }
        if (!nameKeyIs(stack, "item.minecraft.potion.effect.bmfstown_levitation")) {
            helper.fail("悬浮豁免药名字键错误: " + nameKeyOf(stack) + "（会显示'不可合成'）");
            return;
        }
        helper.succeed();
    }

    /** 取 CUSTOM_NAME 的 translatable 键（自身节点+兄弟都扫；无名字 = "null"）。 */
    private static String nameKeyOf(ItemStack stack) {
        var name = stack.get(DataComponents.CUSTOM_NAME);
        if (name == null) {
            return "null";
        }
        var all = new java.util.ArrayList<net.minecraft.network.chat.Component>();
        all.add(name);
        all.addAll(name.getSiblings());
        for (var comp : all) {
            if (comp.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tc) {
                return tc.getKey();
            }
        }
        return "无 translatable";
    }

    private static boolean nameKeyIs(ItemStack stack, String expected) {
        return expected.equals(nameKeyOf(stack));
    }

    /** 缓释：传奇"缓释"让产物时长 ×2（与纯度缩时叠加）。 */
    @GameTest(template = "tiny3x3x3", timeoutTicks = 400)
    public static void doubleTimeDoublesDuration(GameTestHelper helper) {
        Resident brewer = helper.spawn(com.bmf.bmfstown.ModEntities.RESIDENT.get(),
                new BlockPos(1, 1, 1));
        brewer.getIdentity().setProfession(ProfessionType.ALCHEMIST);
        brewer.getGrowth().setPerkChoice(ProfessionType.ALCHEMIST,
                Perks.NODE_AL_LEGEND, "double_time");
        ItemStack stack = PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS);
        com.bmf.bmfstown.alchemy.PurityRules.stamp(stack, 0.6F, brewer);
        var e = stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY)
                .customEffects().get(0);
        int expected = Math.round(3600 * 0.6F * 2.0F);
        if (Math.abs(e.getDuration() - expected) > 1) {
            helper.fail("缓释未生效: " + e.getDuration() + " 期望≈" + expected);
            return;
        }
        helper.succeed();
    }

    /** 浓缩：传奇"浓缩"让效果等级 +1——浊药和纯药都要生效（2026-11-07 修）。 */
    @GameTest(template = "tiny3x3x3", timeoutTicks = 400)
    public static void boostOneRaisesAmplifier(GameTestHelper helper) {
        Resident brewer = helper.spawn(com.bmf.bmfstown.ModEntities.RESIDENT.get(),
                new BlockPos(1, 1, 1));
        brewer.getIdentity().setProfession(ProfessionType.ALCHEMIST);
        brewer.getGrowth().setPerkChoice(ProfessionType.ALCHEMIST,
                Perks.NODE_AL_LEGEND, "boost_one");
        // 浊药（P=0.6）：等级 +1
        ItemStack impure = PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS);
        com.bmf.bmfstown.alchemy.PurityRules.stamp(impure, 0.6F, brewer);
        var e = impure.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY)
                .customEffects().get(0);
        if (e.getAmplifier() != 1) {
            helper.fail("浓缩未生效（浊药）: amplifier=" + e.getAmplifier());
            return;
        }
        // 纯药（P=1）：等级同样 +1（原实现纯药提前返回，浓缩失效——已修）
        ItemStack pure = PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS);
        com.bmf.bmfstown.alchemy.PurityRules.stamp(pure, 1.0F, brewer);
        var e2 = pure.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY)
                .customEffects().get(0);
        if (e2.getAmplifier() != 1 || e2.getDuration() != 3600) {
            helper.fail("浓缩未生效（纯药）: amplifier=" + e2.getAmplifier()
                    + " duration=" + e2.getDuration());
            return;
        }
        // 物化丢 holder 后原版会显示"不可合成的药水"——CUSTOM_NAME 必须兜住正常名
        // （2026-11-07 用户实测：力量Ⅲ喷溅显示"不可合成的喷溅型药水"）
        var name = pure.get(DataComponents.CUSTOM_NAME);
        String baseKey = null;
        if (name != null) {
            // translatable 可能在自身节点或兄弟节点，两处都扫
            var all = new java.util.ArrayList<net.minecraft.network.chat.Component>();
            all.add(name);
            all.addAll(name.getSiblings());
            for (var comp : all) {
                if (comp.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tc) {
                    baseKey = tc.getKey();
                }
            }
        }
        if (!"item.minecraft.potion.effect.swiftness".equals(baseKey)) {
            helper.fail("纯药+浓缩的名字键错误: " + baseKey + "（会显示成'不可合成'）");
            return;
        }
        helper.succeed();
    }

    /** 悬浮秘传（第 11 条）：门禁双面——锁住不起酿、解锁即放行。 */
    @GameTest(template = "tiny3x3x3", timeoutTicks = 1600)
    public static void gateLevitationLockedThenUnlocked(GameTestHelper helper) {
        BlockPos stand = new BlockPos(2, 1, 2);
        helper.setBlock(stand, net.minecraft.world.level.block.Blocks.BREWING_STAND);
        var be0 = helper.getLevel().getBlockEntity(helper.absolutePos(stand));
        if (be0 instanceof net.minecraft.world.level.block.entity.BrewingStandBlockEntity bs) {
            for (int i = 0; i < 3; i++) {
                bs.setItem(i, PotionContents.createItemStack(Items.POTION, Potions.AWKWARD));
            }
            bs.setItem(3, new ItemStack(Items.SHULKER_SHELL)); // 悬浮催化剂
            bs.setItem(4, new ItemStack(Items.BLAZE_POWDER));
            bs.setChanged();
        }
        // 锁定 100t：不得起酿
        helper.startSequence().thenExecuteAfter(100, () -> {
            var be = helper.getLevel().getBlockEntity(helper.absolutePos(stand));
            if (be instanceof net.minecraft.world.level.block.entity.BrewingStandBlockEntity b) {
                var c = b.getItem(0).getOrDefault(DataComponents.POTION_CONTENTS,
                        PotionContents.EMPTY);
                if (c.potion().orElse(null) != Potions.AWKWARD) {
                    helper.fail("未解锁的悬浮秘传被酿出");
                    return;
                }
            }
        }).thenExecute(() -> com.bmf.bmfstown.alchemy.AlchemyGate.open(
                helper.getLevel().getServer(), "unlock.alchemy.levitation"))
                .thenWaitUntil(() -> {
                    var be = helper.getLevel().getBlockEntity(helper.absolutePos(stand));
                    if (be instanceof net.minecraft.world.level.block.entity.BrewingStandBlockEntity b) {
                        var c = b.getItem(0).getOrDefault(DataComponents.POTION_CONTENTS,
                                PotionContents.EMPTY);
                        var h = c.potion().orElse(null);
                        if (h == null || h.value() != com.bmf.bmfstown.alchemy.ModPotions.LEVITATION.get()) {
                            throw new net.minecraft.gametest.framework.GameTestAssertException(
                                    "等待悬浮秘传酿成");
                        }
                    }
                }).thenSucceed();
    }

    /** 缺料闲置 → 玩家补料 → 自动恢复交付（用户实测场景：滞留缺龙息+缺合成材料）。 */
    @GameTest(template = "room11x4x11", timeoutTicks = 30000)
    public static void dragonMaterialsRestockRecovers(GameTestHelper helper) {
        var s = com.bmf.bmfstown.gametest.AlchemyScaffold.prepareTown(
                helper, new BlockPos(1, 0, 1), new BlockPos(9, 0, 9), 1);
        com.bmf.bmfstown.gametest.AlchemyScaffold.stockChest(s, helper, false, 1);
        com.bmf.bmfstown.gametest.AlchemyScaffold.spawnAlchemist(s, helper,
                "alch|minecraft:swiftness|lingering", new BlockPos(4, 1, 5));
        helper.startSequence()
                .thenExecuteAfter(400, () -> {
                    // 400t 后玩家补料：紫颂果+玻璃瓶+一份烈焰粉（龙息配方成本）
                    var chest = s.chest(helper);
                    chest.setItem(20, new ItemStack(Items.CHORUS_FRUIT));
                    chest.setItem(21, new ItemStack(Items.GLASS_BOTTLE));
                    chest.setItem(22, new ItemStack(Items.BLAZE_POWDER));
                    chest.setChanged();
                })
                .thenWaitUntil(() -> {
                    if (com.bmf.bmfstown.gametest.AlchemyScaffold.countStamped(
                            s, helper, Items.LINGERING_POTION) < 4) {
                        throw new net.minecraft.gametest.framework.GameTestAssertException(
                                "等待补料后交付 4 瓶滞留成品");
                    }
                }).thenSucceed();
    }

    /** 冷萃收工：认领释放时燃料必须归零（否则下一个用台的人白嫖 20 次酿造）。 */
    @GameTest(template = "room11x4x11", timeoutTicks = 24000)
    public static void coldBrewResetsFuelOnStop(GameTestHelper helper) {
        var s = com.bmf.bmfstown.gametest.AlchemyScaffold.prepareTown(
                helper, new BlockPos(1, 0, 1), new BlockPos(9, 0, 9), 1);
        com.bmf.bmfstown.gametest.AlchemyScaffold.stockChest(s, helper, false, 1);
        var r = com.bmf.bmfstown.gametest.AlchemyScaffold.spawnAlchemist(s, helper,
                "alch|minecraft:swiftness|none", new BlockPos(4, 1, 5));
        r.getGrowth().setPerkChoice(ProfessionType.ALCHEMIST, Perks.NODE_AL_ADEPT, "cold_brew");
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (com.bmf.bmfstown.gametest.AlchemyScaffold.countStamped(
                            s, helper, Items.POTION) < 4) {
                        throw new net.minecraft.gametest.framework.GameTestAssertException(
                                "等待 4 瓶成品");
                    }
                })
                .thenExecute(() -> r.getJobState().setWorkerEnabled(false))
                // 等 60t：Goal 选择器处理停工 → stop() → 认领释放 → 燃料清零
                .thenExecuteAfter(60, () -> {
                    var stand = s.stand(helper, s.standRels().get(0));
                    var acc = (BrewingStandBlockEntityAccessor) stand;
                    if (acc.bmfstown$getFuel() != 0) {
                        helper.fail("冷萃收工后燃料未清零: " + acc.bmfstown$getFuel()
                                + "（会被下一个人白嫖）");
                    }
                })
                .thenSucceed();
    }

    /** 冷萃+箱里没有烈焰粉：不索要燃料、不卡料，正常交付（2026-11-07 用户抓出）。 */
    @GameTest(template = "room11x4x11", timeoutTicks = 24000)
    public static void coldBrewWorksWithoutBlazePowder(GameTestHelper helper) {
        var s = com.bmf.bmfstown.gametest.AlchemyScaffold.prepareTown(
                helper, new BlockPos(1, 0, 1), new BlockPos(9, 0, 9), 1);
        com.bmf.bmfstown.gametest.AlchemyScaffold.stockChest(s, helper, false, 1);
        // 抽走箱里所有烈焰粉
        var chest = s.chest(helper);
        for (int i = 0; i < chest.getContainerSize(); i++) {
            if (chest.getItem(i).is(Items.BLAZE_POWDER)) {
                chest.removeItem(i, chest.getItem(i).getCount());
            }
        }
        chest.setChanged();
        var r = com.bmf.bmfstown.gametest.AlchemyScaffold.spawnAlchemist(s, helper,
                "alch|minecraft:swiftness|none", new BlockPos(4, 1, 5));
        r.getGrowth().setPerkChoice(ProfessionType.ALCHEMIST, Perks.NODE_AL_ADEPT, "cold_brew");
        helper.succeedWhen(() -> {
            if (com.bmf.bmfstown.gametest.AlchemyScaffold.countStamped(
                    s, helper, Items.POTION) < 4) {
                throw new net.minecraft.gametest.framework.GameTestAssertException(
                        "等待 4 瓶成品（冷萃不应被烈焰粉卡住）");
            }
        });
    }

    /** 龙息 NPC 专属合成：箱里没龙息但有紫颂果/烈焰粉/玻璃瓶 → 就地合成，滞留链走通。 */
    @GameTest(template = "room11x4x11", timeoutTicks = 24000)
    public static void dragonBreathCraftedOnDemand(GameTestHelper helper) {
        var s = com.bmf.bmfstown.gametest.AlchemyScaffold.prepareTown(
                helper, new BlockPos(1, 0, 1), new BlockPos(9, 0, 9), 1);
        com.bmf.bmfstown.gametest.AlchemyScaffold.stockChest(s, helper, false, 1);
        var chest = s.chest(helper);
        chest.setItem(20, new ItemStack(Items.CHORUS_FRUIT));
        chest.setItem(21, new ItemStack(Items.GLASS_BOTTLE));
        chest.setItem(22, new ItemStack(Items.BLAZE_POWDER)); // 龙息配方也要吃一份烈焰粉
        chest.setChanged();
        com.bmf.bmfstown.gametest.AlchemyScaffold.spawnAlchemist(s, helper,
                "alch|minecraft:swiftness|lingering", new BlockPos(4, 1, 5));
        helper.succeedWhen(() -> {
            if (com.bmf.bmfstown.gametest.AlchemyScaffold.countStamped(
                    s, helper, Items.LINGERING_POTION) < 4) {
                throw new net.minecraft.gametest.framework.GameTestAssertException(
                        "等待 4 瓶滞留成品（龙息应就地合成）");
            }
        });
    }

    /** 冷萃：燃料永不消耗（认领的台 fuel 恒 20）。 */
    @GameTest(template = "room11x4x11", timeoutTicks = 24000)
    public static void coldBrewKeepsFuel(GameTestHelper helper) {
        var s = com.bmf.bmfstown.gametest.AlchemyScaffold.prepareTown(
                helper, new BlockPos(1, 0, 1), new BlockPos(9, 0, 9), 1);
        com.bmf.bmfstown.gametest.AlchemyScaffold.stockChest(s, helper, false, 1);
        var r = com.bmf.bmfstown.gametest.AlchemyScaffold.spawnAlchemist(s, helper,
                "alch|minecraft:swiftness|none", new BlockPos(4, 1, 5));
        r.getGrowth().setPerkChoice(ProfessionType.ALCHEMIST, Perks.NODE_AL_ADEPT, "cold_brew");
        helper.succeedWhen(() -> {
            if (com.bmf.bmfstown.gametest.AlchemyScaffold.countStamped(
                    s, helper, Items.POTION) < 4) {
                throw new net.minecraft.gametest.framework.GameTestAssertException("等待 4 瓶成品");
            }
            var stand = s.stand(helper, s.standRels().get(0));
            var acc = (BrewingStandBlockEntityAccessor) stand;
            if (acc.bmfstown$getFuel() < 20) {
                helper.fail("冷萃的台燃料被消耗了: " + acc.bmfstown$getFuel());
            }
        });
    }

    /** 猛火/等级：合成经验高 → 起酿时间缩短（效率乘区只看等级）。 */
    @GameTest(template = "room11x4x11", timeoutTicks = 24000)
    public static void levelSpeedsUpBrewStart(GameTestHelper helper) {
        var s = com.bmf.bmfstown.gametest.AlchemyScaffold.prepareTown(
                helper, new BlockPos(1, 0, 1), new BlockPos(9, 0, 9), 1);
        com.bmf.bmfstown.gametest.AlchemyScaffold.stockChest(s, helper, false, 1);
        var r = com.bmf.bmfstown.gametest.AlchemyScaffold.spawnAlchemist(s, helper,
                "alch|minecraft:swiftness|none", new BlockPos(4, 1, 5));
        r.getGrowth().setSkillXp(SkillType.CRAFTING, 100000); // 满级经验
        r.getGrowth().setPerkChoice(ProfessionType.ALCHEMIST, Perks.NODE_AL_ADEPT, "quickfire");
        int[] fastest = {Integer.MAX_VALUE};
        helper.succeedWhen(() -> {
            var stand = s.stand(helper, s.standRels().get(0));
            if (stand == null) {
                throw new net.minecraft.gametest.framework.GameTestAssertException("等台");
            }
            var acc = (BrewingStandBlockEntityAccessor) stand;
            if (stand.getItem(3).isEmpty() && acc.bmfstown$getBrewTime() <= 0) {
                if (com.bmf.bmfstown.gametest.AlchemyScaffold.countStamped(
                        s, helper, Items.POTION) < 4) {
                    throw new net.minecraft.gametest.framework.GameTestAssertException("等待起酿");
                }
                return; // 成品齐
            }
            if (acc.bmfstown$getBrewTime() > 0) {
                int start = com.bmf.bmfstown.alchemy.AlchemyPace.brewStartTicks(400,
                        helper.getLevel(), helper.absolutePos(s.standRels().get(0)), stand);
                fastest[0] = Math.min(fastest[0], start);
                if (fastest[0] >= 400) {
                    helper.fail("起酿时间未缩短: " + fastest[0] + "t（等级+急火应 <400）");
                }
                throw new net.minecraft.gametest.framework.GameTestAssertException("酿造中，已记录最短起酿 "
                        + fastest[0] + "t");
            }
            throw new net.minecraft.gametest.framework.GameTestAssertException("等待酿造");
        });
    }

    /** 涨经验：整批入库后合成技能经验必须增长。 */
    @GameTest(template = "room11x4x11", timeoutTicks = 24000)
    public static void xpGainsAfterBatch(GameTestHelper helper) {
        var s = com.bmf.bmfstown.gametest.AlchemyScaffold.prepareTown(
                helper, new BlockPos(1, 0, 1), new BlockPos(9, 0, 9), 1);
        com.bmf.bmfstown.gametest.AlchemyScaffold.stockChest(s, helper, false, 1);
        var r = com.bmf.bmfstown.gametest.AlchemyScaffold.spawnAlchemist(s, helper,
                "alch|minecraft:swiftness|none", new BlockPos(4, 1, 5));
        int before = r.getGrowth().skillXp(SkillType.CRAFTING);
        helper.succeedWhen(() -> {
            if (com.bmf.bmfstown.gametest.AlchemyScaffold.countStamped(
                    s, helper, Items.POTION) < 4) {
                throw new net.minecraft.gametest.framework.GameTestAssertException("等待 4 瓶成品");
            }
            if (r.getGrowth().skillXp(SkillType.CRAFTING) <= before) {
                helper.fail("入库后经验未增长");
            }
        });
    }

    /** 玩家干扰：偷走一瓶 → 整批回收重凑 → 最终仍交付 4 瓶盖章成品。 */
    @GameTest(template = "room11x4x11", timeoutTicks = 30000)
    public static void playerInterferenceRecovers(GameTestHelper helper) {
        var s = com.bmf.bmfstown.gametest.AlchemyScaffold.prepareTown(
                helper, new BlockPos(1, 0, 1), new BlockPos(9, 0, 9), 1);
        com.bmf.bmfstown.gametest.AlchemyScaffold.stockChest(s, helper, true, 3);
        com.bmf.bmfstown.gametest.AlchemyScaffold.spawnAlchemist(s, helper,
                "alch|minecraft:swiftness|lingering", new BlockPos(4, 1, 5));
        int[] stolen = {0};
        helper.succeedWhen(() -> {
            var stand = s.stand(helper, s.standRels().get(0));
            // 干扰一次：基础药上瓶且料槽空（红石/火药步间隙）时抽走一瓶
            if (stolen[0] == 0 && stand != null && !stand.getItem(0).isEmpty()
                    && stand.getItem(0).is(Items.POTION) && stand.getItem(3).isEmpty()) {
                stand.removeItem(1, 1);
                stolen[0] = 1;
            }
            if (com.bmf.bmfstown.gametest.AlchemyScaffold.countStamped(
                    s, helper, Items.LINGERING_POTION) < 4) {
                throw new net.minecraft.gametest.framework.GameTestAssertException(
                        "等待干扰后恢复（偷瓶=" + stolen[0] + "）");
            }
        });
    }

    /** 燃料耗尽：台里没燃料 → 自动从箱子补。 */
    @GameTest(template = "room11x4x11", timeoutTicks = 24000)
    public static void fuelRefillFromChest(GameTestHelper helper) {
        var s = com.bmf.bmfstown.gametest.AlchemyScaffold.prepareTown(
                helper, new BlockPos(1, 0, 1), new BlockPos(9, 0, 9), 1);
        com.bmf.bmfstown.gametest.AlchemyScaffold.stockChest(s, helper, false, 1);
        com.bmf.bmfstown.gametest.AlchemyScaffold.spawnAlchemist(s, helper,
                "alch|minecraft:swiftness|none", new BlockPos(4, 1, 5));
        // 台里预置 0 燃料、料槽 4 空
        var stand = s.stand(helper, s.standRels().get(0));
        if (stand != null) {
            ((BrewingStandBlockEntityAccessor) stand).bmfstown$setFuel(0);
        }
        helper.succeedWhen(() -> {
            if (com.bmf.bmfstown.gametest.AlchemyScaffold.countStamped(
                    s, helper, Items.POTION) < 4) {
                throw new net.minecraft.gametest.framework.GameTestAssertException("等待 4 瓶成品");
            }
        });
    }
}
