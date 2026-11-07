package com.bmf.bmfstown.client;

import com.bmf.bmfstown.BmfTown;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 工坊登记会话的客户端渲染（v0.12 工人重做，**城镇级**登记）。
 *
 * <h2>显示时机：手持手册 + 登记会话激活</h2>
 * 登记入口在手册城镇页（「登记方块」按钮），会话期间玩家拿着手册
 * 在城镇里逐个点方块——这正是最需要"哪些已登记"反馈的时刻。
 * 平时不画：登记完就隐去，几十个箱子的小框常驻是视觉噪音。
 *
 * <h2>颜色语言（避开领地四色：白=城镇/区块、橙=就餐、青=住宅、绿=图书馆）</h2>
 * <ul>
 *   <li><b>暖黄</b> = 已登记容器（仓储感）</li>
 *   <li><b>紫</b> = 已登记工作站（与黄明度差大，色盲友好）</li>
 *   <li><b>白加粗</b> = 准星指着的候选方块——"右键就登记它/再点取消它"</li>
 *   <li><b>暗红</b> = 准星在城镇范围外（这里登记无效）</li>
 * </ul>
 */
@EventBusSubscriber(modid = BmfTown.MOD_ID, value = Dist.CLIENT)
public final class WorkshopRegisterClient {

    /** 已登记容器：暖黄。 */
    private static final float[] COLOR_CONTAINER = {1.0F, 0.85F, 0.3F};
    /** 已登记工作站：紫。 */
    private static final float[] COLOR_STATION = {0.75F, 0.5F, 1.0F};

    /** 水源站（炼金补水）：水蓝，与岗位紫/容器黄区分（决策 #34）。 */
    private static final float[] COLOR_WATER = {0.3F, 0.55F, 1.0F};
    /** 准星候选：白。 */
    private static final float[] COLOR_CANDIDATE = {1.0F, 1.0F, 1.0F};
    /** 准星在城镇范围外：暗红。 */
    private static final float[] COLOR_OUTSIDE = {1.0F, 0.3F, 0.25F};

    private WorkshopRegisterClient() {
    }

    /** 服务端 RegisterSessionSync 推来的会话状态。 */
    public static void setSession(boolean active, String townId) {
        TownHandbookClient.setRegisterSession(active, townId);
    }

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
        String townIdText = TownHandbookClient.registerSessionZone();
        if (townIdText == null) {
            return;
        }
        UUID townId;
        try {
            townId = UUID.fromString(townIdText);
        } catch (IllegalArgumentException e) {
            return;
        }

