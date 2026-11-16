package com.bmf.bmfstown.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 酒桶（Brewin' And Chewin Keg）通用读写（决策 #65）——**零 BAC 类、零反射**。
 *
 * <p>BAC 的酒桶不暴露 NeoForge 能力，但其存档结构是公开约定：
 * <ul>
 *   <li>{@code Inventory} = NeoForge 标准 {@code ItemStackHandler} 格式
 *       （内层 {@code Items} 列表，5 槽：0~3 投料、4 出料）；</li>
 *   <li>{@code FluidTank} = NeoForge 标准 {@code FluidTank} 格式
 *       （{@code FluidName} 字符串 + {@code Amount} 整数，单位 droplets，
 *       1000mB = 81000）；</li>
 *   <li>{@code FermentTime}/{@code FermentTimeTotal} 整数。</li>
 * </ul>
 * 读写走 {@code saveWithFullMetadata → 改 → loadWithComponents → setChanged}
 * 通用往返；BAC 的 {@code fermentingTick} 每刻重新匹配配方，写入即自动开酿。
 *
 * <h2>降级安全</h2>
 * 任一键不存在（mod 不在/版本改了）→ 读到 null → 调用方跳过酒桶，
 * 绝不写、绝不炸；酒馆整体回落到"窖里有什么喝什么"。
 */
public final class KegAccess {

    private KegAccess() {
    }

    /** NeoForge droplets：1mB = 81（整桶 81000）。 */
    public static final int DROPLETS_PER_MB = 81;
    /** 一大杯 = 250mB（BAC pouring 配方统一口径）。 */
    public static final long TANKARD_DROPLETS = 250L * DROPLETS_PER_MB;

    private static final String KEY_INVENTORY = "Inventory";
    private static final String KEY_ITEMS = "Items";
    private static final String KEY_FLUID_TANK = "FluidTank";
    private static final String KEY_FLUID_NAME = "FluidName";
    private static final String KEY_AMOUNT = "Amount";
    private static final int SLOTS = 5;
    private static final int INPUT_SLOT_LAST = 3; // 0..3 投料，4 出料

    /** 酒桶流体快照。 */
    public record FluidSnap(String fluidId, long droplets) {
    }

    /** 酒桶整体快照（slots 为 5 槽副本，空槽为 EMPTY）。 */
    public record KegSnap(FluidSnap fluid, ItemStack[] slots) {
        public boolean isKegData() {
            return this.slots != null && this.slots.length == SLOTS;
        }
    }

