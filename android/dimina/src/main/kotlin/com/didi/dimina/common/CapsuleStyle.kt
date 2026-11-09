package com.didi.dimina.common

import androidx.annotation.ColorInt

/**
 * 胶囊(···|◎)微信语义配色。
 *
 * 微信真实行为:胶囊是一个半透明层,透出页面导航栏背景色(视觉上随小程序
 * navigationBarBackgroundColor 变化);图标颜色跟小程序声明的
 * navigationBarTextStyle(black/white)自适应;位置右上角垂直居中。
 * 微信对所有小程序一致,与是否声明 darkmode 无关。
 *
 * 全部是 ARGB int 纯函数,可在 JVM 单测;Compose 侧用 Color.toArgb()/Color(int) 换转。
 * 优先级:宿主显式配置(setCapsuleBackgroundColor/setCapsuleIconColor)> 微信默认语义。
 */
object CapsuleStyle {

    /** 胶囊底=导航栏解析色 @ 此 alpha(半透明 scrim 透出导航栏)。微信观感约 60-75%,取中间值。 */
    const val NAV_BAR_SCRIM_ALPHA: Float = 0.66f

    /** 胶囊 hairline 描边:随图标色(深图标≈浅灰描边,白图标≈半透明白描边)。 */
    const val BORDER_ALPHA: Float = 0.15f

    /** 中缝分隔线:比描边略实一点。 */
    const val SEPARATOR_ALPHA: Float = 0.2f

    /** 胶囊底色:宿主显式配置优先(可自带 alpha);默认=导航栏解析色 @ scrim alpha。 */
    @ColorInt
    fun backgroundColor(@ColorInt navBarColor: Int, @ColorInt explicit: Int?): Int =
        explicit ?: applyAlpha(navBarColor, NAV_BAR_SCRIM_ALPHA)

    /** 图标色:宿主显式配置优先;默认跟 navigationBarTextStyle(white→浅色图标,否则深色图标)。 */
    @ColorInt
    fun iconColor(textStyleWhite: Boolean, @ColorInt explicit: Int?): Int =
        explicit ?: if (textStyleWhite) WHITE_ICON else DARK_ICON

    @ColorInt
    fun borderColor(@ColorInt iconColor: Int): Int = applyAlpha(iconColor, BORDER_ALPHA)

    @ColorInt
    fun separatorColor(@ColorInt iconColor: Int): Int = applyAlpha(iconColor, SEPARATOR_ALPHA)

    /** 深色图标(navigationBarTextStyle=black),与 DiminaConfig 默认图标色一致。 */
    @ColorInt
    private val DARK_ICON = 0xFF1F1F1F.toInt()

    @ColorInt
    private val WHITE_ICON = 0xFFFFFFFF.toInt()

    /** 保留 RGB,重写 alpha 通道(四舍五入到字节)。 */
    @ColorInt
    private fun applyAlpha(@ColorInt color: Int, alpha: Float): Int =
        (color and 0xFFFFFF) or ((alpha * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24)
}
