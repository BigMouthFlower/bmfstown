package com.bmf.bmfstown.menu;

import com.bmf.bmfstown.entity.Resident;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 把实体的 6 个装备槽伪装成一个 {@link Container}。
 *
 * <h2>为什么需要这层包装</h2>
 * {@link net.minecraft.world.inventory.Slot} 只认 {@code Container} 接口，
 * 而装备在实体身上是用 {@code getItemBySlot(EquipmentSlot)} 存的。
 * 这个类就是适配器，让"格子"能读写"实体装备"。
 *
 * <h2>槽位编号约定</h2>
 * {@code 0..5} 依次对应 {@link ResidentMenu#EQUIP_ORDER}：
 * <pre>0=主手 1=副手 2=头 3=胸 4=腿 5=脚</pre>
 *
 * <h2>这个容器不做同步</h2>
 * 装备本身就是 {@code SynchedEntityData} 的一部分（原版 {@code LivingEntity}
 * 把 6 件装备也放进了同步数据），所以只要服务端改了，客户端自动能看到。
 * 不需要我们再写一遍同步逻辑。
 */
public class EquipmentContainer implements Container {

    private final Resident resident;

    public EquipmentContainer(Resident resident) {
        this.resident = resident;
    }

    @Override
    public int getContainerSize() {
        return ResidentMenu.EQUIP_ORDER.length;
    }

    @Override
    public boolean isEmpty() {
        for (EquipmentSlot type : ResidentMenu.EQUIP_ORDER) {
            if (!this.resident.getItemBySlot(type).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** 把 0..5 的索引翻译成真实的 {@link EquipmentSlot}。 */
    private EquipmentSlot typeOf(int index) {
        if (index < 0 || index >= ResidentMenu.EQUIP_ORDER.length) {
            return EquipmentSlot.MAINHAND;
        }
        return ResidentMenu.EQUIP_ORDER[index];
    }

    @Override
    public ItemStack getItem(int slot) {
        return this.resident.getItemBySlot(typeOf(slot));
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        EquipmentSlot type = typeOf(slot);
        ItemStack current = this.resident.getItemBySlot(type);
        if (current.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack taken = current.copyWithCount(Math.min(amount, current.getCount()));
        ItemStack remain = current.copy();
        remain.shrink(taken.getCount());
        this.resident.setItemSlot(type, remain.isEmpty() ? ItemStack.EMPTY : remain);
        this.setChanged();
        return taken;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        EquipmentSlot type = typeOf(slot);
        ItemStack current = this.resident.getItemBySlot(type);
        this.resident.setItemSlot(type, ItemStack.EMPTY);
        return current;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        this.resident.setItemSlot(typeOf(slot), stack);
        this.setChanged();
    }

    @Override
    public void setChanged() {
        // 装备变化后让实体的属性（护甲、攻击力）立刻重算。
        // 原版是在 tick 里做的，这里主动踢一脚能让 UI 上的数值即时刷新。
        this.resident.onEquipChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return this.resident.isAlive() && player.isCloseEnough(this.resident, 8.0D);
    }

    @Override
    public void clearContent() {
        for (EquipmentSlot type : ResidentMenu.EQUIP_ORDER) {
            this.resident.setItemSlot(type, ItemStack.EMPTY);
        }
        this.setChanged();
    }
}
