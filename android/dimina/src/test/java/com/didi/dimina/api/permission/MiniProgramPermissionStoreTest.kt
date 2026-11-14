package com.didi.dimina.api.permission

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * per-appid 权限账本单测（假持久化，不碰 MMKV 原生库）：
 * consent 形态、声明解析（manifest.json permissions 段）、state()/set() 宿主消费面。
 */
class MiniProgramPermissionStoreTest {

    private class FakePersistence : MiniProgramPermissionStore.Persistence {
        val store = LinkedHashMap<String, String>()
        override fun read(appId: String): String? = store[appId]
        override fun write(appId: String, value: String) { store[appId] = value }
        override fun clear(appId: String) { store.remove(appId) }
    }

    private class FakeDeclarationSource : MiniProgramPermissionStore.DeclarationSource {
        var permissions: JSONObject? = null
        var name: String? = null
        override fun readPermissions(appId: String): JSONObject? = permissions
        override fun readName(appId: String): String? = name
    }

    private val persistence = FakePersistence()
    private val source = FakeDeclarationSource()
    private val appId = "wx7c3e9d2a1b4f5e68"

    @Before
    fun setUp() {
        MiniProgramPermissionStore.persistence = persistence
        MiniProgramPermissionStore.declarationSource = source
        // 单例缓存跨用例复位
        MiniProgramPermissionStore.clear(appId)
        MiniProgramPermissionStore.clear("wx-other")
    }

    @Test
    fun `set and state roundtrip on host contract surface`() {
        MiniProgramPermissionStore.set(appId, "scope.record", true)
        val state = MiniProgramPermissionStore.state(appId)
        assertEquals(true, state["scope.record"])
        assertEquals(false, state["scope.notification"])
        // 冻结目录 key 集合
        assertTrue(state.containsKey("network.external"))
        assertTrue(state.containsKey("scope.clipboard"))
        // v1 诚实不可用位恒 false
        assertEquals(false, state["scope.userLocation"])
    }

    @Test
    fun `consent is persisted in contract store shape`() {
        MiniProgramPermissionStore.set(appId, "scope.record", true)
        val raw = JSONObject(persistence.store[appId]!!)
        val record = raw.getJSONObject("scopes").getJSONObject("scope.record")
        assertEquals(true, record.getBoolean("granted"))
        assertTrue(record.getLong("grantedAt") > 0)
        // network 段存在（声明镜像，首期为空数组）
        assertTrue(raw.getJSONObject("network").has("domains"))
    }

    @Test
    fun `consent is isolated per appid`() {
        MiniProgramPermissionStore.set(appId, "scope.record", true)
        MiniProgramPermissionStore.set("wx-other", "scope.record", false)
        assertEquals(true, MiniProgramPermissionStore.consentRecord(appId, "scope.record")?.granted)
        assertEquals(false, MiniProgramPermissionStore.consentRecord("wx-other", "scope.record")?.granted)
        assertFalse(persistence.store[appId] == persistence.store["wx-other"])
    }

    @Test
    fun `explicit revoke sticks and dialog deny leaves no record`() {
        MiniProgramPermissionStore.set(appId, "scope.record", false)
        assertEquals(false, MiniProgramPermissionStore.consentRecord(appId, "scope.record")?.granted)
        // 弹窗拒绝=不落盘（下次再弹）：recordGrant 只在允许后调用
        assertNull(MiniProgramPermissionStore.consentRecord("wx-other", "scope.record"))
    }

    @Test
    fun `manifest permissions feed declarations and dialog desc`() {
        val manifest = JSONObject(
            """
            {"scopes":[{"key":"scope.record","title":"麦克风","usage":"录音测试需要麦克风","mode":"authorized"}],
             "network":{"domains":["https://open.bigmodel.cn"]}}
            """.trimIndent(),
        )
        // 生产路径：RemoteUpdateManager.fetchManifest 成功后喂 onManifest
        source.name = "录音测试"
        MiniProgramPermissionStore.onManifest(appId, manifest)
        assertTrue(MiniProgramPermissionStore.isDeclared(appId, "scope.record"))
        assertFalse(MiniProgramPermissionStore.isDeclared(appId, "scope.camera"))
        assertEquals("录音测试需要麦克风", MiniProgramPermissionStore.scopeDesc(appId, "scope.record"))
        assertEquals(listOf("https://open.bigmodel.cn"), MiniProgramPermissionStore.networkDomains(appId))
        assertEquals("录音测试", MiniProgramPermissionStore.displayName(appId))
        // network.domains 镜像进 consent store 的 network 段
        val raw = JSONObject(persistence.store[appId]!!)
        assertEquals(
            "https://open.bigmodel.cn",
            raw.getJSONObject("network").getJSONArray("domains").getString(0),
        )
    }

    @Test
    fun `bundle json declaration shape is also accepted`() {
        source.permissions = JSONObject(
            """{"scopes":[{"scope":"scope.record","desc":"录音测试需要麦克风"}]}""",
        )
        MiniProgramPermissionStore.clear(appId)
        assertTrue(MiniProgramPermissionStore.isDeclared(appId, "scope.record"))
        assertEquals("录音测试需要麦克风", MiniProgramPermissionStore.scopeDesc(appId, "scope.record"))
        assertTrue(MiniProgramPermissionStore.networkDomains(appId).isEmpty())
    }

