package com.bmf.bmfstown.entity;

import net.minecraft.util.RandomSource;

import java.util.List;

/**
 * 镇民的名字池。
 *
 * <p>为什么先写死一个静态列表，而不是做成 json 数据包？
 * 因为「名字」这件事在 v0 阶段只需要验证一件事：<b>玩家能不能记住它</b>。
 * 等真正确认名字有用之后，再考虑数据包化（那属于路线图阶段 7 的事）。
 *
 * <p>取名思路：不用「村民A」「村民B」这种编号，而用中国人一听就有画面感的
 * 小名/称呼。名字本身携带性格暗示（「阿竹」像安静的人，「老陈」像老练的人），
 * 这是零成本的性格塑造。
 */
public final class ResidentNames {

    private static final List<String> NAMES = List.of(
            "阿竹", "老陈", "小白", "阿杏", "阿禾",
            "老周", "阿柚", "小满", "阿土", "阿灯",
            "老陶", "阿栗", "小禾", "阿青", "老张",
            "阿叶", "小舟", "阿枣", "老黄", "阿梅"
    );

    private ResidentNames() {
    }

    /** 随机取一个名字。 */
    public static String random(RandomSource random) {
        return NAMES.get(random.nextInt(NAMES.size()));
    }

    /** 名字总数，供调试用。 */
    public static int size() {
        return NAMES.size();
    }
}
