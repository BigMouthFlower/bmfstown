package com.bmf.bmfstown.block;

import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.state.properties.EnumProperty;

import java.util.Locale;

/**
 * 墓碑的材质变体（2026-11-28 决策 #143）。
 *
 * <h2>为什么是"一个方块 + 枚举状态"而不是"8 个方块"</h2>
 * 8 个独立方块 = 8 个类 + 8 个 item + 8 份 blockstate + 8 张 loot table，
 * 而且<b>物品栏会被 8 个长得一样的墓碑图标塞满</b>。
 * 变体是<b>方块状态</b>，所以：加第 9 种石头 = 加 1 个枚举项 + 3 个小 JSON，
 * <b>零 Java 逻辑改动</b>。
 *
 * <h2>为什么 8 种全是"石材线内"的</h2>
 * 每一种原料都在 {@code material.SalvageRecipes} 的 {@code STONE} 线里
 * （1 块 = 1 份石材通货），所以「碑拆回石材再切回去」是个真闭环，
 * 不是给个彩虹皮。新增变体前必须先确认原料在回收表里，否则那一档是单向的。
 *
 * <h2>序列化字符串的三种身份</h2>
 * {@link #getSerializedName()} 同时是：blockstate 里的 `variant=` 值、
 * 模型文件名后缀、配方里 `components.block_state.variant` 的值。
 * <b>三者必须同一个字符串</b>——改一个就要改另外两个。
 */
public enum GraveStoneVariant implements StringRepresentable {

    /** 石砖。 */
    STONE_BRICKS("stone_bricks", "minecraft:block/stone_bricks"),
    /** 深板岩砖。 */
    DEEPSLATE_BRICKS("deepslate_bricks", "minecraft:block/deepslate_bricks"),
    /** 凝灰岩砖。 */
    TUFF_BRICKS("tuff_bricks", "minecraft:block/tuff_bricks"),
    /** 黑石砖。 */
    BLACKSTONE_BRICKS("blackstone_bricks", "minecraft:block/polished_blackstone_bricks"),
    /** 磨制花岗岩。 */
    POLISHED_GRANITE("polished_granite", "minecraft:block/polished_granite"),
    /** 磨制闪长岩。 */
    POLISHED_DIORITE("polished_diorite", "minecraft:block/polished_diorite"),
    /** 磨制安山岩。 */
    POLISHED_ANDESITE("polished_andesite", "minecraft:block/polished_andesite"),
    /** 平滑石。 */
    SMOOTH_STONE("smooth_stone", "minecraft:block/smooth_stone");

    /** blockstate / 模型名 / 配方组件共用的那个字符串。 */
    private final String id;

    /** 侧面与背面用的原版方块纹理。正面的碑面是独立贴图，不在这里。 */
    private final String stoneTexture;

    GraveStoneVariant(String id, String stoneTexture) {
        this.id = id;
        this.stoneTexture = stoneTexture;
    }

    @Override
    public String getSerializedName() {
        return this.id;
    }

    public String stoneTexture() {
        return this.stoneTexture;
    }

    /** 方块状态属性。放在这里而不是方块类里，避免枚举与方块互相引用。 */
    public static final EnumProperty<GraveStoneVariant> PROPERTY =
            EnumProperty.create("variant", GraveStoneVariant.class);

    /**
     * 物品模型分派用的浮点值（{@code models/item/grave_stone.json} 的
     * {@code overrides} 按它挑模型）。
     *
     * <p>为什么用 {@code ordinal()} 而不是自造一张表：枚举的声明顺序就是
     * 默认模型之外那 7 个 override 的编号，<b>第 0 项（石砖）不写 override</b>
     * （它是物品模型的 parent 本身，值 0 落到默认）。加第 9 种石头时，
     * 枚举里追加一项 + 补一条 override，编号自动接上。
     *
     * <p><b>顺序即协议</b>：调换枚举顺序会让所有已存在的物品换图标，
     * 虽然不影响存档（材质存在 block_state 组件里、按 name 反查），
     * 但会让玩家手里的碑突然变样——所以只许在末尾追加。
     */
    public float modelIndex() {
        return (float) this.ordinal();
    }

    /** 按序列化名反查；不认识返回 {@link #STONE_BRICKS}（老档/手改数据兜底）。 */
    public static GraveStoneVariant byId(String id) {
        if (id != null) {
            String lower = id.toLowerCase(Locale.ROOT);
            for (GraveStoneVariant v : values()) {
                if (v.id.equals(lower)) {
                    return v;
                }
            }
        }
        return STONE_BRICKS;
    }
}