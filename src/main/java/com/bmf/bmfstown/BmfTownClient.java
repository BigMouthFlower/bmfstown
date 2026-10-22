package com.bmf.bmfstown;

import com.bmf.bmfstown.client.ResidentScreen;
import com.bmf.bmfstown.menu.ModMenus;
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
}
