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

    /** 等概率随机抽一个（配置未加载时的兜底）。 */
    public static TraitType random(RandomSource random) {
        TraitType[] all = values();
        return all[random.nextInt(all.length)];
    }

    /**
     * 加权随机抽一个（v0.9.1：权重数据结构，为词条扩容打底）。
     *
     * <p>权重来自 {@code Config.TRAITS_WEIGHT_OVERRIDES} 覆盖表（"特性id:权重"），
     * 未列出的默认 1——默认空表 = 14 条等概率，与旧版行为一致。
     * 权重 0 = 该特性禁用；全部为 0 时兜底回等概率。
     * <b>永远返回非 null</b>——不设"空特性"（用户定稿：全员必抽，只做概率结构）。
     */
    public static TraitType weightedRandom(RandomSource random) {
        java.util.Map<String, Long> weights = new java.util.HashMap<>();
        for (TraitType t : values()) {
            weights.put(t.id, 1L);
        }
        boolean loaded = com.bmf.bmfstown.config.Config.SPEC.isLoaded();
        if (loaded) {
            for (String entry : com.bmf.bmfstown.config.Config.TRAITS_WEIGHT_OVERRIDES.get()) {
                int i = entry.indexOf(':');
                if (i <= 0) {
                    continue;
                }
                try {
                    // 单条上限 10 万：防手滑巨数把总分顶爆 int
                    long w = Math.min(100_000L, Long.parseLong(entry.substring(i + 1).trim()));
                    if (w >= 0) {
                        weights.put(entry.substring(0, i).trim(), w);
                    }
                } catch (NumberFormatException ignored) {
                    // 坏条目跳过，不影响其他权重
                }
            }
        }
        int total = 0;
        for (TraitType t : values()) {
            total += weights.get(t.id);
        }
        if (total <= 0) {
            return random(random); // 全 0 兜底：等概率
        }
        int point = random.nextInt(total);
        for (TraitType t : values()) {
            point -= weights.get(t.id);
            if (point < 0) {
                return t;
            }
        }
        return random(random); // 理论不可达，防御兜底
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
