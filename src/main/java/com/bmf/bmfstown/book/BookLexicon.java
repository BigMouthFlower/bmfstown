package com.bmf.bmfstown.book;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

/**
 * 闲书词库（v0.11.1，用户定稿口径）：
 *
 * <ul>
 *   <li><b>名词池</b> = 物品 + 方块 + 实体，注册表动态全表读取——mod 加的
 *       物品/方块/生物自动入池，量越多抽面越广；</li>
 *   <li><b>修饰语池</b> = 全部状态效果（buff）名，注册表动态读取——同上，
 *       "速度""发光""中毒"在偏正句式里读作修饰词；</li>
 *   <li><b>谓语池</b> = 自建 MC 风动词词表（lang 键），挖掘/放置/点燃/驯服…
 *       动宾与主谓宾共用一个池子；</li>
 *   <li><b>句式</b> = 6 个模板等概率：偏正×2（之/的）、"〜录"、并列（论X与Y）、
 *       动宾、主谓宾（主语只从实体池抽——物品/方块当不了行为主体）。</li>
 * </ul>
 *
 * <p>双语自动兼容：所有词都是 translatable 组件，显示时才取译文；
 * 英文侧动词存原形（模板里加 s 构成三单）。
 *
 * <p>确定性：同一 seed 永远生成同一本书（书名与内容按 seed 重放），
 * 所以闲书 NBT 只存一个 long——内容永不占存档。
 */
public final class BookLexicon {

    // 谓语词表：lang 键固定编号（中英双语都在 lang 文件里维护）。
    // 动宾 / 主谓宾 / 闲书内容页三处共用这一个池子。
    private static final String[] VERB_KEYS = {
            "lexicon.bmfstown.verb.1", "lexicon.bmfstown.verb.2", "lexicon.bmfstown.verb.3",
            "lexicon.bmfstown.verb.4", "lexicon.bmfstown.verb.5", "lexicon.bmfstown.verb.6",
            "lexicon.bmfstown.verb.7", "lexicon.bmfstown.verb.8", "lexicon.bmfstown.verb.9",
            "lexicon.bmfstown.verb.10", "lexicon.bmfstown.verb.11", "lexicon.bmfstown.verb.12"
    };

    private BookLexicon() {
    }

    /** 按种子生成书名（translatable 组合树，双语显示自动本地化）。 */
    public static MutableComponent title(long seed) {
        RandomSource random = RandomSource.create(seed);
        // 6 句式等概率；槽位词性按句式各取各的池（见 lang 模板注释）
        return switch (random.nextInt(6)) {
            case 0 -> Component.translatable("book.bmfstown.title.a",
                    adjective(random), noun(random));                       // 修饰语之中心语
            case 1 -> Component.translatable("book.bmfstown.title.b",
                    adjective(random), noun(random));                       // 修饰语的中心语
            case 2 -> Component.translatable("book.bmfstown.title.c",
                    noun(random));                                          // 〜录
            case 3 -> Component.translatable("book.bmfstown.title.d",
                    noun(random), noun(random));                            // 论X与Y（并列）
            case 4 -> Component.translatable("book.bmfstown.title.e",
                    verb(random), noun(random));                            // 动宾
            default -> Component.translatable("book.bmfstown.title.f",
                    entityNoun(random), verb(random), noun(random));        // 主谓宾
        };
    }

    /** 按种子生成一段"内容"（图一乐文本，同样确定性重放）。 */
    public static List<Component> pages(long seed) {
        RandomSource random = RandomSource.create(seed ^ 0x5DEECE66DL);
        List<Component> pages = new ArrayList<>(2);
        MutableComponent verb = Component.translatable(VERB_KEYS[random.nextInt(VERB_KEYS.length)]);
        pages.add(Component.translatable("book.bmfstown.idle.page1", adjective(random), noun(random), verb));
        pages.add(Component.translatable("book.bmfstown.idle.page2", noun(random), adjective(random)));
        return pages;
    }

    // ------------------------------------------------------------
    //  词池（注册表动态读取，mod 内容自动入池；列表懒加载缓存一次）
    // ------------------------------------------------------------

    private static List<Item> items;
    private static List<Block> blocks;
    private static List<EntityType<?>> entities;

    private static List<Item> items() {
        if (items == null) {
            List<Item> out = new ArrayList<>();
            BuiltInRegistries.ITEM.forEach(out::add);
            items = out;
        }
        return items;
    }

    private static List<Block> blocks() {
        if (blocks == null) {
            List<Block> out = new ArrayList<>();
            BuiltInRegistries.BLOCK.forEach(out::add);
            blocks = out;
        }
        return blocks;
    }

    private static List<EntityType<?>> entities() {
        if (entities == null) {
            List<EntityType<?>> out = new ArrayList<>();
            BuiltInRegistries.ENTITY_TYPE.forEach(out::add);
            entities = out;
        }
        return entities;
    }

    /**
     * 名词：物品 + 方块 + 实体全表按总量均匀抽（三池合一个虚拟下标空间，
     * 哪个池注册得多被抽中概率就大——"打了 mod 量更多就从更多里抽"）。
     */
    private static MutableComponent noun(RandomSource random) {
        int total = items().size() + blocks().size() + entities().size();
        int pick = random.nextInt(total);
        if (pick < items().size()) {
            return Component.translatable(items().get(pick).getDescriptionId());
        }
        pick -= items().size();
        if (pick < blocks().size()) {
            return Component.translatable(blocks().get(pick).getDescriptionId());
        }
        return Component.translatable(entities().get(pick - blocks().size()).getDescriptionId());
    }

    /** 主语专用：只从实体池抽（主谓宾的行为主体不能是物品/方块——用户定稿）。 */
    private static MutableComponent entityNoun(RandomSource random) {
        List<EntityType<?>> pool = entities();
        return Component.translatable(pool.get(random.nextInt(pool.size())).getDescriptionId());
    }

    /** 修饰语：全部状态效果（buff）名——注册表动态全表，mod 效果自动入池。 */
    private static MutableComponent adjective(RandomSource random) {
        List<MobEffect> effects = new ArrayList<>();
        BuiltInRegistries.MOB_EFFECT.forEach(effects::add);
        MobEffect effect = effects.get(random.nextInt(effects.size()));
        return Component.translatable(effect.getDescriptionId());
    }

    private static MutableComponent verb(RandomSource random) {
        return Component.translatable(VERB_KEYS[random.nextInt(VERB_KEYS.length)]);
    }
}
