package com.bmf.bmfstown.menu;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;

/**
 * 镇民背包的格子——和普通 {@link Slot} 唯一的区别是会感知页状态。
 *
 * <h2>为什么存在</h2>
 * 镇民面板分两页：资料页有装备+背包，归属页是一份城镇列表。
 * 归属页时镇民背包格子必须<b>整个不存在</b>——不只是不画：
 * 原版对 {@code isActive() == false} 的槽位会跳过渲染、悬停高亮、
 * tooltip 和点击，"第一页被关闭"才成立。
 *
 * <p>之前只靠 Screen 的 {@code renderSlot} 覆写把物品藏起来，
 * 结果格子还能点、还能高亮、光标上的东西还能放进去——
 * 玩家对着看不见的格子搬东西，是最恶性的那种 bug。
 * 页状态见 {@link ResidentMenu#CLIENT_PAGE}。
 */
public class ResidentBagSlot extends Slot {

    public ResidentBagSlot(Container container, int index, int x, int y) {
        super(container, index, x, y);
    }

    @Override
    public boolean isActive() {
        return ResidentMenu.CLIENT_PAGE == ResidentMenu.PAGE_INFO && super.isActive();
    }
}
