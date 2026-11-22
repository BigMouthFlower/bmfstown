package com.bmf.bmfstown.config;

/**
 * 设计参数：集中存放"不应由玩家调整"的常数。
 *
 * <p>与 {@link Config} 的分工：
 * <ul>
 *   <li>{@link Config}：玩家可调项，生成 {@code .toml} 文件，改完即时生效；</li>
 *   <li>{@code Balance}：开发者可调项，编译期常量，进 git 可追溯，玩家改不到。</li>
 * </ul>
 *
 * <p>放这里的判断标准：<b>改了会破坏体验或性能，而不是改变玩法手感</b>。
 * 手感类数值（心情基准、间隔、上限）仍然留在 Config。
 */
public final class Balance {

    private Balance() {
    }

    /**
     * 分帧扫描：全服每 tick 最多处理的方块数（格）。
     *
     * <p>4096 ≈ 两个区块柱的表皮量级，单 tick 开销毫秒级。
     * 由 {@code ScanScheduler} 统一使用，建材扫描与物资普查共用同一预算。
     */
    public static final int SCAN_BLOCKS_PER_TICK = 4096;
}