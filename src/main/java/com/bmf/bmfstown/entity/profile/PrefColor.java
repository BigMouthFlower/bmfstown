package com.bmf.bmfstown.entity.profile;

import java.util.Locale;

/**
 * 颜色偏好表（v0.8）：16 染料色。
 *
 * <p>判定同样是前缀思路，但每个颜色展开成 8 个装饰方块前缀
 * （羊毛/混凝土/陶瓦/带釉陶瓦/染色玻璃/床/蜡烛/潜影盒）。
 * 不能只用 "{@code red_}" 这种粗前缀——会把红石矿、红石灯
 * 误判成"红色装饰"，玩家体感会很怪。
 */
public enum PrefColor {

    WHITE("白色"),
    LIGHT_GRAY("浅灰色"),
    GRAY("灰色"),
    BLACK("黑色"),
    BROWN("棕色"),
    RED("红色"),
    ORANGE("橙色"),
    YELLOW("黄色"),
    LIME("黄绿色"),
    GREEN("绿色"),
    CYAN("青色"),
    LIGHT_BLUE("淡蓝色"),
    BLUE("蓝色"),
    PURPLE("紫色"),
    MAGENTA("品红色"),
    PINK("粉色");

    private final String zhName;
    private final String[] prefixes;

    PrefColor(String zhName) {
        this.zhName = zhName;
        String c = name().toLowerCase(Locale.ROOT);
        this.prefixes = new String[]{
                c + "_wool", c + "_concrete", c + "_terracotta", c + "_glazed_terracotta",
                c + "_stained_glass", c + "_bed", c + "_candle", c + "_shulker_box"};
    }

    public String zhName() {
        return this.zhName;
    }

    public String displayName() {
        return "color.bmfstown." + name().toLowerCase(Locale.ROOT);
    }

    public boolean matches(String registryName) {
        String n = registryName.toLowerCase(Locale.ROOT);
        for (String p : this.prefixes) {
            if (n.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    public static PrefColor random(net.minecraft.util.RandomSource random) {
        return values()[random.nextInt(values().length)];
    }
}
