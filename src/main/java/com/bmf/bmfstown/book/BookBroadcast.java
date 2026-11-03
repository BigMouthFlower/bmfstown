package com.bmf.bmfstown.book;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.UUID;

/**
 * 特殊书广播（v0.10.4 学识制配套，用户定稿的两条事件）：
 *
 * <h2>① 发现（永久闩锁，同镇只播一次）</h2>
 * 学者<b>写出</b>一本特殊书的瞬间（或有人学成一本从未发现过的书——
 * 管理员刷书等边缘）。视觉与解锁公告同一套语言："已发现「书籍」《X》"；
 * 闩锁在 {@link BookPoolState} 的 per-town {@code discovered} 集合里，
 * 正常游玩永不重播。
 *
 * <h2>② 学会（每次都播）</h2>
 * 任何人学成一本特殊书："「张三」学会了《X》"——知识在镇上传开是
 * 公共事件，没有"只播一次"的语义。
 *
 * <p>两条都不走解锁登记表——书本的"发现/学会"与解锁树是两个维度，
 * 注册成 unlock 定义会污染解锁树 UI 与前置连锁。
 */
public final class BookBroadcast {

    private BookBroadcast() {
    }

    /** 发现广播：同镇首次返回 true 并公告，否则静默。 */
    public static boolean discovered(ServerLevel level, UUID townId, String bookId) {
        if (townId == null || bookId == null
                || SpecialBookRegistry.byId(bookId) == null) {
            return false;
        }
        if (!BookPoolState.get(level).markDiscovered(townId, bookId)) {
            return false; // 已发现过：永久闩锁，不重播
        }
        var message = Component.translatable("msg.bmfstown.unlock.announce.head") // "已发现 "
                .append(Component.translatable("msg.bmfstown.unlock.type.book")
                        .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
                .append(Component.literal("《").withStyle(ChatFormatting.GOLD))
                .append(Component.translatable(
                                "book.bmfstown.special." + bookId + ".name")
                        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(Component.literal("》").withStyle(ChatFormatting.GOLD));
        for (var player : level.players()) {
            player.displayClientMessage(message, false);
            player.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP,
                    SoundSource.PLAYERS, 0.8F, 1.0F);
        }
        return true;
    }

    /** 学会广播：谁学成都有（无闩锁）。 */
    public static void learned(ServerLevel level, String learnerName, String bookId) {
        if (bookId == null || SpecialBookRegistry.byId(bookId) == null) {
            return;
        }
        var message = Component.translatable("msg.bmfstown.book.learned", learnerName)
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal("《").withStyle(ChatFormatting.GOLD))
                .append(Component.translatable(
                                "book.bmfstown.special." + bookId + ".name")
                        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(Component.literal("》").withStyle(ChatFormatting.GOLD));
        for (var player : level.players()) {
            player.displayClientMessage(message, false);
            player.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP,
                    SoundSource.PLAYERS, 0.5F, 1.5F); // 比发现广播轻一号
        }
    }
}
