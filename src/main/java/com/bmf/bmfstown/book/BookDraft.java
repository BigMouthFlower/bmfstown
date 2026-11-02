package com.bmf.bmfstown.book;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;

/**
 * 书的"内容单"（sealed 密封）：一册书写成什么，由三种互斥的种类之一描述。
 *
 * <p><b>为什么是 sealed 多态而不是字段堆一起</b>（v0.11 设计定稿）：
 * 三种书的数据形状完全不同——闲书只有词库种子、解锁书只有一个 id、
 * 职业强化书还要带实践计数。密封接口让"加一种新书" = 新增一个 record
 * + 序列化一个分支，<b>不改动任何现有代码</b>；编译器还会强制穷尽
 * switch，漏了新种类直接编译报错。
 *
 * <p>三种种类（对应 NBT 判别键）：
 * <ul>
 *   <li>{@link Perk}（{@code "perk"}）——职业强化书：perk id + 挂靠职业 +
 *       实践计数/目标（50% 卡点的解除条件，<b>计数器放在书上</b>，与书同走：
 *       换人、掉落、继承全自带）；</li>
 *   <li>{@link Unlock}（{@code "unlock"}）——隐藏解锁书：一个解锁 id，写成时
 *       走 {@code UnlockState} 广播；</li>
 *   <li>{@link Idle}（{@code "idle"}）——闲书：词库拼装的图一乐读物，
 *       只存随机种子（书名/内容由种子确定性生成，不存全文）。</li>
 * </ul>
 *
 * <p>实例不可变；网络与 NBT 序列化都在本类内完成，外部只见强类型。
 */
public sealed interface BookDraft permits BookDraft.Perk, BookDraft.Unlock, BookDraft.Idle {

    /** NBT/网络判别键（也是将来往注册表里填新种类时的路由名）。 */
    String kindName();

    /** 特殊书的登记键（perk 书 = perkId，解锁书 = unlockId；闲书 = null）。 */
    default String bookId() {
        return switch (this) {
            case Perk p -> p.perkId();
            case Unlock u -> u.unlockId();
            case Idle i -> null;
        };
    }

    // ==========================================================
    //  三种实现
    // ==========================================================

    /** 职业强化书：构筑外 perk 的载体。practiceGoal 开写时按职业定死在书上。 */
    record Perk(String perkId, String professionId, int practiceCount, int practiceGoal)
            implements BookDraft {

        @Override
        public String kindName() {
            return "perk";
        }
    }

    /** 隐藏解锁书：写成时触发一次解锁广播。 */
    record Unlock(String unlockId) implements BookDraft {

        @Override
        public String kindName() {
            return "unlock";
        }
    }

    /** 闲书：词库拼装的图一乐读物，只存种子。 */
    record Idle(long seed) implements BookDraft {

        @Override
        public String kindName() {
            return "idle";
        }
    }

    // ==========================================================
    //  网络序列化（判别键 → 分派）
    // ==========================================================

    /** 按判别键写入：kind 字符串 + 各自字段。新增种类 = 加一个 case。 */
    default void write(ByteBuf buf) {
        ByteBufCodecs.STRING_UTF8.encode(buf, this.kindName());
        switch (this) {
            case Perk p -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, p.perkId());
                ByteBufCodecs.STRING_UTF8.encode(buf, p.professionId());
                ByteBufCodecs.VAR_INT.encode(buf, p.practiceCount());
                ByteBufCodecs.VAR_INT.encode(buf, p.practiceGoal());
            }
            case Unlock u -> ByteBufCodecs.STRING_UTF8.encode(buf, u.unlockId());
            case Idle i -> ByteBufCodecs.VAR_LONG.encode(buf, i.seed());
        }
    }

    /** 按判别键读出。未知 kind 抛异常——坏数据宁可暴露也不要静默变闲书。 */
    static BookDraft read(ByteBuf buf) {
        return switch (ByteBufCodecs.STRING_UTF8.decode(buf)) {
            case "perk" -> new Perk(
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf));
            case "unlock" -> new Unlock(ByteBufCodecs.STRING_UTF8.decode(buf));
            case "idle" -> new Idle(ByteBufCodecs.VAR_LONG.decode(buf));
            case String unknown -> throw new IllegalStateException("未知书稿种类: " + unknown);
        };
    }
}
