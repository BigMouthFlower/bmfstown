package com.bmf.bmfstown.gametest;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.ModEntities;
import com.bmf.bmfstown.config.Config;
import com.bmf.bmfstown.entity.CourageGoals;
import com.bmf.bmfstown.entity.ResidentAggroHandler;
import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.entity.profile.EfficiencyMath;
import com.bmf.bmfstown.entity.profile.PersonalityAxis;
import com.bmf.bmfstown.entity.profile.PersonalityFormulas;
import com.bmf.bmfstown.entity.profile.ProfessionType;
import com.bmf.bmfstown.entity.domain.Status;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 性格轴系统 GameTest（v0.14 多轴改版，决策 #44）：
 * 纯公式（心态乘区四例验算 / 线性过零 / 阈值 80 / 行为旋钮极值）、
 * 轴抽取与 NBT 往返、心情事件方向（服务端真实事件入口）、
 * 勇气 Goal 规则（还手/逃跑 50 分一刀切）、怪物仇恨注入、晨夜窗口偏移、效率段。
 */
@GameTestHolder(BmfTown.MOD_ID)
@PrefixGameTestTemplate(false) // 复用现有结构文件（不带小写类名前缀）
public class PersonalityAxisTests {

    // ==========================================================
    //  公共小工具
    // ==========================================================

    /** 断言收集器：一次跑完收集全部错误再统一上报，方便一次看全。 */
    private static final class Checks {
        final StringBuilder fails = new StringBuilder();

        void eq(Object expect, Object actual, String what) {
            if (!expect.equals(actual)) {
                this.fails.append(what).append(": 期望 ").append(expect)
                        .append(" 实际 ").append(actual).append("; ");
            }
        }

        void eq(double expect, double actual, double eps, String what) {
            if (Math.abs(expect - actual) > eps) {
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

    /** 摆一块地面（防御性；room13x6x11 本身有地板，setBlock 幂等无害）。 */
    private static void layFloor(GameTestHelper helper, BlockPos center) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                helper.setBlock(new BlockPos(center.getX() + dx, 0, center.getZ() + dz),
                        Blocks.SMOOTH_STONE);
            }
        }
    }

    /** 生成一个关 AI 的镇民（纯测试桩：不乱走、不触发日常结算）。 */
    private static Resident spawnResident(GameTestHelper helper, BlockPos rel) {
        layFloor(helper, rel);
        Resident r = helper.spawn(ModEntities.RESIDENT.get(), rel);
        r.setNoAi(true);
        return r;
    }

    // ==========================================================
    //  一、纯公式：心态乘区（用户四例验算锁死）
    // ==========================================================

    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void formulaHopeResolve(GameTestHelper helper) {
        Checks c = new Checks();
        // 乘数：c=(v−50)/100
        c.eq(1.5, PersonalityFormulas.hopeMultiplier(100, true), 1e-9, "乐观满格好事乘数");
        c.eq(0.5, PersonalityFormulas.hopeMultiplier(100, false), 1e-9, "乐观满格坏事乘数");
        c.eq(0.5, PersonalityFormulas.hopeMultiplier(0, true), 1e-9, "悲观满格好事乘数");
        c.eq(1.5, PersonalityFormulas.hopeMultiplier(0, false), 1e-9, "悲观满格坏事乘数");
        c.eq(1.0, PersonalityFormulas.hopeMultiplier(50, true), 1e-9, "中位好事乘数");
        c.eq(1.0, PersonalityFormulas.hopeMultiplier(50, false), 1e-9, "中位坏事乘数");
        // resolve 四例验算（基准 ±3）：+4.5→+5 / −1.5→−2 / +1.5→+2 / −4.5→−5（远离零取整）
        c.eq(5, PersonalityFormulas.resolve(100, 3), "乐观满格 +3 → +4.5 → +5");
        c.eq(-2, PersonalityFormulas.resolve(100, -3), "乐观满格 −3 → −1.5 → −2");
        c.eq(2, PersonalityFormulas.resolve(0, 3), "悲观满格 +3 → +1.5 → +2");
        c.eq(-5, PersonalityFormulas.resolve(0, -3), "悲观满格 −3 → −4.5 → −5");
        // 中位不修正；0 基准恒 0
        c.eq(3, PersonalityFormulas.resolve(50, 3), "中位 +3 不修正");
        c.eq(-3, PersonalityFormulas.resolve(50, -3), "中位 −3 不修正");
        c.eq(0, PersonalityFormulas.resolve(100, 0), "0 基准恒 0");
        c.throwIfAny(helper, "formulaHopeResolve");
    }

