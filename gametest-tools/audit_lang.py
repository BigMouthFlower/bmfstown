"""lang 审计：键名事故回归防线（不进引擎，纯静态扫描）。

检查三项：
1. 每种秘传药水 × 三种瓶前缀（potion/splash_potion/lingering_potion）必须有键
   ——覆盖 2026-11-07 的"喷溅/滞留露键名"事故；
2. 所有值里不得出现"裸 %"（% 后面不是 s/d/数字/% 的都算）——覆盖
   "Format error"事故（perk 描述里的 30%）；
3. 同步检查 zh_cn 与 en_us 键集合一致（防单语漏配）。

用法：python audit_lang.py   （在项目根目录跑）
"""
import io
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LANG_DIR = ROOT / "src/main/resources/assets/bmfstown/lang"
MODS_JAVA = ROOT / "src/main/java/com/bmf/bmfstown/alchemy/ModPotions.java"

PREFIXES = ["item.minecraft.potion.effect.", "item.minecraft.splash_potion.effect.",
            "item.minecraft.lingering_potion.effect."]
SECRET_RE = re.compile(r'POTIONS\.register\("([^"]+)"')
BARE_PERCENT_RE = re.compile(r"%(?!s|d|[0-9]|%)")
# %1$s 这类带位置参数的形式也合法
POSITIONAL_RE = re.compile(r"%\d+\$[sd]")


def load_secrets():
    text = io.open(MODS_JAVA, encoding="utf-8").read()
    ids = SECRET_RE.findall(text)
    if not ids:
        print("FATAL: 从 ModPotions.java 没扫到任何注册 id")
        sys.exit(2)
    return ids


def main():
    secrets = load_secrets()
    problems = []

    langs = {}
    for lang in ("zh_cn", "en_us"):
        path = LANG_DIR / f"{lang}.json"
        langs[lang] = json.load(io.open(path, encoding="utf-8"))

    # ① 秘传药水 × 3 前缀
    for lang, data in langs.items():
        for secret in secrets:
            for prefix in PREFIXES:
                key = prefix + secret
                if key not in data:
                    problems.append(f"[{lang}] 缺键: {key}")

    # ② 裸 %：先移除合法占位（%s/%d/%N$s/%% 转义），剩下的任何 % 都是事故
    for lang, data in langs.items():
        for key, value in data.items():
            if not isinstance(value, str):
                continue
            cleaned = POSITIONAL_RE.sub("", value)
            cleaned = cleaned.replace("%s", "").replace("%d", "").replace("%%", "")
            if "%" in cleaned:
                problems.append(f"[{lang}] 裸 %（会触发 Format error）: {key} = {value!r}")

    # ③ 双语键集一致
    zh, en = set(langs["zh_cn"]), set(langs["en_us"])
    for missing in zh - en:
        problems.append(f"[en_us] 缺 zh_cn 的键: {missing}")
    for missing in en - zh:
        problems.append(f"[zh_cn] 缺 en_us 的键: {missing}")

    if problems:
        print(f"发现 {len(problems)} 个问题：")
        for p in problems:
            print(" -", p)
        sys.exit(1)
    print(f"审计通过：{len(secrets)} 种秘传药水 × 3 前缀齐全，无裸 %，双语键集一致"
          f"（zh_cn {len(langs['zh_cn'])} 键 / en_us {len(langs['en_us'])} 键）")


if __name__ == "__main__":
    main()
