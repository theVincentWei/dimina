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
import com.didi.dimina.common.ApiUtils
import com.didi.dimina.common.LogUtils
import com.didi.dimina.ui.container.DiminaActivity
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class SettingApi(
    private val apiRegistry: ApiRegistry? = null,
) : BaseApiHandler() {
    private companion object {
        const val GET_SETTING = "getSetting"
        const val OPEN_SETTING = "openSetting"
        const val AUTHORIZE = "authorize"
        const val NOTIFY_MODULE = "dsh-notify"
        const val EVENT_GET_SUBSCRIBE_STATE = "getSubscribeState"
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
        AUTHORIZE -> authorize(activity, params, responseCallback)
        OPEN_SETTING -> openSetting(activity, appId, params, responseCallback)
        else -> super.handleAction(activity, appId, apiName, params, responseCallback)
    }

    private fun authorize(
        activity: DiminaActivity,
        params: JSONObject,
        responseCallback: (String) -> Unit,
    ): APIResult {
        val scope = params.optString("scope")
        val permissions = permissionsForScope(scope)
            ?: return AsyncResult(JSONObject().apply { put("errMsg", "$AUTHORIZE:fail invalid scope") }, completeCarriesResult = true)
        activity.handleAuthorization(permissions) { granted ->
            val result = JSONObject().apply {
                put("errMsg", if (granted) "$AUTHORIZE:ok" else "$AUTHORIZE:fail auth deny")
            }
            if (granted) ApiUtils.invokeSuccess(params, result, responseCallback)
            else ApiUtils.invokeFail(params, result, responseCallback)
            ApiUtils.invokeComplete(params, responseCallback, result)
        }
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
        val result = settingResult(activity, apiName)
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

    private fun settingResult(activity: DiminaActivity, apiName: String): JSONObject = JSONObject().apply {
        val authSetting = JSONObject()
        supportedScopes().forEach { scope ->
            val permissions = permissionsForScope(scope).orEmpty()
            authSetting.put(scope, permissions.all {
                ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
            })
        }
        put("authSetting", authSetting)
        put("errMsg", "$apiName:ok")
    }

    private fun supportedScopes(): List<String> = listOf(
        "scope.camera",
        "scope.record",
        "scope.userLocation",
        "scope.addPhoneContact",
        "scope.bluetooth",
        "scope.writePhotosAlbum",
    )

    private fun permissionsForScope(scope: String): Array<String>? = when (scope) {
        "scope.camera" -> arrayOf(Manifest.permission.CAMERA)
        "scope.record" -> arrayOf(Manifest.permission.RECORD_AUDIO)
        "scope.userLocation" -> arrayOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        "scope.addPhoneContact" -> arrayOf(Manifest.permission.WRITE_CONTACTS)
        "scope.bluetooth" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else arrayOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        "scope.writePhotosAlbum" -> if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else emptyArray()
        else -> null
    }
}
