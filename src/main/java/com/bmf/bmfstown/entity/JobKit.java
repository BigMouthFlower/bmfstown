package com.bmf.bmfstown.entity;

import com.bmf.bmfstown.ModItems;
import com.bmf.bmfstown.town.Town;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.WritableBookItem;
import net.minecraft.world.item.WrittenBookItem;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 职业通用行为地基（决策 #68，2026-11-16 用户拍板："通用动作不许各写各的"）。
 *
 * <h2>为什么存在</h2>
 * 工人/炼金/烟火/酒保四职业各自手搓过一套"容器访问+选址+收尾归还+缺料播报"，
 * 细节不一致且新职业（酒保）漏接开箱表演被用户点名三次。本类把四件事收编为
 * 唯一实现：<b>新职业禁止再手搓，一律走这里</b>（architecture.md 已立规）。
 *
 * <h2>三组能力</h2>
 * <ol>
 *   <li><b>容器访问/选址</b>：{@link #containerAt}（BAC 安全：外部 mod 的 BE
 *       构造可能炸，按无容器处理）、{@link #validContainers}（工坊登记池 +
 *       被拆容器惰性清理）、{@link #nearest}（谓词选址：锚点距离最近 + 自定义
 *       "有用"判定）、{@link #hasSpaceFor}、{@link #storeItem}（合并插入）；</li>
 *   <li><b>收尾理货</b>：{@link #unloadInto}（按过滤器卸背包进容器）、
 *       {@link #giveBack}（按距离接力试全部登记容器卸到空，全满留身上
 *       不丢不刷；归还自带开盖→倒货→延迟关盖表演）、
 *       {@link #stowOrDrop}（背包兜底再满掉脚边）、{@link #isPersonal}
 *       （私人物品过滤器：食物/书不归公）；</li>
 *   <li><b>缺料播报</b>：{@link #shortage}（16 格 actionbar + 30 秒节流，
 *       #37 口径）、{@link #label}（"展示名×缺口"一段）。</li>
 * </ol>
 *
 * <h2>开容器表演</h2>
 * 盖子动画/音效走 {@link Resident#containerFx}（更早的通用件）；Goal 运行中
 * 配合 {@code lidTimer} 做"开盖→停顿→取放→关盖"节奏（工人 WorkerGoal 为
 * 标杆实现）；stop() 理货路径没有 Goal tick 可用，{@link #giveBack} 用
 * {@link Resident#containerFxOpenThenClose} 开盖后由实体心跳延迟代发关盖。
 */
public final class JobKit {

    /** 缺料播报节流：30 秒一次（炼金/酒保既有口径）。 */
    private static final long SHORTAGE_THROTTLE_MS = 30_000L;
    /** 播报半径 16 格（平方距离）。 */
    private static final double ANNOUNCE_RANGE_SQR = 256.0D;

    /** 每镇民一条播报节流游标。 */
    private static final Map<UUID, Long> LAST_SHORTAGE_MS = new HashMap<>();

    private JobKit() {
    }

    // ==========================================================
    //  1. 容器访问 / 选址
    // ==========================================================

    /**
     * 容器访问（BAC 安全）：外部 mod 的 BlockEntity 构造可能炸（BAC keg
     * 无配置环境构造即抛），一律按"无容器"处理，绝不让职业扫描炸服。
     */
    @Nullable
    public static Container containerAt(ServerLevel level, BlockPos pos) {
        try {
            BlockEntity be = level.getBlockEntity(pos);
            return be instanceof Container container ? container : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 工坊登记容器池（被拆的顺手惰性清理——原工人/炼金/烟火三处同构逻辑收编）。
     */
    public static List<BlockPos> validContainers(ServerLevel level,
            @Nullable Town.WorkshopRegister reg) {
        List<BlockPos> out = new ArrayList<>();
        if (reg == null) {
            return out;
        }
        List<Long> broken = null;
        for (Long p : reg.containers) {
            BlockPos pos = BlockPos.of(p);
            if (containerAt(level, pos) != null) {
                out.add(pos);
            } else if (broken == null) {
                broken = new ArrayList<>();
                broken.add(p);
            } else {
                broken.add(p);
            }
        }
        if (broken != null) {
            reg.containers.removeAll(broken);
            com.bmf.bmfstown.town.TownSaveData.get(level).setDirty();
        }
        return out;
    }

    /**
     * 谓词选址：池子里离 {@code anchor}（null 则以 {@code resident} 为锚）最近的
     * 满足 {@code useful} 的容器。{@code useful} 内不必再判 containerAt 空——
     * 池子传入前应已经 {@link #validContainers} 校验。
     */
    @Nullable
    public static BlockPos nearest(ServerLevel level, List<BlockPos> pool,
            @Nullable BlockPos anchor, Resident fallbackAnchor,
            Predicate<Container> useful) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos from = anchor != null ? anchor : fallbackAnchor.blockPosition();
        for (BlockPos pos : pool) {
            Container c = containerAt(level, pos);
            if (c == null || !useful.test(c)) {
                continue;
            }
            double d = pos.distSqr(from);
            if (d < bestDist) {
                bestDist = d;
                best = pos;
            }
        }
        return best;
    }

    /** 容器是否装得下这种物品（空槽或可合并的未满堆）。 */
    public static boolean hasSpaceFor(Container c, Item item) {
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack slot = c.getItem(i);
            if (slot.isEmpty() || (slot.is(item) && slot.getCount() < slot.getMaxStackSize())) {
                return true;
            }
        }
        return false;
    }

    /** 合并插入：先叠同类，再进空槽（插不完的留在 stack 里，调用方处理）。 */
    public static void storeItem(Container container, ItemStack stack) {
        for (int i = 0; i < container.getContainerSize() && !stack.isEmpty(); i++) {
            ItemStack slot = container.getItem(i);
            if (slot.isEmpty()) {
                container.setItem(i, stack.copy());
                stack.setCount(0);
            } else if (ItemStack.isSameItemSameComponents(slot, stack)) {
                int space = slot.getMaxStackSize() - slot.getCount();
                if (space > 0) {
                    int move = Math.min(space, stack.getCount());
                    slot.grow(move);
                    stack.shrink(move);
                }
            }
        }
        if (!stack.isEmpty()) {
            container.setChanged();
        }
    }

    // ==========================================================
    //  2. 收尾理货（下班/停工/批次尾归还材料）
    // ==========================================================

    /**
     * 私人物品判定（收尾理货一律带此过滤器）。三类不归公：
     * <ol>
     *   <li><b>食物</b>——觅食自留的口粮。例外预告：厨师职业的食材也是食物，
     *       到时厨师 Goal 传自己的过滤器（{@code giveBack} 收 Predicate 参数，
     *       本方法只是默认基线），见 roadmap 厨师条目；</li>
     *   <li><b>书</b>——学者/图书馆清扫网体系管辖，还书走书架不走公箱；</li>
     *   <li><b>装备</b>（武器/盔甲/盾）——玩家塞进背包或战斗后导演收回的，
     *       是居民的私产而非工作材料（2026-11-16 用户点名：下班理货不许把
     *       武器盔甲充公）。主手与盔甲槽是原版装备槽，{@link #unloadInto}
     *       只扫背包、天然不碰；这里拦的是"停战收回背包"那一刻。</li>
     * </ol>
     */
    public static boolean isPersonal(ItemStack st) {
        return st.has(net.minecraft.core.component.DataComponents.FOOD)
                || st.getItem() instanceof WrittenBookItem
                || st.getItem() instanceof WritableBookItem
                || st.is(ModItems.FINISHED_BOOK.get())
                || isGear(st);
    }

    /** 装备判定：近战武器（剑/斧/重锤/三叉戟）、远程（弓弩）、盔甲、盾。 */
    private static boolean isGear(ItemStack st) {
        var item = st.getItem();
        return item instanceof net.minecraft.world.item.SwordItem
                || item instanceof net.minecraft.world.item.AxeItem
                || item instanceof net.minecraft.world.item.MaceItem
                || item instanceof net.minecraft.world.item.TridentItem
                || item instanceof net.minecraft.world.item.BowItem
                || item instanceof net.minecraft.world.item.CrossbowItem
                || item instanceof net.minecraft.world.item.ShieldItem
                || item instanceof net.minecraft.world.item.AnimalArmorItem
                || item instanceof net.minecraft.world.item.ArmorItem;
    }

    /**
     * 卸背包：把 {@code filter} 命中的槽位尽可能塞进 {@code target}
     * （合并同类；装不下的留身上——绝不吞、绝不掉地上）。
     * 入参用 {@link Container}：镇民背包 ResidentInventory 与箱子同接口。
     */
    public static void unloadInto(Container target, Container inv,
            Predicate<ItemStack> filter) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !filter.test(stack)) {
                continue;
            }
            storeItem(target, stack);
            if (stack.isEmpty()) {
                inv.setItem(i, ItemStack.EMPTY);
            }
        }
    }

    /** 身上是否还有 filter 命中的货（含主手之外的背包槽；ReturnCargoGoal 复用）。 */
    static boolean hasMatching(Container inv, Predicate<ItemStack> filter) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (!st.isEmpty() && filter.test(st)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 归还可达范围（格）：超出这个距离不隔空卸货（2026-11-17 用户实测酒保
     * "远隔空放进去"点名）。够不着时挂 {@link Resident.PendingReturn} 待还
     * 任务，由 {@link ReturnCargoGoal} 走过去再还——物理真实优先（决策 #72）。
     */
    public static final double RETURN_REACH = 4.0;

    /**
     * 收尾归还：**按距离由近到远接力试全部登记容器**，直到卸空或池子试完；
     * 全满则留在身上（下批/下次收尾再试——烟道理货铁律 #39 的通用化）。
     *
     * <p>2026-11-16 实测修订（用户点名烟火工匠）：旧版只试最近一个容器，
     * 满箱会出现"能合并的染料回去了、要开新格的烟火之星/火药/纸留在身上"
     * 的半截归还。多容器接力后，一箱满自动换下一箱。
     *
     * <p>归还补开箱表演（#68 P0 同口径）：开盖→倒货→延迟关盖——stop() 后
     * Goal 不再 tick，关盖事件由 {@link Resident} 心跳代发（见
     * {@link Resident#containerFxOpenThenClose}）。真有货进箱才表演。
     *
     * <p>2026-11-17 二次修订（用户点名酒保"隔空放货"）：加可达距离判定
     * （{@link #RETURN_REACH}）——范围外不隔空卸货，有货还不出时挂
     * {@link Resident.PendingReturn} 待还任务，由 {@link ReturnCargoGoal}
     * 走过去再还（决策 #72）。
     *
     * @return 是否至少往一个容器里倒进过货（false = 身上原样保留/无货可还）
     */
    public static boolean giveBack(ServerLevel level, Resident resident,
            List<BlockPos> pool, Predicate<ItemStack> filter) {
        BlockPos self = resident.blockPosition();
        List<BlockPos> sorted = new ArrayList<>(pool);
        sorted.sort(Comparator.comparingDouble(p -> p.distSqr(self)));
        boolean any = false;
        double reachSqr = RETURN_REACH * RETURN_REACH;
        for (BlockPos pos : sorted) {
            if (!hasMatching(resident.getInventory(), filter)) {
                break; // 卸空了
            }
            if (pos.distSqr(self) > reachSqr) {
                break; // 后面只会更远：范围内已尽力，剩下交给待还任务（#72）
            }
            Container target = containerAt(level, pos);
            if (target == null) {
                continue;
            }
            int before = countMatching(resident.getInventory(), filter);
            unloadInto(target, resident.getInventory(), filter);
            if (countMatching(resident.getInventory(), filter) < before) {
                resident.containerFxOpenThenClose(level, pos, 10);
                any = true;
            }
        }
        // 范围内没卸完 → 挂待还任务（走去最近能收的容器再还，#72 拍板）
        if (hasMatching(resident.getInventory(), filter) && !pool.isEmpty()) {
            resident.setPendingReturn(
                    new Resident.PendingReturn(List.copyOf(pool), filter));
        }
        return any;
    }

    /** 身上 filter 命中的堆数（归还表演只在真有货进箱时响一次；ReturnCargoGoal 复用）。 */
    static int countMatching(Container inv, Predicate<ItemStack> filter) {
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (!st.isEmpty() && filter.test(st)) {
                n++;
            }
        }
        return n;
    }

    /** 背包兜底：塞不进容器的先进背包，再满掉脚边（绝不吞货）。 */
    public static void stowOrDrop(Resident resident, ItemStack st) {
        ItemStack over = resident.getInventory().addItem(st);
        if (!over.isEmpty()) {
            resident.spawnAtLocation(over);
        }
    }

    // ==========================================================
    //  3. 缺料播报（#37 口径：点名缺什么）
    // ==========================================================

    /** "展示名×缺口" 一段（拼句用）。 */
    public static String label(ItemStack sample, int gap) {
        return sample.getHoverName().getString() + "×" + gap;
    }

    /**
     * 缺料播报：16 格内玩家 actionbar，30 秒节流（每镇民独立游标）。
     * 消息文本由调用方组（各职业自己的 lang 键），本方法只管半径/节流/展示。
     */
    public static void shortage(ServerLevel level, Resident who, Component msg) {
        long now = System.currentTimeMillis();
        Long last = LAST_SHORTAGE_MS.get(who.getUUID());
        if (last != null && now - last < SHORTAGE_THROTTLE_MS) {
            return;
        }
        LAST_SHORTAGE_MS.put(who.getUUID(), now);
        for (var p : level.players()) {
            if (p instanceof net.minecraft.server.level.ServerPlayer sp
                    && sp.distanceToSqr(who) < ANNOUNCE_RANGE_SQR) {
                sp.displayClientMessage(msg, true);
            }
        }
    }
}
