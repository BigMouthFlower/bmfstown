package com.bmf.bmfstown.client;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.ModDataComponents;
import com.bmf.bmfstown.network.ModNetworking;
import com.bmf.bmfstown.town.TownWandItem;
import com.bmf.bmfstown.town.TownWandMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
 * <h2>预览的三种形态</h2>
 * <ol>
 *   <li><b>没选点</b>：准心指着的方块表面蒙半透明绿色——"下一个点会是它"；
 *       准星落进某个已保存范围时，该范围的棱边加粗描亮（悬停高亮）</li>
 *   <li><b>选了第 1 点</b>：第 1 点与准心方块之间的盒子（棋盘格半透明面 +
 *       细长方体棱边，颜色随模式），由 {@link ZoneOutliner} 平滑追踪准心；
 *       第 2 点选定后盒子固定不再跟准心</li>
 *   <li><b>切走魔杖</b>：不再保活，选框 8 tick 淡出消失</li>
 * </ol>
 *
 * <h2>绘制分工</h2>
 * 半透明填充面（debugFilledBox，只画朝相机的面）与绿色角点沿用本项目原语；
 * 棱边与棋盘格面改走 {@link ZoneOutliner}/{@link OutlineRenderTypes}
 * （体素棱 + 纹理面 + chase 插值）。各自成批、各自 endBatch，互不混批。
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
    //  右键选点 / 确认：客户端权威接管
    // ==========================================================

    /**
     * 为什么选点不走服务端 useOn（血泪教训）：
     * <ol>
     *   <li><b>潜行竞速</b>：shift+右键时，"开始潜行"包和"使用物品"包
     *       同 tick 发出，服务端可能先处理点击——isShiftKeyDown 为 false，
     *       确认点击被当成选点，选区变成"包含脚下方块的薄片"。</li>
     *   <li><b>高处选点被静默拒收</b>：1.20.5+ 服务端会拒绝够不到的方块的
     *       使用请求且无任何提示——选房间上方角块时第 2 点悄悄丢了。</li>
     * </ol>
     * 所以：潜行状态用客户端键盘状态（100% 准），准星方块用客户端命中结果
     * （所见即所得），打包发给服务端执行；同时取消原版交互，
     * 让服务端 useOn 兜底路径永远不被脏数据触发。
     */
    private static long nextClickMs = 0; // 按住右键连发节流

    @SubscribeEvent
    public static void onUseKey(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !event.isUseItem()) {
            return;
        }
        ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof TownWandItem)) {
            return;
        }

        // 这次右键由本 mod 全权处理，原版的"使用物品"包不再发出
        event.setCanceled(true);

        long now = System.currentTimeMillis();
        if (now < nextClickMs) {
            return; // 按住不放每 4 tick 触发一次，250ms 内的重复全忽略
        }
        nextClickMs = now + 250;

        BlockPos a = held.get(ModDataComponents.WAND_POINT_A.get());
        BlockPos b = held.get(ModDataComponents.WAND_POINT_B.get());

        // 潜行 + 右键 = 确认。客户端自己读键盘，绝无竞速问题。
        if (player.isShiftKeyDown()) {
            if (a == null && b == null) {
                return; // 没选点：静默，不刷"请先选点"
            }
            com.bmf.bmfstown.BmfTown.LOGGER.info(
                    "[BmfTown][客户端] 发送确认包（潜行，A={}，B={}）",
                    a == null ? "未选" : a.toShortString(),
                    b == null ? "未选" : b.toShortString());
            ModNetworking.sendToServer(new ModNetworking.WandConfirmPayload());
            return;
        }

        BlockPos aimed = getAimedBlock(mc);
        if (aimed == null) {
            return;
        }
        TownWandMode mode = TownWandItem.getMode(held);
        if (a == null) {
            com.bmf.bmfstown.BmfTown.LOGGER.info(
                    "[BmfTown][客户端] 发送第1点 = {}", aimed.toShortString());
            ModNetworking.sendToServer(new ModNetworking.WandPointPayload(0, aimed));
            player.displayClientMessage(Component.translatable(
                    "msg.bmfstown.wand.point1", TownWandItem.modeDisplay(mode),
                    TownWandItem.fmt(aimed)), true);
        } else if (b == null) {
            if (aimed.equals(a)) {
                player.displayClientMessage(Component.translatable(
                        "msg.bmfstown.wand.point_same"), true);
                return;
            }
            ModNetworking.sendToServer(new ModNetworking.WandPointPayload(1, aimed));
            com.bmf.bmfstown.BmfTown.LOGGER.info(
                    "[BmfTown][客户端] 发送第2点 = {}（第1点 = {}）",
                    aimed.toShortString(), a.toShortString());
            String size = (Math.abs(aimed.getX() - a.getX()) + 1) + "x"
                    + (Math.abs(aimed.getY() - a.getY()) + 1) + "x"
                    + (Math.abs(aimed.getZ() - a.getZ()) + 1);
            player.displayClientMessage(Component.translatable(
                    "msg.bmfstown.wand.point2", TownWandItem.modeDisplay(mode),
                    TownWandItem.fmt(aimed), size), true);
        } else {
            // 两点已齐：普通右键不覆盖（防手滑），想重选就切走魔杖再回来
            player.displayClientMessage(Component.translatable(
                    "msg.bmfstown.wand.both_set"), true);
        }
    }

    // ==========================================================
    //  预览渲染
    // ==========================================================

    /** 半透明色的透明度。盒子里能看到内部棱线是半透明混合的正常现象，alpha 越低越不明显。 */
    private static final int GREEN_BLOCK_ALPHA = 90;
    /** 划区预览棋盘格面的透明度（0~1）：能看出"这是个范围"又不挡建筑。 */
    private static final float SELECTION_FACE_ALPHA = 0.30F;
    /** 已保存区域常驻棱边的透明度：比线框时代的 0.6 实一些（体素棱更细）。 */
    private static final float BORDER_EDGE_ALPHA = 0.85F;

    /** 选区预览在 ZoneOutliner 里的槽位键（切走魔杖后靠 TTL 淡出消失）。 */
    private static final Object SLOT_WAND = new Object();

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
        boolean holdingWand = held.getItem() instanceof TownWandItem;
        // 副手拿魔杖也能看到已创建区域（用户定稿）：主手要干活放方块时，
        // 魔杖挂副手当"领地眼镜"用。选点/悬停等交互预览仍只认主手。
        boolean offhandWand = player.getOffhandItem().getItem() instanceof TownWandItem;
        BlockPos pointA = holdingWand ? held.get(ModDataComponents.WAND_POINT_A.get()) : null;
        BlockPos pointB = holdingWand ? held.get(ModDataComponents.WAND_POINT_B.get()) : null;
        BlockPos aimed = getAimedBlock(mc);

        // 已创建的城镇/区域：画常驻填充 + 细长方体棱边（白=城镇，彩=子区域）。
        // 主手或副手任一拿魔杖才可见——不做常亮，正常建房时视野干净。
        if (holdingWand || offhandWand) {
            renderSavedZones(event, mc, player);
        }
        // 区域悬停：拿着魔杖还没选点时，准星所在区域描亮一层（"你正看的是它"）
        if (holdingWand && pointA == null) {
            renderHoverHighlight(event, mc, player, aimed);
        }

        if (!holdingWand) {
            return; // 魔杖不在手：什么都不追，选框靠 TTL 自己淡出
        }

        if (pointA == null) {
            // 还没选点：准心方块整体绿色 = "下一个点将是它"
            if (aimed != null) {
                renderBlockHighlight(event, mc, aimed, 0, 255, 0, GREEN_BLOCK_ALPHA);
            }
            return;
        }

        // 盒子的第二个角：第 2 点已定用它；否则用准心（实时预览）。
        // 目标盒交给 ZoneOutliner 追：准心移动时盒子平滑滑过去，不逐格跳。
        BlockPos b = pointB != null ? pointB : aimed;

        if (b != null) {
            TownWandMode mode = TownWandItem.getMode(held);
            float[] c = zoneLineColor(mode.ordinal());
            ZoneOutliner.chase(SLOT_WAND, selectionBox(pointA, b),
                    ZoneOutliner.Style.boxed(c[0], c[1], c[2],
                            ZoneOutliner.EDGE_WIDTH, SELECTION_FACE_ALPHA));
        }

        // 绿色角点 1
        renderBlockHighlight(event, mc, pointA, 0, 255, 0, GREEN_BLOCK_ALPHA);
        // 绿色角点 2（已锁定的是 B；还没锁定时，准心方块就是候选）
        if (b != null && !b.equals(pointA)) {
            renderBlockHighlight(event, mc, b, 0, 255, 0, GREEN_BLOCK_ALPHA);
        }

        // 统一落盘所有追踪盒（面垫底、棱边叠上）
        com.mojang.blaze3d.vertex.PoseStack pose = event.getPoseStack();
        net.minecraft.world.phys.Vec3 cam = event.getCamera().getPosition();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        // 1.21.1 的 getPartialTick() 是 DeltaTracker；取"游戏刻插值"分量（世界渲染同款口径）
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        ZoneOutliner.renderAll(pose, cam, pt);
        pose.popPose();
    }

    /**
     * 选区目标盒（方块坐标含端点 → AABB：max 侧 +1 进整格）。
     * 外扩防 z-fighting 由 ZoneOutliner 在渲染时统一处理。
     */
    private static AABB selectionBox(BlockPos a, BlockPos b) {
        return new AABB(
                Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()) + 1, Math.max(a.getY(), b.getY()) + 1, Math.max(a.getZ(), b.getZ()) + 1);
    }

    /**
     * 已保存区域的常驻可视化。
     *
     * <p>数据来自 {@link TownHandbookClient} 的缓存（服务端推的）——
     * 所以进存档后要打开过一次手册（或建过区域）才有边框。
     *
     * <h2>只画棱边，不画填充面（用户实测定稿）</h2>
     * v0.5~v0.11 曾带一层半透明白填充，但多个区域相邻时，debugFilledBox
     * 的三角面在接缝处互相透视出"对角线"，多盒子叠加没法看。
     * 体素棱边本身够粗够亮，范围感不靠填充也能立住，填充整个移除。
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

        // ---- 棱边（细长方体；白=城镇，彩=子区域，颜色与手册一致） ----
        // 比 1px 线框粗一档：远看/缩放后仍能看清边界，全亮度不随环境光变暗。
        VertexConsumer edges = mc.renderBuffers().bufferSource()
                .getBuffer(OutlineRenderTypes.edge());
        for (TownHandbookClient.TownView town : towns) {
            // 城镇外框：白（追加区块与主外框同色）
            ZoneOutliner.renderEdges(pose.last(), edges, savedBox(town.min(), town.max()),
                    1.0F, 1.0F, 1.0F, BORDER_EDGE_ALPHA, ZoneOutliner.EDGE_WIDTH);
            for (TownHandbookClient.ZoneView block : town.blocks()) {
                ZoneOutliner.renderEdges(pose.last(), edges, savedBox(block.min(), block.max()),
                        1.0F, 1.0F, 1.0F, BORDER_EDGE_ALPHA, ZoneOutliner.EDGE_WIDTH);
            }
            for (TownHandbookClient.ZoneView zone : town.zones()) {
                float[] c = zoneLineColor(zone.kind());
                ZoneOutliner.renderEdges(pose.last(), edges, savedBox(zone.min(), zone.max()),
                        c[0], c[1], c[2], BORDER_EDGE_ALPHA, ZoneOutliner.EDGE_WIDTH);
            }

            // ---- 工坊登记方块（v0.12，用户追加）：黄=容器，紫=工作站 ----
            // 与登记会话同一套颜色语言，透明度略降（这是观察态，别抢过会话编辑态）
            for (Long p : town.workshopContainers()) {
                ZoneOutliner.renderEdges(pose.last(), edges, blockBox(BlockPos.of(p)),
                        1.0F, 0.85F, 0.3F, 0.75F, ZoneOutliner.EDGE_WIDTH);
            }
            for (Long p : town.workshopStations()) {
                ZoneOutliner.renderEdges(pose.last(), edges, blockBox(BlockPos.of(p)),
                        0.75F, 0.5F, 1.0F, 0.75F, ZoneOutliner.EDGE_WIDTH);
            }
        }
        mc.renderBuffers().bufferSource().endBatch(OutlineRenderTypes.edge());
        pose.popPose();
    }

    /** 单方块小盒（轻微外扩防 z-fighting）。 */
    private static net.minecraft.world.phys.AABB blockBox(BlockPos pos) {
        return new net.minecraft.world.phys.AABB(
                pos.getX() - 0.02, pos.getY() - 0.02, pos.getZ() - 0.02,
                pos.getX() + 1.02, pos.getY() + 1.02, pos.getZ() + 1.02);
    }

    /** 已保存区域的原语盒：方块坐标含端点，整体外扩防 z-fighting。 */
    private static AABB savedBox(BlockPos min, BlockPos max) {
        return new AABB(min.getX() - 0.008, min.getY() - 0.008, min.getZ() - 0.008,
                max.getX() + 1.008, max.getY() + 1.008, max.getZ() + 1.008);
    }

    /** 方块坐标是否在 [min, max] 闭区间内。 */
    private static boolean inside(BlockPos p, BlockPos min, BlockPos max) {
        return p.getX() >= min.getX() && p.getX() <= max.getX()
                && p.getY() >= min.getY() && p.getY() <= max.getY()
                && p.getZ() >= min.getZ() && p.getZ() <= max.getZ();
    }

    /** 悬停高亮边宽（比常驻边粗一倍）。 */
    private static final float HOVER_EDGE_WIDTH = ZoneOutliner.EDGE_WIDTH * 2;

    /**
     * 区域悬停高亮：准星方块落在某个已保存范围里时，把**最小**的包含范围
     * （子区域 &gt; 城镇区块 &gt; 城镇外框）描亮——细边变粗边 + 全不透明，
     * "你正看的就是它"，不用打开手册确认。
     */
    private static void renderHoverHighlight(RenderLevelStageEvent event, Minecraft mc,
                                             LocalPlayer player, BlockPos aimed) {
        if (aimed == null) {
            return;
        }
        String dim = player.level().dimension().location().toString();
        for (TownHandbookClient.TownView town : TownHandbookClient.townsInDimension(dim)) {
            if (!inside(aimed, town.min(), town.max())) {
                continue;
            }
            // 优先找更小的包含范围
            TownHandbookClient.ZoneView hitZone = null;
            for (TownHandbookClient.ZoneView zone : town.zones()) {
                if (inside(aimed, zone.min(), zone.max())) {
                    hitZone = zone;
                    break;
                }
            }
            TownHandbookClient.ZoneView hitBlock = null;
            if (hitZone == null) {
                for (TownHandbookClient.ZoneView block : town.blocks()) {
                    if (inside(aimed, block.min(), block.max())) {
                        hitBlock = block;
                        break;
                    }
                }
            }

            PoseStack pose = event.getPoseStack();
            Vec3 cam = event.getCamera().getPosition();
            pose.pushPose();
            pose.translate(-cam.x, -cam.y, -cam.z);
            VertexConsumer edges = mc.renderBuffers().bufferSource()
                    .getBuffer(OutlineRenderTypes.edge());
            if (hitZone != null) {
                float[] c = zoneLineColor(hitZone.kind());
                ZoneOutliner.renderEdges(pose.last(), edges, savedBox(hitZone.min(), hitZone.max()),
                        c[0], c[1], c[2], 1.0F, HOVER_EDGE_WIDTH);
            } else if (hitBlock != null) {
                ZoneOutliner.renderEdges(pose.last(), edges, savedBox(hitBlock.min(), hitBlock.max()),
                        1.0F, 1.0F, 1.0F, 1.0F, HOVER_EDGE_WIDTH);
            } else {
                ZoneOutliner.renderEdges(pose.last(), edges, savedBox(town.min(), town.max()),
                        1.0F, 1.0F, 1.0F, 1.0F, HOVER_EDGE_WIDTH);
            }
            mc.renderBuffers().bufferSource().endBatch(OutlineRenderTypes.edge());
            pose.popPose();
            return; // 只高亮一个
        }
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
        boxFaces(buf, mat, x0, y0, z0, x1, y1, z1, cam.x, cam.y, cam.z, r, g, b, a);
        mc.renderBuffers().bufferSource().endBatch(RenderType.debugFilledBox());

        pose.popPose();
    }

    /**
     * 画盒子的"朝向相机的那一面"（最多 3 个面），其余面直接跳过。
     *
     * <p>为什么不做 6 面全画 + 面剔除开关：debugFilledBox 这个渲染类型本身
     * 不写深度、半透明，6 面全画时前后表面在屏幕上互相叠加，
     * 再叠上每个面 2 个三角形的对角接缝，盒子内部就会出现大量
     * 斜线重影——区域一多根本没法看。按"外法线是否朝向相机"逐面判断后，
     * 从外面看只剩干净的 1~3 个外表面，从里面看也只剩包着自己的那层壳。
     *
     * @param camX/camY/camZ 相机世界坐标（判断面朝向用）
     */
    private static void boxFaces(VertexConsumer buf, Matrix4f mat,
                                 double x0, double y0, double z0,
                                 double x1, double y1, double z1,
                                 double camX, double camY, double camZ,
                                 int r, int g, int b, int a) {
        // 每个面 4 角（周角顺序）+ 该面的可见条件（外法线朝向相机）
        // 例：min-x 面 (x = x0) 的外法线是 (-1,0,0)，只有相机在它左边时可见
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
        boolean[] visible = {
                camZ < z0, camZ > z1,   // z 两面
                camX < x0, camX > x1,   // x 两面
                camY < y0, camY > y1,   // y 两面
        };
        for (int fIdx = 0; fIdx < faces.length; fIdx++) {
            if (!visible[fIdx]) {
                continue;
            }
            double[][] f = faces[fIdx];
            for (int i : new int[]{0, 1, 2, 0, 2, 3}) {
                double[] c = f[i];
                buf.addVertex(mat, (float) c[0], (float) c[1], (float) c[2])
                        .setColor(r, g, b, a);
            }
        }
    }
}
