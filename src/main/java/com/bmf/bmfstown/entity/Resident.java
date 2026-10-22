package com.bmf.bmfstown.entity;

import com.bmf.bmfstown.config.Config;
import com.bmf.bmfstown.entity.profile.HungerSystem;
import com.bmf.bmfstown.entity.profile.ModDataSerializers;
import com.bmf.bmfstown.entity.profile.ProfessionType;
import com.bmf.bmfstown.entity.profile.ResidentInventory;
import com.bmf.bmfstown.entity.profile.ResidentProfile;
import com.bmf.bmfstown.menu.ResidentMenuProvider;
import com.bmf.bmfstown.town.Town;
import com.bmf.bmfstown.town.TownZone;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.List;
import java.util.UUID;

/**
 * 镇民 —— 本 mod 的核心实体。
 *
 * <h2>为什么继承 PathfinderMob 而不是 Mob</h2>
 * <ul>
 *   <li>{@code Mob} 有生命周期、属性、可受伤，但<b>没有寻路能力</b>，
 *       得自己装配 {@code MoveControl} 与 {@code Navigation}。</li>
 *   <li>{@link PathfinderMob} 已经把这套装好，并提供 {@code goalSelector}
 *       与 {@code targetSelector} 两个 AI 调度器。原版村民、僵尸、铁傀儡都继承它。</li>
 * </ul>
 *
 * <h2>实体类的三条铁律</h2>
 * <ol>
 *   <li><b>构造函数里不要 new 别的实体或方块。</b>
 *       实体会在客户端、服务端各构造一次，还可能被反复重建。</li>
 *   <li><b>需要同步给客户端的字段，必须走 {@link SynchedEntityData}。</b>
 *       普通字段客户端看不到，会造成"服务端知道、客户端不知道"的诡异现象。</li>
 *   <li><b>自定义字段必须写进 NBT。</b>
 *       否则存档重进就丢。</li>
 * </ol>
 */
public class Resident extends PathfinderMob {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 同步数据：镇民档案。
     *
     * <p>这是 v0.3 的核心改动：把饥饿、心情、好感度、爱好、专长、经验、归属
     * 全部打包进 {@link ResidentProfile}，用<b>一个</b> accessor 同步。
     *
     * <p>为什么不再用 {@code DATA_LEVEL}：
     * <ul>
     *   <li>等级是经验的函数，存两份就有"不一致"的可能</li>
     *   <li>爱好是结构化数据，塞不进 INT</li>
     * </ul>
     *
     * <p>初始化由 {@code defineSynchedData} 提供（那时序列化器一定已注册）。
     */
    private static final EntityDataAccessor<ResidentProfile> DATA_PROFILE =
            SynchedEntityData.defineId(Resident.class, ModDataSerializers.PROFILE.get());

    /**
     * 背包（27 格）。
     *
     * <p><b>只在服务端有实际内容</b>。客户端实例的背包是空的，
     * 因为物品同步要靠 UI 打开时的容器同步（S7 做）。
     * 现在这个阶段客户端拿不到背包内容，不影响任何逻辑（逻辑都在服务端跑）。
     */
    private final ResidentInventory inventory = new ResidentInventory();

    /** 距离上次饥饿结算累积的 tick 数。 */
    private int hungerTickAccumulator = 0;

    /**
     * 城镇归属检查的倒计时（tick）。
     *
     * <p>见 {@link #tickTownConfinement()}：每 20 tick（1 秒）查一次
     * "我还在不属于我的城镇范围吗"。不用每 tick 查——城镇边界
     * 不会一秒变一次，省下来的性能留给寻路。
     */
    private int townCheckCooldown = 0;

    /** 进食冷却剩余 tick 数。 */
    private int eatCooldown = 0;

    // ---- 觅食状态（就餐区箱子补给，v0.6） ----
    // 状态机：NONE（空闲）→ GO_ZONE（去就餐区）→ GO_CHEST（去箱子）
    //        → TAKE（开箱停留半秒再拿）→ NONE
    private enum ForageState { NONE, GO_ZONE, GO_CHEST, TAKE }

    private ForageState forageState = ForageState.NONE;
    /** 已锁定的目标箱子（到达就餐区后扫描得出）。 */
    @Nullable
    private BlockPos forageChest = null;
    /** 触发检查节流：每 20 tick 才巡一遍"饿了没食物"，别每 tick 扫背包。 */
    private int forageCheckCooldown = 0;
    /** 找不到可用就餐箱后的重试倒计时（配置 ai.forageRetryTicks）。 */
    private int forageRetryCooldown = 0;
    /** 本轮觅食已进行的 tick 数（超时兜底）。 */
    private int forageTicks = 0;
    /** 导航被打断后"再推一把"的次数（防无限重推）。 */
    private int forageRepicks = 0;
    /** TAKE 状态的停留倒计时（开箱动画时间）。 */
    private int takeDelay = 0;

    /**
     * 距离上一次回血累积的 tick 数。
     *
     * <p>详见 {@link #tickRegeneration()}。饥饿跌破回血门槛时会清零，
     * 这样"重新吃饱"要重新等满一个间隔，不会立刻跳出一颗心。
     */
    private int regenTickAccumulator = 0;

    /**
     * 回血门槛：饥饿值达到这个数才自然回血。
     *
     * <p>取 18 是沿用原版玩家的判据（原版 {@code FoodData.tick} 里
     * {@code hunger >= 18} 才进入回血分支）。20 太苛刻——镇民每分钟掉 1 点，
     * 刚喂满到 20，下一分钟跌到 19 就断，玩家会陷入反复喂的疲劳。
     * 18 留出 2 点缓冲，喂一次能维持一阵。
     */
    /** 回血门槛已配置化：{@link com.bmf.bmfstown.config.Config#REGEN_HUNGER_THRESHOLD}。 */

    /**
     * 回血间隔（tick）。80 tick = 4 秒 = 半颗心。
     *
     * <p>这个数值直接取自原版 {@code FoodData.tick} 的"无饱和度"分支。
     * 我们没有饱和度（刻意的设计决定：饱和度会让生食在数值上天然吃亏，
     * 从而毁掉"食物偏好"这个特色），所以取原版的无饱和档速。
     * 20 点血从空回满约 80 秒。
     */
    /** 回血间隔已配置化：{@link com.bmf.bmfstown.config.Config#REGEN_INTERVAL}。 */

    /** 每次回多少血。1.0 = 半颗心，与原版一致。 */
    /** 回血量已配置化：{@link com.bmf.bmfstown.config.Config#REGEN_AMOUNT}。 */

    // ==========================================================
    //  进食（原子操作：一口 = 一次完整的对调-吃-换回）
    // ==========================================================

    /*
     * 设计原则：<b>一口是原子的</b>。
     *
     * 一次「饿了 → 主手对调 → 吃一口 → 消耗 + 涨饥饿 → 换回来」是不可分割的
     * 完整动作。跑完之后实体回到"什么都没发生过"的干净状态：主手是剑、
     * 背包是它该有的样子、没有任何残留字段。
     *
     * "一直吃到饱"不是靠一个长的会话循环实现的，而是靠<b>冷却驱动的重复调用</b>：
     * 这一口吃完、换回剑、进冷却；冷却结束后如果还饿，就再走一遍完整流程。
     *
     * 为什么这样更好：
     *   · 主手被占用的时间窗口就是 32 tick（吃一口的时长），
     *     而不是"吃到饱"的总时长。被打断的窗口小得多。
     *   · 冷却期间他是自由的：主手是剑、可以正常行走/看人/挨打。
     *   · 不需要"会话""被打断"这些状态，字段从 5 个降到 3 个。
     *   · 每个出口都回到同一个干净状态，没有半截状态可言。
     */

    /**
     * 正在吃的那份食物在背包里原来的槽位；{@code -1} = 没在对调状态。
     *
     * <p>有效期覆盖<b>整堆</b>：从第一口对调开始，一直到这堆吃完 / 吃饱 /
     * 被打断才置回 {@code -1}。中途每嚼一口都不变。
     *
     * <p>对调之后，这个槽位里放的是<b>主手原来那件东西</b>（剑），
     * 而不是食物——食物已经上主手了。所以这里不是"食物槽"，是"对调槽"。
     */
    private int eatingSlot = -1;

