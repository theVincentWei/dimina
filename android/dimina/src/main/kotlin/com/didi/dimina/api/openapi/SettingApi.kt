package com.didi.dimina.api.openapi

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.didi.dimina.api.APIResult
import com.didi.dimina.api.ApiRegistry
import com.didi.dimina.api.AsyncResult
import com.didi.dimina.api.BaseApiHandler
import com.didi.dimina.api.NoneResult
import com.didi.dimina.api.ext.ExtCallback
import com.didi.dimina.api.permission.MiniProgramPermissionStore
import com.didi.dimina.api.permission.PermLog
import com.didi.dimina.api.permission.PermissionFlow
import com.didi.dimina.api.permission.PermissionGate
import com.didi.dimina.common.ApiUtils
import com.didi.dimina.common.LogUtils
import com.didi.dimina.ui.container.DiminaActivity
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * authorize / getSetting / openSetting。
 *
 * 契约 v1.0（2026-11-14 冻结）：授权态从「宿主 OS 全局权限位」改为「容器 per-appid 位在前，
 * OS 位在后」——授权级 scope 先过容器 consent（未授权弹容器原生弹窗，按 appId 落盘），
 * 容器放行后才落到 OS 运行时权限（未授权的 app 永远到不了系统弹窗；OS 位天然跨 app 是
 * Android 限制，文档如实声明）。支持面=冻结目录（scope.record/notification/contacts/
 * camera/writePhotosAlbum/bluetooth/clipboard/network.external）；scope.userLocation
 * v1 摘除（getLocation 未实现，调用诚实 fail not supported，不再空转装好人——审计缺陷①）。
 */
