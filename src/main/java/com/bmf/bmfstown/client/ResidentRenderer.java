package com.bmf.bmfstown.client;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.ModEntities;
import com.bmf.bmfstown.entity.Resident;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * 镇民渲染器（v0.15 皮肤系统：双模型 + 按皮肤选贴图）。
 *
 * <h2>为什么不能直接复用 VillagerRenderer</h2>
 * 原版 {@code VillagerRenderer extends MobRenderer<Villager, VillagerModel<Villager>>}，
 * 两个类型参数都<b>写死成 Villager</b>。而 {@code EntityRenderersEvent.RegisterRenderers
 * #registerEntityRenderer} 的签名是：
 *
 * <pre>{@code <T extends Entity> void registerEntityRenderer(
 *         EntityType<? extends T> type, EntityRendererProvider<T> provider)}</pre>
 *
 * 传 {@code EntityType<Resident>} 进去时，编译器推出 {@code T = Resident}，
 * 但 provider 实际返回的是 {@code T = Villager} 的渲染器，等式约束冲突，
 * 于是报「等式约束条件: Villager，下限: Resident」。
 *
 * <p>解决办法只有一个：<b>自己继承 MobRenderer，把类型参数写成 Resident</b>。
 *
 * <h2>双模型：经典（4px 臂）+ 纤细（3px 臂）</h2>
 * 皮肤 PNG 头/身/腿 UV 完全通用，唯一分歧是手臂：经典臂 4px 粗（UV 窗口宽 16px）、
 * 纤细臂 3px 粗（UV 窗口宽 12px）。所以目录里每张皮肤带 slim 标志，渲染时：
 * <ul>
 *   <li><b>本体模型</b>：烘 {@code PLAYER}（经典）与 {@code PLAYER_SLIM}（纤细）
 *       两套（几何同为标准 HumanoidModel 网格），在 {@link #render} 里按皮肤的
 *       slim 标志直接换 {@code this.model} 引用——原版渲染链（动画/手持/头部）
 *       全部跟着新模型走；</li>
 *   <li><b>护甲</b>：HumanoidArmorLayer 的模型在构造期固定，换不了——
 *       挂<b>两层</b>（经典对 + 纤细对），各在 render 前按 slim 标志二选一放行。</li>
 * </ul>
 * 目录查不到（未指派/目录为空/皮肤被删）→ 经典模型 + 僵尸占位贴图（语义兜底）。
 *
 * <h2>四个渲染层（Layer）</h2>
 * {@code MobRenderer} 默认只画模型本体。要让装备可见，必须手动挂层：
 * <ol>
 *   <li><b>HumanoidArmorLayer ×2</b> —— 四件护甲，经典/纤细各一对；
 *       <b>必须先挂</b>，否则外面的武器会被胸甲遮住。</li>
 *   <li><b>ItemInHandLayer</b> —— 主手/副手物品。</li>
 *   <li><b>CustomHeadLayer</b> —— 头盔以外的"戴在头上"的东西。</li>
 * </ol>
 */
@EventBusSubscriber(modid = BmfTown.MOD_ID, value = Dist.CLIENT)
public class ResidentRenderer extends MobRenderer<Resident, ResidentModel> {

    /** 占位贴图：僵尸（皮肤目录查不到时的语义兜底——"这是没脸居民的临时样子"）。 */
    private static final ResourceLocation FALLBACK_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/entity/zombie/zombie.png");

    private final ResidentModel classicModel;
    private final ResidentModel slimModel;

    public ResidentRenderer(EntityRendererProvider.Context context) {
        // 第三个参数 0.5F 是影子大小（方块单位）。
        // 底模型默认经典；render() 里按皮肤 slim 标志实时换引用。
        super(context, new ResidentModel(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
        this.classicModel = this.model;
        this.slimModel = new ResidentModel(context.bakeLayer(ModelLayers.PLAYER_SLIM));

        // ---- 层 1a：护甲（经典 4px 臂对）----
        this.addLayer(armorLayer(context, false));
        // ---- 层 1b：护甲（纤细 3px 臂对）----
        this.addLayer(armorLayer(context, true));

        // ---- 层 2：手持物 ----
        this.addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));

        // ---- 层 3：头部装饰 ----
        this.addLayer(new CustomHeadLayer<>(this, context.getModelSet(), context.getItemInHandRenderer()));
    }

    /**
     * 造一对护甲层：slim=false 用经典护甲烘焙层，true 用纤细对。
     * 两层都常驻，render 前按实体皮肤的 slim 标志放行其一——
     * HumanoidArmorLayer 的模型是构造期固定的，per-entity 选模型只能靠这个办法。
     */
    private HumanoidArmorLayer<Resident, ResidentModel, HumanoidModel<Resident>> armorLayer(
            EntityRendererProvider.Context context, boolean slim) {
        HumanoidModel<Resident> inner = new HumanoidModel<>(context.bakeLayer(
                slim ? ModelLayers.PLAYER_SLIM_INNER_ARMOR : ModelLayers.PLAYER_INNER_ARMOR));
        HumanoidModel<Resident> outer = new HumanoidModel<>(context.bakeLayer(
                slim ? ModelLayers.PLAYER_SLIM_OUTER_ARMOR : ModelLayers.PLAYER_OUTER_ARMOR));
        return new HumanoidArmorLayer<>(this, inner, outer, context.getModelManager()) {
            @Override
            public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                               Resident entity, float limbSwing, float limbSwingAmount,
                               float partialTicks, float ageInTicks, float netHeadYaw, float headPitch) {
                if (ClientSkinCatalog.isSlim(entity.getSkinId()) != slim) {
                    return; // 不是本层的模型变体：跳过（由另一层负责）
                }
                super.render(poseStack, buffer, packedLight, entity, limbSwing, limbSwingAmount,
                        partialTicks, ageInTicks, netHeadYaw, headPitch);
            }
        };
    }

    @Override
    public void render(Resident entity, float entityYaw, float partialTicks,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        // 本体模型按皮肤 slim 标志切换：动画/手持/头部装饰全跟父渲染链走
        this.model = ClientSkinCatalog.isSlim(entity.getSkinId()) ? this.slimModel : this.classicModel;
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(Resident entity) {
        ResourceLocation tex = ClientSkinCatalog.locationOf(entity.getSkinId());
        return tex != null ? tex : FALLBACK_TEXTURE;
    }

    // ==========================================================
    //  注册
    // ==========================================================

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.RESIDENT.get(), ResidentRenderer::new);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        BmfTown.LOGGER.info("[BmfTown] 镇民渲染器已注册（经典+纤细双模型 + 护甲 + 手持 + 头部装饰）");
    }
}
