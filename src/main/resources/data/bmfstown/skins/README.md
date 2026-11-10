# 皮肤清单目录（data/bmfstown/skins/）

皮肤系统（v0.15）的清单登记处。**mod 本体不内置任何皮肤**——皮肤 PNG 等用户收集后
放这里登记（内容清单纪律：机制就绪、内容留空）。

## 加皮肤流程（两件套）

1. 贴图 PNG 放 `assets/bmfstown/textures/entity/resident_skins/<包名>/`（客户端资源）；
2. 本目录建一个 `<包名>.json` 清单（服务端数据包），格式如下。

## 清单格式

```json
{
  "pack": "townsfolk",
  "skins": [
    {
      "id": "townsfolk_01",
      "texture": "bmfstown:textures/entity/resident_skins/townsfolk/townsfolk_01.png",
      "slim": false,
      "author": "来源或作者（CC-BY 必填，进致谢页）",
      "tags": []
    }
  ]
}
```

- 全局唯一 id = `pack + "/" + 皮肤id`（如 `townsfolk/townsfolk_01`）；
- `texture` 必须是**完整 ResourceLocation**（含命名空间）；
- `slim`：true = 纤细模型（3px 臂），false = 经典（4px 臂）。PNG 本身不带这个
  标记，收集时人工判定（看上传站标注或皮肤编辑器预览），填错只影响该镇民手臂
  贴图，改清单即可修；
- `tags`：预留（职业换装/节日换装），v1 不消费；
- 坏条目（缺字段/贴图路径非法/重复 id）加载时记 warn 跳过，不影响其它皮肤。

## 生效方式

数据包重载时重建目录（服务端启动 / `/reload`），客户端在登录与数据包同步时
收到推送。改清单后 `/reload` 即可，不用重开存档。

## 授权纪律（详见 .ai/designs/skin_system.md §7.0）

A 级（明确授权）打底；B 级（Mojang/4J 自创无联动包）用户拍板可用，逐张肉眼复核；
一切联动 IP / 品牌联名 / 主播形象不碰。每张皮肤记 `author` 来源。
