package com.didi.dimina.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CapsuleStyle(胶囊微信语义配色)纯函数契约:
 * - 默认:底=导航栏解析色 @ 半透明 scrim(透出导航栏);图标随 navigationBarTextStyle。
 * - 宿主显式配置(setCapsuleBackgroundColor/setCapsuleIconColor)永远优先,且原样保留 alpha。
 */
class CapsuleStyleTest {

    private val navBarLight = 0xFFECECEC.toInt()
    private val navBarDark = 0xFF191919.toInt()

    @Test
    fun `default background is nav bar color with scrim alpha`() {
        val expectedAlpha = (CapsuleStyle.NAV_BAR_SCRIM_ALPHA * 255f + 0.5f).toInt()
        val bg = CapsuleStyle.backgroundColor(navBarColor = navBarLight, explicit = null)
        assertEquals(expectedAlpha, bg ushr 24 and 0xFF)
        assertEquals(0xECECEC, bg and 0xFFFFFF)
    }

    @Test
    fun `scrim keeps rgb so the nav bar color shows through`() {
        val bg = CapsuleStyle.backgroundColor(navBarColor = navBarDark, explicit = null)
        assertEquals(0x191919, bg and 0xFFFFFF)
    }

    @Test
    fun `explicit background wins verbatim including alpha`() {
        val translucentWhite = 0x80FFFFFF.toInt()
        val bg = CapsuleStyle.backgroundColor(navBarColor = navBarLight, explicit = translucentWhite)
        assertEquals(translucentWhite, bg)
    }

    @Test
    fun `icon follows navigationBarTextStyle`() {
        assertEquals(0xFFFFFFFF.toInt(), CapsuleStyle.iconColor(textStyleWhite = true, explicit = null))
        assertEquals(0xFF1F1F1F.toInt(), CapsuleStyle.iconColor(textStyleWhite = false, explicit = null))
    }

    @Test
    fun `explicit icon color wins verbatim`() {
        assertEquals(0xFF123456.toInt(), CapsuleStyle.iconColor(textStyleWhite = true, explicit = 0xFF123456.toInt()))
    }

    @Test
    fun `border and separator derive from icon color with fixed alphas`() {
        val darkIcon = 0xFF1F1F1F.toInt()
        val border = CapsuleStyle.borderColor(darkIcon)
        val separator = CapsuleStyle.separatorColor(darkIcon)
        assertEquals((CapsuleStyle.BORDER_ALPHA * 255f + 0.5f).toInt(), border ushr 24 and 0xFF)
        assertEquals((CapsuleStyle.SEPARATOR_ALPHA * 255f + 0.5f).toInt(), separator ushr 24 and 0xFF)
        assertEquals(0x1F1F1F, border and 0xFFFFFF)
        assertEquals(0x1F1F1F, separator and 0xFFFFFF)
    }

    @Test
    fun `scrim alpha stays within the wechat 60-75 percent band`() {
        assert(CapsuleStyle.NAV_BAR_SCRIM_ALPHA in 0.60f..0.75f) {
            "scrim alpha ${CapsuleStyle.NAV_BAR_SCRIM_ALPHA} must stay in the WeChat 60-75% band"
        }
    }
}
