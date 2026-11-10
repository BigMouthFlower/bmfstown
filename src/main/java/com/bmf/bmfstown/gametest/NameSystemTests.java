package com.bmf.bmfstown.gametest;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.ModEntities;
import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.entity.ResidentNames;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Set;

/**
 * 名字系统 GameTest（v0.15，.ai/designs/name_system.md）：空池不炸、姓×名组合、
 * 整名池回落、镇内去重。纯服务端逻辑；显示层零改动（原版 CustomName）不测。
 *
 * <p>GameTest 服务器会正常加载数据包里的 core.json，所以每个用例先
 * {@link ResidentNames#testOnlyLoad} 注入假池做隔离（同 SkinSystemTests 惯例）。
 */
@GameTestHolder(BmfTown.MOD_ID)
@PrefixGameTestTemplate(false) // 复用现有结构文件（不带小写类名前缀）
public class NameSystemTests {

    private static final class Checks {
        final StringBuilder fails = new StringBuilder();

        void isTrue(boolean cond, String what) {
            if (!cond) {
                this.fails.append(what).append("; ");
            }
        }

        void eq(Object expect, Object actual, String what) {
            if (!expect.equals(actual)) {
                this.fails.append(what).append(": 期望 ").append(expect)
                        .append(" 实际 ").append(actual).append("; ");
            }
        }

        void throwIfAny(GameTestHelper helper, String label) {
            if (this.fails.length() > 0) {
                helper.fail("[" + label + "] " + this.fails);
            } else {
                helper.succeed();
            }
        }
    }

    private static Resident spawnResident(GameTestHelper helper, BlockPos rel) {
        helper.setBlock(new BlockPos(rel.getX(), 0, rel.getZ()), Blocks.SMOOTH_STONE);
        Resident r = helper.spawn(ModEntities.RESIDENT.get(), rel);
        r.setNoAi(true);
        return r;
    }

    // ==========================================================
    //  用例
    // ==========================================================

    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void nameEmptyPoolReturnsNull(GameTestHelper helper) {
        Checks c = new Checks();
        ResidentNames.testOnlyLoad(List.of(), List.of(), List.of());
        c.isTrue(ResidentNames.rawRandom(RandomSource.create()) == null, "空池 rawRandom 返回 null");
        c.isTrue(ResidentNames.random(RandomSource.create(), helper.getLevel()) == null,
                "空池带去重的 random 也返回 null（不炸）");
        c.throwIfAny(helper, "nameEmptyPoolReturnsNull");
    }

    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void nameCombinationFromSurnameAndGiven(GameTestHelper helper) {
        Checks c = new Checks();
        ResidentNames.testOnlyLoad(List.of("陈", "周"), List.of("满仓"), List.of());
        RandomSource random = RandomSource.create();
        Set<String> legal = Set.of("陈满仓", "周满仓");
        for (int i = 0; i < 20; i++) {
            String n = ResidentNames.rawRandom(random);
            c.isTrue(legal.contains(n), "组合结果在姓×名笛卡尔积内（实际 " + n + "）");
        }
        c.throwIfAny(helper, "nameCombinationFromSurnameAndGiven");
    }

    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void nameWholePoolFallback(GameTestHelper helper) {
        Checks c = new Checks();
        // 姓/名池缺失 → 回落整名池
        ResidentNames.testOnlyLoad(List.of(), List.of(), List.of("阿竹", "老陈"));
        RandomSource random = RandomSource.create();
        Set<String> legal = Set.of("阿竹", "老陈");
        for (int i = 0; i < 20; i++) {
            String n = ResidentNames.rawRandom(random);
            c.isTrue(legal.contains(n), "整名池回落抽取（实际 " + n + "）");
        }
        // 整名池也空 → null
        ResidentNames.testOnlyLoad(List.of("陈"), List.of(), List.of());
        c.isTrue(ResidentNames.rawRandom(random) == null,
                "只有姓池（名池空）不成组合，回落整名池也空 → null");
        c.throwIfAny(helper, "nameWholePoolFallback");
    }

    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void nameDedupAvoidsUsedNames(GameTestHelper helper) {
        Checks c = new Checks();
        // 两姓一名：陈满仓被占用后，去重抽取必然命中王满仓
        ResidentNames.testOnlyLoad(List.of("陈", "王"), List.of("满仓"), List.of());
        Resident taken = spawnResident(helper, new BlockPos(2, 1, 2));
        taken.setCustomName(net.minecraft.network.chat.Component.literal("陈满仓"));
        String n = ResidentNames.random(RandomSource.create(), helper.getLevel());
        c.eq("王满仓", n, "已占用名被排除，唯一空闲组合被抽中");
        // 全占用时接受重复（用户拍板：允许重复，不造编号兜底名）
        Resident taken2 = spawnResident(helper, new BlockPos(4, 1, 2));
        taken2.setCustomName(net.minecraft.network.chat.Component.literal("王满仓"));
        String n2 = ResidentNames.random(RandomSource.create(), helper.getLevel());
        c.isTrue("陈满仓".equals(n2) || "王满仓".equals(n2),
                "全占用时回落到允许重复（实际 " + n2 + "）");
        c.throwIfAny(helper, "nameDedupAvoidsUsedNames");
    }
}
