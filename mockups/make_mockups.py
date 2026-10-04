"""生成「收着」UI 原型图（PNG）。用 Pillow 直绘，无需浏览器渲染。"""
import os
import sys
from PIL import Image, ImageDraw, ImageFont

OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))
os.makedirs(OUT, exist_ok=True)

# ---------- 字体 ----------
FONT_CANDIDATES = [
    r"C:\Windows\Fonts\msyh.ttc",      # 微软雅黑
    r"C:\Windows\Fonts\msyhbd.ttc",
    r"C:\Windows\Fonts\simhei.ttf",
    r"C:\Windows\Fonts\simsun.ttc",
]

def font(size, bold=False):
    picks = [r"C:\Windows\Fonts\msyhbd.ttc", r"C:\Windows\Fonts\msyh.ttc"] if bold else FONT_CANDIDATES
    for p in picks:
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, size)
            except Exception:
                continue
    return ImageFont.load_default()

# ---------- 配色（Material 3 · 纸与墨） ----------
LIGHT = dict(
    bg=(250, 249, 247), on_bg=(26, 28, 27),
    primary=(47, 111, 107), primary_container=(182, 227, 222), on_primary_container=(7, 32, 30),
    surface_variant=(219, 229, 227), on_surface_variant=(63, 73, 72),
    outline_variant=(190, 201, 199),
    tertiary=(180, 102, 74),
    todo=(61, 90, 128), note=(138, 143, 142),
    sum_bg=(239, 244, 243), warn_bg=(247, 238, 233), warn_fg=(90, 42, 24),
    white=(255, 255, 255), status=(92, 102, 100),
)
DARK = dict(
    bg=(16, 20, 19), on_bg=(225, 227, 225),
    primary=(155, 208, 203), primary_container=(21, 80, 73), on_primary_container=(200, 237, 232),
    surface_variant=(63, 73, 72), on_surface_variant=(184, 196, 194),
    outline_variant=(57, 66, 63),
    tertiary=(255, 181, 155),
    todo=(120, 156, 200), note=(140, 148, 146),
    sum_bg=(26, 36, 35), warn_bg=(48, 33, 28), warn_fg=(240, 200, 180),
    white=(255, 255, 255), status=(150, 162, 160),
)

# 手机画布：红米 K80 逻辑尺寸（按 340x700 设计稿 x2 输出）
W, H, S = 340, 700, 2
PW, PH = W * S, H * S


def new_phone():
    img = Image.new("RGB", (PW, PH), LIGHT["bg"])
    return img, ImageDraw.Draw(img)


def px(v):
    return int(v * S)


def text(d, xy, s, f, fill, anchor="la"):
    d.text((px(xy[0]), px(xy[1])), s, font=f, fill=fill, anchor=anchor)


def rounded(d, box, r, fill=None, outline=None, width=1):
    d.rounded_rectangle([px(box[0]), px(box[1]), px(box[2]), px(box[3])],
                        radius=px(r), fill=fill, outline=outline, width=px(width) if outline else 0)


def statusbar(d, c, time_s="10:24", bat="87%"):
    text(d, (16, 8), time_s, font(11), c["status"])
    text(d, (324, 8), f"5G   {bat}", font(11), c["status"], anchor="ra")


def topbar(d, c, title="收着"):
    text(d, (16, 32), title, font(24, True), c["on_bg"])
    # 齿轮
    d.ellipse([px(292), px(34), px(324), px(66)], fill=None, outline=c["on_surface_variant"], width=px(1.4))
    d.ellipse([px(302), px(44), px(314), px(56)], fill=None, outline=c["on_surface_variant"], width=px(1.4))


def chips(d, c, y=76, active=0):
    labels = ["全部", "文章", "待办", "账目", "笔记"]
    x = 16
    for i, lb in enumerate(labels):
        w = 12 * len(lb) + 22
        on = (i == active)
        rounded(d, (x, y, x + w, y + 25), 8,
                fill=c["primary_container"] if on else c["surface_variant"])
        # 对比度已实测：深色选中 7.35:1、未选 5.19:1；浅色 12.18:1 / 7.23:1，均过 WCAG AA
        text(d, (x + w / 2, y + 6), lb, font(12, on),
             c["on_primary_container"] if on else c["on_surface_variant"], anchor="ma")
        x += w + 8


