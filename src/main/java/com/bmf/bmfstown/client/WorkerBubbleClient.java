package com.bmf.bmfstown.client;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.entity.Resident;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * 工人"工作中"头顶气泡（v0.8.1）：白底气泡里一个小锤子，玩家一眼可见。
 *
 * <h2>显示条件</h2>
 * {@code Resident.isWorkerVisual()}（实体同步数据）——工人在工坊里
 * 取料/合成/入库时为 true（走路去工坊不显示，用户定稿：干活才冒泡）。
 *
 * <h2>实现：世界层广告牌</h2>
 * 与站岗选取预览同一挂点（{@link RenderLevelStageEvent}），遍历客户端
 * 可见实体里的工作中的工人，在头顶画一块 18×20 像素的贴图
 * （{@code textures/gui/worker_bubble.png}），朝向相机（billboard）。
 * 不用实体渲染器挂件的原因：零侵入——不动 ResidentRenderer 的管线。
 */
@EventBusSubscriber(modid = BmfTown.MOD_ID, value = Dist.CLIENT)
public final class WorkerBubbleClient {

    /** 气泡贴图（18×20，生成期一次成型）。 */
    private static final ResourceLocation BUBBLE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "textures/gui/worker_bubble.png");

    /** 贴图像素 → 世界格子的缩放。 */
    private static final float PIXEL = 0.025F;

    /** 头顶悬浮高度（格）：贴着头顶留一条缝。 */
    private static final float LIFT = 0.15F;

    private WorkerBubbleClient() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Resident resident) || !resident.isWorkerVisual()) {
                continue;
            }
            // 距离剔除：太远的气泡没有信息量（64 格与名字牌同级）
            if (entity.distanceToSqr(mc.player) > 64.0D * 64.0D) {
                continue;
            }

            pose.pushPose();
            Vec3 pos = entity.position();
            pose.translate(pos.x - cam.x, pos.y - cam.y + entity.getBbHeight() + LIFT,
                    pos.z - cam.z);
            // 广告牌：整体转向相机；再转 180° 让贴图正面朝镜头（原版名牌同款手法）
            pose.mulPose(event.getCamera().rotation());
            pose.mulPose(new org.joml.Quaternionf().rotateY((float) Math.PI));
            pose.scale(-PIXEL, -PIXEL, PIXEL);
            Matrix4f mat = pose.last().pose();

            VertexConsumer vc = buffers.getBuffer(RenderType.text(BUBBLE_TEXTURE));
            int light = net.minecraft.client.renderer.LevelRenderer.getLightColor(
                    mc.level, resident.blockPosition());
            // 两三角拼矩形，UV 铺满整张贴图；text 类型与名牌同款（无染色、双面、透明正常）
            vc.addVertex(mat, -9F, 0F, 0F).setColor(255, 255, 255, 255)
                    .setUv(0F, 1F).setLight(light).setNormal(0F, 0F, 1F);
            vc.addVertex(mat, 9F, 0F, 0F).setColor(255, 255, 255, 255)
                    .setUv(1F, 1F).setLight(light).setNormal(0F, 0F, 1F);
            vc.addVertex(mat, 9F, -20F, 0F).setColor(255, 255, 255, 255)
                    .setUv(1F, 0F).setLight(light).setNormal(0F, 0F, 1F);
            vc.addVertex(mat, -9F, -20F, 0F).setColor(255, 255, 255, 255)
                    .setUv(0F, 0F).setLight(light).setNormal(0F, 0F, 1F);
            buffers.endBatch(RenderType.entityCutoutNoCull(BUBBLE_TEXTURE));
            pose.popPose();
        }
    }
}
