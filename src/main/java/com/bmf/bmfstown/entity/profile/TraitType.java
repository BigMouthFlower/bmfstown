package com.bmf.bmfstown.entity.profile;

import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;

/**
 * 镇民特性（v0.8.1 用户新增词条体系）：与喜好、性格并列的第三类人格词条，
 * 每个 NPC 等概率随机获得一个（用户定稿：目前 14 条概率相等）。
 *
 * <h2>实现状态（v0.8.1 第一批）</h2>
 * <ul>
 *   <li>✅ 行为已实装：轻浮 / 鼓手主唱 / 海象 / 大智若愚；</li>
 *   <li>⏳ 词条在抽取池、行为待实装：漫游癖、GATTACA、末影步、自毁倾向、
 *       蛋蛋人、Dylar实验对象、舍己为人；</li>
 *   <li>⏸ 占位（依赖未建系统）：狗骑兵（骑乘系统）、第二作者（神器/出版物）。
 *       用户原话："神器是一个坑，以后我跟你说"、"超情绪也是一个坑"。</li>
 * </ul>
 */
public enum TraitType {

    WANDERLUST("wanderlust", "漫游癖"),
    FLIGHTY("flighty", "轻浮"),
    DRUMMER("drummer", "鼓手主唱"),
    WALRUS("walrus", "海象"),
    GATTACA("gattaca", "GATTACA"),
    ENDERSTEP("enderstep", "末影步"),
    SELF_DESTRUCT("self_destruct", "自毁倾向"),
    EGGMAN("eggman", "蛋蛋人"),
    DOG_RIDER("dog_rider", "狗骑兵"),
    DYLAR("dylar", "Dylar实验对象"),
    GHOSTWRITER("ghostwriter", "第二作者"),
    SELFLESS("selfless", "舍己为人"),
    DUPLICITY("duplicity", "口是心非"),
    FOOL_SAGE("fool_sage", "大智若愚");

    private final String id;
    private final String displayName;

    TraitType(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** NBT / 网络传输用稳定 id（发布后不改名）。 */
    public String id() {
        return this.id;
    }

    /** 语言文件 key（UI 显示走翻译）。 */
    public String displayName() {
        return "trait.bmfstown." + this.id;
    }

    /** 等概率随机抽一个。 */
    public static TraitType random(RandomSource random) {
        TraitType[] all = values();
        return all[random.nextInt(all.length)];
    }

    /** 读回：无效 id（改档/删条目）返回 null，由调用方重抽。 */
    @Nullable
    public static TraitType byId(String id) {
        for (TraitType t : values()) {
            if (t.id.equals(id)) {
                return t;
            }
        }
        return null;
    }
}