    /**
     * 这一口正在吃的食物（主手上那份，也就是整个食物堆）。
     *
     * <p>吃完后通常变成空气；如果是蘑菇煲/药水这类，会变成空碗/玻璃瓶。
     *
     * <p><b>注意：这个栈在吃完之后就读不到了。</b>
     * {@code setItemSlot} 存的是<b>同一个对象引用</b>，而
     * {@code LivingEntity.startUsingItem} 也是直接引用它。
     * 等 {@code completeUsingItem} 跑完，原版 {@code Item.finishUsingItem}
     * 已经把这个对象消耗成"空气"了，此时再 {@code get(DataComponents.FOOD)}
     * 只会拿到 {@code null}。
     * 所以营养值必须在开始吃之前存进 {@link #eatingNutrition}。
     */
    private ItemStack eatingFood = ItemStack.EMPTY;

    /**
     * 这一口的营养值。**在开始吃之前就要取好**，原因见 {@link #eatingFood}。
     *
     * <p>用 {@code -1} 表示"没有有效营养值"。
     */
    private int eatingNutrition = -1;

    /**
     * "使用状态已结束、但还没等到结算回调"的等待计数。
     *
     * <p>见 {@link #tickEating()} 里的说明：原版 tick 链中
     * {@code completeUsingItem} 与 {@code customServerAiStep} 的先后不可控，
     * 所以不能用"这一 tick 有没有结算"来判断是否被打断，
     * 而是给一个短窗口等结算回调。窗口内来了就是正常吃完，
     * 超时没来才是被打断。
     */
    private int awaitingSettle = 0;

    /**
     * 等待结算的容忍 tick 数。
     *
     * <p>取值 2 的理由：同一个 tick 内两种顺序都有可能，
     * 留 1 tick 余量应对"结算在下一 tick 才发生"的情况。
     * 再大就会让"被打断后主手迟迟不还原"变得可感知。
     */
    private static final int SETTLE_GRACE_TICKS = 2;

    /**
     * 构造。
     *
     * <p>参数类型必须是 {@code EntityType<? extends Resident>}，
     * 不能写 {@code EntityType<Resident>} —— 否则以后做子类时泛型不兼容。
     */
    public Resident(EntityType<? extends Resident> type, Level level) {
        super(type, level);
        // 阻止自然消失：原版生物离玩家太远会被清除，镇民不该这样
        this.setPersistenceRequired();
        // 名字常显（配合下面的 shouldShowName）
        this.setCustomNameVisible(true);
    }

    // ==========================================================
    //  属性
    // ==========================================================

