# -*- coding: utf-8 -*-
"""按项目实际使用的图标子集化 TDesign 图标字体。
流程：解析 icon.wxss 建立 name->codepoint；扫描全部 wxml/js 收集用到的 name
（含 wxml 三元表达式里的字符串字面量与 js 的 icon/activeIcon 键值）；
pyftsubset 子集化后校验 cmap 覆盖，任何遗漏则退出码 1（保留完整字体不上线）。
"""
import base64, re, sys, subprocess, io
from pathlib import Path

ROOT = Path(__file__).parent.parent / "miniprogram"
WXSS = ROOT / "components/tdesign-miniprogram/icon/icon.wxss"
APP_SCSS = ROOT / "app.scss"
SUBSET_OUT = Path(__file__).parent / "subset.woff"

wxss = WXSS.read_text(encoding="utf-8")
name2cp = {}
# 注意：content 值里的反斜杠用 "." 消耗（经工具链写入的 "\\" 字面量在正则中行为不稳定），
# 形如 .t-icon-home:before{content:'\E3FE';}
for m in re.finditer(r"\.t-icon-([A-Za-z0-9_-]+):before\{content:'.([0-9A-Fa-f]+)';\}", wxss):
    name2cp[m.group(1)] = int(m.group(2), 16)
print(f"icon.wxss 字形总数: {len(name2cp)}")

def expr_names(expr):
    """从 wxml 属性表达式取候选图标名：先剥掉 ===/!== 比较字面量，再收剩余单引号串。"""
    expr = re.sub(r"[=!]==?\s*'[^']*'", "", expr)
    return set(re.findall(r"'([^']+)'", expr))

used = set()
# 1) wxml：t-icon 的 name 属性（含 {{ }} 三元里的字符串字面量）+ 自定义组件 icon 属性
for wxml in ROOT.rglob("*.wxml"):
    if "tdesign-miniprogram" in str(wxml):
        continue
    text = wxml.read_text(encoding="utf-8")
    for m in re.finditer(r"<t-icon\b[^>]*?\bname=\"([^\"]+)\"", text):
        expr = m.group(1)
        inner = re.findall(r"\{\{(.*)\}\}", expr, re.S)
        if inner:
            used.update(expr_names(inner[0]))
        elif expr.strip():
            used.add(expr.strip())
    for m in re.finditer(r"\bicon=\"([^\"]+)\"", text):
        expr = m.group(1)
        inner = re.findall(r"\{\{(.*)\}\}", expr, re.S)
        if inner:
            used.update(expr_names(inner[0]))
        elif expr.strip():
            used.add(expr.strip())
# 2) js：icon / activeIcon / inactiveIcon 键的字符串值（wx.showToast 的 icon:'none' 等非
#    字体用法按「不在字形表」自动过滤，不进入子集）
js_extra = set()
for js in ROOT.rglob("*.js"):
    if "tdesign-miniprogram" in str(js):
        continue
    text = js.read_text(encoding="utf-8")
    for m in re.finditer(r"\b(?:icon|activeIcon|inactiveIcon)\s*:\s*['\"]([^'\"]+)['\"]", text):
        js_extra.add(m.group(1))
used |= {u for u in js_extra if u in name2cp}
print(f"js 侧候选忽略（非字体图标）: {sorted(js_extra - set(name2cp))}")

used = {u for u in used if u and not u.startswith("{{")}
print(f"项目用到的图标名 {len(used)} 个: {sorted(used)}")

missing = sorted(u for u in used if u not in name2cp)
if missing:
    print("!! 以下名称在 icon.wxss 无映射（保留完整字体）:", missing)
    sys.exit(1)

unicodes = sorted({name2cp[u] for u in used})
hexlist = ",".join(f"U+{c:04X}" for c in unicodes)
print(f"子集 codepoint {len(unicodes)} 个")

# 从 app.scss 提取 base64 woff
scss = APP_SCSS.read_text(encoding="utf-8")
font_b64 = re.search(r"url\('data:font/woff;base64,([^']+)'\)", scss).group(1)
woff = base64.b64decode(font_b64)
tmp_font = Path(__file__).parent / "full.woff"
tmp_font.write_bytes(woff)
print(f"完整字体: {len(woff)/1024:.0f} KB")

subprocess.run([
    sys.executable, "-m", "fontTools.subset", str(tmp_font),
    f"--unicodes={hexlist}", "--flavor=woff", f"--output-file={SUBSET_OUT}",
    "--no-layout-closure",
], check=True)
subset = SUBSET_OUT.read_bytes()
print(f"子集字体: {len(subset)/1024:.0f} KB")

# 校验子集 cmap 覆盖所有所需 codepoint
from fontTools.ttLib import TTFont
font = TTFont(io.BytesIO(subset))
cmap = font.getBestCmap()
lost = [f"U+{c:04X}" for c in unicodes if c not in cmap]
if lost:
    print("!! 子集丢失字形:", lost)
    sys.exit(1)
print("cmap 覆盖校验通过")

# 用子集字体替换 app.scss 内嵌 base64，并写清单供回归测试校验
new_b64 = base64.b64encode(subset).decode("ascii")
new_scss = re.sub(r"url\('data:font/woff;base64,[^']+'\)",
                  "url('data:font/woff;base64," + new_b64 + "')", scss, count=1)
APP_SCSS.write_text(new_scss, encoding="utf-8")
manifest = {
    "_comment": "图标字体子集清单（scripts/subset-icon-font.py 生成）。新增 t-icon 图标名后必须重跑该脚本，tests/icon-subset.test.js 会拦截遗漏。",
    "subsetUsedNames": sorted(used),
    "nameToCodepoint": {k: name2cp[k] for k in sorted(used)},
}
(Path(__file__).parent / "icon-subset-manifest.json").write_text(
    __import__("json").dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"app.scss 已更新（字体 {len(woff)/1024:.0f}KB → {len(subset)/1024:.0f}KB），清单已写入 scripts/icon-subset-manifest.json")
tmp_font.unlink(missing_ok=True)
SUBSET_OUT.unlink(missing_ok=True)
