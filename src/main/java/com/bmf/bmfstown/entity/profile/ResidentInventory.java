package com.bmf.bmfstown.entity.profile;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 镇民的背包（27 格）。
 *
 * <h2>为什么不直接用 {@code Mob.getInventory()}</h2>
 * 原版 {@code Mob} 自带的 {@code getInventory()} 只有 <b>8 格</b>，
 * 而且是给"怪物捡东西"用的（{@code Mob#canPickUpLoot}）。
 * 我们需要 27 格给玩家存放食物、种子、替换装备，所以自己实现。
 *
 * <h2>为什么实现 {@link Container} 而不是继承 {@code SimpleContainer}</h2>
 * {@code SimpleContainer} 带了一堆我们不需要的东西（监听器、比较器信号），
 * 而且它的 NBT 方法与我们的存档流程对不上。
 * 直接实现 {@code Container} 接口，行为最可控，字段意义最清楚。
 *
 * <h2>装备槽不在这里</h2>
 * 主手、副手、头盔、胸甲、护腿、靴子这 6 个槽位用原版的
 * {@link net.minecraft.world.entity.EquipmentSlot}，不放进这个背包。
 * 好处是：装备放进去，攻击力/护甲值<b>自动生效</b>，不需要自己算。
 *
 * <h2>NBT 格式</h2>
 * 用原版 {@link ContainerHelper} 生成标准格式（{@code Items} 列表，
 * 每项含 {@code Slot} 与 {@code id}/{@code count}/{@code components}），
 * 与箱子、玩家背包完全一致——这样 {@code /item replace} 之类的命令也能操作它。
 */
public class ResidentInventory implements Container {

    /** 背包格数。27 = 玩家主背包（3 行 × 9），刚好排进 UI 面板。 */
    public static final int SIZE = 27;

    private final NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);

    /** 内容变化时的回调，让实体知道该同步了。可以为 null。 */
    private Runnable onChange;

    public void setOnChange(Runnable callback) {
        this.onChange = callback;
    }

    // ==========================================================
    //  Container 接口
    // ==========================================================

    @Override
    public int getContainerSize() {
        return SIZE;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : this.items) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot >= 0 && slot < SIZE ? this.items.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack result = ContainerHelper.removeItem(this.items, slot, amount);
        if (!result.isEmpty()) {
            this.setChanged();
        }
        return result;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(this.items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= SIZE) {
            return;
        }
        this.items.set(slot, stack);
        this.setChanged();
    }

    @Override
    public void setChanged() {
        if (this.onChange != null) {
            this.onChange.run();
        }
    }

    @Override
    public boolean stillValid(Player player) {
        // 没有"容器方块"的概念，永远有效。
        // 实际能否打开由实体的交互逻辑决定（距离判定在那里）。
        return true;
    }

    @Override
    public void clearContent() {
        this.items.clear();
        this.setChanged();
    }

    // ==========================================================
    //  便捷方法
    // ==========================================================

    public NonNullList<ItemStack> getItems() {
        return this.items;
    }

    /**
     * 加一个物品进去。先找同类物品的未满堆，再找空格。
     *
     * @return 没塞进去的剩余部分（全部塞进去了就是 {@link ItemStack#EMPTY}）
     */
    public ItemStack addItem(ItemStack stack) {
        ItemStack remaining = stack.copy();
        if (remaining.isEmpty()) {
            return ItemStack.EMPTY;
        }

        // 第一遍：堆叠到已有的同类物品上
        for (int i = 0; i < SIZE && !remaining.isEmpty(); i++) {
            ItemStack existing = this.items.get(i);
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, remaining)) {
                int space = existing.getMaxStackSize() - existing.getCount();
                if (space > 0) {
                    int move = Math.min(space, remaining.getCount());
                    existing.grow(move);
                    remaining.shrink(move);
                }
            }
        }

        // 第二遍：放进空格
        for (int i = 0; i < SIZE && !remaining.isEmpty(); i++) {
            if (this.items.get(i).isEmpty()) {
                this.items.set(i, remaining.copy());
                remaining.setCount(0);
            }
        }

        this.setChanged();
        return remaining;
    }

    /** 取出指定格的整堆（用于吃掉一个食物时扣减）。 */
    public ItemStack extractOne(int slot) {
        ItemStack stack = this.items.get(slot);
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack one = stack.copyWithCount(1);
        stack.shrink(1);
        if (stack.isEmpty()) {
            this.items.set(slot, ItemStack.EMPTY);
        }
        this.setChanged();
        return one;
    }

    // ==========================================================
    //  序列化
    // ==========================================================

    /**
     * 写入 NBT。
     *
     * <p>必须传 {@code registries}：1.21 的物品带<b>数据组件</b>（食物属性、
     * 附魔等），序列化组件时需要注册表来查 id。没有它就只能存物品 id，
     * 会丢掉所有组件信息（比如附魔剑变成普通剑）。
     */
    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        ContainerHelper.saveAllItems(tag, this.items, registries);
        return tag;
    }

    /** 从 NBT 读取。老存档没有这段数据时不会崩，背包保持空。 */
    public void load(CompoundTag tag, HolderLookup.Provider registries) {
        this.items.clear();
        ContainerHelper.loadAllItems(tag, this.items, registries);
    }
}
