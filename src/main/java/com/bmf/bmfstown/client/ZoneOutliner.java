package com.bmf.bmfstown.client;

import com.bmf.bmfstown.BmfTown;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 世界内选框渲染器：槽位管理的追踪盒（chase 插值）+ 细长方体棱边 + 纹理半透明面。
 *
 * <h2>三件套分工（对应一个"会动的选框"要解决的三件事）</h2>
 * <ol>
 *   <li><b>棱边 = 细长方体</b>：12 条边各画成一个横截面 1/32 格的小立方体，
 *       而不是 1px 线——世界缩放/远看时线宽恒定，体感"这是个实体框架"。
 *       颜色由顶点色控制（纹理纯白），全亮度光照（不随环境光变暗）。</li>
 *   <li><b>面 = 半透明纹理</b>：棋盘格纹理，UV 直接用世界坐标尺寸 →
 *       纹理按方块数平铺，盒子拉多大棋盘格都不变形。渲染类型不写深度，
 *       多面互叠不出脏边。</li>
 *   <li><b>chase 插值</b>：盒子存三份 AABB（prev/当前/目标），每 tick 向目标
 *       逼近一半，渲染时再按 partialTick 补间——魔杖第二点跟随准心时盒子
 *       平滑"追"过去，不逐格跳变。</li>
 * </ol>
 *
 * <h2>生命周期（槽位 + TTL + 淡出）</h2>
 * 调用方每帧（或每 tick）调 {@link #chase} 保活；不再调用后 TTL 到期进入
 * 8 tick 淡出（alpha 立方衰减）自动移除——切走魔杖时选框优雅消失，不闪断。
 *
 * <h2>相机在盒内</h2>
 * 盒子整体外扩 1/128 防与方块面 z-fighting；相机进入盒内时改为内缩 1/128
 * 并用 no-cull 面渲染类型（贴脸不穿帮，从里看面不消失）。
 */
@EventBusSubscriber(modid = BmfTown.MOD_ID, value = Dist.CLIENT)
public final class ZoneOutliner {

    /** 棱边横截面半宽（Create 同款 1/32 格：近看纤细、远看仍可见）。 */
    public static final float EDGE_WIDTH = 1 / 32.0F;
    /** 淡出时长（tick）。 */
    private static final int FADE_TICKS = 8;

    /** 选框样式：颜色 + 线宽 + 面纹理/透明度（faces 传 null = 不画面）。 */
    public record Style(float r, float g, float b, float lineWidth,
                        @Nullable ResourceLocation faceTexture, float faceAlpha) {

        /** 只有棱边的样式（常驻边框用）。 */
        public static Style edges(float r, float g, float b, float lineWidth) {
            return new Style(r, g, b, lineWidth, null, 0);
        }

        /** 棱边 + 棋盘格面（划区预览用）。 */
        public static Style boxed(float r, float g, float b, float lineWidth, float faceAlpha) {
            return new Style(r, g, b, lineWidth, CHECKER, faceAlpha);
        }
    }

    /** 棋盘格面纹理（16×16，2px 一格）。 */
    public static final ResourceLocation CHECKER = ResourceLocation.fromNamespaceAndPath(
            BmfTown.MOD_ID, "textures/misc/zone_checker.png");

    /** 全亮度光照（棱线/面不受环境光变暗）。 */
    private static final int FULL_BRIGHT = LightTexture.FULL_BRIGHT;

    // ==========================================================
    //  槽位管理
    // ==========================================================

    private static final class Entry {
        AABB prev;
        AABB current;
        AABB target;
        Style style;
        int ttl = 1;

        Entry(AABB box, Style style) {
            this.prev = box;
            this.current = box;
            this.target = box;
            this.style = style;
        }

        boolean alive() {
            return ttl >= -FADE_TICKS;
        }

        boolean fading() {
            return ttl < 0;
        }
    }

    private static final Map<Object, Entry> ENTRIES = new HashMap<>();

    /**
     * 让槽位的目标盒平滑追到 target，并保活。每帧调用都安全：
     * 目标变了只是"追的方向变了"，动画连续。
     */
    public static void chase(Object slot, AABB target, Style style) {
        Entry e = ENTRIES.get(slot);
        if (e == null || !e.alive()) {
            ENTRIES.put(slot, new Entry(target, style));
            return;
        }
        e.target = target;
        e.style = style;
        e.ttl = 1;
    }

    /** 立即移除槽位（不淡出）。 */
    public static void remove(Object slot) {
        ENTRIES.remove(slot);
    }

    // ==========================================================
    //  tick：插值 + 生命周期
    // ==========================================================

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (Minecraft.getInstance().level == null) {
            if (!ENTRIES.isEmpty()) {
                ENTRIES.clear(); // 换世界/退到主菜单：全部作废
            }
            return;
        }
        tick();
    }

    private static void tick() {
        Iterator<Entry> it = ENTRIES.values().iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            e.ttl--;
            if (!e.alive()) {
                it.remove();
                continue;
            }
            // prev ← current ← 向 target 追一半（指数逼近）
            e.prev = e.current;
            e.current = lerp(e.current, e.target, 0.5F);
        }
    }

    private static AABB lerp(AABB from, AABB to, float t) {
        return new AABB(
                Mth.lerp(t, from.minX, to.minX),
                Mth.lerp(t, from.minY, to.minY),
                Mth.lerp(t, from.minZ, to.minZ),
                Mth.lerp(t, from.maxX, to.maxX),
                Mth.lerp(t, from.maxY, to.maxY),
                Mth.lerp(t, from.maxZ, to.maxZ));
    }

    // ==========================================================
    //  渲染入口（RenderLevelStageEvent 里调用）
    // ==========================================================

    /**
     * 画所有活跃槽位。poseStack 已是"相机相对"坐标系
     * （调用方先 translate(-cam)）；fade 因子按 partialTick 补间。
     */
    public static void renderAll(PoseStack poseStack, Vec3 cam, float partialTick) {
        if (ENTRIES.isEmpty()) {
            return;
        }
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance()
                .renderBuffers().bufferSource();

        // 面先画（半透明垫底），棱边后画叠在上面——与"先填色后描边"同一手感
        for (Map.Entry<Object, Entry> kv : ENTRIES.entrySet()) {
            Entry e = kv.getValue();
            float alpha = fadeFactor(e, partialTick);
            if (alpha < 1 / 8.0F) {
                continue;
            }
            AABB box = lerp(e.prev, e.current, partialTick);
            Style s = e.style;
            boolean cameraInside = box.contains(cam);
            // 相机在盒内：内缩 1/128 贴脸不穿帮；否则外扩防 z-fighting
            float inflate = cameraInside ? -1 / 128.0F : 1 / 128.0F;
            AABB adj = box.inflate(inflate);

            if (s.faceTexture() != null) {
                VertexConsumer faces = buffers.getBuffer(
                        OutlineRenderTypes.faces(s.faceTexture(), !cameraInside));
                renderFaces(poseStack.last(), faces, adj,
                        s.r(), s.g(), s.b(), s.faceAlpha() * alpha);
            }
            if (s.lineWidth() > 0) {
                VertexConsumer edges = buffers.getBuffer(OutlineRenderTypes.edge());
                renderEdges(poseStack.last(), edges, adj,
                        s.r(), s.g(), s.b(), alpha, s.lineWidth());
            }
        }
        // 自定义渲染类型不在主循环批处理计划里，必须自己 endBatch 落盘
        buffers.endBatch(OutlineRenderTypes.edge());
        buffers.endBatch(OutlineRenderTypes.faces(CHECKER, true));
        buffers.endBatch(OutlineRenderTypes.faces(CHECKER, false));
    }

    /** 淡出因子：TTL>1 全亮；0 → -8 线性衰减后立方压暗（与成熟选框同款手感）。 */
    private static float fadeFactor(Entry e, float partialTick) {
        if (!e.fading()) {
            return 1;
        }
        float prevT = e.ttl + 1;
        float last = prevT >= 0 ? 1 : 1 + (prevT / (float) FADE_TICKS);
        float cur = 1 + (e.ttl / (float) FADE_TICKS);
        float a = Mth.lerp(partialTick, last, cur);
        return a * a * a;
    }

    // ==========================================================
    //  绘制原语（静态公开：常驻边框等"立即画"场景直接用）
    // ==========================================================

    /**
     * 12 条棱边 = 12 个细长方体。buffer 由调用方取好，可多次调用攒一批。
     */
    public static void renderEdges(PoseStack.Pose pose, VertexConsumer buf, AABB box,
                                   float r, float g, float b, float alpha, float lineWidth) {
        Vector4f color = new Vector4f(r, g, b, alpha);
        float h = lineWidth / 2;
        double minX = box.minX, minY = box.minY, minZ = box.minZ;
        double maxX = box.maxX, maxY = box.maxY, maxZ = box.maxZ;

        // X 向 4 条（y/z 取四角组合）
        for (double y : new double[]{minY, maxY}) {
            for (double z : new double[]{minZ, maxZ}) {
                cuboid(buf, pose, color,
                        (float) minX, (float) (y - h), (float) (z - h),
                        (float) maxX, (float) (y + h), (float) (z + h));
            }
        }
        // Y 向 4 条
        for (double x : new double[]{minX, maxX}) {
            for (double z : new double[]{minZ, maxZ}) {
                cuboid(buf, pose, color,
                        (float) (x - h), (float) minY, (float) (z - h),
                        (float) (x + h), (float) maxY, (float) (z + h));
            }
        }
        // Z 向 4 条
        for (double x : new double[]{minX, maxX}) {
            for (double y : new double[]{minY, maxY}) {
                cuboid(buf, pose, color,
                        (float) (x - h), (float) (y - h), (float) minZ,
                        (float) (x + h), (float) (y + h), (float) maxZ);
            }
        }
    }

    /**
     * 6 个面，UV = 世界尺寸（纹理按方块平铺不变形）。buffer 调用方取好。
     */
    public static void renderFaces(PoseStack.Pose pose, VertexConsumer buf, AABB box,
                                   float r, float g, float b, float alpha) {
        Vector4f color = new Vector4f(r, g, b, alpha);
        float minX = (float) box.minX, minY = (float) box.minY, minZ = (float) box.minZ;
        float maxX = (float) box.maxX, maxY = (float) box.maxY, maxZ = (float) box.maxZ;
        Vector3f n = new Vector3f();
        Vector3f p0 = new Vector3f(), p1 = new Vector3f(), p2 = new Vector3f(), p3 = new Vector3f();

        // DOWN
        n.set(0, -1, 0);
        quad(buf, pose, color, n,
                p0.set(minX, minY, maxZ), p1.set(minX, minY, minZ),
                p2.set(maxX, minY, minZ), p3.set(maxX, minY, maxZ),
                0, 0, maxX - minX, maxZ - minZ);
        // UP
        n.set(0, 1, 0);
        quad(buf, pose, color, n,
                p0.set(minX, maxY, minZ), p1.set(minX, maxY, maxZ),
                p2.set(maxX, maxY, maxZ), p3.set(maxX, maxY, minZ),
                0, 0, maxX - minX, maxZ - minZ);
        // NORTH
        n.set(0, 0, -1);
        quad(buf, pose, color, n,
                p0.set(maxX, maxY, minZ), p1.set(maxX, minY, minZ),
                p2.set(minX, minY, minZ), p3.set(minX, maxY, minZ),
                0, 0, maxX - minX, maxY - minY);
        // SOUTH
        n.set(0, 0, 1);
        quad(buf, pose, color, n,
                p0.set(minX, maxY, maxZ), p1.set(minX, minY, maxZ),
                p2.set(maxX, minY, maxZ), p3.set(maxX, maxY, maxZ),
                0, 0, maxX - minX, maxY - minY);
        // WEST
        n.set(-1, 0, 0);
        quad(buf, pose, color, n,
                p0.set(minX, maxY, minZ), p1.set(minX, minY, minZ),
                p2.set(minX, minY, maxZ), p3.set(minX, maxY, maxZ),
                0, 0, maxZ - minZ, maxY - minY);
        // EAST
        n.set(1, 0, 0);
        quad(buf, pose, color, n,
                p0.set(maxX, maxY, maxZ), p1.set(maxX, minY, maxZ),
                p2.set(maxX, minY, minZ), p3.set(maxX, maxY, minZ),
                0, 0, maxZ - minZ, maxY - minY);
    }

    /** 一个实心小立方体（棱边用）：6 面 ×4 顶点，UV 恒 0~1（纯白纹理无所谓）。 */
    private static void cuboid(VertexConsumer buf, PoseStack.Pose pose, Vector4f color,
                               float x0, float y0, float z0, float x1, float y1, float z1) {
        Vector3f n = new Vector3f();
        Vector3f p0 = new Vector3f(), p1 = new Vector3f(), p2 = new Vector3f(), p3 = new Vector3f();
        Matrix4f mat = pose.pose();

        // DOWN
        n.set(0, -1, 0);
        quadRaw(buf, mat, color, n,
                p0.set(x0, y0, z1), p1.set(x0, y0, z0), p2.set(x1, y0, z0), p3.set(x1, y0, z1));
        // UP
        n.set(0, 1, 0);
        quadRaw(buf, mat, color, n,
                p0.set(x0, y1, z0), p1.set(x0, y1, z1), p2.set(x1, y1, z1), p3.set(x1, y1, z0));
        // NORTH
        n.set(0, 0, -1);
        quadRaw(buf, mat, color, n,
                p0.set(x1, y1, z0), p1.set(x1, y0, z0), p2.set(x0, y0, z0), p3.set(x0, y1, z0));
        // SOUTH
        n.set(0, 0, 1);
        quadRaw(buf, mat, color, n,
                p0.set(x0, y1, z1), p1.set(x0, y0, z1), p2.set(x1, y0, z1), p3.set(x1, y1, z1));
        // WEST
        n.set(-1, 0, 0);
        quadRaw(buf, mat, color, n,
                p0.set(x0, y1, z0), p1.set(x0, y0, z0), p2.set(x0, y0, z1), p3.set(x0, y1, z1));
        // EAST
        n.set(1, 0, 0);
        quadRaw(buf, mat, color, n,
                p0.set(x1, y1, z1), p1.set(x1, y0, z1), p2.set(x1, y0, z0), p3.set(x1, y1, z0));
    }

    /** 带世界尺寸 UV 的四边形（面用）。 */
    private static void quad(VertexConsumer buf, PoseStack.Pose pose, Vector4f color, Vector3f n,
                             Vector3f p0, Vector3f p1, Vector3f p2, Vector3f p3,
                             float minU, float minV, float maxU, float maxV) {
        vertex(buf, pose.pose(), color, n, p0, minU, minV);
        vertex(buf, pose.pose(), color, n, p1, minU, maxV);
        vertex(buf, pose.pose(), color, n, p2, maxU, maxV);
        vertex(buf, pose.pose(), color, n, p3, maxU, minV);
    }

    /** 无纹理语义的四边形（边用，UV 恒 0~1）。 */
    private static void quadRaw(VertexConsumer buf, Matrix4f mat, Vector4f color, Vector3f n,
                                Vector3f p0, Vector3f p1, Vector3f p2, Vector3f p3) {
        vertexRaw(buf, mat, color, n, p0);
        vertexRaw(buf, mat, color, n, p1);
        vertexRaw(buf, mat, color, n, p2);
        vertexRaw(buf, mat, color, n, p3);
    }

    /**
     * 单顶点。NEW_ENTITY 格式六个元素必须全给——POSITION/COLOR/UV/OVERLAY/
     * LIGHT/NORMAL，缺任何一个直接崩客户端（pitfalls 有案底）。
     * pose 只有平移，法线不需要变换。
     */
    private static void vertex(VertexConsumer buf, Matrix4f mat, Vector4f color, Vector3f n,
                               Vector3f p, float u, float v) {
        buf.addVertex(mat, p.x(), p.y(), p.z())
                .setColor(color.x(), color.y(), color.z(), color.w())
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(FULL_BRIGHT)
                .setNormal(n.x(), n.y(), n.z());
    }

    private static void vertexRaw(VertexConsumer buf, Matrix4f mat, Vector4f color, Vector3f n,
                                  Vector3f p) {
        vertex(buf, mat, color, n, p, 0, 0);
    }
}
