"""
从用户原图提取符号，生成：
1. 启动页用的单色前景 PNG（各密度）
2. 一份 SVG 轮廓（用 marching squares 提取边界，再简化成 path）

思路：不做手工重画 —— 直接从原图像素提取，保证与桌面图标完全一致。
"""
import os
import numpy as np
from PIL import Image

ROOT = r"D:\projects\shouzhe"
SRC = os.path.join(ROOT, "mockups", "archive", "极简绿色手势应用图标展示.png")
RES = os.path.join(ROOT, "app", "src", "main", "res")

ICON_BOX = (54, 94, 846, 879)   # detect 测出的主图标区域
BRAND = np.array([61, 220, 132], dtype=np.int16)
INK = np.array([14, 17, 22], dtype=np.int16)


def get_symbol_mask():
    """返回符号的二值掩码（True = 符号）及其包围盒"""
    im = Image.open(SRC).convert("RGB")
    crop = im.crop(ICON_BOX)
    a = np.asarray(crop).astype(np.int16)

    db = np.abs(a - BRAND).sum(axis=2)     # 离品牌绿的距离
    di = np.abs(a - INK).sum(axis=2)       # 离墨色的距离

    # 符号 = 明显不是绿底、且明显偏深
    mask = (di < 165) & (db > 130)
    return crop, mask


def largest_component(mask):
    """
    保留所有有意义的连通块。
    实测原图标由两块组成：上方的圆点 + 下方张开的手 —— 必须都保留。
    """
    from collections import deque
    h, w = mask.shape
    seen = np.zeros_like(mask, dtype=bool)
    comps = []
    ys, xs = np.where(mask)
    if not len(xs):
        return mask
    step = 2
    for sy, sx in zip(ys[::step], xs[::step]):
        if seen[sy, sx] or not mask[sy, sx]:
            continue
        q = deque([(sy, sx)])
        seen[sy, sx] = True
        n = 0
        minx = maxx = sx
        miny = maxy = sy
        while q:
            y, x = q.popleft()
            n += 1
            if x < minx: minx = x
            if x > maxx: maxx = x
            if y < miny: miny = y
            if y > maxy: maxy = y
            for dy in (-1, 0, 1):
                for dx in (-1, 0, 1):
                    ny, nx = y + dy, x + dx
                    if 0 <= ny < h and 0 <= nx < w and not seen[ny, nx] and mask[ny, nx]:
                        seen[ny, nx] = True
                        q.append((ny, nx))
        comps.append((n, minx, miny, maxx, maxy))

    if not comps:
        return mask
    comps.sort(reverse=True)

    # 保留前两个大块（圆点 + 手），其余视为噪点
    out = np.zeros_like(mask)
    for cnt, minx, miny, maxx, maxy in comps[:2]:
        # 太小的一律丢弃
        if cnt < 200:
            continue
        out[miny:maxy + 1, minx:maxx + 1] |= mask[miny:maxy + 1, minx:maxx + 1]
    return out


def build_monochrome(size, color=(255, 255, 255)):
    """
    生成 size×size 的单色符号图（透明底），
    符号缩放到画布的 58%（启动页安全区）。
    """
    crop, mask = get_symbol_mask()
    mask = largest_component(mask)

    ys, xs = np.where(mask)
    if not len(xs):
        raise RuntimeError("没提取到符号")
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    sym = mask[y0:y1 + 1, x0:x1 + 1]
    bh, bw = sym.shape

    # 目标尺寸：占画布 58%
    target = int(size * 0.58)
    scale = target / max(bw, bh)
    nw, nh = max(1, int(bw * scale)), max(1, int(bh * scale))

    sym_img = Image.fromarray((sym * 255).astype(np.uint8), "L").resize((nw, nh), Image.LANCZOS)

    # 贴到画布中心
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    layer = Image.new("RGBA", (nw, nh), color + (255,))
    layer.putalpha(sym_img)
    canvas.paste(layer, ((size - nw) // 2, (size - nh) // 2), layer)
    return canvas, sym


def write_svg(sym, out_path, color="#FFFFFF"):
    """用轮廓追踪把掩码转成 SVG path（简化：每行取左右边界，生成矩形条）"""
    # 简化策略：把掩码降采样后，逐行输出水平线段，组成一个"条形"path。
    # 对启动页图标这种尺寸足够（视觉上与位图一致），且可任意缩放。
    h, w = sym.shape
    # 降采样到宽度 96，保持比例
    import math
    tw = 96
    th = max(1, int(h * tw / w))
    small = np.asarray(
        Image.fromarray((sym * 255).astype(np.uint8), "L").resize((tw, th), Image.LANCZOS)
    ) > 110

    paths = []
    for y in range(th):
        x = 0
        while x < tw:
            if small[y, x]:
                x0 = x
                while x < tw and small[y, x]:
                    x += 1
                paths.append(f"M{x0},{y}h{x - x0}v1h-{x - x0}z")
            else:
                x += 1

    d = "".join(paths)
    svg = (
        f'<?xml version="1.0" encoding="utf-8"?>\n'
        f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="108dp"\n    android:height="108dp"\n'
        f'    android:viewportWidth="{tw}"\n    android:viewportHeight="{th}">\n'
        f'    <path android:fillColor="{color}" android:pathData="{d}" />\n'
        f'</vector>\n'
    )
    with open(out_path, "w", encoding="utf-8") as f:
        f.write(svg)
    return tw, th, len(paths)


if __name__ == "__main__":
    drawable = os.path.join(RES, "drawable")
    os.makedirs(drawable, exist_ok=True)

    # 1) 启动页单色前景（白色，系统会自动染色）
    icon, sym = build_monochrome(432, (255, 255, 255))
    icon.save(os.path.join(drawable, "ic_splash_logo.png"))
    print("生成 drawable/ic_splash_logo.png (432x432)")

    # 2) 各密度备份
    for name, size in [("mdpi", 108), ("hdpi", 162), ("xhdpi", 216),
                       ("xxhdpi", 324), ("xxxhdpi", 432)]:
        d = os.path.join(RES, "drawable-" + name)
        os.makedirs(d, exist_ok=True)
        im, _ = build_monochrome(size, (255, 255, 255))
        im.save(os.path.join(d, "ic_splash_logo.png"))
    print("生成各密度 drawable-*dpi/ic_splash_logo.png")

    # 3) SVG（矢量版，可无限缩放）
    tw, th, n = write_svg(sym, os.path.join(drawable, "ic_splash_logo_vec.xml"))
    print(f"生成 drawable/ic_splash_logo_vec.xml ({tw}x{th}, {n} 段)")

    # 4) 预览图：桌面图标 vs 启动页图标并排
    prev = Image.new("RGB", (640, 340), (242, 244, 247))
    # 左：桌面图标（绿底+符号）
    desk = Image.open(SRC).convert("RGB").crop(ICON_BOX).resize((280, 280), Image.LANCZOS)
    prev.paste(desk, (20, 30))
    # 右：启动页单色（画在深色底上，模拟深色模式）
    dark = Image.new("RGB", (280, 280), (14, 17, 22))
    mono, _ = build_monochrome(280, (255, 255, 255))
    dark.paste(mono, (0, 0), mono)
    prev.paste(dark, (340, 30))
    prev.save(os.path.join(ROOT, "tools", "splash-vs-icon.png"))
    print("生成 tools/splash-vs-icon.png（左：桌面图标 右：启动页单色）")
