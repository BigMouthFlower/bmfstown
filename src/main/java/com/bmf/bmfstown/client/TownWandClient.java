package com.bmf.bmfstown.client;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.ModDataComponents;
import com.bmf.bmfstown.network.ModNetworking;
import com.bmf.bmfstown.town.TownWandItem;
import com.bmf.bmfstown.town.TownWandMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;

import java.util.List;

/**
 * 镇界魔杖的客户端侧：滚轮切模式 + 预览渲染 + 已建区域边框。
 *
 * <h2>预览的两种形态</h2>
 * <ol>
 *   <li><b>没选点</b>：准心指着的方块表面蒙半透明绿色——"下一个点会是它"</li>
 *   <li><b>选了第 1 点</b>：第 1 点与准心方块之间的立方体整体蒙半透明白色，
 *       外加白色线框；第 2 点选定后盒子固定不再跟准心</li>
 * </ol>
 *
 * <h2>为什么渲染挂在 RenderLevelStageEvent</h2>
 * 世界渲染有固定的阶段顺序，我们必须在<b>半透明方块之后</b>画，
 * 否则会被水面、玻璃之类的半透明方块盖住。画完立刻 endBatch，
 * 保证我们的顶点这帧一定落盘，不依赖主渲染循环的批处理时机。
 */
@EventBusSubscriber(modid = BmfTown.MOD_ID, value = Dist.CLIENT)
public final class TownWandClient {

    private TownWandClient() {
    }

    // ==========================================================
    //  进存档自动同步城镇数据
    // ==========================================================

    /**
     * 玩家进世界时主动要一份城镇数据。
     *
     * <p>客户端的城镇缓存只来自服务端推送——之前只有"打开手册"这一个
     * 触发点，结果刚进存档没开过手册时，魔杖的世界边框是空的，
     * 看起来就像"功能坏了"。现在登录成功立刻补一次请求，
     * 进图拿着魔杖就能看到已生效的领地。
     */
    @SubscribeEvent
    public static void onLoggingIn(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingIn event) {
        ModNetworking.sendToServer(new ModNetworking.HandbookRequestPayload());
    }

