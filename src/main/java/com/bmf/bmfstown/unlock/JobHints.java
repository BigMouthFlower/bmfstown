package com.bmf.bmfstown.unlock;

import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.social.TownEvent;
import com.bmf.bmfstown.social.TownEventBus;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 职业线索（v0.10.1）：两级结构——
 *
 * <ul>
 *   <li><b>引导线</b>（可重复，不解锁）：镇民看到玩家携带红石块/矿 →
 *       "这东西可以加工成精密元件哦"。防玩家一直不凑齐元件、永远不知道
 *       有这个职业。频率按城镇 × 玩家记账（5 分钟），两玩家互不占额度；</li>
 *   <li><b>解锁线</b>（一次性）：玩家携带 **≥ 3 种**红石元件 → 镇民复述
 *       三种 → **同一 tick** 解锁工人（台词气泡 + 聊天公告 + "叮"同时到达）。</li>
 * </ul>
 *
 * <p>纪律：
 * <ul>
 *   <li><b>每条解锁线每城镇只发生一次</b>——互斥由解锁幂等天然保证：
 *       第一个触发的镇民解锁后，后续镇民（含同 tick）在入口的
 *       isUnlocked 检查处全部静默，无需额外冷却；</li>
 *   <li><b>引导线的游标有意义</b>（可重复 → 需要防刷屏），与解锁线不同；</li>
 *   <li><b>台词复述看到的种类</b>：按背包顺序收集、**同种去重**——
 *       多个格子装同一种只算一种；气泡里念前三种。</li>
 * </ul>
 */
public final class JobHints {

    /** 台词里最多复述的种类数（门槛是 3，多带了的嘴上省略）。 */
    private static final int MAX_BUBBLE_KINDS = 3;

    /** 触发门槛：背包中不同种类数 ≥ 此值才判定为"红石玩家"。 */
    private static final int MIN_KINDS = 3;

    /** 线索触发距离（气泡阅读距离）。 */
    private static final double HINT_RANGE_SQR = 8.0 * 8.0;

    // ------------------------------------------------------------
    //  引导线（可重复，不解锁）：看见红石块/矿 → "这东西能加工哦"
    //  频率记账 = 城镇 × 玩家（用户定稿的二维结构）——两个玩家各自
    //  触发互不占额度；这个游标有意义（可重复 → 需要防刷屏），
    //  与解锁线不同（那条靠解锁幂等，无游标）。
    // ------------------------------------------------------------

    /** 引导线游标：城镇 id → (玩家 id → 上次触发的 dayTime)。 */
    private static final java.util.Map<java.util.UUID,
            java.util.Map<java.util.UUID, Long>> AMBIENT_LAST_AT = new java.util.HashMap<>();

    /** 引导线冷却：同一城镇对同一玩家 5 分钟（dayTime tick，带回拨保护）。 */
    private static final long AMBIENT_INTERVAL = 6000L;

    /** 引导触发清单：红石块 / 红石矿 / 深板红石矿（用户定稿）。 */
    private static final Set<Item> AMBIENT_ITEMS = Set.of(
            Items.REDSTONE_BLOCK, Items.REDSTONE_ORE, Items.DEEPSLATE_REDSTONE_ORE);

    /** 红石元件清单：显式核心 + 注册表后缀扫描（_button/_pressure_plate 全家族 + 模组兼容）。 */
    private static final Set<Item> REDSTONE_COMPONENTS = buildRedstoneComponents();

    private static Set<Item> buildRedstoneComponents() {
        Set<Item> out = new LinkedHashSet<>();
        BuiltInRegistries.ITEM.forEach(item -> {
            String path = BuiltInRegistries.ITEM.getKey(item).getPath();
            if (path.endsWith("_button") || path.endsWith("_pressure_plate")) {
                out.add(item); // 全木材/石质按钮与压力板家族，其他模组自动兼容
            }
        });
        out.add(Items.REDSTONE_TORCH);
        out.add(Items.REDSTONE_BLOCK);
        out.add(Items.REDSTONE_LAMP);
        out.add(Items.REPEATER);
        out.add(Items.COMPARATOR);
        out.add(Items.PISTON);
        out.add(Items.STICKY_PISTON);
        out.add(Items.OBSERVER);
        out.add(Items.DISPENSER);
        out.add(Items.DROPPER);
        out.add(Items.HOPPER);
        out.add(Items.TARGET);
        out.add(Items.DAYLIGHT_DETECTOR);
        out.add(Items.LEVER);
        out.add(Items.TRIPWIRE_HOOK);
        out.add(Items.NOTE_BLOCK);
        out.add(Items.SCULK_SENSOR);
        out.add(Items.CALIBRATED_SCULK_SENSOR);
        out.add(Items.CRAFTER);
        out.add(Items.COPPER_BULB);
        out.add(Items.IRON_DOOR);
        out.add(Items.IRON_TRAPDOOR);
        out.add(Items.SLIME_BLOCK);
        return Set.copyOf(out);
    }

    private JobHints() {
    }

