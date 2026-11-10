# 皮肤贴图目录（assets/bmfstown/textures/entity/resident_skins/）

皮肤系统（v0.15）的贴图存放处。**mod 本体不内置任何皮肤**——PNG 等用户收集后
按 `<包名>/<皮肤id>.png` 放这里，并在 `data/bmfstown/skins/<包名>.json` 清单登记
（格式见清单目录 README）。

## 硬性要求

- **64×64** 标准 HumanoidModel UV 布局（64×32 旧版不支持；村民 UV 是另一套，不能套）；
- classic/slim 都收，但必须在清单里正确标注 `slim`（PNG 本身不带该标记）；
- 目录查不到的皮肤回退原版僵尸贴图（占位语义）。
