package com.bmf.bmfstown;

import com.bmf.bmfstown.config.Config;
import com.bmf.bmfstown.entity.profile.ModDataSerializers;
import com.bmf.bmfstown.menu.ModMenus;
import com.bmf.bmfstown.network.ModNetworking;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.bus.api.SubscribeEvent;
import org.slf4j.Logger;

/**
 * BMF的城镇 —— 主入口。
 *
 * <p>这个类的职责只有一件事：<b>把各个注册器挂到 mod 事件总线上</b>。
 * 具体的物品、方块、实体、数据组件都放在各自的类里，不要全塞在这里，
 * 否则几百行之后就没人看得懂了。
 *
 * <p>构造函数的两个参数由 NeoForge 注入：
 * <ul>
 *   <li>{@code IEventBus modEventBus} —— mod 生命周期事件总线（注册、初始化）</li>
 *   <li>{@code ModContainer modContainer} —— 本 mod 的容器对象（读配置、元数据用）</li>
 * </ul>
 */
@Mod(BmfTown.MOD_ID)
public class BmfTown {

    /** mod id，必须与 gradle.properties 的 mod_id 一致。 */
    public static final String MOD_ID = "bmfstown";

    public static final Logger LOGGER = LogUtils.getLogger();

    public BmfTown(IEventBus modEventBus, ModContainer modContainer) {
        // ---- 配置 ----
        // COMMON：单人和服务端共用一份（config/bmfstown-common.toml）。
        // 注册必须在其它初始化之前，加载发生在 mod 构造之后、
        // 实体属性注册之前——所以 Config.* 在 createAttributes 里可用。
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // ---- 注册器挂载 ----
        // 每加一类内容（物品/方块/实体/组件），就在这里多一行。
        //
        // 顺序说明：数据序列化器必须早于实体，因为 Resident.defineSynchedData
        // 会引用它。实体实例化远晚于 mod 构造，但保持这个顺序更安全。
        ModDataSerializers.register(modEventBus);
        ModDataComponents.register(modEventBus);
        com.bmf.bmfstown.block.ModBlocks.register(modEventBus);
        ModItems.register(modEventBus);
        ModCreativeTabs.register(modEventBus);
        ModEntities.register(modEventBus);
        ModSounds.register(modEventBus);
        ModMenus.register(modEventBus);
        ModNetworking.register(modEventBus);

        // ---- 生命周期 ----
        modEventBus.addListener(this::commonSetup);

        // ---- 游戏事件总线 ----
        // 注意：mod 生命周期事件（注册、加载）走 modEventBus；
        // 游戏内事件（玩家登录、服务端启动）走 NeoForge.EVENT_BUS。两者不能混。
        NeoForge.EVENT_BUS.register(this);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("[BmfTown] common setup —— 城镇 mod 已加载");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("[BmfTown] 服务端启动 —— 注入成功");
    }

    /**
     * 注册命令。
     *
     * <p>注意：命令注册事件 {@code RegisterCommandsEvent} 在
     * {@code NeoForge.EVENT_BUS} 上（游戏总线），不是 mod 总线。
     * 挂错总线的话命令会静默不生效——不报错，但 {@code /bmfstown} 不存在。
     */
    @SubscribeEvent
    public void onRegisterCommands(net.neoforged.neoforge.event.RegisterCommandsEvent event) {
        com.bmf.bmfstown.command.ResidentCommands.register(event.getDispatcher());
    }
}
