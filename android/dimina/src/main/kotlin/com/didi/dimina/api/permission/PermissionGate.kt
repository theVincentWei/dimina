package com.didi.dimina.api.permission

import com.didi.dimina.api.device.BluetoothApi
import org.json.JSONObject

/**
 * wx.* → scope 守门映射 + 判定核心（契约 §4 冻结映射表，2026-11-14）。
 *
 * 判定顺序（契约 §3）：查声明 → 查授权 → 放行/弹窗/fail。
 *  - 无声明（manifest.json permissions 段没有该 scope）→ 诚实 fail `xxx:fail permission undeclared`；
 *  - 授权级已授权 → 放行；未授权 → 弹原生弹窗一次（拒绝=本次 fail 下次再弹，允许=落盘）；
 *  - 声明级/占位级声明过 → 放行（系统 UI/OS 权限语义留在各自 handler 内）；
 *  - scope.userLocation → v1 诚实不可用，一律 fail `not supported`（不再空转装好人）。
 *
 * **豁免级（契约 §1，勿在此加闸）**：sensors（SensorApi 四通道）、audio 播放（InnerAudio.*）、
 * 振动（vibrateShort/Long）、storage 族、主题/UI 类（showToast/showModal/navigation…）、
 * 应用自身文件沙盒（FileSystemManager/文件 API）、局域网发现/UDP/TCP（LocalNetworkApi）、
 * makePhoneCall、setKeepScreenOn、setClipboardData、previewImage/getImageInfo 等无权限语义
 * 的 API——零改动零拦截，防止后续误把豁免面接进守门。
 */
internal object PermissionGate {

    /** 守门内部引用的两个目录 key（ApiRegistry 网络域/相机源甄别用）。 */
    internal const val SCOPE_CAMERA = "scope.camera"
    internal const val SCOPE_NETWORK = "network.external"

    /** getLocation/onLocationChange 族（v1 全部诚实 fail not supported；含 iOS 才有的模糊定位名）。 */
    private val LOCATION_FAMILY = setOf(
        "getLocation", "getFuzzyLocation", "chooseLocation", "openLocation",
        "startLocationUpdate", "stopLocationUpdate",
        "startFuzzyLocationUpdate", "stopFuzzyLocationUpdate",
        "onLocationChange", "offLocationChange", "offLocationChangeError",
        "onFuzzyLocationChange", "offFuzzyLocationChange",
    )

    // ------------------------------------------------------------------
    // 冻结映射表（契约 §4）。改动先改契约文档再动这里。
    // ------------------------------------------------------------------

    private val API_SCOPE_MAP: Map<String, String> = buildMap {
        // 授权级：scope.record —— 录音族（Recorder.* 全前缀兜底见 apiScopeOf）
        put("getRecorderManager", ScopeKey.RECORD)
        // 授权级：scope.notification —— 订阅消息族（todo-reminder 链路，走通知面板）
        put("requestSubscribeMessage", ScopeKey.NOTIFICATION)
        // 占位级：scope.camera —— 相机源 picker + 扫码（album 源见 sourceType 豁免）
        put("chooseMedia", ScopeKey.CAMERA)
        put("chooseVideo", ScopeKey.CAMERA)
        put("chooseImage", ScopeKey.CAMERA)
        put("scanCode", ScopeKey.CAMERA)
        // 占位级：scope.writePhotosAlbum
        put("saveImageToPhotosAlbum", ScopeKey.WRITE_PHOTOS_ALBUM)
        put("saveVideoToPhotosAlbum", ScopeKey.WRITE_PHOTOS_ALBUM)
        // 占位级：scope.bluetooth —— BLE 全族
        BluetoothApi.SUPPORTED_API_NAMES.forEach { put(it, ScopeKey.BLUETOOTH) }
        // 占位级：scope.clipboard（读剪贴板；写不进目录——契约表只列 get）
        put("getClipboardData", ScopeKey.CLIPBOARD)
        // 声明级：scope.contacts（系统 UI 自带确认）
        put("addPhoneContact", ScopeKey.CONTACTS)
        put("chooseContact", ScopeKey.CONTACTS)
        // v1 不可用：scope.userLocation —— getLocation/onLocationChange 族诚实 fail not supported
        LOCATION_FAMILY.forEach { put(it, ScopeKey.USER_LOCATION) }
        // 声明级：network.domains（本机网关恒放行）
        put("request", ScopeKey.NETWORK)
        put("downloadFile", ScopeKey.NETWORK)
        put("uploadFile", ScopeKey.NETWORK)
        put("connectSocket", ScopeKey.NETWORK)
    }

    /** scope 目录 key（与 MiniProgramPermissionStore.SCOPE_CATALOG 对齐）。 */
    private object ScopeKey {
        const val RECORD = "scope.record"
        const val NOTIFICATION = "scope.notification"
        const val CAMERA = "scope.camera"
        const val WRITE_PHOTOS_ALBUM = "scope.writePhotosAlbum"
        const val BLUETOOTH = "scope.bluetooth"
        const val CLIPBOARD = "scope.clipboard"
        const val CONTACTS = "scope.contacts"
        const val USER_LOCATION = "scope.userLocation"
        const val NETWORK = "network.external"
    }