class SettingApi(
    private val apiRegistry: ApiRegistry? = null,
) : BaseApiHandler() {
    private companion object {
        const val GET_SETTING = "getSetting"
        const val OPEN_SETTING = "openSetting"
        const val AUTHORIZE = "authorize"
        const val NOTIFY_MODULE = "dsh-notify"
        const val EVENT_GET_SUBSCRIBE_STATE = "getSubscribeState"
        const val SCOPE_USER_LOCATION = "scope.userLocation"
        const val SCOPE_NETWORK = "network.external"
    }

    private val tag = "SettingApi"

    override val apiNames = setOf(GET_SETTING, OPEN_SETTING, AUTHORIZE)

    override fun handleAction(
        activity: DiminaActivity,
        appId: String,
        apiName: String,
        params: JSONObject,
        responseCallback: (String) -> Unit,
    ): APIResult = when (apiName) {
        GET_SETTING -> {
            emitSettingResult(activity, GET_SETTING, appId, params, responseCallback)
            NoneResult()
        }
        AUTHORIZE -> authorize(activity, appId, params, responseCallback)
        OPEN_SETTING -> openSetting(activity, appId, params, responseCallback)
        else -> super.handleAction(activity, appId, apiName, params, responseCallback)
    }

    private fun authorize(
        activity: DiminaActivity,
        appId: String,
        params: JSONObject,
        responseCallback: (String) -> Unit,
    ): APIResult {
        MiniProgramPermissionStore.attach(activity.applicationContext)
        val scope = params.optString("scope")

        // v1 诚实不可用：getLocation 未实现，userLocation 从支持面摘除（契约 §1，审计缺陷①）。
        if (scope == SCOPE_USER_LOCATION) {
            PermLog.i("authorize", "$appId $scope fail not supported")
            return AsyncResult(JSONObject().apply { put("errMsg", "$AUTHORIZE:fail not supported") })
        }
        val level = PermissionGate.scopeLevel(scope)
            ?: return AsyncResult(JSONObject().apply { put("errMsg", "$AUTHORIZE:fail invalid scope") }).also {
                PermLog.i("authorize", "$appId $scope fail invalid scope")
            }

        // 声明闸：未声明任何敏感能力/该 scope（含 network.domains 未声明）→ 诚实 fail。
        val declared = if (scope == SCOPE_NETWORK) {
            MiniProgramPermissionStore.networkDomains(appId).isNotEmpty()
        } else {
            MiniProgramPermissionStore.isDeclared(appId, scope)
        }
        if (!declared) {
            PermLog.i("authorize", "$appId $scope fail permission undeclared")
            return AsyncResult(JSONObject().apply { put("errMsg", "$AUTHORIZE:fail permission undeclared") })
        }

        fun failDeny() {
            PermLog.i("authorize", "$appId $scope fail auth deny")
            val result = JSONObject().apply { put("errMsg", "$AUTHORIZE:fail auth deny") }
            ApiUtils.invokeFail(params, result, responseCallback)
            ApiUtils.invokeComplete(params, responseCallback, result)
        }

        fun onConsented() {
            // OS 位在后：容器 consent 已拿到才触达 OS 运行时权限（无 OS 位的 scope 直接 ok）。
            val permissions = osPermissionsForScope(scope)
            if (permissions == null) {
                PermLog.i("authorize", "$appId $scope ok (container consent, no OS bit)")
                val result = JSONObject().apply { put("errMsg", "$AUTHORIZE:ok") }
                ApiUtils.invokeSuccess(params, result, responseCallback)
                ApiUtils.invokeComplete(params, responseCallback, result)
                return
            }
            activity.handleAuthorization(permissions) { granted ->
                PermLog.i("authorize", "$appId $scope os=$granted")
                val result = JSONObject().apply {
                    put("errMsg", if (granted) "$AUTHORIZE:ok" else "$AUTHORIZE:fail auth deny")
                }
                if (granted) ApiUtils.invokeSuccess(params, result, responseCallback)
                else ApiUtils.invokeFail(params, result, responseCallback)
                ApiUtils.invokeComplete(params, responseCallback, result)
            }
        }

        if (level == MiniProgramPermissionStore.LEVEL_AUTHORIZED) {
            // 授权级：容器弹窗动线（拒绝=本次 fail auth deny 下次再弹；允许=落盘后走 OS）。
            PermissionFlow.ensureAuthorized(activity, appId, scope, { onConsented() }, { failDeny() })
            return NoneResult()
        }
        // 声明级/占位级：声明过即可走授权入口（OS 位在后），无容器弹窗。
        onConsented()
        return NoneResult()
    }

    private fun openSetting(
        activity: DiminaActivity,
        appId: String,
        params: JSONObject,
        responseCallback: (String) -> Unit,
    ): APIResult {
        val launched = activity.handleOpenAppSettings {
            emitSettingResult(activity, OPEN_SETTING, appId, params, responseCallback)
        }
        if (!launched) {
            return AsyncResult(JSONObject().apply { put("errMsg", "$OPEN_SETTING:fail settings already open") }, completeCarriesResult = true)
        }
        return NoneResult()
    }

    /**
     * getSetting / openSetting 的结果里带上 `subscriptionsSetting`（微信兼容形状）：
     * `{mainSwitch, itemSettings:{tplId:'accept'|'reject'}}`。状态属于宿主，框架只在
     * 宿主已注册 `dsh-notify` 扩展时读取；无宿主时给默认值（总开关开、无模板记录），
     * 保证独立运行 Dimina 时 getSetting 仍然可用。
     */
    private fun emitSettingResult(
        activity: DiminaActivity,
        apiName: String,
        appId: String,
        params: JSONObject,
        responseCallback: (String) -> Unit,
    ) {
        MiniProgramPermissionStore.attach(activity.applicationContext)
        val result = settingResult(activity, appId, apiName)
        val registry = apiRegistry
        if (registry == null) {
            result.put("subscriptionsSetting", defaultSubscriptionsSetting())
            ApiUtils.invokeSuccess(params, result, responseCallback)
            ApiUtils.invokeComplete(params, responseCallback, result)
            return
        }

        // 宿主可能同步回调；不论同步/异步/未注册，终态回调只发一次。
        val resolved = AtomicBoolean(false)
        fun finish(state: JSONObject?) {
            if (!resolved.compareAndSet(false, true)) return
            result.put("subscriptionsSetting", state ?: defaultSubscriptionsSetting())
            ApiUtils.invokeSuccess(params, result, responseCallback)
            ApiUtils.invokeComplete(params, responseCallback, result)
        }

        val handled = registry.invokeExtModule(
            NOTIFY_MODULE,
            EVENT_GET_SUBSCRIBE_STATE,
            // appId 用于宿主按小程序取模板级 itemSettings；总开关 mainSwitch 是用户级。
            JSONObject().apply { put("appId", appId) },
            object : ExtCallback {
                override fun onSuccess(result: JSONObject) {
                    finish(normalizeSubscriptionsSetting(result))
                }

                override fun onFail(error: JSONObject) {
                    LogUtils.w(tag, "getSubscribeState failed: $error")
                    finish(null)
                }
            },
        )
        if (!handled) finish(null)
    }

    private fun defaultSubscriptionsSetting(): JSONObject = JSONObject().apply {
        put("mainSwitch", true)
        put("itemSettings", JSONObject())
    }

    private fun normalizeSubscriptionsSetting(source: JSONObject): JSONObject {
        // 宿主按契约直接返回 {mainSwitch,itemSettings}；兼容包了一层 subscriptionsSetting 的实现。
        val state = source.optJSONObject("subscriptionsSetting") ?: source
        return JSONObject().apply {
            put("mainSwitch", if (state.has("mainSwitch")) state.optBoolean("mainSwitch", true) else true)
            put("itemSettings", state.optJSONObject("itemSettings") ?: JSONObject())
        }
    }

    /**
     * getSetting 的 authSetting = 容器 per-appid 位在前、OS 位在后（wx 语义：反映小程序
     * 当前是否真正可用该能力）：
     *  - scope.record = 容器 consent 且 OS 麦克风已授（OS 被系统回收时如实 false）；
     *  - scope.notification = 容器 consent（OS 通知位归宿主通知链路管）；
     *  - 声明级（contacts/network.external）= 已声明（contacts 另要求 OS 位）；
     *  - 占位级 = 已声明且 OS 位已授（无 OS 位的 clipboard = 已声明）。
     * scope.userLocation 摘除（契约 §1：v1 诚实不可用，不再出现在 authSetting）。
     */
    private fun settingResult(activity: DiminaActivity, appId: String, apiName: String): JSONObject = JSONObject().apply {
        val authSetting = JSONObject()
        val declared = { scope: String -> MiniProgramPermissionStore.isDeclared(appId, scope) }
        val consented = { scope: String ->
            MiniProgramPermissionStore.consentRecord(appId, scope)?.granted == true
        }
        MiniProgramPermissionStore.SCOPE_CATALOG.forEach { (scope, level) ->
            val value = when {
                scope == SCOPE_USER_LOCATION -> return@forEach // 摘除：不出现在 authSetting
                level == MiniProgramPermissionStore.LEVEL_AUTHORIZED && scope == "scope.record" ->
                    consented(scope) && osPermissionsForScope(scope)?.all {
                        ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
                    } != false
                level == MiniProgramPermissionStore.LEVEL_AUTHORIZED -> consented(scope)
                scope == SCOPE_NETWORK -> MiniProgramPermissionStore.networkDomains(appId).isNotEmpty()
                scope == "scope.contacts" -> declared(scope) && (
                    ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
                    )
                level == MiniProgramPermissionStore.LEVEL_PLACEHOLDER -> {
                    if (!declared(scope)) false
                    else osPermissionsForScope(scope)?.all {
                        ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
                    } ?: true
                }
                else -> false
            }
            authSetting.put(scope, value)
        }
        put("authSetting", authSetting)
        put("errMsg", "$apiName:ok")
    }

    /**
     * authorize 的 OS 跟随权限（容器位拿到之后才请求）。null=该 scope 无 OS 运行时位，
     * 容器 consent 即终态（notification/clipboard/network.external）。
     */
    private fun osPermissionsForScope(scope: String): Array<String>? = when (scope) {
        "scope.camera" -> arrayOf(Manifest.permission.CAMERA)
        "scope.record" -> arrayOf(Manifest.permission.RECORD_AUDIO)
        "scope.contacts" -> arrayOf(Manifest.permission.WRITE_CONTACTS)
        "scope.bluetooth" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else arrayOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        "scope.writePhotosAlbum" -> if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else emptyArray()
        // notification / clipboard / network.external：无 OS 运行时位，null=容器位即终态。
        else -> null
    }
}
