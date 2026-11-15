package com.didi.dimina

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the「问AI浮窗」entry-state semantics (2026-11-15, shell modeled after
 * [NotifyEntryState]):
 *
 * - default = visible/on with no host click callback, so hosts that never opt in
 *   still get the spec'd default-on float window with an inert entry;
 * - the container renders the float window and the ⋯menu tile state from this one
 *   observable `visible`, and both close affordances (long-press ✕ and the menu
 *   tile) flip the very same state (wiring pinned in DiminaAskAiWiringTest);
 * - the host click callback is optional (null until set) and clearable.
 */
class AskAiEntryStateTest {

    @Test
    fun `defaults to visible with no click callback`() {
        val state = AskAiEntryState()
        assertTrue(state.visible)
        assertNull(state.onClick)
    }

    @Test
    fun `float visibility follows the state in both directions`() {
        val state = AskAiEntryState()
        // 关闭:容器不渲染浮窗、菜单瓦片置灰(同一 visible)
        state.visible = false
        assertFalse(state.visible)
        // 打开:恢复默认亮态
        state.visible = true
        assertTrue(state.visible)
    }

    @Test
    fun `host click callback is settable and clearable`() {
        val state = AskAiEntryState()
        var clicks = 0
        state.onClick = { clicks += 1 }
        state.onClick?.invoke()
        assertEquals(1, clicks)
        state.onClick = null
        assertNull(state.onClick)
    }

    private fun assertEquals(expected: Int, actual: Int) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
