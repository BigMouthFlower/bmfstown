package com.bmf.bmfstown.gametest;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.ModEntities;
import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.entity.domain.EventSpec;
import com.bmf.bmfstown.entity.domain.EventSpecs;
import com.bmf.bmfstown.entity.domain.Events;
import com.bmf.bmfstown.entity.domain.EventType;
import com.bmf.bmfstown.entity.domain.Status;
import com.bmf.bmfstown.entity.profile.PersonalityAxis;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 事件系统·声明表契约测试（决策 #116，第二批全量）。
 *
 * <p>测"声明 vs 实际"，不测数值合理性：24 条声明完整性 + lang key 存在（坑49 防线）、
 * 日限/间隔限流、样板事件真正走 {@link Events} 入口、"颜色=意图方向"（封顶不改定性、
 * 知识金色）、swing 文案分 _pos/_neg、HOPE 乘区覆盖到固定事件。
 */
@GameTestHolder(BmfTown.MOD_ID)
@PrefixGameTestTemplate(false)
public class EventSpecTests {

    // ==========================================================
    //  公共小工具
    // ==========================================================

    private static final class Checks {
        private final StringBuilder fails = new StringBuilder();

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

    /** 生成一个关 AI 的镇民（纯测试桩：不乱走、不触发日常结算）。 */
    private static Resident spawnResident(GameTestHelper helper, BlockPos rel) {
        Resident r = helper.spawn(ModEntities.RESIDENT.get(), rel);
        r.setNoAi(true);
        r.getStatus().setMood(50); // 基准：初始 mood 50（Config.INITIAL_MOOD 默认），显式定死
        // 心态轴固定中性 50：HOPE 乘区 = ×1.0，心情增量即声明基准值（HOPE 全覆盖后仍可精确断言）
        r.getStatus().setAxis(PersonalityAxis.HOPE, 50);
        return r;
    }