        // 找到目标城镇（含登记表）
        TownHandbookClient.TownView town = null;
        for (TownHandbookClient.TownView t : TownHandbookClient
                .townsInDimension(player.level().dimension().location().toString())) {
            if (t.id().equals(townId)) {
                town = t;
                break;
            }
        }
        if (town == null) {
            return;
        }

        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);

        var buffers = mc.renderBuffers().bufferSource();
        var edges = buffers.getBuffer(OutlineRenderTypes.edge());

        // 已登记：容器黄 / 工作站紫（一格一个小框）
        for (Long p : town.workshopContainers()) {
            ZoneOutliner.renderEdges(pose.last(), edges, blockBox(BlockPos.of(p)),
                    COLOR_CONTAINER[0], COLOR_CONTAINER[1], COLOR_CONTAINER[2],
                    0.9F, ZoneOutliner.EDGE_WIDTH);
        }
        for (Long p : town.workshopStations()) {
            ZoneOutliner.renderEdges(pose.last(), edges, blockBox(BlockPos.of(p)),
                    COLOR_STATION[0], COLOR_STATION[1], COLOR_STATION[2],
                    0.9F, ZoneOutliner.EDGE_WIDTH);
        }
        // 酿造台岗位（炼金）——同为岗位紫（用户 11-06：不能跟容器一个颜色）
        for (Long p : town.workshopBrewStations()) {
            ZoneOutliner.renderEdges(pose.last(), edges, blockBox(BlockPos.of(p)),
                    COLOR_STATION[0], COLOR_STATION[1], COLOR_STATION[2],
                    0.9F, ZoneOutliner.EDGE_WIDTH);
        }
        // 水源站（炼金补水，决策 #34）——水蓝
        for (Long p : town.workshopWaterSources()) {
            ZoneOutliner.renderEdges(pose.last(), edges, blockBox(BlockPos.of(p)),
                    COLOR_WATER[0], COLOR_WATER[1], COLOR_WATER[2],
                    0.9F, ZoneOutliner.EDGE_WIDTH);
        }
        buffers.endBatch(OutlineRenderTypes.edge());

        // 准星候选：容器/工作台类方块画框（镇内白=可登记，镇外暗红=这里无效）
        BlockPos hovered = hoveredBlock(mc);
        if (hovered != null) {
            float[] kind = candidateKind(mc, hovered);
            if (kind != null) {
                boolean inside = town.min().getX() <= hovered.getX()
                        && hovered.getX() <= town.max().getX()
                        && town.min().getY() <= hovered.getY()
                        && hovered.getY() <= town.max().getY()
                        && town.min().getZ() <= hovered.getZ()
                        && hovered.getZ() <= town.max().getZ();
                // 追加区块也要算"镇内"
                if (!inside) {
                    for (TownHandbookClient.ZoneView block : town.blocks()) {
                        if (inside(block, hovered)) {
                            inside = true;
                            break;
                        }
                    }
                }
                float[] color = inside ? COLOR_CANDIDATE : COLOR_OUTSIDE;
                var hover = buffers.getBuffer(OutlineRenderTypes.edge());
                ZoneOutliner.renderEdges(pose.last(), hover, blockBox(hovered),
                        color[0], color[1], color[2],
                        1.0F, ZoneOutliner.EDGE_WIDTH * 2);
                buffers.endBatch(OutlineRenderTypes.edge());
            }
        }

        pose.popPose();
    }

    /** 方块坐标是否在区块（含端点）内。 */
    private static boolean inside(TownHandbookClient.ZoneView block, BlockPos pos) {
        return pos.getX() >= block.min().getX() && pos.getX() <= block.max().getX()
                && pos.getY() >= block.min().getY() && pos.getY() <= block.max().getY()
                && pos.getZ() >= block.min().getZ() && pos.getZ() <= block.max().getZ();
    }

    /** 单方块小盒（轻微外扩防 z-fighting）。 */
    private static net.minecraft.world.phys.AABB blockBox(BlockPos pos) {
        return new net.minecraft.world.phys.AABB(
                pos.getX() - 0.02, pos.getY() - 0.02, pos.getZ() - 0.02,
                pos.getX() + 1.02, pos.getY() + 1.02, pos.getZ() + 1.02);
    }

    @Nullable
    private static BlockPos hoveredBlock(Minecraft mc) {
        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            return blockHit.getBlockPos();
        }
        return null;
    }

    /**
     * 准星方块可否登记：容器（任意 Container 方块实体）、工作台/酿造台、
     * 水源站三形态（含水炼药锅/水源/含水方块）。返回对应的标识色；
     * 不可登记返回 null（不画候选框）。
     */
    @Nullable
    private static float[] candidateKind(Minecraft mc, BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        // 岗位判定在前：酿造台本身是 Container，先判容器会把它染成容器黄
        if (state.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)
                || state.is(net.minecraft.world.level.block.Blocks.BREWING_STAND)) {
            return COLOR_STATION;
        }
        // 容器判定在水源之前：箱子也能被含水（waterlogged chest），
        // 含水的箱子应该按容器登记而不是水源站
        if (mc.level.getBlockEntity(pos) instanceof net.minecraft.world.Container) {
            return COLOR_CONTAINER;
        }
        if (state.is(net.minecraft.world.level.block.Blocks.WATER_CAULDRON)
                || (state.is(net.minecraft.world.level.block.Blocks.WATER)
                && state.getFluidState().isSource())
                || (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
                && state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED))) {
            return COLOR_WATER;
        }
        return null;
    }
}
