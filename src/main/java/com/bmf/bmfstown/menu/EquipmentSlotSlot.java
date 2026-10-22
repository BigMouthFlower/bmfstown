package com.bmf.bmfstown.menu;

import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 一个"装备槽"：把面板上的格子接到镇民实体的 {@link EquipmentSlot} 上。
 *
 * <h2>为什么需要自定义 Slot</h2>
 * 原版 {@code Slot} 只会读写一个 {@link Container}。但装备不该存在容器里——
 * 它必须存在实体的 {@code EquipmentSlot} 上，这样：
 * <ul>
 *   <li>护甲值、攻击力会<b>自动生效</b>（原版每 tick 从装备算属性）</li>
 *   <li>渲染时护甲会自动出现在模型上</li>
 *   <li>死亡掉落、附魔、耐久损耗全部沿用原版逻辑</li>
 * </ul>
 * 所以我们写一个 {@link Container} 代理（{@link EquipmentContainer}），
 * 把 {@code getItem/setItem} 转接到 {@code resident.getItemBySlot(...)}，
 * 再用本类把它包成可以点的格子。
 *
 * <h2>过滤规则（{@link #mayPlace}）</h2>
 * <ol>
 *   <li><b>手部槽（主手/副手）</b>：什么都能放。卫士可能拿火把、拿面包、拿块石头，
 *       不该限制。判断方式是 {@code !equipmentSlot.isArmor()}
 *       ——1.21.1 的 {@code EquipmentSlot} <b>没有</b> {@code isHand()} 这个方法。</li>
 *   <li><b>护甲槽（头/胸/腿/脚）</b>：只收"认领这个槽"的物品。判断方式是
 *       {@code Equipable.get(stack).getEquipmentSlot() == 本槽}。
 *       这样自动兼容所有实现了 {@code Equipable} 的东西，不用维护白名单：
 *       <ul>
 *         <li>{@code ArmorItem}（各类头盔 / 胸甲 / 护腿 / 靴子）</li>
 *         <li>{@code ElytraItem}（鞘翅 → 胸）</li>
 *         <li>{@code SkullBlock} / {@code PlayerHeadBlock}（头颅 → 头）</li>
 *         <li>{@code EquipableCarvedPumpkinBlock}（南瓜头 → 头）</li>
 *         <li>{@code WoolCarpetBlock}（地毯 → 马身上，对镇民无效但不会误判）</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p><b>踩过的坑</b>：这里最初写的是 {@code equipmentSlot.isHand()} 和
 * {@code LivingEntity.getEquipmentSlotForItem(stack)}，两个都不成立——
 * 前者在 1.21.1 不存在；后者是<b>实例方法</b>不是静态方法，而且它内部做的
 * 正是"查 {@code Equipable.getEquipmentSlot()}"，绕一圈还不如直接查。
 *
 * <h2>关于 {@code mayPickup}</h2>
 * 这里<b>没有</b>覆盖 {@code Slot.mayPickup}。{@code Slot} 的默认实现就是
 * {@code return true}，看着像"什么都没做"，但改成
 * {@code return !getItem().isEmpty()} 会出问题：服务端处理点击时会先问
 * {@code mayPickup}，空格返回 false 会让"把东西放进空格"的操作被静默忽略。
 */
public class EquipmentSlotSlot extends Slot {

    private final EquipmentSlot equipmentSlot;

    public EquipmentSlotSlot(@Nullable Container container, int index, int x, int y,
                             EquipmentSlot equipmentSlot) {
        // container 在客户端可能是 null（见 ResidentMenu 的说明），给个空壳避免 NPE
        super(container != null ? container : FALLBACK, index, x, y);
        this.equipmentSlot = equipmentSlot;
    }

    /** 这个格子绑定的装备位。Screen 画空槽提示文字时要用。 */
    public EquipmentSlot getEquipmentSlotType() {
        return this.equipmentSlot;
    }

    /**
     * 这个格子收不收这件物品。
     *
     * <p>注意：这个判断<b>两端都会跑</b>。客户端用它决定"能不能放下"，
     * 服务端用它做最终校验。两边必须给出一致的结果，否则会出现
     * "客户端放上去了、服务端又退回"的抽搐现象。
     */
    @Override
    public boolean mayPlace(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        // ---- 手部槽：不限 ----
        // EquipmentSlot 一共 7 个常量，isArmor() 为 true 的只有 HEAD/CHEST/LEGS/FEET，
        // 剩下的 MAINHAND/OFFHAND/BODY 全归"非护甲"，正好都该放行。
        if (!this.equipmentSlot.isArmor()) {
            return true;
        }
        // ---- 护甲槽：谁认领这个槽就归谁 ----
        // Equipable.get(ItemStack) 是静态工具方法，等价于
        // `stack.getItem() instanceof Equipable eq ? eq : null`，
        // 用它比自己写 instanceof 更省事，也自动覆盖所有原版 + 模组的可穿戴物。
        Equipable equipable = Equipable.get(stack);
        return equipable != null && equipable.getEquipmentSlot() == this.equipmentSlot;
    }

    /** 装备槽不允许堆叠，永远只放 1 个。 */
    @Override
    public int getMaxStackSize() {
        return 1;
    }

    /**
     * 归属页时装备槽整体失活。
     *
     * <p>原版渲染/悬停/点击全都会先问 {@code isActive()}——
     * 返回 false 后物品、高亮框、tooltip、拿放操作全部跳过，
     * 是"页面上这个格子不存在了"，不是"画的时候藏起来"。
     * 页状态见 {@link ResidentMenu#CLIENT_PAGE}。
     */
    @Override
    public boolean isActive() {
        return ResidentMenu.CLIENT_PAGE == ResidentMenu.PAGE_INFO && super.isActive();
    }

    /** 客户端占位容器。服务端会传入真正的 {@link EquipmentContainer}。 */
    private static final Container FALLBACK = new Container() {
        @Override
        public int getContainerSize() {
            return 6;
        }

        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public ItemStack getItem(int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
        }

        @Override
        public void setChanged() {
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public void clearContent() {
        }
    };
}
