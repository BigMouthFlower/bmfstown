package com.bmf.bmfstown.menu;

import com.bmf.bmfstown.entity.Resident;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.jetbrains.annotations.Nullable;

/**
 * 打开镇民面板的"菜单提供者"。
 *
 * <h2>为什么需要这一层</h2>
 * 服务端调 {@code player.openMenu(provider, ...)} 时需要三样东西：
 * 界面的标题、菜单工厂、以及"这个界面还能不能继续用"的判定。
 * {@link MenuProvider} 就是这三样的打包，比直接传一堆 lambda 清楚。
 *
 * <h2>标题是动态的</h2>
 * {@code getDisplayName} 返回 "<名字> 的资料"，所以界面顶上看得到是哪个镇民。
 * 这个字符串在两边都会取，所以只依赖名字（名字本身是同步数据），
 * 不依赖服务端独有的信息。
 */
public class ResidentMenuProvider implements MenuProvider {

    private final Resident resident;

    public ResidentMenuProvider(Resident resident) {
        this.resident = resident;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("gui.bmfstown.resident.title",
                this.resident.getName().getString());
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new ResidentMenu(containerId, playerInventory, this.resident);
    }
}
