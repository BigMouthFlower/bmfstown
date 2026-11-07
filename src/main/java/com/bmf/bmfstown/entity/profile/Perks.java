package com.bmf.bmfstown.entity.profile;

/**
 * perk 节点与分支的定义表（战士先行的三节点体系）。
 *
 * <h2>结构</h2>
 * 每个职业一棵树，树按段位分三个节点（精通 3 / 大师 6 / 传奇 9，
 * 与 {@link SkillTiers#PERK_TIERS} 同源）。每节点 2 选 1：
 * 一支偏防御、一支偏进攻（用户定稿的方向原则）。
 *
 * <h2>为什么用字符串 id 而不是枚举</h2>
 * 节点/分支会随职业增加（农夫、工匠……），字符串
 * {@code "warrior.adept" / "regen"} 直接进存档（{@code perks} 列表），
 * 加新职业不用迁移老数据；枚举删值会让老存档读档崩。
 * id 一旦发布<b>不要改名</b>，改名等于玩家的选择丢失。
 *
 * <h2>语言文件键约定</h2>
 * 节点名：{@code gui.bmfstown.resident.perk.node.<节点序号>}<br>
 * 分支名/描述：{@code gui.bmfstown.resident.perk.bname.<节点序号>.<分支序号>}
 * 与 {@code ...bdesc.<节点序号>.<分支序号>}
 */
public final class Perks {

    // ---- 节点 id（发布后不改名） ----
    public static final String NODE_ADEPT = "warrior.adept";
    public static final String NODE_MASTER = "warrior.master";
    public static final String NODE_LEGENDARY = "warrior.legendary";

    /** 节点 id 数组（下标 = 节点序号，与解锁段位/语言键对齐）。 */
    public static final String[] NODES = {NODE_ADEPT, NODE_MASTER, NODE_LEGENDARY};

    /**
     * 各节点的分支 id（[节点序号][分支序号]，0 = 防御侧，1 = 进攻侧）。
     * 发布后<b>不改名</b>——存档里存的就是这些字符串。
     */
    public static final String[][] BRANCHES = {
            {"regen", "strong"},     // 精通：再生 / 强壮
            {"bulwark", "hunt"},     // 大师：坚盾 / 追猎
            {"taunt", "execute"},    // 传奇：嘲讽 / 斩杀
    };

    // ---- 工人节点（v0.8.1，与战士树同构：3 节点 × 2 分支） ----

    /** 工人精通：效率（快但饿得快）/ 节能（慢但省粮）。 */
    public static final String NODE_W_ADEPT = "worker.adept";
    /** 工人大师：布线（传递/改变信号）/ 机关（接收信号）——决定解锁哪一域配方。 */
    public static final String NODE_W_MASTER = "worker.master";
    /** 工人传奇：开源 / 节流。 */
    public static final String NODE_W_LEGEND = "worker.legend";

    public static final String[] WORKER_NODES = {NODE_W_ADEPT, NODE_W_MASTER, NODE_W_LEGEND};

    public static final String[][] WORKER_BRANCHES = {
            {"efficiency", "frugal"},    // 精通：效率 / 节能
            {"wiring", "mechanism"},     // 大师：布线 / 机关
            {"opensource", "thrift"},    // 传奇：开源 / 节流
    };

    // ---- 学者节点（v0.10.3，与战士/工人树同构；节点二 B 位待补第二分支） ----

    /**
     * 学者精通：辩证法（写 perk 书时写书效率额外乘对应学科效率，
     * 成书双涨经验）/ 分工（写书效率 +20%，非学者经验永久锁涨——
     * <b>不限当前职业</b>，选了就永久是"只会写书的人"）。
     */
    public static final String NODE_S_ADEPT = "scholar.adept";
    /**
     * 学者大师：过拟合（每掌握一门知识写书效率 +10%；抽奖 50% 走全量池，
     * 可能出重复书）/ 批注（书带批注标记：写作耗时 ×1.5，闲书阅读心情
     * 加成 ×1.5，他人研读 perk 书习得更快）。
     */
    public static final String NODE_S_MASTER = "scholar.master";
    /** 学者传奇：三门问题（换门模型提升特殊书中签率）/ 活字印刷术（抄书效率 +50%，抄书玩法实装后生效）。 */
    public static final String NODE_S_LEGEND = "scholar.legend";

    public static final String[] SCHOLAR_NODES = {NODE_S_ADEPT, NODE_S_MASTER, NODE_S_LEGEND};

    public static final String[][] SCHOLAR_BRANCHES = {
            {"dialectics", "division"},   // 精通：辩证法 / 分工
            {"overfit", "annotation"},    // 大师：过拟合 / 批注
            {"monty", "movabletype"},     // 传奇：三门问题 / 活字印刷术
    };

