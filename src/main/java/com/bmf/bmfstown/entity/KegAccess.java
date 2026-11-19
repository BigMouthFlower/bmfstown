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
 * <p>BAC 的酒桶不暴露 NeoForge 能力，但其存档结构是公开约定（4.3.0+1.21.1，
 * 决策 #76 已对照上游源码核实）：
 * <ul>
 *   <li>{@code Inventory} = NeoForge 标准 {@code ItemStackHandler} 格式
 *       （{@code Size} 键 + {@code Items} 列表——<b>只含非空槽</b>、每条带
 *       {@code Slot} 索引；6 槽：0~3 投料、4 容器槽、5 出料）；</li>
 *   <li>{@code FluidTank} = NeoForge 新版 {@code FluidTank} 格式
 *       （{@code Fluid} 子复合 = {@code FluidStack.CODEC} NBT：
 *       {@code id} 字符串 + <b>小写 {@code amount}</b> 正整数，<b>单位 mB</b>
 *       （{@code FluidType.BUCKET_VOLUME=1000}，1.21 无 81 droplets 换算）；
 *       空桶 = 空复合。大写 {@code Amount} 会被 codec 拒读、流体静默丢弃——
 *       2026-11-17 用户两轮实测实锤（"只投料没基液"→"倒了不显示不开酿"），
 *       #76 二修）；</li>
 *   <li>{@code FermentTime}/{@code FermentTimeTotal} 整数。</li>
 * </ul>
 * 读写走 {@code saveWithFullMetadata → 改 → loadWithComponents → setChanged
 * → sendBlockUpdated}（同步客户端方块实体，GUI 液面全靠它）通用往返；
 * BAC 的 {@code fermentingTick} 每刻重新匹配配方，写入即自动开酿。
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
    private static final String KEY_SLOT = "Slot";
    private static final String KEY_SIZE = "Size";
    private static final String KEY_FLUID_TANK = "FluidTank";
    private static final String KEY_FLUID = "Fluid"; // FluidTank 内流体子复合（新版）
    private static final String KEY_ID = "id"; // FluidStack.CODEC 流体注册名键
    private static final String KEY_FLUID_NAME = "FluidName"; // 旧版平铺键（仅读兼容）
    private static final String KEY_FERMENT_TIME = "FermentTime"; // BAC 当前发酵进度（刻）
    private static final String KEY_FERMENT_TOTAL = "FermentTimeTotal"; // BAC 配方总时长（刻）
    private static final String KEY_TEMPERATURE = "Temperature"; // BAC 桶温<b>原始分数</b>（#85）
    /**
     * FluidStack.CODEC 的数量键——1.21.1 是<b>小写</b> {@code amount}
     * （{@code ExtraCodecs.POSITIVE_INT.fieldOf("amount")}，#76 二修实锤：
     * 大写 {@code Amount} 会被 codec 拒读、流体静默丢弃）。
     */
    private static final String KEY_AMOUNT = "amount";
    /** 旧版自写平铺格式的数量键（仅读兼容 + 写入时清理）。 */
    private static final String KEY_LEGACY_AMOUNT = "Amount";
    /** BAC 4.3.0 槽位：0~3 投料、4 容器槽（GUI 灌装用）、5 出料（#76 核实）。 */
    private static final int SLOTS = 6;
    private static final int INPUT_SLOT_LAST = 3;
    /** 5 号出料槽（#85 字节码核实：物品产物完成分支 insertItem(5, ...) 的落点）。 */
    public static final int OUTPUT_SLOT = 5;

    /** 酒桶流体快照。 */
    public record FluidSnap(String fluidId, long droplets) {
    }

    /**
     * 酒桶整体快照（slots 为 6 槽副本，空槽为 EMPTY）。
     *
     * @param temperature 当前温度<b>档位</b>（1~5；0=未知）。BAC NBT
     *                    {@code Temperature} 存的是<b>原始分数</b>（热源分−冷源分），
     *                    档位要经 {@link #temperatureTier} 按 BAC 配置阈值换算
     *                    （坑 22：把分数直接当档位喂 isValidTemp 同款匹配表，
     *                    1 个 cask=1 分永远够不上档 4 → "无适配桶"）——read 已换算，
     *                    调用方拿到的直接是档位
     */
    public record KegSnap(FluidSnap fluid, ItemStack[] slots, int temperature) {
        public boolean isKegData() {
            return this.slots != null && this.slots.length == SLOTS;
        }
    }

    // ---- 温度档换算（坑 22，2026-11-18 反编译 getTemperature/isValidTemp 实锤）----

    /** BAC 默认阈值（brewinandchewin-common.toml [keg]：cold=2/chilly=1/warm=1/hot=2）。
     *  仅作反射失败时的回落；运行时优先以 BAC 自身 getTemperature() 为准。 */
    public static final int CFG_COLD = 2, CFG_CHILLY = 1, CFG_WARM = 1, CFG_HOT = 2;

    /**
     * {@code KegBlockEntity.getTemperature()} 字节码同款换算（纯函数，GameTest 直测）：
     * NBT 原始分数 → 档位 1~5——
     * {@code 分数≤-cold→档1；≤-chilly→档2；<warm→档3；<hot→档4；否则档5}。
     */
    public static int temperatureTier(int score, int cold, int chilly, int warm, int hot) {
        if (score <= -cold) {
            return 1;
        }
        if (score <= -chilly) {
            return 2;
        }
        if (score < warm) {
            return 3;
        }
        if (score < hot) {
            return 4;
        }
        return 5;
    }

    /** 反射缓存：BAC {@code getTemperature()}（找不到/不可调时 null，走回落换算）。 */
    private static java.lang.reflect.Method getTemperatureMethod;
    private static boolean getTemperatureResolved;

    /**
     * 从 BE + NBT 快照取温度档位（1~5；0=两边都拿不到）。优先反射调 BAC 自己的
     * {@code getTemperature()}——配置阈值用户可改，BAC 实例方法是唯一权威；
     * 失败（BAC 不在/配置未加载，如 GameTestServer 坑 15）回落 NBT 分数+默认阈值。
     */
    private static int readTemperatureTier(Object be, CompoundTag root) {
        if (!getTemperatureResolved) {
            try {
                getTemperatureMethod = be.getClass().getMethod("getTemperature");
            } catch (Throwable ignored) {
                getTemperatureMethod = null;
            }
            getTemperatureResolved = true;
        }
        java.lang.reflect.Method m = getTemperatureMethod;
        if (m != null) {
            try {
                return ((Number) m.invoke(be)).intValue();
            } catch (Throwable ignored) {
                // 配置未加载等运行时异常：走回落
            }
        }
        if (root.contains(KEY_TEMPERATURE, Tag.TAG_INT)) {
            return temperatureTier(root.getInt(KEY_TEMPERATURE),
                    CFG_COLD, CFG_CHILLY, CFG_WARM, CFG_HOT);
        }
        return 0;
    }

    /**
     * 解 FluidTank 复合 → 流体快照（内部单位 droplets）。新版格式（NeoForge
     * 1.21.1 {@code FluidStack.CODEC} 真实口径）：{@code {Fluid:{id, amount(mB)}}}——
     * <b>1 amount = 1 mB</b>（{@code FluidType.BUCKET_VOLUME = 1000}，1.21 已无
     * 旧 Forge 的 81 droplets 换算）；兼容旧版自写数据
     * {@code {FluidName, Amount(droplets)}}；空/不认识 → null。
     */
    private static FluidSnap fluidFromTank(CompoundTag tank) {
        if (tank.contains(KEY_FLUID, Tag.TAG_COMPOUND)) {
            CompoundTag f = tank.getCompound(KEY_FLUID);
            String id = f.getString(KEY_ID);
            if (!id.isEmpty() && f.contains(KEY_AMOUNT)) {
                return new FluidSnap(id, f.getInt(KEY_AMOUNT) * (long) DROPLETS_PER_MB);
            }
            return null;
        }
        String legacy = tank.getString(KEY_FLUID_NAME); // 旧版自写格式（GameTest 自造数据）
        if (!legacy.isEmpty() && tank.contains(KEY_LEGACY_AMOUNT)) {
            return new FluidSnap(legacy, tank.getInt(KEY_LEGACY_AMOUNT));
        }
        return null;
    }

    /** 把流体写进 tank 复合（BAC 新版口径：Fluid 子复合 + 小写 amount，值=mB），并清旧版残留键。 */
    private static void writeTank(CompoundTag tank, String fluidId, long droplets) {
        CompoundTag f = new CompoundTag();
        f.putString(KEY_ID, fluidId);
        f.putInt(KEY_AMOUNT, (int) Math.round(droplets / (double) DROPLETS_PER_MB));
        tank.put(KEY_FLUID, f);
        tank.remove(KEY_FLUID_NAME);
        tank.remove(KEY_LEGACY_AMOUNT);
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
        FluidSnap fluid = fluidFromTank(tank);
        ItemStack[] slots = new ItemStack[SLOTS];
        for (int i = 0; i < SLOTS; i++) {
            slots[i] = ItemStack.EMPTY;
        }
        CompoundTag inv = root.getCompound(KEY_INVENTORY);
        if (inv.contains(KEY_ITEMS, Tag.TAG_LIST)) {
            ListTag list = inv.getList(KEY_ITEMS, Tag.TAG_COMPOUND);
            HolderLookup.Provider provider = level.registryAccess();
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                // NeoForge ItemStackHandler：条目带 Slot 索引（无 Slot 的旧格式按位置退化）
                int idx = entry.contains(KEY_SLOT, Tag.TAG_INT) ? entry.getInt(KEY_SLOT) : i;
                if (idx >= 0 && idx < SLOTS) {
                    ItemStack.parse(provider, entry)
                            .ifPresent(st -> slots[idx] = st);
                }
            }
        }
        return new KegSnap(fluid, slots, readTemperatureTier(be, root));
    }

    /** 写回：改完字段后调这个（root 来自 read 的同一对象修改）。 */
    /**
     * 调试催熟：把目标酒桶的发酵进度拉到底（{@code FermentTime = Total - 1}），
     * 下一 tick 的 {@code processFermenting} 自增后越过完成线，自动出料。
     *
     * <p>字节码口径（KegBlockEntity.processFermenting）：<b>先自增后比较</b>
     * {@code fermentTime >= total} → 灌成果流体+播音效——所以拉到 {@code total-1}
     * 或 {@code total} 都会在下一次可酿 tick 完成；温度不满足 {@code canFerment}
     * 时会原地待命（与正常酿造同条件，不是故障）。
     *
     * @return null = 成功；否则为给命令执行者的失败原因。
     */
    @javax.annotation.Nullable
    public static String skipFerment(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return "那里没有方块实体";
        }
        CompoundTag root = be.saveWithFullMetadata(level.registryAccess());
        if (!root.contains(KEY_FERMENT_TOTAL, Tag.TAG_INT)) {
            return "瞄准的方块没有在酿（无 FermentTimeTotal——先让酒保投料开酿）";
        }
        int total = root.getInt(KEY_FERMENT_TOTAL);
        int cur = root.contains(KEY_FERMENT_TIME, Tag.TAG_INT)
                ? root.getInt(KEY_FERMENT_TIME) : 0;
        if (total <= 0) {
            return "发酵总时长异常（" + total + "）";
        }
        if (cur >= total) {
            return "发酵已经完成（" + cur + "/" + total + "），出料在即";
        }
        root.putInt(KEY_FERMENT_TIME, total - 1);
        return write(level, pos, root)
                ? null : "写回酒桶失败（看日志）";
    }

    private static boolean write(ServerLevel level, BlockPos pos, CompoundTag root) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return false;
        }
        be.loadWithComponents(root, level.registryAccess());
        be.setChanged();
        // 客户端同步（#76 追记4，2026-11-17）：GUI 的液面走<b>客户端</b>方块实体，
        // 而 setChanged 只标记存档、不触发网络同步——物品靠菜单槽位同步能看见，
        // 流体没有这条同步就永远"看不着"（BAC 自己每次变更都 sendBlockUpdated，
        // 见 SyncedBlockEntity.inventoryChanged 字节码）。照做同款：原版 API。
        net.minecraft.world.level.block.state.BlockState st = level.getBlockState(pos);
        level.sendBlockUpdated(pos, st, st, 3);
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
        FluidSnap cur = fluidFromTank(tank);
        long newAmt;
        if (cur == null) {
            newAmt = Math.min(cap, droplets);
        } else if (cur.fluidId().equals(fluidId)) {
            newAmt = Math.min(cap, cur.droplets() + droplets);
            if (newAmt == cur.droplets()) {
                return false; // 已经满桶
            }
        } else {
            return false; // 里面有别的流体
        }
        writeTank(tank, fluidId, newAmt);
        root.put(KEY_FLUID_TANK, tank);
        boolean ok = write(level, pos, root);
        if (ok) {
            level.playSound(null, pos, SoundEvents.BOTTLE_FILL, SoundSource.NEUTRAL, 1.0F, 1.0F);
        }
        return ok;
    }

    /**
     * 投配料：把物品放进 0~3 号投料槽的空槽（容器槽 4 / 出料槽 5 不动）。
     * 全部放得下才动手。
     *
     * <p><b>格式铁律（决策 #76，2026-11-17 崩溃修正）</b>：写回对齐 NeoForge
     * {@code ItemStackHandler} 真实序列化——Items 列表<b>只含非空槽</b>、每条带
     * {@code Slot} 索引、保留 {@code Size} 键。旧实现对空槽调
     * {@code ItemStack.save} 抛 "Cannot encode empty ItemStack"（酒保投料必崩）；
     * 且整表按位置写无 Slot 索引，BAC 按索引读回会错位。同一快照改改写回，
     * 不再双读（旧实现两次 save 之间玩家可动桶，旧快照覆盖新料）。
     */
    public static boolean insertIngredients(ServerLevel level, BlockPos pos,
            List<ItemStack> items) {
        if (items.isEmpty()) {
            return true;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return false;
        }
        CompoundTag root;
        try {
            root = be.saveWithFullMetadata(level.registryAccess());
        } catch (RuntimeException e) {
            return false; // GameTest 环境 keg BE 构造 NPE 坑，同 read 的兜底
        }
        CompoundTag inv = root.getCompound(KEY_INVENTORY);
        if (!inv.contains(KEY_ITEMS, Tag.TAG_LIST)) {
            return false; // 不是酒桶格式（BAC 不在/结构变了）
        }
        HolderLookup.Provider provider = level.registryAccess();
        ItemStack[] slots = new ItemStack[SLOTS];
        for (int i = 0; i < SLOTS; i++) {
            slots[i] = ItemStack.EMPTY;
        }
        ListTag list = inv.getList(KEY_ITEMS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            int idx = entry.contains(KEY_SLOT, Tag.TAG_INT) ? entry.getInt(KEY_SLOT) : i;
            if (idx >= 0 && idx < SLOTS) {
                ItemStack.parse(provider, entry).ifPresent(st -> slots[idx] = st);
            }
        }
        int empty = 0;
        for (int i = 0; i <= INPUT_SLOT_LAST; i++) {
            if (slots[i].isEmpty()) {
                empty++;
            }
        }
        if (empty < items.size()) {
            return false;
        }
        int take = 0;
        for (int i = 0; i <= INPUT_SLOT_LAST && take < items.size(); i++) {
            if (slots[i].isEmpty()) {
                slots[i] = items.get(take++).copy();
            }
        }
        ListTag out = new ListTag();
        for (int i = 0; i < SLOTS; i++) {
            if (!slots[i].isEmpty()) {
                CompoundTag entry = new CompoundTag();
                entry.putInt(KEY_SLOT, i);
                out.add(slots[i].save(provider, entry));
            }
        }
        inv.put(KEY_ITEMS, out);
        if (!inv.contains(KEY_SIZE, Tag.TAG_INT)) {
            inv.putInt(KEY_SIZE, SLOTS); // ItemStackHandler 反序列化按它定槽数
        }
        root.put(KEY_INVENTORY, inv);
        try {
            be.loadWithComponents(root, level.registryAccess());
            be.setChanged();
            net.minecraft.world.level.block.state.BlockState st = level.getBlockState(pos);
            level.sendBlockUpdated(pos, st, st, 3); // 客户端同步（同 write()，#76 追记4）
        } catch (RuntimeException e) {
            return false; // 写回任何异常都不让酒保炸 tick（宁可这次投料失败）
        }
        return true;
    }

    /**
     * 接出某流体对应饮品要用的空容器（#83 用户实测拍板"接酒耗酒杯"）：
     * 口径 = 饮品自己的合成余料（BAC 全系 16 种酒 craftRemainder 都注册成
     * tankard，客人喝完 {@code cupOf} 走合成余料还回来的正是这个空杯——
     * 还杯→接酒的杯循环守恒）；无余料的饮品回落玻璃瓶（原版蜂蜜瓶/药水
     * 的余料本来就是玻璃瓶，自洽）。
     */
    public static net.minecraft.world.item.Item containerForFluid(String fluidId) {
        ResourceLocation id = ResourceLocation.tryParse(fluidId);
        ItemStack drink = id == null ? ItemStack.EMPTY
                : BuiltInRegistries.ITEM.getOptional(id)
                        .map(ItemStack::new).orElse(ItemStack.EMPTY);
        ItemStack rem = drink.isEmpty() ? ItemStack.EMPTY
                : drink.getCraftingRemainingItem();
        return rem == null || rem.isEmpty()
                ? Items.GLASS_BOTTLE : rem.getItem();
    }

    /**
     * 接一杯：递上一个<b>匹配口径的空容器</b>（#81 用户实测"没拿空杯就凭空
     * 接酒"；#83 用户实测拍板容器跟饮品走——BAC 酒耗 tankard，不再写死
     * 玻璃瓶）；桶内流体是注册表中的物品（酒流体 id == 物品 id）且 ≥一杯量
     * → 耗掉空容器、扣一杯量、生成物品；不满足 → EMPTY 不动手（空容器
     * 完好退还调用方）。
     */
    public static ItemStack drawOne(ServerLevel level, BlockPos pos, ItemStack emptyBottle) {
        if (emptyBottle.isEmpty()) {
            return ItemStack.EMPTY; // 没拿空杯：绝不凭空接
        }
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
        if (!emptyBottle.is(containerForFluid(snap.fluid.fluidId()))) {
            return ItemStack.EMPTY; // 容器口径不对（接 BAC 酒却递玻璃瓶）：不耗不接
        }
        CompoundTag root = level.getBlockEntity(pos).saveWithFullMetadata(level.registryAccess());
        CompoundTag tank = root.getCompound(KEY_FLUID_TANK);
        long left = snap.fluid.droplets() - TANKARD_DROPLETS;
        if (left <= 0) {
            root.put(KEY_FLUID_TANK, new CompoundTag()); // 桶空 = 空复合（BAC 口径）
        } else {
            writeTank(tank, snap.fluid.fluidId(), left);
        }
        if (!write(level, pos, root)) {
            return ItemStack.EMPTY; // 写回失败：空瓶没耗
        }
        level.playSound(null, pos, SoundEvents.BOTTLE_FILL, SoundSource.NEUTRAL, 1.0F, 1.0F);
        emptyBottle.shrink(1); // 耗掉空瓶（成功路径最后一步）
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

    /**
     * 容器插入（酒窖归还杯/存酒通用，2026-11-17 用户点名堆叠）：先并入
     * <b>同类未满堆</b>（同物品同组件），再找空格——空瓶空桶还窖不再一格一个，
     * 大箱塞得下；全塞不下返回 false（调用方兜底揣兜/掉落，绝不吞）。
     */
    public static boolean insertInto(Container c, ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        int max = Math.min(stack.getMaxStackSize(), c.getMaxStackSize());
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack have = c.getItem(i);
            if (!have.isEmpty() && have.getCount() < max
                    && ItemStack.isSameItemSameComponents(have, stack)) {
                int move = Math.min(max - have.getCount(), stack.getCount());
                have.grow(move);
                stack.shrink(move);
                c.setChanged();
                if (stack.isEmpty()) {
                    return true;
                }
            }
        }
        for (int i = 0; i < c.getContainerSize(); i++) {
            if (c.getItem(i).isEmpty()) {
                c.setItem(i, stack);
                c.setChanged();
                return !c.getItem(i).isEmpty();
            }
        }
        return false;
    }

    /** 容器清点：某物品总数。 */
    public static int countItem(Container c, ItemStack sample) {
        return countIf(c, st -> ItemStack.isSameItemSameComponents(st, sample));
    }

    /** 容器内满足条件的总件数（#88：配料 tag 感知匹配用谓词口径）。 */
    public static int countIf(Container c, java.util.function.Predicate<ItemStack> test) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            if (test.test(c.getItem(i))) {
                n += c.getItem(i).getCount();
            }
        }
        return n;
    }

    /** 从容器取走一件匹配物品（真实消耗）。 */
    public static ItemStack takeOne(Container c, ItemStack sample) {
        return takeIf(c, st -> ItemStack.isSameItemSameComponents(st, sample));
    }

    /** 从容器取走一件满足条件的物品（真实消耗；#88 tag 感知）。 */
    public static ItemStack takeIf(Container c, java.util.function.Predicate<ItemStack> test) {
        int slot = findSlotIf(c, test);
        if (slot < 0) {
            return ItemStack.EMPTY;
        }
        ItemStack out = c.getItem(slot).copy();
        out.setCount(1);
        c.getItem(slot).shrink(1);
        c.setChanged();
        return out;
    }

    private static int findSlotIf(Container c, java.util.function.Predicate<ItemStack> test) {
        for (int i = 0; i < c.getContainerSize(); i++) {
            if (test.test(c.getItem(i))) {
                return i;
            }
        }
        return -1;
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

    /**
     * 温度档匹配（#85 阶段二，<b>照抄</b> BAC {@code isValidTemp} 字节码）：
     * 配方要求 1 → 桶温 ≤1；2 → ≤2；3 → 1~4（常温带）；4 → ≥4；5 → ≥5。
     * recipeTemp 0 = 配方 JSON 无温度字段（不判档，恒真）。
     */
    public static boolean tempOk(int kegTemp, int recipeTemp) {
        return switch (recipeTemp) {
            case 1 -> kegTemp <= 1;
            case 2 -> kegTemp <= 2;
            case 3 -> kegTemp >= 1 && kegTemp < 5;
            case 4 -> kegTemp >= 4;
            case 5 -> kegTemp >= 5;
            default -> true;
        };
    }

    /**
     * 收货（#85 阶段二）：取走 5 号出料槽的物品产物（肉干/泡菜等——BAC 完成
     * 分支 {@code inventory.insertItem(5, result.copy())} 的落点），清槽写回。
     * 格式铁律同 {@link #insertIngredients}（Items 只含非空槽+Slot 索引+Size）。
     * 槽空/不是酒桶格式/写回失败 → EMPTY 不动手。
     */
    public static ItemStack takeOutput(ServerLevel level, BlockPos pos) {
        BlockEntity be;
        CompoundTag root;
        try {
            be = level.getBlockEntity(pos);
            if (be == null) {
                return ItemStack.EMPTY;
            }
            root = be.saveWithFullMetadata(level.registryAccess());
        } catch (RuntimeException e) {
            return ItemStack.EMPTY; // GameTest 环境 keg BE 构造 NPE 坑，同 read 兜底
        }
        CompoundTag inv = root.getCompound(KEY_INVENTORY);
        if (!inv.contains(KEY_ITEMS, Tag.TAG_LIST)) {
            return ItemStack.EMPTY;
        }
        HolderLookup.Provider provider = level.registryAccess();
        ItemStack[] slots = new ItemStack[SLOTS];
        for (int i = 0; i < SLOTS; i++) {
            slots[i] = ItemStack.EMPTY;
        }
        ListTag list = inv.getList(KEY_ITEMS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            int idx = entry.contains(KEY_SLOT, Tag.TAG_INT) ? entry.getInt(KEY_SLOT) : i;
            if (idx >= 0 && idx < SLOTS) {
                ItemStack.parse(provider, entry).ifPresent(st -> slots[idx] = st);
            }
        }
        ItemStack out = slots[OUTPUT_SLOT];
        if (out.isEmpty()) {
            return ItemStack.EMPTY;
        }
        slots[OUTPUT_SLOT] = ItemStack.EMPTY;
        ListTag outList = new ListTag();
        for (int i = 0; i < SLOTS; i++) {
            if (!slots[i].isEmpty()) {
                CompoundTag entry = new CompoundTag();
                entry.putInt(KEY_SLOT, i);
                outList.add(slots[i].save(provider, entry));
            }
        }
        inv.put(KEY_ITEMS, outList);
        if (!inv.contains(KEY_SIZE, Tag.TAG_INT)) {
            inv.putInt(KEY_SIZE, SLOTS);
        }
        root.put(KEY_INVENTORY, inv);
        try {
            be.loadWithComponents(root, level.registryAccess());
            be.setChanged();
            net.minecraft.world.level.block.state.BlockState st = level.getBlockState(pos);
            level.sendBlockUpdated(pos, st, st, 3);
        } catch (RuntimeException e) {
            return ItemStack.EMPTY; // 写回失败不吞货（槽里留着，下轮再试）
        }
        return out;
    }
}
