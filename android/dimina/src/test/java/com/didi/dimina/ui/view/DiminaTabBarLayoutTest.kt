package com.didi.dimina.ui.view

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the native TabBar item layout that all three platforms share (24dp icon + 3dp + a
 * 10sp/12sp label inside a 49dp row) and the low-contrast hairline border.
 *
 * 背景：标签的 `Text` 一旦漏掉显式 `lineHeight`，就会继承 Material3 正文的 ~24sp 行盒，
 * `24dp 图标 + 3dp + 24dp 行盒` 直接撑满 49dp 行——图标被顶到上边框、上下留白不对称
 * （真机实测白线到图标只有 0.8dp）。这些数字是视觉修复的一部分，改它们必须是有意的。
 *
 * 断言读源码文本：本模块的 JVM 单测没有 Android 运行时，渲染不了的布局只能这样钉住。
 */
class DiminaTabBarLayoutTest {

    private val source: String by lazy {
        val relative = "src/main/kotlin/com/didi/dimina/ui/view/DiminaTabBar.kt"
        val found = listOf(relative, "dimina/$relative").map(::File).firstOrNull(File::isFile)
            ?: throw AssertionError(
                "DiminaTabBar.kt not found from working directory ${File(".").absolutePath}; " +
                    "tried ${listOf(relative, "dimina/$relative")}.",
            )
        found.readText()
    }

    @Test
    fun `label keeps an explicit line height so the item block fits the 49dp row`() {
        assertTrue(
            "tab label must set lineHeight explicitly, otherwise it inherits the ~24sp body line box",
            source.contains("lineHeight = 12.sp"),
        )
        assertTrue(
            "icon→label spacer must stay at 3dp",
            source.contains("Modifier.height(3.dp)"),
        )
        assertTrue(
            "tab row must stay 49dp",
            source.contains(".height(49.dp)"),
        )
    }

    @Test
    fun `top border is a translucent hairline instead of an opaque white bar`() {
        assertTrue(
            "border color must be built from the alpha constants",
            source.contains("BORDER_ALPHA_WHITE") && source.contains("BORDER_ALPHA_BLACK"),
        )
        // 不透明 #FFFFFF / #E0E0E0 正是当初深色 tabBar 上那条刺眼白线的来源,别再写回来
        assertTrue(
            "opaque #E0E0E0 border must not come back",
            !source.contains("0xFFE0E0E0"),
        )
    }
}