    /** 感知入口（Status.tickSenses 的 5 秒轮调用）。 */
    public static void sense(Resident resident, ServerLevel level) {
        tryWorkerHint(resident, level);   // 解锁线（一次性，≥3 种元件）
        tryAmbientHint(resident, level);  // 引导线（可重复，红石块/矿）
        // 簿记员（玩家携带书 → "会写字真好……"）等线索将来加在这里
    }

    /**
     * 引导线：该镇还没解锁工人时，镇民看到玩家携带红石块/矿，
     * 偶尔提示"这东西可以加工哦"——引导玩家往元件方向凑。
     * 不解锁、可重复；频率按**城镇 × 玩家**记账（5 分钟），
     * 气泡内容随携带者不同而不同；嘴忙锁兜底防撞嘴。
     */
    private static void tryAmbientHint(Resident resident, ServerLevel level) {
        java.util.UUID townId = resident.getIdentity().townId();
        if (townId == null) {
            return;
        }
        if (UnlockState.get(level).isUnlocked(townId, "unlock.job.worker")) {
            return; // 已解锁：引导任务完成，永久静默
        }
        long now = level.getDayTime();

        // 最近的"身上带着红石块/矿"的玩家（8 格；旁观者不算）
        ServerPlayer carrier = null;
        ItemStack found = ItemStack.EMPTY;
        double best = HINT_RANGE_SQR;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            double d = resident.distanceToSqr(player);
            if (d >= best) {
                continue;
            }
            // 该镇对该玩家的引导 CD 独立记账
            Long last = AMBIENT_LAST_AT.getOrDefault(townId, java.util.Map.of())
                    .get(player.getUUID());
            if (last != null) {
                if (now < last) {
                    AMBIENT_LAST_AT.remove(townId); // 时间回拨：整镇游标重置
                } else if (now - last < AMBIENT_INTERVAL) {
                    continue; // 这位玩家还在冷却中，看看别人
                }
            }
            ItemStack hit = findFirst(player, AMBIENT_ITEMS);
            if (hit.isEmpty()) {
                continue;
            }
            carrier = player;
            found = hit;
            best = d;
        }
        if (carrier == null) {
            return;
        }
        // 嘴忙联动：嘴忙时不记账不说话（种子下轮再播，额度不白扣）
        if (!com.bmf.bmfstown.social.DialogEngine.isMouthFree(resident, level.getGameTime())) {
            return;
        }
        AMBIENT_LAST_AT.computeIfAbsent(townId, k -> new java.util.HashMap<>())
                .put(carrier.getUUID(), now);
        // 不解锁——只播一颗种子
        TownEventBus.dispatch(TownEvent.redstoneAmbient(resident, found));
    }

    private static void tryWorkerHint(Resident resident, ServerLevel level) {
        java.util.UUID townId = resident.getIdentity().townId();
        if (townId == null) {
            return; // 无城镇：无处解锁
        }
        UnlockState state = UnlockState.get(level);
        if (state.isUnlocked(townId, "unlock.job.worker")) {
            return; // 已解锁：永久静默（每镇一次的互斥就在这一行）
        }

        // 最近的"身上带着 ≥ 3 种红石元件"的玩家（8 格；旁观者不算）
        ServerPlayer carrier = null;
        List<ItemStack> kinds = List.of();
        double best = HINT_RANGE_SQR;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            double d = resident.distanceToSqr(player);
            if (d >= best) {
                continue;
            }
            List<ItemStack> hit = collectKinds(player);
            if (hit.size() < MIN_KINDS) {
                continue; // 门槛：种类数不足，不算红石玩家
            }
            carrier = player;
            kinds = hit;
            best = d;
        }
        if (carrier == null) {
            return;
        }
        // 嘴忙联动（用户问出的缝隙）：嘴忙时本轮整轮放弃——不说话也不解锁，
        // 保持"台词+公告+叮"同时到达；感知轮 5 秒一次，等一嘴的功夫就好
        if (!com.bmf.bmfstown.social.DialogEngine.isMouthFree(resident, level.getGameTime())) {
            return;
        }

        // ① 台词（气泡，复述前三种）
        TownEventBus.dispatch(TownEvent.jobHint(resident,
                kinds.subList(0, Math.min(MAX_BUBBLE_KINDS, kinds.size()))));
        // ② 解锁（同 tick）：聊天公告"已发现 隐藏职业「工人」"+ 叮 + 面板列表生效。
        //    解锁成功 = 该镇该线索从此静默；重复调用幂等，天然互斥。
        state.unlockIfNew(level, townId, "unlock.job.worker");
    }

    /** 按背包顺序找第一个命中物品（主手/副手/背包全容器遍历）。 */
    private static ItemStack findFirst(ServerPlayer player, Set<Item> wanted) {
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && wanted.contains(stack.getItem())) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * 按背包顺序收集命中的**种类**（主手/副手/背包全容器遍历）。
     * 多个格子装同一种只算一种（LinkedHashSet 去重，保序）。
     */
    private static List<ItemStack> collectKinds(ServerPlayer player) {
        var inventory = player.getInventory();
        Set<Item> seen = new LinkedHashSet<>();
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !REDSTONE_COMPONENTS.contains(stack.getItem())
                    || !seen.add(stack.getItem())) {
                continue;
            }
            out.add(stack); // 每种只取第一次遇到的那格
        }
        return out;
    }
}
