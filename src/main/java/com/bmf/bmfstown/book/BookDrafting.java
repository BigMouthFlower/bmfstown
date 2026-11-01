package com.bmf.bmfstown.book;

import com.bmf.bmfstown.config.Config;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

import java.util.UUID;

/**
 * 开写抽奖入口（v0.11）：学者把书+墨囊放上讲台的瞬间，
 * 这本书写成什么就在这里定死（写进"写作中的书"的 NBT，玩家不可见）。
 *
 * <h2>抽奖流程（用户定稿）</h2>
 * <ol>
 *   <li>概率判定（{@code scholar.perkBookChance}，默认 30%）命中
 *       且该镇剩余池非空 → {@link BookPoolState#draw} 不放回抽一本
 *       登记书 → 按登记表 id 生成对应内容单（unlock 前缀 → 解锁书）；</li>
 *   <li>未命中或池空 → 闲书（词库种子）。</li>
 * </ol>
 *
 * <h2>耗时系数（用户定稿：闲书幅度大）</h2>
 * <ul>
 *   <li>perk/解锁书 = 基准 ×1.5（写书 90 分钟 → 135 分钟）；</li>
 *   <li>闲书 = 基准 ×0.4（写书 90 分钟 → 36 分钟）。</li>
 * </ul>
 */
public final class BookDrafting {

    /** perk/解锁书耗时系数。 */
    private static final double SPECIAL_FACTOR = 1.5D;
    /** 闲书耗时系数（幅度更大——闲书是日常产出主力）。 */
    private static final double IDLE_FACTOR = 0.4D;

    private BookDrafting() {
    }

    /**
     * 开写抽取：返回抽定的内容单。目标时长由调用方按
     * {@link #targetTicks} 计算（这里只负责"写成什么"）。
     */
    public static BookDraft roll(ServerLevel level, UUID townId, RandomSource random) {
        int chance = Math.min(100, Math.max(0, Config.SCHOLAR_PERK_BOOK_CHANCE.get()));
        if (random.nextInt(100) < chance) {
            String picked = BookPoolState.get(level).draw(level, townId, random);
            if (picked != null) {
                SpecialBookRegistry.SpecialBook book = SpecialBookRegistry.byId(picked);
                if (book != null) {
                    return book.draft();
                }
            }
        }
        return new BookDraft.Idle(random.nextLong());
    }

    /** 目标工作量（在岗 tick，基准按写书；效率作乘数在进度累计侧）。 */
    public static long targetTicks(BookDraft draft) {
        long base = Config.SCHOLAR_WRITE_TICKS.get();
        return Math.max(200L, Math.round(base * switch (draft) {
            case BookDraft.Perk p -> SPECIAL_FACTOR;
            case BookDraft.Unlock u -> SPECIAL_FACTOR;
            case BookDraft.Idle i -> IDLE_FACTOR;
        }));
    }

    /** 抄书目标工作量（用户定稿：60 现实分钟，比写书快）。 */
    public static long copyTargetTicks() {
        return Math.max(200L, Config.SCHOLAR_COPY_TICKS.get());
    }
}
