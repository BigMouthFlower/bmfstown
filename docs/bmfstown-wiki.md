# BMF's Town 开发备忘 · 卷五~卷八（给开发者）

> **版本**：v0.9（Minecraft 1.21.1 / NeoForge 21.1.x）
> **玩家向百科已迁 GitHub Wiki**：<https://github.com/BigMouthFlower/bmfstown/wiki>
> （入门/镇民/心情/喜好/特性/职业/配置/更新日志，MC Wiki 式页面）。
> 本文件只保留**开发者内容**：参数全表、调试与测试手法、机制备忘、路线图 TODO。
> 玩家卷（旧卷一~卷四）已在 v0.9 退役——改玩家可见内容请去 GitHub Wiki。

**导航**：[卷五 参数手册](#卷五--参数手册) · [卷六 调试与测试](#卷六--调试命令与测试手法) · [卷七 机制备忘](#卷七--机制备忘开发者速查) · [卷八 路线图](#卷八--路线图-todo工作决策用)

---

# 卷五 · 参数手册

> 文件：`config/bmfstown-common.toml`。**默认值即行为基准**，括号内为取值范围。
> 段位阈值、效率曲线、UI 布局刻意**不在**此表——见对应卷说明。

## 5.1 residentAttributes.*（基础属性）

| 键 | 默认 | 说明 |
|---|---|---|
| `maxHealth` | 20.0 (1~1024) | 最大生命（半心=1） |
| `movementSpeed` | 0.5 (0.05~4) | 基础移速 |
| `followRange` | 32 (4~256) | 感知范围（格） |
| `strollSpeed` | 0.6 (0.05~4) | 领地内漫步速度（= 睡觉赶路速度；与回家 1.15/跟随 1.3 拉开"闲逛 vs 赶路"） |

## 5.2 hunger.*（饥饿与回血）

| 键 | 默认 | 说明 |
|---|---|---|
| `ticksPerPoint` | 1200 (20~24000) | 每掉 1 点饥饿的 tick 数（1200=每分钟 1 点） |
| `eatCooldownTicks` | 20 (0~600) | 两口之间的最小间隔 |
| `regenThreshold` | 18 (0~20) | 饥饿 ≥ 此值才自然回血 |
| `regenIntervalTicks` | 80 (10~24000) | 回血间隔 |
| `regenAmount` | 1.0 (0.5~20) | 每次回血量 |

## 5.3 growth.*（成长）

| 键 | 默认 | 说明 |
|---|---|---|
| `xpPerLevelBase` | 100 | 旧版等级曲线基数（**不影响**技能段位，段位阈值固定在代码） |
| `initialMood` | 100 (0~100) | 出生心情 |

## 5.4 mood.*（心情，27 项）

**管线与阈值**

| 键 | 默认 | 说明 |
|---|---|---|
| `dailyClamp` | **0 = 不限幅** | 每日结算变动上限（v0.8.1 默认关闭；设为 5 即恢复"每天净变动 ≤±5"的旧行为，挨饿永远绕过） |
| `slowThreshold` | 40 (0~100) | 低于此值效率打折（0=关闭） |
| `slowMultiplierPercent` | 70 (1~100) | 打折档效率乘数（%） |
| `strikeThreshold` | 20 (0~100) | 低于此值罢工（0=关闭） |

**即时事件数值**

| 键 | 默认 | 说明 |
|---|---|---|
| `eatFavored` | 3 | 吃到爱吃的 + |
| `eatFavoredDailyLimit` | 2 (1~100) | 吃喜每天限流 |
| `starving` | 3 | 挨饿基准（2 天=+2、3 天起=+5） |
| `starvingInterval` | 12000 (100~24000) | 挨饿事件间隔（半天） |
| `mobDisliked` | 3 | 讨厌生物出没 − |
| `mobDislikedInterval` | 6000 (200~24000) | 讨厌生物间隔（5 分钟，无日上限） |
| `weatherDisliked` | 3 | 讨厌天气 − |
| `weatherInterval` | 12000 | 天气事件间隔（半天） |

**感知与每日结算数值**

| 键 | 默认 | 说明 |
|---|---|---|
| `jobLiked` | 3 | 干喜欢的活 + |
| `blockLiked` | 3 | 住宅有喜欢的建材 + |
| `colorLiked` | 3 | 住宅有喜欢的颜色 + |
| `animalLiked` | 3 | 喜欢的动物在身边 + |
| `musicLiked` | 3 | 唱片机在放 + |

**性格数值**

| 键 | 默认 | 说明 |
|---|---|---|
| `traitValue` | 3 | 性格类事件统一基础值（攀比/好战/胆小/爱热闹/贪吃） |
| `traitOptimist` | 2 | 乐观每日 + |
| `traitPessimist` | 2 | 悲观每日 − |
| `traitWarlikeNoKillDays` | 5 (1~100) | 好战连续无击杀天数 |
| `traitKillDailyLimit` | 2 (1~100) | 好战击杀想法每天上限 |
| `traitTimidInterval` | 12000 | 胆小受惊间隔（半天） |
| `traitGluttonStarvePercent` | 150 (100~300) | 贪吃挨饿乘数（%） |
| `traitPacifistDailyLimit` | 5 (1~100) | 和平主义"看到死亡"想法每天上限 |
| `traitExplosionDailyLimit` | 1 (1~100) | 爆炸主义"附近爆炸"想法每天上限 |
| `noSleep` | 3 (0~100) | 昨晚没睡的心情减量/夜（逐夜叠加不封顶；战士豁免；0=关闭） |

## 5.5 ai.*（觅食与领地）

| 键 | 默认 | 说明 |
|---|---|---|
| `townCheckIntervalTicks` | 20 (1~200) | 领地逃逸检查频率 |
| `returnHomeSpeed` | 1.15 (0.05~4) | 逃逸后返回领地速度 |
| `forageTakeCount` | 3 (1~64) | 觅食一次拿几个食物 |
| `forageRetryTicks` | 600 (20~24000) | 觅食失败重试间隔 |

## 5.6 sleep.*（作息）

| 键 | 默认 | 说明 |
|---|---|---|
| `startTime` | 12000 (0~23999) | 开始回家睡觉的时刻（日落） |
| `endTime` | 23000 (1~24000) | 起床时刻（日出前） |
| `walkSpeed` | 0.6 (0.05~4) | 回家睡觉走路速度 |
| `bedCacheTicks` | 100 (1~1200) | 住宅区床位列表缓存时长 |
| `travelTimeoutTicks` | 1200 (20~24000) | 回家总超时 |
| `retryTicks` | 600 (20~24000) | 睡觉失败重试间隔 |
| `checkIntervalTicks` | 100 (1~1200) | "该不该睡了"检查频率 |

## 5.7 warrior.*（战士）

| 键 | 默认 | 说明 |
|---|---|---|
| `guardRadius` | 10 (2~64) | 站岗活动半径 |
| `followSpeed` | 1.3 (0.05~4) | 跟随移速（≈玩家疾跑） |
| `guardSpeed` | 1.0 (0.05~4) | 站岗巡逻移速 |
| `teleportDistance` | 14 (4~128) | 跟随掉队传送距离 |
| `attackDamage` | 3.0 (0.5~40) | 战士攻击力（改后需重启） |
| `attackCreepers` | false | 是否索敌苦力怕 |
| `xpPerKill` | 30 (0~1000) | 击杀格斗经验 |
| `attackReach` | 2.5 (1~6) | 出手距离（村民原版仅 1.2） |

## 5.8 perk.*（天赋）

| 键 | 默认 | 说明 |
|---|---|---|
| `activeCooldownSeconds` | 30 (1~600) | 主动技共用冷却（秒） |
| `bulwarkRadius` | 5.0 (1~64) | 坚盾作用半径 |
| `bulwarkDurationSeconds` | 15 (1~600) | 坚盾持续（秒） |
| `huntRadius` | 20.0 (1~128) | 追猎标记半径 |
| `huntDurationSeconds` | 20 (1~600) | 追猎持续（秒） |
| `tauntRadius` | 10.0 (1~64) | 嘲讽半径 |
| `tauntDurationSeconds` | 10 (1~600) | 嘲讽持续（秒） |
| `regenMultiplier` | 1.5 (1~10) | 再生回血倍率 |
| `strongBonusPercent` | 10 (0~100) | 强壮效率加成（百分点） |
| `executeHealthThreshold` | 50.0 (1~1024) | 斩杀目标血量上限 |
| `executeChancePercent` | 25 (0~100) | 斩杀触发概率（%） |

## 5.9 craft.*（工人工坊）

| 键 | 默认 | 说明 |
|---|---|---|
| `ticksPerItem` | 100 (10~24000) | 每件合成基础耗时（tick，÷职业效率 ×精通分支） |
| `xpPerCraft` | 5 (0~1000) | 每件合成涨的合成经验 |
| `efficiencyPercent` | 10 (0~90) | 布线保留位（现由天赋分支固定 20%） |
| `openSourcePercent` | 10 (0~100) | 开源分支：每件产出 +1 概率（%） |
| `thriftPercent` | 10 (0~100) | 节流分支：整次免料概率（%） |
| `workStartTick` | 2000 (0~23999) | 开工时刻（起床后留空闲） |
| `workEndTick` | 10000 (1~24000) | 收工时刻（睡前留空闲） |

---

# 卷六 · 调试命令与测试手法

## 6.1 调试命令 ✅（均需作弊权限）

| 命令 | 作用 |
|---|---|
| `/bmfstown hunger <目标> <值>` | 设置饥饿值（0~20） |
| `/bmfstown mood <目标> <值>` | 设置心情值（0~100） |
| `/bmfstown skillxp <目标> <值>` | 设置技能经验（0~7500，验段位/效率/perk） |
| `/bmfstown reroll <目标>` | 重摇全部随机偏好（含职业好恶与口味；不动饥饿/心情/技能） |

目标支持全部原版选择器。**最近的一个镇民**：
`/bmfstown reroll @e[type=bmfstown:resident,limit=1,sort=nearest]`

## 6.2 标准测试手法

- **跳天**：`/time add 24000`（每日结算必触发一次；相位随机但跳天按调整日计数，跳一天必换一天）
- **天气**：`/weather clear|rain|thunder`；注意天气事件双限流（半天 1 次 + 变了才触发），连测用 `/time add 12000` 清间隔
- **面板同步**：想法/偏好/心情只在开面板瞬间下发——动作后**重开面板**

---

# 卷七 · 机制备忘（开发者速查）

> 给"时间久了记不住自己怎么写的"自己的。逐条对应代码位置。

- **配方反查**：工人配方绝不手写——白名单只圈产物域，材料/产量从 `RecipeManager` 真实配方实时反查（v0.8.1 第一版硬编码配方被批"凭记忆猜不对"后定下的铁律）；
- **containerFx 原语**：开箱开关盖动画+音效与觅食共用同一套 containerFx 原语——新增"对容器做动作"的表现直接复用，别另写；
- **"一天"** = `dayTime/24000`（`MoodState.currentDay`）；`settleOffset` 落 NBT，与 `lastSettledDay` 成对读写，绝不能读档重随；
- **long 间隔闸门**：`last == Long.MIN_VALUE || now - last >= interval`（防哨兵值减法溢出）；
- **挑食进食**（`HungerSystem.findFoodSlot`）：三轮扫描——能吃的爱吃的 → 存在性检查（有爱吃的就拒普通）→ 普通里挑营养最低；"不浪费"门槛 = `canEatWithoutWaste`；`allowWaste`（回血优先）无视一切；续吃判据在 `Resident.finishBite`，必须与门槛一致；
- **偏好拿取**（`Resident.takeFromContainer`）：同结构两轮扫描，先爱吃后普通；
- **住宅区材质扫描**（`HomeMaterialScanManager`）：每镇民每天一个 Job，每 tick 全服 4096 格摊派、命中即停、跨天弃扫、gameTime 去重；由 `Resident.customServerAiStep` 驱动；扫完回调 `MoodState.applyHomeMaterialResult`；方块注册名用 IdentityHashMap 缓存；
- **homePos**：镇民躺下时登记（`lieDown` → `withHomePos(bed)`），换床自动更新；
- **敌对生物检测**：一律 `getEntitiesOfClass(Mob.class) + instanceof Enemy`——`Monster` 漏史莱姆/岩浆怪（v0.8.1 教训）；`getEntitiesOfClass` 只收类不收接口；
- **菜单按钮状态**走 `SynchedEntityData`，不走 ContainerData（同步不可靠）；瞬态可视化（嘲讽红罩）走"S→C 广播 UUID+截止 gameTime，客户端按本地时间轴过期"模式；
- **索敌节流**：Goal 自持计数器，禁 `tickCount % N`（原版按 (tick+实体ID)%2 单双号调度）；
- **网关编码**：1.21.1 没有 `ByteBufCodecs.list(codec)`；裸 ByteBuf 没有 readUtf/readVarint；
- **语言文件**：含 `%` 必须 `%%` 转义；`RandomSource` 在 `net.minecraft.util`；
- **编译排错**：javac 错误全量人工过目，别 grep 过滤（会漏 illegal forward reference 等）；噪音只有 cannot find symbol / package does not exist / does not override / no suitable method 四种级联；
- **DEBUG 日志**：常态路径别放（dev 全打、玩家看不见）；排障信息要么删要么升 WARN 带坐标。

---

# 卷八 · 路线图 TODO（工作决策用）

> 按优先级分层；"⏸ 待拍板"= 动工前需要先定参数。权威实现进度另见《需求分析说明书》§12。

## 8.1 近期 · 特性第二批（词条池已放出，行为待填）

| 项 | 说明 | 前置 |
|---|---|---|
| 漫游癖 | 出城 +20% 战力 +心情（一天一次）、进城减战力 | ⏸ 待拍板：心情数值（倾向 +3） |
| 自毁倾向 | 心情 <20 随机 4 事件（爆炸/中毒/窒息/跳楼）≤3 次/天 | ⏸ 待拍板：爆炸半径（倾向 1.5~2 格不毁方块） |
| 蛋蛋人 | 蛋喜好 + 家内容器方块判定每日 +3 | ⏸ 待拍板：蛋的判定范围（倾向鸡蛋/海龟蛋/嗅探兽蛋/龙蛋全算） |
| Dylar实验对象 | 7 天白板循环 + 快照还原 | 无 |
| 舍己为人 | 战斗中随机自动嘲讽（复用战士嘲讽 perk） | 依赖超情绪（可先做基础嘲讽，超上限留坑） |

## 8.2 中期 · 系统（多个特性挂在这些系统上）

| 系统 | 说明 | 解锁 |
|---|---|---|
| **超情绪** | 心情可超 100 上限的"高情绪"状态；舍己为人/口是心非/大智若愚都挂它 | 解锁 3 条特性完整行为 |
| **记忆系统** | 事件→记忆→情绪影响随时间衰减；传奇模式（存档年史/大事记） | 心情系统深化 |
| **NPC 社交** | 镇民间传播恐慌/知识（传授=被传授人加经验）；酒吧闲话/图书馆写书（矮人要塞式） | 依赖记忆 |
| **镇民来源** | 流浪者迁徒入城（文字提示"从 XX 流浪至此"）、猪灵族（隐藏职业/perk 与种族绑定）、野外营地 | 中期 |
| **神器 / 出版物** | 装备/书籍的作者署名（第二作者特性）、坟墓神器 | 远期坑（用户多次点名"以后我跟你说"） |
| **魔法研究院** | 研究熟练度、魔咒进度、黑魔法与禁忌行为 | 远期大方向 |
| **狗骑兵** | 骑狗 | ⏸ 待拍板实现方式 |

## 8.3 旧账（说明书 §12.2 遗留，原"尾卷A·计划中"并入此处）

- ~~v0.8 本地实测清单（33 项）~~ ✅ v0.8.1 起多轮实测覆盖（工人/簿记员/特性/重命名等均过实测），测试方案文档已退役
- ~~手册成员列表 UI~~ ✅ v0.8.1（见 2.7：居民列表/排序/点击开面板/免距离/回跳/传送按钮）
- 领地权限控制（非成员交互限制；顺手收窄坚盾友军为同城镇成员）
- 第二/三职业（矿工/厨师，回抽行为原语层；见卷三 3.3）
- 自语台词库（镇民头顶气泡的文案库；工人工作气泡已上线，属气泡系统的一部分）
- 魔杖等占位贴图替换
