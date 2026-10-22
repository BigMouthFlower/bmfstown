package com.bmf.bmfstown.town;

/**
 * 镇界魔杖的绘制模式。
 *
 * <p>一根魔杖负责所有区域类型，模式决定"这次画的是哪类区域"。
 * 将来加新区块类型（比如"农田区"）只需要在这里加一个枚举值，
 * 选点、预览、确认的逻辑全部复用。
 *
 * <p>顺序即滚轮切换顺序（Alt+滚轮上下循环）。
 */
public enum TownWandMode {

    /** 城镇本体：外层大圈，其它区域必须落在它的范围内。 */
    TOWN("城镇"),
    /** 就餐区：镇民集体吃饭的地方。 */
    DINING("就餐区"),
    /** 住宅：镇民睡觉/归属的家。 */
    HOUSING("住宅"),
    /** 工作区：岗位干活的地方。 */
    WORK("工作区");

    /** 中文名。写死在枚举里（界面提示用的都是字面中文，与项目其它 UI 一致）。 */
    public final String zhName;

    TownWandMode(String zhName) {
        this.zhName = zhName;
    }

    /** 下一个模式（滚轮向上），循环。 */
    public TownWandMode next() {
        TownWandMode[] all = values();
        return all[(this.ordinal() + 1) % all.length];
    }

    /** 上一个模式（滚轮向下），循环。 */
    public TownWandMode prev() {
        TownWandMode[] all = values();
        return all[Math.floorMod(this.ordinal() - 1, all.length)];
    }

    /** 语言文件 key，玩家可见文本一律走它（zhName 仅留给日志）。 */
    public String displayName() {
        return "zone.bmfstown.mode." + this.name().toLowerCase();
    }

    /** 由序号取模式；越界取模兜底，不抛异常。 */
    public static TownWandMode byIndex(int index) {
        TownWandMode[] all = values();
        return all[Math.floorMod(index, all.length)];
    }
}
