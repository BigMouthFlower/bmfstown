package com.bmf.bmfstown.gametest;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.ModEntities;
import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.skin.SkinCatalog;
import com.bmf.bmfstown.skin.SkinEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 皮肤系统 GameTest（v0.15，.ai/designs/skin_system.md §6）：
 * 目录空不炸、测试目录注入后随机指派、NBT 往返终身保留、懒生成（旧档/summon 路径）、
 * 坏 id 回落。纯服务端逻辑；渲染不测（无头服务器不覆盖）。
 *
 * <p>GameTest 环境没有皮肤清单（mod 本体不内置），所以"目录有货"的用例
 * 先用 {@link SkinCatalog#testOnlyLoad} 注入假目录。
 */
@GameTestHolder(BmfTown.MOD_ID)
@PrefixGameTestTemplate(false) // 复用现有结构文件（不带小写类名前缀）
public class SkinSystemTests {

    // ==========================================================
    //  公共小工具（同 PersonalityAxisTests 惯例）
    // ==========================================================

    private static final class Checks {
        final StringBuilder fails = new StringBuilder();

        void eq(Object expect, Object actual, String what) {
            if (!expect.equals(actual)) {
                this.fails.append(what).append(": 期望 ").append(expect)
                        .append(" 实际 ").append(actual).append("; ");
            }
        }

        void isTrue(boolean cond, String what) {
            if (!cond) {
                this.fails.append(what).append("; ");
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

    private static void layFloor(GameTestHelper helper, BlockPos center) {
        helper.setBlock(new BlockPos(center.getX(), 0, center.getZ()), Blocks.SMOOTH_STONE);
    }

    private static Resident spawnResident(GameTestHelper helper, BlockPos rel) {
        layFloor(helper, rel);
        Resident r = helper.spawn(ModEntities.RESIDENT.get(), rel);
        r.setNoAi(true);
        return r;
    }

    private static SkinEntry entry(String id, boolean slim) {
        return new SkinEntry(id, ResourceLocation.withDefaultNamespace(
                "textures/entity/zombie/zombie.png"), slim, "test", List.of());
    }

    // ==========================================================
    //  用例
    // ==========================================================

    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void skinEmptyCatalogNoCrash(GameTestHelper helper) {
        Checks c = new Checks();
        // 还原成空目录（mod 本体不内置皮肤，GameTest 环境本就为空；防御性清一次）
        SkinCatalog.testOnlyLoad(List.of());
        c.isTrue(SkinCatalog.entries().isEmpty(), "目录为空");
        c.isTrue(SkinCatalog.randomId(RandomSource.create()) == null, "空目录 randomId 返回 null");
        // 空目录下指派：不炸、保持空串（渲染回退占位贴图）
        Resident r = spawnResident(helper, new BlockPos(2, 1, 2));
        r.ensureSkinAssigned();
        c.eq("", r.getSkinId(), "空目录指派后 skinId 仍为空串");
        c.throwIfAny(helper, "skinEmptyCatalogNoCrash");
    }

    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void skinAssignFromCatalog(GameTestHelper helper) {
        Checks c = new Checks();
        SkinCatalog.testOnlyLoad(List.of(
                entry("townsfolk/a", false),
                entry("townsfolk/b", true),
                entry("townsfolk/c", false)));
        Resident r = spawnResident(helper, new BlockPos(2, 1, 2));
        r.ensureSkinAssigned();
        String id = r.getSkinId();
        c.isTrue(!id.isEmpty(), "目录有货时指派非空");
        c.isTrue(SkinCatalog.get(id) != null, "指派的 id 在目录里");
        // 再 ensure 不换脸（终身保留：已指派就不再动）
        r.ensureSkinAssigned();
        c.eq(id, r.getSkinId(), "二次 ensure 不换脸");
        c.throwIfAny(helper, "skinAssignFromCatalog");
    }

    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void skinNbtRoundtrip(GameTestHelper helper) {
        Checks c = new Checks();
        SkinCatalog.testOnlyLoad(List.of(entry("townsfolk/a", true)));
        Resident r = spawnResident(helper, new BlockPos(2, 1, 2));
        r.setSkinId("townsfolk/a");
        // 实体级 NBT 往返：save → 全新 Resident load → skinId 保持
        CompoundTag tag = new CompoundTag();
        r.addAdditionalSaveData(tag);
        c.eq("townsfolk/a", tag.getString("SkinId"), "SkinId 落 NBT");
        Resident r2 = helper.spawn(ModEntities.RESIDENT.get(), new BlockPos(4, 1, 2));
        r2.setNoAi(true);
        r2.readAdditionalSaveData(tag);
        c.eq("townsfolk/a", r2.getSkinId(), "读档后 skinId 保持");
        // 读档后再 ensure 不换脸（终身保留语义）
        r2.ensureSkinAssigned();
        c.eq("townsfolk/a", r2.getSkinId(), "读档后 ensure 不换脸");
        c.throwIfAny(helper, "skinNbtRoundtrip");
    }

    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void skinLazyGenerationForLegacySave(GameTestHelper helper) {
        Checks c = new Checks();
        SkinCatalog.testOnlyLoad(List.of(entry("townsfolk/a", false)));
        Resident r = spawnResident(helper, new BlockPos(2, 1, 2));
        // 模拟旧档：NBT 里没有 SkinId 键 → load 后空串 → ensure 懒补抽
        CompoundTag tag = new CompoundTag();
        r.addAdditionalSaveData(tag);
        tag.remove("SkinId");
        Resident r2 = helper.spawn(ModEntities.RESIDENT.get(), new BlockPos(4, 1, 2));
        r2.setNoAi(true);
        r2.readAdditionalSaveData(tag);
        c.eq("", r2.getSkinId(), "旧档读入后 skinId 为空");
        r2.ensureSkinAssigned();
        c.eq("townsfolk/a", r2.getSkinId(), "懒生成补上目录里唯一的皮肤");
        c.throwIfAny(helper, "skinLazyGenerationForLegacySave");
    }

    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void skinUnknownIdFallsBack(GameTestHelper helper) {
        Checks c = new Checks();
        SkinCatalog.testOnlyLoad(List.of(entry("townsfolk/a", false)));
        Resident r = spawnResident(helper, new BlockPos(2, 1, 2));
        // 皮肤被删后存档里残留野 id：不炸、原样保留（不自动换脸），渲染侧回退占位
        r.setSkinId("deleted/from_some_pack");
        r.ensureSkinAssigned();
        c.eq("deleted/from_some_pack", r.getSkinId(), "野 id 原样保留（不自动重指派）");
        c.isTrue(SkinCatalog.get(r.getSkinId()) == null, "野 id 查不到（客户端回退占位贴图）");
        c.throwIfAny(helper, "skinUnknownIdFallsBack");
    }
}
