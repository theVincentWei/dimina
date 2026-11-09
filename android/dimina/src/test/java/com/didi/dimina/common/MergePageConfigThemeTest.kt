package com.didi.dimina.common

import com.didi.dimina.bean.App
import com.didi.dimina.bean.PageModule
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the page-config-over-global-config priority with darkmode "@key" references in play:
 * a page's own window keys win verbatim (still unresolved "@key" strings - resolution happens
 * at style-apply time in the container), missing page keys fall back to the app window values,
 * and fully unset keys keep the historical defaults. Merging must never resolve or strip the
 * "@" - otherwise the current theme at merge time would be baked into the merged config and a
 * later system theme flip could no longer re-resolve it.
 *
 * Pure JVM: [Utils.mergePageConfig] only combines data classes, no Android types are touched.
 */
class MergePageConfigThemeTest {

    private fun appWindow() = App(
        pages = listOf("pages/index/index"),
        window = com.didi.dimina.bean.WindowConfig(
            navigationBarTitleText = "深色模式演示",
            navigationBarBackgroundColor = "@navBgColor",
            navigationBarTextStyle = "@navTxtStyle",
            backgroundColor = "@bgColor",
        ),
    )

    @Test
    fun `page keys override global window keys verbatim including at refs`() {
        val merged = Utils.mergePageConfig(
            appWindow(),
            PageModule(
                navigationBarTitleText = "详情页",
                navigationBarBackgroundColor = "@detailNavBg",
                navigationBarTextStyle = "@navTxtStyle",
                backgroundColor = "@detailBg",
            ),
        )

        assertEquals("详情页", merged.navigationBarTitleText)
        assertEquals("@detailNavBg", merged.navigationBarBackgroundColor)
        assertEquals("@navTxtStyle", merged.navigationBarTextStyle)
        assertEquals("@detailBg", merged.backgroundColor)
    }

    @Test
    fun `page keys absent from the page fall back to global window keys`() {
        val merged = Utils.mergePageConfig(appWindow(), PageModule(navigationBarTitleText = "详情页"))

        assertEquals("@navBgColor", merged.navigationBarBackgroundColor)
        assertEquals("@navTxtStyle", merged.navigationBarTextStyle)
        assertEquals("@bgColor", merged.backgroundColor)
    }

    @Test
    fun `fully unset window keeps historical literal defaults`() {
        val merged = Utils.mergePageConfig(
            App(pages = listOf("pages/index/index")),
            null,
        )

        assertEquals("", merged.navigationBarTitleText)
        assertEquals("#000", merged.navigationBarBackgroundColor)
        assertEquals("white", merged.navigationBarTextStyle)
        assertEquals("#fff", merged.backgroundColor)
    }

    @Test
    fun `navigation style and home button follow the same page-over-global priority`() {
        val pageWins = Utils.mergePageConfig(
            appWindow().copy(
                window = appWindow().window?.copy(navigationStyle = "default", homeButton = false),
            ),
            PageModule(navigationStyle = "custom", homeButton = true),
        )
        assertEquals("custom", pageWins.navigationStyle)
        assertEquals(true, pageWins.homeButton)

        val globalApplies = Utils.mergePageConfig(
            appWindow().copy(
                window = appWindow().window?.copy(navigationStyle = "custom", homeButton = true),
            ),
            PageModule(navigationBarTitleText = "详情页"),
        )
        assertEquals("custom", globalApplies.navigationStyle)
        assertEquals(true, globalApplies.homeButton)
    }
}
