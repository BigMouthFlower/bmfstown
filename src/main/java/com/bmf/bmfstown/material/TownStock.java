package com.bmf.bmfstown.material;

import com.bmf.bmfstown.entity.JobKit;
import com.bmf.bmfstown.town.Town;
import com.bmf.bmfstown.town.TownSaveData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 镇级建材库存的取用口（v0.17 石匠线）。
 *
 * <h2>库存就是"登记容器"本身，没有第二本账</h2>
 * 石材/木材/碎沙是<b>真实物品</b>（用户 2026-11-25 拍板"真实物品存容器"），
 * 就躺在 {@code Town.WorkshopRegister.containers} 这些登记容器里。
 * 本类<b>不维护任何数量缓存</b>——每次用都是现扫现取。
 * 理由：账本与容器双写必然对不上（玩家手动往箱子里塞一摞、或用漏斗灌），
 * 而"只认容器"永远自洽，也不会出现"箱子被拆了但账上还有料"的悬空。
 *
 * <h2>取用顺序（玩家可预期）</h2>
 * <ol>
 *   <li><b>离切石机最近的容器优先</b>——玩家不用记料在哪个箱子里；</li>
 *   <li>同一容器的<b>格子按索引顺序</b>——确定性，不随机抽（便于玩家理解"料去哪了"）；</li>
 *   <li><b>一次只取 1 个</b>——切石机一次消耗 1 个原料，取多没意义。</li>
 * </ol>
 *
 * <h2>只取三种通货</h2>
 * 石匠的产物才进切石机的自动补料。普通建材（石头、木板）<b>不补</b>：
 * 那条原版路径必须保留（玩家自己掏料切），而且全补会让"石匠拆→切石机切"
 * 的边界糊掉（拆出来的台阶又拿去切，循环语义不清）。
 */
public final class TownStock {

    private TownStock() {
    }

    /**
     * 为切石机取 1 个通货。
     *
     * @return 取到的物品堆（1 个），取不到返回 {@link ItemStack#EMPTY}
     */
    public static ItemStack withdrawForStonecutter(ServerLevel level, BlockPos cutterPos) {
        Town town = townContaining(level, cutterPos);
        if (town == null) {
            return ItemStack.EMPTY; // 不在城镇内：不补（用户要求"位于城镇内部"）
        }
        List<BlockPos> pool = JobKit.validContainers(level, town.workshop());
        if (pool.isEmpty()) {
            return ItemStack.EMPTY;
        }
        // 最近的容器优先
        List<BlockPos> sorted = new ArrayList<>(pool);
        sorted.sort(Comparator.comparingDouble(p -> p.distSqr(cutterPos)));
        for (BlockPos pos : sorted) {
            Container c = JobKit.containerAt(level, pos);
            if (c == null) {
                continue;
            }
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack slot = c.getItem(i);
                if (slot.isEmpty() || !isCurrency(slot.getItem())) {
                    continue;
                }
                ItemStack one = slot.copyWithCount(1);
                slot.shrink(1);
                if (slot.isEmpty()) {
                    c.setItem(i, ItemStack.EMPTY);
                }
                c.setChanged();
                return one;
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * 镇里三种通货的总数（面板/调试用）。
     * 注意这是<b>现扫</b>，不缓存——调用方别放在每 tick 的路径上。
     */
    public static int totalOf(ServerLevel level, Town town, SalvageRecipes.Line line) {
        int n = 0;
        for (BlockPos pos : JobKit.validContainers(level, town.workshop())) {
            Container c = JobKit.containerAt(level, pos);
            if (c == null) {
                continue;
            }
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack slot = c.getItem(i);
                if (!slot.isEmpty() && slot.is(line.item())) {
                    n += slot.getCount();
                }
            }
        }
        return n;
    }

    /** 是否是三种通货之一。 */
    public static boolean isCurrency(Item item) {
        for (SalvageRecipes.Line line : SalvageRecipes.Line.values()) {
            if (item == line.item()) {
                return true;
            }
        }
        return false;
    }

    /** 该坐标落在哪个城镇的框内（三轴严格，与领地保护同口径）。 */
    @Nullable
    private static Town townContaining(ServerLevel level, BlockPos pos) {
        // 维度也要匹配——跨维度同坐标不能误判为同镇
        String dim = level.dimension().location().toString();
        for (Town town : TownSaveData.get(level).allTowns()) {
            if (!dim.equals(town.dimension())) {
                continue;
            }
            if (town.contains(pos)) {
                return town;
            }
        }
        return null;
    }
}