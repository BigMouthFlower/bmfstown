package com.bmf.bmfstown.menu;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.entity.Resident;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 菜单类型注册。
 *
 * <h2>为什么用 {@code IMenuTypeExtension.create} 而不是 {@code new MenuType(...)}</h2>
 * 客户端的 {@link ResidentMenu} 需要知道"我在看哪个镇民"，
 * 但客户端的构造方法拿不到实体（实体的构造发生在客户端，参数只有
 * {@code (containerId, playerInventory)}）。
 *
 * <p>NeoForge 给了额外通道：服务端打开界面时可以用
 * {@code player.openMenu(provider, buf -&gt; buf.writeVarInt(entityId))} 附加数据，
 * 客户端构造时从这个 {@code RegistryFriendlyByteBuf} 里读出来。
 * {@code IMenuTypeExtension.create(IContainerFactory)} 就是为了支持这个而存在的——
 * 它生成的 {@link MenuType} 会把那段额外数据传给工厂方法。
 *
 * <p>普通 {@code new MenuType(MenuSupplier, FeatureFlagSet)} 没有这段数据，
 * 用它就只能拿到 containerId 和玩家背包，做不了"打开指定实体"的界面。
 */
public final class ModMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(BuiltInRegistries.MENU, BmfTown.MOD_ID);

    /**
     * 镇民面板。
     *
     * <p>工厂方法在<b>服务端和客户端都会调用</b>：
     * <ul>
     *   <li>服务端：{@code openMenu} 时直接用已存在的实体构造</li>
     *   <li>客户端：从 buffer 读 entityId，再去世界里找实体</li>
     * </ul>
     * 客户端找不到实体（比如刚好被卸载）时退回无实体版本，界面照常打开但
     * 只剩玩家背包可操作——比直接崩溃好得多。
     */
    public static final DeferredHolder<MenuType<?>, MenuType<ResidentMenu>> RESIDENT =
            MENUS.register("resident", () -> IMenuTypeExtension.create(
                    (containerId, playerInventory, extraData) -> {
                        Resident resident = null;
                        // 只有客户端需要读额外数据（服务端那次调用不会走这里）
                        if (extraData != null) {
                            int entityId = extraData.readVarInt();
                            var entity = playerInventory.player.level().getEntity(entityId);
                            if (entity instanceof Resident r) {
                                resident = r;
                            }
                        }
                        if (resident == null) {
                            return new ResidentMenu(containerId, playerInventory);
                        }
                        return new ResidentMenu(containerId, playerInventory, resident);
                    }));

    private ModMenus() {
    }

    public static void register(IEventBus modEventBus) {
        MENUS.register(modEventBus);
    }
}