    @Test
    fun `unknown scopes in declaration are ignored`() {
        source.permissions = JSONObject(
            """
            {"scopes":[{"key":"scope.werun","usage":"x"},{"key":"scope.record","usage":"y"}]}
            """.trimIndent(),
        )
        MiniProgramPermissionStore.clear(appId)
        assertFalse(MiniProgramPermissionStore.isDeclared(appId, "scope.werun"))
        assertTrue(MiniProgramPermissionStore.isDeclared(appId, "scope.record"))
    }

    @Test
    fun `onManifest without permissions clears declarations like legacy packages`() {
        source.permissions = JSONObject("""{"scopes":[{"key":"scope.record","usage":"y"}]}""")
        MiniProgramPermissionStore.clear(appId)
        assertTrue(MiniProgramPermissionStore.isDeclared(appId, "scope.record"))
        // 老包：manifest 无 permissions 段 = 清空声明（诚实 fail + 通用文案）
        MiniProgramPermissionStore.onManifest(appId, null)
        assertFalse(MiniProgramPermissionStore.isDeclared(appId, "scope.record"))
        assertNull(MiniProgramPermissionStore.scopeDesc(appId, "scope.record"))
    }

    @Test
    fun `catalog matches frozen contract v1`() {
        val catalog = MiniProgramPermissionStore.SCOPE_CATALOG
        assertEquals(MiniProgramPermissionStore.LEVEL_AUTHORIZED, catalog["scope.record"])
        assertEquals(MiniProgramPermissionStore.LEVEL_AUTHORIZED, catalog["scope.notification"])
        assertEquals(MiniProgramPermissionStore.LEVEL_DECLARED, catalog["network.external"])
        assertEquals(MiniProgramPermissionStore.LEVEL_DECLARED, catalog["scope.contacts"])
        assertEquals(MiniProgramPermissionStore.LEVEL_PLACEHOLDER, catalog["scope.camera"])
        assertEquals(MiniProgramPermissionStore.LEVEL_PLACEHOLDER, catalog["scope.writePhotosAlbum"])
        assertEquals(MiniProgramPermissionStore.LEVEL_PLACEHOLDER, catalog["scope.bluetooth"])
        assertEquals(MiniProgramPermissionStore.LEVEL_PLACEHOLDER, catalog["scope.clipboard"])
        assertEquals(MiniProgramPermissionStore.LEVEL_UNSUPPORTED, catalog["scope.userLocation"])
        assertEquals(9, catalog.size)
    }

    @Test
    fun `malformed consent payload degrades to empty consent`() {
        persistence.store[appId] = "not-json-at-all"
        assertNull(MiniProgramPermissionStore.consentRecord(appId, "scope.record"))
        // 清缓存后再投合法形态（绕过 writeConsent 直写持久层后需要清内存缓存）
        MiniProgramPermissionStore.clear(appId)
        persistence.store[appId] = """{"scopes":"oops"}"""
        assertNull(MiniProgramPermissionStore.consentRecord(appId, "scope.record"))
        MiniProgramPermissionStore.clear(appId)
        persistence.store[appId] =
            """{"scopes":{"scope.record":{"granted":true,"grantedAt":123}},"network":{"domains":["https://a.cn"]}}"""
        assertEquals(true, MiniProgramPermissionStore.consentRecord(appId, "scope.record")?.granted)
        // consent JSON 的 network 段（契约 store 形态）原样可读回
        val raw = JSONObject(persistence.store[appId]!!)
        assertEquals("https://a.cn", raw.getJSONObject("network").getJSONArray("domains").getString(0))
    }
}

/** manifest.json permissions 段解析器（wanqi 形态 + bundle.json 兼容形态）。 */
class ManifestPermissionsParserTest {

    @Test
    fun `parses wanqi manifest shape`() {
        val parsed = ManifestPermissionsParser.parse(
            JSONObject(
                """
                {"scopes":[{"key":"scope.record","usage":"需要麦克风","mode":"authorized"}],
                 "network":{"domains":["https://a.cn","https://b.cn"]}}
                """.trimIndent(),
            ),
        )
        assertEquals(setOf("scope.record"), parsed?.scopes?.keys)
        assertEquals("需要麦克风", parsed?.scopes?.get("scope.record")?.desc)
        assertEquals(listOf("https://a.cn", "https://b.cn"), parsed?.domains)
    }

    @Test
    fun `returns null for legacy packages without permissions`() {
        assertNull(ManifestPermissionsParser.parse(null))
        assertNull(ManifestPermissionsParser.parse(JSONObject("{}")))
        assertNull(ManifestPermissionsParser.parse(JSONObject("""{"scopes":[]}""")))
    }

    @Test
    fun `catalog-external scopes are dropped`() {
        val parsed = ManifestPermissionsParser.parse(
            JSONObject("""{"scopes":[{"key":"scope后台运行","usage":"x"}]}"""),
        )
        assertNull(parsed)
    }
}
