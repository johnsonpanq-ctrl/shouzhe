package com.shouzhe.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 「收这吧」配色 —— 浅色湖蓝 / 深色荧光绿（UI-SPEC v5）
 *
 * 纪律：
 * - 单品牌色，浅深两套各自成立
 * - 类型不靠颜色区分
 * - 深色卡片用描边代替阴影
 */
object SzColors {
    // 浅色
    val LightBg = Color(0xFFF2F4F7)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceAlt = Color(0xFFF0F2F5)
    val LightSurfaceDeep = Color(0xFFE8EBEF)
    val LightInk = Color(0xFF17202A)
    val LightInk2 = Color(0xFF5A6675)
    val LightInk3 = Color(0xFF9AA3AF)
    val LightLine = Color(0xFFE8EBEF)
    val LightBrand = Color(0xFF3478F6)
    val LightBrandSoft = Color(0xFFE8F0FE)

    // 深色
    val DarkBg = Color(0xFF0E1116)
    val DarkSurface = Color(0xFF151A21)
    val DarkSurfaceAlt = Color(0xFF1A2028)
    val DarkSurfaceDeep = Color(0xFF1E242C)
    val DarkInk = Color(0xFFE6EAF0)
    val DarkInk2 = Color(0xFF8A94A3)
    val DarkInk3 = Color(0xFF6B7684)
    val DarkLine = Color(0xFF1E242C)
    val DarkBrand = Color(0xFF3DDC84)
    val DarkBrandSoft = Color(0xFF12291D)
}

/** 间距系统（4dp 基准，卡片场景取 9/14/18） */
object SzSpacing {
    val pageH = 14
    val headerH = 18
    val cardGap = 9
    val cardPad = 14
    val xs = 5
    val sm = 8
    val md = 12
    val lg = 16
    val xl = 22
}

/** 圆角 */
object SzRadius {
    val card = 14
    val pill = 15
    val input = 12
    val button = 9
    val chip = 6
}

/**
 * 应用扩展配色 —— Material3 的 colorScheme 覆盖不到的面
 */
data class SzExtras(
    val surfaceAlt: Color,
    val surfaceDeep: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val line: Color,
    val brand: Color,
    val brandSoft: Color,
    val onBrand: Color,
    val isDark: Boolean,
)

private val LocalSzExtras = androidx.compose.runtime.staticCompositionLocalOf {
    SzExtras(
        surfaceAlt = SzColors.LightSurfaceAlt,
        surfaceDeep = SzColors.LightSurfaceDeep,
        ink = SzColors.LightInk,
        ink2 = SzColors.LightInk2,
        ink3 = SzColors.LightInk3,
        line = SzColors.LightLine,
        brand = SzColors.LightBrand,
        brandSoft = SzColors.LightBrandSoft,
        onBrand = Color.White,
        isDark = false,
    )
}

val szExtras: @Composable () -> SzExtras = { LocalSzExtras.current }

private val LightScheme = lightColorScheme(
    primary = SzColors.LightBrand,
    onPrimary = Color.White,
    background = SzColors.LightBg,
    onBackground = SzColors.LightInk,
    surface = SzColors.LightSurface,
    onSurface = SzColors.LightInk,
    surfaceVariant = SzColors.LightSurfaceAlt,
    onSurfaceVariant = SzColors.LightInk2,
    outline = SzColors.LightLine,
    outlineVariant = SzColors.LightLine,
)

private val DarkScheme = darkColorScheme(
    primary = SzColors.DarkBrand,
    onPrimary = Color(0xFF08130C),
    background = SzColors.DarkBg,
    onBackground = SzColors.DarkInk,
    surface = SzColors.DarkSurface,
    onSurface = SzColors.DarkInk,
    surfaceVariant = SzColors.DarkSurfaceAlt,
    onSurfaceVariant = SzColors.DarkInk2,
    outline = SzColors.DarkLine,
    outlineVariant = SzColors.DarkLine,
)

/**
 * 字体：等线（DengXian）为正文，等宽用于金额与时间戳。
 * 不打包字体文件，走系统字体。
 */
val SzTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 31.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.5.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 28.sp,   // 长文阅读 1.85
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 19.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,   // 时间戳 / 元信息
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 15.sp,
    ),
)

@Composable
fun ShouzheTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val extras = if (darkTheme) {
        SzExtras(
            surfaceAlt = SzColors.DarkSurfaceAlt,
            surfaceDeep = SzColors.DarkSurfaceDeep,
            ink = SzColors.DarkInk,
            ink2 = SzColors.DarkInk2,
            ink3 = SzColors.DarkInk3,
            line = SzColors.DarkLine,
            brand = SzColors.DarkBrand,
            brandSoft = SzColors.DarkBrandSoft,
            onBrand = Color(0xFF08130C),
            isDark = true,
        )
    } else {
        SzExtras(
            surfaceAlt = SzColors.LightSurfaceAlt,
            surfaceDeep = SzColors.LightSurfaceDeep,
            ink = SzColors.LightInk,
            ink2 = SzColors.LightInk2,
            ink3 = SzColors.LightInk3,
            line = SzColors.LightLine,
            brand = SzColors.LightBrand,
            brandSoft = SzColors.LightBrandSoft,
            onBrand = Color.White,
            isDark = false,
        )
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalSzExtras provides extras) {
        MaterialTheme(
            colorScheme = scheme,
            typography = SzTypography,
            content = content,
        )
    }
}