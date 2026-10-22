package com.bmf.bmfstown.entity.profile;

import com.bmf.bmfstown.BmfTown;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * 自定义实体数据序列化器的注册。
 *
 * <h2>什么是 "EntityDataSerializer"</h2>
 * 原版 {@code SynchedEntityData} 里每个字段都有一个"怎么变成字节"的定义，
 * 叫 {@code EntityDataSerializer}。原版自带 INT / FLOAT / STRING / BLOCK_POS 等，
 * 但<b>没有</b>"我们的镇民档案"这种类型。所以必须自己注册一个。
 *
 * <h2>注册顺序很重要</h2>
 * 这个注册必须发生在 {@code Resident.defineSynchedData} 被调用之前。
 * 而 {@code defineSynchedData} 在实体<b>第一次实例化</b>时调用——
 * 也就是玩家进入世界、实体开始加载的时候。
 * 注册发生在 mod 构造阶段，远早于此，所以顺序是安全的。
 *
 * <p>但如果有人在 {@code static} 初始化块里就引用 {@code DATA_PROFILE}，
 * 可能触发类加载过早。所以 {@code DATA_PROFILE} 只在 {@code defineSynchedData}
 * 里引用（那时一定已经注册完了）。
 */
public final class ModDataSerializers {

    /**
     * 序列化器注册表。
     *
     * <p>注意这里用的是 {@link NeoForgeRegistries#ENTITY_DATA_SERIALIZERS}，
     * 不是 {@code BuiltInRegistries}——后者没有这个注册表。
     */
    public static final DeferredRegister<EntityDataSerializer<?>> SERIALIZERS =
            DeferredRegister.create(NeoForgeRegistries.ENTITY_DATA_SERIALIZERS, BmfTown.MOD_ID);

    /**
     * 镇民档案的序列化器。
     */
    public static final DeferredHolder<EntityDataSerializer<?>, EntityDataSerializer<ResidentProfile>> PROFILE =
            SERIALIZERS.register("resident_profile", ResidentProfileSerializer::create);

    private ModDataSerializers() {
    }

    public static void register(IEventBus modEventBus) {
        SERIALIZERS.register(modEventBus);
    }
}
