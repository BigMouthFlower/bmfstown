package com.bmf.bmfstown.entity.profile;

/**
 * 技能段位表 —— 全部技能共用一张阶梯（10 级长线）。
 *
 * <h2>为什么用"累计经验阈值"而不是"每级固定需求"</h2>
 * 段位是经验的单调函数：只存经验，段位随时推导，永远自洽；
 * 以后调曲线数值不用迁移存档（与原档案 level() 的设计同理）。
 *
 * <h2>perk 节点（占位）</h2>
 * 段位达到 精通(3) / 大师(6) / 传奇(9，即第 10 级) 时各解锁一个 perk 选择位——
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
            5200,   // 8 神话
            7500,   // 9 传奇  ← perk 节点 3（最高段位）
    };

    private static final String[] TIER_ZH = {
            "生疏", "入门", "熟练", "精通", "老练",
            "专家", "大师", "宗师", "神话", "传奇",
    };

    /** 最高段位。 */
    public static final int MAX_TIER = TIER_ZH.length - 1;

    /** 解锁 perk 选择位的段位（精通 / 大师 / 传奇）。 */
    public static final int[] PERK_TIERS = {3, 6, 9};

    private SkillTiers() {
    }

    /** 累计经验 → 玩家直观等级（Lv.1~Lv.10 = 段位下标 + 1）。 */
    public static int levelOf(int xp) {
        return tierOf(xp) + 1;
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

    /**
     * 段位名的服务端/兜底显示（如日志、无语言环境）。
     *
     * <p><b>UI 一律走语言文件</b>（键 {@code gui.bmfstown.resident.tier.0~9}），
     * 不要直接用这里——这组字符串只保证服务端侧有东西可打，不做本地化。
     */
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

    // ==========================================================
    //  职业效率（v0.5 定稿：A 方案，纯代码常量）
    // ==========================================================

    /**
     * 职业效率曲线（定稿 A 方案）：{@code 0.6 + 0.1 × (Lv - 1)}。
     *
     * <ul>
     *   <li>Lv.1（生疏）= 0.6：生手只有六成功力；</li>
     *   <li>Lv.5（专家）= 1.0：练到专家才算"合格"，回本点；</li>
     *   <li>Lv.10（传奇）= 1.5：满级上限，满级是新手 2.5 倍。</li>
     * </ul>
     *
     * <h2>这个参数是什么</h2>
     * "效率"是<b>抽象的产出倍率</b>：它不绑定具体效果，每个职业自己决定
     * 拿它乘什么——战士乘伤害，未来农夫乘产出、工匠乘质量。参数一处，
     * 效果多处。数值与段位阈值同理<b>不配置化</b>：它是整个数值体系的
     * 坐标系，改它等于全部玩法要重新配平。
     *
     * <h2>为什么由经验推导</h2>
     * 与段位同理：只存经验、效率现算，等级和效率永远自洽，不存在
     * "等级降了效率没降"的分裂。
     */
    public static final double EFFICIENCY_BASE = 0.6;
    /** 每升一级（= 一个段位）增加的效率。 */
    public static final double EFFICIENCY_STEP = 0.1;

    /** 按累计经验取职业效率（1.0 = 100% 基准产出）。 */
    public static double efficiencyOf(int xp) {
        return efficiencyOfLevel(levelOf(xp));
    }

    /** 按等级（Lv.1~10）取职业效率，线性增长、两端截断。 */
    public static double efficiencyOfLevel(int level) {
        int lv = Math.max(1, Math.min(MAX_TIER + 1, level));
        return EFFICIENCY_BASE + EFFICIENCY_STEP * (lv - 1);
    }
}
