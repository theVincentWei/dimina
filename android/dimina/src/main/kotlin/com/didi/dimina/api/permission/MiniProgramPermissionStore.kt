package com.didi.dimina.api.permission

import android.content.Context
import com.didi.dimina.common.LogUtils
import com.tencent.mmkv.MMKV
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 平台权限契约 v1.0 的容器侧账本：per-appid scope 目录 + 声明注册表 + consent 持久化。
 * （契约冻结基准：sysmon-perms/platform-permissions-contract-draft.md §1/§3/§4，2026-11-14）
 *
 * 三块职责：
 *  1. **SCOPE_CATALOG**：冻结的 scope 目录（scope→级别）。授权级首次使用弹原生弹窗按 appId
 *     持久化；声明级/占位级只认声明不弹窗；scope.userLocation v1 诚实不可用（getLocation 未
 *     实现，从 authorize/getSetting 支持面摘除，调用 fail not supported）。豁免级（sensors/
 *     audio 播放/振动/storage/主题/UI/自身沙盒）不进目录、不过闸，勿在守门点给它们加闸。
 *  2. **声明**：来源=启动 manifest.json 的 permissions 段（wanqi 下发，形态
 *     `{"scopes":[{key,title,usage,mode}],"network":{domains}}`；兼容 bundle.json 的
 *     `{scope,desc}` 形状）。RemoteUpdateManager 拉 manifest 后经 [onManifest] 喂进来，
 *     同时随 install 写进包内 config.json（离线首启/老宿主也能读到）。老包无该段=未声明
 *     任何敏感能力（守门诚实 fail、弹窗走通用文案）。
 *  3. **consent**：按 appId 持久化在独立 MMKV（命名法同 dimina_storage_v2_<len>_<appId>，
 *     前缀 dimina_perm_v1_，与业务 storage 分文件——wx.setStorageSync 碰不到权限位）。
 *     形态：`{"scopes":{"scope.record":{"granted":true,"grantedAt":<ms>}},"network":{…}}`。
 *
 * 宿主消费面（dsh-go ext module 经此读写，方法名冻结——改前先同步宿主）：
 *   [state]    state(appId): Map<String, Boolean> — 目录内每个 scope 的当前生效位
 *   [set]      set(appId, scope, granted): Unit  — 宿主管理页开关写容器 consent
 *   [SCOPE_CATALOG] — 冻结目录
 *
 * Author: dimina-guard (task-4, 2026-11-14)
 */
object MiniProgramPermissionStore {
    /** 授权级：首次使用自动弹原生弹窗，按 appId 持久化，可撤销。 */
    const val LEVEL_AUTHORIZED = "authorized"

    /** 声明级：bundle.json/manifest 声明才可用，不弹窗（系统 UI 自带确认）。 */
    const val LEVEL_DECLARED = "declared"

    /** 占位级：目录内保留、未声明调用=诚实 fail（对照微信「未声明不能调」）。 */
    const val LEVEL_PLACEHOLDER = "placeholder"

    /** v1 诚实不可用：支持面摘除，调用一律 fail not supported。 */
    const val LEVEL_UNSUPPORTED = "unsupported"

    /** 声明级 network.external 在目录中的 key（domains 白名单声明）。 */
    const val SCOPE_NETWORK_EXTERNAL = "network.external"

    /**
     * 冻结 scope 目录 v1.0（契约 §1）。key=scope，value=级别。
     * 注意：这是「平台目录」而非「wx.* API 表」——API→scope 的守门映射见 PermissionGate。
     */
    val SCOPE_CATALOG: Map<String, String> = mapOf(
        "scope.record" to LEVEL_AUTHORIZED,
        "scope.notification" to LEVEL_AUTHORIZED,
        SCOPE_NETWORK_EXTERNAL to LEVEL_DECLARED,
        "scope.contacts" to LEVEL_DECLARED,
        "scope.camera" to LEVEL_PLACEHOLDER,
        "scope.writePhotosAlbum" to LEVEL_PLACEHOLDER,
        "scope.bluetooth" to LEVEL_PLACEHOLDER,
        "scope.clipboard" to LEVEL_PLACEHOLDER,
        "scope.userLocation" to LEVEL_UNSUPPORTED,
    )

