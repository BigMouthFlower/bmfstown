package com.bmf.bmfstown.gametest;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.ModEntities;
import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.entity.ResidentTitles;
import com.bmf.bmfstown.entity.domain.TitleState;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;

/**
 * 称号系统 GameTest（v0.16 数据结构先行，#104）：授予幂等/佩戴门禁/存档往返/
 * 文案回落/展示串拼接。具体称号内容与 UI 展示后补，此处只测数据结构。
 */
@GameTestHolder(BmfTown.MOD_ID)
@PrefixGameTestTemplate(false) // 复用现有结构文件（不带小写类名前缀）
public class TitleTests {

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

    // ==========================================================
    //  TitleState 域
    // ==========================================================

    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void titleAwardIdempotentAndOrdered(GameTestHelper helper) {
        Checks c = new Checks();
        TitleState t = new TitleState();
        c.isTrue(t.award("slayer"), "首次授予返回 true");
        c.isTrue(!t.award("slayer"), "重复授予幂等返回 false");
        c.isTrue(!t.award("  "), "空白 id 拒绝");
        c.isTrue(!t.award(null), "null id 拒绝");
        c.isTrue(t.award("builder"), "第二个称号授予");
        c.eq(List.of("slayer", "builder"), t.earnedIds(), "获得顺序保留（履历语义）");
        c.throwIfAny(helper, "titleAwardIdempotentAndOrdered");
    }

    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void titleSetActiveRequiresEarned(GameTestHelper helper) {
        Checks c = new Checks();
        TitleState t = new TitleState();
        c.isTrue(!t.setActive("slayer"), "未获得的称号不能佩戴");
        t.award("slayer");
        c.isTrue(t.setActive("slayer"), "已获得才能佩戴");
        c.eq("slayer", t.activeId(), "佩戴生效");
        c.isTrue(t.setActive(""), "空串摘下");
        c.eq("", t.activeId(), "摘下生效");
        // 授予不自动佩戴（展示接线后由 UI/成就逻辑决定）
        TitleState t2 = new TitleState();
        t2.award("slayer");
        c.eq("", t2.activeId(), "授予不自动佩戴");
        c.throwIfAny(helper, "titleSetActiveRequiresEarned");
    }

    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void titleSaveLoadRoundtrip(GameTestHelper helper) {
        Checks c = new Checks();
        TitleState t = new TitleState();
        t.award("slayer");
        t.award("builder");
        t.setActive("builder");
        CompoundTag tag = t.save();
        TitleState t2 = new TitleState();
        t2.loadFrom(tag);
        c.eq(List.of("slayer", "builder"), t2.earnedIds(), "存档往返：获得列表保序还原");
        c.eq("builder", t2.activeId(), "存档往返：佩戴还原");
        // 手改存档悬空佩戴被拒绝还原（防 Active 指向未持有的 id）
        CompoundTag bad = t.save();
        bad.putString("Active", "ghost");
        TitleState t3 = new TitleState();
        t3.loadFrom(bad);
        c.eq("", t3.activeId(), "悬空佩戴在 loadFrom 被拒");
        c.throwIfAny(helper, "titleSaveLoadRoundtrip");
    }

    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void titleRevokeDropsActive(GameTestHelper helper) {
        Checks c = new Checks();
        TitleState t = new TitleState();
        t.award("slayer");
        t.award("builder");
        t.setActive("slayer");
        c.isTrue(t.revoke("slayer"), "撤销已持有返回 true");
        c.eq("", t.activeId(), "撤销连带摘下佩戴中的同 id");
        c.isTrue(!t.revoke("slayer"), "撤销不存在的返回 false");
        c.eq(List.of("builder"), t.earnedIds(), "其余称号不受影响");
        c.throwIfAny(helper, "titleRevokeDropsActive");
    }

    // ==========================================================
    //  ResidentTitles 注册表 + 展示串
    // ==========================================================

    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void titleTextFallbackAndDefined(GameTestHelper helper) {
        Checks c = new Checks();
        ResidentTitles.testOnlyLoad(Map.of("slayer", "屠龙者"));
        c.eq("屠龙者", ResidentTitles.textOf("slayer"), "已登记返回登记文本");
        c.eq("ghost", ResidentTitles.textOf("ghost"), "未登记回落 id 本身（先授予后补文案）");
        c.isTrue(ResidentTitles.isDefined("slayer"), "isDefined 已登记");
        c.isTrue(!ResidentTitles.isDefined("ghost"), "isDefined 未登记");
        c.eq("", ResidentTitles.textOf(""), "空 id 返回空串");
        c.eq("", ResidentTitles.textOf(null), "null id 返回空串");
        ResidentTitles.testOnlyLoad(Map.of());
        c.throwIfAny(helper, "titleTextFallbackAndDefined");
    }

    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void titleTitledNameOnResident(GameTestHelper helper) {
        Checks c = new Checks();
        ResidentTitles.testOnlyLoad(Map.of("slayer", "屠龙者"));
        helper.setBlock(new BlockPos(2, 0, 2), Blocks.SMOOTH_STONE);
        Resident r = helper.spawn(ModEntities.RESIDENT.get(), new BlockPos(2, 1, 2));
        r.setNoAi(true);
        r.setCustomName(net.minecraft.network.chat.Component.literal("黑之剑"));
        c.eq("黑之剑", r.titledName(), "未佩戴返回原名");
        r.getTitleState().award("slayer");
        c.eq("黑之剑", r.titledName(), "授予不自动佩戴，展示串仍是原名");
        r.getTitleState().setActive("slayer");
        c.eq("屠龙者黑之剑", r.titledName(), "佩戴后展示串=称号+名字直接相连");
        c.throwIfAny(helper, "titleTitledNameOnResident");
    }
}
