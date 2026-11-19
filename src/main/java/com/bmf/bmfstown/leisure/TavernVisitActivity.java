package com.bmf.bmfstown.leisure;

import com.bmf.bmfstown.config.Config;
import com.bmf.bmfstown.entity.KegAccess;
import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.entity.SeatBlocks;
import com.bmf.bmfstown.entity.SeatEntity;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 酒馆消费·营业版（#67 顾客体验重做：顾客是主动的）：
 *
 * <pre>
 * 走进酒吧 → <b>找椅子坐下</b>（楼梯/下半砖，隐形骑乘真坐）
 *   → <b>坐下立即点单 + 打铃叫酒保</b>（顾客主动通知，不靠酒保轮询）
 *   → 坐着等酒（酒保送到主手；超时自取酒窖兜底）
 *   → <b>到手即喝</b>（不搞落座延迟——#67 用户拍板）
 *   → 【还杯】起身走近容器放回（座位留着）
 *   → <b>回座位坐下</b>，安静逗留到段尾 → 起身走人
 * </pre>
 *
 * <h2>三条硬规矩（不变）</h2>
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
 * <h2>#67 的四条拍板</h2>
 * <ul>
 *   <li><b>找椅子坐下</b>：区内有楼梯/下半砖（{@link SeatBlocks}）就真坐
 *       （{@link SeatEntity} 隐形骑乘）；没座位/座位走不到 → 站着点单兜底
 *       （老口径，不因为缺椅子就不喝酒）。</li>
 *   <li><b>坐下立即点单</b>：删"落座歇口气"延迟；坐定即挂单。</li>
 *   <li><b>点单打铃</b>：{@link TavernOrders#place} 主动唤醒 48 格内酒保
 *       （清扫描冷却当 tick 受理）——顾客叫服务员，不是服务员每 5 秒扫店。</li>
 *   <li><b>喝什么随机</b>：窖里有几种饮品就随机抽一种——绝不固定第一格
 *       （旧实现永远取第一个饮品格，蜂蜜囤前排全员喝蜂蜜）。</li>
 * </ul>
 *
 * <h2>逗留=坐在座位上到段尾</h2>
 * 有座位时逗留=坐在原座位上安静待到段尾（聊天/看节目的壳挂在坐姿上）；
 * 没座位才走老的"走一步歇一阵"游走。段长仍有界（600~1200t），到点活动
 * 结束走人重掷（每日一杯闸门挡住再入酒馆）。
 *
 * <p>饮品白名单（用户定稿）：原版三样（牛奶桶/蜂蜜瓶/反胃药水）+
 * {@code #bmfstown:tavern_drinks} tag（内引 {@code #brewinandchewin:fermented_drinks}，
 * required:false——BAC 不在时空 tag 自动回落；lang 键给 JEI 标签视图提供
 * 友好显示名"酒馆饮品"——2026-11-17 用户权衡后拍板保留：BAC 自己都往
 * origins 命名空间塞标记，自有 tag 不算不体面，datapack 可改价值更实）。
 * 药效走原版 CONSUMABLE 组件通用应用（药水/BAC 酒全覆盖，零 BAC 类）。
 */
public class TavernVisitActivity implements LeisureActivity {

    private enum Phase { GO_SEAT, ORDER_WAIT, GO_STOCK, DRINKING, RETURN_CUP, LINGER }

    /** 自有酒单 tag（内引 BAC 酒 tag，required:false——见 data/bmfstown/tags/item）。 */
    public static final TagKey<Item> TAVERN_DRINKS =
            TagKey.create(Registries.ITEM,
                    ResourceLocation.fromNamespaceAndPath("bmfstown", "tavern_drinks"));

    private Phase phase = Phase.GO_SEAT;
    /** 选中的座位方块（null = 区内没座位：站着点单兜底）。 */
    private BlockPos seatTarget;
    /** 选座时的区中心（无座时的行进方向）。 */
    private BlockPos zoneCenter;
    private BlockPos stockPos;
    private ItemStack drink = ItemStack.EMPTY;
    private ItemStack cup = ItemStack.EMPTY;
    private int waitTicksLeft;
    /** 走向座位/进区的计时（超时兜底：进不了区改天再来；座位走不到站着点单）。 */
    private int goSeatTicks;
    /** 走向座位/进区的上限（300t=15 秒）。 */
    private static final int GO_SEAT_TIMEOUT = 300;
    /** 坐上的座位实体（引用跟踪；还杯时保留，回座再骑）。 */
    private SeatEntity seat;
    /** 落脚点之间的原地歇息（无座兜底游走用）。 */
    private int dwellTicksLeft;
    /** 逗留段剩余（有界活动段：到点结束走人）。 */
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
            if (c == null || drinkSlots(c).isEmpty()) {
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

    /** 容器里所有饮品格位（#67 随机化的底料）。 */
    private static List<Integer> drinkSlots(Container stock) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < stock.getContainerSize(); i++) {
            if (isDrink(stock.getItem(i))) {
                slots.add(i);
            }
        }
        return slots;
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
    //  座位
    // ------------------------------------------------------------

    /** 区内挑一个空座位（楼梯/下半砖；已被别的座位实体占用的跳过）。随机取。 */
    private static BlockPos pickSeat(ServerLevel level, TownZone zone, Resident resident) {
        List<BlockPos> seats = new ArrayList<>();
        for (BlockPos pos : TownZone.iterate(zone)) {
            if (!SeatBlocks.isSeat(level.getBlockState(pos))) {
                continue;
            }
            if (!level.getEntitiesOfClass(SeatEntity.class, new AABB(pos)).isEmpty()) {
                continue; // 已被别的客人坐了
            }
            seats.add(pos.immutable());
        }
        return seats.isEmpty() ? null
                : seats.get(resident.getRandom().nextInt(seats.size()));
    }

    /** 离座位方块足够近（水平 1.3 格内、高度差 1.5 内）就能坐。 */
    private static boolean closeEnough(Resident resident, BlockPos pos) {
        double dx = resident.getX() - (pos.getX() + 0.5D);
        double dz = resident.getZ() - (pos.getZ() + 0.5D);
        return dx * dx + dz * dz <= 1.69D
                && Math.abs(resident.getY() - pos.getY()) <= 1.5D;
    }

    /** 真坐：生成座位实体并骑上去（失败返回 false → 站着点单兜底）。 */
    private boolean tryMount(Resident resident) {
        if (resident.isPassenger()) {
            return resident.getVehicle() instanceof SeatEntity;
        }
        this.seat = SeatEntity.seat(resident, this.seatTarget);
        return this.seat != null;
    }

    /** 起身（座位实体保留——还杯后回座再骑；活动结束才真正拆座位）。 */
    private static void dismountSeat(Resident resident) {
        if (resident.isPassenger()) {
            resident.stopRiding();
        }
    }

    /** 拆座位（活动结束/危险打断）。 */
    private void discardSeat() {
        if (this.seat != null) {
            if (this.seat.isAlive()) {
                this.seat.evict();
            }
            this.seat = null;
        }
    }

    /** 是否正处于被打/战斗状态（坐着的人遇险必须起身）——#68 上提 Resident.isUnderThreat。 */
    private static boolean threatened(Resident resident) {
        return resident.isUnderThreat();
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
        this.zoneCenter = new BlockPos(
                (nearest.min().getX() + nearest.max().getX()) / 2,
                nearest.min().getY(),
                (nearest.min().getZ() + nearest.max().getZ()) / 2);
        this.seatTarget = pickSeat(level, nearest, resident);
        this.seat = null;
        this.goSeatTicks = 0;
        this.phase = Phase.GO_SEAT;
        // 无座兜底目标=中心就近的可站立格（中心落在酒桶里时别往桶上撞，2026-11-19）
        BlockPos fallback = com.bmf.bmfstown.entity.NavOps.findStandable(level,
                this.zoneCenter, 4, nearest.min().getY() - 1, nearest.max().getY() + 1);
        navigateTo(resident, this.seatTarget != null ? this.seatTarget
                : (fallback != null ? fallback : this.zoneCenter));
        return true;
    }

    @Override
    public boolean tick(Resident resident, ServerLevel level) {
        // 座位引用跟踪：活着就续坐 ping（ping 断供的座位 40t 后自毁——
        // 活动被掐/存档重载的兜底）
        if (this.seat != null) {
            if (!this.seat.isAlive()) {
                this.seat = null;
                dismountSeat(resident);
            } else {
                this.seat.ping();
            }
        }
        return switch (this.phase) {
            case GO_SEAT -> tickGoSeat(resident, level);
            case ORDER_WAIT -> tickOrderWait(resident, level);
            case GO_STOCK -> tickGoStock(resident, level);
            case DRINKING -> tickDrinking(resident, level);
            case RETURN_CUP -> tickReturnCup(resident, level);
            case LINGER -> tickLinger(resident, level);
        };
    }

    /**
     * 走进酒吧 → 找座位坐下 → 点单。座位走不到/没有座位 → 站着点单兜底
     * （不因为缺椅子就不喝酒）；连区都没进去（被卡门外）→ 改天再来。
     */
    private boolean tickGoSeat(Resident resident, ServerLevel level) {
        this.goSeatTicks++;
        TownZone zone = zoneAt(level, resident);
        if (zone == null) {
            if (this.goSeatTicks > GO_SEAT_TIMEOUT) {
                return false; // 300t 还没踏进酒馆区：被卡死了，改天再来
            }
            if (resident.getNavigation().isDone()) {
                navigateTo(resident, this.seatTarget != null ? this.seatTarget : this.zoneCenter);
            }
            return true;
        }
        // 已进区：停下办正事
        resident.getNavigation().stop();
        if (!resident.getMainHandItem().isEmpty()) {
            return false; // 手被占：本次不喝
        }
        if (this.seatTarget != null && closeEnough(resident, this.seatTarget)
                && tryMount(resident)) {
            placeOrder(resident, zone); // 坐下立即点单（+打铃）
            return true;
        }
        if (this.seatTarget == null || this.goSeatTicks > GO_SEAT_TIMEOUT) {
            // 没座位 / 座位走不到：站着点单兜底（#66 口径保留）
            placeOrder(resident, zone);
            return true;
        }
        if (resident.getNavigation().isDone()) {
            navigateTo(resident, this.seatTarget);
        }
        return true;
    }

    /** 当前镇民所站的酒馆区（XZ 判定）；不在任何区内返回 null。 */
    private static TownZone zoneAt(ServerLevel level, Resident resident) {
        for (TownZone zone : tavernZones(level, resident)) {
            if (zoneContainsXZ(zone, resident.blockPosition())) {
                return zone;
            }
        }
        return null;
    }

    /** 落单公共段：挂单 + 打铃唤醒酒保 → 坐着（或站着）等酒。 */
    private void placeOrder(Resident resident, TownZone zone) {
        TavernOrders.place(resident, zone.min());
        this.waitTicksLeft = Config.TAVERN_ORDER_WAIT.get();
        this.phase = Phase.ORDER_WAIT;
    }

    private static boolean zoneContainsXZ(TownZone zone, BlockPos pos) {
        return pos.getX() >= zone.min().getX() && pos.getX() <= zone.max().getX()
                && pos.getZ() >= zone.min().getZ() && pos.getZ() <= zone.max().getZ();
    }

    /** 坐着等上酒：酒到手 → 立即喝（#67 不搞延迟）；超时 → 自取酒窖兜底。 */
    private boolean tickOrderWait(Resident resident, ServerLevel level) {
        if (threatened(resident)) {
            // 遇险：还没上酒没什么好收尾的，起身走人
            TavernOrders.cancel(resident);
            dismountSeat(resident);
            return false;
        }
        ItemStack hand = resident.getMainHandItem();
        if (!hand.isEmpty()) {
            if (!isDrink(hand)) {
                TavernOrders.cancel(resident);
                dismountSeat(resident);
                return false; // 手里多了不是酒的东西：怪事，本次作废
            }
            TavernOrders.cancel(resident);
            receiveDrink(resident, level, hand); // 到手开喝（原版动画/时长/音效）
            return true;
        }
        if (--this.waitTicksLeft > 0) {
            return true; // 坐着等酒保
        }
        TavernOrders.cancel(resident);
        dismountSeat(resident); // 超时自取：起身去酒窖（座位留着回来坐）
        BlockPos stock = findDrinkStock(level, resident);
        if (stock == null) {
            return false; // 酒保没来窖里也没货：改天再来
        }
        this.stockPos = stock;
        this.phase = Phase.GO_STOCK;
        navigateTo(resident, this.stockPos);
        return true;
    }

    /** 到手开喝（2026-11-16 用户实测拍板：要跟玩家一样的喝法）。可饮物品走
     *  原版使用流程（举瓶仰头+按物品自己的咕咚声/粒子/时长——药水 32t、
     *  蜂蜜 40t、奶桶 32t，BAC 消耗品照各自定义），完成路径见 tickDrinking；
     *  不可饮物品（BAC 纯展示瓶等）落旧即饮兜底。 */
    private void receiveDrink(Resident resident, ServerLevel level, ItemStack stack) {
        this.drink = stack;
        this.cup = cupOf(this.drink);
        if (this.drink.getUseDuration(resident) > 0) {
            this.phase = Phase.DRINKING;
            resident.startUsingItem(InteractionHand.MAIN_HAND);
        } else {
            settleDrink(resident, level); // 即饮兜底（旧路径，含 KegAccess 药效）
            this.drink = ItemStack.EMPTY;
            toLingerOrReturnCup(resident, level);
        }
    }

    /** 正在咕咚：坐着举瓶按原版时长喝（声音/粒子/仰头全走原版使用渲染，
     *  蜂蜜瓶与药水声音不同也是原版按物品出的——不再手动 playSound）。 */
    private boolean tickDrinking(Resident resident, ServerLevel level) {
        if (threatened(resident)) {
            resident.stopUsingItem();
            settleDrink(resident, level); // 打断=当场喝掉（用户拍板"点了就喝完"）
            this.drink = ItemStack.EMPTY;
            returnCupInstantly(resident, level);
            return false;
        }
        if (resident.isUsingItem()) {
            return true; // 正在喝
        }
        finishVanillaDrink(resident, level);
        return true;
    }

    /** 原版喝完的收尾。原版对非玩家的余料口径是分裂的（1.21.1 实读）：
     *  <ul>
     *    <li>奶桶——消耗但<b>不产生空桶</b>（mob 分支 {@code return stack}，
     *        手直接空了，桶凭空消失）；</li>
     *    <li>药水——<b>根本不消耗</b>（女巫同款，主手还是那瓶药水），
     *        但药效照上；</li>
     *    <li>蜂蜜——正常消耗，主手换玻璃瓶。</li>
     *  </ul>
     *  所以杯一律用 {@code cupOf} 预算值走还杯铁律（不指望原版给）；
     *  原版没消耗的（药水）在这里补消耗。心情/饥饿/每日账补居民侧账目；
     *  药效只在原版没上时补（非 PotionItem 的 BAC 酒走不到原版药效）。
     */
    private void finishVanillaDrink(Resident resident, ServerLevel level) {
        ItemStack hand = resident.getMainHandItem();
        if (!hand.isEmpty() && ItemStack.isSameItemSameComponents(hand, this.drink)) {
            hand.shrink(1); // 原版不消耗的补刀（药水）；已消耗的（奶/蜜）为空操作
            if (hand.isEmpty()) {
                resident.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            }
        }
        // 蜂蜜/外部酒把杯（玻璃瓶等）塞进了主手：收走，交给还杯流程
        if (!this.cup.isEmpty()
                && ItemStack.isSameItemSameComponents(resident.getMainHandItem(), this.cup)) {
            resident.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
        resident.getStatus().drankAtTavern(level,
                Config.TAVERN_MOOD_BONUS.get(), Config.TAVERN_DRINK_FOOD.get());
        if (!(this.drink.getItem() instanceof net.minecraft.world.item.PotionItem)) {
            KegAccess.applyPotionContentsOnly(level, resident, this.drink);
        }
        resident.getStatus().setLastTavernDay(level.getDayTime() / 24000L);
        this.drink = ItemStack.EMPTY;
        toLingerOrReturnCup(resident, level);
    }

    private void toLingerOrReturnCup(Resident resident, ServerLevel level) {
        if (this.cup.isEmpty()) {
            toLinger(resident, level);
        } else {
            this.phase = Phase.RETURN_CUP;
        }
    }

    /** 自取兜底：走到酒窖 → 真实取出<strong>随机一种</strong>饮品。 */
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
                receiveDrink(resident, level, resident.getMainHandItem());
                return true;
            }
            return false;
        }
        List<Integer> slots = drinkSlots(stock);
        if (slots.isEmpty()) {
            return false; // 酒被人喝空了
        }
        int slot = slots.get(resident.getRandom().nextInt(slots.size())); // #67 随机
        ItemStack taken = stock.getItem(slot).copy();
        taken.setCount(1);
        stock.getItem(slot).shrink(1);
        stock.setChanged();
        resident.setItemInHand(InteractionHand.MAIN_HAND, taken);
        receiveDrink(resident, level, taken);
        return true;
    }

    /**
     * 即饮结算（旧路径兜底）：不可饮物品的即饮、以及打断时的"当场喝掉"。
     * 心情/饥饿/每日账/药效（KegAccess FOOD+POTION_CONTENTS 全覆盖）。
     * 主手清杯前先确认手上还是这杯——手被手装导演换装过（打断时战斗抢手）
     * 就改从背包喝掉那杯，绝不把导演的武器抹掉。
     */
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

    /** 还杯：起身走到最近的酒吧容器 → 杯子放回（就近原则）→ 回座逗留。 */
    private boolean tickReturnCup(Resident resident, ServerLevel level) {
        dismountSeat(resident); // 起身还杯（座位实体留着，回来再骑）
        if (threatened(resident)) {
            returnCupInstantly(resident, level); // 被打了：杯就地安顿，先顾命
            return false;
        }
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
     *  收尾就会在酒吧待一整天（2026-11-15 用户确认口径）。段结束后自然重掷：
     *  每日一杯闸门挡住再入酒馆，接下来的多半是游走/图书馆。 */
    private void toLinger(Resident resident, ServerLevel level) {
        int lMin = Math.max(1, Config.TAVERN_LINGER_MIN.get());
        int lMax = Math.max(lMin, Config.TAVERN_LINGER_MAX.get());
        this.lingerTicksLeft = lMin + resident.getRandom().nextInt(lMax - lMin + 1);
        this.dwellTicksLeft = 0;
        this.phase = Phase.LINGER;
    }

    /**
     * 逗留：有座位 → <b>坐在原座位上</b>安静待到段尾（聊天/看节目的壳挂在
     * 坐姿上）；没座位 → 老的"走一步歇一阵"游走（安静待客，不永动机狂奔）。
     * 站歇的这段时间就是将来"跟别人聊天/看节目"要填充的壳（#65 下一步）。
     */
    private boolean tickLinger(Resident resident, ServerLevel level) {
        if (--this.lingerTicksLeft <= 0) {
            dismountSeat(resident);
            discardSeat(); // 逗留到点：拆座位走人
            return false; // 活动段结束，重掷下一件事（游走/图书馆…）
        }
        if (threatened(resident)) {
            dismountSeat(resident);
            discardSeat();
            return false; // 遇险起身：酒已喝完杯已还，没什么好收尾的
        }
        if (this.seat != null) {
            // 坐着逗留：还杯途中离座了就走回去再骑上
            if (!resident.isPassenger()) {
                if (closeEnough(resident, this.seatTarget)) {
                    if (resident.startRiding(this.seat, true)) {
                        resident.getNavigation().stop();
                    }
                } else if (resident.getNavigation().isDone()) {
                    navigateTo(resident, this.seatTarget);
                }
            } else {
                resident.getNavigation().stop();
            }
            return true;
        }
        // 无座兜底：原来的游走（走到落脚点歇 100~200t 再挪）
        if (resident.getNavigation().isDone()) {
            if (this.dwellTicksLeft > 0) {
                this.dwellTicksLeft--;
                return true; // 原地站着歇（不走不传）
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
        // 坐着被掐：先起身再收尾（座位当场拆掉——活动都没了，座位不留）
        dismountSeat(resident);
        discardSeat();
        // 兜底（#66 堵滞留杯）：手里已经端着酒（酒保刚塞到手/自取刚拿起的
        // 瞬间被掐）——"点了就喝完"不分阶段：当场喝+还杯，绝不揣着杯走
        if (this.drink.isEmpty() && isDrink(resident.getMainHandItem())) {
            this.drink = resident.getMainHandItem().copy();
        }
        if (!this.drink.isEmpty()) {
            // 睡觉/开饭/窗口掐断统一路径：当场喝掉（用户拍板"点了就喝完"）+ 还杯
            if (resident.isUsingItem()) {
                resident.stopUsingItem(); // 喝到一半被掐：先停使用再结算
            }
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
        this.seatTarget = null;
        this.zoneCenter = null;
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