    /** 授权级 scope 集合（未授权时首次触达要弹容器弹窗的目录）。 */
    val AUTHORIZED_SCOPES: Set<String> =
        SCOPE_CATALOG.filterValues { it == LEVEL_AUTHORIZED }.keys

    // ------------------------------------------------------------------
    // 宿主消费面（冻结签名）
    // ------------------------------------------------------------------

    /**
     * 目录内每个 scope 的当前生效位（宿主管理页数据源）。
     * 语义按级别区分（契约 §7）：
     *  - 授权级（record/notification）= 容器 consent 位（管理页开关的原样读回，不掺 OS 位）；
     *  - 声明级（contacts/network.external）= 声明状态（只读展示，开关无意义）；
     *  - 占位级 = 已声明 且（有 OS 权限位时 OS 已授）；无 OS 位的（clipboard）= 已声明；
     *  - scope.userLocation = 恒 false（v1 诚实不可用）。
     */
    fun state(appId: String): Map<String, Boolean> {
        val declared = declaredScopes(appId)
        return SCOPE_CATALOG.mapValues { (scope, level) ->
            when (level) {
                LEVEL_AUTHORIZED -> readConsent(appId).record(scope)?.granted == true
                LEVEL_DECLARED -> scope in declared
                LEVEL_PLACEHOLDER -> scope in declared && PlaceholderOsGate.isOsGranted(scope)
                else -> false
            }
        }
    }

    /**
     * 宿主管理页开关写容器 consent。授权级=真实开关（granted=false 落显式拒绝位，
     * 守门不再弹窗）；声明级/占位级无 consent 位，宿主契约上是只读，这里写进去也只
     * 影响读回值、不影响守门判定。
     */
    fun set(appId: String, scope: String, granted: Boolean) {
        writeScope(appId, scope, granted)
        LogUtils.d(TAG, "set($appId, $scope, $granted)")
    }

    /** 容器内读取某 scope 的 consent 记录（null=从未落盘）。 */
    internal fun consentRecord(appId: String, scope: String): ConsentRecord? =
        readConsent(appId).record(scope)

    /** 弹窗「允许」后落盘授权位（granted=true）。显式拒绝位只经 set(false) 产生。 */
    internal fun recordGrant(appId: String, scope: String) = writeScope(appId, scope, true)

    private fun writeScope(appId: String, scope: String, granted: Boolean) {
        val consent = readConsent(appId)
        val scopes = JSONObject(consent.scopesJson.toString())
        scopes.put(
            scope,
            JSONObject().put("granted", granted).put("grantedAt", System.currentTimeMillis()),
        )
        writeConsent(appId, consent.copy(scopesJson = scopes))
    }

    // ------------------------------------------------------------------
    // consent 持久化（Android=MMKV per-appid）
    // ------------------------------------------------------------------

    /** 容器内授权判定用的单条 consent 记录。 */
    internal data class ConsentRecord(val granted: Boolean, val grantedAt: Long)

    internal data class Consent(
        val scopesJson: JSONObject,
        val networkDomains: List<String>,
    ) {
        fun record(scope: String): ConsentRecord? {
            val entry = scopesJson.optJSONObject(scope) ?: return null
            return ConsentRecord(entry.optBoolean("granted"), entry.optLong("grantedAt"))
        }
    }

    /** consent 持久化后端。MMKV 需要原生库，单测注入内存实现。 */
    internal interface Persistence {
        fun read(appId: String): String?
        fun write(appId: String, value: String)
        fun clear(appId: String)
    }

    /** 声明来源（包内 config.json / assets config.json 的 permissions 段）。 */
    internal interface DeclarationSource {
        fun readPermissions(appId: String): JSONObject?
        fun readName(appId: String): String?
    }

    @Volatile
    internal var persistence: Persistence = MmkvPersistence

    @Volatile
    internal var declarationSource: DeclarationSource = ConfigFileDeclarationSource

    private val consentCache = ConcurrentHashMap<String, Consent>()

    /** 最新 manifest 无声明段的 app（抑制 config.json 回落，见 onManifest）。 */
    private val manifestSuppressed = ConcurrentHashMap.newKeySet<String>()

    private object MmkvPersistence : Persistence {
        // 命名法照 StorageRecordCodec.storageId（dimina_storage_v2_<len>_<appId>），
        // 独立前缀避免与业务 storage 同文件：JS 键值永远写不到权限位。
        private fun storageId(appId: String) = "dimina_perm_v1_${appId.length}_$appId"
        private const val KEY = "consent"

