package com.bmf.bmfstown.client;

import com.bmf.bmfstown.BmfTown;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.Util;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.function.BiFunction;

/**
 * 选区预览框的两个自定义渲染类型（分层）：
 *
 * <ul>
 *   <li><b>边</b> {@link #edge()}：实心（写深度）+ 全亮度光照——棱线永远清晰，
 *       不随环境光变暗；颜色走顶点色（纹理是纯白占位）。</li>
 *   <li><b>面</b> {@link #faces(ResourceLocation, boolean)}：半透明 + <b>不写深度</b>
 *       （只写颜色），面与面之间不互相切割出锯齿线；相机在框内时用 no-cull
 *       变体，从里往外看面不消失。</li>
 * </ul>
 *
 * <h2>为什么自己建 RenderType 而不用原版</h2>
 * 原版 {@code entityTranslucent} 写深度（半透明面互相遮挡出脏边），
 * {@code debugFilledBox} 不带纹理。选框需要"带纹理 + 半透明 + 不写深度 +
 * 可开关面剔除"这个组合，只能自定义。做法：继承 RenderType 以访问受保护的
 * CompositeState 零件并调用其受保护构造器，把 state 的 setup/clear 接进
 * 渲染类型的启停——这是世界内自定义渲染类型的通行做法。
 *
 * <h2>顶点格式 NEW_ENTITY（坑：缺一个元素崩客户端）</h2>
 * 两个类型都用 {@link DefaultVertexFormat#NEW_ENTITY}
 * （POSITION + COLOR + UV + OVERLAY + LIGHT + NORMAL）。
 * 填顶点时六个元素必须逐个补全，见 {@code ZoneOutliner} 的绘制原语。
 */
public final class OutlineRenderTypes extends RenderType {

    /** 纯白纹理：边的颜色全部由顶点色控制。 */
    public static final ResourceLocation WHITE = ResourceLocation.fromNamespaceAndPath(
            BmfTown.MOD_ID, "textures/misc/white.png");

    private static final RenderType EDGE = make("zone_edge",
            DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1024, false, false,
            CompositeState.builder()
                    .setShaderState(RENDERTYPE_ENTITY_SOLID_SHADER)
                    .setTextureState(new TextureStateShard(WHITE, false, false))
                    .setCullState(CULL)
                    .setLightmapState(LIGHTMAP)
                    .setOverlayState(OVERLAY)
                    .createCompositeState(false));

    /** 面渲染类型按纹理+是否剔除缓存（世界内同时最多两三种纹理）。 */
    private static final BiFunction<ResourceLocation, Boolean, RenderType> FACES =
            Util.memoize((texture, cull) -> make("zone_faces_" + (cull ? "cull" : "nocull"),
                    DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1024, false, true,
                    CompositeState.builder()
                            .setShaderState(cull
                                    ? RENDERTYPE_ENTITY_TRANSLUCENT_CULL_SHADER
                                    : RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                            .setTextureState(new TextureStateShard(texture, false, false))
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setCullState(cull ? CULL : NO_CULL)
                            .setLightmapState(LIGHTMAP)
                            .setOverlayState(OVERLAY)
                            // 只写颜色不写深度：多个半透明面互叠时不产生深度切割脏边
                            .setWriteMaskState(COLOR_WRITE)
                            .createCompositeState(false)));

    private OutlineRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode,
                               int bufferSize, boolean affectsCrumbling, boolean sortOnUpload,
                               Runnable setup, Runnable clear) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setup, clear);
    }

    /**
     * 类型工厂：直接用 RenderType.create（公开静态）组装——继承本类只是
     * 为了访问受保护的 shards（TextureStateShard 构造器与各状态常量）。
     */
    private static RenderType make(String name, VertexFormat format, VertexFormat.Mode mode,
                                   int bufferSize, boolean affectsCrumbling, boolean sortOnUpload,
                                   CompositeState state) {
        return create(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, state);
    }

    /** 棱边（实心、写深度、全亮）。 */
    public static RenderType edge() {
        return EDGE;
    }

    /** 半透明面（不写深度）；cull=false 用于相机在框内。 */
    public static RenderType faces(ResourceLocation texture, boolean cull) {
        return FACES.apply(texture, cull);
    }
}
