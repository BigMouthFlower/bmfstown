package com.bmf.bmfstown;

import com.bmf.bmfstown.entity.Resident;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 实体注册。
 *
 * <h2>EntityType 的三个关键参数</h2>
 * <table border="1">
 *   <tr><th>参数</th><th>含义</th><th>选错的后果</th></tr>
 *   <tr>
 *     <td>{@link MobCategory}</td>
 *     <td>生物分类，决定生成上限、是否随难度消失、是否计入生物容量</td>
 *     <td>写成 {@code MONSTER} 会导致白天被清除、亮度判定异常</td>
 *   </tr>
 *   <tr>
 *     <td>{@code sized(w, h)}</td>
 *     <td>碰撞箱宽高（方块单位）</td>
 *     <td>写错会导致卡墙、打不到、或者悬空</td>
 *   </tr>
 *   <tr>
 *     <td>{@code clientTrackingRange}</td>
 *     <td>客户端同步半径（区块数）</td>
 *     <td>太小吃不到动画，太大浪费带宽</td>
 *   </tr>
 * </table>
 *
 * <h2>为什么属性要在单独的事件里注册</h2>
 * 属性（血量、移速）不是 {@code EntityType} 的一部分，而是靠
 * {@link EntityAttributeCreationEvent} 在<b>注册表就绪之后</b>绑定上去的。
 * 这个事件必须在 mod 事件总线上监听，且必须早于任何实体被创建。
 */
public final class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, BmfTown.MOD_ID);

    /**
     * 镇民。
     *
     * <p>尺寸参考原版村民：宽 0.6、高 1.95（村民是 0.6 × 1.95）。
     * 这里略微收窄到 0.5，让它在人群里显得瘦一些，也和以后换模型留余地。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<Resident>> RESIDENT =
            ENTITY_TYPES.register("resident", () -> EntityType.Builder
                    .of(Resident::new, MobCategory.CREATURE)
                    .sized(0.5F, 1.9F)
                    .clientTrackingRange(10)
                    .build(ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "resident").toString()));

    private ModEntities() {
    }

    public static void register(IEventBus modEventBus) {
        ENTITY_TYPES.register(modEventBus);

        // 属性表绑定：注意监听的是 mod 事件总线
        modEventBus.addListener(ModEntities::onEntityAttributeCreation);
    }

    private static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(RESIDENT.get(), Resident.createAttributes().build());
    }
}