    /**
     * 属性表。注意这是 <b>static</b> 方法 ——
     * NeoForge 在注册阶段通过 {@code EntityAttributeCreationEvent} 调用它，
     * 为每个 EntityType 构建一份模板，而非每个实例构建一次。
     *
     * <p>数值参考原版村民（血量 20、移速 0.5），略作调整：
     * <ul>
     *   <li>血量 20 = 10 颗心，与村民一致，不至于一刀死</li>
     *   <li>移速 0.5 = 村民速度，走起来不慌不忙，符合「镇民」气质</li>
     *   <li>跟随范围 32 = 比村民（48）小，减少寻路压力</li>
     * </ul>
     */
    public static AttributeSupplier.Builder createAttributes() {
        // 注意：属性注册早于配置加载，必须走带兜底的读取
        //（直接 Config.MAX_HEALTH.get() 会在启动期抛 IllegalStateException）
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, Config.maxHealthOrDefault())
                .add(Attributes.MOVEMENT_SPEED, Config.movementSpeedOrDefault())
                .add(Attributes.FOLLOW_RANGE, Config.followRangeOrDefault());
    }

    // ==========================================================
    //  AI
    // ==========================================================

    /**
     * 注册 AI 目标。
     *
     * <p>{@code goalSelector} 中每个 Goal 有优先级（数字越小越优先），
     * 同一时刻只有一个 Goal 能占用移动控制权（{@code MOVE} / {@code LOOK}）。
     * 高优先级会抢占低优先级，结束后低优先级自动恢复 ——
     * 这是后续「饿了要吃饭」能打断「随便溜达」的机制基础。
     */
    @Override
    protected void registerGoals() {
        // 优先级 0：掉水里先浮上来。几乎所有陆地生物都有这条
        this.goalSelector.addGoal(0, new FloatGoal(this));

        // 优先级 5：随便溜达（避开水面，免得走进去淹死）。
        // 有城镇归属时，漫步目标会被限制在城镇长方体内（见 TownBoundedStrollGoal）
        this.goalSelector.addGoal(5, new TownBoundedStrollGoal(this, Config.STROLL_SPEED.get()));

        // 优先级 6：看向附近的玩家
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));

        // 优先级 7：无事可做时东张西望
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }

    // ==========================================================
    //  同步数据
    // ==========================================================

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        // 默认档案：先给一份"满饥饿满心情"的，finalizeSpawn 里会替换成随机的
        builder.define(DATA_PROFILE, ResidentProfile.createRandom(this.random));
    }

    // ==========================================================
    //  档案读写（v0.3 核心）
    // ==========================================================

    /** 读取当前档案。客户端与服务端都有值。 */
    public ResidentProfile getProfile() {
        return this.entityData.get(DATA_PROFILE);
    }

    /**
     * 写入档案。
     *
     * <p>只在服务端调用。{@code SynchedEntityData.set} 内部会比较新旧值，
     * 不同才发包，所以这里不需要自己判断。
     */
    public void setProfile(ResidentProfile profile) {
        this.entityData.set(DATA_PROFILE, profile);
    }

    /** 便捷方法：返回修改后的新档案并写回。 */
    private void updateProfile(java.util.function.UnaryOperator<ResidentProfile> mutation) {
        this.setProfile(mutation.apply(this.getProfile()));
    }

    // ---- 便捷访问器（UI 与 AI 常用） ----

    public int getHunger() {
        return this.getProfile().hunger();
    }

    public int getMood() {
        return this.getProfile().mood();
    }

    public int getAffection() {
        return this.getProfile().affection();
    }

    public ProfessionType getProfession() {
        return this.getProfile().profession();
    }

    public int getLevel() {
        return this.getProfile().level();
    }

    /** 背包（仅服务端有意义）。 */
    public ResidentInventory getInventory() {
        return this.inventory;
    }

    /**
     * 把 27 格背包暴露给命令系统（{@code /item replace ... container.N}）。
     *
     * <h2>为什么要重写这里</h2>
     * {@code /item replace entity <目标> container.0 ...} 走的是
     * {@link #getSlot(int)}。原版 {@code Mob} 的默认实现只暴露它自带的
     * <b>8 格捡物背包</b>（而且行为和 {@code CanPickUpLoot} 纠缠在一起），
     * 我们的 27 格 {@link ResidentInventory} 命令系统根本看不见——
     * 这就是命令报"没有对象在0槽位接受了物品"的直接原因。
     *
     * <p>重写之后：{@code container.0} ~ {@code container.26} 对应背包的
     * 27 格，其余编号交给原版逻辑（原版用 100+ 编号映射装备栏，
     * 比如主手/副手/盔甲，具体偏移量由 {@code LivingEntity} 决定）。
     *
     * <p>注意顺序：<b>背包判断在前，super 在后</b>。
     * 如果反过来，原版可能先把编号吃掉。
     */
    @Override
    public net.minecraft.world.entity.SlotAccess getSlot(int slot) {
        if (slot >= 0 && slot < ResidentInventory.SIZE) {
            return net.minecraft.world.entity.SlotAccess.forContainer(this.inventory, slot);
        }
        return super.getSlot(slot);
    }

    // ==========================================================
    //  交互：右键打开面板
    // ==========================================================

    /**
     * 玩家右键点他时调用。
     *
     * <p>只在服务端打开界面——客户端调用 {@code openMenu} 是无效的，
     * 因为界面必须由服务端下发（否则容器 id 对不上，点击全废）。
     *
     * <p>三种返回值的含义：
     * <ul>
     *   <li>{@code SUCCESS} → 已处理，客户端播放手臂摆动动画</li>
     *   <li>{@code CONSUME} → 已处理，但不摆手</li>
     *   <li>{@code PASS} → 没处理，让原版继续尝试别的东西</li>
     * </ul>
     *
     * <h3>为什么用 openMenu(provider, extraDataWriter) 而不是 openMenu(provider, pos)</h3>
     * 那个重载是给"方块容器"用的（写一个 BlockPos）。
     * 我们的容器属于<b>实体</b>，得传 entityId，让客户端那边能还原出是哪个镇民。
     */
    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        // 副手不重复触发，避免一次右键开两次界面
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }

        if (!this.level().isClientSide && player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(
                    new ResidentMenuProvider(this),
                    buf -> buf.writeVarInt(this.getId())
            );
        }
        return InteractionResult.sidedSuccess(this.level().isClientSide);
    }

    /**
     * 装备变化后调用，让属性立刻重算。
     *
     * <p>原版其实每 tick 都会从装备重算一次属性，所以这个调用在功能上是冗余的。
     * 保留它是为了两个原因：
     * <ol>
     *   <li>UI 打开时换装备，护甲数值能立即变（不用等下一 tick）</li>
     *   <li>以后加"卫士攻击力随熟练度提升"这类逻辑时，这里是个天然的挂载点</li>
     * </ol>
     */
    public void onEquipChanged() {
        this.setHealth(this.getHealth());  // 触发一次属性重算，同时不会掉血
    }

    /**
     * 档案的一行文字摘要。调试与 UI 共用。
     *
     * <p>为什么写在这里而不是 {@link ResidentProfile#toString()}：
     * {@code toString()} 是 record 自动生成的，改不了；
     * 而摘要需要"名字 + 档案"两边的信息，只能放在实体这一层。
     */
    public String debugSummary() {
        ResidentProfile p = this.getProfile();
        // String.format 的 %s 一律走 toString()，这里显式取中文名，避免输出 SWEET / NOISE 这种英文枚举名
        return String.format(
                "%s | 饥饿=%d 心情=%d 好感=%d | 专长:%s 称号:%s Lv%d xp=%d | 喜欢:%s/%s/%s/%s | 讨厌:%s/%s",
                this.getName().getString(),
                p.hunger(), p.mood(), p.affection(),
                p.profession().zhName(), p.title(), p.level(), p.xp(),
                p.hobbyFood().zhName(), p.hobbyHomeItem().zhName(),
                p.hobbyEnvironment().zhName(), p.hobbyAmbience().zhName(),
                p.dislikeItem().zhName(), p.dislikeEnvironment().zhName());
    }

    /**
     * 背包里第一件食物的营养值，没有食物返回 {@code -1}。
     *
     * <p>调试用：能一眼看出"他背包里到底有没有能吃的"。
     */
    public int firstFoodNutrition() {
        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            var stack = this.inventory.getItem(i);
            var food = stack.get(net.minecraft.core.component.DataComponents.FOOD);
            if (food != null) {
                return food.nutrition();
            }
        }
        return -1;
    }

    // ==========================================================
    //  生成时取名
    // ==========================================================

    /**
     * 实体首次生成时调用。适合在这里做随机化 —— 名字、外观、偏好都塞这里。
     *
     * <p>注意 {@code finalizeSpawn} 只在<b>自然生成</b>和<b>刷怪蛋</b>时触发，
     * 用 {@code /summon} 命令或从存档读取时<b>不会</b>调用。
     * 所以名字不能只在这里设，还要考虑存档场景（见 {@link #readAdditionalSaveData}）。
     */
    @Nullable
    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
                                        MobSpawnType spawnType, @Nullable SpawnGroupData spawnGroupData) {
        SpawnGroupData data = super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData);

        // 只在还没有名字时取名，避免覆盖玩家用命名牌改过的名字
        if (!this.hasCustomName()) {
            this.setCustomName(Component.literal(ResidentNames.random(level.getRandom())));
        }

        // 生成一份随机的镇民档案（饥饿满、心情满、爱好随机、无专长）
        this.setProfile(ResidentProfile.createRandom(level.getRandom()));

        LOGGER.debug("[BmfTown] 生成镇民：{}，喜欢 {} / 讨厌 {}",
                this.getName().getString(),
                this.getProfile().hobbyFood().zhName(),
                this.getProfile().dislikeItem().zhName());
        return data;
    }

    // ==========================================================
    //  NBT 存取
    // ==========================================================

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        // 边界情况：恰好存档时正在吃那一口。
        //
        // 此时主手和背包第 eatingSlot 格处于"对调"状态：
        // 那一格里是剑、主手上是食物。如果把中间状态存下来，
        // 读档后 eatingSlot 这些瞬态字段就没了，剑会永久留在背包里。
        //
        // 所以干脆在存档前撤销对调（settleBite 的全部数据操作），
        // 存下去就是干净状态。代价只是当前这一口白吃了，玩家察觉不到。
        if (this.eatingSlot >= 0) {
            this.settleBite();
        }
        // 档案整体存成一个子 Compound，字段增加时这里不用改
        tag.put("ResidentProfile", this.getProfile().save());
        // 背包（需要注册表来保存物品的数据组件）
        tag.put("ResidentInventory", this.inventory.save(this.registryAccess()));
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);

        if (tag.contains("ResidentProfile", Tag.TAG_COMPOUND)) {
            this.setProfile(ResidentProfile.load(tag.getCompound("ResidentProfile")));
        } else {
            // 老存档（S1 时代）没有档案，给一份随机的
            this.setProfile(ResidentProfile.createRandom(this.getRandom()));
        }

        if (tag.contains("ResidentInventory", Tag.TAG_COMPOUND)) {
            this.inventory.load(tag.getCompound("ResidentInventory"), this.registryAccess());
        }

        // 兜底：老存档 / summon 出来的镇民没有名字，补一个
        if (!this.hasCustomName()) {
            this.setCustomName(Component.literal(ResidentNames.random(this.getRandom())));
        }
    }

    // ==========================================================
    //  饥饿（服务端逻辑）
    // ==========================================================

    /**
     * 服务端每 tick 调用一次。
     *
     * <p>这里做三件事：推进饥饿计时、按规则进食、吃饱时回血。
     *
     * <p><b>为什么放在 {@code customServerAiStep} 而不是 {@code tick}：</b>
     * {@code customServerAiStep} 只在服务端执行，天然不会在客户端跑一遍
     * （客户端跑的话会和服务端算出的值打架，导致饥饿条乱跳）。
     */
    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();

        // ---- 1. 推进饥饿 ----
        this.hungerTickAccumulator++;
        int ticksPerPoint = Config.HUNGER_TICKS_PER_POINT.get();
        if (this.hungerTickAccumulator >= ticksPerPoint) {
            this.hungerTickAccumulator = 0;
            ResidentProfile before = this.getProfile();
            ResidentProfile after = HungerSystem.tickHunger(before, ticksPerPoint);
            if (after != null) {
                this.setProfile(after);
            }
        }

        // ---- 2. 进食 ----
        this.tickEating();

        // ---- 3. 回血 ----
        this.tickRegeneration();

        // ---- 4. 城镇归属约束 ----
        this.tickTownConfinement();

        // ---- 5. 觅食（就餐区箱子补给） ----
        this.tickForaging();
    }

    // ==========================================================
    //  城镇归属（v0.5：领地绑定，活动范围锁定）
    // ==========================================================

    /** 归属检查间隔（tick）。1 秒一次，理由见 {@link #townCheckCooldown}。 */
    /** 检查间隔已配置化：{@link com.bmf.bmfstown.config.Config#TOWN_CHECK_INTERVAL}。 */

    /** 被关在外面（被推走/传送）后回家用的速度。比漫步（0.6）快一点，别让玩家等太久。 */
    /** 回家速度已配置化：{@link com.bmf.bmfstown.config.Config#RETURN_HOME_SPEED}。 */

    /**
     * 归属约束的核心逻辑，每 {@link Config#TOWN_CHECK_INTERVAL} tick 跑一次：
     * <ol>
     *   <li>档案里没有城镇 → 什么都不做（自由镇民随便逛）</li>
     *   <li>城镇已被删除 / 镇民被带到了别的维度 → 自动解除绑定
     *       （挂在已删除城镇上的 UUID 是悬空引用，与其留着当 bug，不如当场清掉）</li>
     *   <li>人还在城镇里 → 什么都不做</li>
     *   <li>人跑到城镇外了（被推走、被船带走、被玩家拎着传送）→ 寻路回城镇中心</li>
     * </ol>
     *
     * <h2>为什么"锁范围"不靠这一个方法</h2>
     * 越界回家是<b>兜底</b>。正常情况下他根本走不出去——
     * {@link TownBoundedStrollGoal} 生成的漫步目标永远在城镇框内，
     * 他没有理由出去。两个机制合起来才是完整的"不能到处乱跑"：
     * 漫步管"想出去也出不去"，这里管"万一出去了就回来"。
     */
    private void tickTownConfinement() {
        if (this.townCheckCooldown-- > 0) {
            return;
        }
        this.townCheckCooldown = Config.TOWN_CHECK_INTERVAL.get();

        if (!(this.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return;
        }
        UUID townId = this.getProfile().townId();
        if (townId == null) {
            return;
        }

        Town town = com.bmf.bmfstown.town.TownSaveData.get(serverLevel).findTown(townId.toString());
        if (town == null || !town.dimension().equals(serverLevel.dimension().location().toString())) {
            // 城镇没了（手册里删掉了）或人被带去了别的维度：解除绑定。
            // 档案同步走 SynchedEntityData，面板上的"归属"行会立刻变回"自由身"。
            LOGGER.debug("[BmfTown] {} 的城镇 {} 已不存在，自动解除归属",
                    this.getName().getString(), townId);
            // 住宅区属于某个城镇，城镇没了住宅绑定也就失效了，一并清掉
            this.setProfile(this.getProfile().withTownId(null).withHousing(null, 0L));
            return;
        }

        if (town.contains(this.blockPosition())) {
            return; // 人在城里，一切正常
        }

        // 越界了：走向城镇中心（y 夹回城镇的 y 范围，防止中心点悬空/埋地）
        BlockPos center = townCenter(town);
        this.getNavigation().moveTo(
                center.getX() + 0.5D, center.getY(), center.getZ() + 0.5D, Config.RETURN_HOME_SPEED.get());
        LOGGER.debug("[BmfTown] {} 跑出了城镇「{}」，正在返回 ({})",
                this.getName().getString(), town.name(), center.toShortString());
    }

    /** 城镇外框的几何中心，y 夹进外框范围（中心点必须落在实体能站的空间语义内）。 */
    private static BlockPos townCenter(Town town) {
        return new BlockPos(
                (town.min().getX() + town.max().getX()) / 2,
                Mth.clamp((town.min().getY() + town.max().getY()) / 2,
                        town.min().getY(), town.max().getY()),
                (town.min().getZ() + town.max().getZ()) / 2);
    }

    /** 档案里绑定的城镇（仅在镇民当前所处维度有效时返回，否则 null）。服务端专用。 */
    @Nullable
    private Town boundTown() {
        if (!(this.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return null;
        }
        UUID townId = this.getProfile().townId();
        if (townId == null) {
            return null;
        }
        Town town = com.bmf.bmfstown.town.TownSaveData.get(serverLevel).findTown(townId.toString());
        if (town == null || !town.dimension().equals(serverLevel.dimension().location().toString())) {
            return null;
        }
        return town;
    }

    // ==========================================================
    //  觅食（v0.6：就餐区箱子 → 背包）
    // ==========================================================
    //
    //  设计（与产品决策一致）：
    //   · 触发：饥饿没满 + 背包里没有任何食物（复用"没有能吃的"语义）；
    //   · 选区：所属城镇的就餐区里选中心点最近的（就近原则第一级）；
    //   · 选箱：到达就餐区后扫描区域内所有容器，选带食物的最近的
    //     （就近原则第二级）；
    //   · 拿取：像玩家一样"打开"箱子（盖子动画 + 音效），停留半秒，
    //     然后拿最多 FORAGE_TAKE_COUNT（默认 3）个——不够就拿全部；
    //   · 兜底：任何一步失败 → 放弃本轮，FORAGE_RETRY_TICKS 后重试。
    //
    //  状态机为什么这样设计（第一版踩的坑）：
    //   1. 到达判定用 3 格而不是 2.5——寻路的终点不一定紧贴箱子
    //      （箱子周围可能站不进去），阈值太苛刻会导致"走到了旁边
    //      却判为没到→放弃→空手离开"，玩家看到的就是"他走了但没拿"；
    //   2. 导航被打断（推挤/漫步抢路径）先重推最多 8 次再放弃，
    //      而不是一次 isDone 就整轮作废；
    //   3. 整轮 60 秒超时兜底——不管卡在哪一步都会回到空闲态，
    //      绝不存在"永远站着"的死态。

    /** 区域扫描的方块数上限——防止超大选区一次扫描卡服。 */
    private static final int MAX_ZONE_SCAN = 20 * 20 * 20;
    /** 整轮觅食超时（tick），60 秒。 */
    private static final int FORAGE_TIMEOUT_TICKS = 20 * 60;
    /** 导航重推次数上限，超过即视为走不到。 */
    private static final int FORAGE_MAX_REPICK = 8;
    /** 判定"到达箱子旁"的距离（格）。 */
    private static final double CHEST_REACH = 3.0D;
    /** 开箱后停留的 tick 数（盖子动画 + 音效的表演时间）。 */
    private static final int TAKE_DELAY_TICKS = 10;

    private void tickForaging() {
        if (this.forageRetryCooldown > 0) {
            this.forageRetryCooldown--;
        }

        switch (this.forageState) {
            case NONE -> {
                if (this.forageCheckCooldown-- > 0) {
                    return;
                }
                this.forageCheckCooldown = 20;
                this.tryStartForaging();
            }
            case GO_ZONE -> this.tickGoZone();
            case GO_CHEST -> this.tickGoChest();
            case TAKE -> this.tickTake();
        }
    }

    /** 触发觅食：饿了 + 背包无食物 + 有城镇 → 走向最近的就餐区。 */
    private void tryStartForaging() {
        if (this.forageRetryCooldown > 0) {
            return;
        }
        if (this.getProfile().hunger() >= ResidentProfile.MAX_HUNGER) {
            return; // 不饿
        }
        if (this.hasAnyFood()) {
            return; // 背包还有吃的，先吃自己的
        }
        TownZone zone = nearestDiningZone();
        if (zone == null) {
            return; // 没绑城镇或城镇没有就餐区——安静放弃，下轮再查
        }
        if (LOGGER.isDebugEnabled()) {
            Town town = boundTown();
            List<TownZone> allZones = town == null
                    ? List.of()
                    : town.zones(com.bmf.bmfstown.town.TownWandMode.DINING);
            LOGGER.debug("[BmfTown][觅食] 城镇共有 {} 个就餐区：{}",
                    allZones.size(),
                    allZones.stream()
                            .map(z -> z.min().toShortString() + "~" + z.max().toShortString())
                            .collect(java.util.stream.Collectors.joining("、")));
        }
        this.beginForaging();
        if (zone.contains(this.blockPosition())) {
            // 人已经在就餐区里：直接扫箱
            this.scanForChest(zone);
        } else {
            this.navigateToZone(zone);
            LOGGER.debug("[BmfTown][觅食] {} 出发去就餐区 ({})~({})",
                    this.getName().getString(), zone.min().toShortString(),
                    zone.max().toShortString());
        }
    }

    /** 开启一轮觅食（状态与计时归零）。 */
    private void beginForaging() {
        this.forageState = ForageState.GO_ZONE;
        this.forageTicks = 0;
        this.forageRepicks = 0;
        this.forageChest = null;
    }

    /** 阶段 1：走向就餐区；到了就扫箱。 */
    private void tickGoZone() {
        if (!this.tickForageTimeout()) {
            return;
        }
        TownZone zone = nearestDiningZone();
        if (zone == null) {
            this.cancelForaging(); // 城镇/区域被删，安静收场
            return;
        }
        if (zone.contains(this.blockPosition())) {
            this.scanForChest(zone);
            return;
        }
        // 没到：走丢了就再推一把，推满次数才认输
        if (this.getNavigation().isDone()) {
            this.repickOrGiveUp(() -> this.navigateToZone(zone), "走不到就餐区");
        }
    }

    /** 阶段 2：走向锁定的箱子；够了就开箱。 */
    private void tickGoChest() {
        if (!this.tickForageTimeout()) {
            return;
        }
        BlockPos chest = this.forageChest;
        if (chest == null || nearestDiningZone() == null) {
            this.cancelForaging();
            return;
        }
        if (chest.closerThan(this.blockPosition(), CHEST_REACH)) {
            // 到了：进入"开箱"表演阶段
            this.forageState = ForageState.TAKE;
            this.takeDelay = TAKE_DELAY_TICKS;
            this.getNavigation().stop();
            this.containerFx((net.minecraft.server.level.ServerLevel) this.level(), chest, true);
            return;
        }
        if (this.getNavigation().isDone()) {
            this.repickOrGiveUp(() -> this.getNavigation().moveTo(
                    chest.getX() + 0.5D, chest.getY(), chest.getZ() + 0.5D,
                    Config.STROLL_SPEED.get()), "走不到箱子");
        }
    }

    /** 阶段 3：盖子已打开，停留半秒，真的拿取。 */
    private void tickTake() {
        if (this.takeDelay-- > 0) {
            return;
        }
        BlockPos chest = this.forageChest;
        if (chest != null && this.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            this.doTakeFood(serverLevel, chest);
            this.containerFx(serverLevel, chest, false);
        }
        this.cancelForaging();
    }

    /** 就餐区里选"中心点最近的一个"（就近原则第一级）。 */
    @Nullable
    private TownZone nearestDiningZone() {
        Town town = boundTown();
        if (town == null) {
            return null;
        }
        TownZone best = null;
        double bestDist = Double.MAX_VALUE;
        for (TownZone zone : town.zones(com.bmf.bmfstown.town.TownWandMode.DINING)) {
            BlockPos c = zoneCenter(zone);
            double d = c.distSqr(this.blockPosition());
            if (best == null || d < bestDist) {
                best = zone;
                bestDist = d;
            }
        }
        return best;
    }

    private void navigateToZone(TownZone zone) {
        BlockPos c = zoneCenter(zone);
        this.getNavigation().moveTo(
                c.getX() + 0.5D, c.getY(), c.getZ() + 0.5D, Config.STROLL_SPEED.get());
    }

    /** 就餐区里找"带食物的最近容器"（就近原则第二级），锁定后走过去。 */
    private void scanForChest(TownZone zone) {
        if (!(this.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            this.cancelForaging();
            return;
        }
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        int scanned = 0;
        int containers = 0;
        for (BlockPos pos : BlockPos.betweenClosed(zone.min(), zone.max())) {
            if (++scanned > MAX_ZONE_SCAN) {
                LOGGER.warn("[BmfTown] {} 的就餐区太大（>{} 方块），跳过扫箱",
                        this.getName().getString(), MAX_ZONE_SCAN);
                this.giveUpForaging("选区过大");
                return;
            }
            // 注意：大箱子（DoubleChest）是两个方块实体，直接 getBlockEntity
            // 只能看到所在那一半的 27 格——牛排放在另一半就会误判"空箱"。
            // getContainerAt 是原版漏斗用的取容器入口，会把大箱子两半
            // 合并成一个 54 格容器，箱子/木桶/潜影盒也都覆盖。
            net.minecraft.world.Container container =
                    net.minecraft.world.level.block.entity.HopperBlockEntity.getContainerAt(serverLevel, pos);
            if (container == null) {
                continue;
            }
            containers++;
            if (!containerHasFood(container)) {
                // 排障：把容器里的东西逐格报出来，看是"真的没食物"还是"食物判定没认出来"。
                // 注意用 isDebugEnabled 包住：LOGGER.debug 的参数会先求值，
                // 不加判断的话即使关日志也照样遍历整个容器，白耗性能。
                if (LOGGER.isDebugEnabled()) {
                    StringBuilder contents = new StringBuilder();
                    for (int i = 0; i < container.getContainerSize(); i++) {
                        ItemStack st = container.getItem(i);
                        if (!st.isEmpty()) {
                            if (contents.length() > 0) {
                                contents.append(", ");
                            }
                            contents.append(BuiltInRegistries.ITEM.getKey(st.getItem()))
                                    .append("x").append(st.getCount());
                        }
                    }
                    LOGGER.debug("[BmfTown][觅食] {} 扫到容器 {}，内容物=[{}]，均无食物组件",
                            this.getName().getString(), pos.immutable().toShortString(),
                            contents.length() == 0 ? "空" : contents);
                }
                continue;
            }
            double d = pos.distSqr(this.blockPosition());
            if (best == null || d < bestDist) {
                best = pos.immutable(); // betweenClosed 复用可变对象，必须 immutable() 收藏
                bestDist = d;
            }
        }
        if (best == null) {
            LOGGER.debug("[BmfTown][觅食] {} 扫描就餐区 ({})~({}) 完成：容器 {} 个，均无食物或无容器",
                    this.getName().getString(), zone.min().toShortString(),
                    zone.max().toShortString(), containers);
            this.giveUpForaging("就餐区里没有带食物的容器");
            return;
        }
        this.forageChest = best;
        this.forageState = ForageState.GO_CHEST;
        this.forageRepicks = 0;
        this.getNavigation().moveTo(
                best.getX() + 0.5D, best.getY(), best.getZ() + 0.5D, Config.STROLL_SPEED.get());
        LOGGER.debug("[BmfTown][觅食] {} 锁定食物容器 {}",
                this.getName().getString(), best.toShortString());
    }

    /** 真正的拿取：最多 FORAGE_TAKE_COUNT 个，不足拿全部；塞不下的放回箱子。 */
    private void doTakeFood(net.minecraft.server.level.ServerLevel level, BlockPos chest) {
        // 与扫描同款：必须用 getContainerAt 合并大箱子两半，
        // 否则 getBlockEntity 只拿到所在半边的 27 格——
        // 食物在另一半时就是"开盖→摸空→合盖"无限循环（实测踩坑）。
        net.minecraft.world.Container container =
                net.minecraft.world.level.block.entity.HopperBlockEntity.getContainerAt(level, chest);
        if (container == null) {
            return; // 容器在这几秒里被拆了——空手而归
        }
        int want = Config.FORAGE_TAKE_COUNT.get();
        int taken = 0;
        for (int i = 0; i < container.getContainerSize() && taken < want; i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty() || stack.get(DataComponents.FOOD) == null) {
                continue;
            }
            int n = Math.min(want - taken, stack.getCount());
            ItemStack grabbed = stack.split(n);
            container.setItem(i, stack.isEmpty() ? ItemStack.EMPTY : stack); // 触发容器标记变更
            ItemStack leftover = this.inventory.addItem(grabbed);
            if (!leftover.isEmpty()) {
                container.setItem(i, leftover); // 背包塞不下：剩余的放回
                taken += n - leftover.getCount();
                break;
            }
            taken += n;
        }
        if (taken > 0) {
            LOGGER.info("[BmfTown] {} 从 {} 处的箱子拿了 {} 个食物",
                    this.getName().getString(), chest.toShortString(), taken);
        }
    }

    /** 超时兜底。返回 false 表示本轮已被超时终结。 */
    private boolean tickForageTimeout() {
        if (++this.forageTicks > FORAGE_TIMEOUT_TICKS) {
            this.giveUpForaging("超时");
            return false;
        }
        return true;
    }

    /** 导航走丢了：重推一次；推满 FORAGE_MAX_REPICK 次才放弃本轮。 */
    private void repickOrGiveUp(Runnable repath, String reason) {
        this.forageRepicks++;
        if (this.forageRepicks > FORAGE_MAX_REPICK) {
            this.giveUpForaging(reason);
            return;
        }
        LOGGER.debug("[BmfTown][觅食] {} 导航未达（{}），第 {} 次重推",
                this.getName().getString(), reason, this.forageRepicks);
        repath.run();
    }

    /**
     * 播放容器开/关的表演。
     *
     * <h2>通用，不挑容器类型</h2>
     * <ul>
     *   <li><b>动画</b>：箱子与木桶的原版盖子动画都由"方块事件 1（打开
     *       人数 +/-）"驱动，谁支持就给谁发，不支持的事件会被静默忽略；</li>
     *   <li><b>音效</b>：直接取该方块自己的 {@link net.minecraft.sounds.SoundType}
     *       的开/关音——箱子出箱子声、木桶出木桶声，加新容器类型零改动。</li>
     * </ul>
     * 原版玩家开容器的声音走的正是同一条路（ContainerOpenersCounter
     * 里也是 {@code state.getSoundType().getOpenSound()}）。
     */
    private void containerFx(net.minecraft.server.level.ServerLevel level, BlockPos pos, boolean open) {
        var state = level.getBlockState(pos);
        var block = state.getBlock();
        if (block instanceof net.minecraft.world.level.block.ChestBlock
                || block instanceof net.minecraft.world.level.block.BarrelBlock) {
            level.blockEvent(pos, block, 1, open ? 1 : 0);
        }
        // 1.21.1 的 SoundType 没有开/关音，原版也是按容器类型各自写死的；
        // 这里统一映射，新增容器类型在这里加一行即可。
        net.minecraft.sounds.SoundEvent sound;
        if (block instanceof net.minecraft.world.level.block.BarrelBlock) {
            sound = open ? net.minecraft.sounds.SoundEvents.BARREL_OPEN
                         : net.minecraft.sounds.SoundEvents.BARREL_CLOSE;
        } else if (block instanceof net.minecraft.world.level.block.ShulkerBoxBlock) {
            sound = open ? net.minecraft.sounds.SoundEvents.SHULKER_BOX_OPEN
                         : net.minecraft.sounds.SoundEvents.SHULKER_BOX_CLOSE;
        } else {
            sound = open ? net.minecraft.sounds.SoundEvents.CHEST_OPEN
                         : net.minecraft.sounds.SoundEvents.CHEST_CLOSE;
        }
        level.playSound(null, pos, sound,
                net.minecraft.sounds.SoundSource.BLOCKS, 0.5F,
                level.random.nextFloat() * 0.1F + 0.9F);
    }

    /** 本轮觅食失败收场：冷却一段时间再试（避免空转）。 */
    private void giveUpForaging(String reason) {
        this.cancelForaging();
        this.forageRetryCooldown = Config.FORAGE_RETRY_TICKS.get();
        LOGGER.info("[BmfTown][觅食] {} 本轮失败（{}），{} tick 后重试",
                this.getName().getString(), reason, this.forageRetryCooldown);
    }

    /** 结束本轮觅食（成功或中性收场），不设冷却。 */
    private void cancelForaging() {
        this.forageState = ForageState.NONE;
        this.forageChest = null;
        this.forageTicks = 0;
        this.forageRepicks = 0;
    }

    /** 背包里是否还有任何食物（不管营养多少、能不能不浪费地吃下）。 */
    private boolean hasAnyFood() {
        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            ItemStack stack = this.inventory.getItem(i);
            if (!stack.isEmpty() && stack.get(DataComponents.FOOD) != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean containerHasFood(net.minecraft.world.Container container) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && stack.get(DataComponents.FOOD) != null) {
                return true;
            }
        }
        return false;
    }

    /** 就餐区的几何中心（y 夹进区域范围，理由同 {@link #townCenter}）。 */
    private static BlockPos zoneCenter(com.bmf.bmfstown.town.TownZone zone) {
        return new BlockPos(
                (zone.min().getX() + zone.max().getX()) / 2,
                Mth.clamp((zone.min().getY() + zone.max().getY()) / 2,
                        zone.min().getY(), zone.max().getY()),
                (zone.min().getZ() + zone.max().getZ()) / 2);
    }

    /**
     * 受限漫步：有城镇归属时，随机目标永远落在城镇长方体内。
     *
     * <h2>为什么继承原版漫步而不是重写一个 Goal</h2>
     * {@code WaterAvoidingRandomStrollGoal} 的整套调度（间隔、冷却、
     * 水面规避、寻路失败放弃）都是现成的，我们只想换"目标点怎么挑"——
     * 那正是 {@code getPosition()} 这一个钩子的职责。继承 + 覆写一个方法，
     * 比复制一份 Goal 改三行要少背一倍的维护负担。
     *
     * <h2>目标点怎么挑</h2>
     * 在城镇外框内（缩进 1 格，避免贴墙卡住）随机取 x/z，
     * y 沿用当前高度并夹回外框范围。不找"能站住的地面"：
     * 寻路器自己会处理"目标在楼上/楼下/够不着"——够不着就放弃，
     * 下一轮漫步再挑一个，自然收敛到能走到的位置。
     *
     * <h2>只约束服务端</h2>
     * Goal 两端都会跑，但客户端没有 SavedData（查不到城镇），
     * 所以客户端永远走 super 的原版逻辑。位置同步由服务端权威，
     * 客户端少算一遍目标点没有任何可见差异。
     */
    static class TownBoundedStrollGoal extends WaterAvoidingRandomStrollGoal {

        TownBoundedStrollGoal(Resident mob, double speedModifier) {
            super(mob, speedModifier);
        }

        @Override
        @Nullable
        protected Vec3 getPosition() {
            if (this.mob instanceof Resident resident) {
                Town town = resident.boundTown();
                if (town != null) {
                    return resident.randomTownPos(town);
                }
            }
            return super.getPosition();
        }
    }

    /**
     * 在城镇外框内随机取一个漫步目标点。
     * x/z 缩进 1 格（贴边的格子容易卡在墙上），y 沿用当前高度并夹回范围。
     */
    @Nullable
    Vec3 randomTownPos(Town town) {
        RandomSource random = this.getRandom();
        int spanX = Math.max(1, town.max().getX() - town.min().getX() - 1);
        int spanZ = Math.max(1, town.max().getZ() - town.min().getZ() - 1);
        int x = Mth.clamp(town.min().getX() + 1 + random.nextInt(spanX),
                town.min().getX(), town.max().getX());
        int z = Mth.clamp(town.min().getZ() + 1 + random.nextInt(spanZ),
                town.min().getZ(), town.max().getZ());
        int y = Mth.clamp(this.blockPosition().getY(),
                town.min().getY(), town.max().getY());
        return Vec3.atBottomCenterOf(new BlockPos(x, y, z));
    }

    /**
     * 自然回血。饥饿够高就慢慢回，饿了就停。
     *
     * <h2>为什么单开一个方法，而不是塞进进食流程</h2>
     * <b>吃就是吃，回血就是回血。</b>吃饭只负责改变饥饿值，回血只负责看饥饿值。
     * 两件事完全解耦，理由：
     * <ul>
     *   <li>原版玩家的回血本来就不在吃饭流程里，而在 {@code FoodData.tick}
     *       的持续判断里。我们照这个模型走，行为一致，玩家不用学新规则。</li>
     *   <li>解耦之后"食物偏好"才能只影响心情和台词，不影响任何数值。
     *       否则喂他爱吃的生胡萝卜 = 回血慢，偏好就成了纯劣势。</li>
     *   <li>以后要调回血节奏只改这里的参数，碰不到进食状态机。</li>
     * </ul>
     *
     * <h2>为什么没有饱和度</h2>
     * 这是刻意的设计决定，不是偷懒。原版饱和度会让熟食在"耐饿"上
     * 严格优于生食，那样"喜欢吃生食的镇民"就永远吃亏。
     * 去掉饱和度后，生食只是营养低（多吃几个），而不是被双重惩罚。
     *
     * <h2>饿到 0 不掉血</h2>
     * 与原版玩家不同（原版饥饿 0 且血量 > 10 时会缓慢掉血）。
     * 镇民是玩家的居民，不是需要一直盯着照顾的宠物——
     * 饿着他只是不回血、状态差，不该以死亡收场。
     * 真要惩罚，用心情下降更温和。这条留待以后再说。
     */
    private void tickRegeneration() {
        // 没吃饱：清零计时器，一点都不攒。
        // 清零而不是"暂停"是有意的：饥饿跌破 18 → 再喂回 18，
        // 必须重新等满一个完整间隔，不能立刻跳出一颗心。
        if (this.getProfile().hunger() < Config.REGEN_HUNGER_THRESHOLD.get()) {
            this.regenTickAccumulator = 0;
            return;
        }
        // 血是满的：也没必要攒，省点判断
        if (this.getHealth() >= this.getMaxHealth()) {
            this.regenTickAccumulator = 0;
            return;
        }

        this.regenTickAccumulator++;
        if (this.regenTickAccumulator < Config.REGEN_INTERVAL.get()) {
            return;
        }
        this.regenTickAccumulator = 0;
        float before = this.getHealth();
        this.heal(Config.REGEN_AMOUNT.get().floatValue());
        LOGGER.debug("[BmfTown] {} 自然回血 0.5 心，血量 {} → {}，饥饿 {}",
                this.getName().getString(), before, this.getHealth(),
                this.getProfile().hunger());
    }

    /**
     * 进食状态机。每 tick 服务端跑一次。
     *
     * <h2>状态</h2>
     * <pre>
     *   [空闲] ──饿、冷却完、有得吃──▶ [对调] ──开始咀嚼──▶ [进食中]
     *     ▲                                                    │
     *     │                                            32 tick 嚼完
     *     │                                                    ▼
     *     └──────────────── [空闲] ◀──── 撤销对调 ◀──── [结算]
     *                          ▲                            │
     *                          └──还饿且这一堆还有──┐         │
     *                                               └─────────┘
     * </pre>
     *
     * <p>一次"对调"覆盖一整堆食物：剑进背包那一格，整堆食物上主手，
     * 一直吃到这堆没了（或吃饱了）才把剑换回来。中途被打断也一定换回来。
     *
     * <p>"吃饱之后还要不要再开一堆"由冷却驱动：收尾后 {@code eatCooldown}
     * 开始倒数，数完如果还饿，就自然再走一遍。
     */
    private void tickEating() {
        if (this.eatCooldown > 0) {
            this.eatCooldown--;
        }

        if (this.eatingSlot >= 0) {
            // ---- 有一口正在进行中的对调 ----
            if (this.isUsingItem()) {
                // 交给原版推进（音效、粒子、举臂动画、32 tick 计时）
                this.awaitingSettle = 0;
                return;
            }

            // 使用状态没了。要区分两种情况，但它们在这个时点上**看起来一样**：
            //   a) 刚嚼完 —— completeUsingItem 已经/即将被调用
            //   b) 被打断 —— completeUsingItem 永远不会来
            //
            // 原版 tick 链里 completeUsingItem 与 customServerAiStep 的先后
            // 不是我们能控制的（不同版本、不同映射都可能不同），所以不能
            // 靠"这一 tick 有没有结算过"来判断。给它一个短窗口：
            // 等几 tick，如果 completeUsingItem 来了，finishBite 会把
            // eatingSlot 置回 -1，我们下次就进不到这里；
            // 等满窗口还没来，那就是真被打断。
            if (this.awaitingSettle < SETTLE_GRACE_TICKS) {
                this.awaitingSettle++;
                return;
            }
            this.abortBite();
            return;
        }

        // ---- 空闲 ----
        this.awaitingSettle = 0;
        if (this.eatCooldown > 0) {
            return;
        }
        // 双保险：万一还有 use-item 状态残留（别的来源把主手换成了可食物品），
        // 绝不在这个当口再开一堆，否则会和那次使用重叠成"同时吃两个"。
        if (this.isUsingItem()) {
            return;
        }
        this.startBite();
    }

    /**
     * 原版"物品使用完成"的唯一回调。**吃这一口的结算入口。**
     *
     * <h2>为什么必须挂在这里</h2>
     * {@code completeUsingItem()} 是原版唯一一个"这一口真的嚼完了"的信号。
     *
     * <p>之前的实现靠"主手物品空了没有"来判断，但原版
     * {@code Item.finishUsingItem} 里加饱食度那段有 {@code instanceof Player}
     * 的门槛，非玩家实体什么都不做——主手那份食物吃完后原样还在，
     * 于是每轮都判定"没吃完"，立刻重开动画，变成无限循环。
     * 这就是"看起来一直在吃、但什么都不消耗"的直接原因。
     *
     * <h2>⚠ super 里面已经吃掉了这一口</h2>
     * {@code super.completeUsingItem()} 内部会调
     * {@code ItemStack.finishUsingItem(...)}，那里面就有 {@code shrink(1)}
     * ——所以<b>消耗必须完全交给 super</b>，{@link #finishBite()} 里
     * 绝不能再调一次 {@code eat()}，否则一个曲奇掉两个（已踩过）。
     * 容器返还（蘑菇煲剩碗、药水剩瓶）也由 super 处理好了。
     *
     * <p>但 super 涨饥饿那段有 {@code instanceof Player} 门槛，对镇民不生效，
     * 所以饥饿得我们在 {@link #finishBite()} 里按 nutrition 补上。
     */
    @Override
    protected void completeUsingItem() {
        super.completeUsingItem();
        // 只在服务端结算。completeUsingItem 由 use-item 状态机驱动，
        // 而那个状态机两端都跑（客户端靠同步包拿到"正在使用"标志）。
        if (this.level().isClientSide) {
            return;
        }
        if (this.eatingSlot >= 0) {
            this.finishBite();
        }
    }

    /**
     * 开始一堆：把背包里一整堆食物与主手物品**对调**，然后进入进食状态。
     *
     * <h2>对调是字面意义上的对调</h2>
     * <pre>
     *   对调前：主手 = 钻石剑        背包第 N 格 = 曲奇 ×32
     *   对调后：主手 = 曲奇 ×32      背包第 N 格 = 钻石剑
     * </pre>
     * 两边的数据都在实体身上真实移动，剑<b>真的在背包里</b>，
     * 玩家打开面板就能看到它。不存在任何"看不见的暂存处"。
     *
     * <h2>为什么是整堆而不是只拿 1 个</h2>
     * 之前是"从堆里抽 1 个放手上"，结果：
     * <ul>
     *   <li>主手只有 1 个，剩下的还在背包，两处分别计数，容易对不上；</li>
     *   <li>剑被存进一个普通字段，玩家看不到，感觉像"被系统吞了"。</li>
     * </ul>
     * 整堆对调之后，"主手上还剩几个"就等于"还能吃几口"，
     * 一个数字说明一切；吃完主手自然空。
     *
     * <h2>四步要么全做完，要么回滚</h2>
     * 任何一步失败（比如 {@code startUsingItem} 没生效）都会立刻
     * {@link #rollbackBite()}，把对调撤销回去。不存在"做了一半"的状态。
     */
    private void startBite() {
        ResidentProfile profile = this.getProfile();
        int slot = HungerSystem.findFoodSlot(this.inventory, profile);
        if (slot < 0) {
            return;
        }
        ItemStack food = this.inventory.getItem(slot);
        if (food.isEmpty()) {
            return;
        }

        // 食物属性必须在**这一刻**取出来并记住。
        //
        // 为什么不能等吃完再取：
        //   setItemSlot 存的是同一个对象引用，startUsingItem 内部
        //   useItem 也是同一个引用。等 completeUsingItem 跑完，
        //   原版已经把那个对象消耗成"空气"，get(DataComponents.FOOD)
        //   只会返回 null —— 这正是"每一口都被判定为没有食物属性、
        //   无限重复吃、饥饿不涨"的根因。
        FoodProperties foodProps = food.get(DataComponents.FOOD);
        if (foodProps == null) {
            // findFoodSlot 已经过滤过，走到这里说明背包在两次读取之间被改了
            return;
        }
        int nutrition = foodProps.nutrition();

        // ---- 对调：整堆食物 ↔ 主手物品 ----
        //
        // 这里必须用 copy() 而不能传引用：
        //   inventory.setItem 和 setItemSlot 内部都会持有传入的对象，
        //   如果两边传同一个对象，之后任何一方 shrink/grow 都会
        //   莫名其妙地改到另一边 —— 那是极难排查的数据串台。
        //   所以每一处写入都显式 copy()，让两个位置各自持有独立对象。
        ItemStack handBefore = this.getItemBySlot(EquipmentSlot.MAINHAND);

        // 1. 主手物品放进食物原来的槽位（剑 → 背包第 slot 格）
        this.inventory.setItem(slot,
                handBefore.isEmpty() ? ItemStack.EMPTY : handBefore.copy());

        // 2. 整堆食物上主手（背包第 slot 格 → 主手）
        ItemStack foodAll = food.copy();
        this.eatingFood = foodAll;
        this.eatingNutrition = nutrition;
        this.eatingSlot = slot;
        this.awaitingSettle = 0;
        this.setItemSlot(EquipmentSlot.MAINHAND, foodAll);

        // 3. 进入 use-item 状态
        this.startUsingItem(InteractionHand.MAIN_HAND);
        if (!this.isUsingItem()) {
            // 没能进入：立刻回滚，绝不留下半截状态
            LOGGER.warn("[BmfTown] {} 无法进入进食状态，已回滚",
                    this.getName().getString());
            this.rollbackBite();
            return;
        }

        LOGGER.debug("[BmfTown] {} 开始吃，槽 {} 的 {}×{} 与主手 {} 对调，营养 {}",
                this.getName().getString(), slot,
                foodAll.getHoverName().getString(), foodAll.getCount(),
                handBefore.isEmpty() ? "空手" : handBefore.getHoverName().getString(),
                nutrition);
    }

    /**
     * 吃完这一口：消耗、涨饥饿，然后决定"这一堆继续"还是"收工"。
     *
     * <p>由 {@link #completeUsingItem()} 调用，即"这一口真的嚼完了"。
     *
     * <h2>为什么营养值不在这里读</h2>
     * {@code completeUsingItem} 被调用时，原版已经把主手那份食物消耗掉了
     * （{@code Item.finishUsingItem} 里 {@code itemstack.shrink(1)}，
     * 而那个栈和 {@link #eatingFood} 是<b>同一个对象</b>）。
     * 此时 {@code eatingFood} 已经是空气，{@code get(DataComponents.FOOD)}
     * 返回 {@code null}——所以营养值必须在 {@link #startBite()} 就存进
     * {@link #eatingNutrition}。
     *
     * <p>之前正是因为在这里才读，导致每一口都判定"没有食物属性"→ 回滚 →
     * 下一口再吃 → 再回滚，形成"一直吃不停、饥饿不涨、但曲奇确实在减少"。
     *
     * <h2>为什么可以不收尾就继续吃</h2>
     * 整堆食物就在主手上，界面上那一格放的是剑。这一口吃完时，
     * 食物和剑都还在各自的位置上——<b>对调状态依然成立</b>。
     * 所以"同一堆继续吃"只要再 {@code startUsingItem} 一次，
     * 完全不用碰背包。只有这堆吃完/吃饱/被打断才需要 {@link #settleBite()}
     * 把剑换回主手。
     *
     * <h2>"继续吃"的判据必须和挑食物时一致</h2>
     * {@link HungerSystem#findFoodSlot} 挑食物用的是
     * {@code canEatWithoutWaste(nutrition)}（吃了不溢出）。这里续吃
     * 也必须过同一道门槛，否则就会"吃到溢出还接着吃"——
     * 那是"吃饱了还多吃一口"的成因。
     *
     * <h2>消耗只归 super 一处管</h2>
     * 本方法<b>不</b>调用 {@code eat()}。见 {@link #completeUsingItem()} 的说明：
     * {@code super.completeUsingItem()} 里已经 {@code shrink(1)} 了，
     * 这里再吃一次就会"一个曲奇掉两个"。
     */
    private void finishBite() {
        ResidentProfile before = this.getProfile();

        // 1. 用 startBite 存好的营养值，不从（已被消耗的）栈上读
        int nutrition = this.eatingNutrition;
        if (nutrition < 0) {
            LOGGER.warn("[BmfTown] {} 这一口没有记下营养值，取消这一口",
                    this.getName().getString());
            this.rollbackBite();
            return;
        }

        // 2. 消耗已经由 super.completeUsingItem() 做过了，这里**不能再吃一次**。
        //
        //    这是"吃一个曲奇掉两个"的根因：
        //      completeUsingItem() 第一行 super.completeUsingItem() 内部
        //      调用了 ItemStack.finishUsingItem，里面就是 shrink(1)。
        //      而我们从外面又调了一次 eat(level, eatingFood) →
        //      同一个栈被减了两次，饥饿却只加一次。
        //
        //    现在的分工：
        //      · 消耗 + 容器返还（碗/瓶的剩余物）→ 交给原版 super
        //      · 涨饥饿                          → 我们自己按 nutrition 加
        //    因为原版那段加饱食度有 instanceof Player 门槛，
        //    非玩家实体它什么都不做，所以饥饿必须我们自己补。
        //
        //    只做回读：以主手当前实际内容为准（super 已经把它改成剩余物）。
        this.eatingFood = this.getItemBySlot(EquipmentSlot.MAINHAND);

        // 3. 涨饱食度。nutrition 与玩家吃同一份食物得到的完全一致。
        this.setProfile(before.withHunger(before.hunger() + nutrition));

        LOGGER.debug("[BmfTown] {} 吃掉一口（+{}），饥饿 {} → {}，主手还剩 {}",
                this.getName().getString(), nutrition,
                before.hunger(), this.getProfile().hunger(),
                this.eatingFood.isEmpty() ? 0 : this.eatingFood.getCount());

        // 4. 决定"这一堆继续吃"还是"收工"
        //
        //    判据必须和 HungerSystem.findFoodSlot 的那道门槛**完全一致**：
        //    下一口吃了不会溢出（hunger + nutrition <= MAX）。
        //
        //    之前这里写的是 hunger < MAX_HUNGER，比上面宽一格。
        //    后果是"吃到溢出"：例如饥饿 19、营养 2 的曲奇，
        //    这一口已经是 21 → 被夹回 20，可 19 < 20 判为 stillHungry，
        //    于是同一堆继续来一口 → 又夹回 20。玩家看到的就是
        //    "明明吃饱了还在吃"，而且背包里的食物比应有的少。
        //    现在有"下一口会不会浪费"这一条卡着，吃饱当口就停。
        ResidentProfile now = this.getProfile();
        boolean stillHungry = now.hunger() < ResidentProfile.MAX_HUNGER;
        boolean hasMore = !this.eatingFood.isEmpty() && this.eatingFood.getCount() > 0;
        boolean nextBiteWanted = hasMore && now.canEatWithoutWaste(nutrition);

        if (nextBiteWanted) {
            // 同一堆继续：主手不动、背包不动，直接再来一口。
            this.awaitingSettle = 0;
            this.startUsingItem(InteractionHand.MAIN_HAND);
            if (this.isUsingItem()) {
                return;
            }
        }

        // 这一堆吃完了（或吃饱了）→ 撤销对调，剑回主手。
        // 若还饿且背包里还有别的食物，冷却结束后 tickEating 会重新挑一堆。
        if (stillHungry) {
            LOGGER.debug("[BmfTown] {} 这一口会让饥饿溢出（{} + {} > {}），停下不再吃",
                    this.getName().getString(), now.hunger(), nutrition,
                    ResidentProfile.MAX_HUNGER);
        }
        this.settleBite();
    }

    /**
     * 被打断时调用：不消耗、不涨饥饿，但要把手上的东西还回去。
     *
     * <p>什么情况会走到这里：进食途中受伤、AI 改了主手、
     * 玩家在面板里动了那格物品。
     */
    private void abortBite() {
        LOGGER.debug("[BmfTown] {} 进食被打断", this.getName().getString());
        // 手上的食物退回背包，主手换回原物
        this.rollbackBite();
    }

    /**
     * 收尾：撤销对调 —— 主手上剩下的放回原格，原格里的东西（剑）回到主手。
     *
     * <h2>为什么这是唯一的收尾实现</h2>
     * 吃完、被打断、启动失败、存档四条路径都走这里。
     * 它们的共同点是：<b>主手和背包槽之间还处于"对调"状态</b>，
     * 必须把这个交换撤销。
     *
     * <h2>撤销对调的两步</h2>
     * <ol>
     *   <li>先把 {@code eatingSlot} 那一格的内容（剑）读到 {@code displaced}；</li>
     *   <li>把主手上剩下的食物（可能是吃剩的几个，也可能是碗/瓶）写回那一格；</li>
     *   <li>把 {@code displaced} 放回主手。</li>
     * </ol>
     *
     * <h2>为什么不直接把两边互换引用</h2>
     * 主手可能因为原版的容器返还逻辑变成了"碗"，也可能因为被打断而
     * 变成了别的东西，甚至被玩家从面板里拿走变成空气。逐项写入并
     * 全程 copy()，是唯一能保证"那格的内容只由这里决定"的写法。
     *
     * <h2>为什么不用 addItem 随便塞</h2>
     * 之前用 {@code inventory.addItem(hand)} 把手上东西丢进背包任意空格，
     * 结果是"剑不见了、背包里多出一堆曲奇"——玩家完全看不出发生过什么。
     * 现在固定放回<b>原来那一格</b>，玩家打开面板就能看到
     * "剑还在原来的位置，曲奇少了几口"。
     */
    private void settleBite() {
        int slot = this.eatingSlot;
        this.eatingSlot = -1;
        this.awaitingSettle = 0;
        this.stopUsingItem();

        ItemStack hand = this.getItemBySlot(EquipmentSlot.MAINHAND);

        if (slot >= 0) {
            // 那一格里是原来的剑（对调时写进去的）
            ItemStack displaced = this.inventory.getItem(slot);
            // 手上剩下的放回原格。可能为空（吃光了），那也照写，
            // 保证"那一格的内容只由这里决定"。
            this.inventory.setItem(slot, hand.isEmpty() ? ItemStack.EMPTY : hand.copy());
            // 剑回主手
            this.setItemSlot(EquipmentSlot.MAINHAND,
                    displaced.isEmpty() ? ItemStack.EMPTY : displaced.copy());
        } else {
            // slot < 0 表示没建立过对调（正常流程不会发生），保守处理：
            // 手上东西塞进背包，塞不下就掉在地上
            if (!hand.isEmpty()) {
                ItemStack over = this.inventory.addItem(hand);
                if (!over.isEmpty()) {
                    this.spawnAtLocation(over);
                }
            }
            this.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }

        this.eatingFood = ItemStack.EMPTY;
        this.eatingNutrition = -1;
        this.eatCooldown = Config.EAT_COOLDOWN.get();
    }

    /**
     * 回滚：等同于收尾。保留这个名字作为语义标记——
     * 调用方写 {@code rollbackBite()} 时意图是"这一口不算数"，
     * 而实际的数据操作和 {@link #settleBite()} 完全一样（撤销对调）。
     */
    private void rollbackBite() {
        this.settleBite();
    }

    // ==========================================================
    //  显示与声音
    // ==========================================================

    /**
     * 是否一直显示名字。
     *
     * <p>原版村民只有被命名牌改过名才显示。镇民必须<b>永远</b>显示 ——
     * 名字是建立情感连接的第一步，玩家看不到名字就不会记住他。
     */
    @Override
    public boolean shouldShowName() {
        return true;
    }

    /** 声音：阶段 1 复用原版村民，保证不报错；以后换自定义音效只改这一处。 */
    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.VILLAGER_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.VILLAGER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.VILLAGER_DEATH;
    }
}
