package com.bmf.bmfstown.entity.profile;

import java.util.Locale;

/**
 * 天气偏好（v0.8）：每人 1/3 概率讨厌一种天气。
 *
 * <p>权重（用户定稿）：晴天 1、雨天 15、雷暴 5（雨天最烦人，
 * 讨厌晴天极少见——晴天是常态，讨厌它等于天天不开心，概率必须压低）。
 * 权重和为 21，按权重比例抽取即可。
 */
public enum WeatherPref {

    CLEAR("晴天", 1),
    RAIN("雨天", 15),
    THUNDER("雷暴", 5);

    private final String zhName;
    private final int weight;

    WeatherPref(String zhName, int weight) {
        this.zhName = zhName;
        this.weight = weight;
    }

    public String zhName() {
        return this.zhName;
    }

    public String displayName() {
        return "weather.bmfstown." + name().toLowerCase(Locale.ROOT);
    }

    /** 按权重抽一个；返回 null 表示不讨厌任何天气（2/3 概率）。 */
    public static WeatherPref randomDisliked(net.minecraft.util.RandomSource random) {
        int total = 0;
        for (WeatherPref w : values()) {
            total += w.weight;
        }
        int roll = random.nextInt(total);
        for (WeatherPref w : values()) {
            roll -= w.weight;
            if (roll < 0) {
                return w;
            }
        }
        return RAIN;
    }

    /** 当前世界的天气（服务端 level 判）。 */
    public static WeatherPref current(net.minecraft.server.level.ServerLevel level) {
        if (level.isThundering()) {
            return THUNDER;
        }
        if (level.isRaining()) {
            return RAIN;
        }
        return CLEAR;
    }
}
