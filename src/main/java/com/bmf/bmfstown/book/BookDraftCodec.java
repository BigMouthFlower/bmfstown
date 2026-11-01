package com.bmf.bmfstown.book;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * {@link BookDraft} 的持久化编解码（判别式）。
 *
 * <p>DFU 的判别分派没有想象中顺手的现成写法（registry dispatch 要为
 * 三种书注册一套子类型注册表，杀鸡用牛刀），所以用最直白的方案：
 * kind 判别字段 + 各种类字段 optional 化，getter 侧模式匹配、工厂侧
 * 按 kind 分派。字段总数远低于 RecordCodecBuilder 的 16 上限。
 *
 * <p>新增书种类时：BookDraft 加 record → 这里加 optional 字段与一个
 * case——两处，编译器会通过穷尽 switch 提醒前者。
 */
public final class BookDraftCodec {

    public static final Codec<BookDraft> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.STRING.fieldOf("Kind").forGetter(BookDraft::kindName),
            Codec.STRING.optionalFieldOf("PerkId", "")
                    .forGetter(d -> d instanceof BookDraft.Perk p ? p.perkId() : ""),
            Codec.STRING.optionalFieldOf("ProfessionId", "")
                    .forGetter(d -> d instanceof BookDraft.Perk p ? p.professionId() : ""),
            Codec.INT.optionalFieldOf("PracticeCount", 0)
                    .forGetter(d -> d instanceof BookDraft.Perk p ? p.practiceCount() : 0),
            Codec.INT.optionalFieldOf("PracticeGoal", 0)
                    .forGetter(d -> d instanceof BookDraft.Perk p ? p.practiceGoal() : 0),
            Codec.STRING.optionalFieldOf("UnlockId", "")
                    .forGetter(d -> d instanceof BookDraft.Unlock u ? u.unlockId() : ""),
            Codec.LONG.optionalFieldOf("Seed", 0L)
                    .forGetter(d -> d instanceof BookDraft.Idle i ? i.seed() : 0L)
    ).apply(inst, BookDraftCodec::from));

    private BookDraftCodec() {
    }

    private static BookDraft from(String kind, String perkId, String professionId,
                                  int practiceCount, int practiceGoal, String unlockId, long seed) {
        return switch (kind) {
            case "perk" -> new BookDraft.Perk(perkId, professionId, practiceCount, practiceGoal);
            case "unlock" -> new BookDraft.Unlock(unlockId);
            case "idle" -> new BookDraft.Idle(seed);
            default -> throw new IllegalStateException("未知书稿种类: " + kind);
        };
    }
}
