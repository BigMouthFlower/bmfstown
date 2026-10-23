package com.bmf.bmfstown.entity.profile;

/**
 * 技能段位表 —— 全部技能共用一张阶梯（10 级长线）。
 *
 * <h2>为什么用"累计经验阈值"而不是"每级固定需求"</h2>
 * 段位是经验的单调函数：只存经验，段位随时推导，永远自洽；
 * 以后调曲线数值不用迁移存档（与原档案 level() 的设计同理）。
 *
 * <h2>perk 节点（占位）</h2>
 * 段位达到 精通(3) / 大师(6) / 神话(9，即第 10 级) 时各解锁一个 perk 选择位——
 * perk 本体（分支选项）还没设计，这里先把"解锁到第几个"的判定做出来，
 * 将来 perk UI 按它显示"有待选择的分支"。
 *
 * <h2>为什么阈值<b>不做成配置项</b>（定稿口径）</h2>
 * 阈值是<b>存档语义</b>，不是玩法参数。这里的判定规则必须一次定死：
 * <ol>
 *   <li><b>段位是推导值，不落盘</b>。{@code ResidentProfile} 只存
 *       {@code skillXp}，段位靠 {@link #tierOf(int)} 现算。阈值一旦可调，
 *       老存档会在下次加载时<b>静默跳级或掉级</b>，玩家无从理解也无法回退。</li>
 *   <li><b>perk 名额绑在段位下标上</b>（{@link #PERK_TIERS}）。阈值变了，
 *       有人可能凭空多出两个 perk 名额，或者本该到手的名额消失——
 *       已发放的东西不回收，状态会直接分裂。</li>
 *   <li><b>没有即时反馈</b>。改配置后所有面板的段位整体跳变，
 *       但历史收益不会变，玩家会觉得"系统坏了"。</li>
 * </ol>
 * 能调的只有<b>产出速率</b>（如 {@code warrior.xpPerKill}），
 * 那是"多久能攒到"；阈值是<b>坐标系本身</b>，决定"攒到了什么"。
 * 要整体加速成长，改产出侧；要改阶梯形状，改代码并发版。
 */
public final class SkillTiers {

    /**
     * 各段位的累计经验门槛（下标 = 段位）。
     *
     * <p>发布后<b>只增不改</b>：允许在尾部追加更高段位，不允许调整已有数值——
     * 调整会让老存档段位回退。这是硬约束，不是风格建议。
     */
    public static final int[] XP_THRESHOLDS = {
            0,      // 0 生疏
            100,    // 1 入门
            250,    // 2 熟练
            500,    // 3 精通  ← perk 节点 1
            900,    // 4 老练
            1500,   // 5 专家
            2400,   // 6 大师  ← perk 节点 2
            3600,   // 7 宗师
            5200,   // 8 传奇
            7500,   // 9 神话  ← perk 节点 3
    };

    private static final String[] TIER_ZH = {
            "生疏", "入门", "熟练", "精通", "老练",
            "专家", "大师", "宗师", "传奇", "神话",
    };

    /** 最高段位。 */
    public static final int MAX_TIER = TIER_ZH.length - 1;

    /** 解锁 perk 选择位的段位（精通 / 大师 / 神话）。 */
    public static final int[] PERK_TIERS = {3, 6, 9};

    private SkillTiers() {
    }

    /** 累计经验 → 当前段位。 */
    public static int tierOf(int xp) {
        int tier = 0;
        for (int i = 1; i < XP_THRESHOLDS.length; i++) {
            if (xp >= XP_THRESHOLDS[i]) {
                tier = i;
            }
        }
        return tier;
    }

    /** 段位的中文名（面板兜底显示用；正式显示走语言文件）。 */
    public static String tierZh(int tier) {
        return TIER_ZH[clampTier(tier)];
    }

    /** 当前段位的经验下限（画进度条的起点）。 */
    public static int floorXp(int tier) {
        return XP_THRESHOLDS[clampTier(tier)];
    }

    /**
     * 升到下一段位还差多少经验；已到顶返回 0。
     */
    public static int xpToNext(int xp) {
        int tier = tierOf(xp);
        if (tier >= MAX_TIER) {
            return 0;
        }
        return XP_THRESHOLDS[tier + 1] - xp;
    }

    /** 当前段位经验进度比例（0.0 ~ 1.0），UI 进度条用。 */
    public static float progress(int xp) {
        int tier = tierOf(xp);
        if (tier >= MAX_TIER) {
            return 0.0F;
        }
        int floor = XP_THRESHOLDS[tier];
        int ceil = XP_THRESHOLDS[tier + 1];
        return (float) (xp - floor) / (ceil - floor);
    }

    /** 该段位已解锁的 perk 选择位数量（perk 系统占位）。 */
    public static int perksUnlocked(int tier) {
        int count = 0;
        for (int t : PERK_TIERS) {
            if (tier >= t) {
                count++;
            }
        }
        return count;
    }

    private static int clampTier(int tier) {
        return Math.max(0, Math.min(MAX_TIER, tier));
    }
}