    // ---- 炼金术士节点（v0.12，用户 2026-11-05 定稿；同构 3 节点 × 2 分支） ----

    /** 炼金精通：低温熟成（不耗烈焰粉，速度 −30%）/ 急火快酿（纯度 −0.3，酿造效率 +30%，名字待用户定）。 */
    public static final String NODE_AL_ADEPT = "alchemist.adept";
    /** 炼金大师：医疗石（正面药不窜负面杂质）/ 毒石（负面药不窜正面杂质）。 */
    public static final String NODE_AL_MASTER = "alchemist.master";
    /** 炼金传奇：倍效（药水时长翻倍）/ 升阶（药水效果等级 +1）。 */
    public static final String NODE_AL_LEGEND = "alchemist.legend";

    public static final String[] ALCHEMIST_NODES = {NODE_AL_ADEPT, NODE_AL_MASTER, NODE_AL_LEGEND};

    public static final String[][] ALCHEMIST_BRANCHES = {
            {"cold_brew", "quickfire"},
            {"medic_stone", "poison_stone"},
            {"double_time", "boost_one"},
    };

    /** 节点解锁所需段位（与 {@code SkillTiers.PERK_TIERS} 对齐：精通/大师/传奇）。 */
    public static final int[] UNLOCK_TIER = {3, 6, 9};

    /** 节点 id 前缀 → 归属职业（六域重构：perk 按职业分格后，从节点反查格子）。 */
    public static ProfessionType ownerOf(String node) {
        if (node == null) {
            return ProfessionType.WARRIOR;
        }
        if (node.startsWith("worker.")) {
            return ProfessionType.WORKER;
        }
        if (node.startsWith("scholar.")) {
            return ProfessionType.SCHOLAR;
        }
        if (node.startsWith("alchemist.")) {
            return ProfessionType.ALCHEMIST;
        }
        return ProfessionType.WARRIOR;
    }

    /**
     * 服务端校验：该镇民是否有权选择此节点的分支。
     *
     * <p><b>不能信客户端</b>——payload 里的节点/分支必须复核段位与合法性，
     * 改包客户端想跳级选传奇分支时在这里被拒。
     *
     * <p>职业门禁：战士节点只认战士，工人节点只认工人，学者节点只认学者——
     * 拿别的职业 payload 点天赋在这里被拒。
     *
     * @return true = 允许落档
     */
    public static boolean canChoose(com.bmf.bmfstown.entity.domain.Identity identity,
            com.bmf.bmfstown.entity.domain.Growth growth, TraitType trait,
            String node, String branch) {
        var profession = identity.profession();
        boolean workerTree = node.startsWith("worker.");
        boolean scholarTree = node.startsWith("scholar.");
        boolean alchTree = node.startsWith("alchemist.");
        // 节点树前缀必须与职业匹配
        if (workerTree && profession != ProfessionType.WORKER) {
            return false;
        }
        if (scholarTree && profession != ProfessionType.SCHOLAR) {
            return false;
        }
        if (alchTree && profession != ProfessionType.ALCHEMIST) {
            return false;
        }
        if (!workerTree && !scholarTree && !alchTree && profession != ProfessionType.WARRIOR) {
            return false;
        }
        String[] nodes = workerTree ? WORKER_NODES
                : scholarTree ? SCHOLAR_NODES
                : alchTree ? ALCHEMIST_NODES : NODES;
        String[][] branches = workerTree ? WORKER_BRANCHES
                : scholarTree ? SCHOLAR_BRANCHES
                : alchTree ? ALCHEMIST_BRANCHES : BRANCHES;
        for (int n = 0; n < nodes.length; n++) {
            if (!nodes[n].equals(node)) {
                continue;
            }
            var skill = profession == null ? null : profession.mainSkill();
            if (skill == null) {
                return false;
            }
            // 大智若愚：满级前一个 perk 都解锁不了；满级（传奇）跳过段位门槛全解锁
            if (trait == TraitType.FOOL_SAGE) {
                return SkillTiers.tierOf(growth.skillXp(skill)) >= SkillTiers.MAX_TIER;
            }
            // 段位必须达标（按当前职业主技能实时推导，不是按存档记录）
            if (SkillTiers.tierOf(growth.skillXp(skill)) < UNLOCK_TIER[n]) {
                return false;
            }
            for (String b : branches[n]) {
                if (b.equals(branch)) {
                    return true;
                }
            }
            return false; // 节点合法但分支不认识
        }
        return false; // 节点不认识
    }

    private Perks() {
    }
}
