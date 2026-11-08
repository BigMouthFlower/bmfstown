package com.bmf.bmfstown.entity;

import com.bmf.bmfstown.config.Config;
import com.bmf.bmfstown.entity.profile.PersonalityAxis;
import com.bmf.bmfstown.social.DialogEngine;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 嫉妒轴·玩家装备评判（v0.14，决策 #44）：镇民看到玩家身上的"好装备"
 * （钻石/下界合金材质或任一附魔）时的双通道反应——
 * <ul>
 *   <li><b>心情</b>：仅轴值 &gt;80 生效，{@code −3·(v−80)/20}，每天上限见配置（2026-11-08 定稿阈值）；</li>
 *   <li><b>台词</b>：轴值 ≥60 出"嗤之以鼻"池、&lt;60 出"夸奖"池（含中性区，NPC 不玩高冷），
 *       占位符填实际读到的首件触发装备名。</li>
 * </ul>
 *
 * <h2>城镇级节流（用户定稿：台词不能频发）</h2>
 * 全镇共享一个冷却游标（默认 6000t = 5 分钟）+ 单 NPC 每游戏日一条；
 * 冷却不落档（重进世界重置可接受，与 DialogEngine 冷却表同语义）。
 *
 * <p>挂在 {@code Status.tickSenses} 的 100t 感知节拍上，零轮询开销。
 */
public final class GearJudgeHandler {

    /** 台词池：嗤之以鼻（轴值 ≥60）。 */
    private static final String[] MOCK_KEYS = {
            "dialog.bmfstown.gear_mock.1",
            "dialog.bmfstown.gear_mock.2",
            "dialog.bmfstown.gear_mock.3",
    };

    /** 台词池：夸奖（轴值 <60，含中性区——淡泊的人真心觉得好）。 */
    private static final String[] PRAISE_KEYS = {
            "dialog.bmfstown.gear_praise.1",
            "dialog.bmfstown.gear_praise.2",
            "dialog.bmfstown.gear_praise.3",
    };

    /** 全镇级冷却游标：上次任何人开金口是哪个 gameTime。 */
    private static long townLastSpeakAt = Long.MIN_VALUE;

    /** 单 NPC 台词日配额记账：UUID → [天号, 已说条数]。 */
    private static final Map<UUID, int[]> SPEAK_DAY = new HashMap<>();

    private GearJudgeHandler() {
    }

    /**
     * 感知入口（Status.tickSenses 每 100t 调一次）。
     */
    public static void sense(Resident resident, ServerLevel level) {
        if (!resident.isAlive()) {
            return;
        }
        int envy = resident.getStatus().axis(resident, level, PersonalityAxis.ENVY);
        double range = Config.AXIS_GEAR_RANGE.get();

        ServerPlayer seen = null;
        ItemStack trigger = null;
        for (ServerPlayer player : level.getEntitiesOfClass(ServerPlayer.class,
                resident.getBoundingBox().inflate(range))) {
            if (!player.isAlive() || !resident.hasLineOfSight(player)) {
                continue;
            }
            ItemStack found = firstGoodGear(player);
            if (found != null) {
                seen = player;
                trigger = found;
                break;
            }
        }
        if (seen == null) {
            return;
        }

        // 心情通道：惩罚仅 v>80（notePlayerGear 内部按轴值/节流自挡）
        resident.getStatus().notePlayerGear(resident, level);

        // 台词通道：城镇级节流 + 单人日配额
        trySpeak(resident, level, envy, trigger);
    }

    /** 台词判定与节流。 */
    private static void trySpeak(Resident resident, ServerLevel level, int envy, ItemStack trigger) {
        long now = level.getGameTime();
        long cooldown = Config.AXIS_GEAR_TOWN_COOLDOWN.get();
        if (townLastSpeakAt != Long.MIN_VALUE && now >= townLastSpeakAt
                && now - townLastSpeakAt < cooldown) {
            return; // 全镇冷却中（now < townLastSpeakAt = 时间回拨，放行）
        }
        int today = (int) (level.getDayTime() / 24000L);
        int[] quota = SPEAK_DAY.computeIfAbsent(resident.getUUID(), k -> new int[]{-1, 0});
        if (quota[0] != today) {
            quota[0] = today;
            quota[1] = 0;
        }
        if (quota[1] >= 1) {
            return; // 单 NPC 每游戏日 1 条（用户定稿口径）
        }

        String key;
        if (envy >= 60) {
            key = MOCK_KEYS[level.getRandom().nextInt(MOCK_KEYS.length)];
        } else {
            key = PRAISE_KEYS[level.getRandom().nextInt(PRAISE_KEYS.length)];
        }
        boolean spoken = DialogEngine.sayDirect(resident,
                Component.translatable(key, trigger.getHoverName()));
        if (!spoken) {
            return; // 嘴忙锁拦截：不算说话，下轮感知再试
        }
        townLastSpeakAt = now;
        quota[1]++;
    }

    /**
     * 玩家是否穿着/拿着"好装备"：盔甲四槽 + 主副手里，
     * 材质名含 diamond/netherite，或任一件带附魔 → 返回首件触发物；否则 null。
     */
    private static ItemStack firstGoodGear(ServerPlayer player) {
        for (EquipmentSlot slot : new EquipmentSlot[]{
                EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String path = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).getPath();
            if (path.contains("diamond") || path.contains("netherite")) {
                return stack;
            }
            if (stack.isEnchanted()) {
                return stack;
            }
        }
        return null;
    }
}
