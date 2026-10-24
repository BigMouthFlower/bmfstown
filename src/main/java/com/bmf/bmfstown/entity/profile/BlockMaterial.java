package com.bmf.bmfstown.entity.profile;

import java.util.Locale;

/**
 * 建筑方块材质偏好表（v0.8）。
 *
 * <p>一个"材质" = 一组<b>注册名前缀</b>。镇民喜欢某材质时，判定任何方块
 * 只要注册名以其中任一前缀开头即算命中——楼梯/台阶/门/活板门等衍生
 * 方块全部自动覆盖，不用手抄几百个方块 id。
 *
 * <p>表按 MC 原版建材家族选样，和玩家实际会用的建材对齐；
 * 后续要增删只改这张表，逻辑零改动。
 */
public enum BlockMaterial {

    STONE("石头", "stone_", "cobblestone", "smooth_stone", "stone_bricks"),
    DEEPSLATE("深板岩", "deepslate_", "cobbled_deepslate", "polished_deepslate"),
    GRANITE("花岗岩", "granite"),
    DIORITE("闪长岩", "diorite"),
    ANDESITE("安山岩", "andesite"),
    TUFF("凝灰岩", "tuff"),
    CALCITE("方解石", "calcite"),
    SANDSTONE("砂岩", "sandstone"),
    RED_SANDSTONE("红砂岩", "red_sandstone"),
    BLACKSTONE("黑石", "blackstone", "gilded_blackstone"),
    OAK("橡木", "oak"),
    SPRUCE("云杉木", "spruce"),
    BIRCH("白桦木", "birch"),
    JUNGLE("丛林木", "jungle"),
    ACACIA("金合欢木", "acacia"),
    DARK_OAK("深色橡木", "dark_oak"),
    MANGROVE("红树木", "mangrove"),
    CHERRY("樱花木", "cherry"),
    BAMBOO("竹子", "bamboo"),
    COPPER("铜", "copper", "cut_copper", "exposed_copper", "weathered_copper", "oxidized_copper"),
    IRON("铁", "iron_block", "iron_bars", "iron_door", "iron_trapdoor"),
    GOLD("金", "gold_block", "light_weighted_pressure_plate", "heavy_weighted_pressure_plate"),
    QUARTZ("石英", "quartz"),
    AMETHYST("紫水晶", "amethyst"),
    PRISMARINE("海晶石", "prismarine");

    private final String zhName;
    private final String[] prefixes;

    BlockMaterial(String zhName, String... prefixes) {
        this.zhName = zhName;
        this.prefixes = prefixes;
    }

    public String zhName() {
        return this.zhName;
    }

    /** 语言文件 key（UI 用 I18n 现译）。 */
    public String displayName() {
        return "block.bmfstown." + name().toLowerCase(Locale.ROOT);
    }

    /** 注册名是否属于本材质（大小写不敏感）。 */
    public boolean matches(String registryName) {
        String n = registryName.toLowerCase(Locale.ROOT);
        for (String p : this.prefixes) {
            if (n.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** 按注册名反查材质；不属于任何材质返回 null。 */
    public static BlockMaterial byName(String registryName) {
        for (BlockMaterial m : values()) {
            if (m.matches(registryName)) {
                return m;
            }
        }
        return null;
    }

    /** 均匀抽一个。 */
    public static BlockMaterial random(net.minecraft.util.RandomSource random) {
        return values()[random.nextInt(values().length)];
    }
}