    // ==========================================================
    //  二、纯公式：好战/暴力基准（线性过零 + 单向门）
    // ==========================================================

    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void formulaWarlikeViolenceBases(GameTestHelper helper) {
        Checks c = new Checks();
        // 暴力轴：死亡/爆炸线性过零
        c.eq(3.0, PersonalityFormulas.violenceBase(100, 3), 1e-9, "嗜血满格死亡 +3");
        c.eq(-3.0, PersonalityFormulas.violenceBase(0, 3), 1e-9, "仁慈满格死亡 −3");
        c.eq(0.0, PersonalityFormulas.violenceBase(50, 3), 1e-9, "中位死亡无感");
        c.eq(1.5, PersonalityFormulas.violenceBase(75, 3), 1e-9, "75 半额");
        c.eq(-1.5, PersonalityFormulas.violenceBase(25, 3), 1e-9, "25 半额负");
        // 繁殖镜像
        c.eq(-3.0, PersonalityFormulas.birthBase(100, 3), 1e-9, "嗜血嫌吵 −3");
        c.eq(3.0, PersonalityFormulas.birthBase(0, 3), 1e-9, "仁慈欣喜 +3");
        c.eq(0.0, PersonalityFormulas.birthBase(50, 3), 1e-9, "中位繁殖无感");
        // 好战·击杀：仅 v>50 生效（单向门）
        c.eq(3.0, PersonalityFormulas.killBase(100, 3), 1e-9, "好战满格击杀 +3");
        c.eq(1.5, PersonalityFormulas.killBase(75, 3), 1e-9, "好战 75 半额");
        c.eq(0.0, PersonalityFormulas.killBase(50, 3), 1e-9, "好战中位无感");
        c.eq(0.0, PersonalityFormulas.killBase(0, 3), 1e-9, "温和者击杀无感（不扣分）");
        // 好战·久无击杀：镜像
        c.eq(-3.0, PersonalityFormulas.noKillBase(100, 3), 1e-9, "好战满格久无杀 −3");
        c.eq(0.0, PersonalityFormulas.noKillBase(50, 3), 1e-9, "中位久无杀无感");
        c.eq(0.0, PersonalityFormulas.noKillBase(0, 3), 1e-9, "温和者久无杀无感");
        c.throwIfAny(helper, "formulaWarlikeViolenceBases");
    }

    // ==========================================================
    //  三、纯公式：勇气/社交/嫉妒基准
    // ==========================================================

    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void formulaCourageSocialEnvyBases(GameTestHelper helper) {
        Checks c = new Checks();
        // 勇气·遇敌：线性 −base·(1−v/100)
        // 遇敌（用户定稿）：仅胆小 <35 怕怪，v=0 封顶 −3，≥35 无感
        c.eq(-3.0, PersonalityFormulas.scareBase(0, 3), 1e-9, "胆小满格遇敌 −3（封顶）");
        c.eq(-3.0 * 17 / 35, PersonalityFormulas.scareBase(18, 3), 1e-9, "v=18 半额");
        c.eq(-3.0 * 1 / 35, PersonalityFormulas.scareBase(34, 3), 1e-9, "v=34 微额");
        c.eq(0.0, PersonalityFormulas.scareBase(35, 3), 1e-9, "分界线无感");
        c.eq(0.0, PersonalityFormulas.scareBase(50, 3), 1e-9, "中位遇敌无感");
        c.eq(0.0, PersonalityFormulas.scareBase(100, 3), 1e-9, "勇者遇敌无感");
        // 勇气·整日平安（同步对齐 35 线）：仅 v<35 能被吓到的人有安心结算
        c.eq(3.0, PersonalityFormulas.safeBase(0, 3), 1e-9, "胆小平安 +3（封顶）");
        c.eq(3.0 * 1 / 35, PersonalityFormulas.safeBase(34, 3), 1e-9, "v=34 微额");
        c.eq(0.0, PersonalityFormulas.safeBase(35, 3), 1e-9, "分界线平安无感");
        c.eq(0.0, PersonalityFormulas.safeBase(49, 3), 1e-9, "49 平安无感");
        c.eq(0.0, PersonalityFormulas.safeBase(100, 3), 1e-9, "勇者平安无感");
        // 社交·扎堆对冲 / 孤身镜像
        c.eq(3.0, PersonalityFormulas.crowdBase(100, 3), 1e-9, "爱扎堆扎堆 +3");
        c.eq(-3.0, PersonalityFormulas.crowdBase(0, 3), 1e-9, "喜独处扎堆 −3");
        c.eq(0.0, PersonalityFormulas.crowdBase(50, 3), 1e-9, "中位扎堆无感");
        c.eq(-3.0, PersonalityFormulas.lonelyBase(100, 3), 1e-9, "爱扎堆孤身 −3");
        c.eq(3.0, PersonalityFormulas.lonelyBase(0, 3), 1e-9, "喜独处孤身 +3");
        // 嫉妒·装备心情惩罚：仅 v>80（2026-11-08 定稿阈值）
        c.eq(-3.0, PersonalityFormulas.gearMoodBase(100, 3), 1e-9, "攀比满格 −3");
        c.eq(-1.5, PersonalityFormulas.gearMoodBase(90, 3), 1e-9, "攀比 90 半额");
        c.eq(0.0, PersonalityFormulas.gearMoodBase(80, 3), 1e-9, "v=80 恰好无感");
        c.eq(0.0, PersonalityFormulas.gearMoodBase(79, 3), 1e-9, "v=79 无感");
        c.throwIfAny(helper, "formulaCourageSocialEnvyBases");
    }

