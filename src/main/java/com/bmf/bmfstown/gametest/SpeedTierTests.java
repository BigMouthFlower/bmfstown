package com.bmf.bmfstown.gametest;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.config.Config;
import com.bmf.bmfstown.config.Speeds;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 移速三档契约测试（决策 #138，2026-11-27 用户拍板）。
 *
 * <h2>为什么需要这个用例</h2>
 * 改前全库十处寻路各写各的 0.6 / 0.9 / 1.0 / 1.15 / 1.3，其中 0.9 与 1.0
 * <b>是裸字面量</b>。用户实测的抱怨是"还杯突然快走，很突兀"——不是某个数错，
 * 是<b>没有刻度</b>。这种问题修完会复发：下一个写代码的人不知道有档位，
 * 顺手又敲一个 0.9D 出来。所以必须有用例钉住。
 *
 * <h2>守什么</h2>
 * <ol>
 *   <li>三档的默认值关系：正常 &lt; 急促 &lt; 奔跑，且各自等于它住的配置键
 *       （防"改了 Config 默认值但 Speeds 映射到别的键"——同一个约束写两遍，坑54）；</li>
 *   <li><b>源码里不许再出现拼在 moveTo / approach 后面的裸速度字面量</b>
 *       ——这是真正防复发的那条，直接扫源码。</li>
 * </ol>
 *
 * <p>第 2 条扫的是 {@code src/main/java} 而非 classpath：字面量在编译后
 * 只是字节码里的常量池数字，反射看不出来，只能读源文件。
 */
@GameTestHolder(BmfTown.MOD_ID)
@PrefixGameTestTemplate(false)
public class SpeedTierTests {

    /** 源码根（GameTest 的工作目录是 run/，回到工程根再进 src）。 */
    private static final Path SOURCE_ROOT = Path.of("..", "src", "main", "java");

    /**
     * 裸速度字面量：在 moveTo / approach 调用后面直接跟一个十进制数。
     *
     * <p>允许的写法只有 {@code Speeds.normal()/hurry()/follow()} 或
     * {@code Config.XXX.get()}——两者都不含裸数字，所以扫不到。
     */
    private static final Pattern BARE_SPEED = Pattern.compile(
            "\\b(?:moveTo|approach)\\s*\\(([^;]{0,200}?)\\)\\s*;",
            Pattern.DOTALL);

    /** 在捕获组里找一个"看起来是速度"的裸数字（位于最后一个实参位置）。 */
    private static final Pattern TRAILING_NUMBER = Pattern.compile(
            "[,，]\\s*([0-9]+(?:\\.[0-9]+)?)[dD]?\\s*$");

