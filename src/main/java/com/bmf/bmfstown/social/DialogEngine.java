package com.bmf.bmfstown.social;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.config.Config;
import com.bmf.bmfstown.entity.Resident;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 台词引擎（v0.9.1 表达层）：决定"谁、在什么时候、说哪句"。
 *
 * <p>展示方式：原版 {@code text_display} 实体——命名牌同款观感
 * （公告牌朝向 + 半透明背景板 + 自动换行），渲染完全由原版维护。
 * 生成时打上 {@link #DISPLAY_TAG} 标记，到期 discard；重进世界若有孤儿
 * （服务重启没来得及回收的）由定期清扫按标记 + 年龄回收。
 *
 * <p>两条触发路径：
 * <ul>
 *   <li>**事件即时**：吃到爱吃的——结算瞬间说（错过时刻就没意义）；</li>
 *   <li>**事件 + 个人相位错峰**：天气变化——只有 settleOffset % N == 0 的
 *       镇民开口，生成时刻 = 变化时刻 + (settleOffset % 错峰窗口)，
 *       复用每日结算的负载均衡偏离值（用户定稿：全镇个人节拍全局一致）。</li>
 * </ul>
 *
 * <p>冷却记账：镇民 × 冷却组（P1 无定向型台词，多人分账 P2 引入）。
 * 运行态不落档。
 */
@EventBusSubscriber(modid = BmfTown.MOD_ID)
public final class DialogEngine {

    private DialogEngine() {
    }

    /** 台词展示实体的标记（重进世界后清孤儿用）。 */
    public static final String DISPLAY_TAG = "bmfstown_dialog";
    /** 展示实体最长寿命（tick）：超过即视为孤儿回收（展示时长 200 + 冗余）。 */
    private static final int DISPLAY_MAX_AGE_TICKS = 600;
    /** 孤儿清扫间隔（tick）。 */
    private static final int SWEEP_INTERVAL_TICKS = 400;
    /** 单句展示时长（tick）：10 秒——关键信息要让人看得全（用户 2026-10-30 定稿）。 */
    private static final int DISPLAY_TICKS = 200;
    /** "嘴忙锁"冷却组：镇民头顶还有字没消完时，其他台词全部跳过，防止两条字叠同人。 */
    private static final String GROUP_BUSY = "busy";

    /** 错峰队列：到点才生成展示实体。 */
    private record ScheduledSay(Resident resident, DialogRegistry.DialogLine line,
                                ItemStack item, long dueTick) {
    }

    /** 回收队列：到点 discard 展示实体（附带镇民引用，存活期内每 tick 跟随头顶）。 */
    private record PendingDiscard(Resident resident, DialogDisplay display, long dueTick) {
    }

    private static final List<ScheduledSay> SCHEDULED = new ArrayList<>();
    private static final List<PendingDiscard> DISCARDS = new ArrayList<>();
    /** 冷却记账：镇民 UUID → (冷却组 → 禁言到 gameTime)。 */
    private static final Map<UUID, Map<String, Long>> COOLDOWNS = new HashMap<>();

    private static int sweepCooldown = 0;

    static {
        // 静态注册一次（本类随 mod 构造加载）
        TownEventBus.register(DialogEngine::handle);
    }

    // ==========================================================
    //  公开入口（命令/测试用）
    // ==========================================================

    /** 让镇民说一句**原文**（不走 lang key，调试命令用）。 */
    public static void sayRaw(Resident resident, String text) {
        if (!(resident.level() instanceof ServerLevel level) || text.isEmpty()) {
            return;
        }
        spawnDisplay(level, resident, Component.literal(text));
        BmfTown.LOGGER.info("[BmfTown][台词] {} →「{}」（命令）",
                resident.getName().getString(), text);
    }

    // ==========================================================
    //  事件入口
    // ==========================================================

    private static void handle(TownEvent event) {
        if (!(event.subject.level() instanceof ServerLevel level)) {
            return;
        }
        List<DialogRegistry.DialogLine> lines = DialogRegistry.matching(event);
        if (lines.isEmpty()) {
            return;
        }
        DialogRegistry.DialogLine line = DialogRegistry.pick(lines, level.getRandom());

        switch (event.type) {
            case EAT_FAVORED -> {
                // 即时：吃到爱吃的，冷却内或正在说话（嘴忙）直接跳过
                if (cooldownOk(event.subject, GROUP_BUSY, level.getGameTime())
                        && cooldownOk(event.subject, line.group(), level.getGameTime())) {
                    say(level, event.subject, line, event.item.getHoverName());
                    markCooldown(event.subject, line.group(), level.getGameTime(),
                            Config.DIALOG_EAT_COOLDOWN.get() * 20L);
                }
            }
            case STARVING, SLEEPLESS -> {
                // 即时：受苦线（挨饿/没睡——与记忆无关谁都喊，变体由 condition 区分）
                if (cooldownOk(event.subject, GROUP_BUSY, level.getGameTime())
                        && cooldownOk(event.subject, line.group(), level.getGameTime())) {
                    say(level, event.subject, line, event.item.getHoverName());
                    markCooldown(event.subject, line.group(), level.getGameTime(),
                            Config.DIALOG_SUFFER_COOLDOWN.get() * 20L);
                }
            }
            case JOB_HINT -> {
                // 即时：职业解锁线——**一次性事件（每镇一次），无 CD**：
                // 频率由"解锁即静默 + 调试 lock 才能重来"天然保证；
                // 嘴忙联动在 JobHints 层已查（这里 GROUP_BUSY 再查一次是防御）。
                // 占位符 = 看到的元件种类名（门槛保证恰好 3 种，台词三占位）。
                // ⚠ 此分支曾残留受苦线的 600 秒组冷却（共用 case 时代的遗产），
                //   导致 lock→unlock 密集测试时气泡被静默——已删，勿加回。
                if (cooldownOk(event.subject, GROUP_BUSY, level.getGameTime())) {
                    say(level, event.subject, line,
                            event.items.stream()
                                    .map(ItemStack::getHoverName)
                                    .toArray(net.minecraft.network.chat.Component[]::new));
                }
            }
            case REDSTONE_AMBIENT -> {
                // 即时：职业引导线（可重复不解锁——引导玩家往元件方向凑）。
                // 占位符 = 看到的红石块/矿名字。频率由 JobHints 的
                // 城镇×玩家游标（5 分钟）主控，这里只受嘴忙锁兜底。
                if (cooldownOk(event.subject, GROUP_BUSY, level.getGameTime())) {
                    say(level, event.subject, line, event.item.getHoverName());
                }
            }
            case WEATHER_CHANGED -> {
                // 相位筛选：只有 1/N 的镇民开口（N 可配，默认 3）
                int oneIn = Math.max(1, Config.DIALOG_WEATHER_ONE_IN_N.get());
                if (event.subject.getStatus().phaseOffset() % oneIn != 0) {
                    return;
                }
                if (!cooldownOk(event.subject, GROUP_BUSY, level.getGameTime())
                        || !cooldownOk(event.subject, line.group(), level.getGameTime())) {
                    return;
                }
                // 个人相位错峰：生成时刻 = 现在 + settleOffset % 错峰窗口
                long delay = event.subject.getStatus().phaseOffset()
                        % Config.DIALOG_STAGGER_TICKS.get();
                SCHEDULED.add(new ScheduledSay(event.subject, line,
                        event.item, level.getGameTime() + delay));
            }
        }
    }

    // ==========================================================
    //  每 tick：错峰到点生成 + 回收到期展示 + 孤儿清扫
    // ==========================================================

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        // 1. 错峰队列到点 → 生成展示
        //    requeue 模式：遍历中绝不往原列表写（哪怕 it.remove 后 add 也会触发 CME），
        //    未到点的条目先收进暂存列表，遍历结束后统一放回。
        if (!SCHEDULED.isEmpty()) {
            List<ScheduledSay> requeue = null;
            Iterator<ScheduledSay> it = SCHEDULED.iterator();
            while (it.hasNext()) {
                ScheduledSay say = it.next();
                it.remove();
                Resident resident = say.resident();
                if (!resident.isAlive()
                        || !(resident.level() instanceof ServerLevel level)) {
                    continue; // 人没了/换了维度：这趟台词作废
                }
                if (level.getGameTime() < say.dueTick()) {
                    if (requeue == null) {
                        requeue = new ArrayList<>();
                    }
                    requeue.add(say); // 还没到点：先暂存，遍历完统一放回
                    continue;
                }
                if (cooldownOk(resident, GROUP_BUSY, level.getGameTime())
                        && cooldownOk(resident, say.line().group(), level.getGameTime())) {
                    say(level, resident, say.line(), say.item().getHoverName());
                    markCooldown(resident, say.line().group(), level.getGameTime(),
                            Config.DIALOG_WEATHER_COOLDOWN.get() * 20L);
                }
            }
            if (requeue != null) {
                SCHEDULED.addAll(requeue);
            }
        }

        // 2. 到期展示实体回收（同样走 requeue 模式）；存活期内每 tick 跟随镇民头顶
        if (!DISCARDS.isEmpty()) {
            long now = event.getServer().overworld().getGameTime();
            List<PendingDiscard> requeue = null;
            Iterator<PendingDiscard> it = DISCARDS.iterator();
            while (it.hasNext()) {
                PendingDiscard pending = it.next();
                it.remove();
                if (now < pending.dueTick()) {
                    if (requeue == null) {
                        requeue = new ArrayList<>();
                    }
                    requeue.add(pending); // 还没到回收时刻：暂存
                    continue;
                }
                if (pending.display().isAlive()) {
                    pending.display().discard();
                }
            }
            if (requeue != null) {
                // 先跟随、后放回：text_display 位置 updateInterval=1 每 tick 同步，
                // 配合 teleport_duration 客户端插值，走动平滑不抽搐
                for (PendingDiscard pending : requeue) {
                    Resident resident = pending.resident();
                    if (resident.isAlive()
                            && pending.display().isAlive()
                            && pending.display().level() == resident.level()) {
                        pending.display().setPos(resident.getX(),
                                resident.getY() + resident.getBbHeight() + 0.55F,
                                resident.getZ());
                    }
                }
                DISCARDS.addAll(requeue);
            }
        }

        // 3. 孤儿清扫（重启没来得及回收的展示实体）：定期按标记 + 年龄扫一遍
        if (++sweepCooldown >= SWEEP_INTERVAL_TICKS) {
            sweepCooldown = 0;
            for (ServerLevel level : event.getServer().getAllLevels()) {
                for (var entity : level.getEntities().getAll()) {
                    if (entity.getTags().contains(DISPLAY_TAG)
                            && entity.tickCount > DISPLAY_MAX_AGE_TICKS) {
                        entity.discard();
                    }
                }
            }
        }
    }

    // ==========================================================
    //  生成展示实体 + 冷却
    // ==========================================================

    private static void say(ServerLevel level, Resident resident,
                            DialogRegistry.DialogLine line, Component... args) {
        Component text = (line.withItem() && args.length > 0)
                ? Component.translatable(line.key(), (Object[]) args)
                : Component.translatable(line.key());
        spawnDisplay(level, resident, text);
        BmfTown.LOGGER.info("[BmfTown][台词] {} →「{}」",
                resident.getName().getString(), text.getString());
    }

    private static void spawnDisplay(ServerLevel level, Resident resident, Component text) {
        DialogDisplay display = new DialogDisplay(level);
        display.setPos(resident.getX(),
                resident.getY() + resident.getBbHeight() + 0.55F, resident.getZ());
        display.loadText(text);
        display.addTag(DISPLAY_TAG);
        level.addFreshEntity(display);

        // 展示时长：固定 10 秒（用户定稿：关键信息要看得全）
        DISCARDS.add(new PendingDiscard(resident, display, level.getGameTime() + DISPLAY_TICKS));
        // 嘴忙锁：字没消完之前，这个镇民的其他台词全部跳过
        markCooldown(resident, GROUP_BUSY, level.getGameTime(), DISPLAY_TICKS);
    }

    /**
     * 该镇民当前嘴是否空闲（10 秒嘴忙锁）。职业线索在 dispatch 前查这个：
     * 嘴忙则本轮整轮放弃（不说话也不解锁）——保证"说话+解锁"同时发生，
     * 而不是台词被吞、公告却先飞出去。感知轮 5 秒一次，最多等一嘴的功夫。
     */
    public static boolean isMouthFree(Resident resident, long now) {
        return cooldownOk(resident, GROUP_BUSY, now);
    }

    private static boolean cooldownOk(Resident resident, String group, long now) {
        Map<String, Long> groups = COOLDOWNS.get(resident.getUUID());
        Long until = groups == null ? null : groups.get(group);
        return until == null || now >= until;
    }

    private static void markCooldown(Resident resident, String group, long now, long ticks) {
        COOLDOWNS.computeIfAbsent(resident.getUUID(), k -> new HashMap<>())
                .put(group, now + ticks);
    }
}
