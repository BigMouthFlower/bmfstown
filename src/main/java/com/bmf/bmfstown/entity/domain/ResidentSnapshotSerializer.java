package com.bmf.bmfstown.entity.domain;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.syncher.EntityDataSerializer;

/**
 * {@link ResidentSnapshot} 的网络序列化器（六域重构后接替旧
 * ResidentProfileSerializer；NBT 往返方案不变——只有一处序列化逻辑，
 * 加字段只改快照自己）。
 *
 * <p>这里的 NBT 只用于网络传输，不落盘；落盘走域对象各自的 save/load。
 */
public final class ResidentSnapshotSerializer {

    public static final StreamCodec<RegistryFriendlyByteBuf, ResidentSnapshot> STREAM_CODEC =
            StreamCodec.<RegistryFriendlyByteBuf, ResidentSnapshot>of(
                    (buf, snapshot) -> buf.writeNbt(snapshot.save()),
                    buf -> {
                        CompoundTag tag = buf.readNbt();
                        // readNbt 可能返回 null（对端没写，或数据损坏）
                        return tag == null
                                ? ResidentSnapshot.defaults()
                                : ResidentSnapshot.load(tag);
                    }
            );

    private ResidentSnapshotSerializer() {
    }

    /**
     * 创建 {@link EntityDataSerializer}。
     * forValueType 的前提是"值本身不可变"——快照是值拷贝，符合前提。
     */
    public static EntityDataSerializer<ResidentSnapshot> create() {
        return EntityDataSerializer.forValueType(STREAM_CODEC);
    }
}
