package com.bmf.bmfstown.leisure;

import com.bmf.bmfstown.config.Config;
import com.bmf.bmfstown.entity.KegAccess;
import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.entity.TavernOrders;
import com.bmf.bmfstown.town.Town;
import com.bmf.bmfstown.town.TownSaveData;
import com.bmf.bmfstown.town.TownZone;
import com.bmf.bmfstown.town.TownWandMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 酒馆消费·营业版（v0.16 决策 #65 重做）：镇民闲暇时"去酒馆坐坐"，
 * 客人不是自助——是**点单等酒**：
 *
 * <pre>
 * 走进酒吧落座 → 点单 → 等酒保送（超时自取酒窖兜底） → 落座 100~200t
 * → 喝掉（心情+3/饥饿+2/CONSUMABLE 药效上身） → 【还杯】就近放回酒吧容器
 * → 酒吧内<b>安静逗留</b>到休闲窗口结束（站着歇为主，慢慢挪步）
 * </pre>
 *
 * <h2>三条硬规矩</h2>
 * <ul>
 *   <li><b>每日一杯</b>：闸门记 {@code Status.lastTavernDay}（落档）；</li>
 *   <li><b>点了就喝完</b>（用户拍板）：拿到酒后本活动不可被休闲窗口结束
 *       掐断——喝完、还完杯才放人；睡觉/开饭仍最高优先，但打断 = 当场
 *       喝掉+还杯，绝不退酒、绝不占着杯走人；</li>
 *   <li><b>还杯铁律</b>（用户拍板）：喝完的容器（奶桶→空桶、药水/蜜瓶→
 *       空瓶、酒→其余杯）**必须**回到酒吧容器（就近原则）；容器全满时
 *       揣身上，绝不吞杯。</li>
 * </ul>
 *
 * <h2>逗留=有界活动段+安静待客（2026-11-15 终版口径）</h2>
 * 逗留段<b>有长度上限</b>（lingerMin/MaxTicks，600~1200t 默认）——到点活动
 * 结束走人，之后 LeisureGoal 重掷下一件事（每日一杯闸门挡住再入酒馆）。
 * 绝不能"等窗口收尾"：无业/周末的闲暇窗口全天开着，那样会在酒吧站一整天
 * （用户 2026-11-15 确认："肯定不能让他一天一直站酒吧里"）。真正的大坑是
 * 节奏：原实现"一到达就冲刺下一个随机点"=区内永动机狂奔，看着像想突围
 * 回主城；配末影步每 2~3t 一传。现在走到落脚点站着歇 100~200t 再挪——
 * 末影步只是症状，不动它本体，行为安静下来瞬移自然一传一歇。歇着的壳
 * 留给下一步的聊天/看节目。
 *
 * <p>饮品白名单（用户定稿）：原版三样（牛奶桶/蜂蜜瓶/反胃药水）+
 * {@code #bmfstown:tavern_drinks} tag（内引 {@code #brewinandchewin:fermented_drinks}，
 * required:false——BAC 不在时空 tag 自动回落）。药效走原版 CONSUMABLE
 * 组件通用应用（药水/BAC 酒全覆盖，零 BAC 类）。
 */
public class TavernVisitActivity implements LeisureActivity {

    private enum Phase { GO_SEAT, ORDER_WAIT, GO_STOCK, SEATED, RETURN_CUP, LINGER }

    /** 自有酒单 tag（内引 BAC 酒 tag，required:false——见 data/bmfstown/tags/item）。 */
    public static final TagKey<Item> TAVERN_DRINKS =
            TagKey.create(Registries.ITEM,
                    ResourceLocation.fromNamespaceAndPath("bmfstown", "tavern_drinks"));

    private Phase phase = Phase.GO_SEAT;
    private BlockPos seatTarget;
    private BlockPos stockPos;
    private ItemStack drink = ItemStack.EMPTY;
    private ItemStack cup = ItemStack.EMPTY;
    private int waitTicksLeft;
    private int sitTicksLeft;
    /** 走向座位的计时（GO_SEAT_TIMEOUT 到点就地点单兜底）。 */
    private int goSeatTicks;
    /** 走向座位的上限（300t=15 秒，走不到就地点单）。 */
    private static final int GO_SEAT_TIMEOUT = 300;
    /** 落脚点之间的原地歇息（安静待客：站着歇，不永动机狂奔）。 */
    private int dwellTicksLeft;
    /** 逗留段剩余（有界活动段：到点结束走人，不会站一整天——与图书馆同型）。 */
    private int lingerTicksLeft;

    @Override
    public String toString() {
        return "tavern_visit";
    }

    // ==========================================================
    //  饮品白名单
    // ==========================================================

    /** 这瓶是不是酒馆饮品（原版三样 + tavern_drinks tag 成员；药水只认反胃——#64 定稿）。 */
    public static boolean isDrink(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.getItem() instanceof net.minecraft.world.item.PotionItem) {
            return stack.getOrDefault(
                            net.minecraft.core.component.DataComponents.POTION_CONTENTS,
                            net.minecraft.world.item.alchemy.PotionContents.EMPTY)
                    .is(com.bmf.bmfstown.alchemy.ModPotions.NAUSEA);
        }
        return stack.is(TAVERN_DRINKS);
    }

    // ------------------------------------------------------------
    //  区/窖扫描（与酒保同一口径）
    // ------------------------------------------------------------

    /** 该镇民所属城镇的全部酒馆区。 */
    private static List<TownZone> tavernZones(ServerLevel level, Resident resident) {
        UUID townId = resident.getIdentity().townId();
        if (townId == null) {
            return List.of();
        }
        Town town = TownSaveData.get(level).findTown(townId.toString());
        return town == null ? List.of() : town.zones(TownWandMode.TAVERN);
    }

    private static List<BlockPos> cellarContainers(ServerLevel level, Resident resident) {
        List<BlockPos> out = new ArrayList<>();
        for (TownZone zone : tavernZones(level, resident)) {
            for (BlockPos pos : TownZone.iterate(zone)) {
                if (level.getBlockEntity(pos) instanceof Container) {
                    out.add(pos.immutable());
                }
            }
        }
        return out;
    }

    /** 区内最近的、装着至少一瓶饮品的酒窖容器；无则 null。 */
    private static BlockPos findDrinkStock(ServerLevel level, Resident resident) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : cellarContainers(level, resident)) {
            Container c = containerAt(level, pos);
            if (c == null || findDrinkSlot(c) < 0) {
                continue;
            }
            double d = resident.blockPosition().distSqr(pos);
            if (d < bestDist) {
                bestDist = d;
                best = pos;
            }
        }
        return best;
    }

    private static int findDrinkSlot(Container stock) {
        for (int i = 0; i < stock.getContainerSize(); i++) {
            if (isDrink(stock.getItem(i))) {
                return i;
            }
        }
        return -1;
    }

    private static Container containerAt(ServerLevel level, BlockPos pos) {
        try {
            BlockEntity be = level.getBlockEntity(pos);
            return be instanceof Container c ? c : null;
        } catch (RuntimeException e) {
            return null; // 防御（#65）：BAC keg 等 BE 构造炸的环境按无容器处理
        }
    }

    /** 区内有没有能接出一杯的酒桶（酒保酿好了没装瓶也行）。 */
    private static boolean anyDrawableKeg(ServerLevel level, Resident resident) {
        for (TownZone zone : tavernZones(level, resident)) {
            for (BlockPos pos : TownZone.iterate(zone)) {
                if (KegAccess.drawable(level, pos)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------
    //  生命周期
    // ------------------------------------------------------------

    @Override
    public boolean canStart(Resident resident, ServerLevel level) {
        long day = level.getDayTime() / 24000L;
        if (resident.getStatus().lastTavernDay() == day) {
            return false; // 每日一杯：硬闸门
        }
        if (!resident.getMainHandItem().isEmpty()) {
            return false; // 主手被占（#49 不抢手）
        }
        if (tavernZones(level, resident).isEmpty()) {
            return false;
        }
        // 有酒可点：窖里有瓶 或 桶里能接
        return findDrinkStock(level, resident) != null || anyDrawableKeg(level, resident);
    }

    @Override
    public boolean start(Resident resident, ServerLevel level) {
        List<TownZone> zones = tavernZones(level, resident);
        if (zones.isEmpty()) {
            return false;
        }
        // 就近区：离得最近的酒馆区
        TownZone nearest = zones.get(0);
        double bestDist = Double.MAX_VALUE;
        for (TownZone zone : zones) {
            double d = resident.blockPosition().distToCenterSqr(
                    Vec3.atCenterOf(zone.min()));
            if (d < bestDist) {
                bestDist = d;
                nearest = zone;
            }
        }
        this.seatTarget = new BlockPos(
                (nearest.min().getX() + nearest.max().getX()) / 2,
                nearest.min().getY(),
                (nearest.min().getZ() + nearest.max().getZ()) / 2);
        this.goSeatTicks = 0;
        this.phase = Phase.GO_SEAT;
        navigateTo(resident, this.seatTarget);
        return true;
    }

    @Override
    public boolean tick(Resident resident, ServerLevel level) {
        return switch (this.phase) {
            case GO_SEAT -> tickGoSeat(resident, level);
            case ORDER_WAIT -> tickOrderWait(resident, level);
            case GO_STOCK -> tickGoStock(resident, level);
            case SEATED -> tickSeated(resident, level);
            case RETURN_CUP -> tickReturnCup(resident, level);
            case LINGER -> tickLinger(resident, level);
        };
    }

    /** 走进酒吧 → 点单。<b>进区即点单</b>（#66 用户质疑后重做）：原设计
     *  "必须走到区中心 3 格内才算到位"有走不到的死点（中心被方块挡住/
     *  被占位就永远点不了单）——中心只是行进方向，人一踏进酒馆区边界
     *  就停下挂单。超时兜底只覆盖"连区都没进去"（被卡在门外），本次不喝。 */
    private boolean tickGoSeat(Resident resident, ServerLevel level) {
        if (this.seatTarget == null) {
            return false;
        }
        if (++this.goSeatTicks > GO_SEAT_TIMEOUT) {
            return false; // 300t 还没踏进酒馆区：被卡死了，改天再来
        }
        for (TownZone zone : tavernZones(level, resident)) {
            if (zoneContainsXZ(zone, resident.blockPosition())) {
                resident.getNavigation().stop();
                if (!resident.getMainHandItem().isEmpty()) {
                    return false; // 手被占：本次不喝
                }
                placeOrder(resident, zone);
                return true;
            }
        }
        if (resident.getNavigation().isDone()) {
            navigateTo(resident, this.seatTarget);
        }
        return true;
    }

    /** 落单公共段（落座/兜底两路共用）。 */
    private void placeOrder(Resident resident, TownZone zone) {
        TavernOrders.place(resident, zone.min());
        this.waitTicksLeft = Config.TAVERN_ORDER_WAIT.get();
        this.phase = Phase.ORDER_WAIT;
    }

    private static boolean zoneContainsXZ(TownZone zone, BlockPos pos) {
        return pos.getX() >= zone.min().getX() && pos.getX() <= zone.max().getX()
                && pos.getZ() >= zone.min().getZ() && pos.getZ() <= zone.max().getZ();
    }

    /** 等上酒：酒到手 → 开喝；超时 → 自取酒窖兜底。 */
    private boolean tickOrderWait(Resident resident, ServerLevel level) {
        ItemStack hand = resident.getMainHandItem();
        if (!hand.isEmpty()) {
            if (!isDrink(hand)) {
                TavernOrders.cancel(resident);
                return false; // 手里多了不是酒的东西：怪事，本次作废
            }
            this.drink = hand;
            TavernOrders.cancel(resident);
            beginSeated(resident);
            return true;
        }
        if (--this.waitTicksLeft > 0) {
            return true; // 站着等酒保
        }
        TavernOrders.cancel(resident);
        // 超时自取：酒窖兜底（老自助路径）
        BlockPos stock = findDrinkStock(level, resident);
        if (stock == null) {
            return false; // 酒保没来窖里也没货：改天再来
        }
        this.stockPos = stock;
        this.phase = Phase.GO_STOCK;
        navigateTo(resident, this.stockPos);
        return true;
    }

    /** 自取兜底：走到酒窖 → 真实取出一瓶。 */
    private boolean tickGoStock(Resident resident, ServerLevel level) {
        Container stock = this.stockPos == null ? null : containerAt(level, this.stockPos);
        if (stock == null) {
            return false;
        }
        if (!resident.blockPosition().closerThan(this.stockPos, 2.5D)) {
            if (resident.getNavigation().isDone()) {
                navigateTo(resident, this.stockPos);
            }
            return true;
        }
        resident.getNavigation().stop();
        if (!resident.getMainHandItem().isEmpty()) {
            if (isDrink(resident.getMainHandItem())) {
                // 手里已经端着酒（酒保恰好在这时塞到手的，#66 堵滞留杯）：
                // 当场认账，当作已上酒
                this.drink = resident.getMainHandItem().copy();
                beginSeated(resident);
                return true;
            }
            return false;
        }
        int slot = findDrinkSlot(stock);
        if (slot < 0) {
            return false; // 酒被人喝空了
        }
        this.drink = stock.getItem(slot).copy();
        this.drink.setCount(1);
        stock.getItem(slot).shrink(1);
        stock.setChanged();
        resident.setItemInHand(InteractionHand.MAIN_HAND, this.drink);
        beginSeated(resident);
        return true;
    }

    /** 落座：坐一小会儿（100~200t，歇口气）再开喝。 */
    private void beginSeated(Resident resident) {
        int sMin = Config.TAVERN_SIT_MIN.get();
        int sMax = Math.max(sMin, Config.TAVERN_SIT_MAX.get());
        this.sitTicksLeft = sMin + resident.getRandom().nextInt(Math.max(1, sMax - sMin + 1));
        this.phase = Phase.SEATED;
    }

    /** 原地小坐 → 喝掉（结算 + 还杯铁律）。 */
    private boolean tickSeated(Resident resident, ServerLevel level) {
        resident.getNavigation().stop();
        if (--this.sitTicksLeft > 0) {
            return true;
        }
        settleDrink(resident, level);
        // 还杯：remainder（奶桶→空桶/药水蜜瓶→空瓶/酒→杯）必须回到酒吧容器
        this.cup = cupOf(this.drink);
        this.drink = ItemStack.EMPTY;
        if (this.cup.isEmpty()) {
            toLinger(resident, level);
        } else {
            this.phase = Phase.RETURN_CUP;
        }
        return true;
    }

    /** 喝下结算：心情/饥饿/每日账/CONSUMABLE 药效（药水+BAC 酒全覆盖）。
     *  主手清杯前先确认手上还是这杯——手被手装导演换装过（打断时战斗抢手）
     *  就改从背包喝掉那杯，绝不把导演的武器抹掉。 */
    private void settleDrink(Resident resident, ServerLevel level) {
        ItemStack hand = resident.getMainHandItem();
        if (!hand.isEmpty() && ItemStack.isSameItemSameComponents(hand, this.drink)) {
            resident.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        } else {
            // 酒被导演收进了背包（副本）：从背包里消耗那杯；找不到就认了，不崩
            var inv = resident.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                if (ItemStack.isSameItemSameComponents(inv.getItem(i), this.drink)) {
                    inv.getItem(i).shrink(1);
                    break;
                }
            }
        }
        resident.getStatus().drankAtTavern(level,
                Config.TAVERN_MOOD_BONUS.get(), Config.TAVERN_DRINK_FOOD.get());
        KegAccess.applyConsumable(level, resident, this.drink);
        level.playSound(null, resident.blockPosition(), SoundEvents.GENERIC_DRINK,
                SoundSource.NEUTRAL, 1.0F, 1.0F);
        resident.getStatus().setLastTavernDay(level.getDayTime() / 24000L);
    }

    /** 还杯：走到最近的酒吧容器 → 杯子放回（就近原则）。 */
    private boolean tickReturnCup(Resident resident, ServerLevel level) {
        BlockPos target = nearestEmptyContainer(level, resident);
        if (target == null) {
            // 区里连个有空位的容器都没有：揣着杯逗留（绝不吞杯）
            toLinger(resident, level);
            return true;
        }
        if (!resident.blockPosition().closerThan(target, 2.5D)) {
            if (resident.getNavigation().isDone()) {
                navigateTo(resident, target);
            }
            return true;
        }
        Container c = containerAt(level, target);
        resident.getNavigation().stop();
        if (c != null && KegAccess.insertInto(c, this.cup)) {
            this.cup = ItemStack.EMPTY;
        } // 插不进（被抢满）：揣着走
        toLinger(resident, level);
        return true;
    }

    /** 进入逗留：<b>有界的活动段</b>（600~1200t，lingerMin/MaxTicks）。到点
     *  活动结束交还控制权——无业/周末居民的闲暇窗口全天开着，若逗留等窗口
     *  收尾就会在酒吧站一整天（与图书馆看书的"看完就散"同型，2026-11-15
     *  用户确认口径："肯定不能让他一天一直站酒吧里"）。段结束后自然重掷：
     *  每日一杯闸门挡住再入酒馆，接下来的多半是游走/图书馆。 */
    private void toLinger(Resident resident, ServerLevel level) {
        int lMin = Math.max(1, Config.TAVERN_LINGER_MIN.get());
        int lMax = Math.max(lMin, Config.TAVERN_LINGER_MAX.get());
        this.lingerTicksLeft = lMin + resident.getRandom().nextInt(lMax - lMin + 1);
        this.dwellTicksLeft = 0;
        this.phase = Phase.LINGER;
    }

    /**
     * 逗留：在酒吧里"待着"——走到一个落脚点就<b>站着歇一阵</b>（100~200t），
     * 再慢慢挪下一个点；段总长到点即结束。绝不能"一到达就冲刺下一个随机点"：
     * 那是在区内永动机狂奔（2026-11-15 实测：看着像想突围跑回主城、实际被
     * 活动钉死），配末影步就是每 2~3t 一传的瞬移连发——末影步只是症状，
     * 安静待客才是本相。站着歇的这段时间就是将来"跟别人聊天/看节目"
     * 要填充的壳（#65 下一步）。
     */
    private boolean tickLinger(Resident resident, ServerLevel level) {
        if (--this.lingerTicksLeft <= 0) {
            return false; // 逗留到点：活动段结束，重掷下一件事（游走/图书馆…）
        }
        if (resident.getNavigation().isDone()) {
            if (this.dwellTicksLeft > 0) {
                this.dwellTicksLeft--;
                return true; // 原地站着歇（不走不传，未来在这接聊天/看节目）
            }
            List<TownZone> zones = tavernZones(level, resident);
            if (zones.isEmpty()) {
                return false;
            }
            TownZone zone = zones.get(resident.getRandom().nextInt(zones.size()));
            BlockPos p = new BlockPos(
                    zone.min().getX() + resident.getRandom().nextInt(
                            Math.max(1, zone.max().getX() - zone.min().getX() + 1)),
                    zone.min().getY(),
                    zone.min().getZ() + resident.getRandom().nextInt(
                            Math.max(1, zone.max().getZ() - zone.min().getZ() + 1)));
            this.dwellTicksLeft = 100 + resident.getRandom().nextInt(100);
            navigateTo(resident, p);
        }
        return true;
    }

    /** 最近的、有空位的酒吧容器。 */
    private BlockPos nearestEmptyContainer(ServerLevel level, Resident resident) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : cellarContainers(level, resident)) {
            Container c = containerAt(level, pos);
            if (c == null) {
                continue;
            }
            boolean hasSpace = false;
            for (int i = 0; i < c.getContainerSize(); i++) {
                if (c.getItem(i).isEmpty()) {
                    hasSpace = true;
                    break;
                }
            }
            if (!hasSpace) {
                continue;
            }
            double d = resident.blockPosition().distSqr(pos);
            if (d < bestDist) {
                bestDist = d;
                best = pos;
            }
        }
        return best;
    }

    /** 还杯来源：药水→玻璃瓶（原版把瓶余料硬编码在 finishUsingItem，字段查不到）
     *  → FoodProperties.usingConvertsTo（奶桶）→ 合成余料兜底。 */
    private static ItemStack cupOf(ItemStack drink) {
        if (drink.getItem() instanceof net.minecraft.world.item.PotionItem) {
            return new ItemStack(Items.GLASS_BOTTLE);
        }
        net.minecraft.world.food.FoodProperties food = drink.get(net.minecraft.core.component.DataComponents.FOOD);
        if (food != null && food.usingConvertsTo().isPresent()) {
            return food.usingConvertsTo().get().copy();
        }
        ItemStack rem = drink.getCraftingRemainingItem();
        return rem == null || rem.isEmpty() ? ItemStack.EMPTY : rem;
    }

    @Override
    public void abort(Resident resident, ServerLevel level) {
        TavernOrders.cancel(resident);
        // 兜底（#66 堵滞留杯）：手里已经端着酒（酒保刚塞到手/自取刚拿起的
        // 瞬间被掐）——"点了就喝完"不分阶段：当场喝+还杯，绝不揣着杯走
        if (this.drink.isEmpty() && isDrink(resident.getMainHandItem())) {
            this.drink = resident.getMainHandItem().copy();
        }
        if (!this.drink.isEmpty()) {
            // 睡觉/开饭/窗口掐断统一路径：当场喝掉（用户拍板"点了就喝完"）+ 还杯
            settleDrink(resident, level);
            this.cup = cupOf(this.drink);
            this.drink = ItemStack.EMPTY;
        }
        if (!this.cup.isEmpty()) {
            returnCupInstantly(resident, level); // 就近直接插；全满揣背包，绝不吞
        } else {
            resident.getNavigation().stop();
        }
        this.drink = ItemStack.EMPTY;
        this.cup = ItemStack.EMPTY;
        this.phase = Phase.GO_SEAT;
    }

    /** 不走路的还杯：就近容器直接插（打断场景的折衷）；全满揣背包，绝不吞。 */
    private void returnCupInstantly(Resident resident, ServerLevel level) {
        if (this.cup.isEmpty()) {
            return;
        }
        for (BlockPos pos : cellarContainers(level, resident)) {
            Container c = containerAt(level, pos);
            if (c != null && KegAccess.insertInto(c, this.cup)) {
                this.cup = ItemStack.EMPTY;
                return;
            }
        }
        resident.getInventory().addItem(this.cup);
    }

    private void navigateTo(Resident resident, BlockPos pos) {
        resident.getNavigation().moveTo(
                pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 0.9D);
    }
}
