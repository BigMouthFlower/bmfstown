package com.bmf.bmfstown.fireworks;

import com.bmf.bmfstown.BmfTown;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.server.level.ServerLevel;
import it.unimi.dsi.fastutil.ints.IntList;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 烟火订单目录（v0.13）：订单=变量指定制（决策 #30"不做混杂"），单点解析。
 *
 * <h2>订单键（{@code |} 分隔惯例，对齐 {@code alch|}）</h2>
 * <pre>{@code
 * fw|<火药n>|<星1>|<星2>|…
 * 星 = <形状>,<主色列表>,<渐变列表>,<修饰>
 *      形状 0~4：0小球 / 1大球 / 2星形 / 3爬行者形 / 4爆裂形
 *      主色：c1+c2+…（各 0~15，DyeColor 序号；上限=原版星配方格子账，见 dyeCap）
 *      渐变：'-'=无，或 c1+c2+…（各 0~15，最多 8 个——fade 配方=星+染料 ≤8）
 *      修饰：n=无 / t=轨迹 / w=闪烁 / tw=双修
 * 例：fw|1|2,5+3,-,n = 飞行时长1 + 一颗星形·紫+红主色·无渐变星
 * }</pre>
 *
 * <h2>原版格子约束（3×3 网格，服务端复核）</h2>
 * 火箭：纸 1 格，火药 n(1~3) 与星 m 共享其余 8 格 → m ≤ 8−n。<br>
 * 星：火药 1（必）+ 染料 C + 形状物 S(0/1) + 钻石 T(0/1) + 荧石 W(0/1) ≤ 9 格
 * → C ≤ 8−S−T−W（无形状无修饰时最多 8 染料）；渐变=星+染料，染料 ≤ 8。
 *
 * <h2>真实 assemble 铁律（设计稿 §2）</h2>
 * 星与火箭必须走 {@code RecipeManager} 真实特殊配方（FireworkStarRecipe /
 * FireworkStarFadeRecipe / FireworkRocketRecipe），纯模拟 new ItemStack 会产出
 * 无星哑炮。原版口径修正（设计稿 §3.1 与原版不符，2026-11-07 反编译核实）：
 * <b>星星合成的多染料全进主色 colors（fadeColors 恒空），渐变必须走第二段
 * fade 合成（星+染料 → 追加 fadeColors）</b>——NPC 造星按 星→fade 两段跑。
 *
 * <h2>客户端展示</h2>
 * {@link #displayStack} 按订单键直接构造等价组件（原版特殊配方的 assemble
 * 是确定性纯计算，重放结果一致）——展示用途零网络依赖。
 */
public final class FireworksCatalog {

    public static final String KEY_PREFIX = "fw|";

    /** 一颗星的规格（订单变量全表，设计稿 §3.1）。主色=多染料（原版全进 colors）。 */
    public record StarSpec(FireworkExplosion.Shape shape, int[] colors,
                           int[] fades, boolean trail, boolean twinkle) {

        /** 星配方染料上限：8 − 形状物 − 钻石 − 荧石（各占 1 格；原版口径）。 */
        public static int dyeCap(FireworkExplosion.Shape shape,
                boolean trail, boolean twinkle) {
            int cap = 8;
            if (shape != FireworkExplosion.Shape.SMALL_BALL) {
                cap--;
            }
            if (trail) {
                cap--;
            }
            if (twinkle) {
                cap--;
            }
            return cap;
        }

        /** 该规格的最终爆炸组件（池中现成星按此精确匹配）。
         *  颜色必须映射成**烟花 RGB**（DyeColor.fireworkColor）——真实配方产出的
         *  星组件存的是 RGB 而非 DyeColor 序号，口径不一致会让收星/池匹配全部失配。
         *  多主色时 colors 顺序=造星投料顺序（原版按格子序收录，两处必须一致）。 */
        public FireworkExplosion explosion() {
            int[] rgbs = new int[this.colors.length];
            for (int i = 0; i < this.colors.length; i++) {
                rgbs[i] = DyeColor.values()[this.colors[i]].getFireworkColor();
            }
            IntList colorList = IntList.of(rgbs);
            IntList fadeList;
            if (this.fades.length == 0) {
                fadeList = IntList.of();
            } else {
                int[] fadeColors = new int[this.fades.length];
                for (int i = 0; i < this.fades.length; i++) {
                    fadeColors[i] = DyeColor.values()[this.fades[i]].getFireworkColor();
                }
                fadeList = IntList.of(fadeColors);
            }
            return new FireworkExplosion(this.shape, colorList, fadeList,
                    this.trail, this.twinkle);
        }

        /** 单星一套的造星材料行（不含组装料；模具复用的形状物减免在 Goal 侧处理）。 */
        public List<ItemStack> materials() {
            List<ItemStack> out = new ArrayList<>();
            out.add(new ItemStack(Items.GUNPOWDER));
            for (int color : this.colors) {
                out.add(new ItemStack(DyeItem.byColor(DyeColor.values()[color])));
            }
            ItemStack shapeItem = shapeItem();
            if (!shapeItem.isEmpty()) {
                out.add(shapeItem);
            }
            if (this.twinkle) {
                out.add(new ItemStack(Items.GLOWSTONE_DUST));
            }
            if (this.trail) {
                out.add(new ItemStack(Items.DIAMOND));
            }
            for (int fade : this.fades) {
                out.add(new ItemStack(DyeItem.byColor(DyeColor.values()[fade])));
            }
            return out;
        }

        /** 形状物（借用归还制下模具复用 perk 免耗的就是它；小球=无形状物）。 */
        public ItemStack shapeItem() {
            return switch (this.shape) {
                case LARGE_BALL -> new ItemStack(Items.FIRE_CHARGE);
                case STAR -> new ItemStack(Items.GOLD_NUGGET);
                case CREEPER -> new ItemStack(Items.SKELETON_SKULL);
                case BURST -> new ItemStack(Items.FEATHER);
                default -> ItemStack.EMPTY;
            };
        }

        public String encode() {
            StringBuilder sb = new StringBuilder();
            sb.append(this.shape.ordinal()).append(',');
            for (int i = 0; i < this.colors.length; i++) {
                if (i > 0) {
                    sb.append('+');
                }
                sb.append(this.colors[i]);
            }
            sb.append(',');
            if (this.fades.length == 0) {
                sb.append('-');
            } else {
                for (int i = 0; i < this.fades.length; i++) {
                    if (i > 0) {
                        sb.append('+');
                    }
                    sb.append(this.fades[i]);
                }
            }
            sb.append(',');
            if (this.trail && this.twinkle) {
                sb.append("tw");
            } else if (this.trail) {
                sb.append('t');
            } else if (this.twinkle) {
                sb.append('w');
            } else {
                sb.append('n');
            }
            return sb.toString();
        }

        @Nullable
        private static StarSpec parse(String s) {
            String[] p = s.split(",", -1);
            if (p.length != 4) {
                return null;
            }
            int shape = parseInt(p[0]);
            if (shape < 0 || shape > 4) {
                return null;
            }
            // 主色：c1+c2+…（1~8；具体上限等 trail/twinkle 解出后按格子账复核）
            String[] cp = p[1].split("\\+");
            if (cp.length < 1 || cp.length > 8) {
                return null;
            }
            int[] colors = new int[cp.length];
            for (int i = 0; i < cp.length; i++) {
                colors[i] = parseInt(cp[i]);
                if (colors[i] < 0 || colors[i] > 15) {
                    return null;
                }
            }
            int[] fades;
            if (!p[2].equals("-")) {
                String[] fp = p[2].split("\\+");
                if (fp.length < 1 || fp.length > 8) {
                    return null;
                }
                fades = new int[fp.length];
                for (int i = 0; i < fp.length; i++) {
                    fades[i] = parseInt(fp[i]);
                    if (fades[i] < 0 || fades[i] > 15) {
                        return null;
                    }
                }
            } else {
                fades = new int[0];
            }
            boolean trail = false;
            boolean twinkle = false;
            switch (p[3]) {
                case "t" -> trail = true;
                case "w" -> twinkle = true;
                case "tw" -> {
                    trail = true;
                    twinkle = true;
                }
                case "n" -> {
                }
                default -> {
                    return null;
                }
            }
            // 星配方格子账：火药 1 + 染料 + 形状物/钻石/荧石 ≤ 9
            if (colors.length > dyeCap(FireworkExplosion.Shape.values()[shape],
                    trail, twinkle)) {
                return null;
            }
            return new StarSpec(FireworkExplosion.Shape.values()[shape], colors,
                    fades, trail, twinkle);
        }
    }

    /** 一张订单：火药档位 n（=飞行时长）+ 星规格列表（≤ 8−n 颗，可 0 颗=纯火箭）。 */
    public record FwOrder(int gunpowder, List<StarSpec> stars) {

        /** 8 格共享约束：纸占 1 格，星数 m ≤ 8−n。 */
        public static boolean fitsGrid(int gunpowder, int starCount) {
            return gunpowder >= 1 && gunpowder <= 3
                    && starCount >= 0 && starCount <= 8 - gunpowder;
        }

        public String encode() {
            StringBuilder sb = new StringBuilder();
            sb.append(KEY_PREFIX).append(this.gunpowder);
            for (StarSpec star : this.stars) {
                sb.append('|').append(star.encode());
            }
            return sb.toString();
        }

        /** 单套的总材料（组装料 + 全部造星料 + 渐变染料；UI 材料预估行用）。 */
        public List<ItemStack> materialsPerSet() {
            List<ItemStack> out = new ArrayList<>();
            out.add(new ItemStack(Items.PAPER));
            out.add(new ItemStack(Items.GUNPOWDER, this.gunpowder));
            for (StarSpec star : this.stars) {
                out.addAll(star.materials());
            }
            return out;
        }
    }

    // ==========================================================
    //  解析（单点，对齐 AlchemyCatalog.resolve 模式）
    // ==========================================================

    /** 订单键解析（非法/超约束 = null；野键零静默）。 */
    @Nullable
    public static FwOrder parse(@Nullable String key) {
        if (key == null || !key.startsWith(KEY_PREFIX)) {
            return null;
        }
        String[] parts = key.split("\\|");
        // parts[0]="fw"，parts[1]=火药数，其后是星
        int n = parseInt(parts.length > 1 ? parts[1] : "");
        if (n < 1 || n > 3 || parts.length - 2 > 8 - n) {
            return null;
        }
        List<StarSpec> stars = new ArrayList<>();
        for (int i = 2; i < parts.length; i++) {
            StarSpec star = StarSpec.parse(parts[i]);
            if (star == null) {
                return null;
            }
            stars.add(star);
        }
        if (!FwOrder.fitsGrid(n, stars.size())) {
            return null;
        }
        return new FwOrder(n, List.copyOf(stars));
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ==========================================================
    //  真实 assemble（服务端；特殊配方动态算组件，无星哑炮天然规避）
    // ==========================================================

    /**
     * 造星（真实配方两段：星合成 → 渐变 fade）。
     * 造出的星带完整 FIREWORK_EXPLOSION 组件；无渐变时只走第一段。
     */
    public static ItemStack assembleStar(ServerLevel level, StarSpec spec) {
        // 第一段：火药 + 主色染料×N + 形状物 + 修饰物（位置无关，特殊配方；
        // 染料紧随火药投料 → colors 列表顺序与 explosion() 一致）
        List<ItemStack> slots = new ArrayList<>();
        slots.add(new ItemStack(Items.GUNPOWDER));
        for (int color : spec.colors()) {
            slots.add(new ItemStack(DyeItem.byColor(DyeColor.values()[color])));
        }
        ItemStack shapeItem = spec.shapeItem();
        if (!shapeItem.isEmpty()) {
            slots.add(shapeItem);
        }
        if (spec.twinkle()) {
            slots.add(new ItemStack(Items.GLOWSTONE_DUST));
        }
        if (spec.trail()) {
            slots.add(new ItemStack(Items.DIAMOND));
        }
        ItemStack star = assemble(level, slots);
        if (star.isEmpty() || spec.fades().length == 0) {
            return star;
        }
        // 第二段：星 + 渐变染料（FireworkStarFadeRecipe 追加 fadeColors）
        List<ItemStack> fadeSlots = new ArrayList<>();
        fadeSlots.add(star.copyWithCount(1));
        for (int fade : spec.fades()) {
            fadeSlots.add(new ItemStack(DyeItem.byColor(DyeColor.values()[fade])));
        }
        return assemble(level, fadeSlots);
    }

    /**
     * 组装火箭（真实配方）：纸 1 + 火药 n + 星 m → 3 支带 FIREWORKS 组件的烟花。
     * 星参数 = 背包/池里凑到的现成星（必须带爆炸组件——matches 只收带组件的星）。
     */
    public static ItemStack assembleRocket(ServerLevel level, FwOrder order,
            List<ItemStack> stars) {
        List<ItemStack> slots = new ArrayList<>();
        slots.add(new ItemStack(Items.PAPER));
        for (int i = 0; i < order.gunpowder(); i++) {
            slots.add(new ItemStack(Items.GUNPOWDER));
        }
        slots.addAll(stars);
        ItemStack rocket = assemble(level, slots);
        if (rocket.isEmpty()) {
            return ItemStack.EMPTY;
        }
        // 原版口径复核：特殊配方 assemble 已产出 FIREWORKS(n, explosions)——
        // 这里断言防御（配方被数据包改坏时宁可空手也不产哑炮）
        Fireworks fw = rocket.get(DataComponents.FIREWORKS);
        if (fw == null || fw.flightDuration() != order.gunpowder()) {
            BmfTown.LOGGER.warn("[BmfTown][烟火] 组装产物组件异常（flight={}，期望={}）",
                    fw == null ? "?" : fw.flightDuration(), order.gunpowder());
            return ItemStack.EMPTY;
        }
        return rocket;
    }

    /** 通用真实合成：构造 3×3 输入 → 命中原版配方 → assemble（纯计算不消耗输入）。 */
    private static ItemStack assemble(ServerLevel level, List<ItemStack> used) {
        List<ItemStack> slots = new ArrayList<>(9);
        slots.addAll(used);
        while (slots.size() < 9) {
            slots.add(ItemStack.EMPTY);
        }
        CraftingInput input = CraftingInput.of(3, 3, slots);
        RecipeHolder<CraftingRecipe> holder = level.getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, input, level).orElse(null);
        if (holder == null) {
            return ItemStack.EMPTY;
        }
        return holder.value().assemble(input, level.registryAccess());
    }

    // ==========================================================
    //  展示（客户端/服务端共用；等价重放特殊配方的确定性计算）
    // ==========================================================

    /**
     * 按订单键出展示烟花（等价重放：原版特殊配方 assemble 是确定性纯计算，
     * 直接构造同一组件结果一致——无星哑炮问题天然规避）。
     */
    public static ItemStack displayStack(@Nullable String orderKey) {
        FwOrder order = parse(orderKey);
        if (order == null) {
            return ItemStack.EMPTY;
        }
        List<FireworkExplosion> explosions = new ArrayList<>();
        for (StarSpec star : order.stars()) {
            explosions.add(star.explosion());
        }
        ItemStack stack = new ItemStack(Items.FIREWORK_ROCKET);
        stack.set(DataComponents.FIREWORKS,
                new Fireworks(order.gunpowder(), explosions));
        return stack;
    }

    /** 单套材料明细（UI 预估行悬停用）：物品×数量合并同类项。 */
    public static List<String> materialLines(@Nullable String orderKey) {
        FwOrder order = parse(orderKey);
        if (order == null) {
            return List.of();
        }
        java.util.LinkedHashMap<ItemStack, Integer> merged = new java.util.LinkedHashMap<>();
        for (ItemStack material : order.materialsPerSet()) {
            boolean found = false;
            for (java.util.Map.Entry<ItemStack, Integer> e : merged.entrySet()) {
                if (ItemStack.isSameItemSameComponents(e.getKey(), material)) {
                    merged.put(e.getKey(), e.getValue() + material.getCount());
                    found = true;
                    break;
                }
            }
            if (!found) {
                merged.put(material, material.getCount());
            }
        }
        List<String> lines = new ArrayList<>();
        for (var e : merged.entrySet()) {
            lines.add(e.getKey().getHoverName().getString() + "×" + e.getValue());
        }
        return lines;
    }

    private FireworksCatalog() {
    }
}
