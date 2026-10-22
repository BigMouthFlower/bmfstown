package com.bmf.bmfstown.client;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.ModEntities;
import com.bmf.bmfstown.entity.Resident;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
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
 * 镇民渲染器。
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
 * <h2>三个渲染层（Layer）</h2>
 * {@code MobRenderer} 默认只画模型本体。要让装备可见，必须手动挂层——
 * 每层各自负责一类附加物，按添加顺序从内到外叠画：
 * <ol>
 *   <li><b>HumanoidArmorLayer</b> —— 四件护甲。它拿两份"膨胀模型"
 *       （内层贴身、外层略大），按物品的 {@code ArmorMaterial} 查贴图。
 *       <b>必须先挂</b>，否则外面的武器会被胸甲遮住。</li>
 *   <li><b>ItemInHandLayer</b> —— 主手/副手物品。用
 *       {@code ArmedModel.translateToHand} 定位到手掌，然后按物品类型
 *       选 {@code ItemDisplayContext}（剑是 THIRD_PERSON_RIGHT_HAND）。</li>
 *   <li><b>CustomHeadLayer</b> —— 头盔以外的"戴在头上"的东西：
 *       头颅方块、南瓜头、雕刻南瓜。护甲层只管 {@code ArmorItem}，
 *       这些不属于护甲，得单独一层。</li>
 * </ol>
 *
 * <h2>贴图</h2>
 * 现在是僵尸贴图占位（浅绿），因为它是标准 {@code HumanoidModel} 的 UV 布局。
 * 换成村民外观的自定义皮肤时，只需要替换 {@link #TEXTURE} 这一行，
 * 并且新贴图必须按 {@code HumanoidModel} 的 UV 排布画（不能用村民的贴图直接套，
 * 村民贴图的 UV 是另一套布局，套上去会错位）。
 */
@EventBusSubscriber(modid = BmfTown.MOD_ID, value = Dist.CLIENT)
public class ResidentRenderer extends MobRenderer<Resident, ResidentModel> {

    /** 占位贴图：僵尸。换自定义皮肤时只改这一行。 */
    private static final ResourceLocation TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/entity/zombie/zombie.png");

    public ResidentRenderer(EntityRendererProvider.Context context) {
        // 第三个参数 0.5F 是影子大小（方块单位）
        super(context, new ResidentModel(context.bakeLayer(ModelLayers.ZOMBIE)), 0.5F);

        // ---- 层 1：护甲 ----
        // 参数说明：渲染器自己、内层模型、外层模型、模型管理器（查盔甲贴图集用）
        this.addLayer(new HumanoidArmorLayer<>(
                this,
                new ResidentModel(context.bakeLayer(ModelLayers.ZOMBIE_INNER_ARMOR)),
                new ResidentModel(context.bakeLayer(ModelLayers.ZOMBIE_OUTER_ARMOR)),
                context.getModelManager()));

        // ---- 层 2：手持物 ----
        this.addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));

        // ---- 层 3：头部装饰 ----
        this.addLayer(new CustomHeadLayer<>(this, context.getModelSet(), context.getItemInHandRenderer()));
    }

    @Override
    public ResourceLocation getTextureLocation(Resident entity) {
        return TEXTURE;
    }

    // ==========================================================
    //  注册
    // ==========================================================

    /**
     * 注册到实体渲染器表。
     *
     * <p>注意这里用的是 {@code ResidentRenderer::new}，类型参数是 Resident，
     * 与 {@code EntityType<Resident>} 匹配，不会再出现泛型冲突。
     */
    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.RESIDENT.get(), ResidentRenderer::new);
    }

    /**
     * 注册自定义模型层（烘焙用的网格）。
     *
     * <p>为什么需要这一步：{@code context.bakeLayer(ModelLayers.ZOMBIE)} 用的是
     * <b>原版已经注册好的</b>僵尸层，不需要我们做什么。但如果以后要做
     * 独立的镇民模型（不同身高、不同体型），就得在这里注册自己的
     * {@link ModelLayerLocation}，否则 {@code bakeLayer} 会抛
     * "Unknown model layer"。
     *
     * <p>现在留个空实现 + 注释，是为了让你知道扩展点在哪。
     */
    @SubscribeEvent
    public static void onRegisterLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        // 目前复用原版 ZOMBIE 层，无需注册。将来自定义体型时在这里加：
        // event.registerLayerDefinition(ResidentModel.LAYER, ResidentModel::createMesh);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        BmfTown.LOGGER.info("[BmfTown] 镇民渲染器已注册（人形模型 + 护甲 + 手持 + 头部装饰）");
    }
}