    /** 本机网关 host：契约「缺省=仅本机网关」「本机网关恒放行」。 */
    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "[::1]")

    /** 判定所需的外部事实（由调用方从 store/params 取好喂进来，纯函数便于单测）。 */
    internal data class Facts(
        val declared: Boolean,
        /** 授权级 consent 位：true=已授权；false=显式拒绝位（宿主管理页置的拒绝）；null=从未落盘。 */
        val consentGranted: Boolean?,
        /** 仅 network 域用：目标 url 是否被声明覆盖（loopback 恒 true）。 */
        val urlAllowed: Boolean,
    )

    internal sealed class Decision {
        /** 放行：继续原有分发（handler 的 OS 级检查保留）。 */
        object Pass : Decision()

        /** 显式 fail（errMsg 已带 `apiName:fail …`）。 */
        data class Fail(val errMsg: String) : Decision()

        /** 未授权的授权级：弹一次原生弹窗（拒绝 fail auth deny / 允许落盘后放行）。 */
        object Ask : Decision()
    }

    /** 纯判定：给定事实返回 Pass/Fail/Ask。 */
    internal fun decide(apiName: String, level: String?, facts: Facts): Decision {
        if (level == null) return Decision.Pass
        if (level == MiniProgramPermissionStore.LEVEL_UNSUPPORTED) {
            return Decision.Fail("$apiName:fail not supported")
        }
        if (!facts.declared) {
            return Decision.Fail("$apiName:fail permission undeclared")
        }
        return when (level) {
            MiniProgramPermissionStore.LEVEL_AUTHORIZED -> when (facts.consentGranted) {
                true -> Decision.Pass
                false -> Decision.Fail("$apiName:fail auth deny")
                null -> Decision.Ask
            }
            // 声明级/占位级：声明过即放行，不做运行时弹窗（契约 §1）。
            else -> Decision.Pass
        }
    }

    /**
     * API 名 → 目录 scope（null=不归守门管）。精确表 + Recorder 前缀规则：
     * 录音**操作族**（start/pause/resume/stop）闸 scope.record；onXxx/offXxx 是纯事件
     * 订阅（无 OS/隐私面，onLoad 注册即调），放行不弹窗（微信语义：点 start 才授权；
     * 2026-11-14 214 真机教训：前缀全闸会把 onLoad 的事件注册也弹窗）。
     */
    internal fun apiScopeOf(apiName: String): String? {
        API_SCOPE_MAP[apiName]?.let { return it }
        if (apiName.startsWith("Recorder.")) {
            return when (apiName.substringAfter('.')) {
                "start", "pause", "resume", "stop" -> ScopeKey.RECORD
                else -> null
            }
        }
        return null
    }

    internal fun scopeLevel(scope: String): String? = MiniProgramPermissionStore.SCOPE_CATALOG[scope]

    /**
     * camera 源甄别：chooseImage/chooseMedia/chooseVideo 仅 sourceType 含 camera 才算
     * 相机用途（契约表「camera 源」）；纯相册路径走系统 Photo Picker/文件选择器，
     * 不消耗 scope.camera 声明。sourceType 缺省按微信默认 ['album','camera'] = 相机源。
     */
    internal fun isCameraSource(params: JSONObject): Boolean {
        val sourceType = params.optJSONArray("sourceType") ?: return true
        if (sourceType.length() == 0) return true
        for (index in 0 until sourceType.length()) {
            if (sourceType.optString(index) == "camera") return true
        }
        return false
    }

    /**
     * 网络白名单判定：本机网关恒放行（契约 §4）；其余目标按 origin 精确比对声明
     * domains（wanqi 侧保证每条都是 http(s)://origin 形状）。
     */
    internal fun isUrlAllowed(appId: String, url: String): Boolean {
        val origin = urlOrigin(url) ?: return false
        if (isLoopback(origin)) return true
        return MiniProgramPermissionStore.networkDomains(appId).any { it == origin }
    }

    internal fun isLoopback(origin: String): Boolean {
        // origin 形如 https://127.0.0.1:7610
        val host = origin.substringAfter("://", "").substringBefore('/')
            .substringBefore(':').removeSurrounding("[", "]")
        return host in LOOPBACK_HOSTS
    }

    /** 取 scheme://host[:port] origin；非 http(s)/ws(s) 或畸形 url 返回 null（不放行）。 */
    internal fun urlOrigin(url: String): String? {
        val trimmed = url.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        if (scheme !in setOf("http", "https", "ws", "wss")) return null
        val rest = trimmed.substring(schemeEnd + 3)
        val hostPort = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (hostPort.isEmpty()) return null
        return "$scheme://$hostPort"
    }
}

/**
 * 占位级 scope 的 OS 权限位读取（state() 用：声明过且 OS 已授才算生效）。
 * 守门主链路不查这些位——OS 弹窗语义保留在各 handler 内（契约 §3 缺陷①修复的同款分层：
 * 容器 per-appid 位在前，OS 位在后）。
 */
internal object PlaceholderOsGate {
    fun isOsGranted(scope: String): Boolean {
        val context = MiniProgramPermissionStore.contextOrNull() ?: return false
        return runCatching {
            when (scope) {
                "scope.camera" -> granted(context, "android.permission.CAMERA")
                "scope.bluetooth" ->
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        granted(context, "android.permission.BLUETOOTH_SCAN") &&
                            granted(context, "android.permission.BLUETOOTH_CONNECT")
                    } else {
                        granted(context, "android.permission.ACCESS_FINE_LOCATION")
                    }
                "scope.writePhotosAlbum" ->
                    android.os.Build.VERSION.SDK_INT > android.os.Build.VERSION_CODES.P ||
                        granted(context, "android.permission.WRITE_EXTERNAL_STORAGE")
                // 剪贴板无 OS 运行时位（Android 10+ 后台限制是系统行为，非授权位）。
                "scope.clipboard" -> true
                else -> true
            }
        }.getOrDefault(false)
    }

    private fun granted(context: android.content.Context, permission: String): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
}
