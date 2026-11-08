package com.bmf.bmfstown.fireworks;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * 烟火工匠章（v0.13）：工匠产烟花的战斗向 perk 物化标记（出厂即定型，
 * 与炼金 stamp 同模式——工匠不在场照样生效）。
 *
 * <ul>
 *   <li>{@code shrapnel}=破片（爆炸判定半径 +1）；</li>
 *   <li>{@code payload}=装药足量（爆炸伤害 +25%）；</li>
 *   <li>{@code echo}=回响（首爆后延迟再爆一次，伤害=第一次的一半）。</li>
 * </ul>
 *
 * <p>爆炸挂点（{@code FireworkRocketEntityMixin}）读章生效：有章走自定义
 * 伤害结算（原版公式+章加成），无章走原版逻辑照跑——玩家产的烟花零变化。
 */
public record FireworksMark(boolean shrapnel, boolean payload, boolean echo) {

    public static final Codec<FireworksMark> CODEC = RecordCodecBuilder.create(inst ->
            inst.group(
                    Codec.BOOL.fieldOf("shrapnel").forGetter(FireworksMark::shrapnel),
                    Codec.BOOL.fieldOf("payload").forGetter(FireworksMark::payload),
                    Codec.BOOL.fieldOf("echo").forGetter(FireworksMark::echo)
            ).apply(inst, FireworksMark::new));

    public static final StreamCodec<io.netty.buffer.ByteBuf, FireworksMark> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, FireworksMark::shrapnel,
                    ByteBufCodecs.BOOL, FireworksMark::payload,
                    ByteBufCodecs.BOOL, FireworksMark::echo,
                    FireworksMark::new);
}
