package com.bmf.bmfstown.config;

/**
 * 镇民移速三档（2026-11-27 用户拍板统一）。
 *
 * <h2>为什么要有这个类</h2>
 * 改前全库十处寻路各写各的：0.6 / 0.9 / 1.0 / 1.15 / 1.3 混用，其中
 * <b>0.9 与 1.0 是裸字面量</b>（写死在酒馆、图书馆、送货、学者跟随里），
 * 玩家想调也调不到。用户实测的直接观感是"还杯突然快走，很突兀"——
 * 不是某个数错了，是<b>没有刻度</b>。
 *
 * <p>现在只认三档，所有寻路都从这里取，不许再写数字：
 * <ul>
 *   <li>{@link #normal()} = <b>正常档</b>（0.6，村民步速的六成）。日常一切走动：
 *       漫步、回家睡觉、进酒馆落座、还杯、取书、送还货物、进镇、站岗巡逻。
 *       用户原话：<i>"其他的我觉得都应该是 0.6"</i>、<i>"就是正常档"</i>。</li>
 *   <li>{@link #hurry()} = <b>急促档</b>（1.15）。逃跑、逃逸后回领地、追击。
 *       用来表达"他这会儿有事"，不是常态。</li>
 *   <li>{@link #follow()} = <b>奔跑档</b>（1.3 ≈ 玩家疾跑）。<b>只给跟随</b>
 *       ——跟随对象在动，慢一档就会一直追不上；战士跟随的战斗机动性在此。</li>
 * </ul>
 *
 * <p><b>为什么是访问器而不是常量</b>：三档的数值分别住在
 * {@link Config#STROLL_SPEED} / {@link Config#RETURN_HOME_SPEED} /
 * {@link Config#WARRIOR_FOLLOW_SPEED} 三个已有键里，玩家可调、改完即时生效。
 * 这里只做"档位 → 键"的映射，<b>不新增数值来源</b>（同一个约束不许写两遍，坑54）。
 */
public final class Speeds {

    private Speeds() {
    }

    /** 正常档：日常一切走动（0.6，默认）。 */
    public static double normal() {
        return Config.STROLL_SPEED.get();
    }

    /** 急促档：逃跑 / 回领地 / 追击（1.15，默认）。 */
    public static double hurry() {
        return Config.RETURN_HOME_SPEED.get();
    }

    /**
     * 奔跑档：跟随（1.3，默认）。
     *
     * <p>数值住在 {@code warrior.followSpeed}（历史键名，最早只有战士跟随用它）；
     * 学者跟随等其他"追着动的东西跑"的行为一律读这里，<b>不再各开一个键</b>。
     */
    public static double follow() {
        return Config.WARRIOR_FOLLOW_SPEED.get();
    }
}