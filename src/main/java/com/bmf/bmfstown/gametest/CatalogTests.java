package com.bmf.bmfstown.gametest;

import com.bmf.bmfstown.BmfTown;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * 目录解析全键测试：炼金目录的每一个"药水 × 修饰"组合都必须能 resolve。
 * 覆盖 2026-11-06 的 bug（resolve 拿最终变体和基础药水比对，加红石订单被拒）——
 * 防复发断言。
 */
@GameTestHolder(BmfTown.MOD_ID)
public class CatalogTests {

    /** 常用修饰组合（药效档 × 容器档的全部合法搭配）。 */
    private static final String[] MODS = {
            "none", "long", "strong",
            "splash", "lingering",
            "long+splash", "long+lingering", "strong+splash", "strong+lingering"
    };

    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void catalogResolveVanillaSwiftness(GameTestHelper helper) {
        var level = helper.getLevel();
        int ok = 0;
        var fails = new StringBuilder();
        for (String mod : MODS) {
            String key = "alch|minecraft:swiftness|" + mod;
            var recipe = com.bmf.bmfstown.alchemy.AlchemyCatalog.resolve(level, key);
            if (recipe != null) {
                ok++;
            } else {
                fails.append(key).append("; ");
            }
        }
        if (fails.isEmpty()) {
            helper.succeed();
        } else {
            helper.fail("resolve 失败的键: " + fails);
        }
    }

    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void catalogRejectsWildKeys(GameTestHelper helper) {
        var level = helper.getLevel();
        // 野键/畸形键必须解析失败（服务端拒单的依据）
        String[] wild = {"", "alch|", "alch|minecraft:swiftness",
                "alch|minecraft:not_a_potion|none",
                "alch|minecraft:swiftness|bogus",
                "alch|minecraft:swiftness|none|extra"};
        for (String key : wild) {
            if (com.bmf.bmfstown.alchemy.AlchemyCatalog.resolve(level, key) != null) {
                helper.fail("野键不应解析成功: " + key);
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3", timeoutTicks = 200)
    public static void catalogSecretPotionsResolve(GameTestHelper helper) {
        var level = helper.getLevel();
        // 秘传药水（mix 恒注册）也必须在目录里——普通档
        for (String id : new String[]{"bmfstown_haste", "bmfstown_blindness", "bmfstown_luck",
                "bmfstown_levitation"}) {
            var recipe = com.bmf.bmfstown.alchemy.AlchemyCatalog.resolve(level, "alch|minecraft:" + id + "|none");
            // 注册名带 bmfstown 命名空间
            recipe = recipe == null
                    ? com.bmf.bmfstown.alchemy.AlchemyCatalog.resolve(level, "alch|bmfstown:" + id + "|none")
                    : recipe;
            if (recipe == null) {
                helper.fail("秘传药水不在目录: " + id);
                return;
            }
        }
        helper.succeed();
    }
}
