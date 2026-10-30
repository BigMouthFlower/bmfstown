package com.bmf.bmfstown.social;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * 台词展示实体：原版 {@code text_display} 的薄封装。
 *
 * <p>为什么存在：1.21.1 的 {@code TextDisplay.setText} 与
 * {@code readAdditionalSaveData} 都是 private/protected——原版只留
 * NBT 数据通道。子类里 protected 可见，于是有了 {@link #loadText}。
 */
public class DialogDisplay extends Display.TextDisplay {

    public DialogDisplay(Level level) {
        super(EntityType.TEXT_DISPLAY, level);
    }

    /** 跟随镇民时的位置插值时长（tick）：配合每 tick 重新定位，走起来不抽搐。 */
    public static final int TELEPORT_INTERPOLATION_TICKS = 2;

    /**
     * 用文本组件设置显示内容。
     *
     * <p>踩坑记录：1.21.1 的 TextDisplay 把 NBT 里的 "text" 当 <b>JSON 字符串</b>
     * 读（{@code Component.Serializer.fromJson(getString("text"))}）——不是
     * 1.20.5+ 其他组件字段的原生复合标签！用 ComponentSerialization.CODEC 直接
     * 编出来的裸字符串会被 Gson 当数字/非法 JSON 拒收（异常被原版吞掉只打 WARN，
     * 文本静默丢失，实体顶着空气渲染）。必须用原版 {@code Serializer.toJson}
     * 往返一圈，产出它自己认的 JSON 字符串。
     */
    public void loadText(Component text) {
        CompoundTag data = new CompoundTag();
        data.putString("text",
                Component.Serializer.toJson(text, this.registryAccess()));
        data.putString("billboard", "center");
        data.putInt("line_width", 160);
        data.putInt("teleport_duration", TELEPORT_INTERPOLATION_TICKS);
        this.readAdditionalSaveData(data);
    }
}
