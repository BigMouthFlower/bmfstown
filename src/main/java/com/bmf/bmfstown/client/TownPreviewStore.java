package com.bmf.bmfstown.client;

import com.bmf.bmfstown.BmfTown;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.LevelResource;
import com.mojang.blaze3d.platform.NativeImage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 领地预览图：创建城镇时截玩家主视角，压成小图存进<b>存档目录</b>，
 * 手册右页展示。
 *
 * <h2>存哪里（玩家可按层级自己找/替换贴图）</h2>
 * <pre>存档文件夹/bmfstown/towns/&lt;名字净化后的文件名&gt;.png</pre>
 * 例如 {@code saves/新世界/bmfstown/towns/新城镇1.png}。
 * 想换图的玩家把同名的 PNG 放回去即可（比例随意，UI 会按图的比例绘制）。
 *
 * <h2>改名/删除要跟着动（不能留野文件、不能空指针）</h2>
 * {@link #syncFiles} 在每次手册数据同步时对比新旧名字表：
 * 名字变了 → 移动文件；城镇没了 → 删文件并释放显存里的纹理。
 *
 * <h2>为什么在客户端截图</h2>
 * "玩家主视角"只有客户端有。仅单人游戏（内置服务端）能把图写进
 * 存档目录；联机时没有存档目录写权限，跳过截图，手册里显示占位
 * 文字——功能降级但不出错。
 *
 * <h2>为什么用最近邻采样压像素</h2>
 * 用户要求"不用很清晰、等比例压缩像素"——最近邻就是最原始的
 * 像素化压缩，还省一次缩放滤波的开销。
 */
public final class TownPreviewStore {

    /** UI 里绘制宽度（纸面上）。图本身存半分辨率，绘制时再缩小。 */
    public static final int DRAW_WIDTH = 96;

    /** 已注册的动态纹理（id → 纹理位置）。 */
    private static final Map<UUID, ResourceLocation> TEXTURES = new HashMap<>();
    /** 纹理尺寸缓存（id → [宽, 高]），blit 时要用。 */
    private static final Map<UUID, int[]> SIZES = new HashMap<>();

    private TownPreviewStore() {
    }

    // ==========================================================
    //  路径
    // ==========================================================

    /** 存档目录下的 towns 文件夹（仅单人游戏有效）。 */
    private static Path townsDir(Minecraft mc) {
        return mc.getSingleplayerServer()
                .getWorldPath(new LevelResource("bmfstown/towns"));
    }

    /** 城镇对应的预览图文件。文件名 = 净化后的城镇名（改名会跟着变）。 */
    private static Path fileOf(Minecraft mc, String name) {
        String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        return townsDir(mc).resolve(safe + ".png");
    }

    // ==========================================================
    //  截图（创建城镇时调用）
    // ==========================================================

    /**
     * 截当前主视角 → 缩小一半分辨率 → 存档 → 注册纹理。
     * 在手册打开<b>之前</b>调用（画面还是游戏世界）。
     */
    public static void capture(Minecraft mc, TownHandbookClient.TownView town) {
        if (mc.getSingleplayerServer() == null) {
            return; // 联机端写不了存档目录：静默降级为"无预览图"
        }
        if (TEXTURES.containsKey(town.id())) {
            return; // 已有图（同一城镇重复推送），不重截
        }
        Path file = fileOf(mc, town.name());
        try {
            Files.createDirectories(file.getParent());
            // 1. 抓主帧缓冲
            NativeImage shot = Screenshot.takeScreenshot(mc.getMainRenderTarget());
            try {
                int sw = shot.getWidth(), sh = shot.getHeight();
                // 压缩一半分辨率（等比例）
                int tw = Math.max(1, sw / 2), th = Math.max(1, sh / 2);
                NativeImage small = new NativeImage(tw, th, false);
                try {
                    for (int y = 0; y < th; y++) {
                        int sy = Math.min(sh - 1, y * 2);
                        for (int x = 0; x < tw; x++) {
                            // 1.21.1 公开的是 RGBA 版本，读写成对使用格式一致
                            small.setPixelRGBA(x, y,
                                    shot.getPixelRGBA(x * 2, sy));
                        }
                    }
                    // 2. 写进存档目录（层级：存档/bmfstown/towns/）
                    small.writeToFile(file);
                } finally {
                    small.close();
                }
            } finally {
                shot.close();
            }
            registerTexture(mc, town.id(), file);
        } catch (Exception e) {
            BmfTown.LOGGER.warn("[BmfTown] 城镇预览图截取失败：{}", town.name(), e);
        }
    }

    /** 从文件读图并注册成动态纹理。 */
    private static void registerTexture(Minecraft mc, UUID id, Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            NativeImage img = NativeImage.read(in);
            ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(
                    BmfTown.MOD_ID, "town_preview/" + id);
            mc.getTextureManager().register(rl, new DynamicTexture(img));
            TEXTURES.put(id, rl);
            SIZES.put(id, new int[]{img.getWidth(), img.getHeight()});
        } catch (IOException e) {
            BmfTown.LOGGER.warn("[BmfTown] 预览图读取失败：{}", file, e);
        }
    }

    /**
     * 懒加载：进档后第一次打开手册时，把存档里已有的预览图
     * 注册成纹理（只对还没纹理的城镇生效，重复调用无害）。
     */
    public static void ensureLoaded(Minecraft mc, TownHandbookClient.TownView town) {
        if (TEXTURES.containsKey(town.id()) || mc.getSingleplayerServer() == null) {
            return;
        }
        Path file = fileOf(mc, town.name());
        if (Files.exists(file)) {
            registerTexture(mc, town.id(), file);
        }
    }

    // ==========================================================
    //  手册查询（屏幕渲染用）
    // ==========================================================

    /** 预览纹理与尺寸；没有图（联机/截取失败/文件被删）返回 null。 */
    public static Preview preview(UUID townId) {
        ResourceLocation rl = TEXTURES.get(townId);
        int[] size = SIZES.get(townId);
        if (rl == null || size == null) {
            return null;
        }
        return new Preview(rl, size[0], size[1]);
    }

    public record Preview(ResourceLocation texture, int width, int height) {
    }

    // ==========================================================
    //  改名 / 删除时同步文件
    // ==========================================================

    /**
     * 对比新旧城镇表：改名 → 移动文件；删除 → 删文件 + 释放纹理。
     * 每次手册数据同步都调用，保证文件状态与城镇数据一致。
     */
    public static void syncFiles(Minecraft mc,
                                 List<TownHandbookClient.TownView> oldTowns,
                                 List<TownHandbookClient.TownView> newTowns) {
        if (mc.getSingleplayerServer() == null) {
            return; // 联机端没管过文件
        }
        Map<UUID, String> oldNames = new HashMap<>();
        for (TownHandbookClient.TownView t : oldTowns) {
            oldNames.put(t.id(), t.name());
        }
        for (TownHandbookClient.TownView t : newTowns) {
            String oldName = oldNames.remove(t.id());
            if (oldName == null) {
                continue; // 新建的，capture() 已处理
            }
            if (!oldName.equals(t.name())) {
                // 改名：文件跟着改名（目标已存在就覆盖，防死锁在异常状态）
                try {
                    Files.move(fileOf(mc, oldName), fileOf(mc, t.name()),
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    BmfTown.LOGGER.warn("[BmfTown] 预览图改名失败：{} → {}",
                            oldName, t.name(), e);
                }
            }
        }
        // oldNames 里剩下的 = 已删除的城镇：清文件 + 释放纹理
        for (UUID removed : oldNames.keySet()) {
            try {
                Files.deleteIfExists(fileOf(mc, oldNames.get(removed)));
            } catch (IOException e) {
                BmfTown.LOGGER.warn("[BmfTown] 预览图删除失败", e);
            }
            ResourceLocation rl = TEXTURES.remove(removed);
            int[] size = SIZES.remove(removed);
            if (rl != null) {
                mc.getTextureManager().release(rl);
            }
            if (size == null) {
                // 纹理本来就没有，无事可做
            }
        }
    }
}
