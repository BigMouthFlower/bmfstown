package com.bmf.bmfstown.unlock;

import com.bmf.bmfstown.network.ModNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 解锁状态（SavedData）：**访问空间 = 城镇**（用户定稿：全域共享被否决——
 * 新建城镇必须独立成长，解锁进度不得跨城镇泄漏）。
 *
 * <p>存储：城镇 id → (解锁 id → 解锁时刻)。城镇删除后的条目成为孤儿
 * （无害；将来可在删除城镇时顺带清理）。解锁是永久闩锁：正常游玩永不回收
 * （含时间回拨——回拨保护见各自的游标比较；解锁记录不受影响）。
 *
 * <p>三条解锁路径：
 * <ol>
 *   <li><b>显式解锁</b>（{@link #unlockIfNew}）：代码在验证过语义后直接调用；</li>
 *   <li><b>事件型定义</b>（{@link #feed}）：已武装的 EVENT 型定义被投喂
 *       解锁上下文，谓词通过即解锁；</li>
 *   <li><b>状态型定义</b>：武装瞬间立即评估一次当前状态（在
 *       {@link #armAndEvaluate} 连锁里完成）。</li>
 * </ol>
 *
 * <p><b>武装连锁</b>：每次解锁后扫描登记表，把"前置 ⊆ 已解锁"的定义激活；
 * 状态型当场评估，可能再触发下一轮解锁——沿依赖 DAG 徐徐展开。
 *
 * <p>公告与同步：解锁瞬间全服聊天公告（双色 + 提示音）+ 客户端全量快照
 * 同步（{@link com.bmf.bmfstown.client.ClientUnlockState} 镜像）+ 登录补发。
 */
@EventBusSubscriber
public final class UnlockState extends SavedData {

    /** 城镇 id → (解锁 id → 解锁时刻 dayTime)。 */
    private final Map<UUID, Map<String, Long>> byTown = new HashMap<>();

    private static final String DATA_NAME = "bmfstown_unlocks";

    public static UnlockState get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(UnlockState::new, UnlockState::load, null), DATA_NAME);
    }

    // ------------------------------------------------------------
    //  查询
    // ------------------------------------------------------------

    public boolean isUnlocked(@Nullable UUID townId, String id) {
        if (townId == null) {
            return false;
        }
        Map<String, Long> set = this.byTown.get(townId);
        return set != null && set.containsKey(id);
    }

    public Set<String> allIds(UUID townId) {
        Map<String, Long> set = this.byTown.get(townId);
        return set == null ? Set.of() : Set.copyOf(set.keySet());
    }

    /** 武装判定：该城镇已解锁的定义前置全部就位。 */
    public boolean isArmed(UnlockDefinition def, UUID townId) {
        if (townId == null) {
            return false;
        }
        return this.byTown.getOrDefault(townId, Map.of()).keySet()
                .containsAll(def.requires());
    }

    // ------------------------------------------------------------
    //  解锁路径（显式 / 事件投喂 / 武装评估）
    // ------------------------------------------------------------

    public boolean unlockIfNew(ServerLevel level, @Nullable UUID townId, String id) {
        if (townId == null || isUnlocked(townId, id)) {
            return false;
        }
        record(level, townId, id);
        armAndEvaluate(UnlockContext.of(level, townId));
        return true;
    }

    /**
     * 重新锁定（仅调试：/bmfstown lock）。游戏语义上解锁是永久闩锁，
     * 正常游玩永不回收——只服务于测试循环。静默：无公告无提示音；
     * 已雇用的职业等数据不受影响（锁定的只是"可新选定"入口）。
     */
    public boolean lock(ServerLevel level, @Nullable UUID townId, String id) {
        if (townId == null) {
            return false;
        }
        Map<String, Long> set = this.byTown.get(townId);
        if (set == null || set.remove(id) == null) {
            return false;
        }
        this.setDirty();
        broadcast(level);
        return true;
    }

    /** 记账 + 状态同步 + 全服公告。三条解锁路径共用的唯一记账点。 */
    private void record(ServerLevel level, UUID townId, String id) {
        this.byTown.computeIfAbsent(townId, k -> new HashMap<>()).put(id, level.getDayTime());
        this.setDirty();
        broadcast(level);
        announce(level, id);
    }

    /** 事件型条件投喂：对"已武装 + 未解锁 + 事件型"定义跑谓词（带城镇空间）。 */
    public void feed(UnlockContext ctx) {
        if (ctx.townId() == null) {
            return;
        }
        for (UnlockDefinition def : UnlockRegistry.all()) {
            if (def.condition() == null || def.stateCondition()) {
                continue; // 手动型 / 状态型不走事件投喂
            }
            if (isUnlocked(ctx.townId(), def.id()) || !isArmed(def, ctx.townId())) {
                continue;
            }
            if (def.condition().test(ctx)) {
                unlockIfNew(ctx.level(), ctx.townId(), def.id());
            }
        }
    }

    /** 武装连锁：新解锁让下游定义凑齐前置；状态型当场评估（沿 DAG 展开）。 */
    private void armAndEvaluate(UnlockContext ctx) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (UnlockDefinition def : UnlockRegistry.all()) {
                if (def.condition() == null || !def.stateCondition()) {
                    continue; // 手动型 / 事件型不在武装瞬间评估
                }
                if (isUnlocked(ctx.townId(), def.id()) || !isArmed(def, ctx.townId())) {
                    continue;
                }
                if (def.condition().test(ctx)) {
                    record(ctx.level(), ctx.townId(), def.id());
                    changed = true; // 新解锁可能让更下游凑齐前置，再扫一轮
                }
            }
        }
    }

    // ------------------------------------------------------------
    //  公告与同步
    // ------------------------------------------------------------

    /**
     * 解锁公告（用户定稿）：左下角聊天栏，"已发现 + 类型 + 「内容」"——
     * 类型词青色加粗、内容名金色加粗，配"叮"提示音。
     * 无展示名的定义静默解锁。⚠ 广播听众目前是维度内全部玩家，
     * 多人按城镇成员过滤待玩家归属建模后细化。
     */
    private void announce(ServerLevel level, String id) {
        UnlockDefinition def = UnlockRegistry.byId(id);
        if (def == null || def.displayNameKey() == null) {
            return;
        }
        var message = Component.translatable("msg.bmfstown.unlock.announce.head") // "已发现 "
                .append(Component.translatable("msg.bmfstown.unlock.type." + def.category())
                        .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)) // 类型：隐藏职业…
                .append(Component.literal("「").withStyle(ChatFormatting.GOLD))
                .append(Component.translatable(def.displayNameKey())
                        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)) // 内容名
                .append(Component.literal("」").withStyle(ChatFormatting.GOLD));
        for (var player : level.players()) {
            player.displayClientMessage(message, false); // false = 聊天栏（非 actionbar）
            // 提示音必须走 playNotifySound（直发连接、与坐标无关）——
            // Player.playSound 会把玩家自己排除在听众外（实测踩坑）
            player.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP,
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.8F, 1.0F);
        }
    }

    /** 全量快照广播（城镇 → ids；解锁瞬间与登录时发送）。 */
    private void broadcast(ServerLevel level) {
        ModNetworking.sendUnlockSync(level, snapshot());
    }

    public Map<UUID, Set<String>> snapshot() {
        Map<UUID, Set<String>> out = new HashMap<>();
        this.byTown.forEach((town, set) -> out.put(town, Set.copyOf(set.keySet())));
        return out;
    }

    /** 玩家登录补发全量快照。 */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            get(player.serverLevel()).broadcast(player.serverLevel());
        }
    }

    // ------------------------------------------------------------
    //  序列化（扁平条目：城镇 + 解锁 id + 时刻；读档时按城镇分组）
    // ------------------------------------------------------------

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        this.byTown.forEach((town, set) -> set.forEach((id, at) -> {
            CompoundTag t = new CompoundTag();
            t.putUUID("Town", town);
            t.putString("Id", id);
            t.putLong("At", at);
            list.add(t);
        }));
        tag.put("Entries", list);
        return tag;
    }

    private static UnlockState load(CompoundTag tag, HolderLookup.Provider registries) {
        UnlockState state = new UnlockState();
        ListTag list = tag.getList("Entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            UUID town = t.hasUUID("Town") ? t.getUUID("Town") : null;
            String id = t.getString("Id");
            if (town == null || id.isEmpty()) {
                continue;
            }
            state.byTown.computeIfAbsent(town, k -> new HashMap<>()).put(id, t.getLong("At"));
        }
        return state;
    }
}
