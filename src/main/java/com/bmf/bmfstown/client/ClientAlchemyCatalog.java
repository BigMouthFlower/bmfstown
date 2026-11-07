package com.bmf.bmfstown.client;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.alchemy.Potion;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;

/**
 * 客户端炼金目录缓存（v0.12 炼金）：客户端的 PotionBrewing 是 vanilla
 * 自举的独立实例（不含 mod 注册的秘传 mix），反查不可靠——目录由服务端
 * 在登录/数据包同步时推送（{@code ModNetworking.AlchemyCatalogSyncPayload}），
 * 这里只存"药水注册名 + 变体可用性"，显示名本地按同步过的注册表解析。
 */
public final class ClientAlchemyCatalog {

    /** 一条可酿药水：注册名 + 延时/强化/喷溅/滞留变体是否存在。 */
    public record Entry(String potionId, boolean hasLong, boolean hasStrong,
                        boolean hasSplash, boolean hasLingering) {
    }

    private static volatile List<Entry> entries = List.of();

    private ClientAlchemyCatalog() {
    }

    /** 接收服务端目录（条目 "potionId|hasLong|hasStrong"）。 */
    public static void accept(List<String> raw) {
        List<Entry> out = new java.util.ArrayList<>();
        for (String line : raw) {
            String[] parts = line.split("\\|");
            if (parts.length == 5) {
                out.add(new Entry(parts[0], parts[1].equals("1"), parts[2].equals("1"),
                        parts[3].equals("1"), parts[4].equals("1")));
            }
        }
        entries = List.copyOf(out);
    }

    public static List<Entry> entries() {
        return entries;
    }

    /** 显示名（本地化；注册表是同步的，键→Holder→Potion.getName 拼键）。 */
    public static String displayName(String potionId) {
        Holder<Potion> holder = holderOf(potionId);
        if (holder == null) {
            return potionId;
        }
        return net.minecraft.client.resources.language.I18n.get(
                Potion.getName(Optional.of(holder), "item.minecraft.potion.effect."));
    }

    /**
     * 订单键（"alch|<potionId>|<mod>"）→ 最终产物展示物品（容器=最终瓶、
     * 内容=最终药水档；变体在注册表不存在时逐级回落）。工人订单槽同款用法。
     */
    public static net.minecraft.world.item.ItemStack displayStackForKey(String orderKey) {
        if (orderKey == null || !orderKey.startsWith("alch|")) {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }
        String[] parts = orderKey.split("\\|");
        if (parts.length != 3) {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }
        String potionPart = "none";
        String contPart = "none";
        for (String part : parts[2].split("\\+")) {
            if (part.equals("long") || part.equals("strong")) {
                potionPart = part;
            } else if (part.equals("splash") || part.equals("lingering")) {
                contPart = part;
            }
        }
        return displayStack(parts[1], potionPart, contPart);
    }

    /** 按药水注册名 + 修饰档构建展示瓶（变体缺失回落基础档）。 */
    public static net.minecraft.world.item.ItemStack displayStack(String potionId,
            String potionMod, String containerMod) {
        Holder<Potion> base = holderOf(potionId);
        if (base == null) {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }
        Holder<Potion> finalP = base;
        if (potionMod.equals("long")) {
            Holder<Potion> h = holderOf(potionId + "_long");
            if (h != null) {
                finalP = h;
            }
        } else if (potionMod.equals("strong")) {
            Holder<Potion> h = holderOf(potionId + "_strong");
            if (h != null) {
                finalP = h;
            }
        }
        // 容器档：喷溅/滞留换瓶子物品（vanilla 注册名规则：基础药水 + _long/_strong 后缀）
        net.minecraft.world.item.Item container = switch (containerMod) {
            case "splash" -> net.minecraft.world.item.Items.SPLASH_POTION;
            case "lingering" -> net.minecraft.world.item.Items.LINGERING_POTION;
            default -> net.minecraft.world.item.Items.POTION;
        };
        return net.minecraft.world.item.alchemy.PotionContents.createItemStack(container, finalP);
    }

    /** 弹层格子图标：普通药水瓶 + 对应药水内容（渲染出原版染色瓶）。 */
    public static net.minecraft.world.item.ItemStack iconStack(Entry e) {
        Holder<Potion> h = holderOf(e.potionId());
        if (h == null) {
            return new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.POTION);
        }
        return net.minecraft.world.item.alchemy.PotionContents.createItemStack(
                net.minecraft.world.item.Items.POTION, h);
    }

    @Nullable
    public static Holder<Potion> holderOf(String potionId) {
        try {
            return BuiltInRegistries.POTION.getHolder(ResourceLocation.parse(potionId))
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