    // ==========================================================
    //  四、纯公式：行为旋钮（锁敌/逃跑/攻击/坚韧/专注/晨夜/摸鱼）
    // ==========================================================

    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void formulaBehaviorKnobs(GameTestHelper helper) {
        Checks c = new Checks();
        // 勇气：战士锁敌半径修正（+10/−5 极值）
        c.eq(10.0, PersonalityFormulas.aggroRadiusCorrection(100), 1e-9, "锁敌 +10 极值");
        c.eq(0.0, PersonalityFormulas.aggroRadiusCorrection(50), 1e-9, "锁敌中位零修正");
        c.eq(-5.0, PersonalityFormulas.aggroRadiusCorrection(0), 1e-9, "锁敌 −5 极值");
        // 逃跑距离（仅 v<35，v=0 最远 8 格；2026-11-08 分界线定稿 35）
        c.eq(8.0, PersonalityFormulas.fleeDistance(0), 1e-9, "逃跑 8 格极值");
        c.eq(8.0 * 10 / 35, PersonalityFormulas.fleeDistance(25), 1e-9, "逃跑 25 = 8×10/35");
        c.eq(8.0 / 35, PersonalityFormulas.fleeDistance(34), 1e-9, "逃跑 34 微额");
        c.eq(0.0, PersonalityFormulas.fleeDistance(35), 1e-9, "分界线 35 不逃");
        c.eq(0.0, PersonalityFormulas.fleeDistance(100), 1e-9, "勇者不逃");
        // 攻击乘数：v=0 打七折，中位以上不增伤
        c.eq(0.7, PersonalityFormulas.attackMultiplier(0), 1e-9, "攻击 −30% 极值");
        c.eq(0.85, PersonalityFormulas.attackMultiplier(25), 1e-9, "攻击 25 半额");
        c.eq(1.0, PersonalityFormulas.attackMultiplier(50), 1e-9, "中位不折");
        c.eq(1.0, PersonalityFormulas.attackMultiplier(100), 1e-9, "勇者不增伤");
        // 坚韧：打折线 40 / 罢工线 20 平行位移 ±10
        c.eq(40, PersonalityFormulas.slowThreshold(50), "打折线中位=40");
        c.eq(30, PersonalityFormulas.slowThreshold(100), "坚韧满格打折线 30");
        c.eq(50, PersonalityFormulas.slowThreshold(0), "脆弱满格打折线 50");
        c.eq(20, PersonalityFormulas.strikeThreshold(50), "罢工线中位=20");
        c.eq(10, PersonalityFormulas.strikeThreshold(100), "坚韧满格罢工线 10");
        c.eq(30, PersonalityFormulas.strikeThreshold(0), "脆弱满格罢工线 30");
        // 专注效率段 ±5%
        c.eq(1.05, PersonalityFormulas.focusMultiplier(100), 1e-9, "专注满格 ×1.05");
        c.eq(1.0, PersonalityFormulas.focusMultiplier(50), 1e-9, "专注中位 ×1.0");
        c.eq(0.95, PersonalityFormulas.focusMultiplier(0), 1e-9, "专注零格 ×0.95");
        // 晨夜偏移 ±1000t（早鸟为负=提前）
        c.eq(-1000, PersonalityFormulas.chronoShift(100), "早鸟 −1000");
        c.eq(0, PersonalityFormulas.chronoShift(50), "中位零偏移");
        c.eq(1000, PersonalityFormulas.chronoShift(0), "夜猫子 +1000");
        // 摸鱼：仅 v<40，v=0 四成
        c.eq(0.4, PersonalityFormulas.goofChance(0), 1e-9, "摸鱼 40% 极值");
        c.eq(0.2, PersonalityFormulas.goofChance(20), 1e-9, "摸鱼 20 半额");
        c.eq(0.0, PersonalityFormulas.goofChance(40), 1e-9, "v=40 不摸鱼");
        c.eq(0.0, PersonalityFormulas.goofChance(50), 1e-9, "中位不摸鱼");
        c.throwIfAny(helper, "formulaBehaviorKnobs");
    }

