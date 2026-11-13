package com.didi.dimina.common

import com.didi.dimina.bean.WindowConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 微信语义沉浸式全屏判定的纯逻辑回归:
 * - 小游戏(runtimeType "game")一律沉浸 —— 对齐微信小游戏无状态栏;
 * - 小程序必须显式 window.fullscreen = true 才沉浸,缺省(null/false)与旧包
 *   行为一致(状态栏照常);
 * - runtimeType 兜底:未知类型不当 game 处理。
 */
class FullScreenPolicyTest {

    @Test
    fun `mini game is always immersive`() {
        assertTrue(FullScreenPolicy.shouldGoImmersive("game", null))
        assertTrue(FullScreenPolicy.shouldGoImmersive("game", WindowConfig()))
        assertTrue(
            "显式 fullscreen=false 不能压过小游戏语义",
            FullScreenPolicy.shouldGoImmersive("game", WindowConfig(fullscreen = false)),
        )
    }

    @Test
    fun `mini program opts in only via window fullscreen`() {
        assertTrue(
            FullScreenPolicy.shouldGoImmersive("miniProgram", WindowConfig(fullscreen = true)),
        )
    }

    @Test
    fun `mini program without declaration keeps status bar`() {
        assertFalse(FullScreenPolicy.shouldGoImmersive("miniProgram", null))
        assertFalse(FullScreenPolicy.shouldGoImmersive("miniProgram", WindowConfig()))
        assertFalse(
            FullScreenPolicy.shouldGoImmersive("miniProgram", WindowConfig(fullscreen = false)),
        )
        assertFalse(
            "缺省 runtimeType(历史包)不得触发沉浸",
            FullScreenPolicy.shouldGoImmersive(null, WindowConfig(fullscreen = false)),
        )
    }

    @Test
    fun `unknown runtime type is not treated as game`() {
        assertFalse(FullScreenPolicy.shouldGoImmersive("plugin", null))
        assertFalse(FullScreenPolicy.shouldGoImmersive("", WindowConfig()))
    }
}
