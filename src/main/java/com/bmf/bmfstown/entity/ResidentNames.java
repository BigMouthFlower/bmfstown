package com.bmf.bmfstown.entity;

import com.bmf.bmfstown.BmfTown;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ServerLevelAccessor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 镇民名字池（v0.15 数据包化；v0.16 前缀+后缀制 #104；组合规则 2026-11-21 用户拍板）：
 * 从数据包 {@code data/<ns>/names/*.json} 读名字池，生成「前缀 + 后缀」直接相连的
 * 名字（无分隔符，如 黑之剑 / 活闪电 / 黑山脉之子）。
 *
 * <h2>格式（登记制，与皮肤清单同哲学）</h2>
 * <pre>{@code
 * {
 *   "prefixes": [                                  // 前缀池：分组登记
 *     { "group": "animal", "items": ["黑", "狼"],
 *       "onlySuffixGroups": ["animal"] },          // 可选：本组前缀只配这些后缀组
 *     { "group": "special", "excludeSuffixContains": ["之"], "items": ["呼吸的"] }
 *   ],
 *   "suffixes": [                                  // 后缀池：分组登记
 *     { "group": "item", "items": ["之剑", "铁锄头"] },
 *     { "group": "terrain", "weightPerItem": 0.5, "items": ["山脉"] }
 *   ],
 *   "names":    ["阿竹"]                           // 可选：整名直用（组合池全空时回落）
 * }
 * }</pre>
 *
 * <h2>组合规则（2026-11-21 用户拍板，按优先级）</h2>
 * <ol>
 *   <li><b>组限制</b>：前缀组带 {@code onlySuffixGroups} 时，该组前缀只与列出的
 *       后缀组组合（动物前缀只能配动物后缀——含其中的颜色词）；</li>
 *   <li><b>禁配</b>：{@code excludeSuffixContains}——后缀文本<b>含</b>这些串则不
 *       组合（2026-11-21 用户收紧："之"与"的"不能共存=整个后缀任何位置都不能有
 *       '之'，不再只是开头；特殊/动词前缀用）。此类前缀也<b>不追加"之子"</b>；</li>
 *   <li><b>权重</b>：后缀组带 {@code weightPerItem}（默认 1.0）——地形组 0.5 =
 *       每条地形后缀被抽中概率是别的组的一半（组内条目多，靠权重压总量占比）；</li>
 *   <li><b>之子</b>：抽中地形组后缀时 30% 概率名字追加"之子"（黑山→黑山之子）；
 *       动物前缀走不到这（只配动物组）。</li>
 * </ol>
 * 筛空兜底：组限制/禁配把候选筛空时放开全后缀池（绝不无名）；前缀池与后缀池
 * 都空则回落 {@code names} 整名池；再空返回 null。旧格式键 {@code surnames}/
 * {@code given} 仍兼容（按无约束前缀/后缀并入池）。
 *
 * <h2>镇内去重（尽力而为）</h2>
 * {@link #random(RandomSource, ServerLevelAccessor)} 会扫一遍当前世界的镇民
 * 排除已用名，试抓若干次仍全占用则接受重复（用户拍板：允许重复，不造编号兜底名）。
 *
 * <h2>内容纪律</h2>
 * 名单是用户内容：core.json 即用户定稿名单，玩家改 JSON（/reload 生效）即可覆盖。
 * 颜色前缀组已于 2026-11-21 应用户要求移除（与动物前缀组重复，动物组自带颜色词）。
 *
 * <p>赋名调用点只有 Resident.finalizeSpawn 与 readAdditionalSaveData 兜底两处，
 * 依旧走原版 CustomName——命名牌/重命名面板/头顶名牌全兼容，玩家改过不覆盖。
 */
@EventBusSubscriber(modid = BmfTown.MOD_ID)
public final class ResidentNames extends SimpleJsonResourceReloadListener {

    private static final Gson GSON = new Gson();

    /** 去重重试次数：试这么多次仍全占用就接受重复（用户拍板）。 */
    private static final int DEDUP_ATTEMPTS = 10;

    /** 地形后缀组 id（{@code weightPerItem}=0.5 半概率 + 30% 追加"之子"，2026-11-21 拍板）。 */
    public static final String GROUP_TERRAIN = "terrain";

    /** 地形后缀追加"之子"的概率（%，2026-11-21 用户拍板 30）。 */
    static final int TERRAIN_CHILD_CHANCE = 30;

    /** 地形后缀追加的尾缀字面。 */
    static final String CHILD_SUFFIX = "之子";

    /**
     * 前缀条目：文本 + 禁配表（后缀文本含这些串则不组合）+ 组限制
     * （非空=只配列出的后缀组）。
     */
    public record PrefixEntry(String text, List<String> excludeSuffixContains,
                              List<String> onlySuffixGroups) {

        /** 无约束的普通前缀。 */
        public static PrefixEntry plain(String text) {
            return new PrefixEntry(text, List.of(), List.of());
        }

        /** 旧式构造（只带禁配，兼容测试/旧调用）。 */
        public PrefixEntry(String text, List<String> excludeSuffixContains) {
            this(text, excludeSuffixContains, List.of());
        }
    }

    /**
     * 后缀条目：文本 + 所属组 id + 抽取权重（1.0=正常；地形组 0.5=概率减半）。
     */
    public record SuffixEntry(String text, String group, double weight) {

        /** 无权重的普通后缀（general 组，旧格式/测试用）。 */
        public static SuffixEntry plain(String text) {
            return new SuffixEntry(text, "general", 1.0);
        }
    }

    private static volatile List<PrefixEntry> prefixes = List.of();
    private static volatile List<SuffixEntry> suffixes = List.of();
    private static volatile List<String> wholeNames = List.of();

    public ResidentNames() {
        super(GSON, "names");
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new ResidentNames());
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files,
                         ResourceManager resourceManager, ProfilerFiller profiler) {
        BmfTown.LOGGER.info("[BmfTown][名字] reload 收到文件 {} 个：{}", files.size(), files.keySet());
        List<PrefixEntry> pre = new ArrayList<>();
        List<SuffixEntry> suf = new ArrayList<>();
        List<String> whole = new ArrayList<>();
        Set<String> seenPrefix = new HashSet<>();
        Set<String> seenSuffix = new HashSet<>();
        int bad = 0;
        for (Map.Entry<ResourceLocation, JsonElement> file : files.entrySet()) {
            if (!(file.getValue() instanceof JsonObject root)) {
                bad++;
                continue;
            }
            bad += readPrefixGroups(root, pre, seenPrefix);
            bad += readSuffixGroups(root, suf, seenSuffix);
            bad += readStrings(root, "names", whole, null);
            // 旧格式兼容：surnames/given 按无约束前缀/后缀并入池（#104，2026-11-20）
            List<String> legacySur = new ArrayList<>();
            List<String> legacyGiv = new ArrayList<>();
            bad += readStrings(root, "surnames", legacySur, null);
            bad += readStrings(root, "given", legacyGiv, null);
            for (String s : legacySur) {
                addPrefix(pre, seenPrefix, PrefixEntry.plain(s));
            }
            for (String s : legacyGiv) {
                addSuffix(suf, seenSuffix, SuffixEntry.plain(s));
            }
        }
        prefixes = List.copyOf(pre);
        suffixes = List.copyOf(suf);
        wholeNames = List.copyOf(whole);
        BmfTown.LOGGER.info("[BmfTown][名字] 名字池已加载：前缀 {} × 后缀 {}（组合 {} 种）+ 整名 {} 个{}",
                prefixes.size(), suffixes.size(),
                (long) prefixes.size() * Math.max(suffixes.size(), 1),
                wholeNames.size(), bad > 0 ? "（跳过 " + bad + " 条坏条目）" : "");
        // 防回归报警（坑44）：文件收到了却解析出空池 = 有 bug，绝不静默
        if (!files.isEmpty() && prefixes.isEmpty() && suffixes.isEmpty() && wholeNames.isEmpty()) {
            BmfTown.LOGGER.error("[BmfTown][名字] 名字池解析异常：收到 {} 个文件但池子全空，"
                    + "镇民将全部无名（显示为'镇民'）——检查解析逻辑", files.size());
        }
    }

    /** 读 prefixes 分组数组（每项 {group, items, excludeSuffixContains?, onlySuffixGroups?}）。 */
    private static int readPrefixGroups(JsonObject root, List<PrefixEntry> out, Set<String> seen) {
        if (!(root.get("prefixes") instanceof JsonArray groups)) {
            return 0; // 字段可选
        }
        int bad = 0;
        for (JsonElement gEl : groups) {
            if (!(gEl instanceof JsonObject group)) {
                bad++;
                continue;
            }
            List<String> excludes = readStringList(group, "excludeSuffixContains");
            List<String> onlyGroups = readStringList(group, "onlySuffixGroups");
            List<String> items = new ArrayList<>();
            // 去重权归 addPrefix（seen 在入池时检查）；这里 dedupe 必须传 null，
            // 否则 readStrings 先把词占进 seen，addPrefix 再查必撞、全组被吞
            //（#104 引入过这个 bug：138 条全读出但 0 条入池，坑44）
            bad += readStrings(group, "items", items, null);
            for (String item : items) {
                addPrefix(out, seen, new PrefixEntry(item, List.copyOf(excludes), List.copyOf(onlyGroups)));
            }
        }
        return bad;
    }

    /** 读 suffixes 分组数组（每项 {group, items, weightPerItem?}）。 */
    private static int readSuffixGroups(JsonObject root, List<SuffixEntry> out, Set<String> seen) {
        if (!(root.get("suffixes") instanceof JsonArray groups)) {
            return 0;
        }
        int bad = 0;
        for (JsonElement gEl : groups) {
            if (!(gEl instanceof JsonObject group)) {
                bad++;
                continue;
            }
            String groupId = group.get("group") instanceof JsonElement g
                    && g.isJsonPrimitive() ? g.getAsString().trim() : "general";
            double weightPerItem = 1.0D;
            if (group.get("weightPerItem") instanceof JsonElement w && w.isJsonPrimitive()) {
                try {
                    weightPerItem = Math.max(0.01D, w.getAsDouble());
                } catch (NumberFormatException e) {
                    bad++; // 坏权重落默认值
                }
            }
            List<String> items = new ArrayList<>();
            bad += readStrings(group, "items", items, null); // 去重权归 addSuffix（坑44）
            for (String item : items) {
                addSuffix(out, seen, new SuffixEntry(item, groupId, weightPerItem));
            }
        }
        return bad;
    }

    /** 读字符串数组字段（trim，空条目跳过）。 */
    private static List<String> readStringList(JsonObject group, String field) {
        List<String> out = new ArrayList<>();
        if (group.get(field) instanceof JsonArray arr) {
            for (JsonElement el : arr) {
                if (el.isJsonPrimitive() && !el.getAsString().isBlank()) {
                    out.add(el.getAsString().trim());
                }
            }
        }
        return out;
    }

    /** 前缀去重入池：同文本首见优先（约束差异以先出现者为准）。 */
    private static void addPrefix(List<PrefixEntry> out, Set<String> seen, PrefixEntry entry) {
        if (seen.add(entry.text())) {
            out.add(entry);
        }
    }

    /** 后缀去重入池。 */
    private static void addSuffix(List<SuffixEntry> out, Set<String> seen, SuffixEntry entry) {
        if (seen.add(entry.text())) {
            out.add(entry);
        }
    }

    /** 读一个字符串数组字段（trim；dedupe 非空时跨组去重），返回坏条目数。 */
    private static int readStrings(JsonObject root, String field, List<String> out, Set<String> dedupe) {
        if (!(root.get(field) instanceof JsonArray arr)) {
            return 0; // 字段可选
        }
        int bad = 0;
        for (JsonElement el : arr) {
            if (el.isJsonPrimitive() && !el.getAsString().isBlank()) {
                String v = el.getAsString().trim();
                if (dedupe == null || dedupe.add(v)) {
                    out.add(v);
                }
            } else {
                bad++;
            }
        }
        return bad;
    }

    // ==========================================================
    //  取名
    // ==========================================================

    /**
     * 随机取名（带镇内去重，尽力而为）。
     *
     * @return 名字；池子全空返回 null（调用方不设名）
     */
    public static String random(RandomSource random, ServerLevelAccessor level) {
        Set<String> used = collectUsedNames(level);
        for (int i = 0; i < DEDUP_ATTEMPTS; i++) {
            String candidate = rawRandom(random);
            if (candidate == null) {
                return null;
            }
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
        // 池子枯竭（重试全撞）：接受重复（用户拍板 #48）
        return rawRandom(random);
    }

    /** 无去重随机取名；池子全空返回 null。 */
    public static String rawRandom(RandomSource random) {
        List<PrefixEntry> pre = prefixes;
        List<SuffixEntry> suf = suffixes;
        if (!pre.isEmpty() && !suf.isEmpty()) {
            PrefixEntry p = pre.get(random.nextInt(pre.size()));
            List<SuffixEntry> candidates = filterSuffixes(suf, p);
            // 筛空兜底：组限制/禁配把候选筛空 → 放开全池（绝不无名）
            if (candidates.isEmpty()) {
                candidates = suf;
            }
            SuffixEntry s = weightedPick(candidates, random);
            String name = p.text() + s.text();
            // 地形后缀 30% 追加"之子"（2026-11-21 拍板）；"不和之共存"的前缀不追加；
            // 后缀自身含'的'的也不追加（之/的不能共存的另一半，推定待用户纠偏：
            // 否则"生苔的巨石"→"生苔的巨石之子"）
            if (GROUP_TERRAIN.equals(s.group()) && allowsZhiChild(p)
                    && !s.text().contains("的")
                    && random.nextInt(100) < TERRAIN_CHILD_CHANCE) {
                name = name + CHILD_SUFFIX;
            }
            return name;
        }
        List<String> whole = wholeNames;
        if (!whole.isEmpty()) {
            return whole.get(random.nextInt(whole.size()));
        }
        return null;
    }

    /** 按前缀的组限制+禁配筛后缀候选（无约束返回原池引用，零开销）。 */
    private static List<SuffixEntry> filterSuffixes(List<SuffixEntry> suf, PrefixEntry p) {
        boolean groupRestricted = !p.onlySuffixGroups().isEmpty();
        boolean hasExcludes = !p.excludeSuffixContains().isEmpty();
        if (!groupRestricted && !hasExcludes) {
            return suf;
        }
        List<SuffixEntry> filtered = new ArrayList<>(suf.size());
        for (SuffixEntry s : suf) {
            if (groupRestricted && !p.onlySuffixGroups().contains(s.group())) {
                continue;
            }
            boolean banned = false;
            for (String ban : p.excludeSuffixContains()) {
                if (s.text().contains(ban)) { // 2026-11-21 收紧：任何位置含禁串都不配
                    banned = true;
                    break;
                }
            }
            if (!banned) {
                filtered.add(s);
            }
        }
        return filtered;
    }

    /** "不和之共存"的前缀（禁配含'之'）不追加"之子"尾缀。 */
    private static boolean allowsZhiChild(PrefixEntry p) {
        return !p.excludeSuffixContains().contains("之");
    }

    /** 按条目权重随机抽（权重和为分母；weights ≤0 已在解析时钳到 ≥0.01）。 */
    private static SuffixEntry weightedPick(List<SuffixEntry> cands, RandomSource random) {
        double total = 0;
        for (SuffixEntry c : cands) {
            total += c.weight();
        }
        double roll = random.nextDouble() * total;
        for (SuffixEntry c : cands) {
            roll -= c.weight();
            if (roll < 0) {
                return c;
            }
        }
        return cands.get(cands.size() - 1); // 浮点边界兜底
    }

    /** 收集当前世界镇民已占用的名字（去重用；非服务端/world 空则返回空集）。 */
    private static Set<String> collectUsedNames(ServerLevelAccessor level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return Set.of();
        }
        Set<String> used = new HashSet<>();
        for (Entity e : serverLevel.getEntities().getAll()) {
            if (e instanceof Resident r && r.hasCustomName()) {
                used.add(r.getName().getString());
            }
        }
        return used;
    }

    // ==========================================================
    //  测试专用（GameTest 注入假池；生产路径绝不调用）
    // ==========================================================

    /** GameTest 用：临时注入假池（无约束旧语义），测组合/回落/空态逻辑。 */
    public static void testOnlyLoad(List<String> surnames, List<String> given, List<String> wholeNames) {
        List<PrefixEntry> pre = new ArrayList<>();
        for (String s : surnames) {
            pre.add(PrefixEntry.plain(s));
        }
        List<SuffixEntry> suf = new ArrayList<>();
        for (String s : given) {
            suf.add(SuffixEntry.plain(s));
        }
        ResidentNames.prefixes = List.copyOf(pre);
        ResidentNames.suffixes = List.copyOf(suf);
        ResidentNames.wholeNames = List.copyOf(wholeNames);
    }

    /** GameTest 用：注入带约束的前缀组（后缀按普通条目处理）。 */
    public static void testOnlyLoadGrouped(List<PrefixEntry> prefixes, List<String> suffixes,
                                           List<String> wholeNames) {
        List<SuffixEntry> suf = new ArrayList<>();
        for (String s : suffixes) {
            suf.add(SuffixEntry.plain(s));
        }
        testOnlyLoadFull(prefixes, List.copyOf(suf), wholeNames);
    }

    /** GameTest 用：注入完整条目（测组限制/权重/之子规则）。 */
    public static void testOnlyLoadFull(List<PrefixEntry> prefixes, List<SuffixEntry> suffixes,
                                        List<String> wholeNames) {
        ResidentNames.prefixes = List.copyOf(prefixes);
        ResidentNames.suffixes = List.copyOf(suffixes);
        ResidentNames.wholeNames = List.copyOf(wholeNames);
    }
}
