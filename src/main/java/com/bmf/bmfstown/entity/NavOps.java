package com.bmf.bmfstown.entity;

import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 居民寻路统一口径（2026-11-18 全局化修复，起因：酒保顶着挡路的一排酒桶
 * 无限重发寻路卡死）。
 *
 * <p>病根是各 Goal 重复了 25 处的同一段模式：
 * {@code 没到位 → if(isDone) 重发寻路 → 永不放弃}——目标被围死时寻路只给
 * "能走的最近点"，居民走到墙边 nav 完成、再重发、再走到墙边，死循环。
 *
 * <p>本类提供统一三段式：
 * <ol>
 *   <li><b>有限重试</b>——nav 完成却没到位时推一把，两次推进隔 20t
 *       （寻路失败时 isDone 每 tick 都是 true，不隔开就是每 tick 重寻路）；
 *       连续 {@value #MAX_ATTEMPTS} 次到不了判 STUCK；</li>
 *   <li><b>放弃本单</b>——调用方收到 STUCK 后按各自语义收尾
 *       （货回窖/抱货待机/空手回扫），不再无脑重试；</li>
 *   <li><b>目标拉黑</b>——STUCK 的坐标 10 秒内 {@link #blocked} 返回 true，
 *       各 Goal 选点（挑桶/挑箱/挑工位）过滤掉，换别的目标；
 *       到期自动解除（玩家拆了障碍能自愈）。</li>
 * </ol>
 *
 * <p>拉黑按居民记（WeakHashMap），跨 Goal 共享：酒保到不了的水源站，
 * 炼金工也不会去撞。战士巡逻随机走点、Resident 觅食/睡觉（已有重试上限
 * 与超时兜底）不走本类。
 */
public final class NavOps {

    private static final org.slf4j.Logger LOGGER =
            com.mojang.logging.LogUtils.getLogger();

    private NavOps() {
    }

    /** approach 三态：到位 / 还在路上 / 重试烧完（调用方须放弃本单）。 */
    public enum Nav {
        ARRIVED, MOVING, STUCK
    }

    /** nav 完成却没到位时的最大推进次数（次次隔 RETRY_TICKS）。 */
    static final int MAX_ATTEMPTS = 3;
    /** 两次推进的最小间隔 tick。 */
    static final int RETRY_TICKS = 20;
    /** 路径"进行中"却没挪窝多久判卡死（3 秒——进度验收，2026-11-19 实测）。 */
    static final int STALL_TICKS = 60;
    /** 落脚点路径验证缓存有效期 tick（GO_KEG 每 tick 重选点，不能每 tick createPath）。 */
    static final long PATH_OK_TTL = 60L;
    /** STUCK 目标首次拉黑时长 tick（10 秒）。 */
    static final long BLOCK_TICKS = 200L;
    /** 同一目标反复 STUCK 时拉黑翻倍的封顶（5 分钟——永久堵死的目标别每 10 秒撞一次）。 */
    static final long BLOCK_TICKS_MAX = 6000L;

    private static final Map<Resident, St> STATES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final class St {
        BlockPos navTarget;      // 当前推进目标（换目标即清零计数）
        int attempts;
        long nextAttemptAt;      // 节流：寻路失败 isDone 恒 true，不能每 tick 推
        BlockPos lastPos;        // 进度验收：上次见到的脚下位置
        long stallSince;         // 进度验收：开始原地不动（没挪窝）的起始时刻
        /** pos → [拉黑到期时刻, 本次拉黑时长, 连续失败次数]（递增退避用）。 */
        final Map<Long, long[]> blocked = new HashMap<>();
        /**
         * 楔死格（身体物理卡进去的缝）：唯一有资格拦"路径路过"的拉黑级别。
         * 2026-11-20 实测"酒桶区雪崩"：到不了/选点拒绝级别的拉黑若也拦路过，
         * 桶排（寻路器爱抄桶顶近道）被拉黑几个格后，周围所有路径全穿它们
         * →全部判不可达→一个 tick 拉黑 23 个目标→整个桶区锁死"无适配桶"。
         * 站上去没事、只是到不了的格，拦目标就够了。
         */
        final java.util.Set<Long> wedges = new java.util.HashSet<>();
        /** pos → 验证"路径可达"的时刻（{@value #PATH_OK_TTL} tick 内免重复寻路）。 */
        final Map<Long, Long> pathOk = new HashMap<>();
    }

    private static St state(Resident r) {
        return STATES.computeIfAbsent(r, k -> new St());
    }

    /**
     * 到位判定 + 走路。已在 {@code range} 直线距离内<b>且视线无阻挡</b> →
     * 停导航返 ARRIVED；拉黑中的目标直接 STUCK（不走路、不烧重试——认单在先
     * 的旧目标也不许再撞）；否则 nav 完成（含寻路失败）时按节流推进，
     * 烧满次数返 STUCK 并拉黑目标（同一目标反复到不了则拉黑时长翻倍递增，
     * 封顶 5 分钟）。
     *
     * <p>视线验收（2026-11-19 实测）：纯直线距离会把"隔着酒桶排站"误判成
     * 到位——ARRIVED 一到就 stop() 导航，把回城/绕路全部掐断，居民永远绕不进
     * 边上的空位。射线被<b>别的</b>方块挡住=没到位，继续走（视线终点打在目标
     * 方块本身上=正常贴身，算到位）。
     *
     * <p>同一目标连续调用才累计；换了目标（含同坐标新任务）计数清零。
     */
    public static Nav approach(Resident r, BlockPos target, double range, double speed) {
        return approach(r, target, range, speed, Integer.MAX_VALUE);
    }

    /**
     * 同上，{@code maxDyAbove} 给"到位"加 Y 门槛：居民脚 Y 超出目标
     * {@code maxDyAbove} 格时<b>不算到位</b>（2026-11-20 实测"封桶顶酒保站
     * 盖板上隔着交互、禁上方也没变化"的真身——人已在盖板上时，侧面落脚点
     * 距离 2~3 格、视线通畅，纯距离判定直接 ARRIVED 短路，永远不触发走下
     * 去的导航；选点层的禁上方只管新选点，管不住"人已经在上面"）。桶类
     * 交互传 0：必须真站到落脚点那一层或更低；其余调用不传=无门槛。
     */
    public static Nav approach(Resident r, BlockPos target, double range,
                               double speed, int maxDyAbove) {
        if (r.blockPosition().closerThan(target, range) && lineClear(r, target)
                && r.blockPosition().getY() - target.getY() <= maxDyAbove) {
            r.getNavigation().stop();
            St s = state(r);
            s.navTarget = null;
            s.attempts = 0;
            s.lastPos = null;
            s.blocked.remove(target.asLong()); // 到得了就解除历史拉黑（障碍拆了自愈）
            return Nav.ARRIVED;
        }
        St s = state(r);
        if (blocked(r, target)) {
            return Nav.STUCK; // 拉黑期内直拒：不走路、不计次，等选点换目标
        }
        long now = r.level().getGameTime();
        if (!target.equals(s.navTarget)) {
            s.navTarget = target.immutable();
            s.attempts = 0;
            s.lastPos = null;
            s.stallSince = now;
        }
        // 进度验收（2026-11-19 实测"阿灯一动不动"）：只认 isDone 会漏掉
        // "路径进行中但物理卡死"——isDone 永不真=永远 MOVING 静默卡死。
        // 3 秒没挪窝 = 卡死：掐掉路径并按节流重新推进（与"nav 完成却没
        // 到位"同一口锅，烧满 MAX_ATTEMPTS 判 STUCK）。
        BlockPos here = r.blockPosition();
        if (!r.getNavigation().isDone()) {
            if (!here.equals(s.lastPos)) {
                s.lastPos = here;
                s.stallSince = now;
                return Nav.MOVING; // 还在走，不干预
            }
            if (now - s.stallSince < STALL_TICKS) {
                return Nav.MOVING; // 静止未满 3 秒，再观察
            }
            r.getNavigation().stop(); // 掐掉卡死的路径，落进下面的推进分支重寻路
            s.stallSince = now;
        }
        if (now >= s.nextAttemptAt) {
            if (s.attempts >= MAX_ATTEMPTS) {
                s.attempts = 0;
                s.navTarget = null;
                // 递增退避（2026-11-19 改版）：按"连续失败次数"翻倍。旧版要求
                // "上次拉黑到期后 11 秒内再失败"——掐掉-换点循环里永远赶不上
                // 这个窗口，拉黑永远只有 10 秒，堵死的目标每 25 秒被撞一次。
                long[] prev = s.blocked.get(target.asLong());
                int seq = 0;
                if (prev != null && now - prev[0] <= BLOCK_TICKS_MAX * 2L) {
                    seq = (int) prev[2];
                }
                long len = Math.min(BLOCK_TICKS << Math.min(seq, 5), BLOCK_TICKS_MAX);
                s.blocked.put(target.asLong(), new long[]{now + len, len, seq + 1});
                LOGGER.debug("[BmfTown][寻路] {} 到不了 {}（{} 次推进失败）——放弃本单，{} 秒内不再选它，挑别的目标",
                        r.getName().getString(), target.toShortString(),
                        MAX_ATTEMPTS, len / 20);
                return Nav.STUCK;
            }
            r.getNavigation().moveTo(target.getX() + 0.5D, target.getY(),
                    target.getZ() + 0.5D, speed);
            s.attempts++;
            s.nextAttemptAt = now + RETRY_TICKS;
            // 路线审计（2026-11-19 实测"传送完 5 秒楔回同一缝"二轮）：传送后
            // Goal 缓存的旧目标<b>绕过选点器</b>直发 moveTo——spotNear 的拒收
            // 只管新选点，管不住缓存目标；路线照穿楔死缝把人领回去。新发的
            // 路径中途落在拉黑格上的：立刻掐掉（烧掉这次推进，重试烧满走
            // 既有 STUCK+拉黑流程，Goal 换目标）。
            var issued = r.getNavigation().getPath();
            if (issued != null && routeCrossesBlocked(r, issued)) {
                r.getNavigation().stop();
            }
        }
        return Nav.MOVING;
    }

    /**
     * 路径中途是否落在拉黑格上（起点=当前位置豁免，人可能正站在缝里）。
     * 2026-11-19 实测：楔死缝被拉黑后，寻路器照样把缝当<b>路过点</b>生成
     * "完整可达"路径——终点可达≠能走完。
     */
    /**
     * 路径中途是否落在<b>楔死格</b>上（起点=当前位置豁免，人可能正站在缝里）。
     * 2026-11-19 实测：楔死缝被拉黑后，寻路器照样把缝当<b>路过点</b>生成
     * "完整可达"路径——终点可达≠能走完。
     *
     * <p>2026-11-20 收窄：只拦楔死级拉黑（{@link #markWedge}），普通"到不了"
     * 拉黑不拦路过——桶顶这类"到不了但走过去没事"的格被普通拉黑后，拦路过
     * 会把桶排近道全堵死（雪崩链见 {@link St#wedges}）。
     */
    private static boolean routeCrossesBlocked(Resident r,
            net.minecraft.world.level.pathfinder.Path path) {
        BlockPos from = r.blockPosition();
        St s = state(r);
        long now = r.level().getGameTime();
        for (int i = 0; i < path.getNodeCount(); i++) {
            BlockPos n = path.getNode(i).asBlockPos();
            if (n.equals(from)) {
                continue; // 起点豁免：人可能正站在楔死缝里等脱困
            }
            long key = n.asLong();
            if (s.wedges.contains(key)) {
                long[] b = s.blocked.get(key);
                if (b != null && now <= b[0]) {
                    return true;
                }
                s.wedges.remove(key); // 楔死拉黑已到期：顺手清标记
            }
        }
        return false;
    }

    /**
     * 目标是否在寻路黑名单内（选点过滤用；到期自动解除）。
     * 注意：到期只是"不再拦"，<b>不删记录</b>——递增退避要靠历史失败次数
     * （2026-11-19 修复：到期即删会把连续失败史抹掉，拉黑永远 10 秒不翻倍）；
     * 记录只在目标到得了（ARRIVED，障碍拆了自愈）时清除。
     */
    public static boolean blocked(Resident r, BlockPos pos) {
        St s = STATES.get(r);
        if (s == null) {
            return false;
        }
        long[] b = s.blocked.get(pos.asLong());
        return b != null && r.level().getGameTime() <= b[0];
    }

    /**
     * 显式拉黑一个目标（递增退避同 approach 判 STUCK 的口径）。
     * 用途：站点（水源站等）周围所有落脚点都失败时整站拉黑，让
     * 选点器立刻换下一站，而不是等黑名单到期再撞同一堵墙。
     */
    public static void blacklist(Resident r, BlockPos target) {
        blacklist(r, target, 0L);
    }

    /**
     * 楔死级拉黑：除"不再当目标"外，还拦<b>路径路过</b>（routeCrossesBlocked
     * 只看这一级）。仅用于身体物理卡进去的缝——普通"到不了"的格站上去没事，
     * 拦路过会把桶排近道之类的必经之路全堵死（雪崩链见 {@link St#wedges}）。
     */
    public static void markWedge(Resident r, BlockPos target, long ticks) {
        blacklist(r, target, ticks);
        state(r).wedges.add(target.asLong());
    }

    /**
     * 显式拉黑：{@code ticks} 为 0 走标准递增退避（10 秒起步翻倍封顶
     * 5 分钟），否则用给定时长。楔死传送用 60 秒——楔死点是确定性
     * 陷阱（桶旁 1/16 缝/围挡走廊），10 秒后照选照楔。
     */
    public static void blacklist(Resident r, BlockPos target, long ticks) {
        St s = state(r);
        long now = r.level().getGameTime();
        long[] prev = s.blocked.get(target.asLong());
        int seq = 0;
        if (prev != null && now - prev[0] <= BLOCK_TICKS_MAX * 2L) {
            seq = (int) prev[2];
        }
        long len = ticks > 0
                ? ticks
                : Math.min(BLOCK_TICKS << Math.min(seq, 5), BLOCK_TICKS_MAX);
        s.blocked.put(target.asLong(), new long[]{now + len, len, seq + 1});
        s.pathOk.clear(); // 可达缓存立即失效：新拉黑可能让原有路径变成"穿缝必楔"
        LOGGER.debug("[BmfTown][寻路] {} 的目标 {} 暂时放弃（{} 秒内不再选它）",
                r.getName().getString(), target.toShortString(), len / 20);
    }

    /**
     * 眼睛到目标中心的视线是否畅通：MISS（全程空地）或命中目标方块本身
     * （贴身正常）算通；命中<b>别的</b>方块（隔着桶排/墙）算不通。
     */
    private static boolean lineClear(Resident r, BlockPos target) {
        var hit = r.level().clip(new net.minecraft.world.level.ClipContext(
                r.getEyePosition(),
                net.minecraft.world.phys.Vec3.atCenterOf(target),
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, r));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS
                || hit.getBlockPos().equals(target);
    }

    /** 可站立判定：脚下实心、本格与头顶无碰撞。 */
    private static boolean isStandable(net.minecraft.world.level.Level level, BlockPos p) {
        return !level.getBlockState(p.below())
                .getCollisionShape(level, p.below()).isEmpty()
                && level.getBlockState(p)
                .getCollisionShape(level, p).isEmpty()
                && level.getBlockState(p.above())
                .getCollisionShape(level, p.above()).isEmpty();
    }

    /**
     * 交互站点（水源站等）旁的最佳<b>落脚点</b>：可站立、未被拉黑、
     * 离站 ≤ {@code interactRange} 格；按<b>离居民</b>最近挑。
     * 找不到返回 null——调用方应整站拉黑换下一站。
     *
     * <p>背景（2026-11-19 实测）：直接寻路到水源方块/含水树叶本身，
     * 路径常是退化的（目标在箱子/酒桶排围挡后，部分路径把人推进
     * 方块缝楔死），永远够不到交互范围。改寻"站旁能站的格子"——
     * 寻路器对可站立格的处理远好于对实体方块本身；到站后装水是
     * 模拟记账，不需要真实点击面。
     *
     * <p>站点<b>正上方</b>的格不是候选（2026-11-19 步高 1.0 实测：
     * 酒保迈上桶顶"就地干活"=站桶顶转圈，且路径从桶顶出发易退化）。
     *
     * <p>二轮补刀（2026-11-19 实测"每次取水必靠卡死兜底"）：光"能站"
     * 还不够——几何最近的落脚点可能<b>到不了</b>（退化部分路径烧完
     * 重试才 STUCK，楔死判定先到，用户看到的永远是传送救场）。
     * 现在候选按离居民距离逐个做 {@link #pathReaches} 终点验证：
     * 首个"路径终点=落点"的候选胜出；验证不过的当场拉黑，下 tick
     * 不再撞同一个死点。验证结果缓存 {@value #PATH_OK_TTL} tick。
     */
    @Nullable
    public static BlockPos spotNear(net.minecraft.world.level.Level level, Resident r,
                                    BlockPos station, int xzRadius, int yBand,
                                    double interactRange) {
        return spotNear(level, r, station, xzRadius, yBand, interactRange, true);
    }

    /**
     * 同上，{@code allowAbove=false} 时禁止任何<b>高于站点</b>的落点
     * （2026-11-20 实测：玩家用石台阶封桶顶测试稳定性，酒保反而站上盖板
     * 隔着方块交互——yBand 内 dy≥+1 的候选把盖板顶当成了工作位，步高 1.0
     * 一步迈上去。桶类交互正解=站在桶侧同层或更低（桶垫高在台上时脚下
     * dy=-1 也合法），顶面永远不是工作位）。
     */
    public static BlockPos spotNear(net.minecraft.world.level.Level level, Resident r,
                                    BlockPos station, int xzRadius, int yBand,
                                    double interactRange, boolean allowAbove) {
        BlockPos from = r.blockPosition();
        double rangeSq = interactRange * interactRange;
        java.util.List<BlockPos> candidates = new java.util.ArrayList<>();
        for (int dx = -xzRadius; dx <= xzRadius; dx++) {
            for (int dz = -xzRadius; dz <= xzRadius; dz++) {
                for (int dy = -yBand; dy <= yBand; dy++) {
                    BlockPos p = station.offset(dx, dy, dz);
                    if (p.equals(station)
                            || (p.getX() == station.getX() && p.getZ() == station.getZ())
                            || (!allowAbove && p.getY() > station.getY())
                            || p.distSqr(station) > rangeSq
                            || blocked(r, p)
                            || !isStandable(level, p)) {
                        continue;
                    }
                    candidates.add(p);
                }
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(java.util.Comparator
                .comparingInt((BlockPos p) -> Math.abs(p.getY() - station.getY()))
                .thenComparingDouble(p -> p.distSqr(from)));
        St s = state(r);
        for (BlockPos p : candidates) {
            int verdict = pathReaches(r, p, s);
            if (verdict == REACH_ROUTE_BLOCKED) {
                // 路径穿拉黑格≠目标本身到不了（2026-11-20 雪崩根因：桶排近道
                // 上的拉黑格让周围候选全"路径被拒"，逐个拉黑=一个 tick 把整个
                // 桶区锁死）。只跳过本轮，不拉黑——楔死格到期后自然恢复。
                continue;
            }
            if (verdict == REACH_DEAD_END) {
                // 终点确实到不了：当场拉黑，选点级联不再每轮从同一个死点烧起
                blacklist(r, p);
                continue;
            }
            return p;
        }
        return null;
    }

    /** pathReaches 判定结果：可达。 */
    private static final int REACH_OK = 0;
    /** pathReaches 判定结果：终点到不了（寻路器只给退化部分路径/无路径）。 */
    private static final int REACH_DEAD_END = 1;
    /** pathReaches 判定结果：终点可达但路径穿楔死格（目标是好的，绕不开而已）。 */
    private static final int REACH_ROUTE_BLOCKED = 2;

    /**
     * 居民当前能否<b>寻路走到</b> {@code p}：{@code createPath} 的终点必须
     * 就是 p 本身（或贴邻一格）——vanilla 寻路器对围死目标给的是"最近能走
     * 点"的<b>部分路径</b>（非 null！），只看非 null 会把退化路径当可达。
     * 站在自己所在格直接算可达（ARRIVED 短路，别寻路）。结果缓存
     * {@value #PATH_OK_TTL} tick（GO_KEG 每 tick 调 spotNear，不能每 tick
     * createPath）。返回 {@link #REACH_OK}/{@link #REACH_DEAD_END}/
     * {@link #REACH_ROUTE_BLOCKED} 三态——调用方据此决定拉黑与否。
     */
    private static int pathReaches(Resident r, BlockPos p, St s) {
        if (p.equals(r.blockPosition())) {
            return REACH_OK; // 人已在格上：ARRIVED 短路，寻路器对自身坐标反而会退化
        }
        long now = r.level().getGameTime();
        Long ok = s.pathOk.get(p.asLong());
        if (ok != null && now - ok < PATH_OK_TTL) {
            return REACH_OK;
        }
        net.minecraft.world.level.pathfinder.Path path =
                r.getNavigation().createPath(p, 0);
        net.minecraft.world.level.pathfinder.Node end =
                path == null ? null : path.getEndNode();
        boolean reaches = path != null && !path.isDone() && end != null
                && end.asBlockPos().distSqr(p) <= 1;
        if (s.pathOk.size() > 256) {
            s.pathOk.clear(); // 兜底防涨（正常几十个键，换点即过期）
        }
        if (!reaches) {
            return REACH_DEAD_END;
        }
        // 终点到了≠能走完：路径中途落在楔死格上的不算可达（routeCrossesBlocked
        // 只看楔死级；穿普通拉黑格没关系——见 St#wedges 雪崩注释）
        if (routeCrossesBlocked(r, path)) {
            return REACH_ROUTE_BLOCKED;
        }
        s.pathOk.put(p.asLong(), now);
        return REACH_OK;
    }

    /**
     * 在 {@code around} 附近找一格"能站"的位置：脚下实心、本格与头顶无碰撞。
     * 同心环就近搜索（半径 0..{@code xzRadius}，先到先停），y 在区间内取
     * 离 {@code around} 最近的可站层。找不到返回 null——调用方自行降级
     * （原地待机/换目标/撒点）。
     *
     * <p>背景（2026-11-19 实测）：回城/回岗/回家曾一律瞄准选区<b>几何中心</b>，
     * 中心点落在酒桶、床、栅栏里时居民走到最近的能走点后无限重发寻路，
     * 看着就是顶着方块卡死。几何中心只是"区域的代称"，导航目标必须是可站立格。
     */
    public static BlockPos findStandable(net.minecraft.world.level.Level level,
                                         BlockPos around, int xzRadius,
                                         int yMin, int yMax) {
        return findStandable(level, around, xzRadius, yMin, yMax, null);
    }

    /** 带"排除格"版本（楔死脱困用：排除自己所在的格子，避免原地空转）。 */
    public static BlockPos findStandable(net.minecraft.world.level.Level level,
                                         BlockPos around, int xzRadius,
                                         int yMin, int yMax, BlockPos exclude) {
        return findStandable(level, around, xzRadius, yMin, yMax, exclude, null);
    }

    /** 带"排除格+落点谓词"版本：谓词不过的格不选（楔死脱困优先城内落点用）。 */
    public static BlockPos findStandable(net.minecraft.world.level.Level level,
                                         BlockPos around, int xzRadius,
                                         int yMin, int yMax, BlockPos exclude,
                                         @Nullable java.util.function.Predicate<BlockPos> filter) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int r = 0; r <= xzRadius && best == null; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue; // 同心环：由内向外，找到最近环就收手
                    }
                    int x = around.getX() + dx;
                    int z = around.getZ() + dz;
                    for (int y = Math.min(yMin, yMax); y <= Math.max(yMin, yMax); y++) {
                        BlockPos p = new BlockPos(x, y, z);
                        if (p.equals(exclude)
                                || (filter != null && !filter.test(p))) {
                            continue;
                        }
                        if (isStandable(level, p)) {
                            double d = p.distSqr(around);
                            if (d < bestD) {
                                bestD = d;
                                best = p;
                            }
                        }
                    }
                }
            }
        }
        return best;
    }
}
