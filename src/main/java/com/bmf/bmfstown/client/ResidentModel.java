package com.bmf.bmfstown.client;

import com.bmf.bmfstown.entity.Resident;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.ArmedModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.world.entity.HumanoidArm;

/**
 * 镇民的人形模型。
 *
 * <h2>为什么从 VillagerModel 换成 HumanoidModel</h2>
 * 村民模型是「一个整体 + 鼻子」，<b>没有独立的手臂部件</b>——
 * 手是长袍的一部分，永远垂在身侧。这带来两个致命问题：
 * <ol>
 *   <li><b>拿不了武器</b>：{@code ItemInHandLayer} 要求模型实现 {@link ArmedModel}
 *       （需要 {@code translateToHand} 提供手臂端点），村民模型没实现。</li>
 *   <li><b>穿不了盔甲</b>：原版 {@code ModelLayers} 里<b>没有</b>
 *       {@code VILLAGER_INNER_ARMOR} / {@code VILLAGER_OUTER_ARMOR}，
 *       只有僵尸村民有。盔甲层需要一份"和身体同形但略微膨胀"的模型，
 *       村民模型拿不到这份烘焙层。</li>
 * </ol>
 * 换成 {@link HumanoidModel} 一次解决两个问题：它有 6 个独立部件
 * （头/身/左右臂/左右腿），既实现了 {@link ArmedModel}，又能配合
 * {@link net.minecraft.client.model.geom.ModelLayers#ZOMBIE_INNER_ARMOR}
 * 那套盔甲模型工作（僵尸和类人结构完全一致）。
 *
 * <h2>为什么用 ZOMBIE 的烘焙层而不是 PLAYER 的</h2>
 * 两者几何都是 {@code createMesh(CubeDeformation.NONE, 0.0F)}，形状一样。
 * 选 ZOMBIE 是因为玩家模型有 slim（细臂 3px vs 粗臂 4px）变体，
 * 对应的烘焙层要和模型手臂粗细匹配；僵尸只有一种，省事。
 *
 * <h2>关于 {@link HumanoidModel#getHead()}</h2>
 * 父类已经提供了 {@code getHead()}（返回 {@code this.head}），
 * 而且 {@code HumanoidModel} 本身也实现了 {@code HeadedModel}。
 * 所以这里<b>不需要</b>再实现一遍——重复实现只会让代码显得啰嗦。
 * 同理 {@code translateToHand} 父类也实现好了，直接继承即可。
 */
public class ResidentModel extends HumanoidModel<Resident> {

    public ResidentModel(ModelPart root) {
        super(root);
    }

    /**
     * 构建模型网格。
     *
     * <p>{@code CubeDeformation.NONE} = 不膨胀，就是标准体型。
     * 第二个参数 0.0F 是 y 轴偏移（婴儿模型会用到）。
     *
     * <p>目前没有自己的 {@code ModelLayerLocation}，这个方法暂时用不上；
     * 留着是为了将来自定义体型（比如"镇民比玩家矮半格"）时直接接上，
     * 那时在 {@code ResidentRenderer#onRegisterLayers} 里注册即可。
     */
    public static MeshDefinition createMesh() {
        return HumanoidModel.createMesh(CubeDeformation.NONE, 0.0F);
    }

    /**
     * 把姿态栈移到指定手上，供 {@code ItemInHandLayer} 摆放物品。
     *
     * <p>父类 {@link HumanoidModel#translateToHand(HumanoidArm, PoseStack)}
     * 已经算好了手臂的旋转和长度补偿，所以这里只是显式转调一次，
     * 让"这个模型支持手持物品"这件事在代码里可见（也是 {@link ArmedModel} 的要求）。
     */
    @Override
    public void translateToHand(HumanoidArm side, PoseStack poseStack) {
        super.translateToHand(side, poseStack);
    }
}
