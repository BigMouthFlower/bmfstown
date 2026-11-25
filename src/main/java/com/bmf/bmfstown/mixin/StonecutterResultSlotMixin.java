package com.bmf.bmfstown.mixin;

import com.bmf.bmfstown.config.Config;
import com.bmf.bmfstown.material.TownStock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 切石机自动补料（v0.17 石匠线，2026-11-25 用户拍板"第一版就上 mixin 自动补料"）。
 *
 * <h2>要解决什么</h2>
 * 玩家的石材/木材/碎沙堆在镇子的登记容器里。原版切石机的输入槽只认玩家背包，
 * 每切一次都要先从箱子掏 64 个带身上——这层摩擦会让建材闭环的手感变碎。
 * 本 mixin 让<b>镇内的切石机</b>在输入槽空掉时自动从镇库存取 1 个补进去。
 *
 * <h2>为什么注入在 TAIL 而不是 HEAD（复制漏洞）</h2>
 * 原版结果槽 {@code onTake} 的执行顺序：
 * <pre>
 *   ① 发成就 / 记录配方
 *   ② inputSlot.remove(1)      ← 扣掉 1 个原料
 *   ③ setupResultSlot()        ← 按剩余原料重算产物
 *   ④ 播音效
 * </pre>
 * 若在 HEAD（扣料之前）补 1 个进去，② 扣掉的会是<b>刚补进去的那个</b>，
 * 原来那格原料还在——连点就能凭空产出，典型的补料时序复制漏洞。
 * <b>TAIL 注入：原版已扣完、算完、播完音，我们才补</b>，此时槽子状态与
 * "玩家自己又放了一个"完全等价，没有复制面。
 *
 * <h2>连点为什么安全</h2>
 * <b>补料与扣料严格 1:1 配对</b>：每次补都对应一次已经发生的产出（产物已给玩家）。
 * 同一 tick 再发一次取货，原版扣的是我们刚补的那个、并再给一份产物——这是
 * 正当交易，不是刷物品。不需要额外 tick 锁。
 *
 * <h2>触发条件（宁可不动，不可错动）</h2>
 * <ol>
 *   <li>服务端（客户端菜单不补）；</li>
 *   <li>方块确实是切石机、且位于<b>某个城镇框内</b>（用户要求"位于城镇内部"）；</li>
 *   <li>输入槽<b>已空</b>——非空时绝不插手，玩家自己放的东西优先；</li>
 *   <li>镇库存里确实有料（取不到就安静放弃，不播报不报错）。</li>
 * </ol>
 *
 * <h3>为什么 targets 写内部类名</h3>
 * {@code onTake} 定义在结果槽的匿名子类上，不在 {@link StonecutterMenu} 本体。
 * 已用 javap 核对字节码：{@code StonecutterMenu$1} 是输入容器的匿名
 * SimpleContainer，{@code $2} 才是 {@code extends Slot} 的结果槽。
 * 所以 {@code method = "onTake"} 写在 StonecutterMenu 上永远匹配不到。
 *
 * <p>内部类持有 {@code this$0}（外层菜单）与 {@code val$access}
 * （ContainerLevelAccess，能拿到切石机坐标）——两个都 shadow 出来，
 * 不需要反射，也不需要任何全局状态。
 *
 * <p>匿名类名会随源码变动，故 {@code require = 0}：匹配不上时只是不补料，
 * 不至于整个 mod 起不来（这是"优雅降级"，也是本 mixin 唯一的失败模式）。
 */
@Mixin(targets = "net.minecraft.world.inventory.StonecutterMenu$2")
public abstract class StonecutterResultSlotMixin extends Slot {

    /** 外层菜单（编译器为内部类自动加的 final 字段）。 */
    @Shadow(aliases = {"this$0"})
    private StonecutterMenu this$0;

    /** 结果槽持有的容器访问器（原版用它播音效，我们用它拿坐标）。 */
    @Shadow(aliases = {"val$access"})
    private ContainerLevelAccess access;

    // mixin 类必须能编译成 Slot 子类；此构造永不执行
    private StonecutterResultSlotMixin() {
        super(null, 0, 0, 0);
    }

    @Inject(method = "onTake", at = @At("TAIL"), require = 1)
    private void bmfstown$refillFromTownStock(Player player, ItemStack taken, CallbackInfo ci) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        if (!Config.SALVAGE_STONECUTTER_AUTOFEED.get()) {
            return;
        }
        StonecutterMenu menu = this.this$0;
        if (menu == null) {
            return;
        }
        // 输入槽非空 = 玩家自己的料，绝不插手
        if (menu.slots.size() <= StonecutterMenu.INPUT_SLOT) {
            return;
        }
        Slot inputSlot = menu.slots.get(StonecutterMenu.INPUT_SLOT);
        if (inputSlot.hasItem()) {
            return;
        }
        // 拿切石机坐标
        BlockPos[] posRef = new BlockPos[1];
        this.access.execute((lvl, p) -> posRef[0] = p);
        BlockPos pos = posRef[0];
        if (pos == null || !level.getBlockState(pos).is(Blocks.STONECUTTER)) {
            return;
        }
        // 镇内才补（用户原话："如果他切石机位于城镇内部"）
        ItemStack drawn = TownStock.withdrawForStonecutter(level, pos);
        if (drawn.isEmpty()) {
            return;
        }
        inputSlot.set(drawn);
        inputSlot.setChanged();
        Container inContainer = inputSlot.container;
        menu.slotsChanged(inContainer);
        level.playSound(null, pos, SoundEvents.ITEM_PICKUP,
                SoundSource.BLOCKS, 0.35F, 1.2F);
    }
}