        override fun read(appId: String): String? = MMKV.mmkvWithID(storageId(appId)).decodeString(KEY)
        override fun write(appId: String, value: String) {
            MMKV.mmkvWithID(storageId(appId)).encode(KEY, value)
        }

        override fun clear(appId: String) {
            MMKV.mmkvWithID(storageId(appId)).clearAll()
        }
    }

    /**
     * 从已装包读声明：jsapp/<appId>/config.json（RemoteUpdateManager.install 时由
     * writeConfig 写入 permissions 段）；无本地包回落 assets 内置包 config.json。
     * 形状即 manifest.json 的 permissions 段（写 config 时原样保留）。
     */
    private object ConfigFileDeclarationSource : DeclarationSource {
        override fun readPermissions(appId: String): JSONObject? {
            val context = appContext ?: return null
            val file = java.io.File(context.filesDir, "jsapp/$appId/config.json")
            val raw = if (file.isFile) {
                runCatching { file.readText() }.getOrNull()
            } else {
                runCatching {
                    context.assets.open("jsapp/$appId/config.json").bufferedReader().use { it.readText() }
                }.getOrNull()
            } ?: return null
            return runCatching {
                JSONObject(raw).optJSONObject("permissions")
            }.getOrNull()
        }

        override fun readName(appId: String): String? {
            val context = appContext ?: return null
            val file = java.io.File(context.filesDir, "jsapp/$appId/config.json")
            val raw = if (file.isFile) {
                runCatching { file.readText() }.getOrNull()
            } else {
                runCatching {
                    context.assets.open("jsapp/$appId/config.json").bufferedReader().use { it.readText() }
                }.getOrNull()
            } ?: return null
            return runCatching { JSONObject(raw).optString("name") }.getOrNull()?.takeIf { it.isNotBlank() }
        }
    }

    @Volatile
    private var appContext: Context? = null

    /** SDK 初始化/首次守门时挂 application context；幂等。 */
    fun attach(context: Context) {
        if (appContext == null) {
            synchronized(this) {
                if (appContext == null) appContext = context.applicationContext
            }
        }
    }

    /** 已挂的 application context（未 attach 时 null）。 */
    internal fun contextOrNull(): Context? = appContext

    private fun readConsent(appId: String): Consent {
        consentCache[appId]?.let { cached ->
            // 声明刷新（新版本安装/manifest 更新）时缓存会被 onManifest 覆写，这里直接信任缓存。
            return cached
        }
        val raw = runCatching { persistence.read(appId) }.getOrNull()
        val consent = parseConsent(raw)
        consentCache[appId] = consent
        return consent
    }

    private fun writeConsent(appId: String, consent: Consent) {
        consentCache[appId] = consent
        val payload = JSONObject()
            .put("scopes", consent.scopesJson)
            .put("network", JSONObject().put("domains", JSONArray(consent.networkDomains)))
        runCatching { persistence.write(appId, payload.toString()) }
            .onFailure { LogUtils.e(TAG, "persist consent failed for $appId: ${it.message}") }
    }

    internal fun parseConsent(raw: String?): Consent {
        val json = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
        val scopes = json?.optJSONObject("scopes") ?: JSONObject()
        val domains = json?.optJSONObject("network")?.optJSONArray("domains")
            ?.let { array -> (0 until array.length()).mapNotNull { array.opt(it) as? String } }
            ?: emptyList()
        return Consent(scopes, domains)
    }

    // ------------------------------------------------------------------
    // 声明（manifest.json permissions 段）
    // ------------------------------------------------------------------

    internal data class ScopeDeclaration(val scope: String, val desc: String)

    internal data class Declaration(
        val scopes: Map<String, ScopeDeclaration>,
        val domains: List<String>,
    )

    private val declarationCache = ConcurrentHashMap<String, Declaration>()

    /** 该 app 是否声明过指定 scope（守门第一道：无声明=诚实 fail）。 */
    internal fun isDeclared(appId: String, scope: String): Boolean = declaration(appId).scopes.containsKey(scope)

    internal fun declaredScopes(appId: String): Set<String> = declaration(appId).scopes.keys

    /** 弹窗文案：声明里的 usage/title（兼容 desc），无声明=null（调用方走通用文案）。 */
    internal fun scopeDesc(appId: String, scope: String): String? =
        declaration(appId).scopes[scope]?.desc?.takeIf { it.isNotBlank() }

