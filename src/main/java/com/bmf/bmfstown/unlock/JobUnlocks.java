package com.bmf.bmfstown.unlock;

import com.bmf.bmfstown.client.ClientUnlockState;
import com.bmf.bmfstown.entity.profile.ProfessionType;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * 职业可选性的统一判定（数据层过滤的唯一出口）。
 *
 * <p>设计定稿（用户 2026-10-31）：锁定职业**在数据层剔除**——构建可选
 * 列表时就不读它，而不是渲染层遮盖。服务端（ResidentMenu 分配校验）与
 * 客户端（列表渲染/点击下标/滚轮范围）都从这里取同一个列表，顺序天然一致。
 *
 * <p>规则：当前职业永远保留（否则面板看不出他现在是谁）；
 * 无业与战士是初始状态永远可选；其余职业按**所在城镇**的解锁状态
 * （unlock.job.*，访问空间=城镇）过滤——无城镇的镇民只有无业/战士。
 */
public final class JobUnlocks {

    /** 职业对应的解锁 id（登记表里的键）。 */
    public static String idOf(ProfessionType type) {
        return "unlock.job." + type.name().toLowerCase(Locale.ROOT);
    }

    /** 服务端版：可选职业列表（分配校验与点击下标共用；访问空间=城镇）。 */
    public static List<ProfessionType> selectable(ServerLevel level,
            @Nullable java.util.UUID townId, @Nullable ProfessionType current) {
        UnlockState state = UnlockState.get(level);
        return filter(type -> townId != null && state.isUnlocked(townId, idOf(type)), current);
    }

    /** 客户端版：查 ClientUnlockState 镜像（渲染与点击发包共用）。 */
    public static List<ProfessionType> selectableClient(@Nullable java.util.UUID townId,
            @Nullable ProfessionType current) {
        return filter(type -> townId != null && ClientUnlockState.isUnlocked(townId, idOf(type)),
                current);
    }

    /** 服务端单点校验：该职业当前是否可被分配（点击兜底用）。 */
    public static boolean isUnlockedServer(ServerLevel level, @Nullable java.util.UUID townId,
            ProfessionType type) {
        return type == ProfessionType.WARRIOR || type == ProfessionType.NONE
                || type == ProfessionType.SCHOLAR
                || type == ProfessionType.ALCHEMIST
                || type == ProfessionType.FIREWORKSMAN
                || (townId != null && UnlockState.get(level).isUnlocked(townId, idOf(type)));
    }

    private static List<ProfessionType> filter(Predicate<ProfessionType> unlocked,
            @Nullable ProfessionType current) {
        List<ProfessionType> out = new ArrayList<>();
        for (ProfessionType type : ProfessionType.values()) {
            // 无业（NONE）/战士/学者（v0.11 用户定稿）/炼金术士（v0.12 先跑通机制，解锁链后接）
            // 永远可选；烟火工匠（v0.13 同口径：解锁引子用户搁置，先跑通机制）
            if (type == current || type == ProfessionType.WARRIOR
                    || type == ProfessionType.NONE || type == ProfessionType.SCHOLAR
                    || type == ProfessionType.ALCHEMIST
                    || type == ProfessionType.FIREWORKSMAN
                    || unlocked.test(type)) {
                out.add(type);
            }
        }
        return out;
    }

    private JobUnlocks() {
    }
}
