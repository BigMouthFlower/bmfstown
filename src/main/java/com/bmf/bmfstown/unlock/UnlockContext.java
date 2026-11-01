package com.bmf.bmfstown.unlock;

import com.bmf.bmfstown.entity.Resident;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * 解锁判定的上下文：条件谓词评估时能看到的一切。
 *
 * <p>设计纪律（v0.10 定稿讨论）：谓词必须**可归因**——"钓到特殊鱼"要检查
 * 行为人是不是渔夫，而不是"世界上出现了特殊鱼"。所以上下文必须带
 * 行为人（subject）与动作相关物（item），只给现象不给主语的事件
 * 不要拿来当解锁条件。
 *
 * @param level    世界
 * @param resident 行为人（事件型条件的 subject；无主语场景为 null）
 * @param item     相关物品（进食类；无则 EMPTY）
 * @param townId   事件所属城镇（解锁的访问空间；无城镇场景为 null = 不解锁）
 */
public record UnlockContext(ServerLevel level, @Nullable Resident resident,
                            @Nullable ItemStack item, @Nullable java.util.UUID townId) {

    public static UnlockContext of(ServerLevel level, @Nullable java.util.UUID townId) {
        return new UnlockContext(level, null, null, townId);
    }
}
