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

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;

/**
 * 职业线索（v0.10）：镇民"看见"玩家携带特定物品 → 说一句职业线索台词 →
 * **同一 tick** 解锁该职业（台词气泡 + 聊天公告 + "叮"三件套同时到达）。
 *
 * <p>纪律：
 * <ul>
 *   <li><b>每条线索每城镇只发生一次</b>——说与解锁同时，解锁后永久静默；</li>
 *   <li><b>全服每线索类型一个游标</b>（15 分钟）——多名镇民在范围内也只有一个
 *       开口（互斥），且不同线索类型互不干扰（冷却按类型独立）；</li>
 *   <li><b>元件按背包顺序取第一个命中</b>——台词复述它（占位符）。</li>
 * </ul>
 */
public final class JobHints {

    /** 解锁 id → 上次触发的 dayTime（全服每类型一个游标）。 */
    private static final Map<String, Long> LAST_HINT_AT = new HashMap<>();

    /** 同一线索类型的全服冷却：15 分钟（dayTime tick）。 */
    private static final long HINT_INTERVAL = 18000L;

    /** 线索触发距离（气泡阅读距离）。 */
    private static final double HINT_RANGE_SQR = 8.0 * 8.0;

    /** 红石元件清单：显式核心 + 注册表后缀扫描（_button/_pressure_plate 全家族 + 模组兼容）。 */
    private static final Set<Item> REDSTONE_COMPONENTS = buildRedstoneComponents();

    private static Set<Item> buildRedstoneComponents() {
        Set<Item> out = new HashSet<>();
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
        tryWorkerHint(resident, level);
        // 簿记员（玩家携带书 → "会写字真好……"）等线索将来加在这里
    }

    private static void tryWorkerHint(Resident resident, ServerLevel level) {
        java.util.UUID townId = resident.getIdentity().townId();
        if (townId == null) {
            return; // 无城镇：无处解锁
        }
        UnlockState state = UnlockState.get(level);
        if (state.isUnlocked(townId, "unlock.job.worker")) {
            return; // 已解锁：永久静默
        }
        long now = level.getDayTime();
        Long last = LAST_HINT_AT.get("worker");
        if (last != null && now < last) {
            LAST_HINT_AT.put("worker", now); // 时间回拨：游标重置
        } else if (last != null && now - last < HINT_INTERVAL) {
            return; // 冷却中
        }

        // 最近的"身上带着红石元件"的玩家（8 格；旁观者不算；按背包顺序取第一个命中）
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
            ItemStack hit = findComponent(player);
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
        LAST_HINT_AT.put("worker", now);

        // ① 台词（气泡，元件名占位；嘴忙锁/冷却可能丢弃——无妨，解锁不依赖台词）
        TownEventBus.dispatch(TownEvent.jobHint(resident, found));
        // ② 解锁（同 tick）：聊天公告"已发现 隐藏职业「工人」"+ 叮 + 面板列表生效
        state.unlockIfNew(level, townId, "unlock.job.worker");
    }

    /** 按背包顺序找第一个红石元件（主手/副手/背包/盔甲全容器遍历）。 */
    private static ItemStack findComponent(ServerPlayer player) {
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && REDSTONE_COMPONENTS.contains(stack.getItem())) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }
}
