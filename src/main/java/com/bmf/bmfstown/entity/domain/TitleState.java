package com.bmf.bmfstown.entity.domain;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 称号（第九域，v0.16 数据结构先行）：镇民达成特殊事项后获得的称号，
 * 可佩戴一枚显示在名字前面（用户拍板 2026-11-20：显示串=称号+名字直接相连，
 * 无分隔符；具体有哪些称号与 UI 展示后补）。
 *
 * <h2>数据结构（只建仓，不发内容）</h2>
 * <ul>
 *   <li><b>称号定义</b>：{@link com.bmf.bmfstown.entity.ResidentTitles} 数据包
 *       注册表（{@code data/<ns>/titles/*.json}，id→显示文本）。id 未登记时
 *       {@code textOf} 回落显示 id 本身——称号可以先授予后补文案；</li>
 *   <li><b>已获得</b>：按获得顺序去重存 id（履历语义：先得先记）；</li>
 *   <li><b>佩戴</b>：{@code active} 单枚（空串=未佩戴）；只能佩戴已获得的
 *       称号；授予不自动佩戴（展示接线后由 UI/成就逻辑决定）。</li>
 * </ul>
 *
 * <p>授予入口 {@link #award} 是幂等的：重复授予同一称号无害（返回 false）。
 * 撤销 {@link #revoke} 会连带摘下佩戴中的同 id。UI 展示串见
 * {@code Resident.titledName()}（现仅供调试/后续接线，尚未挂名牌）。
 */
public class TitleState {

    /** 已获得的称号 id（按获得顺序；LinkedHashSet 保序去重）。 */
    private final LinkedHashSet<String> earned = new LinkedHashSet<>();

    /** 当前佩戴的称号 id（空串=未佩戴）。 */
    private String active = "";

    /**
     * 授予称号（幂等）：已拥有返回 false，新获得返回 true。
     * 只记 id，不自动佩戴。
     */
    public boolean award(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return this.earned.add(id.trim());
    }

    /** 撤销称号：连带摘下佩戴中的同 id；本来就没有返回 false。 */
    public boolean revoke(String id) {
        if (id == null || !this.earned.remove(id)) {
            return false;
        }
        if (id.equals(this.active)) {
            this.active = "";
        }
        return true;
    }

    /** 有没有该称号。 */
    public boolean has(String id) {
        return id != null && this.earned.contains(id);
    }

    /**
     * 佩戴称号：只有已获得的 id 才能佩戴（返回 false=拒绝）；空串/null=摘下。
     */
    public boolean setActive(String id) {
        if (id == null || id.isBlank()) {
            this.active = "";
            return true;
        }
        if (!this.earned.contains(id)) {
            return false;
        }
        this.active = id;
        return true;
    }

    /** 当前佩戴的称号 id（空串=未佩戴）。 */
    public String activeId() {
        return this.active;
    }

    /** 已获得的称号 id 列表（获得顺序，只读快照）。 */
    public List<String> earnedIds() {
        return List.copyOf(this.earned);
    }

    // ------------------------------------------------------------
    //  序列化（新域：老档无此键 = 空仓默认，零迁移成本）
    // ------------------------------------------------------------

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (String id : this.earned) {
            CompoundTag t = new CompoundTag();
            t.putString("Id", id);
            list.add(t);
        }
        tag.put("Earned", list);
        if (!this.active.isEmpty()) {
            tag.putString("Active", this.active);
        }
        return tag;
    }

    public void loadFrom(CompoundTag tag) {
        this.earned.clear();
        this.active = "";
        ListTag list = tag.getList("Earned", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            String id = list.getCompound(i).getString("Id");
            if (!id.isBlank()) {
                this.earned.add(id);
            }
        }
        // 佩戴的称号必须仍持有（防手改存档悬空佩戴）
        String active = tag.getString("Active");
        if (!active.isBlank() && this.earned.contains(active)) {
            this.active = active;
        }
    }
}
