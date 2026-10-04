"""
从「极简绿色手势应用图标展示.png」提取主图标，生成 Android 自适应图标资源。

输入：1536x1024 的展示图（左侧 1024 主图 + 右侧 192/48 预览 + 深色桌面预览）
输出：
  - ic_launcher_foreground.png   （前景层：绿底 + 深墨符号，含安全边距）
  - ic_launcher_background.xml   （纯色底）
  - ic_launcher.xml              （自适应图标定义）
  - 各密度 mipmap PNG兜底（mdpi..xxxhdpi）
  - 圆形裁切验证图
"""
import os
import numpy as np
from PIL import Image, ImageDraw

ROOT = r"D:\projects\shouzhe"
RES = os.path.join(ROOT, "app", "src", "main", "res")
SRC = os.path.join(ROOT, "极简绿色手势应用图标展示.png")

BRAND = (61, 220, 132)   # #3DDC84
INK = (14, 17, 22)       # #0E1116

# 主图标区域（detect.py 测出）
BOX = (54, 94, 846, 879)


def ensure(p):
    os.makedirs(p, exist_ok=True)
    return p


def make_mask_and_layers(crop):
    """把主图标切成前景（品牌绿底 + 深墨符号），并输出前景层的透明版"""
    a = np.asarray(crop).astype(np.int16)
    brand = np.array(BRAND, dtype=np.int16)
    ink = np.array(INK, dtype=np.int16)
    db = np.abs(a - brand).sum(axis=2)
    di = np.abs(a - ink).sum(axis=2)
    bg_mask = db < 150# 接近品牌绿
    fg_mask = di < 150           # 接近深墨（符号）
    return bg_mask, fg_mask


def build_icon(img, size, safe_ratio=0.62):
    """
    生成 size×size 的图标：品牌绿底 + 居中的符号，四周留出 safe_ratio 的安全边距。
    安卓自适应图标会裁切到中央约 66%，所以主体必须缩在 66% 内。
    """
    out = Image.new("RGBA", (size, size), BRAND + (255,))
    # 符号层：透明底
    sym = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(sym)

    # 原图符号包围盒
    a = np.asarray(img.convert("RGBA"))
    ink = np.array(INK, dtype=np.int16)
    di = np.abs(a[:, :, :3].astype(np.int16) - ink).sum(axis=2)
    ys, xs = np.where(di < 150)
    if len(xs) == 0:
        return out
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    sw, sh = x1 - x0, y1 - y0

    # 目标：符号缩放到中央 safe_ratio 宽度内
    target = size * safe_ratio
    scale = target / max(sw, sh)
    nw, nh = max(1, int(sw * scale)), max(1, int(sh * scale))

    sub = img.crop((x0, y0, x1 + 1, y1 + 1)).resize((nw, nh), Image.LANCZOS)
    # 把非符号区域透明化
    sa = np.asarray(sub.convert("RGBA")).copy()
    si = np.abs(sa[:, :, :3].astype(np.int16) - ink).sum(axis=2)
    sa[:, :, 3] = np.where(si < 150, 255, 0)
    sub = Image.fromarray(sa, "RGBA")

    ox = (size - nw) // 2
    oy = (size - nh) // 2
    sym.paste(sub, (ox, oy), sub)
    out = Image.alpha_composite(out, sym)
    return out


def main():
    img = Image.open(SRC).convert("RGBA")
    W, H = img.size
    crop = img.crop(BOX)
    side = max(crop.size)
    crop = crop.resize((side, side), Image.LANCZOS)

    mipmap = ensure(os.path.join(RES, "mipmap"))
    drawable = ensure(os.path.join(RES, "drawable"))
    anydpi = ensure(os.path.join(RES, "mipmap-anydpi-v26"))

    # 1) 自适应图标前景层（432x432，含大量安全边距）
    fg = build_icon(crop, 432, safe_ratio=0.58)
    fg.save(os.path.join(mipmap, "ic_launcher_foreground.png"))
    print("生成 ic_launcher_foreground.png (432x432)")

    # 2) 各密度兜底 PNG
    for name, size in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                       ("xxhdpi", 144), ("xxxhdpi", 192)]:
        d = ensure(os.path.join(RES, mipmap_name(name)))
        ic = build_icon(crop, size, safe_ratio=0.70)
        ic.save(os.path.join(d, "ic_launcher.png"))
        ic.save(os.path.join(d, "ic_launcher_round.png"))
        print(f"生成 mipmap-{name}/ic_launcher.png ({size}x{size})")

    # 3) 纯色背景
    with open(os.path.join(drawable, "ic_launcher_background.xml"), "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n')
        f.write('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n')
        f.write('    android:width="108dp"\n    android:height="108dp"\n')
        f.write('    android:viewportWidth="108"\n    android:viewportHeight="108">\n')
        f.write('    <path android:fillColor="#3DDC84" android:pathData="M0,0h108v108h-108z"/>\n')
        f.write('</vector>\n')
    print("生成 ic_launcher_background.xml")

    # 4) 自适应图标定义
    with open(os.path.join(anydpi, "ic_launcher.xml"), "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n')
        f.write('<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n')
        f.write('    <background android:drawable="@drawable/ic_launcher_background"/>\n')
        f.write('    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n')
        f.write('    <monochrome android:drawable="@mipmap/ic_launcher_foreground"/>\n')
        f.write('</adaptive-icon>\n')
    print("生成 mipmap-anydpi-v26/ic_launcher.xml")

    # 5) 圆形裁切验证图
    size = 432
    circle = build_icon(crop, size, safe_ratio=0.58)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    circle.putalpha(mask)
    circle.save(os.path.join(ROOT, "tools", "icon-round-check.png"))
    print("生成 tools/icon-round-check.png（圆形裁切验证）")

    # 6) 各尺寸并排预览
    prev = Image.new("RGB", (760, 200), (245, 246, 248))
    x = 20
    for s in (48, 72, 96, 144, 192):
        ic = build_icon(crop, s, safe_ratio=0.70)
        prev.paste(ic, (x, 100 - s // 2), ic)
        x += s + 28
    # 深色背景预览
    dark = Image.new("RGB", (220, 200), (14, 17, 22))
    ic = build_icon(crop, 150, safe_ratio=0.70)
    dark.paste(ic, (35, 25), ic)
    prev.paste(dark, (540, 0))
    prev.save(os.path.join(ROOT, "tools", "icon-preview.png"))
    print("生成 tools/icon-preview.png（多尺寸+深色预览）")


def mipmap_name(d):
    return "mipmap-" + d


if __name__ == "__main__":
    main()