    // ==========================================================
    //  Alt + 滚轮：切换模式
    // ==========================================================

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !Screen.hasAltDown()) {
            return; // Alt 没按着：完全不干预，滚轮正常切快捷栏
        }
        ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof TownWandItem)) {
            return; // 拿的不是魔杖：不干预
        }

        // 吃掉这次滚动（快捷栏不动），换成切模式
        event.setCanceled(true);

        // 滚轮一格 = 一个模式；先在客户端算好目标模式再发包，
        // 服务端是权威（改组件、清选点、发提示），客户端不私自改组件。
        TownWandMode cur = TownWandItem.getMode(held);
        TownWandMode next = event.getScrollDeltaY() > 0 ? cur.next() : cur.prev();
        ModNetworking.sendToServer(new ModNetworking.WandModePayload(next.ordinal()));
    }

    // ==========================================================
    //  预览渲染
    // ==========================================================

    /** 半透明色的透明度。盒子里能看到内部棱线是半透明混合的正常现象，alpha 越低越不明显。 */
    private static final int GREEN_BLOCK_ALPHA = 90;
    private static final int WHITE_FILL_ALPHA = 30;
    private static final int WHITE_LINE_ALPHA = 200;

    /**
     * 已建城镇外框的填充透明度（0-255）。
     *
     * <p>为什么这么淡：这层是"观察工具"不是"装饰"，太浓会挡住里面的
     * 建筑和镇民——玩家要透过它看 AI 行为，40（约 16%）刚好是
     * "看得出范围"和"看得清里面"的平衡点。外扩 0.008 防止与方块面 Z-fighting。
     */
    private static final int TOWN_FILL_ALPHA = 40;
    /** 子区域（食堂/住所/工作区）的填充透明度。比城镇更淡一层：它们嵌在城镇里，双重叠加不至于糊成白块。 */
    private static final int ZONE_FILL_ALPHA = 26;

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }
        ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof TownWandItem)) {
            return;
        }

        BlockPos pointA = held.get(ModDataComponents.WAND_POINT_A.get());
        BlockPos pointB = held.get(ModDataComponents.WAND_POINT_B.get());
        BlockPos aimed = getAimedBlock(mc);

        // 已创建的城镇/区域：画常驻边框 + 半透明白填充（白=城镇，彩=子区域）。
        // 注意必须在这里画——下面的"没选点"分支会提前 return，
        // 之前放在选点流程末尾，导致拿着魔杖还没选点时看不到任何已生效领地。
        renderSavedZones(event, mc, player);

        if (pointA == null) {
            // 还没选点：准心方块整体绿色 = "下一个点将是它"
            if (aimed != null) {
                renderBlockHighlight(event, mc, aimed, 0, 255, 0, GREEN_BLOCK_ALPHA);
            }
            return;
        }

        // 盒子的第二个角：第 2 点已定用它；否则用准心（实时预览）
        BlockPos b = pointB != null ? pointB : aimed;

        // 先画白盒再画绿点：都是半透明，后画的叠在上面，绿点才能盖住盒子的角
        if (b != null) {
            renderBox(event, mc, pointA, b);
        }
        // 绿色角点 1
        renderBlockHighlight(event, mc, pointA, 0, 255, 0, GREEN_BLOCK_ALPHA);
        // 绿色角点 2（已锁定的是 B；还没锁定时，准心方块就是候选）
        if (b != null && !b.equals(pointA)) {
            renderBlockHighlight(event, mc, b, 0, 255, 0, GREEN_BLOCK_ALPHA);
        }
    }

    /**
     * 已保存区域的常驻可视化。
     *
     * <p>数据来自 {@link TownHandbookClient} 的缓存（服务端推的）——
     * 所以进存档后要打开过一次手册（或建过区域）才有边框。
     *
     * <h2>线 + 面双层（v0.5）</h2>
     * 之前只画线框，观察 AI 行为时看不出"他到底在不在这个范围里"——
     * 线是空的。现在城镇外框加一层<b>半透明白色填充</b>（子区域更淡一层），
     * 镇民在不在城里一眼可见。填充压得很淡（alpha 40/26），
     * 叠上线框后是"磨砂玻璃"而不是"奶白色大箱子"。
     */
    private static void renderSavedZones(RenderLevelStageEvent event, Minecraft mc,
                                         LocalPlayer player) {
        List<TownHandbookClient.TownView> towns = TownHandbookClient.townsInDimension(
                player.level().dimension().location().toString());
        if (towns.isEmpty()) {
            return;
        }
        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);

        // ---- 先画填充面（半透明白），再画线框叠在上面 ----
        // 填充用 debugFilledBox：半透明、无深度写入，多个区域叠加不会互相糊死。
        VertexConsumer fill = mc.renderBuffers().bufferSource()
                .getBuffer(RenderType.debugFilledBox());
        Matrix4f mat = pose.last().pose();
        com.mojang.blaze3d.systems.RenderSystem.disableCull();
        for (TownHandbookClient.TownView town : towns) {
            boxFaces(fill, mat,
                    town.min().getX() - 0.008, town.min().getY() - 0.008, town.min().getZ() - 0.008,
                    town.max().getX() + 1.008, town.max().getY() + 1.008, town.max().getZ() + 1.008,
                    255, 255, 255, TOWN_FILL_ALPHA);
            for (TownHandbookClient.ZoneView zone : town.zones()) {
                boxFaces(fill, mat,
                        zone.min().getX() - 0.008, zone.min().getY() - 0.008, zone.min().getZ() - 0.008,
                        zone.max().getX() + 1.008, zone.max().getY() + 1.008, zone.max().getZ() + 1.008,
                        255, 255, 255, ZONE_FILL_ALPHA);
            }
        }
        com.mojang.blaze3d.systems.RenderSystem.enableCull();
        mc.renderBuffers().bufferSource().endBatch(RenderType.debugFilledBox());

        // ---- 线框（白=城镇，彩=子区域，颜色与手册一致） ----
        VertexConsumer line = mc.renderBuffers().bufferSource().getBuffer(RenderType.lines());
        for (TownHandbookClient.TownView town : towns) {
            // 城镇外框：白
            lineBox(line, pose, town.min(), town.max(), 1.0F, 1.0F, 1.0F);
            for (TownHandbookClient.ZoneView zone : town.zones()) {
                float[] c = zoneLineColor(zone.kind());
                lineBox(line, pose, zone.min(), zone.max(), c[0], c[1], c[2]);
            }
        }
        mc.renderBuffers().bufferSource().endBatch(RenderType.lines());
        pose.popPose();
    }

    private static void lineBox(VertexConsumer line, PoseStack pose,
                                BlockPos min, BlockPos max,
                                float r, float g, float b) {
        net.minecraft.client.renderer.LevelRenderer.renderLineBox(pose, line,
                min.getX(), min.getY(), min.getZ(),
                max.getX() + 1, max.getY() + 1, max.getZ() + 1,
                r, g, b, 0.6F);
    }

    private static float[] zoneLineColor(int kind) {
        return switch (TownWandMode.byIndex(kind)) {
            case DINING -> new float[]{1.0F, 0.65F, 0.0F};   // 橙
            case HOUSING -> new float[]{0.33F, 1.0F, 1.0F};  // 青
            case WORK -> new float[]{0.65F, 1.0F, 0.33F};    // 绿
            default -> new float[]{1.0F, 1.0F, 1.0F};
        };
    }

    /** 准心指着的方块；没指方块（天上/太远）返回 null。 */
    private static BlockPos getAimedBlock(Minecraft mc) {
        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult blockHit
                && hit.getType() == HitResult.Type.BLOCK) {
            return blockHit.getBlockPos();
        }
        return null;
    }

    // ==========================================================
    //  绘制原语
    // ==========================================================

    /**
     * 整个方块蒙一层半透明色（6 个面全包），作为"待选点"的标记。
     *
     * <p>整体外扩一点点，避免与方块面 Z-fighting。
     * 这是"角点"的视觉语言：两个绿块夹一个白盒子。
     */
    private static void renderBlockHighlight(RenderLevelStageEvent event, Minecraft mc,
                                             BlockPos pos, int r, int g, int b, int a) {
        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        pose.pushPose();
        // 世界坐标 → 相机相对坐标。RenderLevelStageEvent 的 PoseStack
        // 只做了旋转没做平移，必须自己减掉相机位置。
        pose.translate(-cam.x, -cam.y, -cam.z);

        double o = 0.008;
        double x0 = pos.getX() - o, y0 = pos.getY() - o, z0 = pos.getZ() - o;
        double x1 = pos.getX() + 1 + o, y1 = pos.getY() + 1 + o, z1 = pos.getZ() + 1 + o;

        VertexConsumer buf = mc.renderBuffers().bufferSource()
                .getBuffer(RenderType.debugFilledBox());
        Matrix4f mat = pose.last().pose();
        // 关面剔除：六个面都要从各个角度可见
        com.mojang.blaze3d.systems.RenderSystem.disableCull();
        boxFaces(buf, mat, x0, y0, z0, x1, y1, z1, r, g, b, a);
        com.mojang.blaze3d.systems.RenderSystem.enableCull();
        mc.renderBuffers().bufferSource().endBatch(RenderType.debugFilledBox());

        pose.popPose();
    }

    /**
     * 画一个半透明实心盒 + 白色线框。
     *
     * @param a 角 1（世界方块坐标）
     * @param b 角 2（世界方块坐标，含它在内的整格）
     */
    private static void renderBox(RenderLevelStageEvent event, Minecraft mc,
                                  BlockPos a, BlockPos b) {
        int minX = Math.min(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ());
        int maxX = Math.max(a.getX(), b.getX()) + 1;
        int maxY = Math.max(a.getY(), b.getY()) + 1;
        int maxZ = Math.max(a.getZ(), b.getZ()) + 1;

        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f mat = pose.last().pose();

        // ---- 半透明填充（6 个面）----
        VertexConsumer fill = mc.renderBuffers().bufferSource()
                .getBuffer(RenderType.debugFilledBox());
        com.mojang.blaze3d.systems.RenderSystem.disableCull();
        boxFaces(fill, mat, minX, minY, minZ, maxX, maxY, maxZ,
                255, 255, 255, WHITE_FILL_ALPHA);
        com.mojang.blaze3d.systems.RenderSystem.enableCull();
        mc.renderBuffers().bufferSource().endBatch(RenderType.debugFilledBox());

        // ---- 白色线框（12 条边）----
        VertexConsumer line = mc.renderBuffers().bufferSource()
                .getBuffer(RenderType.lines());
        LevelRenderer.renderLineBox(pose, line,
                minX, minY, minZ, maxX, maxY, maxZ,
                1.0F, 1.0F, 1.0F, WHITE_LINE_ALPHA / 255.0F);
        mc.renderBuffers().bufferSource().endBatch(RenderType.lines());

        pose.popPose();
    }

    /** 画盒子的全部 6 个面（12 个三角形），面方向不重要（已关剔除）。 */
    private static void boxFaces(VertexConsumer buf, Matrix4f mat,
                                 double x0, double y0, double z0,
                                 double x1, double y1, double z1,
                                 int r, int g, int b, int a) {
        // 每个面 4 角（双向绘制由 disableCull 保证）
        double[][][] faces = {
                // 南北面（z = 常量）
                {{x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0}},
                {{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}},
                // 东西面（x = 常量）
                {{x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}},
                {{x1, y0, z0}, {x1, y0, z1}, {x1, y1, z1}, {x1, y1, z0}},
                // 顶底面（y = 常量）
                {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}},
                {{x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}},
        };
        for (double[][] f : faces) {
            for (int i : new int[]{0, 1, 2, 0, 2, 3}) {
                double[] c = f[i];
                buf.addVertex(mat, (float) c[0], (float) c[1], (float) c[2])
                        .setColor(r, g, b, a);
            }
        }
    }
}
