package com.bmf.bmfstown.gametest;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.ModEntities;
import com.bmf.bmfstown.entity.HandDirector;
import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.entity.WeaponPref;
import com.bmf.bmfstown.entity.domain.JobState;
import com.bmf.bmfstown.entity.profile.ProfessionType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 手装导演 GameTest（决策 #49）：
 * 装填/卸载往返、附魔感知选武器（亡灵杀手打僵尸加分）、
 * 偏好类别过滤与回落（无该类别 → 全背包最高伤害）、工具也算武器、
 * 进食收尾不再恢复原主手（主手清空交导演——导演体系的核心契约）。
 */
@GameTestHolder(BmfTown.MOD_ID)
@PrefixGameTestTemplate(false) // 复用现有结构文件（不带小写类名前缀）
public class HandDirectorTests {

    private static Resident spawnResident(GameTestHelper helper, BlockPos rel) {
        Resident r = helper.spawn(ModEntities.RESIDENT.get(), rel);
        r.setNoAi(true);
        return r;
    }

    /** 给镇民背包塞一格物品（ResidentInventory 行优先 3×9，下标即格号）。 */
    private static void bag(Resident r, int slot, ItemStack stack) {
        r.getInventory().setItem(slot, stack);
    }

    /** 亡灵杀手 I 的 Holder（1.21.1 附魔数据驱动，从注册表取）。 */
    private static net.minecraft.core.Holder<Enchantment> smite(GameTestHelper helper) {
        return helper.getLevel().registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.SMITE);
    }

    // ==========================================================
    //  一、装填与卸载往返
    // ==========================================================

    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void directorArmsAndDisarms(GameTestHelper helper) {
        Resident r = spawnResident(helper, new BlockPos(2, 1, 2));
        var zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(4, 1, 2));
        zombie.setNoAi(true);
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        bag(r, 0, sword);
        HandDirector d = r.handDirector();

        // 无目标：导演不装填（空手是合法默认态）
        d.tick();
        if (!r.getMainHandItem().isEmpty()) {
            helper.fail("无目标不应装填，主手却拿了 " + r.getMainHandItem());
        }

        // 目标出现：下一 tick 剑上主手
        r.setTarget(zombie);
        d.tick();
        if (r.getMainHandItem().getItem() != Items.IRON_SWORD) {
            helper.fail("有目标应装填背包里的剑，主手却是 " + r.getMainHandItem());
        }

        // 同一目标继续：不折腾（引用不变、主手不空）
        ItemStack armed = r.getMainHandItem();
        d.tick();
        if (r.getMainHandItem() != armed) {
            helper.fail("同一目标不应重复换装");
        }

        // 目标消失：剑回背包，主手清空
        r.setTarget(null);
        d.tick();
        if (!r.getMainHandItem().isEmpty()) {
            helper.fail("目标消失应卸载，主手仍是 " + r.getMainHandItem());
        }
        boolean back = false;
        for (int i = 0; i < r.getInventory().getContainerSize(); i++) {
            if (r.getInventory().getItem(i).getItem() == Items.IRON_SWORD) {
                back = true;
            }
        }
        if (!back) {
            helper.fail("卸载后剑应回背包，背包里没有剑");
        }
        helper.succeed();
    }

    // ==========================================================
    //  二、附魔感知：亡灵杀手对僵尸加分（原版 modifyDamage 结算）
    // ==========================================================

    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void directorPrefersSmiteAgainstUndead(GameTestHelper helper) {
        Resident r = spawnResident(helper, new BlockPos(2, 1, 2));
        var zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(4, 1, 2));
        zombie.setNoAi(true);

        ItemStack plain = new ItemStack(Items.IRON_SWORD);
        ItemStack smiteSword = new ItemStack(Items.IRON_SWORD);
        smiteSword.enchant(smite(helper), 1); // 亡灵杀手 I：对不死 +2.5
        bag(r, 0, plain);
        bag(r, 1, smiteSword);

        r.setTarget(zombie);
        r.handDirector().tick();
        // 两把铁剑面板同为 7，打僵尸时带亡灵杀手的那把期望更高 → 选中它
        ItemStack main = r.getMainHandItem();
        if (main.getItem() != Items.IRON_SWORD) {
            helper.fail("应装填铁剑，主手却是 " + main);
        }
        if (main.getEnchantmentLevel(smite(helper)) != 1) {
            helper.fail("打僵尸应优先选亡灵杀手剑，选中的却没带附魔");
        }
        helper.succeed();
    }

    // ==========================================================
    //  三、偏好类别：过滤优先，无该类别回落全背包最高
    // ==========================================================

    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void directorWeaponPrefFilterAndFallback(GameTestHelper helper) {
        Resident r = spawnResident(helper, new BlockPos(2, 1, 2));
        var zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(4, 1, 2));
        zombie.setNoAi(true);
        r.getIdentity().setProfession(ProfessionType.WARRIOR);
        JobState job = r.getJobState();

        // 伤害口径（原版）：铁剑 +5→6，铁斧 +8→9（斧比剑狠）
        bag(r, 0, new ItemStack(Items.IRON_SWORD));
        bag(r, 1, new ItemStack(Items.IRON_AXE));

        // 偏好 NBT 往返
        job.setWeaponPref(WeaponPref.AXE);
        if (job.weaponPref() != WeaponPref.AXE) {
            helper.fail("武器偏好 NBT 往返失败：" + job.weaponPref());
        }

        // 偏好斧：类内只有铁斧 → 选斧（过滤器生效，不是统一比较器）
        r.setTarget(zombie);
        r.handDirector().tick();
        if (r.getMainHandItem().getItem() != Items.IRON_AXE) {
            helper.fail("偏好斧应选斧，主手却是 " + r.getMainHandItem());
        }

        // 清场：把斧从背包撤走 → 背包只剩铁剑
        r.setTarget(null);
        r.handDirector().tick();
        for (int i = 0; i < r.getInventory().getContainerSize(); i++) {
            if (r.getInventory().getItem(i).getItem() == Items.IRON_AXE) {
                r.getInventory().setItem(i, ItemStack.EMPTY);
            }
        }

        // 偏好重锤但背包没有 → 回落全背包最高（剩铁剑 6，用户拍板：不空手）
        job.setWeaponPref(WeaponPref.MACE);
        r.setTarget(zombie);
        r.handDirector().tick();
        if (r.getMainHandItem().getItem() != Items.IRON_SWORD) {
            helper.fail("偏好重锤但背包没有 → 应回落全背包最高的铁剑，主手却是 "
                    + r.getMainHandItem());
        }
        helper.succeed();
    }

    // ==========================================================
    //  四、工具也算武器：只有镐时宁可拿镐不空手
    // ==========================================================

    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void directorToolsCountAsWeapons(GameTestHelper helper) {
        Resident r = spawnResident(helper, new BlockPos(2, 1, 2));
        var zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(4, 1, 2));
        zombie.setNoAi(true);
        bag(r, 0, new ItemStack(Items.IRON_PICKAXE)); // 攻击伤害 2 > 空手 1

        r.setTarget(zombie);
        r.handDirector().tick();
        if (r.getMainHandItem().getItem() != Items.IRON_PICKAXE) {
            helper.fail("背包只有镐也应拿镐（伤害高于空手），主手却是 " + r.getMainHandItem());
        }
        helper.succeed();
    }

    // ==========================================================
    //  五、进食收尾不再恢复原主手（端到端：真实 AI 跑完一口）
    // ==========================================================

    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 600)
    public static void eatSettleLeavesMainhandEmpty(GameTestHelper helper) {
        Resident r = helper.spawn(ModEntities.RESIDENT.get(), new BlockPos(6, 1, 6));
        // 开 AI：进食状态机跑在 customServerAiStep 里，必须让它自己 tick
        r.getStatus().setHunger(18); // 曲奇营养 2：18+2=20 恰好吃得下（不浪费门槛）
        bag(r, 0, new ItemStack(Items.COOKIE));
        r.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));

        // 等 AI 自己完成"对调 → 咀嚼 → 收尾"。收尾后契约：主手空（不再恢复剑）、
        // 剑仍在背包、曲奇消失、饥饿 +2。
        helper.startSequence().thenExecuteAfter(120, () -> {
            if (!r.getMainHandItem().isEmpty()) {
                helper.fail("进食收尾后主手应清空（不再恢复原物），实际是 "
                        + r.getMainHandItem());
            }
            boolean swordInBag = false;
            for (int i = 0; i < r.getInventory().getContainerSize(); i++) {
                if (r.getInventory().getItem(i).getItem() == Items.IRON_SWORD) {
                    swordInBag = true;
                }
            }
            if (!swordInBag) {
                helper.fail("原主手的剑应仍在背包");
            }
            if (r.getStatus().hunger() < 20) {
                helper.fail("曲奇应被吃完（饥饿应到 20），实际 " + r.getStatus().hunger());
            }
            helper.succeed();
        }).thenSucceed();
    }
}
