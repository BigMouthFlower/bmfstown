package com.bmf.bmfstown;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 创造模式标签页。
 *
 * <p>为什么单独一个类？因为标签页会随着内容增多而不断加东西（方块、刷怪蛋、工具……），
 * 跟物品注册混在一起会很难维护。
 */
public final class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, BmfTown.MOD_ID);

    /** 主标签页。图标暂时用城镇凭证，等有了核心方块再换。 */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN =
            TABS.register("main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.bmfstown"))
                    .withTabsBefore(CreativeModeTabs.SPAWN_EGGS)
                    .icon(() -> ModItems.TOWN_CHARTER.get().getDefaultInstance())
                    .displayItems((params, output) -> {
                        output.accept(ModItems.TOWN_CHARTER.get());
                        output.accept(ModItems.TOWN_WAND.get());
                        output.accept(ModItems.TOWN_HANDBOOK.get());
                        output.accept(ModItems.RESIDENT_SPAWN_EGG.get());
                    })
                    .build());

    private ModCreativeTabs() {
    }

    public static void register(IEventBus modEventBus) {
        TABS.register(modEventBus);
    }
}
