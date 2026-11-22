package com.bmf.bmfstown.social;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.config.Config;
import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.entity.domain.EventType;
import com.bmf.bmfstown.entity.domain.SpeakTrigger;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntSupplier;

/**
 * 台词引擎（v0.9.1 表达层）：决定"谁、在什么时候、说哪句"。
 *
 * <p>展示方式：原版 {@code text_display} 实体——命名牌同款观感
 * （公告牌朝向 + 半透明背景板 + 自动换行），渲染完全由原版维护。
 * 生成时打上 {@link #DISPLAY_TAG} 标记，到期 discard；重进世界若有孤儿
 * （服务重启没来得及回收的）由定期清扫按标记 + 年龄回收。
 *
 * <p><b>两个入口（v0.16.8）</b>：
 * <ul>
 *   <li>{@link #speak} —— 心情事件的气泡（由 {@code Events.happen} 按声明表的
 *       {@code .bubble(topic)} 直调）。这里是唯一的"选句 + 策略"实现；</li>
 *   <li>{@link #handle} —— 纯表达世界事件（职业线索），仍走
 *       {@link TownEventBus}，内部同样委托 {@link #speak}。</li>
 * </ul>
 *
 * <p><b>策略集中在 {@link #RULES}</b>（相位筛选 / 错峰 / 冷却），不在结算层。
 * 嘴忙锁只影响表现不影响结算——心情照加、想法照记，只是话没说出口。
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

    /** 错峰队列：到点才生成展示实体（带模板参数与到点冷却，延迟期间参数不丢）。 */
    private record ScheduledSay(Resident resident, DialogRegistry.DialogLine line,
                                String[] args, long cooldownTicks, long dueTick) {
    }

    /** 回收队列：到点 discard 展示实体（附带镇民引用，存活期内每 tick 跟随头顶）。 */
    private record PendingDiscard(Resident resident, DialogDisplay display, long dueTick) {
    }

    /**
     * 单条触发键的说话策略（v0.16.8 从 switch 分支收成表）。
     *
     * <p>全部走 IntSupplier 延迟读 Config，保持游戏内可调不断电。
     *
     * @param oneInN       相位筛选：settleOffset % N == 0 的镇民才开口（1 = 不限）
     * @param staggerTicks 错峰窗口（tick）；0 = 立刻说
     * @param cooldownTicks 该组说完后的禁言时长（tick）；0 = 不打冷却（按镇民记账）
     *
     * <p>频次上限（个人每日/城镇级）属于<b>事件声明</b>（{@code EventSpec.daily} /
     * {@code EventSpec.townCooldown}），不在这里重复一遍。
     */
    private record SayRule(IntSupplier oneInN, IntSupplier staggerTicks, IntSupplier cooldownTicks) {
    }

    private static final List<ScheduledSay> SCHEDULED = new ArrayList<>();
    private static final List<PendingDiscard> DISCARDS = new ArrayList<>();
    /** 冷却记账：镇民 UUID → (冷却组 → 禁言到 gameTime)。 */
    private static final Map<UUID, Map<String, Long>> COOLDOWNS = new HashMap<>();

    /** 触发键 → 说话策略。未登记的触发键一律不开口（防新事件忘配策略静默乱说）。 */
    private static final Map<SpeakTrigger, SayRule> RULES = new HashMap<>();

    private static int sweepCooldown = 0;

    static {
        // 纯表达世界事件（职业线索）仍走总线；心情事件已由 Events 直调 speak
        TownEventBus.register(DialogEngine::handle);

        RULES.put(TownEventType.JOB_HINT,
                new SayRule(() -> 1, () -> 0, () -> 0));
        RULES.put(TownEventType.REDSTONE_AMBIENT,
                new SayRule(() -> 1, () -> 0, () -> 0));
        RULES.put(EventType.ATE_FAVORED,
                new SayRule(() -> 1, () -> 0, () -> Config.DIALOG_EAT_COOLDOWN.get() * 20));
        RULES.put(EventType.STARVING,
                new SayRule(() -> 1, () -> 0, () -> Config.DIALOG_SUFFER_COOLDOWN.get() * 20));
        RULES.put(EventType.NO_SLEEP,
                new SayRule(() -> 1, () -> 0, () -> Config.DIALOG_SUFFER_COOLDOWN.get() * 20));
        RULES.put(EventType.WEATHER_NEUTRAL,
                new SayRule(() -> Math.max(1, Config.DIALOG_WEATHER_ONE_IN_N.get()),
                        Config.DIALOG_STAGGER_TICKS::get,
                        () -> Config.DIALOG_WEATHER_COOLDOWN.get() * 20));
        // 装备评判（2026-11-22 归位）：选句按嫉妒轴分池（condition）；
        // 频次（个人每日 + 城镇冷却）在 EventSpecs 的事件声明里，这里不再限。
        RULES.put(EventType.GEAR_SEEN,
                new SayRule(() -> 1, () -> 0, () -> 0));
        // 老滚 5 彩蛋：频次全在事件声明里（个人每日 + 城镇冷却），这里不限
        RULES.put(EventType.KNEE_ARROW,
                new SayRule(() -> 1, () -> 0, () -> 0));
        RULES.put(EventType.WOKE_UP,
                new SayRule(() -> 1, () -> 0, () -> 0));
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

    /**
     * 外部自带节流的组件台词直发（v0.14 嫉妒装备评判用）：
     * 调用方负责自己的频率控制（全镇冷却/每日上限），这里只查"嘴忙锁"
     * 防止两条字叠同人。头顶还有字没消完时静默跳过。
     *
     * @return true = 真的说出来了（调用方可据此记账）；false = 嘴忙被拦。
     */
    public static boolean sayDirect(Resident resident, net.minecraft.network.chat.Component text) {
        if (!(resident.level() instanceof ServerLevel level) || text == null) {
            return false;
        }
        if (!cooldownOk(resident, GROUP_BUSY, level.getGameTime())) {
            return false;
        }
        spawnDisplay(level, resident, text);
        return true;
    }

    /**
     * 心情事件的气泡入口（v0.16.8）：按 触发键 + 变体 选台词，走 {@link #RULES} 策略。
     *
     * <p>调用方（{@code Events.happen}）只表达"该说话"，其余全部在这里决定。
     *
     * @param subject 说话人
     * @param trigger 事件键（{@code EventType}）
     * @param variant 变体（天气 = {@code WeatherPref.name()}，如 {@code "CLEAR"}）；null = 不限
     * @param args    模板参数（物品名等），可空
     */
    public static void speak(Resident subject, SpeakTrigger trigger, String variant, String... args) {
        if (!(subject.level() instanceof ServerLevel level)) {
            return;
        }
        SayRule rule = RULES.get(trigger);
        if (rule == null) {
            BmfTown.LOGGER.warn("[BmfTown][台词] 触发键未配策略，已静默：{}", trigger.id());
            return;
        }
        List<DialogRegistry.DialogLine> lines = DialogRegistry.matching(trigger, variant, subject);
        if (lines.isEmpty()) {
            return; // 这个变体没有台词（如雨天/雷暴）——正常静默
        }
        long now = level.getGameTime();

        // 1. 相位筛选：稳定身份（settleOffset），让同一批镇民长期"爱聊这类事"
        int oneIn = Math.max(1, rule.oneInN().getAsInt());
        if (oneIn > 1 && subject.getStatus().phaseOffset() % oneIn != 0) {
            return;
        }
        // 2. 嘴忙锁：头顶有字就不开口（只影响表现，不影响已完成的结算）
        if (!cooldownOk(subject, GROUP_BUSY, now)) {
            return;
        }

        DialogRegistry.DialogLine line = DialogRegistry.pick(lines, level.getRandom());
        // 3. 组冷却（"eat"/"weather"/"starving"…同组共享）
        if (!cooldownOk(subject, line.group(), now)) {
            return;
        }

        String[] clean = args == null ? new String[0]
                : Arrays.stream(args).filter(Objects::nonNull).toArray(String[]::new);
        long cooldown = rule.cooldownTicks().getAsInt();

        // 4. 错峰：全城同时感知到的事情（天气）按个人相位延迟开口
        long stagger = rule.staggerTicks().getAsInt();
        if (stagger > 0) {
            long delay = subject.getStatus().phaseOffset() % stagger;
            SCHEDULED.add(new ScheduledSay(subject, line, clean, cooldown, now + delay));
            return;
        }
        say(level, subject, line, clean);
        if (cooldown > 0) {
            markCooldown(subject, line.group(), now, cooldown);
        }
    }

    // ==========================================================
    //  纯表达世界事件入口（职业线索）
    // ==========================================================

    private static void handle(TownEvent event) {
        switch (event.type) {
            case JOB_HINT -> speak(event.subject, event.type, null,
                    event.items.stream()
                            .map(s -> s.getHoverName().getString())
                            .toArray(String[]::new));
            case REDSTONE_AMBIENT -> speak(event.subject, event.type, null,
                    event.item.getHoverName().getString());
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
                long now = level.getGameTime();
                if (cooldownOk(resident, GROUP_BUSY, now)
                        && cooldownOk(resident, say.line().group(), now)) {
                    say(level, resident, say.line(), say.args());
                    if (say.cooldownTicks() > 0) {
                        markCooldown(resident, say.line().group(), now, say.cooldownTicks());
                    }
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
                            DialogRegistry.DialogLine line, String... args) {
        Component text;
        if (line.withItem() && args.length > 0) {
            Component[] filled = new Component[args.length];
            for (int i = 0; i < args.length; i++) {
                filled[i] = Component.literal(args[i]);
            }
            text = Component.translatable(line.key(), (Object[]) filled);
        } else {
            text = Component.translatable(line.key());
        }
        spawnDisplay(level, resident, text);
        BmfTown.LOGGER.debug("[BmfTown][台词] {} →「{}」",
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

    /** 该触发键是否配了说话策略（契约测试用：防"声明了却永远不开口"）。 */
    public static boolean hasRule(SpeakTrigger trigger) {
        return RULES.containsKey(trigger);
    }
}