def row_frame(d, c, y, h, bar_color):
    """列表行：左色条 + 底部分割线"""
    d.rectangle([px(0), px(y), px(4), px(y + h)], fill=bar_color)
    d.line([px(16), px(y + h), px(340), px(y + h)], fill=c["outline_variant"], width=px(1))


# ============================================================
# ① 收件箱
# ============================================================
def screen_inbox(c=LIGHT, dark=False):
    img, d = new_phone()
    statusbar(d, c)
    topbar(d, c)
    chips(d, c, 76)
    # 顶部进度条
    d.rectangle([px(0), px(112), px(340), px(114)], fill=c["surface_variant"])
    d.rectangle([px(0), px(112), px(130), px(114)], fill=c["primary"])

    y = 118
    # 抽取中
    row_frame(d, c, y, 62, c["outline_variant"])
    text(d, (16, y + 14), "正在收着…", font(15), c["on_surface_variant"])
    text(d, (16, y + 38), "公众号 · 2 秒前", font(12), c["on_surface_variant"])
    y += 62

    # 记账确认（暖赭）
    row_frame(d, c, y, 96, c["tertiary"])
    text(d, (16, y + 13), "记一笔：烧饼", font(15, True), c["on_bg"])
    text(d, (16, y + 37), "¥1.00", font(16, True), c["tertiary"])
    text(d, (74, y + 41), "餐饮", font(12), c["on_surface_variant"])
    rounded(d, (16, y + 64, 76, y + 88), 12, fill=c["tertiary"])
    text(d, (46, y + 70), "确认", font(12, True), c["white"], anchor="ma")
    rounded(d, (84, y + 64, 132, y + 88), 12, fill=None, outline=c["outline_variant"], width=1)
    text(d, (108, y + 70), "改", font(12), c["on_surface_variant"], anchor="ma")
    y += 96

    # 待办
    row_frame(d, c, y, 66, c["todo"])
    text(d, (16, y + 14), "明天去老丈人家", font(15, True), c["on_bg"])
    text(d, (16, y + 38), "⏰ 明天 10:00 · 今晚 20:00 提醒", font(12), c["on_surface_variant"])
    y += 66

    # 文章
    row_frame(d, c, y, 110, c["primary"])
    text(d, (16, y + 13), "一篇讲 Flutter 状态管理的文章", font(15, True), c["on_bg"])
    text(d, (16, y + 37), "本文梳理了三种主流方案：Provider、", font(12), c["on_surface_variant"])
    text(d, (16, y + 55), "Riverpod、BLoC，并对比了各自适用场景。", font(12), c["on_surface_variant"])
    text(d, (16, y + 77), "公众号 · 3 小时前", font(11), c["on_surface_variant"])
    for i, tg in enumerate(["Flutter", "状态管理"]):
        tx = 16 + i * 62
        rounded(d, (tx, y + 92, tx + 54, y + 108), 6, fill=c["surface_variant"])
        text(d, (tx + 27, y + 95), tg, font(10), c["on_surface_variant"], anchor="ma")

    # FAB
    d.ellipse([px(268), px(624), px(324), px(680)], fill=c["primary"])
    text(d, (296, 638), "+", font(26), c["white"], anchor="ma")
    return img


