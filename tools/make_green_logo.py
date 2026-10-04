"""生成品牌绿版本的启动页符号（浅色模式品牌启动页用）"""
import sys
sys.path.insert(0, r"D:\projects\shouzhe\tools")
from make_splash_logo import build_monochrome
import os

RES = r"D:\projects\shouzhe\app\src\main\res"
BRAND_GREEN = (61, 220, 132)   # #3DDC84

os.makedirs(os.path.join(RES, "drawable"), exist_ok=True)
icon, _ = build_monochrome(432, BRAND_GREEN)
icon.save(os.path.join(RES, "drawable", "ic_splash_logo_green.png"))
print("生成 drawable/ic_splash_logo_green.png (432x432)")

for name, size in [("mdpi", 108), ("hdpi", 162), ("xhdpi", 216),
                   ("xxhdpi", 324), ("xxxhdpi", 432)]:
    d = os.path.join(RES, "drawable-" + name)
    os.makedirs(d, exist_ok=True)
    im, _ = build_monochrome(size, BRAND_GREEN)
    im.save(os.path.join(d, "ic_splash_logo_green.png"))
print("生成各密度绿色版")
