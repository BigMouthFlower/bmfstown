package com.bmf.bmfstown.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.Mob;

/**
 * 嘲讽红罩渲染层：被嘲讽的生物整个模型叠画一层半透明红色。
 *
 * <p><b>实现思路</b>：用生物<b>自己的贴图</b>再渲一遍模型，但换成
 * 半透明渲染模式并把顶点色染成红色——相当于把原贴图整体染红，
 * 叠在正常渲染之上，视觉上就是"罩了一层红"。
 *
 * <p><b>为什么不做成群系变色</b>：技术上可行（草方块那种顶点染色，
 * 渲染时采样 {@code BiomeColors}），但"被嘲讽"只需要一眼可辨，
 * 统一红色语义最清晰，故不做群系分支（用户确认）。
 */
public class TauntOverlayLayer<T extends Mob, M extends EntityModel<T>> extends RenderLayer<T, M> {

    /** 半透明红（ARGB）：alpha 0x99 约 60% 不透明（用户反馈 33% 太淡）。 */
    private static final int TAUNT_COLOR = 0x99FF2020;

    public TauntOverlayLayer(RenderLayerParent<T, M> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                       T entity, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        if (!ClientTauntState.isTaunted(entity)) {
            return;
        }
        com.bmf.bmfstown.BmfTown.LOGGER.debug(
                "[BmfTown][perk] 红罩渲染中：{}（{}）",
                entity.getType().toShortString(), entity.getUUID());
        VertexConsumer consumer = buffer.getBuffer(
                RenderType.entityTranslucent(getTextureLocation(entity)));
        this.getParentModel().renderToBuffer(poseStack, consumer,
                packedLight, OverlayTexture.NO_OVERLAY, TAUNT_COLOR);
    }
}
