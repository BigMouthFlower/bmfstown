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

    /** 节点解锁所需段位（与 {@code SkillTiers.PERK_TIERS} 对齐：精通/大师/传奇）。 */
    public static final int[] UNLOCK_TIER = {3, 6, 9};

    /**
     * 服务端校验：该镇民是否有权选择此节点的分支。
     *
     * <p><b>不能信客户端</b>——payload 里的节点/分支必须复核段位与合法性，
     * 改包客户端想跳级选传奇分支时在这里被拒。
     *
     * @return true = 允许落档
     */
    public static boolean canChoose(ResidentProfile profile, String node, String branch) {
        for (int n = 0; n < NODES.length; n++) {
            if (!NODES[n].equals(node)) {
                continue;
            }
            // 段位必须达标（按当前职业主技能实时推导，不是按存档记录）
            var skill = profile.profession() == null ? null : profile.profession().mainSkill();
            if (skill == null || SkillTiers.tierOf(profile.skillXp(skill)) < UNLOCK_TIER[n]) {
                return false;
            }
            for (String b : BRANCHES[n]) {
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
