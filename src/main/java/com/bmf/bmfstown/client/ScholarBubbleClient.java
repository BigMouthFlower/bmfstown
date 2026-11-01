package com.bmf.bmfstown.client;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.entity.Resident;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * 学者"写作中"头顶气泡（v0.11）：白底气泡里一本打开的书 + 一支笔
 * （用户定稿）。机制与 {@link WorkerBubbleClient} 同款——billboard、
 * 距离剔除、独立贴图——只换过滤条件与贴图。
 */
@EventBusSubscriber(modid = BmfTown.MOD_ID, value = Dist.CLIENT)
public final class ScholarBubbleClient {

    /** 气泡贴图（18×20，生成期一次成型）。 */
    private static final ResourceLocation BUBBLE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(BmfTown.MOD_ID, "textures/gui/scholar_bubble.png");

    /** 贴图像素 → 世界格子的缩放。 */
    private static final float PIXEL = 0.025F;

    /** 头顶悬浮高度（格）：贴着头顶留一条缝。 */
    private static final float LIFT = 0.15F;

    private ScholarBubbleClient() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.level instanceof ClientLevel level) || mc.player == null) {
            return;
        }
        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof Resident resident) || !resident.isScholarVisual()) {
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
            pose.mulPose(new Quaternionf().rotateY((float) Math.PI));
            pose.scale(-PIXEL, -PIXEL, PIXEL);
            Matrix4f mat = pose.last().pose();

            VertexConsumer vc = buffers.getBuffer(RenderType.text(BUBBLE_TEXTURE));
            int light = net.minecraft.client.renderer.LevelRenderer.getLightColor(
                    level, resident.blockPosition());
            // 两三角拼矩形，UV 铺满整张贴图（同工人气泡的验证写法）
            vc.addVertex(mat, -9F, 0F, 0F).setColor(255, 255, 255, 255)
                    .setUv(0F, 1F).setLight(light).setNormal(0F, 0F, 1F);
            vc.addVertex(mat, 9F, 0F, 0F).setColor(255, 255, 255, 255)
                    .setUv(1F, 1F).setLight(light).setNormal(0F, 0F, 1F);
            vc.addVertex(mat, 9F, -20F, 0F).setColor(255, 255, 255, 255)
                    .setUv(1F, 0F).setLight(light).setNormal(0F, 0F, 1F);
            vc.addVertex(mat, -9F, -20F, 0F).setColor(255, 255, 255, 255)
                    .setUv(0F, 0F).setLight(light).setNormal(0F, 0F, 1F);
            buffers.endBatch(RenderType.text(BUBBLE_TEXTURE));
            pose.popPose();
        }
    }
}
