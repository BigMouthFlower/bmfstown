package com.bmf.bmfstown.unlock;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解锁登记表（静态定义，代码声明）：每一个"可被发现的隐藏内容"都必须在这登记。
 *
 * <p>纪律（v0.10 设计讨论定稿）：
 * <ul>
 *   <li><b>没登记的内容对系统来说不存在</b>——防止漏网与剧透失控；</li>
 *   <li><b>依赖必须显式写进数据（requires 字段）</b>，不藏在设计文档或
 *       谓词里——层级关系用字段表达，登记表本身保持扁平（所有定义一层）；</li>
 *   <li><b>注册顺序保证无环</b>：requires 只能引用已注册的 id，
 *       引用未注册的直接抛异常拒收。</li>
 * </ul>
 *
 * <p>登记表只管"定义"；已解锁集合在 {@link UnlockState}（SavedData）。
 * 判定与状态分离：条件以后随便改，已解锁的人不受影响；记录了就永久记录。
 */
public final class UnlockRegistry {

    /** LinkedHashMap 保注册顺序（调试/图鉴渲染需要稳定次序）。 */
    private static final Map<String, UnlockDefinition> DEFS = new LinkedHashMap<>();

    private UnlockRegistry() {
    }

    /**
     * 登记一条解锁定义。
     *
     * @throws IllegalArgumentException 重复注册，或 requires 引用了未注册的前置
     */
    public static void define(UnlockDefinition def) {
        if (DEFS.containsKey(def.id())) {
            throw new IllegalStateException("[BmfTown][解锁] 重复注册: " + def.id());
        }
        for (String req : def.requires()) {
            if (!DEFS.containsKey(req)) {
                throw new IllegalStateException("[BmfTown][解锁] 前置未注册: "
                        + req + " ← " + def.id() + "（先注册前置，再注册依赖它的）");
            }
        }
        DEFS.put(def.id(), def);
    }

    @Nullable
    public static UnlockDefinition byId(String id) {
        return DEFS.get(id);
    }

    public static Collection<UnlockDefinition> all() {
        return List.copyOf(DEFS.values());
    }
}
