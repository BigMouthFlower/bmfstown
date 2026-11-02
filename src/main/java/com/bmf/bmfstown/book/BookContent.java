package com.bmf.bmfstown.book;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 一册书的完整数据（v0.11）：**写作中的书与成书共用同一组件**——
 * progress 达到 target 即视为完成，转换物品形态时组件整体拷贝。
 *
 * <h2>字段职责</h2>
 * <ul>
 *   <li><b>townId</b>：诞生城镇（奖池退还需知——书稿销毁时归还该镇池子）；</li>
 *   <li><b>authors</b>：作者链（名字列表，用户定稿——谁写过往后串，
 *       死了也是作者，不搞"佚名"）。中途换人续写就往后追加；"第二作者"
 *       特性（ghostwriter，未实装）实装后在完成钩子里 append——
 *       列表结构天然预留；</li>
 *   <li><b>draft</b>：{@link BookDraft} 密封内容单——种类解耦的核心，
 *       实践计数器也在里面（跟着书走：换人/掉落/继承全自带）；</li>
 *   <li><b>progress / target</b>：写作进度按 <b>gameTime 在岗逐 tick 累计</b>
 *       （写作状态每 tick 进度 += 效率系数；{@code /time set} 完全影响不了）。
 *       target 开写时按"类型系数 × 基准"算定；效率提升即时生效，因为
 *       增量每 tick 按当前效率算；</li>
 *   <li><b>checkpointPassed</b>：50% 卡点是否已解除（实践计数满）——
 *       永久写在书上；传奇直写（途径 A）不经过此标记。</li>
 * </ul>
 *
 * <h2>不变量</h2>
 * <ul>
 *   <li>progress ∈ [0, target]，完成判定 = {@code progress >= target}；</li>
 *   <li>authors 永远 ≥ 1（开写时至少有第一作者）；</li>
 *   <li>实例不可变——修改一律返回新实例（withXxx 系）。</li>
 * </ul>
 */
public record BookContent(UUID townId, List<String> authors, BookDraft draft,
                          long progress, long target, boolean checkpointPassed,
                          boolean annotated) {

    /** 开写：进度归零、卡点未解除、作者链只有第一人、非批注。 */
    public static BookContent startWriting(UUID townId, String firstAuthor, BookDraft draft, long target) {
        return new BookContent(townId, List.of(firstAuthor), draft, 0L, target, false, false);
    }

    /** 写作 tick（效率作乘数，由调用方把本 tick 的贡献值算好传入）。 */
    public BookContent withProgressAdded(long amount) {
        return new BookContent(this.townId, this.authors, this.draft,
                Math.min(this.target, this.progress + amount), this.target, this.checkpointPassed,
                this.annotated);
    }

    /**
     * 标记为批注本（批注 perk，v0.10.3）：开写时定死，书名加后缀
     * "——批注"，闲书阅读心情加成 ×1.5、他人研读 perk 书习得更快。
     */
    public BookContent withAnnotated() {
        return new BookContent(this.townId, this.authors, this.draft,
                this.progress, this.target, this.checkpointPassed, true);
    }

    /** 换人续写：作者名往后串（用户定稿：死者也是作者，名字链永留）。 */
    public BookContent withAdditionalAuthor(String author) {
        if (this.authors.contains(author)) {
            return this;
        }
        List<String> out = new ArrayList<>(this.authors);
        out.add(author);
        return new BookContent(this.townId, List.copyOf(out), this.draft,
                this.progress, this.target, this.checkpointPassed, this.annotated);
    }

    /** 实践计数 +1（书 NBT 自带计数器，被跟随的 NPC 每完成一次对应行为 +1）。 */
    public BookContent withPracticeIncremented() {
        if (!(this.draft instanceof BookDraft.Perk perk) || this.checkpointPassed) {
            return this; // 非职业书没有计数器；已解除的不再累计
        }
        int count = Math.min(perk.practiceGoal(), perk.practiceCount() + 1);
        return new BookContent(this.townId, this.authors, new BookDraft.Perk(
                        perk.perkId(), perk.professionId(), count, perk.practiceGoal()),
                this.progress, this.target, count >= perk.practiceGoal(), this.annotated);
    }

    /**
     * 本 tick 是否处于 50% 卡点（写作状态机用它决定停笔）。
     * practiceGoal = 0 的书（无观察挂点的学科）不吃卡点——否则永远写不完。
     */
    public boolean isStuck() {
        return this.progress >= this.target / 2 && !this.checkpointPassed
                && this.draft instanceof BookDraft.Perk perk
                && perk.practiceGoal() > 0;
    }

    /** 是否已完成。 */
    public boolean isFinished() {
        return this.progress >= this.target;
    }

    // ==========================================================
    //  序列化（NBT persistent + 网络 STREAM）
    // ==========================================================

    public static final Codec<BookContent> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            UUIDUtil.CODEC.fieldOf("Town").forGetter(BookContent::townId),
            Codec.STRING.listOf().fieldOf("Authors").forGetter(BookContent::authors),
            BookDraftCodec.CODEC.fieldOf("Draft").forGetter(BookContent::draft),
            Codec.LONG.fieldOf("Progress").forGetter(BookContent::progress),
            Codec.LONG.fieldOf("Target").forGetter(BookContent::target),
            Codec.BOOL.optionalFieldOf("CheckpointPassed", false).forGetter(BookContent::checkpointPassed),
            Codec.BOOL.optionalFieldOf("Annotated", false).forGetter(BookContent::annotated)
    ).apply(inst, BookContent::new));

    public static final StreamCodec<ByteBuf, BookContent> STREAM_CODEC = StreamCodec.ofMember(
            (content, buf) -> {
                buf.writeLong(content.townId().getMostSignificantBits());
                buf.writeLong(content.townId().getLeastSignificantBits());
                ByteBufCodecs.VAR_INT.encode(buf, content.authors().size());
                for (String author : content.authors()) {
                    ByteBufCodecs.STRING_UTF8.encode(buf, author);
                }
                content.draft().write(buf);
                buf.writeLong(content.progress());
                buf.writeLong(content.target());
                buf.writeBoolean(content.checkpointPassed());
                buf.writeBoolean(content.annotated());
            },
            buf -> {
                java.util.UUID townId = new java.util.UUID(buf.readLong(), buf.readLong());
                int authorCount = ByteBufCodecs.VAR_INT.decode(buf);
                List<String> authors = new ArrayList<>(authorCount);
                for (int i = 0; i < authorCount; i++) {
                    authors.add(ByteBufCodecs.STRING_UTF8.decode(buf));
                }
                BookDraft draft = BookDraft.read(buf);
                long progress = buf.readLong();
                long target = buf.readLong();
                boolean passed = buf.readBoolean();
                boolean annotated = buf.readBoolean();
                return new BookContent(townId, List.copyOf(authors), draft, progress, target, passed, annotated);
            });
}
