package com.didi.dimina.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the「问AI」event payload contract (2026-11-15):
 *
 * - the event travels the existing host lifecycle channel (notifyFloated's chain),
 *   carrying exactly the four payload fields (appId, appName, pageTitle, pagePath);
 * - page context (title/path) is normalized: blank/null collapse to `null`, never a
 *   fabricated empty string;
 * - the listener method defaults to a no-op so existing host listeners are unaffected.
 */
class AskAiEventPayloadTest {

    private fun source(relative: String): String {
        val candidates = listOf(relative, "dimina/$relative").map(::File)
        return candidates.firstOrNull(File::isFile)
            ?.readText()
            ?: throw AssertionError("$relative not found from ${File(".").absolutePath}")
    }

    private val listenerSource: String by lazy {
        source("src/main/kotlin/com/didi/dimina/core/MiniProgramLifecycleListener.kt")
    }

    private val miniAppSource: String by lazy {
        source("src/main/kotlin/com/didi/dimina/core/MiniApp.kt")
    }

    @Test
    fun `normalize keeps real page context`() {
        assertEquals(
            "首页" to "/pages/index/index",
            AskAiEventPayload.normalizePageContext("首页", "/pages/index/index"),
        )
    }

    @Test
    fun `normalize collapses blank and null context to null`() {
        assertNull(AskAiEventPayload.normalizePageContext(null, null).first)
        assertNull(AskAiEventPayload.normalizePageContext(null, null).second)
        assertNull(AskAiEventPayload.normalizePageContext("", "").first)
        assertNull(AskAiEventPayload.normalizePageContext("   ", "  ").second)
        // trim 后非空的保留
        assertEquals("首页", AskAiEventPayload.normalizePageContext(" 首页 ", null).first)
    }

    @Test
    fun `event rides the notifyFloated lifecycle channel with the four payload fields`() {
        assertTrue(
            "MiniApp must expose notifyAskAiInvoked on the same lifecycle chain as notifyFloated",
            miniAppSource.contains(
                "internal fun notifyAskAiInvoked(appId: String, appName: String, pageTitle: String?, pagePath: String?)",
            ),
        )
        assertTrue(
            "the notify body must forward all four payload fields to onAskAiInvoked",
            miniAppSource.contains("it.onAskAiInvoked(appId, appName, pageTitle, pagePath)"),
        )
        assertTrue(
            "the listener must carry onAskAiInvoked(appId, appName, pageTitle, pagePath)",
            listenerSource.contains(
                "fun onAskAiInvoked(appId: String, appName: String, pageTitle: String?, pagePath: String?)",
            ),
        )
        assertTrue(
            "onAskAiInvoked must default to a no-op so existing host listeners are unaffected",
            listenerSource.contains("pagePath: String?) {}"),
        )
    }

    @Test
    fun `listener keeps the floated chain intact`() {
        assertTrue(listenerSource.contains("fun onFloated(appId: String, appName: String, iconPng: ByteArray?)"))
        assertTrue(listenerSource.contains("fun onForegrounded(appId: String)"))
        assertTrue(listenerSource.contains("fun onRuntimeDestroyed(appId: String)"))
        assertFalse(miniAppSource.contains("notifyAskAiInvoked(appId, appName) {"))
    }
}