    /**
     * 读酒桶：非酒桶（无 FluidTank+Inventory 双键）或 BAC 不在 → null。
     *
     * <p>防御（GameTest 实证，#65）：BAC 的 keg 方块实体<b>构造时读自身配置</b>
     * （greenhouseconfig），配置未加载的环境（GameTestServer）里对 keg 位置
     * 调 {@code getBlockEntity} 直接抛 NPE——这里整体兜成 null，调用方静默跳过。
     */
    public static KegSnap read(ServerLevel level, BlockPos pos) {
        try {
            return read0(level, pos);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static KegSnap read0(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return null;
        }
        CompoundTag root = be.saveWithFullMetadata(level.registryAccess());
        if (!root.contains(KEY_FLUID_TANK, Tag.TAG_COMPOUND)
                || !root.contains(KEY_INVENTORY, Tag.TAG_COMPOUND)) {
            return null; // 不是酒桶（BAC 不在/结构变了）
        }
        CompoundTag tank = root.getCompound(KEY_FLUID_TANK);
        FluidSnap fluid = null;
        String fluidName = tank.getString(KEY_FLUID_NAME);
        if (!fluidName.isEmpty() && tank.contains(KEY_AMOUNT)) {
            fluid = new FluidSnap(fluidName, tank.getInt(KEY_AMOUNT));
        }
        ItemStack[] slots = new ItemStack[SLOTS];
        for (int i = 0; i < SLOTS; i++) {
            slots[i] = ItemStack.EMPTY;
        }
        CompoundTag inv = root.getCompound(KEY_INVENTORY);
        if (inv.contains(KEY_ITEMS, Tag.TAG_LIST)) {
            ListTag list = inv.getList(KEY_ITEMS, Tag.TAG_COMPOUND);
            HolderLookup.Provider provider = level.registryAccess();
            for (int i = 0; i < list.size() && i < SLOTS; i++) {
                int idx = i;
                ItemStack.parse(provider, list.getCompound(idx))
                        .ifPresent(st -> slots[idx] = st);
            }
        }
        return new KegSnap(fluid, slots);
    }

    /** 写回：改完字段后调这个（root 来自 read 的同一对象修改）。 */
    private static boolean write(ServerLevel level, BlockPos pos, CompoundTag root) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return false;
        }
        be.loadWithComponents(root, level.registryAccess());
        be.setChanged();
        return true;
    }

    /**
     * 倒基液：空桶直倒；已有<b>同种</b>流体则累加（蜂蜜 250mB/瓶 ×4 → 1000mB
     * 配方的现实形态），到桶容量封顶；已有别的流体 → false（不掺不混）。
     * fluidId 需在流体注册表内。
     */
    public static boolean pourBaseFluid(ServerLevel level, BlockPos pos,
            String fluidId, long droplets) {
        if (BuiltInRegistries.FLUID.keySet().stream().noneMatch(k -> k.toString().equals(fluidId))) {
            return false; // 流体不存在（BAC 不在）
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return false;
        }
        CompoundTag root = be.saveWithFullMetadata(level.registryAccess());
        CompoundTag tank = root.contains(KEY_FLUID_TANK, Tag.TAG_COMPOUND)
                ? root.getCompound(KEY_FLUID_TANK).copy() : new CompoundTag();
        long cap = 1000L * DROPLETS_PER_MB;
        String cur = tank.getString(KEY_FLUID_NAME);
        long newAmt;
        if (cur.isEmpty()) {
            newAmt = Math.min(cap, droplets);
            tank.putString(KEY_FLUID_NAME, fluidId);
        } else if (cur.equals(fluidId)) {
            long curAmt = tank.contains(KEY_AMOUNT) ? tank.getInt(KEY_AMOUNT) : 0;
            newAmt = Math.min(cap, curAmt + droplets);
            if (newAmt == curAmt) {
                return false; // 已经满桶
            }
        } else {
            return false; // 里面有别的流体
        }
        tank.putInt(KEY_AMOUNT, (int) newAmt);
        root.put(KEY_FLUID_TANK, tank);
        boolean ok = write(level, pos, root);
        if (ok) {
            level.playSound(null, pos, SoundEvents.BOTTLE_FILL, SoundSource.NEUTRAL, 1.0F, 1.0F);
        }
        return ok;
    }

    /**
     * 投配料：把物品放进 0~3 号投料槽的空槽（出料槽 4 不动）。全部放得下才动手。
     */
    public static boolean insertIngredients(ServerLevel level, BlockPos pos,
            List<ItemStack> items) {
        if (items.isEmpty()) {
            return true;
        }
        KegSnap snap = read(level, pos);
        if (snap == null || !snap.isKegData()) {
            return false;
        }
        int empty = 0;
        for (int i = 0; i <= INPUT_SLOT_LAST; i++) {
            if (snap.slots[i].isEmpty()) {
                empty++;
            }
        }
        if (empty < items.size()) {
            return false;
        }
        CompoundTag root = level.getBlockEntity(pos).saveWithFullMetadata(level.registryAccess());
        CompoundTag inv = root.getCompound(KEY_INVENTORY);
        ItemStack[] slots = snap.slots;
        int take = 0;
        for (int i = 0; i <= INPUT_SLOT_LAST && take < items.size(); i++) {
            if (slots[i].isEmpty()) {
                slots[i] = items.get(take++).copy();
            }
        }
        ListTag list = new ListTag();
        HolderLookup.Provider provider = level.registryAccess();
        for (int i = 0; i < SLOTS; i++) {
            list.add(slots[i].save(provider));
        }
        inv.put(KEY_ITEMS, list);
        root.put(KEY_INVENTORY, inv);
        return write(level, pos, root);
    }

    /**
     * 接一杯：桶内流体是注册表中的物品（16 种酒流体 id == 物品 id）且
     * ≥一杯量 → 扣一杯量、生成物品；不满足 → EMPTY 不动手。
     */
    public static ItemStack drawOne(ServerLevel level, BlockPos pos) {
        KegSnap snap = read(level, pos);
        if (snap == null || snap.fluid == null
                || snap.fluid.droplets() < TANKARD_DROPLETS) {
            return ItemStack.EMPTY;
        }
        ResourceLocation fluidId = ResourceLocation.tryParse(snap.fluid.fluidId());
        if (fluidId == null) {
            return ItemStack.EMPTY;
        }
        Optional<ItemStack> bottle = BuiltInRegistries.ITEM.getOptional(fluidId)
                .map(item -> new ItemStack(item));
        if (bottle.isEmpty() || bottle.get().isEmpty()) {
            return ItemStack.EMPTY; // 流体没有同名物品：不硬接
        }
        CompoundTag root = level.getBlockEntity(pos).saveWithFullMetadata(level.registryAccess());
        CompoundTag tank = root.getCompound(KEY_FLUID_TANK);
        long left = snap.fluid.droplets() - TANKARD_DROPLETS;
        if (left <= 0) {
            root.put(KEY_FLUID_TANK, new CompoundTag()); // 桶空 = 空复合（BAC 口径）
        } else {
            tank.putInt(KEY_AMOUNT, (int) left);
        }
        if (!write(level, pos, root)) {
            return ItemStack.EMPTY;
        }
        level.playSound(null, pos, SoundEvents.BOTTLE_FILL, SoundSource.NEUTRAL, 1.0F, 1.0F);
        return bottle.get();
    }

    /** 桶内流体能不能接一杯（有流体、够量、流体有同名物品）。 */
    public static boolean drawable(ServerLevel level, BlockPos pos) {
        KegSnap snap = read(level, pos);
        if (snap == null || snap.fluid == null
                || snap.fluid.droplets() < TANKARD_DROPLETS) {
            return false;
        }
        ResourceLocation fluidId = ResourceLocation.tryParse(snap.fluid.fluidId());
        return fluidId != null && BuiltInRegistries.ITEM.getOptional(fluidId).isPresent();
    }

    /** 基液映射：物品 → (流体 id, droplets)。仅通用基液（水/奶/蜜），酒瓶不倒回去。 */
    public static FluidSnap baseFluidOf(ItemStack stack) {
        if (stack.is(Items.WATER_BUCKET)) {
            return new FluidSnap("minecraft:water", 1000L * DROPLETS_PER_MB);
        }
        if (stack.is(Items.MILK_BUCKET)) {
            return new FluidSnap("minecraft:milk", 1000L * DROPLETS_PER_MB);
        }
        if (stack.is(Items.HONEY_BOTTLE)) {
            return new FluidSnap("brewinandchewin:honey", TANKARD_DROPLETS);
        }
        return null;
    }

    /** 喝下结算的通用药效应用（1.21.1 FoodProperties/POTION_CONTENTS：药水+BAC 酒全覆盖）。 */
    public static void applyConsumable(ServerLevel level, net.minecraft.world.entity.LivingEntity who,
            ItemStack drink) {
        applyFoodEffects(who, drink);
        applyPotionContentsOnly(level, who, drink);
    }

    /** 只上 POTION_CONTENTS 药效（原版可饮物走 finishUsingItem 时 FOOD 已被
     *  原版上过，调用方用它补 BAC 酒的原版不管的药效，防双份）。 */
    public static void applyPotionContentsOnly(ServerLevel level,
            net.minecraft.world.entity.LivingEntity who, ItemStack drink) {
        net.minecraft.world.item.alchemy.PotionContents pc =
                drink.get(DataComponents.POTION_CONTENTS);
        if (pc != null) {
            for (net.minecraft.world.effect.MobEffectInstance e : pc.getAllEffects()) {
                who.addEffect(e);
            }
        }
    }

    private static void applyFoodEffects(net.minecraft.world.entity.LivingEntity who,
            ItemStack drink) {
        net.minecraft.world.food.FoodProperties food = drink.get(DataComponents.FOOD);
        if (food != null) {
            for (net.minecraft.world.food.FoodProperties.PossibleEffect pe : food.effects()) {
                if (who.getRandom().nextFloat() < pe.probability()) {
                    who.addEffect(pe.effectSupplier().get());
                }
            }
        }
    }

    /** 容器空位插入（酒窖归还杯/存酒通用）。 */
    public static boolean insertInto(Container c, ItemStack stack) {
        for (int i = 0; i < c.getContainerSize(); i++) {
            if (c.getItem(i).isEmpty()) {
                c.setItem(i, stack);
                c.setChanged();
                return !c.getItem(i).isEmpty();
            }
        }
        return false;
    }

    /** 容器里第一个匹配物品的槽位（无则 -1）。 */
    public static int findItemSlot(Container c, ItemStack stack) {
        for (int i = 0; i < c.getContainerSize(); i++) {
            if (!c.getItem(i).isEmpty() && ItemStack.isSameItemSameComponents(c.getItem(i), stack)) {
                return i;
            }
        }
        return -1;
    }

    /** 容器清点：某物品总数。 */
    public static int countItem(Container c, ItemStack sample) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            if (ItemStack.isSameItemSameComponents(c.getItem(i), sample)) {
                n += c.getItem(i).getCount();
            }
        }
        return n;
    }

    /** 从容器取走一件匹配物品（真实消耗）。 */
    public static ItemStack takeOne(Container c, ItemStack sample) {
        int slot = findItemSlot(c, sample);
        if (slot < 0) {
            return ItemStack.EMPTY;
        }
        ItemStack out = c.getItem(slot).copy();
        out.setCount(1);
        c.getItem(slot).shrink(1);
        c.setChanged();
        return out;
    }

    /** 简单工具：收集非空物品列表。 */
    public static List<ItemStack> nonEmpty(ItemStack[] slots) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack st : slots) {
            if (!st.isEmpty()) {
                out.add(st);
            }
        }
        return out;
    }
}