    // ==========================================================
    //  五、纯逻辑：抽取/钳制/档位/标签
    // ==========================================================

    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void axisRollClampAndTier(GameTestHelper helper) {
        Checks c = new Checks();
        // 抽取：范围 + 分布中心（固定种子回归）
        RandomSource rnd = RandomSource.create(42L);
        long sum = 0;
        for (int i = 0; i < 500; i++) {
            int[] axes = PersonalityAxis.rollAll(rnd);
            c.isTrue(axes.length == PersonalityAxis.COUNT, "rollAll 长度");
            for (int v : axes) {
                if (v < 0 || v > 100) {
                    c.isTrue(false, "rollAll 越界 " + v);
                    break;
                }
            }
            sum += axes[PersonalityAxis.HOPE.ordinal()];
        }
        double mean = sum / 500.0;
        c.isTrue(mean > 44 && mean < 56, "正态中心偏离过多: " + mean);
        c.eq(0, PersonalityAxis.clampAxis(-5), "钳下限");
        c.eq(100, PersonalityAxis.clampAxis(150), "钳上限");
        // 档位边界
        c.eq(0, PersonalityAxis.tierOf(0), "tier 0 下界");
        c.eq(0, PersonalityAxis.tierOf(9), "tier 0 上界");
        c.eq(1, PersonalityAxis.tierOf(10), "tier 1 下界");
        c.eq(1, PersonalityAxis.tierOf(24), "tier 1 上界");
        c.eq(2, PersonalityAxis.tierOf(25), "tier 2 下界");
        c.eq(2, PersonalityAxis.tierOf(39), "tier 2 上界");
        c.eq(3, PersonalityAxis.tierOf(40), "中性下界");
        c.eq(3, PersonalityAxis.tierOf(60), "中性上界");
        c.eq(4, PersonalityAxis.tierOf(61), "tier 4 下界");
        c.eq(4, PersonalityAxis.tierOf(75), "tier 4 上界");
        c.eq(5, PersonalityAxis.tierOf(76), "tier 5 下界");
        c.eq(5, PersonalityAxis.tierOf(90), "tier 5 上界");
        c.eq(6, PersonalityAxis.tierOf(91), "tier 6 下界");
        c.eq(6, PersonalityAxis.tierOf(100), "tier 6 上界");
        c.throwIfAny(helper, "axisRollClampAndTier");
    }

    /** 轴间耦合（用户定稿 2026-11-08）：好战端（>50）的人勇气不低于分界线（不胆小）。 */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void axisRollCoupling(GameTestHelper helper) {
        Checks c = new Checks();
        RandomSource rnd = RandomSource.create(77L);
        int warlikeHigh = 0;
        int floored = 0;
        for (int i = 0; i < 2000; i++) {
            int[] axes = PersonalityAxis.rollAll(rnd);
            int warlike = axes[PersonalityAxis.WARLIKE.ordinal()];
            int courage = axes[PersonalityAxis.COURAGE.ordinal()];
            if (warlike > PersonalityAxis.NEUTRAL) {
                warlikeHigh++;
                if (courage < (int) PersonalityFormulas.COURAGE_SPLIT_LINE) {
                    c.isTrue(false, "好战 " + warlike + " 却胆小 " + courage);
                }
                if (courage == (int) PersonalityFormulas.COURAGE_SPLIT_LINE
                        && courage > 0) {
                    floored++; // 有被抬线痕迹说明耦合真的在工作
                }
            }
        }
        c.isTrue(warlikeHigh > 300, "好战样本过少: " + warlikeHigh);
        c.isTrue(floored > 0, "2000 抽无一例勇气被抬线，耦合疑似未生效");
        c.throwIfAny(helper, "axisRollCoupling");
    }

    /** 喜好职业过滤（用户定稿）：胆小（<35）抽不到战士；非胆小可以抽到。 */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void likedProfessionCowardFilter(GameTestHelper helper) {
        Checks c = new Checks();
        RandomSource rnd = RandomSource.create(99L);
        // 胆小池：200 抽无战士
        for (int i = 0; i < 200; i++) {
            com.bmf.bmfstown.entity.domain.Prefs prefs =
                    new com.bmf.bmfstown.entity.domain.Prefs();
            prefs.ensureGenerated(rnd, true);
            c.isTrue(prefs.likedProfession() != ProfessionType.WARRIOR,
                    "胆小第 " + i + " 抽喜好战士");
        }
        // 非胆小池：能抽到战士（证明过滤只在胆小时生效）
        boolean sawWarrior = false;
        for (int i = 0; i < 200 && !sawWarrior; i++) {
            com.bmf.bmfstown.entity.domain.Prefs prefs =
                    new com.bmf.bmfstown.entity.domain.Prefs();
            prefs.ensureGenerated(rnd, false);
            sawWarrior = prefs.likedProfession() == ProfessionType.WARRIOR;
        }
        c.isTrue(sawWarrior, "非胆小 200 抽无战士，过滤疑似失效方向反了");
        // rerollLikedProfession(true)：已有战士喜好也换得掉
        com.bmf.bmfstown.entity.domain.Prefs prefs =
                new com.bmf.bmfstown.entity.domain.Prefs();
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Generated", true);
        tag.putString("LikedProfession", "warrior");
        prefs.loadFrom(tag);
        c.eq(ProfessionType.WARRIOR, prefs.likedProfession(), "NBT 灌入战士喜好");
        prefs.rerollLikedProfession(RandomSource.create(7L), true);
        c.isTrue(prefs.likedProfession() != ProfessionType.WARRIOR, "定向重摇后仍战士");
        c.throwIfAny(helper, "likedProfessionCowardFilter");
    }

