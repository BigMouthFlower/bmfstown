# BMF's Town —— BMF的城镇

以《镇民》需求分析说明书为蓝本的 NeoForge 1.21.1 mod：
让 NPC 镇民吃掉你的过剩食物，并替你完成那些重复劳动。

- **mod id**: `bmfstown`
- **包名**: `com.bmf.bmfstown`
- **MC**: 1.21.1 / **NeoForge**: 21.1.x

## 构建与运行

```powershell
# Windows（JAVA_HOME 若指向 JDK8，需要先改）
$env:JAVA_HOME = "D:\Java\java21"
.\gradlew runClient     # 开发环境启动游戏
.\gradlew build         # 打包，产物在 build/libs/bmfstown-0.1.0.jar
```

## 当前状态（M0 骨架）

- [x] 项目骨架（注册器、创造标签页、双语语言文件）
- [x] 占位物品 `bmfstown:town_charter`（城镇凭证）
- [ ] 第一个 NPC 实体（EntityType + 占位模型）
- [ ] 饥饿值 + 动态食物识别
- [ ] 行为原语层

## 目录约定

```
src/main/java/com/bmf/bmfstown/
├── BmfTown.java          ← 主入口：只负责挂注册器，不放具体内容
├── BmfTownClient.java    ← 客户端专用（渲染、屏幕）
├── ModItems.java         ← 物品
├── ModCreativeTabs.java  ← 创造标签页
├── (将来) ModEntities.java      ← 实体
├── (将来) ModDataComponents.java ← 数据组件（饥饿值等存这里）
└── (将来) entity/               ← NPC 逻辑
src/main/resources/assets/bmfstown/
├── lang/                 ← en_us.json + zh_cn.json（加内容必须同步三处）
├── models/               ← 模型 JSON
└── textures/             ← 16x16 贴图
```

## 加新内容的固定套路

以物品为例，四处同步，缺一处就是紫黑方块或原始 key：

1. `ModItems.java` 注册
2. `assets/bmfstown/models/item/xxx.json` 模型
3. `assets/bmfstown/textures/item/xxx.png` 贴图
4. `lang/en_us.json` + `lang/zh_cn.json` 名称
