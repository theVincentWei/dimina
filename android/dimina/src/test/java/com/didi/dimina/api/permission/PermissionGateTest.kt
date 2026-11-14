package com.didi.dimina.api.permission

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守门判定核心单测（契约 §3/§4 冻结映射表，2026-11-14）。
 * 纯函数层：apiScopeOf / decide / isCameraSource / url 白名单。
 */
class PermissionGateTest {

    private fun facts(
        declared: Boolean = true,
        consent: Boolean? = null,
        urlAllowed: Boolean = true,
    ) = PermissionGate.Facts(declared, consent, urlAllowed)

    @Test
    fun `exempt apis are not in the gate map`() {
        // 豁免级（契约 §1）：storage/sensors/主题/UI/文件沙盒/局域网族零改动零拦截。
        assertNull(PermissionGate.apiScopeOf("setStorageSync"))
        assertNull(PermissionGate.apiScopeOf("onAccelerometerChange"))
        assertNull(PermissionGate.apiScopeOf("startLocalServiceDiscovery"))
        assertNull(PermissionGate.apiScopeOf("showModal"))
        assertNull(PermissionGate.apiScopeOf("FileSystemManager.readFile"))
        assertNull(PermissionGate.apiScopeOf("setClipboardData"))
        assertNull(PermissionGate.apiScopeOf("vibrateShort"))
        assertNull(PermissionGate.apiScopeOf("getNetworkType"))
    }

    @Test
    fun `frozen mapping table hits`() {
        // 授权级
        assertEquals("scope.record", PermissionGate.apiScopeOf("getRecorderManager"))
        assertEquals("scope.record", PermissionGate.apiScopeOf("Recorder.start"))
        assertEquals("scope.notification", PermissionGate.apiScopeOf("requestSubscribeMessage"))
        // 占位级
        assertEquals("scope.camera", PermissionGate.apiScopeOf("chooseMedia"))
        assertEquals("scope.camera", PermissionGate.apiScopeOf("scanCode"))
        assertEquals("scope.writePhotosAlbum", PermissionGate.apiScopeOf("saveImageToPhotosAlbum"))
        assertEquals("scope.bluetooth", PermissionGate.apiScopeOf("openBluetoothAdapter"))
        assertEquals("scope.clipboard", PermissionGate.apiScopeOf("getClipboardData"))
        // 声明级
        assertEquals("scope.contacts", PermissionGate.apiScopeOf("addPhoneContact"))
        assertEquals("network.external", PermissionGate.apiScopeOf("request"))
        assertEquals("network.external", PermissionGate.apiScopeOf("connectSocket"))
        // v1 不可用
        assertEquals("scope.userLocation", PermissionGate.apiScopeOf("getLocation"))
        assertEquals("scope.userLocation", PermissionGate.apiScopeOf("onLocationChange"))
    }

    @Test
    fun `userLocation always fails not supported`() {
        val decision = PermissionGate.decide(
            "getLocation",
            PermissionGate.scopeLevel("scope.userLocation"),
            facts(declared = true, consent = true),
        )
        assertTrue(decision is PermissionGate.Decision.Fail)
        assertEquals("getLocation:fail not supported", (decision as PermissionGate.Decision.Fail).errMsg)
    }

    @Test
    fun `undeclared call fails honest permission undeclared`() {
        val decision = PermissionGate.decide(
            "getRecorderManager",
            PermissionGate.scopeLevel("scope.record"),
            facts(declared = false),
        )
        assertTrue(decision is PermissionGate.Decision.Fail)
        assertEquals("getRecorderManager:fail permission undeclared", (decision as PermissionGate.Decision.Fail).errMsg)
    }

    @Test
    fun `authorized level asks when consent missing passes when granted`() {
        val level = PermissionGate.scopeLevel("scope.record")
        assertTrue(PermissionGate.decide("Recorder.start", level, facts(consent = null)) is PermissionGate.Decision.Ask)
        assertTrue(PermissionGate.decide("Recorder.start", level, facts(consent = true)) is PermissionGate.Decision.Pass)
    }

    @Test
    fun `explicit revoke fails without dialog`() {
        val decision = PermissionGate.decide(
            "Recorder.start",
            PermissionGate.scopeLevel("scope.record"),
            facts(consent = false),
        )
        assertTrue(decision is PermissionGate.Decision.Fail)
        assertEquals("Recorder.start:fail auth deny", (decision as PermissionGate.Decision.Fail).errMsg)
    }

    @Test
    fun `declared and placeholder levels pass after declaration`() {
        assertTrue(
            PermissionGate.decide("chooseContact", PermissionGate.scopeLevel("scope.contacts"), facts()) 
                is PermissionGate.Decision.Pass,
        )
        assertTrue(
            PermissionGate.decide("getClipboardData", PermissionGate.scopeLevel("scope.clipboard"), facts()) 
                is PermissionGate.Decision.Pass,
        )
    }

    @Test
    fun `camera source detection`() {
        val camera = JSONObject().put("sourceType", org.json.JSONArray(listOf("album", "camera")))
        val album = JSONObject().put("sourceType", org.json.JSONArray(listOf("album")))
        assertTrue(PermissionGate.isCameraSource(camera))
        assertFalse(PermissionGate.isCameraSource(album))
        // 缺省 = 微信默认（album+camera）= 相机源
        assertTrue(PermissionGate.isCameraSource(JSONObject()))
    }

    @Test
    fun `url origin extraction`() {
        assertEquals("https://open.bigmodel.cn", PermissionGate.urlOrigin("https://open.bigmodel.cn/api/paas/v4/models"))
        assertEquals("http://127.0.0.1:7610", PermissionGate.urlOrigin("http://127.0.0.1:7610/api/v1/apps/x"))
        assertEquals("wss://example.cn", PermissionGate.urlOrigin("wss://example.cn/ws"))
        assertNull(PermissionGate.urlOrigin("ftp://example.cn"))
        assertNull(PermissionGate.urlOrigin("not-a-url"))
    }

    @Test
    fun `loopback always allowed external needs declaration`() {
        // 本机网关恒放行（契约 §4）——无需任何声明即可过 url 检查这一关。
        assertTrue(PermissionGate.isLoopback(PermissionGate.urlOrigin("http://127.0.0.1:7610/api")!!))
        assertTrue(PermissionGate.isLoopback(PermissionGate.urlOrigin("http://localhost:5173/")!!))
        assertFalse(PermissionGate.isLoopback(PermissionGate.urlOrigin("https://open.bigmodel.cn")!!))
        // 畸形 url 不放行
        assertFalse(PermissionGate.isUrlAllowed("wx-x", ""))
        assertFalse(PermissionGate.isUrlAllowed("wx-x", "javascript:alert(1)"))
    }
}