    // ==========================================================
    //  一、三档定义本身
    // ==========================================================

    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 100)
    public static void threeTiersAreOrdered(GameTestHelper helper) {
        List<String> fails = new ArrayList<>();

        double normal = Speeds.normal();
        double hurry = Speeds.hurry();
        double follow = Speeds.follow();

        // 档位语义必须是严格递增的——否则"急促"和"正常"没区别，
        // 玩家还是看不出"他这会儿有事"
        if (!(normal < hurry)) {
            fails.add("正常档(" + normal + ") 必须小于急促档(" + hurry + ")");
        }
        if (!(hurry < follow)) {
            fails.add("急促档(" + hurry + ") 必须小于奔跑档(" + follow + ")");
        }

        // 三档默认值就是用户 2026-11-27 拍板的那三个数
        if (Math.abs(normal - 0.6D) > 1e-9) {
            fails.add("正常档默认应为 0.6，实际 " + normal);
        }
        if (Math.abs(hurry - 1.15D) > 1e-9) {
            fails.add("急促档默认应为 1.15，实际 " + hurry);
        }
        if (Math.abs(follow - 1.3D) > 1e-9) {
            fails.add("奔跑档默认应为 1.3，实际 " + follow);
        }

        // Speeds 必须真的映射到那三个键（不是抄了一份数字）。
        // 三条分开断言：映射接错了要能指出是哪一条。
        if (normal != Config.STROLL_SPEED.get()) {
            fails.add("Speeds.normal() 未映射到 Config.STROLL_SPEED（映射接错或写了新常量）");
        }
        if (hurry != Config.RETURN_HOME_SPEED.get()) {
            fails.add("Speeds.hurry() 未映射到 Config.RETURN_HOME_SPEED");
        }
        if (follow != Config.WARRIOR_FOLLOW_SPEED.get()) {
            fails.add("Speeds.follow() 未映射到 Config.WARRIOR_FOLLOW_SPEED");
        }

        // 站岗已并入正常档，warrior.guardSpeed 键已删除。若有人为兼容重新加回这个键，
        // 会出现"改了正常档、站岗不跟"的双份约束（坑54），这里钉住"它不该存在"。
        if (guardSpeedKeyExists()) {
            fails.add("warrior.guardSpeed 键被重新加回来了：站岗已归入正常档，"
                    + "两份速度会打架（一个约束只许放一处）");
        }

        if (fails.isEmpty()) {
            helper.succeed();
        } else {
            helper.fail("[移速三档] " + String.join("; ", fails));
        }
    }

    // ==========================================================
    //  二、源码不许再出现裸速度字面量（防复发）
    // ==========================================================

    @GameTest(template = "smoketests.empty3x3x3", timeoutTicks = 200)
    public static void noBareSpeedLiterals(GameTestHelper helper) {
        if (!Files.isDirectory(SOURCE_ROOT)) {
            helper.fail("[移速三档] 源码目录不可达：" + SOURCE_ROOT.toAbsolutePath()
                    + "（工作目录应为 run/，若是别的目录请同步本用例的 SOURCE_ROOT）");
            return;
        }

        List<String> offenders = new ArrayList<>();
        try (var stream = Files.walk(SOURCE_ROOT)) {
            List<Path> files = stream
                    .filter(p -> p.toString().endsWith(".java"))
                    // 用例自己会写出速度字面量做对照，跳过
                    .filter(p -> !p.toString().contains("gametest"))
                    .toList();
            for (Path file : files) {
                scan(file, offenders);
            }
        } catch (IOException e) {
            helper.fail("[移速三档] 扫源码失败：" + e);
            return;
        }

        if (offenders.isEmpty()) {
            helper.succeed();
        } else {
            helper.fail("[移速三档] 这些寻路还在用裸速度，必须改走 Speeds："
                    + String.join(" | ", offenders));
        }
    }

    /** 站岗是否被重新加回独立键（反射查，避免编译期硬依赖一个"已删的东西"）。 */
    private static boolean guardSpeedKeyExists() {
        try {
            Config.class.getField("WARRIOR_GUARD_SPEED");
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    /** 扫一个文件里所有 moveTo/approach 调用，挑出末尾是裸数字的。 */
    private static void scan(Path file, List<String> offenders) throws IOException {
        String src = Files.readString(file, StandardCharsets.UTF_8);
        // 去掉行注释与块注释：注释里写"改前是 0.9"是合法的，不该判红
        String code = stripComments(src);
        Matcher m = BARE_SPEED.matcher(code);
        while (m.find()) {
            String args = m.group(1);
            if (TRAILING_NUMBER.matcher(args.trim()).find()) {
                offenders.add(shortName(file) + " → " + oneLine(args));
            }
        }
    }

    /** 去掉注释（只为判定用，不改原文）。 */
    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("//[^\n]*", " ");
    }

    /** 截断超长实参，报错信息要能一眼看懂。 */
    private static String oneLine(String args) {
        String s = args.replaceAll("\\s+", " ").trim();
        return s.length() <= 70 ? s : s.substring(0, 67) + "...";
    }

    private static String shortName(Path file) {
        Path parent = file.getParent();
        return parent == null ? file.toString()
                : parent.getFileName() + "/" + file.getFileName();
    }
}