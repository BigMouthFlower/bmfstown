package com.bmf.bmfstown.unlock;

import java.util.List;

/**
 * 解锁条目的启动登记（mod 构造期调用一次）。
 *
 * <p>当前登记的都是**手动型占位定义**——解锁条件尚未设计（用户定稿：
 * 解锁树玩出来，不坐下来设计），先用调试命令 {@code /bmfstown unlock <id>}
 * 手动触发，便于验证职业锁定链路。条件想好后把 manual 换成带谓词的
 * 定义即可，锁定链路零改动。
 *
 * <p>战士不登记——初始职业永远可选（{@link JobUnlocks} 里特判）。
 */
public final class UnlockBootstrap {

    public static void register() {
        UnlockRegistry.define(UnlockDefinition.manual("unlock.job.worker", "job",
                List.of(), "unlock.bmfstown.job.worker.name"));
        UnlockRegistry.define(UnlockDefinition.manual("unlock.job.bookkeeper", "job",
                List.of(), "unlock.bmfstown.job.bookkeeper.name"));
        UnlockRegistry.define(UnlockDefinition.manual("unlock.job.scholar", "job",
                List.of(), "unlock.bmfstown.job.scholar.name"));
    }

    private UnlockBootstrap() {
    }
}
