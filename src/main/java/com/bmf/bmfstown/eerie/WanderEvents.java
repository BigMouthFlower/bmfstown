package com.bmf.bmfstown.eerie;

import com.bmf.bmfstown.ModDataComponents;
import com.bmf.bmfstown.ModItems;
import com.bmf.bmfstown.book.BookContent;
import com.bmf.bmfstown.book.BookDraft;
import com.bmf.bmfstown.book.FinishedBookItem;
import com.bmf.bmfstown.social.DialogEngine;
import com.bmf.bmfstown.unlock.UnlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * 闲暇随机事件框架（决策 #57：闲逛 →（概率）随机事件 →（子集概率）恐怖事件）。
 *
 * <h2>触发链与固定概率（#57 用户拍板"先固定"，调优后可挪 config）</h2>
 * <ol>
 *   <li><b>抽取点</b>：每段"自然游走"开始时（{@code LeisureGoal.pick()} 没随机到
 *       活动的兜底分支）掷一次 {@link #WANDER_EVENT_CHANCE}%；</li>
 *   <li><b>事件类型</b>：命中后按权重表抽类型——目前只登记恐怖线
 *       （{@link #HORROR_EVENT_WEIGHT}），其余权重即"无事发生"。
 *       新事件类型 = 加一个 EventType + 一个 dispatch 分支；</li>
 *   <li><b>恐怖线产出</b>：命中恐怖线后进 {@link HorrorLineState} 状态机
 *       ——单线锁定 + 全镇每日配额 {@link #HORROR_DAILY_CAP} 格（稀缺池：
 *       触发频繁×产出稀少），十卷写齐解锁、随机 {@link #VERIFY_MIN_DAYS}~
 *       +{@link #VERIFY_SPAN_DAYS} 日应验、应验后降极小概率（#54）。</li>
 * </ol>
 *
 * <p><b>表现三出口</b>（与写书同源，#57）：分卷手记进<b>登记图书馆</b>里离发现者
 * 最近的雕纹书架空位（没登记图书馆 / 区内无空位 → 留身上，再满掉脚边——绝不吞；
 * #74 登记制寻架）+ 公告栏 {@link TownBulletinData} + 头顶气泡
 * （{@link DialogEngine#sayDirect}，嘴忙就跳过）。
 */
public final class WanderEvents {

    // ==========================================================
    //  固定概率（#57 拍板：先固定；均为"每段游走/每次事件"口径）
    // ==========================================================

    /** 每段自然游走开始时，触发"随机事件"的概率（%）。 */
    public static final int WANDER_EVENT_CHANCE = 10;

    /** 事件类型权重表内的恐怖线权重（权重总和之外的余量 = 无事发生）。 */
    public static final int HORROR_EVENT_WEIGHT = 30;

    /** 全镇每日恐怖线推进配额（格/游戏日，稀缺池）。 */
    public static final int HORROR_DAILY_CAP = 1;

    /** 解锁→应验时窗下限（游戏日，#57 默认 2~5）。 */
    public static final int VERIFY_MIN_DAYS = 2;

    /** 应验时窗跨度：应验日 = 解锁日 + MIN + random(SPAN)。 */
    public static final int VERIFY_SPAN_DAYS = 4;

    // ==========================================================
    //  事件类型注册表（通用层：以后加类型只动这里 + dispatch）
    // ==========================================================

    /** 事件类型条目：id + 抽中权重。 */
    private record EventType(String id, int weight) {
    }

    /** 权重表（余量=无事发生）。恐怖线未应验权重 HORROR_EVENT_WEIGHT，应验线降 1/10。 */
    private static final List<EventType> TYPES = List.of(
            new EventType("horror", HORROR_EVENT_WEIGHT));

    // ==========================================================
    //  抽取入口（LeisureGoal 自然游走段开始时调用）
    // ==========================================================

    /**
     * 闲逛段开始（抽取入口）：应验懒检查 + 概率抽事件 + 分派。
     * 未命中/无事发生时开销 = 一到两次取随机。
     */
    public static void onWanderSegmentStart(com.bmf.bmfstown.entity.Resident resident,
                                            ServerLevel level) {
        UUID townId = resident.getIdentity().townId();
        if (townId == null) {
            return; // 无镇居民不参与（公告/线状态都是城镇粒度）
        }
        RandomSource random = level.getRandom();

        // 0. 应验懒检查（零 tick 成本：只在抽取点顺带跑）
        pollVerifications(resident, level, townId);

        // 1. 要不要发生随机事件
        if (random.nextInt(100) >= WANDER_EVENT_CHANCE) {
            return;
        }
        // 2. 抽事件类型（权重和之外的余量 = 无事发生）
        int total = TYPES.stream().mapToInt(EventType::weight).sum();
        int roll = random.nextInt(100);
        if (roll >= total) {
            return; // 无事
        }
        // 3. 按权重落点分派
        int acc = 0;
        for (EventType type : TYPES) {
            acc += type.weight();
            if (roll < acc) {
                dispatch(type.id(), resident, level, townId, random);
                return;
            }
        }
    }

    private static void dispatch(String typeId, com.bmf.bmfstown.entity.Resident resident,
                                 ServerLevel level, UUID townId, RandomSource random) {
        switch (typeId) {
            case "horror" -> horrorEvent(resident, level, townId, random);
            default -> { // 未知类型：注册表与 dispatch 不同步，暴露出来
                com.bmf.bmfstown.BmfTown.LOGGER.warn("[BmfTown][事件] 未分派的事件类型 {}", typeId);
            }
        }
    }

    // ==========================================================
    //  恐怖线事件（十部制推进 + 三出口表现）
    // ==========================================================

    /** GameTest 驱动口：跳过概率抽签，直接命中一次恐怖事件（含应验懒检查）。 */
    public static void forceHorrorEvent(com.bmf.bmfstown.entity.Resident resident,
                                        ServerLevel level) {
        UUID townId = resident.getIdentity().townId();
        if (townId == null) {
            return;
        }
        pollVerifications(resident, level, townId);
        horrorEvent(resident, level, townId, level.getRandom());
    }

    private static void horrorEvent(com.bmf.bmfstown.entity.Resident resident, ServerLevel level,
                                    UUID townId, RandomSource random) {
        HorrorLineState state = HorrorLineState.get(level);
        HorrorLineState.Advance advance = state.advance(level, townId, random);
        if (advance == null) {
            return; // 每日配额/无可抽线：这次事件落空（公告也不发——没发现就没故事）
        }

        String residentName = resident.getName().getString();
        TownBulletinData.get(level).post(level, townId, new TownBulletinData.Entry(
                level.getDayTime() / 24000L, !level.isDay(),
                TownBulletinData.KIND_SPOT, residentName,
                advance.line().id(), advance.chapter()));

        // 解锁瞬间（第 10 格）：解锁登记（隐藏解锁类）——正式解锁物等用户定稿
        if (advance.unlockedNow()) {
            UnlockState.get(level).unlockIfNew(level, townId, advance.line().unlockId());
        }

        // 写分卷手记 → 登记图书馆寻架（#74：只认登记的图书馆，不扫野）
        placeVolumeNote(resident, level, townId, advance);

        // 气泡（嘴忙锁拦下就算了——公告栏已经记了）
        DialogEngine.sayDirect(resident, Component.translatable(
                "dialog.bmfstown.horror_spot.1"));
    }

    /** 把第 N 卷手记（成书）放进登记图书馆里离发现者最近的雕纹书架空位。 */
    private static void placeVolumeNote(com.bmf.bmfstown.entity.Resident resident, ServerLevel level,
                                        UUID townId, HorrorLineState.Advance advance) {
        // 归类（决策 #58）：手记是隐藏解锁类的扩容——走 BookDraft.Unlock，
        // unlockId 编码 horror_<线>#<格>；第 10 格的"解锁登记"另用 horror_<线>
        String volumeId = FinishedBookItem.HORROR_PREFIX + advance.line().id()
                + "#" + advance.chapter();
        BookContent content = BookContent
                .startWriting(townId, resident.getName().getString(),
                        new BookDraft.Unlock(volumeId), 1L)
                .withProgressAdded(1L); // 手记即时成书（居民随走随记，不走学者写作台）
        ItemStack book = new ItemStack(ModItems.FINISHED_BOOK.get());
        book.set(ModDataComponents.BOOK_CONTENT.get(), content);

        BlockPos shelf = nearestLibraryShelfSlot(level, resident);
        if (shelf != null && level.getBlockEntity(shelf)
                instanceof ChiseledBookShelfBlockEntity be) {
            for (int slot = 0; slot < be.getContainerSize(); slot++) {
                if (be.getItem(slot).isEmpty()) {
                    be.setItem(slot, book);
                    return;
                }
            }
        }
        ItemStack over = resident.getInventory().addItem(book);
        if (!over.isEmpty()) {
            resident.spawnAtLocation(over);
        }
    }

    /**
     * 登记制寻架（决策 #74，2026-11-17 用户拍板）：手记归档<b>不扫野</b>——
     * 只认镇上登记的图书馆（WORK 选区），区内找离发现者最近的、有空位的
     * 雕纹书架（与 {@code LibraryReadActivity} 同款口径）。16 格半径扫描
     * 废弃：野外碰巧有架就入、"镇里图书馆就在眼前却因为超 16 格不入"都
     * 不对味；登记制才是功能区的正解。
     *
     * @return 目标书架坐标；没登记图书馆 / 区内没有空位雕纹书架 → null
     *         （手记留身上，绝不吞）
     */
    @Nullable
    private static BlockPos nearestLibraryShelfSlot(ServerLevel level,
                                                    com.bmf.bmfstown.entity.Resident resident) {
        UUID townId = resident.getIdentity().townId();
        if (townId == null) {
            return null;
        }
        var town = com.bmf.bmfstown.town.TownSaveData.get(level).findTown(townId.toString());
        if (town == null) {
            return null;
        }
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (var zone : town.zones(com.bmf.bmfstown.town.TownWandMode.WORK)) {
            for (BlockPos pos : com.bmf.bmfstown.town.TownZone.iterate(zone)) {
                if (!(level.getBlockEntity(pos) instanceof ChiseledBookShelfBlockEntity be)) {
                    continue;
                }
                boolean hasSpace = false;
                for (int slot = 0; slot < be.getContainerSize(); slot++) {
                    if (be.getItem(slot).isEmpty()) {
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
                    best = pos.immutable();
                }
            }
        }
        return best;
    }

    // ==========================================================
    //  应验（懒检查）
    // ==========================================================

    /** 到期的线登记应验 + 发公告。正式异象效果按线挂接处（内容定稿前为空）。 */
    private static void pollVerifications(com.bmf.bmfstown.entity.Resident resident,
                                          ServerLevel level, UUID townId) {
        for (HorrorLines.HorrorLine line : HorrorLineState.get(level)
                .pollVerifications(level, townId)) {
            TownBulletinData.get(level).post(level, townId, new TownBulletinData.Entry(
                    level.getDayTime() / 24000L, !level.isDay(),
                    TownBulletinData.KIND_VERIFY, resident.getName().getString(),
                    line.id(), 0));
            DialogEngine.sayDirect(resident, Component.translatable(
                    "dialog.bmfstown.horror_verify.1"));
            // TODO(内容定稿)：按 line.id() 挂接异象效果（草方块染菌丝/雷劈/……）
        }
    }

    private WanderEvents() {
    }
}
