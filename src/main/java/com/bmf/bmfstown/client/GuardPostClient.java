package com.bmf.bmfstown.client;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.network.ModNetworking;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

/**
 * 站岗位置选取的客户端状态机。
 *
 * <h2>流程（与魔杖选点同一套"客户端权威"思路）</h2>
 * <ol>
 *   <li>玩家在镇民面板工作页点「站岗」→ Screen 关闭界面并调 {@link #startPick}</li>
 *   <li>预览态：准心方块蒙半透明红色（渲染挂 {@link RenderLevelStageEvent}）</li>
 *   <li><b>右键</b>：取消原版交互，把准心方块打包发给服务端落账
 *       （{@link ModNetworking.WorkGuardPostPayload}），退出预览态</li>
 *   <li><b>Shift</b>：按一下就取消，退出预览态（不发包）</li>
 * </ol>
 *
 * <h2>为什么状态在客户端</h2>
 * "玩家此刻的准心对着哪"只有客户端知道（1.20.5+ 服务端还会静默拒收
 * 超距方块），所以确认动作必须客户端判定、服务端只做职业复核与落账。
 */
@EventBusSubscriber(modid = BmfTown.MOD_ID, value = Dist.CLIENT)
public final class GuardPostClient {

    private GuardPostClient() {
    }

    /** 当前是否处于选取状态。 */
    private static boolean picking = false;
    /** 要布置站岗的镇民实体 id（确认包回传给服务端用）。 */
    private static int residentId = -1;

    /** 进入选取状态（镇民面板工作页的"站岗"按钮调用）。 */
    public static void startPick(int entityId) {
        picking = true;
        residentId = entityId;
        Minecraft.getInstance().player.displayClientMessage(Component.translatable(
                "msg.bmfstown.work.guard_pick"), true);
    }

    // ==========================================================
    //  输入：右键确认 / Shift 取消
    // ==========================================================

    @SubscribeEvent
    public static void onUseKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (!picking || !event.isUseItem()) {
            return;
        }
        // 预览态的右键全权归本 mod：原版交互（放方块/用物品）一律取消
        event.setCanceled(true);
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            picking = false;
            return;
        }
        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = blockHit.getBlockPos();
            ModNetworking.sendToServer(new ModNetworking.WorkGuardPostPayload(residentId, pos));
            picking = false;
        }
        // 没指着方块：保持预览态，等玩家指到方块再确认
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (!picking) {
            return;
        }
        boolean shift = event.getKey() == GLFW.GLFW_KEY_LEFT_SHIFT
                || event.getKey() == GLFW.GLFW_KEY_RIGHT_SHIFT;
        if (shift && event.getAction() == GLFW.GLFW_PRESS) {
            picking = false;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.translatable(
                        "msg.bmfstown.work.cancel_pick"), true);
            }
        }
    }

    // ==========================================================
    //  渲染：准心方块红色半透明预览
    // ==========================================================

    /** 红色填充/线框的透明度（0-255）。 */
    private static final int FILL_ALPHA = 80;
    private static final int LINE_ALPHA = 230;

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!picking
                || event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }
        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        BlockPos pos = blockHit.getBlockPos();

        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f mat = pose.last().pose();

        double o = 0.008; // 外扩防 Z-fighting
        double x0 = pos.getX() - o, y0 = pos.getY() - o, z0 = pos.getZ() - o;
        double x1 = pos.getX() + 1 + o, y1 = pos.getY() + 1 + o, z1 = pos.getZ() + 1 + o;

        // 半透明红色填充（只画朝向相机的面，避免重影）
        VertexConsumer fill = mc.renderBuffers().bufferSource()
                .getBuffer(RenderType.debugFilledBox());
        double[][][] faces = {
                {{x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0}},
                {{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}},
                {{x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}},
                {{x1, y0, z0}, {x1, y0, z1}, {x1, y1, z1}, {x1, y1, z0}},
                {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}},
                {{x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}},
        };
        boolean[] visible = {
                cam.z < z0, cam.z > z1, cam.x < x0, cam.x > x1, cam.y < y0, cam.y > y1
        };
        for (int f = 0; f < faces.length; f++) {
            if (!visible[f]) {
                continue;
            }
            double[][] quad = faces[f];
            for (int i : new int[]{0, 1, 2, 0, 2, 3}) {
                double[] c = quad[i];
                fill.addVertex(mat, (float) c[0], (float) c[1], (float) c[2])
                        .setColor(255, 40, 40, FILL_ALPHA);
            }
        }
        mc.renderBuffers().bufferSource().endBatch(RenderType.debugFilledBox());

        // 红色线框
        VertexConsumer line = mc.renderBuffers().bufferSource().getBuffer(RenderType.lines());
        LevelRenderer.renderLineBox(pose, line,
                x0, y0, z0, x1, y1, z1,
                1.0F, 0.15F, 0.15F, LINE_ALPHA / 255.0F);
        mc.renderBuffers().bufferSource().endBatch(RenderType.lines());

        pose.popPose();
    }
}
