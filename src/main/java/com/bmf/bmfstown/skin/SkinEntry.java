package com.bmf.bmfstown.skin;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * 皮肤清单里的一条皮肤（v0.15 皮肤系统，见 .ai/designs/skin_system.md）。
 *
 * @param id      全局唯一 id（"包名/皮肤id"，如 "townsfolk/townsfolk_01"）
 * @param texture 完整贴图路径（assets/bmfstown/textures/entity/resident_skins/...）
 * @param slim    true = 纤细模型（3px 臂）渲染；false = 经典模型（4px 臂）
 * @param author  来源/作者（CC-BY 授权的皮肤必须署名；记到 wiki 致谢页）
 * @param tags    前向预留：职业换装/节日换装过滤用，v1 不消费
 */
public record SkinEntry(String id, ResourceLocation texture, boolean slim,
                        String author, List<String> tags) {
}
