package com.bmf.bmfstown;

import com.bmf.bmfstown.client.ResidentScreen;
import com.bmf.bmfstown.menu.ModMenus;
import net.minecraft.world.entity.EntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * 客户端专用初始化。
 *
 * <p>这个类只会被客户端加载（{@code value = Dist.CLIENT}），
 * 专用服务器上根本不会碰它 —— 所以这里放任何渲染相关代码都是安全的，
 * 比如注册实体渲染器、模型层、屏幕等。
 *
 * <p>注意：{@code bus = EventBusSubscriber.Bus.MOD} 在 NeoForge 21.1 中
 * 已标记为「待删除」，且默认就是 MOD 总线，所以不再显式写出。
 */
@EventBusSubscriber(modid = BmfTown.MOD_ID, value = Dist.CLIENT)
public final class BmfTownClient {

    private BmfTownClient() {
    }

    @SubscribeEvent
    public static void onClientSetup(final FMLClientSetupEvent event) {
        BmfTown.LOGGER.info("[BmfTown] 客户端初始化完成");
    }

    /**
     * 把菜单类型和界面类绑起来。
     *
     * <h3>为什么这一步不能省</h3>
     * 服务端只发"打开菜单 #id"的包，客户端收到后需要自己 new 一个 Screen。
     * 这个映射关系必须在这里登记——否则玩家右键后游戏会直接崩，
     * 报错大意是 "no screen registered for menu type"。
     *
     * <p>这个方法必须在<b>客户端</b>注册，而且只能注册一次。
     * 用 {@code @EventBusSubscriber(value = Dist.CLIENT)} 保证专用服务器不加载。
     */
    @SubscribeEvent
    public static void onRegisterScreens(final RegisterMenuScreensEvent event) {
        event.register(ModMenus.RESIDENT.get(), ResidentScreen::new);
    }

    /**
     * 给原版怪物渲染器挂嘲讽红罩层。
     *
     * <p>逐个列出会进战的怪（渲染器按 EntityType 分开注册，没有
     * "给所有 Monster 统一挂"的入口），拿到它的 LivingEntityRenderer
     * addLayer 即可。列表外的怪只是没红罩（嘲讽逻辑不受影响）。
     */
    @SubscribeEvent
    public static void onAddLayers(final net.neoforged.neoforge.client.event.EntityRenderersEvent.AddLayers event) {
        EntityType<?>[] covered = {
                EntityType.ZOMBIE, EntityType.HUSK, EntityType.DROWNED, EntityType.ZOMBIE_VILLAGER,
                EntityType.SKELETON, EntityType.STRAY, EntityType.WITHER_SKELETON,
                EntityType.SPIDER, EntityType.CAVE_SPIDER, EntityType.CREEPER,
                EntityType.PILLAGER, EntityType.VINDICATOR, EntityType.EVOKER,
                EntityType.RAVAGER, EntityType.PHANTOM, EntityType.BLAZE, EntityType.WITCH,
                EntityType.ENDERMAN, EntityType.ZOMBIFIED_PIGLIN, EntityType.PIGLIN,
                EntityType.PIGLIN_BRUTE, EntityType.ZOGLIN, EntityType.HOGLIN,
                EntityType.SILVERFISH, EntityType.ENDERMITE, EntityType.VEX,
                EntityType.WARDEN, EntityType.BREEZE
        };
        int added = 0;
        for (EntityType<?> type : covered) {
            var renderer = event.getRenderer(type);
            if (renderer instanceof net.minecraft.client.renderer.entity.LivingEntityRenderer<?, ?> living) {
                addLayerUnchecked(living);
                added++;
            }
        }
        BmfTown.LOGGER.info("[BmfTown] 嘲讽红罩层已挂到 {} 种怪物渲染器", added);
    }

    /** 原版渲染器类型参数在事件里被通配符抹掉了，这里安全地补上（层内部不依赖具体类型）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void addLayerUnchecked(net.minecraft.client.renderer.entity.LivingEntityRenderer living) {
        living.addLayer(new com.bmf.bmfstown.client.TauntOverlayLayer(living));
    }

    /** 断线/换世界清空客户端嘲讽缓存，防止串档残留。 */
    @SubscribeEvent
    public static void onLoggingOut(final net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        com.bmf.bmfstown.client.ClientTauntState.clear();
        com.bmf.bmfstown.client.ClientMoodState.clear();
    }
}
