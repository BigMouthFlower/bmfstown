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

    /**
     * 闲书的"词位解剖"（v0.10.3）：种子不只是拼书名——句式类型与各槽位
     * 的词都能结构化还原，供读书气泡做动态占位台词（"嗅探兽有意思"）。
     * 槽位含义随 {@code template} 而定（见 {@link #title} 的句式表）。
     *
     * @param template 句式下标 0~5（a~f）
     * @param words    该句式实际用到的槽位词（1~3 个，顺序 = 模板占位顺序）
     */
    public record BookWords(int template, List<MutableComponent> words) {

        public MutableComponent word(int index) {
            return this.words.get(index);
        }

        public int slotCount() {
            return this.words.size();
        }
    }

    /**
     * 按种子还原闲书的句式与词位（确定性重放）。调用顺序与
     * {@link #title} 完全一致——同一个 seed 两边还原出同一本书。
     */
    public static BookWords deconstruct(long seed) {
        RandomSource random = RandomSource.create(seed);
        return switch (random.nextInt(6)) {
            case 0 -> { // 偏正 a：修饰语之中心语
                MutableComponent mod = adjective(random);
                MutableComponent head = noun(random);
                yield new BookWords(0, List.of(mod, head));
            }
            case 1 -> { // 偏正 b：修饰语的中心语
                MutableComponent mod = adjective(random);
                MutableComponent head = noun(random);
                yield new BookWords(1, List.of(mod, head));
            }
            case 2 -> { // 〜录：单名词
                MutableComponent head = noun(random);
                yield new BookWords(2, List.of(head));
            }
            case 3 -> { // 论X与Y（并列）：双名词
                MutableComponent first = noun(random);
                MutableComponent second = noun(random);
                yield new BookWords(3, List.of(first, second));
            }
            case 4 -> { // 动宾：谓语 + 名词
                MutableComponent verb = verb(random);
                MutableComponent obj = noun(random);
                yield new BookWords(4, List.of(verb, obj));
            }
            default -> { // 主谓宾：实体 + 谓语 + 名词
                MutableComponent subject = entityNoun(random);
                MutableComponent verb = verb(random);
                MutableComponent obj = noun(random);
                yield new BookWords(5, List.of(subject, verb, obj));
            }
        };
    }

    /** 按种子生成书名（translatable 组合树，双语显示自动本地化）。 */
    public static MutableComponent title(long seed) {
        BookWords words = deconstruct(seed);
        return switch (words.template()) {
            case 0 -> Component.translatable("book.bmfstown.title.a",
                    words.word(0), words.word(1));                       // 修饰语之中心语
            case 1 -> Component.translatable("book.bmfstown.title.b",
                    words.word(0), words.word(1));                       // 修饰语的中心语
            case 2 -> Component.translatable("book.bmfstown.title.c",
                    words.word(0));                                      // 〜录
            case 3 -> Component.translatable("book.bmfstown.title.d",
                    words.word(0), words.word(1));                       // 论X与Y（并列）
            case 4 -> Component.translatable("book.bmfstown.title.e",
                    words.word(0), words.word(1));                       // 动宾
            default -> Component.translatable("book.bmfstown.title.f",
                    words.word(0), words.word(1), words.word(2));        // 主谓宾
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