    /** 喜好职业对账：存量"胆小却喜好战士"在 ensureGenerated 时被修正。 */
    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void likedProfessionReconcile(GameTestHelper helper) {
        Checks c = new Checks();
        Resident r = spawnResident(helper, new BlockPos(2, 1, 2));
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Generated", true);
        tag.putString("LikedProfession", "warrior");
        r.getPrefs().loadFrom(tag);

        // 胆小（勇气 10）：对账换掉战士
        r.getStatus().setAxis(PersonalityAxis.COURAGE, 10);
        r.getStatus().ensureGenerated(r, helper.getLevel());
        c.isTrue(r.getPrefs().generated(), "对账后偏好应已生成");
        c.isTrue(r.getPrefs().likedProfession() != ProfessionType.WARRIOR,
                "胆小喜好战士未被对账修正");

        // 非胆小（勇气 50）：战士喜好保留
        Resident r2 = spawnResident(helper, new BlockPos(2, 1, 5));
        r2.getPrefs().loadFrom(tag);
        r2.getStatus().setAxis(PersonalityAxis.COURAGE, 50);
        r2.getStatus().ensureGenerated(r2, helper.getLevel());
        c.eq(ProfessionType.WARRIOR, r2.getPrefs().likedProfession(),
                "非胆小的战士喜好被误改");
        c.throwIfAny(helper, "likedProfessionReconcile");
        helper.runAfterDelay(20, () -> r.discard());
        helper.runAfterDelay(20, () -> r2.discard());
    }

