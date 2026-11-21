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

    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void nameGroupedPoolCombination(GameTestHelper helper) {
        Checks c = new Checks();
        // v0.16 前缀+后缀分组池（#104）：随机前缀+随机后缀直接相连，无分隔符
        ResidentNames.testOnlyLoadGrouped(
                List.of(ResidentNames.PrefixEntry.plain("黑"),
                        ResidentNames.PrefixEntry.plain("活")),
                List.of("之剑", "闪电"),
                List.of());
        RandomSource random = RandomSource.create();
        Set<String> legal = Set.of("黑之剑", "黑闪电", "活之剑", "活闪电");
        for (int i = 0; i < 30; i++) {
            String n = ResidentNames.rawRandom(random);
            c.isTrue(legal.contains(n), "分组池组合=前缀+后缀直连（实际 " + n + "）");
        }
        c.throwIfAny(helper, "nameGroupedPoolCombination");
    }

    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void namePrefixExcludesZhiSuffix(GameTestHelper helper) {
        Checks c = new Checks();
        // 用户表格标注"特殊/动词前缀（不和之共存）"：禁配前缀绝不组合'之'后缀
        // 2026-11-21 用户收紧：后缀**任何位置含'之'**都不配（不再只是开头）
        ResidentNames.testOnlyLoadGrouped(
                List.of(new ResidentNames.PrefixEntry("呼吸的", List.of("之")),
                        ResidentNames.PrefixEntry.plain("黑")),
                List.of("之剑", "巨石之环", "铁锄头"),
                List.of());
        RandomSource random = RandomSource.create();
        for (int i = 0; i < 60; i++) {
            String n = ResidentNames.rawRandom(random);
            c.isTrue(!"呼吸的之剑".equals(n), "禁配前缀不得产生'呼吸的之剑'");
            c.isTrue(!"呼吸的巨石之环".equals(n), "后缀中段含'之'同样禁配（实际 " + n + "）");
            c.isTrue(n != null && (n.equals("呼吸的铁锄头") || n.equals("黑之剑")
                    || n.equals("黑巨石之环") || n.equals("黑铁锄头")),
                    "组合结果在合法集内（实际 " + n + "）");
        }
        // 禁配筛空的极端：后缀池全是'之'系 → 放开全池兜底（绝不无名）
        ResidentNames.testOnlyLoadGrouped(
                List.of(new ResidentNames.PrefixEntry("呼吸的", List.of("之"))),
                List.of("之剑", "之斧"),
                List.of());
        String n2 = ResidentNames.rawRandom(random);
        c.isTrue("呼吸的之剑".equals(n2) || "呼吸的之斧".equals(n2),
                "禁配筛空时放开兜底不返回 null（实际 " + n2 + "）");
        c.throwIfAny(helper, "namePrefixExcludesZhiSuffix");
    }

    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void nameAnimalPrefixRestriction(GameTestHelper helper) {
        Checks c = new Checks();
        // 2026-11-21 拍板：动物前缀只能配动物后缀（组内颜色词同理，不得配铜铁/山脉）
        java.util.List<ResidentNames.SuffixEntry> suf = new java.util.ArrayList<>();
        for (String s : List.of("公牛", "狼", "山羊")) {
            suf.add(new ResidentNames.SuffixEntry(s, "animal", 1.0));
        }
        for (String s : List.of("铁锄头", "山脉", "闪电")) {
            suf.add(new ResidentNames.SuffixEntry(s, "item", 1.0));
        }
        ResidentNames.testOnlyLoadFull(
                List.of(new ResidentNames.PrefixEntry("黑", List.of(), List.of("animal")),
                        ResidentNames.PrefixEntry.plain("活")),
                suf, List.of());
        RandomSource random = RandomSource.create(20261121L);
        Set<String> animalSuf = Set.of("公牛", "狼", "山羊");
        int animalPrefixDraws = 0;
        for (int i = 0; i < 3000; i++) {
            String n = ResidentNames.rawRandom(random);
            c.isTrue(n != null, "组合池非空不应返回 null");
            if (n.startsWith("黑")) {
                animalPrefixDraws++;
                c.isTrue(animalSuf.contains(n.substring(1)),
                        "动物前缀名字的后缀必须是动物组（实际 " + n + "）");
            } else {
                c.isTrue(n.startsWith("活"), "普通前缀不受组限制（实际 " + n + "）");
            }
        }
        c.isTrue(animalPrefixDraws > 1000, "黑前缀应被大量抽到（实际 " + animalPrefixDraws + "）");
        // 筛空兜底：组限制指向不存在的组 → 放开全池，绝不无名
        ResidentNames.testOnlyLoadFull(
                List.of(new ResidentNames.PrefixEntry("黑", List.of(), List.of("不存在组"))),
                List.of(new ResidentNames.SuffixEntry("铁锄头", "item", 1.0)), List.of());
        String n2 = ResidentNames.rawRandom(random);
        c.isTrue("黑铁锄头".equals(n2), "组限制筛空时放开全池兜底（实际 " + n2 + "）");
        c.throwIfAny(helper, "nameAnimalPrefixRestriction");
    }

    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void nameTerrainHalfWeightAndChild(GameTestHelper helper) {
        Checks c = new Checks();
        // 2026-11-21 拍板：地形后缀权重减半（0.5）+ 抽中地形 30% 追加"之子"
        List<ResidentNames.SuffixEntry> suf = List.of(
                new ResidentNames.SuffixEntry("铁锄头", "item", 1.0),
                new ResidentNames.SuffixEntry("山脉", "terrain", 0.5));
        ResidentNames.testOnlyLoadFull(List.of(ResidentNames.PrefixEntry.plain("黑")), suf, List.of());
        RandomSource random = RandomSource.create(20261121L);
        int terrain = 0;
        int child = 0;
        int total = 20000;
        for (int i = 0; i < total; i++) {
            String n = ResidentNames.rawRandom(random);
            if (n.startsWith("黑山脉")) {
                terrain++;
                if (n.endsWith("之子")) {
                    child++;
                }
            }
        }
        double share = (double) terrain / total;
        // 权重 0.5 : 1 → 地形期望占比 1/3 ≈ 0.333（固定种子=确定性，宽区间防脆）
        c.isTrue(share > 0.28 && share < 0.40,
                "地形后缀权重减半后占比≈1/3（实际 " + share + "）");
        c.isTrue(child > 0, "地形后缀应产生'之子'尾缀（30%）");
        c.isTrue(terrain > child, "不是每个地形名都带'之子'（70% 不带）");
        // '不和之共存'前缀不追加'之子'（呼吸的+地形组仍可组合，但无尾缀）
        ResidentNames.testOnlyLoadFull(
                List.of(new ResidentNames.PrefixEntry("呼吸的", List.of("之"))), suf, List.of());
        for (int i = 0; i < 500; i++) {
            String n = ResidentNames.rawRandom(random);
            c.isTrue(!n.endsWith("之子"), "禁配'之'的前缀不得追加'之子'（实际 " + n + "）");
        }
        c.throwIfAny(helper, "nameTerrainHalfWeightAndChild");
    }
}
