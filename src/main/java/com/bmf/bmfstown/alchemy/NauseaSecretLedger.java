package com.bmf.bmfstown.alchemy;

import com.bmf.bmfstown.BmfTown;
import com.bmf.bmfstown.config.Config;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 反胃秘传·酒馆双通道记账（v0.16 决策 #64）：反胃秘传书（{@code
 * unlock.alchemy.nausea}）除了写书奖池的正常抽取外，还有两条酒馆题材的
 * <b>额外</b>通道：
 * <ul>
 *   <li><b>写书通道</b>：每镇每游戏日一掷 2%——命中则学者本次开写强制
 *       定死为反胃秘传（{@code BookDrafting.roll} 挂钩），写成即全局解锁
 *       （与普通秘传书同一结算路径）；</li>
 *   <li><b>酒保通道</b>：每镇每游戏日一掷 5%——命中则酒保把一本反胃秘传
 *       成书放进酒窖（{@code TavernkeepGoal} 挂钩），放下的瞬间全局解锁。</li>
 * </ul>
 * <b>全服上限</b>：两通道合计最多发出 {@code tavern.nauseaBookCap}（默认 2）
 * 本——计数在掷中时落账（书可能没写完，但账已记下，防"反复开写白嫖"）；
 * 超限后通道静默失效（掷中也不发）。每镇每日闸门与计数持久化在主世界
 * SavedData——不落档的话读档即重置，"存读档刷秘传"就成立了。
 */
public final class NauseaSecretLedger extends SavedData {

    private static final String DATA_NAME = "bmfstown_nausea_secret";

    /** 反胃秘传的解锁 id（ModPotions.SECRETS 与 SpecialBookRegistry 同键）。 */
    public static final String NAUSEA_UNLOCK = "unlock.alchemy.nausea";

    /** 两通道合计已发出的本数（全服）。 */
    private int issued;

    /** 写书通道：镇 id → 上次掷骰的游戏日（每镇每日一掷闸门）。 */
    private final Map<UUID, Long> writeRolled = new HashMap<>();

    /** 酒保通道：镇 id → 上次掷骰的游戏日。 */
    private final Map<UUID, Long> tavernRolled = new HashMap<>();

    public NauseaSecretLedger() {
    }

    private NauseaSecretLedger(CompoundTag tag) {
        this.issued = tag.getInt("Issued");
        loadDays(tag.getCompound("WriteRolled"), this.writeRolled);
        loadDays(tag.getCompound("TavernRolled"), this.tavernRolled);
    }

    public static NauseaSecretLedger get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(NauseaSecretLedger::new,
                        NauseaSecretLedger::load, null), DATA_NAME);
    }

    // ------------------------------------------------------------
    //  通道掷骰（每镇每日一掷；掷中且全服有余量才发账）
    // ------------------------------------------------------------

    /** 写书通道（{@code BookDrafting.roll} 调用）：命中 = 本次开写定死反胃秘传。 */
    public boolean writeRoll(ServerLevel level, UUID townId, RandomSource random) {
        return roll(level, townId, random, this.writeRolled,
                Config.NAUSEA_WRITE_CHANCE.get());
    }

    /** 酒保通道（{@code TavernkeepGoal} 调用）：命中 = 往酒窖放一本秘传成书。 */
    public boolean tavernRoll(ServerLevel level, UUID townId, RandomSource random) {
        return roll(level, townId, random, this.tavernRolled,
                Config.NAUSEA_ASK_CHANCE.get());
    }

    private boolean roll(ServerLevel level, UUID townId, RandomSource random,
            Map<UUID, Long> gate, int chancePercent) {
        if (townId == null) {
            return false;
        }
        long day = level.getDayTime() / 24000L;
        Long last = gate.get(townId);
        if (last != null && last == day) {
            return false; // 本镇今天已掷过
        }
        gate.put(townId, day); // 落空也记账：一掷一次/日，不吃"反复开写刷概率"
        this.setDirty();
        if (chancePercent <= 0 || random.nextInt(100) >= chancePercent) {
            return false;
        }
        if (this.issued >= Math.max(1, Config.NAUSEA_BOOK_CAP.get())) {
            return false; // 全服上限到：静默失效
        }
        this.issued++;
        this.setDirty();
        return true;
    }

    /** 已发出几本（测试断言用）。 */
    public int issuedCount() {
        return this.issued;
    }

    // ------------------------------------------------------------
    //  存档往返
    // ------------------------------------------------------------

    private static void loadDays(CompoundTag tag, Map<UUID, Long> out) {
        ListTag list = tag.getList("Days", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            out.put(e.getUUID("Town"), e.getLong("Day"));
        }
    }

    private static CompoundTag saveDays(Map<UUID, Long> in) {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Long> e : in.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Town", e.getKey());
            entry.putLong("Day", e.getValue());
            list.add(entry);
        }
        tag.put("Days", list);
        return tag;
    }

    private static NauseaSecretLedger load(CompoundTag tag,
            net.minecraft.core.HolderLookup.Provider registries) {
        return new NauseaSecretLedger(tag);
    }

    @Override
    public CompoundTag save(CompoundTag tag,
            net.minecraft.core.HolderLookup.Provider registries) {
        tag.putInt("Issued", this.issued);
        tag.put("WriteRolled", saveDays(this.writeRolled));
        tag.put("TavernRolled", saveDays(this.tavernRolled));
        BmfTown.LOGGER.debug("[BmfTown][秘传] 反胃秘传账本落档：已发 {} 本", this.issued);
        return tag;
    }
}