    /** 从 classpath 读语言文件（服务端 ResourceManager 只挂 data 包，读不到 assets/lang）。 */
    private static JsonObject lang(String code) {
        try (InputStream in = EventSpecTests.class.getClassLoader()
                .getResourceAsStream("assets/bmfstown/lang/" + code + ".json")) {
            if (in == null) {
                return null;
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (IOException e) {
            return null;
        }
    }

    // ==========================================================
    //  一、声明完整性 + lang key 存在（坑49 防线）
    // ==========================================================

    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void declaredSpecsAndLang(GameTestHelper helper) {
        Checks c = new Checks();
        JsonObject zh = lang("zh_cn");
        JsonObject en = lang("en_us");
        c.isTrue(zh != null && en != null, "lang 双文件 classpath 可读");

        for (EventType t : EventSpecs.declaredTypes()) {
            EventSpec spec = EventSpecs.of(t);
            c.eq(t, spec.type(), "spec.type 回指");
            c.isTrue(spec.thoughtKey() != null && !spec.thoughtKey().isBlank(), t + " 想法 key 空");

            if (zh != null && en != null) {
                String key = Events.THOUGHT_KEY_PREFIX + spec.thoughtKey();
                c.isTrue(zh.has(key), t + " 的 key zh 缺失: " + key);
                c.isTrue(en.has(key), t + " 的 key en 缺失: " + key);
                if (spec.isSwing()) {
                    c.isTrue(zh.has(key + "_pos") && zh.has(key + "_neg"), t + " swing _pos/_neg zh 缺失");
                    c.isTrue(en.has(key + "_pos") && en.has(key + "_neg"), t + " swing _pos/_neg en 缺失");
                }
            }
        }
        // 全量登记（第二批）：每个 EventType 成员都必须有声明，防漏接
        c.eq(EventType.values().length, EventSpecs.declaredTypes().size(), "EventType 全量有声明");
        c.throwIfAny(helper, "declaredSpecsAndLang");
    }

    // ==========================================================
    //  二、样本事件真走 Events + 颜色动态计算
    // ==========================================================

    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void animalLikedOncePerDay(GameTestHelper helper) {
        Checks c = new Checks();
        ServerLevel level = helper.getLevel();
        Resident r = spawnResident(helper, new BlockPos(2, 1, 2));
        Status s = r.getStatus();
        int m0 = s.mood();

        // 直接走统一入口（senseAnimal 仅包了实体扫描，这里验证 happen 的日限）
        Events.happen(r, level, EventType.ANIMAL_LIKED);
        int after1 = s.mood();
        c.eq(m0 + 3, after1, "第一次 +3（mood.animalLiked 默认 3）");
        c.eq(1, s.thoughts().size(), "第一次记 1 条想法");

        Events.happen(r, level, EventType.ANIMAL_LIKED);
        c.eq(after1, s.mood(), "同日第二次无效果（日限）");
        c.eq(1, s.thoughts().size(), "同日第二次不记想法");

        c.throwIfAny(helper, "animalLikedOncePerDay");
    }

    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void readingAndTavernRoute(GameTestHelper helper) {
        Checks c = new Checks();
        ServerLevel level = helper.getLevel();
        Resident r = spawnResident(helper, new BlockPos(2, 1, 2));
        Status s = r.getStatus();

        // 读书：moodOverride 传 6（≠ 默认 5），证明调用方覆盖生效；clamp NONE 直加
        s.setMood(50);
        s.readEnjoyed(r, level, 123L, 6);
        c.eq(56, s.mood(), "读书 +6（override）");
        var read = s.thoughts().getLast();
        c.eq("mood.bmfstown.thought.reading", read.key(), "读书想法 key");
        c.eq("idle:123", read.arg(), "读书想法 arg");
        c.eq(Status.TONE_POSITIVE, read.tone(), "读书想法绿（实算 +6 > 0）");

        // 酒馆：mood +3、饥饿 +2，走 happen
        s.setMood(50);
        s.setHunger(10);
        s.drankAtTavern(r, level, 3, 2);
        c.eq(53, s.mood(), "喝酒 +3");
        c.eq(12, s.hunger(), "喝酒饥饿 +2");
        var tavern = s.thoughts().getLast();
        c.eq("mood.bmfstown.thought.tavern", tavern.key(), "喝酒想法 key");
        c.eq(Status.TONE_POSITIVE, tavern.tone(), "喝酒想法绿");

        c.throwIfAny(helper, "readingAndTavernRoute");
    }

    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void learnedKnowledgeTone(GameTestHelper helper) {
        Checks c = new Checks();
        ServerLevel level = helper.getLevel();
        Resident r = spawnResident(helper, new BlockPos(2, 1, 2));
        Status s = r.getStatus();

        s.setMood(42);
        s.skillBookLearned(r, level, "some_book");
        c.eq(42, s.mood(), "学书不改心情");
        var learned = s.thoughts().getLast();
        c.eq("mood.bmfstown.thought.learned", learned.key(), "学书想法 key");
        c.eq("book:some_book", learned.arg(), "学书想法 arg");
        c.eq(Status.TONE_KNOWLEDGE, learned.tone(), "学书想法金色（知识专属色）");

        c.throwIfAny(helper, "learnedKnowledgeTone");
    }

    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void cappedMoodKeepsPositiveTone(GameTestHelper helper) {
        Checks c = new Checks();
        ServerLevel level = helper.getLevel();
        Resident r = spawnResident(helper, new BlockPos(2, 1, 2));
        Status s = r.getStatus();

        // 心情已满：实际 +0，但"读书"对这位居民仍定性为正面 → 想法仍绿
        // （颜色看意图方向，不看落账；封顶不该把开心事变灰）
        s.setMood(Status.MAX_MOOD);
        s.readEnjoyed(r, level, 999L, 5);
        c.eq(Status.MAX_MOOD, s.mood(), "心情封顶不变");
        var thought = s.thoughts().getLast();
        c.eq(Status.TONE_POSITIVE, thought.tone(), "封顶不改定性：读书仍是绿");

        c.throwIfAny(helper, "cappedMoodKeepsPositiveTone");
    }

    // ==========================================================
    //  三、第二批执行器契约：swing 文案 / HOPE 乘区 / interval 闸
    // ==========================================================

    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void swingThoughtSplitByAxis(GameTestHelper helper) {
        Checks c = new Checks();
        ServerLevel level = helper.getLevel();

        // 嗜血（CRUELTY=80）：暴力基准为正 → 想法绿 + _pos 文案
        Resident cruel = spawnResident(helper, new BlockPos(2, 1, 2));
        cruel.getStatus().setAxis(PersonalityAxis.CRUELTY, 80);
        cruel.getStatus().noteDeathNearby(cruel, level);
        var pos = cruel.getStatus().thoughts().getLast();
        c.eq(Status.TONE_POSITIVE, pos.tone(), "嗜血者死亡想法=绿");
        c.eq("mood.bmfstown.thought.death_nearby_pos", pos.key(), "嗜血者用 _pos 文案");

        // 仁慈（CRUELTY=20）：暴力基准为负 → 想法红 + _neg 文案
        Resident kind = spawnResident(helper, new BlockPos(6, 1, 2));
        kind.getStatus().setAxis(PersonalityAxis.CRUELTY, 20);
        kind.getStatus().noteDeathNearby(kind, level);
        var neg = kind.getStatus().thoughts().getLast();
        c.eq(Status.TONE_NEGATIVE, neg.tone(), "仁慈者死亡想法=红");
        c.eq("mood.bmfstown.thought.death_nearby_neg", neg.key(), "仁慈者用 _neg 文案");

        c.throwIfAny(helper, "swingThoughtSplitByAxis");
    }

    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void hopeScalesFixedEvents(GameTestHelper helper) {
        Checks c = new Checks();
        ServerLevel level = helper.getLevel();

        // 乐观（HOPE=100）：吃爱吃 base +3 → ×1.5 → +5（HOPE 覆盖到固定偏好事件）
        Resident optimist = spawnResident(helper, new BlockPos(2, 1, 2));
        optimist.getStatus().setAxis(PersonalityAxis.HOPE, 100);
        int m0 = optimist.getStatus().mood();
        Events.happen(optimist, level, EventType.ATE_FAVORED);
        c.eq(m0 + 5, optimist.getStatus().mood(), "乐观者吃爱吃 +5（×1.5）");

        // 悲观（HOPE=0）：遇怪 base -3 → ×1.5 → -5
        Resident pessimist = spawnResident(helper, new BlockPos(6, 1, 2));
        pessimist.getStatus().setAxis(PersonalityAxis.HOPE, 0);
        int m1 = pessimist.getStatus().mood();
        Events.happen(pessimist, level, EventType.MOB_DISLIKED);
        c.eq(m1 - 5, pessimist.getStatus().mood(), "悲观者遇怪 -5（×1.5）");

        c.throwIfAny(helper, "hopeScalesFixedEvents");
    }

    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void intervalGatesSecondHit(GameTestHelper helper) {
        Checks c = new Checks();
        ServerLevel level = helper.getLevel();
        Resident r = spawnResident(helper, new BlockPos(2, 1, 2));
        Status s = r.getStatus();
        s.setMood(50);

        Events.happen(r, level, EventType.MOB_DISLIKED);
        c.eq(47, s.mood(), "遇怪第一次 -3");

        Events.happen(r, level, EventType.MOB_DISLIKED);
        c.eq(47, s.mood(), "间隔未过第二次无效果（interval 闸）");

        c.throwIfAny(helper, "intervalGatesSecondHit");
    }
}