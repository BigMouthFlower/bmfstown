package com.bmf.bmfstown.entity.profile;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.util.RandomSource;

/**
 * {@link ResidentProfile} 的网络序列化器。
 *
 * <h2>这是干什么的</h2>
 * 原版实体的 {@code SynchedEntityData} 只认基本类型（int/float/string/boolean）。
 * 要把整个 {@code ResidentProfile} 同步给客户端，就得注册一个自定义序列化器，
 * 告诉游戏"怎么把它变成网络字节、又怎么变回来"。
 *
 * <h2>为什么走 NBT 而不是逐字段手写</h2>
 * 有两条路：
 * <ol>
 *   <li><b>逐字段手写</b>：{@code buf.writeInt(hunger); buf.writeInt(mood); ...}
 *       —— 快，但每次加字段都要同步改读写两处，<b>顺序写错就整个错位</b>，
 *       而且没有任何编译期检查。</li>
 *   <li><b>转成 NBT 再传</b>：复用 {@link ResidentProfile#save()} 与
 *       {@link ResidentProfile#load(CompoundTag)}，只有一处序列化逻辑，
 *       加字段只改 profile 自己。</li>
 * </ol>
 *
 * <h2>性能说明</h2>
 * NBT 方案每个包多几十字节，但：
 * <ul>
 *   <li>只在 profile <b>真正变化时</b>才发（靠 {@code equals} 判断）</li>
 *   <li>一个镇民的档案也就几百字节，相比区块数据可忽略</li>
 *   <li>换取的"只有一处序列化代码"的价值远大于这点开销</li>
 * </ul>
 *
 * <p><b>注意</b>：这里的 NBT 只用于网络传输，不落盘。
 * 落盘走实体自己的 {@code addAdditionalSaveData}。
 */
public final class ResidentProfileSerializer {

    /**
     * 流编解码器。
     *
     * <p>用 {@link StreamCodec#of}（而不是 {@code composite}）：
     * 我们只有<b>一个</b>字段——整份 NBT，用不上 composite 那套
     * "每个字段一对 codec+getter、最后接 factory"的机制。
     *
     * <h3>为什么不能写 {@code StreamCodec.<RegistryFriendlyByteBuf>of(...)}</h3>
     * {@code of} 声明时是 {@code static <B, V> StreamCodec<B, V> of(...)}，
     * 两个类型参数。Java 有个硬规矩：
     * <b>方法调用上写了显式类型参数，就必须把全部类型参数都写出来</b>，
     * 不能只写一部分让剩下的靠推断。
     *
     * <p>所以只能写成 {@code StreamCodec.<RegistryFriendlyByteBuf, ResidentProfile>of(...)}
     * ——两个都写。只写一个就会报"类型变量数目错误; 需要 2"。
     *
     * <h3>为什么类型能对上</h3>
     * {@code of} 的两个参数是：
     * <ul>
     *   <li>{@code StreamEncoder<? super B, V>} —— 这里 {@code B} 固定为
     *       {@code RegistryFriendlyByteBuf}，所以 lambda 的 buf 参数就是它</li>
     *   <li>{@code StreamDecoder<? super B, V>} —— 同上</li>
     * </ul>
     * 两个 {@code super} 通配让 lambda 可以安全地接受 {@code RegistryFriendlyByteBuf}，
     * 不会卡在"型变"上（这正是之前 {@code composite} 传对象而不是 lambda 会失败的原因）。
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, ResidentProfile> STREAM_CODEC =
            StreamCodec.<RegistryFriendlyByteBuf, ResidentProfile>of(
                    (buf, profile) -> buf.writeNbt(profile.save()),
                    buf -> {
                        CompoundTag tag = buf.readNbt();
                        // readNbt 可能返回 null（对端没写，或数据损坏）
                        return tag == null
                                ? ResidentProfile.createRandom(RandomSource.create())
                                : ResidentProfile.load(tag);
                    }
            );

    private ResidentProfileSerializer() {
    }

    /**
     * 创建 {@link EntityDataSerializer}。
     *
     * <p>{@code forValueType} 的意思是"值类型本身就是不可变的"，
     * 所以 {@code copy()} 直接返回原对象即可（不用深拷贝）。
     * {@link ResidentProfile} 是不可变 record，正好符合这个前提——
     * <b>如果哪天把它改成可变类，这里必须换成手写 copy，否则会出现
     * "两个镇民共享同一份档案"的灾难性 bug。</b>
     */
    public static EntityDataSerializer<ResidentProfile> create() {
        return EntityDataSerializer.forValueType(STREAM_CODEC);
    }
}
