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
                        output.accept(ModItems.WALL_CALENDAR.get());
                        output.accept(ModItems.BULLETIN_BOARD.get());
                        output.accept(ModItems.TAVERN_MENU_BOARD.get());
                        // 墓碑：8 种材质共用一个物品，但**各给一条**——
                        // 创造模式是玩家唯一能直接拿到"非石砖"材质的路（生存只能切石机）。
                        // 只放一条的话，创造栏里给出的永远是没组件的那块，落地就是石砖，
                        // 玩家会以为"材质功能坏了"（2026-11-28 实际被问到）。
                        for (com.bmf.bmfstown.block.GraveStoneVariant v
                                : com.bmf.bmfstown.block.GraveStoneVariant.values()) {
                            output.accept(com.bmf.bmfstown.block.GraveStoneItem.stackFor(v));
                        }
                        output.accept(ModItems.WRITING_BOOK.get());
                        output.accept(ModItems.FINISHED_BOOK.get());
                        output.accept(ModItems.RESIDENT_SPAWN_EGG.get());
                        // 建材通货（v0.17 石匠线）：石匠拆解产物 / 切石机原料
                        output.accept(ModItems.MATERIAL_STONE.get());
                        output.accept(ModItems.MATERIAL_WOOD.get());
                        output.accept(ModItems.MATERIAL_SAND.get());
                    })
                    .build());

    private ModCreativeTabs() {
    }

    public static void register(IEventBus modEventBus) {
        TABS.register(modEventBus);
    }
}
