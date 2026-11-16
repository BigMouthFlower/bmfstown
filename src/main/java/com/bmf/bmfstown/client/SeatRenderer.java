package com.bmf.bmfstown.client;

import com.bmf.bmfstown.entity.SeatEntity;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

/**
 * 隐形座位渲染器：座位实体本体不可见（{@code setInvisible}），渲染层零绘制，
 * 只需要占位注册防客户端在座位进入视野时找不到渲染器而崩。
 */
public class SeatRenderer extends EntityRenderer<SeatEntity> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/misc/white.png");

    public SeatRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(SeatEntity entity) {
        return TEXTURE; // 永不真正使用：无模型无绘制
    }
}