# ============================================================
# ② 快速录入浮层
# ============================================================
def screen_quickadd():
    img, d = new_phone()
    c = LIGHT
    statusbar(d, c); topbar(d, c); chips(d, c, 76)

    y = 118
    row_frame(d, c, y, 66, c["todo"])
    text(d, (16, y + 14), "明天去老丈人家", font(15, True), c["on_bg"])
    text(d, (16, y + 38), "⏰ 明天 10:00 · 今晚 20:00 提醒", font(12), c["on_surface_variant"])
    y += 66
    row_frame(d, c, y, 76, c["primary"])
    text(d, (16, y + 13), "一篇讲 Flutter 状态管理的文章", font(15, True), c["on_bg"])
    text(d, (16, y + 37), "公众号 · 3 小时前", font(12), c["on_surface_variant"])

    # 遮罩
    overlay = Image.new("RGBA", (PW, PH), (20, 24, 23, 108))
    img.paste(Image.alpha_composite(img.convert("RGBA"), overlay).convert("RGB"), (0, 0))
    d = ImageDraw.Draw(img)

    # 底部容器
    sy = 372
    rounded(d, (0, sy, 340, 700), 22, fill=c["bg"])
    d.rectangle([px(0), px(sy + 20), px(340), px(700)], fill=c["bg"])
    rounded(d, (0, sy, 340, sy + 44), 22, fill=c["bg"])

    rounded(d, (153, sy + 10, 187, sy + 14), 2, fill=c["outline_variant"])
    text(d, (16, sy + 26), "说点什么，或记一下…", font(16, True), c["on_surface_variant"])

    # 输入框
    rounded(d, (16, sy + 58, 324, sy + 114), 12, fill=c["surface_variant"],
            outline=c["primary"], width=2)
    text(d, (30, sy + 76), "明天去老丈人家", font(15), c["on_bg"])
    d.rectangle([px(150), px(sy + 76), px(152), px(sy + 94)], fill=c["primary"])

    # 解析预览
    rounded(d, (16, sy + 128, 324, sy + 200), 12, fill=c["sum_bg"])
    text(d, (30, sy + 138), "解析结果", font(11), c["on_surface_variant"])
    d.ellipse([px(30), px(sy + 164), px(38), px(sy + 172)], fill=c["todo"])
    text(d, (48, sy + 158), "待办", font(15, True), c["on_bg"])
    text(d, (48, sy + 180), "明天 10:00 → 今晚 20:00 提醒", font(12), c["on_surface_variant"])

    # 语音 + 收着
    d.ellipse([px(16), px(sy + 218), px(68), px(sy + 270)], fill=c["surface_variant"])
    text(d, (42, sy + 232), "🎤", font(19), c["on_surface_variant"], anchor="ma")
    rounded(d, (80, sy + 218, 324, sy + 270), 26, fill=c["primary"])
    text(d, (202, sy + 234), "收着", font(16, True), c["white"], anchor="ma")
    return img


# ============================================================
# ③ 文章详情
# ============================================================
def screen_detail():
    img, d = new_phone()
    c = LIGHT
    statusbar(d, c)
    text(d, (16, 34), "←", font(20), c["on_surface_variant"])

    y = 74
    text(d, (16, y), "一篇讲 Flutter 状态管理的文章", font(20, True), c["on_bg"])
    y += 34
    text(d, (16, y), "公众号「移动开发笔记」· 2026-10-03 · 3240 字", font(12), c["on_surface_variant"])
    y += 30

    # 摘要
    rounded(d, (16, y, 324, y + 116), 12, fill=c["sum_bg"])
    text(d, (30, y + 14), "📝 摘要", font(12, True), c["primary"])
    for i, ln in enumerate([
        "本文梳理了三种主流方案——Provider、",
        "Riverpod、BLoC，并对比了各自适用场景",
        "与迁移成本。作者认为小项目用 Provider",
        "足够，中大型项目应优先 Riverpod。",
    ]):
        text(d, (30, y + 38 + i * 19), ln, font(13), c["on_bg"])
    y += 130

    # 标签
    for i, tg in enumerate(["Flutter", "状态管理", "移动开发"]):
        tx = 16 + i * 76
        rounded(d, (tx, y, tx + 68, y + 18), 6, fill=c["surface_variant"])
        text(d, (tx + 34, y + 2), tg, font(11), c["on_surface_variant"], anchor="ma")
    y += 32

    d.line([px(16), px(y), px(324), px(y)], fill=c["outline_variant"], width=px(1))
    y += 16

    # 正文
    for para in [
        ["状态管理是 Flutter 开发中最容易被过度", "设计的一环。很多团队在项目初期就引入了", "重量级方案，结果复杂度上去了，可维护性", "反而下降。"],
        ["我们先看 Provider。它的学习曲线最平缓，", "本质上是对 InheritedWidget 的封装……"],
    ]:
        for ln in para:
            text(d, (16, y), ln, font(15), (35, 40, 39))
            y += 26
        y += 10
    return img