    /** 声明的外网域名白名单（manifest network.domains；无声明=空=仅本机网关）。 */
    internal fun networkDomains(appId: String): List<String> = declaration(appId).domains

    private fun declaration(appId: String): Declaration {
        declarationCache[appId]?.let { return it }
        // 最新一次 manifest 拉取声明为空（老包/新包无段）= manifest 是权威，
        // 不再回落读 config.json 旧值（权限收回要即时生效）。
        if (manifestSuppressed.contains(appId)) {
            return Declaration(emptyMap(), emptyList())
        }
        val parsed = ManifestPermissionsParser.parse(declarationSource.readPermissions(appId))
        val declaration = parsed ?: Declaration(emptyMap(), emptyList())
        declarationCache[appId] = declaration
        return declaration
    }

    /**
     * manifest.json 拉取成功后的声明入口（RemoteUpdateManager 所有 fetch 路径都会调）。
     * permissions=null（老包无该段）= 清空声明。同时把 network.domains 镜像进 consent
     * JSON 的 network 段（契约 store 形态），宿主只读 state() 即可看到最新声明面。
     */
    internal fun onManifest(appId: String, permissions: JSONObject?) {
        val parsed = ManifestPermissionsParser.parse(permissions)
        if (parsed == null) {
            declarationCache.remove(appId)
            // manifest 声明为空（老包或新包无段）→ 抑制 config.json 回落，
            // 直到下一次带 permissions 段的 manifest 到达。
            manifestSuppressed.add(appId)
        } else {
            declarationCache[appId] = parsed
            manifestSuppressed.remove(appId)
            // 声明变更镜像进 consent store 的 network 段（scopes 段不动——授权记忆独立于声明）。
            val consent = readConsent(appId)
            if (consent.networkDomains != parsed.domains) {
                writeConsent(appId, consent.copy(networkDomains = parsed.domains))
            }
        }
        LogUtils.d(
            TAG,
            "onManifest($appId): ${parsed?.scopes?.keys ?: "no permissions section"} domains=${parsed?.domains ?: emptyList()}",
        )
    }

    /** 宿主展示名（config.json name；无则 appId）。弹窗标题用。 */
    internal fun displayName(appId: String): String =
        declarationSource.readName(appId)?.takeIf { it.isNotBlank() } ?: appId

    /** 卸载清数据时顺带清 consent（与 StorageApi.clearAllStorage 同一语义层）。 */
    internal fun clear(appId: String) {
        consentCache.remove(appId)
        declarationCache.remove(appId)
        manifestSuppressed.remove(appId)
        runCatching { persistence.clear(appId) }
    }

    private const val TAG = "MiniProgramPermissionStore"
}

/**
 * manifest.json / bundle.json 两种声明形状的宽容解析（wanqi manifest 形态为主）：
 *   manifest: {"scopes":[{"key","title","usage","mode"}...],"network":{"domains":[...]}}
 *   bundle:   {"scopes":[{"scope","desc"}...],"network":{"domains":[...]}}
 * scope key 取 key||scope；文案取 usage||desc||title。目录外的 scope 忽略（wanqi 侧
 * 严式校验拒装是第一道；容器对已装包宽容、不因坏声明崩运行时）。
 */
internal object ManifestPermissionsParser {
    fun parse(permissions: JSONObject?): MiniProgramPermissionStore.Declaration? {
        val root = permissions ?: return null
        val scopes = LinkedHashMap<String, MiniProgramPermissionStore.ScopeDeclaration>()
        val catalog = MiniProgramPermissionStore.SCOPE_CATALOG.keys
        root.optJSONArray("scopes")?.let { array ->
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                val scope = entry.optString("key", entry.optString("scope"))
                if (scope !in catalog) continue
                val desc = entry.optString("usage", entry.optString("desc", entry.optString("title")))
                scopes[scope] = MiniProgramPermissionStore.ScopeDeclaration(scope, desc)
            }
        }
        val domains = root.optJSONObject("network")?.optJSONArray("domains")
            ?.let { array -> (0 until array.length()).mapNotNull { array.opt(it) as? String } }
            ?: emptyList()
        if (scopes.isEmpty() && domains.isEmpty()) return null
        return MiniProgramPermissionStore.Declaration(scopes, domains)
    }
}