    /** 击杀经验全民入账（用户定稿）：非战士击杀也涨格斗经验。 */
    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void killGrantsCombatXpForAll(GameTestHelper helper) {
        Checks c = new Checks();
        Resident r = spawnResident(helper, new BlockPos(2, 1, 2));
        c.isTrue(!r.isWarrior(), "预备居民应为非战士");
        var zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(4, 1, 2));
        zombie.setNoAi(true);
        zombie.setHealth(1.0F);
        int before = r.getArchive().skillXp(com.bmf.bmfstown.entity.profile.SkillType.COMBAT);
        r.doHurtTarget(zombie);
        helper.runAfterDelay(10, () -> {
            c.isTrue(r.getArchive()
                            .skillXp(com.bmf.bmfstown.entity.profile.SkillType.COMBAT) > before,
                    "非战士击杀未涨格斗经验");
            c.throwIfAny(helper, "killGrantsCombatXpForAll");
            r.discard();
        });
    }

    /** 面板标签：中性轴不出现；key 形如 personality.bmfstown.axis.tag.<tier>.<axis>。 */
    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void axisVisibleTags(GameTestHelper helper) {
        Checks c = new Checks();
        int[] allNeutral = new int[PersonalityAxis.COUNT];
        java.util.Arrays.fill(allNeutral, 50);
        c.isTrue(PersonalityFormulas.visibleTags(k -> k, allNeutral).isEmpty(), "全中性无标签");
        c.isTrue(PersonalityFormulas.visibleTags(k -> k, null).isEmpty(), "null 轴无标签");
        int[] axes = new int[PersonalityAxis.COUNT];
        java.util.Arrays.fill(axes, 50);
        axes[PersonalityAxis.HOPE.ordinal()] = 100;   // tier 6
        axes[PersonalityAxis.CRUELTY.ordinal()] = 0;  // tier 0
        axes[PersonalityAxis.ENVY.ordinal()] = 60;    // 中性上界，不显示
        List<String> tags = PersonalityFormulas.visibleTags(k -> k, axes);
        c.eq(2, tags.size(), "非中性轴恰好 2 条标签");
        if (tags.size() == 2) {
            c.isTrue(tags.get(0).contains("personality.bmfstown.axis.tag.6.hope"),
                    "心态极档 key: " + tags.get(0));
            c.isTrue(tags.get(1).contains("personality.bmfstown.axis.tag.0.cruelty"),
                    "暴力极档 key: " + tags.get(1));
        }
        c.throwIfAny(helper, "axisVisibleTags");
    }

    // ==========================================================
    //  六、效率公式（坚韧位移阈值 + 专注段连乘）
    // ==========================================================

    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void focusAndGritEfficiency(GameTestHelper helper) {
        Checks c = new Checks();
        double eps = 1e-9;
        // 心情段：mood 35 在中位口径（线 40）下打折；坚韧满格（线 30）不打折
        c.eq(0.7, EfficiencyMath.moodMultiplier(35, 50), eps, "mood35 中位口径打折");
        c.eq(1.0, EfficiencyMath.moodMultiplier(35, 100), eps, "mood35 坚韧满格不打折");
        c.eq(1.0, EfficiencyMath.moodMultiplier(50, 0), eps, "mood50 脆弱满格不打折");
        c.eq(0.7, EfficiencyMath.moodMultiplier(35), eps, "旧单参签名走中位口径");
        // 完整连乘：专注段必须进 total
        double base = EfficiencyMath.baseOf(0);
        c.eq(base * 1.05, EfficiencyMath.total(0, 100, false, 50, 100), eps, "total 含专注 ×1.05");
        c.eq(base * 0.95, EfficiencyMath.total(0, 100, false, 50, 0), eps, "total 含专注 ×0.95");
        c.eq(base, EfficiencyMath.total(0, 100, false, 50, 50), eps, "total 全中性 = 基础值");
        // 组合：mood 打折 × 专注
        c.eq(base * 0.7 * 1.05, EfficiencyMath.total(0, 35, false, 50, 100), eps, "打折+专注组合");
        c.throwIfAny(helper, "focusAndGritEfficiency");
    }

    // ==========================================================
    //  七、实体级：轴 NBT 往返 + 懒生成
    // ==========================================================

    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void axisNbtRoundtrip(GameTestHelper helper) {
        Checks c = new Checks();
        Resident r = spawnResident(helper, new BlockPos(2, 1, 2));
        Status status = r.getStatus();
        // 设值 → save → 全新 Status loadFrom → 逐轴相等
        status.setAxis(PersonalityAxis.HOPE, 0);
        status.setAxis(PersonalityAxis.CRUELTY, 100);
        status.setAxis(PersonalityAxis.FOCUS, 77);
        CompoundTag tag = status.save();
        Status restored = new Status();
        restored.loadFrom(tag);
        c.eq(0, restored.axesView()[PersonalityAxis.HOPE.ordinal()], "NBT 往返 HOPE");
        c.eq(100, restored.axesView()[PersonalityAxis.CRUELTY.ordinal()], "NBT 往返 CRUELTY");
        c.eq(77, restored.axesView()[PersonalityAxis.FOCUS.ordinal()], "NBT 往返 FOCUS");
        c.throwIfAny(helper, "axisNbtRoundtrip");
    }

    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void axisLazyGeneration(GameTestHelper helper) {
        Checks c = new Checks();
        Resident r = spawnResident(helper, new BlockPos(2, 1, 2));
        // setAxis 触发兜底生成；读回一致、全轴落域
        r.getStatus().setAxis(PersonalityAxis.COURAGE, 77);
        c.eq(77, r.axisValue(PersonalityAxis.COURAGE), "setAxis 后读回");
        int[] view = r.getStatus().axesView();
        c.isTrue(view != null && view.length == PersonalityAxis.COUNT, "懒生成全轴长度");
        if (view != null) {
            for (int v : view) {
                c.isTrue(v >= 0 && v <= 100, "懒生成轴值落域 " + v);
            }
        }
        // setAxis 钳制
        r.getStatus().setAxis(PersonalityAxis.HOPE, 999);
        c.eq(100, r.axisValue(PersonalityAxis.HOPE), "setAxis 钳上限");
        r.getStatus().setAxis(PersonalityAxis.HOPE, -5);
        c.eq(0, r.axisValue(PersonalityAxis.HOPE), "setAxis 钳下限");
        c.throwIfAny(helper, "axisLazyGeneration");
    }

    // ==========================================================
    //  八、实体级：心情事件方向（真实事件入口 noteXxx）
    // ==========================================================

    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void axisMoodEventsDirection(GameTestHelper helper) {
        Checks c = new Checks();
        ServerLevel level = helper.getLevel();
        Resident r = spawnResident(helper, new BlockPos(2, 1, 2));
        Status s = r.getStatus();
        s.setAxis(PersonalityAxis.HOPE, 50); // 心态中位：去掉乘区干扰，基准即实得
        int m0 = s.mood();

        // 暴力轴·死亡线性过零
        s.setAxis(PersonalityAxis.CRUELTY, 100);
        s.noteDeathNearby(r, level);
        c.eq(m0 + 3, s.mood(), "嗜血见死亡 +3");
        s.setAxis(PersonalityAxis.CRUELTY, 0);
        s.noteDeathNearby(r, level);
        c.eq(m0, s.mood(), "仁慈见死亡 −3（回到原位）");
        s.setAxis(PersonalityAxis.CRUELTY, 50);
        s.noteDeathNearby(r, level);
        c.eq(m0, s.mood(), "中位见死亡无感");

        // 暴力轴·繁殖镜像
        s.setAxis(PersonalityAxis.CRUELTY, 0);
        s.noteBirthNearby(r, level);
        c.eq(m0 + 3, s.mood(), "仁慈见繁殖 +3");
        s.setAxis(PersonalityAxis.CRUELTY, 100);
        s.noteBirthNearby(r, level);
        c.eq(m0, s.mood(), "嗜血见繁殖 −3（回到原位）");

        // 好战·击杀单向门：v≤50 杀怪无感
        s.setAxis(PersonalityAxis.WARLIKE, 0);
        s.noteKill(r, level);
        c.eq(m0, s.mood(), "温和者击杀无感");
        s.setAxis(PersonalityAxis.WARLIKE, 100);
        s.noteKill(r, level);
        c.eq(m0 + 3, s.mood(), "好战满格击杀 +3");

        // 嫉妒·装备：仅 v>80 扣
        s.setAxis(PersonalityAxis.ENVY, 80);
        s.notePlayerGear(r, level);
        c.eq(m0 + 3, s.mood(), "v=80 看装备无感");
        s.setAxis(PersonalityAxis.ENVY, 100);
        s.notePlayerGear(r, level);
        c.eq(m0, s.mood(), "攀比满格看装备 −3（回到原位）");

        c.throwIfAny(helper, "axisMoodEventsDirection");
    }

    // ==========================================================
    //  九、实体级：勇气 Goal 规则（35 分界线）+ 群体支援 + 威胁唤醒
    // ==========================================================

    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void courageRetaliateRules(GameTestHelper helper) {
        Checks c = new Checks();
        Resident r = spawnResident(helper, new BlockPos(2, 1, 2));
        var zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(4, 1, 2));
        zombie.setNoAi(true);
        r.setLastHurtByMob(zombie); // 直接喂"被攻击"记忆，绕过真实受击副作用
        var goal = new CourageGoals.RetaliateGoal(r);

        // 非战士：v=35 恰好还手（分界线含入）/ v=34 不还手 / v=100 还手 / v=0 不还手
        r.getStatus().setAxis(PersonalityAxis.COURAGE, 35);
        c.eq(true, goal.canUse(), "非战士 v=35 还手（分界线含入）");
        r.getStatus().setAxis(PersonalityAxis.COURAGE, 34);
        c.eq(false, goal.canUse(), "非战士 v=34 不还手（转逃跑）");
        r.getStatus().setAxis(PersonalityAxis.COURAGE, 100);
        c.eq(true, goal.canUse(), "非战士 v=100 还手");
        r.getStatus().setAxis(PersonalityAxis.COURAGE, 0);
        c.eq(false, goal.canUse(), "非战士 v=0 不还手（转逃跑）");

        // 战士：保底自卫（v=0 也还手）
        r.getIdentity().setProfession(ProfessionType.WARRIOR);
        r.syncClient();
        c.eq(true, goal.canUse(), "战士保底自卫");

        // 无攻击者记忆 → 不还手
        r.setLastHurtByMob(null);
        c.eq(false, goal.canUse(), "无攻击者不还手");

        c.throwIfAny(helper, "courageRetaliateRules");
    }

    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void courageFleeRules(GameTestHelper helper) {
        Checks c = new Checks();
        Resident r = spawnResident(helper, new BlockPos(2, 1, 2));
        var goal = new CourageGoals.CourageFleeGoal(r);

        // 无威胁在场：永不触发
        r.getStatus().setAxis(PersonalityAxis.COURAGE, 0);
        c.eq(false, goal.canUse(), "无威胁不逃");

        // 威胁在 2 格内（< 8 格 / ≈2.29 格阈值依次收窄；35 线定稿后 v≥34 不逃）
        var zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(4, 1, 2));
        zombie.setNoAi(true);
        r.getStatus().setAxis(PersonalityAxis.COURAGE, 0);
        c.eq(true, goal.canUse(), "v=0 威胁 2 格内 → 逃");
        r.getStatus().setAxis(PersonalityAxis.COURAGE, 25);
        c.eq(true, goal.canUse(), "v=25 威胁 2 格内（阈值≈2.29 格）→ 逃");
        r.getStatus().setAxis(PersonalityAxis.COURAGE, 34);
        c.eq(false, goal.canUse(), "v=34 阈值 0.23 格 < 2 格 → 不逃");
        r.getStatus().setAxis(PersonalityAxis.COURAGE, 35);
        c.eq(false, goal.canUse(), "v=35 分界线不逃");
        r.getStatus().setAxis(PersonalityAxis.COURAGE, 100);
        c.eq(false, goal.canUse(), "勇者不逃");

        // 战士不逃（胆小战士吃攻击力折损）
        r.getStatus().setAxis(PersonalityAxis.COURAGE, 0);
        r.getIdentity().setProfession(ProfessionType.WARRIOR);
        r.syncClient();
        c.eq(false, goal.canUse(), "战士不逃");

        c.throwIfAny(helper, "courageFleeRules");
    }

    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void courageAssistRules(GameTestHelper helper) {
        Checks c = new Checks();
        Resident r = spawnResident(helper, new BlockPos(2, 1, 2));
        var zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(4, 1, 2));
        zombie.setNoAi(true);
        var goal = new CourageGoals.AssistGoal(r);

        // 无呼救 → 不响应
        c.eq(false, goal.canUse(), "无呼救不响应");

        // 呼救后按勇气轴判定：v=34 胆小不参战 / v=35 恰好参战（与还手同线）
        r.raiseAlarm(zombie);
        r.getStatus().setAxis(PersonalityAxis.COURAGE, 34);
        c.eq(false, goal.canUse(), "v=34 胆小不助战");
        r.getStatus().setAxis(PersonalityAxis.COURAGE, 35);
        c.eq(true, goal.canUse(), "v=35 不胆小助战");

        // 入场锁敌：start 一次定格，目标不换；stop 清场
        goal.start();
        c.eq(zombie, r.getTarget(), "start 后锁敌袭击者");
        c.eq(true, goal.canContinueToUse(), "目标存活继续追打");
        c.eq(false, goal.canUse(), "已在打架不换目标（锁敌稳定）");
        goal.stop();
        c.isTrue(r.getTarget() == null, "stop 清目标");
        c.eq(false, goal.canUse(), "呼救已消费不响应");

        // 战士无条件助战（v=0 也上）
        r.getStatus().setAxis(PersonalityAxis.COURAGE, 0);
        r.getIdentity().setProfession(ProfessionType.WARRIOR);
        r.syncClient();
        r.raiseAlarm(zombie);
        c.eq(true, goal.canUse(), "战士无条件助战");

        // 呼救过期 → 不响应
        r.clearAlarm();
        c.eq(false, goal.canUse(), "呼救过期不响应");

        c.throwIfAny(helper, "courageAssistRules");
    }

    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void threatWakeClearsSleepPose(GameTestHelper helper) {
        Checks c = new Checks();
        Resident r = spawnResident(helper, new BlockPos(2, 1, 2));
        // 原版 startSleeping 强制入睡（置 Pose.SLEEPING 与 sleepingPos）——
        // 回归"躺着逃跑"：怪物逼近触发逃跑/助战前必须先真正起床
        r.startSleeping(helper.absolutePos(new BlockPos(2, 1, 2)));
        c.isTrue(r.getPose() == net.minecraft.world.entity.Pose.SLEEPING, "入睡成功");
        r.wakeUpForThreat();
        c.isTrue(!r.isSleeping(), "威胁唤醒后不再睡");
        c.isTrue(r.getPose() != net.minecraft.world.entity.Pose.SLEEPING, "睡姿已清");
        c.throwIfAny(helper, "threatWakeClearsSleepPose");
    }

    // ==========================================================
    //  十、实体级：怪物仇恨注入 + 排除名单
    // ==========================================================

    /** 检查怪的目标选择器里是否挂上了 ResidentTargetGoal。 */
    private static boolean hasResidentTargetGoal(net.minecraft.world.entity.Mob mob) {
        return mob.targetSelector.getAvailableGoals().stream()
                .anyMatch(g -> g.getGoal().getClass().getName().endsWith("ResidentTargetGoal"));
    }

    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void aggroInjectionAndExclusions(GameTestHelper helper) {
        Checks c = new Checks();
        // 排除名单：苦力怕/末影人/僵尸猪灵对居民无仇恨；普通怪有
        c.eq(true, ResidentAggroHandler.isExcludedType(EntityType.CREEPER), "苦力怕排除");
        c.eq(true, ResidentAggroHandler.isExcludedType(EntityType.ENDERMAN), "末影人排除");
        c.eq(true, ResidentAggroHandler.isExcludedType(EntityType.ZOMBIFIED_PIGLIN), "僵尸猪灵排除");
        c.eq(false, ResidentAggroHandler.isExcludedType(EntityType.ZOMBIE), "僵尸不排除");
        c.eq(false, ResidentAggroHandler.isExcludedType(EntityType.SPIDER), "蜘蛛不排除");
        c.eq(false, ResidentAggroHandler.isExcludedType(EntityType.PLAYER), "玩家不是怪");

        // 实体入图即注入：僵尸有、苦力怕没有（EntityJoinLevelEvent 同步触发）
        var zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        zombie.setNoAi(true);
        c.isTrue(hasResidentTargetGoal(zombie), "僵尸入图注入 ResidentTargetGoal");
        var creeper = helper.spawn(EntityType.CREEPER, new BlockPos(4, 1, 2));
        creeper.setNoAi(true);
        c.isTrue(!hasResidentTargetGoal(creeper), "苦力怕不注入");

        c.throwIfAny(helper, "aggroInjectionAndExclusions");
    }

    // ==========================================================
    //  十一、实体级：晨夜轴作息窗口偏移
    // ==========================================================

    @GameTest(template = "fireworkstests.room13x6x11", timeoutTicks = 200)
    public static void chronoWindowShift(GameTestHelper helper) {
        Checks c = new Checks();
        Resident r = spawnResident(helper, new BlockPos(2, 1, 2));
        int base = Config.SLEEP_START_TIME.get();
        c.isTrue(Config.SLEEP_END_TIME.get() > base, "睡眠窗口合法（前提校验）");

        r.getStatus().setAxis(PersonalityAxis.CHRONO, 100);
        int[] early = r.sleepWindow();
        c.eq(base - 1000, early[0], "早鸟睡眠起点 −1000");
        c.eq(base - 1000, early[1] - (Config.SLEEP_END_TIME.get() - base),
                "早鸟睡眠终点同步前移");
        int[] earlyWork = r.workWindow();
        c.eq(Config.CRAFT_WORK_START.get() - 1000, earlyWork[0], "早鸟开工提前");

        r.getStatus().setAxis(PersonalityAxis.CHRONO, 0);
        int[] late = r.sleepWindow();
        c.eq(base + 1000, late[0], "夜猫子睡眠起点 +1000");
        c.eq(Config.CRAFT_WORK_START.get() + 1000, r.workWindow()[0], "夜猫子开工推迟");

        r.getStatus().setAxis(PersonalityAxis.CHRONO, 50);
        c.eq(base, r.sleepWindow()[0], "中位零偏移");

        c.throwIfAny(helper, "chronoWindowShift");
    }
}
