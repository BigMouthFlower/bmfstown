package com.bmf.bmfstown.town;

import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 刻碑的三重复核（2026-11-28 决策 #144）。
 *
 * <h2>为什么单独抽成类而不是写在网络处理器里</h2>
 * 三条判定都是"<b>客户端说了算也不行</b>"的安全检查：
 * 名单是玩家打开界面那一刻的快照，到他点下去之间，世界可能已经变了
 * （碑被挖、碑被别人刻了、那个人已经立在别处）。写死在
 * {@code ModNetworking.handleGraveCarve} 里就没法单测——而 GameTest
 * 跑不了网络层，那三条检查就永远没人验过（等于没写）。
 *
 * <p>抽出来以后网络处理器只剩"读包 → 调这里 → 按结果发提示"，
 * 判定逻辑与传输格式彻底分开。
 *
 * <h2>为什么每次都要全查一遍，不做"抽查"</h2>
 * 三条的失败后果各不相同，而且都是<b>静默</b>的：
 * <ul>
 *   <li>位置不是碑 ⇒ 往一个不是墓碑的位置写碑位，将来那里放任何方块
 *       都可能被 {@code graveAt} 认成碑；</li>
 *   <li>碑已经有主 ⇒ 一个人两行碑文，或者新名字盖掉旧名字；</li>
 *   <li>人不待葬 ⇒ 同一个人立两块碑（#143 明令禁止，迁坟的做法是挖旧立新）。</li>
 * </ul>
 * 三条都很便宜（两次 map 查询 + 一次集合过滤），没有任何理由省。
 */
public final class GraveCarving {

    /** 复核结果。网络处理器按它选提示文字，不在这里拼字符串（服务端没语言环境）。 */
    public enum Result {
        /** 可以刻。 */
        OK,
        /** 那个位置已经不是墓碑了。 */
        NOT_A_STONE,
        /** 这块碑已经刻着人了。 */
        ALREADY_CARVED,
        /** 这个人不在待葬名册里（已立碑 / 不存在）。 */
        NOT_WAITING,
    }

    /**
     * 复核一次刻碑请求。
     *
     * @param stoneAt  该位置现在是不是墓碑方块（<b>由调用方查世界</b>，
     *                 本类不碰 {@code Level}——那样就没法在纯逻辑用例里跑）
     * @param who      客户端回传的逝者 UUID
     */
    public static Result check(GraveyardData data, BlockPos pos, boolean stoneAt,
            @Nullable UUID who) {
        if (!stoneAt) {
            return Result.NOT_A_STONE;
        }
        if (data.graveAt(pos) != null) {
            return Result.ALREADY_CARVED;
        }
        if (who == null) {
            return Result.NOT_WAITING;
        }
        // 必须"名册里有这个人" 且 "他现在没碑" 两条同时成立
        if (data.find(who) == null) {
            return Result.NOT_WAITING;
        }
        for (GraveyardData.Grave g : data.unburied()) {
            if (g.id().equals(who)) {
                return Result.OK;
            }
        }
        return Result.NOT_WAITING;
    }

    /** 复核结果对应的 lang key（网络处理器直接用它发提示）。 */
    public static String messageKey(Result result) {
        return switch (result) {
            case OK -> "grave.bmfstown.carved";
            case NOT_A_STONE -> "grave.bmfstown.gone";
            case ALREADY_CARVED -> "grave.bmfstown.already_carved";
            case NOT_WAITING -> "grave.bmfstown.not_waiting";
        };
    }

    private GraveCarving() {
    }
}