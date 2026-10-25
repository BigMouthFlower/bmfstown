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
 * 工人工坊绑定的客户端状态机（v0.8.1）：三步依次指定 合成台→原料箱→产物箱。
 *
 * <h2>流程（与站岗选取同一套"客户端权威"思路）</h2>
 * <ol>
 *   <li>工人面板工作页点「设置工坊」→ 关界面并调 {@link #startPick}</li>
 *   <li>预览态：准心方块按步骤染色（合成台蓝/原料箱橙/产物箱绿）</li>
 *   <li><b>右键</b>：确认当前步，进下一步；三步齐了打包发给服务端落账
 *       （{@link ModNetworking.WorkshopBindPayload}），退出预览态</li>
 *   <li><b>Shift</b>：取消，不发包</li>
 * </ol>
 *
 * <p>为什么状态在客户端：与站岗选取同一套理由（准心只有客户端知道）。
 */
@EventBusSubscriber(modid = BmfTown.MOD_ID, value = Dist.CLIENT)
public final class WorkshopBenchClient {

    private WorkshopBenchClient() {
    }

    /** 绑定步骤：0 合成台 → 1 原料箱 → 2 产物箱。 */
    private static final int STEPS = 3;

    /** 每步的预览颜色（RGB）：合成台蓝 / 原料箱橙 / 产物箱绿。 */
    private static final float[][] STEP_COLORS = {
            {0.35F, 0.55F, 1.0F}, {1.0F, 0.65F, 0.2F}, {0.3F, 1.0F, 0.4F}
    };

    /** 当前是否处于绑定状态。 */
    private static boolean picking = false;
    /** 当前进行到第几步。 */
    private static int step = 0;
    /** 要绑定的镇民实体 id（确认包回传给服务端用）。 */
    private static int residentId = -1;
    /** 已确认的方块（三步收集齐才发包）。 */
    private static final BlockPos[] picked = new BlockPos[STEPS];

    /** 进入绑定状态（工人面板工作页的"设置工坊"按钮调用）。 */
    public static void startPick(int entityId) {
        picking = true;
        step = 0;
        residentId = entityId;
        Minecraft.getInstance().player.displayClientMessage(Component.translatable(
                "msg.bmfstown.workshop.pick_start"), true);
    }

    // ==========================================================
    //  输入：右键逐步确认 / Shift 取消
    // ==========================================================

    @SubscribeEvent
    public static void onUseKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (!picking || !event.isUseItem()) {
            return;
        }
        event.setCanceled(true); // 预览态的右键全权归本 mod
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            picking = false;
            return;
        }
        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            picked[step] = blockHit.getBlockPos().immutable();
            step++;
            if (step >= STEPS) {
                picking = false;
                ModNetworking.sendToServer(new ModNetworking.WorkshopBindPayload(
                        residentId, picked[0], picked[1], picked[2]));
            } else {
                player.displayClientMessage(Component.translatable(
                        "msg.bmfstown.workshop.pick_next", step + 1), true);
            }
        }
        // 没指着方块：保持预览态
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
    //  渲染：准心方块按步骤染色预览
    // ==========================================================

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
        float[] color = STEP_COLORS[step];

        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f mat = pose.last().pose();

        double o = 0.008; // 外扩防 Z-fighting
        double x0 = pos.getX() - o, y0 = pos.getY() - o, z0 = pos.getZ() - o;
        double x1 = pos.getX() + 1 + o, y1 = pos.getY() + 1 + o, z1 = pos.getZ() + 1 + o;

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
                        .setColor((int) (color[0] * 255), (int) (color[1] * 255),
                                (int) (color[2] * 255), FILL_ALPHA);
            }
        }
        mc.renderBuffers().bufferSource().endBatch(RenderType.debugFilledBox());

        VertexConsumer line = mc.renderBuffers().bufferSource().getBuffer(RenderType.lines());
        LevelRenderer.renderLineBox(pose, line,
                x0, y0, z0, x1, y1, z1,
                color[0], color[1], color[2], LINE_ALPHA / 255.0F);
        mc.renderBuffers().bufferSource().endBatch(RenderType.lines());

        pose.popPose();
    }
}
