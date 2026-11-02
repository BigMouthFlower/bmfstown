package com.bmf.bmfstown.book;

import com.bmf.bmfstown.config.Config;
import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.entity.profile.Perks;
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
 * <h2>perk 插入的两个判定层（v0.10.3 学者树）</h2>
 * <ul>
 *   <li><b>三门问题</b>（传奇 A）：把"特殊书 vs 闲书"抽象成十份模型
 *       （s 份中奖 = 概率×10 取整）——先选门、系统揭示一扇不中奖的、
 *       换门定结果。中签率精确等于 9s/80（30% → 33.75%，相对 +12.5%）；
 *       池空时不生效；</li>
 *   <li><b>过拟合</b>（大师 A）：中签后再掷 50%——从<b>全量登记池</b>抽
 *       （可能抽到已写出的书 = 重复副本，池子不动）或从剩余池抽
 *       （未写过的照常出池）。</li>
 * </ul>
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
    /** 三门问题模型的份数（70/30 的最小公倍数刻度——用户 00:18 定稿）。 */
    private static final int MONTY_SLOTS = 10;
    /** 三门问题：主持人揭示一扇后，换门时剩余的候选门数（10 - 首选 1 - 揭示 1）。 */
    private static final int MONTY_SWITCH_CANDIDATES = 8;

    private BookDrafting() {
    }

    /**
     * 开写抽取：返回抽定的内容单。目标时长由调用方按
     * {@link #targetTicks} 计算（这里只负责"写成什么"）。
     *
     * @param scholar 写书的学者（ perk 判定用；可为 null——判空安全）
     */
    public static BookDraft roll(ServerLevel level, UUID townId, RandomSource random,
            Resident scholar) {
        int chance = Math.min(100, Math.max(0, Config.SCHOLAR_PERK_BOOK_CHANCE.get()));
        if (montySpecialRoll(random, chance, scholar)) {
            String picked = overfitDraw(level, townId, random, scholar);
            if (picked != null) {
                SpecialBookRegistry.SpecialBook book = SpecialBookRegistry.byId(picked);
                if (book != null) {
                    return book.draft();
                }
            }
        }
        return new BookDraft.Idle(random.nextLong());
    }

    /**
     * "这次能不能中特殊书"：三门问题 perk 把单次概率判定换成
     * 十份模型的选门-揭示-换门模拟（闭式中签率 9s/80）。
     */
    private static boolean montySpecialRoll(RandomSource random, int chance, Resident scholar) {
        boolean monty = scholar != null && scholar.hasPerk(Perks.NODE_S_LEGEND, "monty");
        if (!monty) {
            return random.nextInt(100) < chance;
        }
        int s = Math.max(0, Math.min(MONTY_SLOTS,
                (int) Math.round(chance / (double) 100 * MONTY_SLOTS)));
        if (s <= 0) {
            return false; // 池子判定本来就全闲书：换门也换不出花来
        }
        boolean firstSpecial = random.nextInt(MONTY_SLOTS) < s;
        // 主持人揭示一扇不中奖的门后换门：候选 8 扇里
        //   首选中奖 → 剩 s-1 扇中奖；首选拉胯 → 剩 s 扇中奖
        return firstSpecial
                ? random.nextInt(MONTY_SWITCH_CANDIDATES) < s - 1
                : random.nextInt(MONTY_SWITCH_CANDIDATES) < s;
    }

    /** 中签后的取书：过拟合 50% 走全量池（重复副本不消耗），否则正常不放回。 */
    private static String overfitDraw(ServerLevel level, UUID townId, RandomSource random,
            Resident scholar) {
        boolean overfit = scholar != null && scholar.hasPerk(Perks.NODE_S_MASTER, "overfit");
        if (overfit && random.nextBoolean()) {
            return BookPoolState.get(level).drawFromFullPool(level, townId, random);
        }
        return BookPoolState.get(level).draw(level, townId, random);
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
