package com.bmf.bmfstown.book;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 特殊书奖池（v0.11，**不放回抽取**——用户定稿）：每本特殊书被抽中后
 * 即从该镇的池子里移除，剩余的书等概率——先抽中谁，谁的同伴概率就变大，
 * 抽完为止（空池后学者只能写出闲书）。
 *
 * <h2>归属与生命周期</h2>
 * <ul>
 *   <li><b>城镇粒度</b>：每个城镇独立一份池子（与解锁系统同款访问空间）；
 *       新建城镇 = 满池重来；</li>
 *   <li><b>落档</b>：SavedData，重进世界保持；</li>
 *   <li><b>同步登记表</b>：每次抽取前把登记表里"既不在剩余也不在已抽"
 *       的书（新登记的）补进剩余池——将来往 {@link SpecialBookRegistry}
 *       填新书自动入池，老城镇也能抽到；</li>
 *   <li><b>已抽列表单独存</b>：用于同步登记表与将来的图鉴/调试查询
 *       （"该镇还剩哪些书没写出来"）。</li>
 * </ul>
 *
 * <p>⚠ 边缘情况：抽中后书稿若被彻底销毁（实体被命令清除等）不会自动退池——
 * 那本书在该镇就成了绝版。正常流程（死亡掉落、换人继承）不受影响。
 */
public final class BookPoolState extends SavedData {

    private static final String DATA_NAME = "bmfstown_book_pool";

    /** 城镇 id → 剩余池（未抽中的书 id）。 */
    private final Map<UUID, Set<String>> remaining = new HashMap<>();

    /** 城镇 id → 已抽中的书 id（按抽出顺序，供查询/图鉴）。 */
    private final Map<UUID, Set<String>> drawn = new HashMap<>();

    /** 城镇 id → 已"发现"（写出过成书）的书 id——发现广播的永久闩锁。 */
    private final Map<UUID, Set<String>> discovered = new HashMap<>();

    // ==========================================================
    //  存取入口
    // ==========================================================