# ============================================================
# ④ 后台保活设置（小米专项）
# ============================================================
def screen_settings():
    img, d = new_phone()
    c = LIGHT
    statusbar(d, c)
    text(d, (16, 34), "←", font(20), c["on_surface_variant"])
    text(d, (44, 36), "后台运行设置", font(15, True), c["on_bg"])

    y = 76
    text(d, (16, y), "为保证提醒准时，请完成以下 4 项：", font(13), c["on_surface_variant"])
    y += 30

    items = [("自启动", "已开启", True), ("省电策略", "无限制", True),
             ("通知设为重要", "去设置 →", False), ("锁定最近任务", "查看步骤 →", False)]
    for name, right, ok in items:
        if ok:
            d.ellipse([px(16), px(y + 2), px(35), px(y + 21)], fill=c["primary"])
            text(d, (25.5, y + 5), "✓", font(11, True), c["white"], anchor="ma")
        else:
            d.ellipse([px(16), px(y + 2), px(35), px(y + 21)], fill=None,
                      outline=c["outline_variant"], width=px(1.5))
        text(d, (44, y + 3), name, font(14), c["on_bg"])
        text(d, (324, y + 5), right, font(12, True) if not ok else font(12),
             c["primary"] if not ok else c["on_surface_variant"], anchor="ra")
        y += 40
        d.line([px(16), px(y - 8), px(340), px(y - 8)], fill=c["outline_variant"], width=px(1))

    y += 12
    rounded(d, (16, y, 324, y + 74), 12, fill=c["warn_bg"])
    text(d, (30, y + 13), "⚠️ 检测到：红米 K80（HyperOS）", font(12, True), c["warn_fg"])
    text(d, (30, y + 36), "以下步骤专为此机型定制。若跳转失败，", font(12), c["warn_fg"])
    text(d, (30, y + 53), "将显示图文步骤说明。", font(12), c["warn_fg"])
    y += 88

    rounded(d, (16, y, 324, y + 74), 12, fill=c["sum_bg"])
    text(d, (30, y + 13), "上次提醒偏差：2 分钟", font(12, True), c["primary"])
    text(d, (30, y + 36), "偏差偏大，建议检查「省电策略」是否", font(12), c["on_bg"])
    text(d, (30, y + 53), "已设为无限制。", font(12), c["on_bg"])
    return img


# ============================================================
# ⑤ 空状态
# ============================================================
def screen_empty():
    img, d = new_phone()
    c = LIGHT
    statusbar(d, c); topbar(d, c); chips(d, c, 76)

    d.rounded_rectangle([px(142), px(268), px(198), px(318)], radius=px(8),
                        fill=None, outline=c["outline_variant"], width=px(2))
    d.line([px(142), px(286), px(198), px(286)], fill=c["outline_variant"], width=px(2))
    text(d, (170, 258), "📥", font(28), c["on_surface_variant"], anchor="ma")

    text(d, (170, 348), "还没收着东西", font(17, True), c["on_bg"], anchor="ma")
    text(d, (170, 392), "看到好文章，分享给「收着」", font(13), c["on_surface_variant"], anchor="ma")
    text(d, (170, 416), "想到一件事，说一句话", font(13), c["on_surface_variant"], anchor="ma")

    d.ellipse([px(268), px(624), px(324), px(680)], fill=c["primary"])
    text(d, (296, 638), "+", font(26), c["white"], anchor="ma")
    return img


# ============================================================
# ⑥ 深色模式
# ============================================================
def screen_dark():
    img = screen_inbox(DARK, dark=True)
    return img


# ---------- 输出 ----------
screens = [
    ("01-收件箱.png", screen_inbox()),
    ("02-快速录入.png", screen_quickadd()),
    ("03-文章详情.png", screen_detail()),
    ("04-后台保活设置.png", screen_settings()),
    ("05-空状态.png", screen_empty()),
    ("06-深色模式.png", screen_dark()),
]
for name, im in screens:
    p = os.path.join(OUT, name)
    im.save(p)
    print("OK", p)

# 拼一张总览图
gap = 24
cols, rows = 3, 2
CW = PW + gap
CH = PH + gap
board = Image.new("RGB", (cols * CW + gap, rows * CH + gap), (237, 235, 232))
for i, (_, im) in enumerate(screens):
    r, cc = divmod(i, cols)
    board.paste(im, (gap + cc * CW, gap + r * CH))
bp = os.path.join(OUT, "00-总览.png")
board.save(bp)
print("OK", bp)
print("尺寸:", board.size)
