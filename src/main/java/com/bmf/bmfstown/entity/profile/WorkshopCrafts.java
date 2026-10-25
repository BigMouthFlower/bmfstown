package com.bmf.bmfstown.entity.profile;

import com.bmf.bmfstown.BmfTown;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工坊配方体系（v0.8.1）：白名单圈产物域，材料/产量从游戏配方反查。
 *
 * <h2>为什么不手写配方表（用户定稿）</h2>
 * 手写 = 凭记忆猜配方，猜错一次改一次没完没了。游戏配方本来就在
 * {@code RecipeManager} 里，白名单只回答"工人能做哪些产物"，
 * 材料与产量从真实配方<b>实时反查</b>——原版/数据包改配方，这里自动跟。
 * 将来跨 mod（如机械动力）支持 = 往白名单加产物，零配方代码。
 *
 * <h2>分域（用户定稿：按信号语义）</h2>
 * <ul>
 *   <li><b>tier 0 · 信号源</b>：自己发出信号——人人可做；</li>
 *   <li><b>tier 1 · 布线</b>：传递/改变信号——Lv6 选"布线"解锁；</li>
 *   <li><b>tier 2 · 机关</b>：接收信号被驱动——Lv6 选"机关"解锁。</li>
 * </ul>
 *
 * <h2>缓存与失效</h2>
 * 目录按 {@code RecipeManager} 反查结果缓存；数据包同步完成时失效
 * （{@link OnDatapackSyncEvent}），下次访问自动用新配方重建。
 */
@EventBusSubscriber(modid = BmfTown.MOD_ID)
public final class WorkshopCrafts {

    // ---- 白名单：产物 → 线别（0 信号源 / 1 布线 / 2 机关） ----

    private static final Map<Item, Integer> LINES = new LinkedHashMap<>();

    static {
        LINES.put(net.minecraft.world.item.Items.REDSTONE_TORCH, 0);
        LINES.put(net.minecraft.world.item.Items.LEVER, 0);
        LINES.put(net.minecraft.world.item.Items.TRIPWIRE_HOOK, 0);
        LINES.put(net.minecraft.world.item.Items.DAYLIGHT_DETECTOR, 0);
        LINES.put(net.minecraft.world.item.Items.TARGET, 0);
        LINES.put(net.minecraft.world.item.Items.REPEATER, 1);
        LINES.put(net.minecraft.world.item.Items.COMPARATOR, 1);
        LINES.put(net.minecraft.world.item.Items.OBSERVER, 1);
        LINES.put(net.minecraft.world.item.Items.PISTON, 2);
        LINES.put(net.minecraft.world.item.Items.STICKY_PISTON, 2);
        LINES.put(net.minecraft.world.item.Items.DROPPER, 2);
        LINES.put(net.minecraft.world.item.Items.DISPENSER, 2);
        LINES.put(net.minecraft.world.item.Items.NOTE_BLOCK, 2);
        LINES.put(net.minecraft.world.item.Items.REDSTONE_LAMP, 2);
        LINES.put(net.minecraft.world.item.Items.HOPPER, 2);
    }

    /** 白名单里是否含该产物。 */
    public static boolean isWhitelisted(Item item) {
        return LINES.containsKey(item);
    }

    /** 该镇民当前是否被允许做该产物（基础档人人可做；布线/机关看大师分支）。 */
    public static boolean lineAllowed(ResidentProfile profile, Item item) {
        int line = LINES.getOrDefault(item, 0);
        if (line == 0) {
            return true;
        }
        String master = profile.perkChoice(Perks.NODE_W_MASTER);
        return line == 1 ? "wiring".equals(master) : "mechanism".equals(master);
    }

    /** 白名单条目（保序，按线别分域的插入序）。 */
    public static List<Map.Entry<Item, Integer>> whitelist() {
        return List.copyOf(LINES.entrySet());
    }

    // ==========================================================
    //  目录：从 RecipeManager 反查（服务端）
    // ==========================================================

    /** 缓存的目录。 */
    private static volatile List<WorkshopCraft> cache;

    /** 数据包重载完成：配方可能变了，目录作废重建。 */
    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        cache = null;
    }

    /**
     * 反查目录：遍历全部工作台配方，产物在白名单里的收入目录。
     * 同产物多条配方取第一条；无材料配方（异常数据）跳过。
     */
    public static List<WorkshopCraft> catalog(RecipeManager recipes, RegistryAccess access) {
        List<WorkshopCraft> cached = cache;
        if (cached != null) {
            return cached;
        }
        List<WorkshopCraft> list = new ArrayList<>();
        for (RecipeHolder<CraftingRecipe> holder : recipes.getAllRecipesFor(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = holder.value();
            ItemStack result = recipe.getResultItem(access);
            if (result.isEmpty() || !LINES.containsKey(result.getItem())) {
                continue;
            }
            // 非空材料槽按实例归并计数（原版 shaped 配方同材料共享实例）
            Map<Ingredient, Integer> merged = new LinkedHashMap<>();
            for (Ingredient ing : recipe.getIngredients()) {
                if (ing == Ingredient.EMPTY) {
                    continue;
                }
                merged.merge(ing, 1, Integer::sum);
            }
            if (merged.isEmpty()) {
                continue;
            }
            Item item = result.getItem();
            List<WorkshopCraft.Req> reqs = merged.entrySet().stream()
                    .map(e -> new WorkshopCraft.Req(e.getKey(), e.getValue()))
                    .toList();
            list.add(new WorkshopCraft(item, result.getCount(),
                    LINES.getOrDefault(item, 0), reqs));
        }
        list.sort(Comparator
                .comparingInt(WorkshopCraft::tier)
                .thenComparing(c -> c.result().toString()));
        cache = List.copyOf(list);
        return cache;
    }

    /** 按产物注册名找目录条目（找不到 = 该产物没有可反查的配方）。 */
    public static WorkshopCraft byProduct(RecipeManager recipes, RegistryAccess access,
            String productId) {
        for (WorkshopCraft craft : catalog(recipes, access)) {
            if (BuiltInItemKey.of(craft.result()).equals(productId)) {
                return craft;
            }
        }
        return null;
    }

    /** 物品 → 注册名（"minecraft:repeater" 口径）。 */
    public static String keyOf(Item item) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private WorkshopCrafts() {
    }

    /** 注册名字符串与物品互转的小工具（坏 id 回 AIR，调用方判 isEmpty）。 */
    public static final class BuiltInItemKey {
        private BuiltInItemKey() {
        }

        public static String of(Item item) {
            return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
        }

        public static Item item(String id) {
            return net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .get(ResourceLocation.parse(id));
        }
    }
}