    public static BookPoolState get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(BookPoolState::new, BookPoolState::load, null), DATA_NAME);
    }

    /**
     * 抽一本特殊书（不放回）：从该镇剩余池等概率取一本并移出。
     *
     * @return 抽中的书定义；剩余池为空（全抽完）或登记表为空返回 null
     *         ——调用方回退写闲书。
     */
    public String draw(ServerLevel level, UUID townId, RandomSource random) {
        Set<String> pool = remaining.computeIfAbsent(townId, k -> new HashSet<>());
        Set<String> done = drawn.computeIfAbsent(townId, k -> new HashSet<>());

        // 新登记的书自动入池（既不在剩余也不在已抽 → 登记表新增）
        for (String id : SpecialBookRegistry.all().keySet()) {
            if (!pool.contains(id) && !done.contains(id)) {
                pool.add(id);
            }
        }
        if (pool.isEmpty()) {
            return null; // 全抽完：只能写闲书
        }
        // 等概率取一本
        String picked = pool.stream().skip(random.nextInt(pool.size())).findFirst().orElseThrow();
        pool.remove(picked);
        done.add(picked);
        this.setDirty();
        return picked;
    }

    /** 该镇已抽出的书（图鉴/调试查询用）。 */
    public Set<String> drawnOf(UUID townId) {
        return Set.copyOf(drawn.getOrDefault(townId, Set.of()));
    }

    /**
     * 全量池抽取（过拟合 perk 专用，v0.10.3）：无视不放回状态，从
     * **登记表全部书目**里等概率取一本。
     *
     * <h2>两种结果的池子处理（用户 00:18 定稿）</h2>
     * <ul>
     *   <li>抽中<b>还没写过</b>的书 → 照常出池（从剩余移入已抽）——
     *       与正常抽取无异，只是多了一层"可能抽到旧的"；</li>
     *   <li>抽中<b>已经写过</b>的书 → 得到重复副本，池子<b>不动</b>
     *       （那本书本来就已出池，再多一本收藏品）。</li>
     * </ul>
     *
     * @return 抽中的书 id；登记表为空返回 null（调用方回退闲书）
     */
    public String drawFromFullPool(ServerLevel level, UUID townId, RandomSource random) {
        var all = SpecialBookRegistry.all().keySet();
        if (all.isEmpty()) {
            return null;
        }
        // 先补登记（与 draw 同口径）：新登记的书先入剩余池
        Set<String> pool = remaining.computeIfAbsent(townId, k -> new HashSet<>());
        Set<String> done = drawn.computeIfAbsent(townId, k -> new HashSet<>());
        for (String id : all) {
            if (!pool.contains(id) && !done.contains(id)) {
                pool.add(id);
            }
        }
        String picked = all.stream().skip(random.nextInt(all.size())).findFirst().orElseThrow();
        if (pool.contains(picked)) {
            // 还没写过：照常出池
            pool.remove(picked);
            done.add(picked);
            this.setDirty();
        }
        // 已写过的：重复副本，池子不动
        return picked;
    }

    /**
     * 退池：书稿被彻底销毁时，把那本特殊书放回该镇剩余池（用户定稿）。
     * 已在剩余池（重复退）或从未入池（坏数据）时静默忽略。
     */
    public void returnToPool(ServerLevel level, UUID townId, String bookId) {
        if (bookId == null || !SpecialBookRegistry.all().containsKey(bookId)) {
            return;
        }
        Set<String> pool = remaining.computeIfAbsent(townId, k -> new HashSet<>());
        if (pool.contains(bookId)) {
            return;
        }
        Set<String> done = drawn.computeIfAbsent(townId, k -> new HashSet<>());
        if (!done.remove(bookId)) {
            return; // 从未抽出过（坏数据），不污染剩余池
        }
        pool.add(bookId);
        this.setDirty();
    }

    /**
     * 标记一本书为"已发现"（写出成书的瞬间）：首次返回 true——
     * 调用方据此发一次性的发现广播（永久闩锁，同镇不重播）。
     */
    public boolean markDiscovered(UUID townId, String bookId) {
        if (bookId == null) {
            return false;
        }
        boolean first = discovered.computeIfAbsent(townId, k -> new HashSet<>())
                .add(bookId);
        if (first) {
            this.setDirty();
        }
        return first;
    }

    // ==========================================================
    //  落档
    // ==========================================================

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag towns = new ListTag();
        Set<UUID> allTowns = new HashSet<>();
        allTowns.addAll(this.remaining.keySet());
        allTowns.addAll(this.drawn.keySet());
        allTowns.addAll(this.discovered.keySet());
        for (UUID townId : allTowns) {
            CompoundTag t = new CompoundTag();
            t.putUUID("Town", townId);
            ListTag rem = new ListTag();
            for (String id : this.remaining.getOrDefault(townId, Set.of())) {
                rem.add(StringTag.valueOf(id));
            }
            t.put("Remaining", rem);
            ListTag drw = new ListTag();
            for (String id : this.drawn.getOrDefault(townId, Set.of())) {
                drw.add(StringTag.valueOf(id));
            }
            t.put("Drawn", drw);
            ListTag dis = new ListTag();
            for (String id : this.discovered.getOrDefault(townId, Set.of())) {
                dis.add(StringTag.valueOf(id));
            }
            t.put("Discovered", dis);
            towns.add(t);
        }
        tag.put("Towns", towns);
        return tag;
    }

    private static BookPoolState load(CompoundTag tag, HolderLookup.Provider registries) {
        BookPoolState state = new BookPoolState();
        ListTag towns = tag.getList("Towns", Tag.TAG_COMPOUND);
        for (int i = 0; i < towns.size(); i++) {
            CompoundTag t = towns.getCompound(i);
            UUID townId = t.getUUID("Town");
            Set<String> rem = new HashSet<>();
            ListTag remTag = t.getList("Remaining", Tag.TAG_STRING);
            for (int j = 0; j < remTag.size(); j++) {
                rem.add(remTag.getString(j));
            }
            Set<String> drw = new HashSet<>();
            ListTag drwTag = t.getList("Drawn", Tag.TAG_STRING);
            for (int j = 0; j < drwTag.size(); j++) {
                drw.add(drwTag.getString(j));
            }
            Set<String> dis = new HashSet<>();
            ListTag disTag = t.getList("Discovered", Tag.TAG_STRING);
            for (int j = 0; j < disTag.size(); j++) {
                dis.add(disTag.getString(j));
            }
            state.remaining.put(townId, rem);
            state.drawn.put(townId, drw);
            state.discovered.put(townId, dis);
        }
        return state;
    }
}
