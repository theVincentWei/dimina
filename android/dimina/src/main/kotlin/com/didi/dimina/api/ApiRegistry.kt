package com.didi.dimina.api

import com.didi.dimina.api.ext.ExtBridgeApi
import com.didi.dimina.api.ext.ExtCallback
import com.didi.dimina.api.ext.ExtModuleHandler
import com.didi.dimina.api.permission.MiniProgramPermissionStore
import com.didi.dimina.api.permission.PermLog
import com.didi.dimina.api.permission.PermissionFlow
import com.didi.dimina.api.permission.PermissionGate
import com.didi.dimina.common.ApiUtils
import com.didi.dimina.common.LogUtils
import com.didi.dimina.core.MiniApp
import com.didi.dimina.ui.container.DiminaActivity
import org.json.JSONObject

/**
 * API Registry to manage all API handlers
 * Author: Doslin
 */
class ApiRegistry {
    private val tag = "ApiRegistry"
    private val apiHandlers = mutableMapOf<String, ApiHandler>()

    // 宿主注册的第三方扩展模块，key=moduleName
    private val extModules = mutableMapOf<String, ExtModuleHandler>()

    // extBridge 一次性调用的处理器，共享 extModules 引用
    private val extBridgeApi by lazy { ExtBridgeApi(extModules) }

    // extOnBridge 持续订阅的取消函数。跨小程序栈共享 ApiRegistry，必须按 owner 隔离。
    private data class ExtSubscriptionKey(val appId: String, val eventKey: String)
    private val extSubscriptions = mutableMapOf<ExtSubscriptionKey, Runnable>()

    /**
     * Registers an API handler
     */
    fun register(name: String, handler: ApiHandler) {
        apiHandlers[name] = handler
    }

    /**
     * 注册第三方扩展 bridge 模块
     * @param moduleName 模块名，对应 extBridge/extOnBridge 的 module 参数
     * @param handler    模块处理器
     */
    fun registerExtModule(moduleName: String, handler: ExtModuleHandler) {
        extModules[moduleName] = handler
        LogUtils.d(tag, "Registered ext module: $moduleName")
    }

    /**
     * 供原生 API handler 主动回调宿主扩展模块（一次性调用）。
     *
     * extModules 本身是私有的，原生 API 需要「把用户交互/状态查询交给宿主」时走这里，
     * 而不是另开一份模块表。语义与 extBridge 的一次性调用一致：handler 处理完调用
     * [ExtCallback]，不改变 extBridge / extOnBridge 既有的注册与路由行为。
     *
     * @return 是否找到了该 module 的 handler。false 表示宿主未注册（调用方应按失败处理，
     *         调用方不要依赖 callback 一定被触发）。
     */
    fun invokeExtModule(
        module: String,
        event: String,
        data: JSONObject = JSONObject(),
        callback: ExtCallback,
    ): Boolean {
        val handler = extModules[module]
        if (handler == null) {
            LogUtils.e(tag, "invokeExtModule: module \"$module\" not registered")
            return false
        }
        return try {
            handler.handle(event, data, callback)
            true
        } catch (e: Exception) {
            LogUtils.e(tag, "invokeExtModule error ($module/$event): ${e.message}")
            false
        }
    }

    /**
     * Invokes an API
     *
     * @param apiName The name of the API to invoke
     * @param params Parameters for the API call
     * @return True if API was successfully invoked, false otherwise
     */
    fun invoke(
        activity: DiminaActivity,
        appId: String,
        apiName: String,
        params: JSONObject,
        responseCallback: (String) -> Unit,
    ): APIResult {
        // 0. 平台权限守门（契约 v1.0 §3/§4，2026-11-14 冻结）：
        //    wx.* 预分发闸——查声明 → 查授权 → 放行/弹窗/诚实 fail。
        //    豁免级（sensors/audio 播放/振动/storage/主题/UI/文件沙盒/局域网族等）
        //    不在映射表内零改动，勿在此加闸（详见 PermissionGate 头注）。
        permissionGate(activity, appId, apiName, params, responseCallback)?.let { return it }

        // 1. 优先命中已注册的标准 API
        apiHandlers[apiName]?.let { handler ->
            return handler.handleAction(activity, appId, apiName, params, responseCallback)
        }

        // 2. extBridge：未命中且 params 携带 "module" 字段
        if (params.has("module")) {
            return extBridgeApi.handleAction(activity, appId, apiName, params, responseCallback)
        }

        // 3. extOnBridge / extOffBridge：apiName 格式为 "${module}_${event}"
        val matchedModule = extModules.keys.firstOrNull { apiName.startsWith("${it}_") }
        if (matchedModule != null) {
            val event = apiName.removePrefix("${matchedModule}_")
            val successId = params.optString("success", "")
            return if (successId.isNotEmpty()) {
                handleExtOnBridge(
                    appId,
                    matchedModule,
                    event,
                    apiName,
                    params,
                    successId,
                    responseCallback,
                )
            } else {
                handleExtOffBridge(appId, apiName)
                NoneResult()
            }
        }

        // 4. 未知 API 显式 fail（审计缺陷②修复：原来静默 NoneResult，JS 回调永不触发；
        //    errMsg 与 web 侧 `:fail api is not supported` 对齐，JS 侧能收到 fail）。
        LogUtils.e(tag, "API not found: $apiName")
        return AsyncResult(JSONObject().apply { put("errMsg", "$apiName:fail api is not supported") })
    }

    /**
     * per-appid 平台权限预分发闸。返回 null=放行（继续原有分发）；
     * 返回非 null=本次调用被闸拦截/已转授权动线，调用方直接返回该结果。
     *
     * 判定顺序（契约 §3）：查声明（无声明=诚实 fail `xxx:fail permission undeclared`）
     * → 查授权（授权级未授权弹原生弹窗一次：拒绝=本次 fail auth deny 下次再弹，
     * 允许=落盘后放行）→ 放行（声明级/占位级声明过即放行，OS 级检查保留在 handler 内）。
     */
    private fun permissionGate(
        activity: DiminaActivity,
        appId: String,
        apiName: String,
        params: JSONObject,
        responseCallback: (String) -> Unit,
    ): APIResult? {
        MiniProgramPermissionStore.attach(activity.applicationContext)
        val scope = PermissionGate.apiScopeOf(apiName) ?: return null

        // camera 源甄别：chooseImage/chooseMedia/chooseVideo 纯相册路径不消耗 scope.camera。
        if (scope == PermissionGate.SCOPE_CAMERA && !PermissionGate.isCameraSource(params)) {
            PermLog.i("gate", "$appId $apiName pass (album-only source)")
            return null
        }

        // 网络声明级：本机网关恒放行；外网目标按 origin 比对 manifest network.domains。
        if (scope == PermissionGate.SCOPE_NETWORK) {
            if (PermissionGate.isUrlAllowed(appId, params.optString("url"))) {
                return null
            }
            val declared = MiniProgramPermissionStore.networkDomains(appId).isNotEmpty()
            val errMsg = if (declared) "$apiName:fail url not in domain list" else "$apiName:fail permission undeclared"
            PermLog.i("gate", "$appId $apiName fail: $errMsg")
            return AsyncResult(JSONObject().apply { put("errMsg", errMsg) })
        }

        val facts = PermissionGate.Facts(
            declared = MiniProgramPermissionStore.isDeclared(appId, scope),
            consentGranted = MiniProgramPermissionStore.consentRecord(appId, scope)?.granted,
            urlAllowed = true,
        )
        return when (val decision = PermissionGate.decide(apiName, PermissionGate.scopeLevel(scope), facts)) {
            PermissionGate.Decision.Pass -> {
                PermLog.i("gate", "$appId $apiName pass ($scope)")
                null
            }
            is PermissionGate.Decision.Fail -> {
                PermLog.i("gate", "$appId $apiName fail: ${decision.errMsg}")
                AsyncResult(JSONObject().apply { put("errMsg", decision.errMsg) })
            }
            PermissionGate.Decision.Ask -> {
                // 授权级未授权：弹原生弹窗一次（与 SettingApi.authorize 共用同一动线与记账）。
                PermLog.i("gate", "$appId $apiName ask ($scope)")
                PermissionFlow.ensureAuthorized(
                    activity,
                    appId,
                    scope,
                    onAllowed = {
                        // 允许已落盘，按原参数重新分发到真实 handler（再进闸时直接放行）。
                        MiniApp.getInstance().invokeAPI(
                            appId,
                            activity,
                            apiName,
                            params,
                            null,
                            responseCallback,
                        )
                    },
                    onDenied = {
                        val result = JSONObject().apply { put("errMsg", "$apiName:fail auth deny") }
                        ApiUtils.invokeFail(params, result, responseCallback)
                        ApiUtils.invokeComplete(params, responseCallback, result)
                    },
                )
                NoneResult()
            }
        }
    }

    /**
     * 处理 extOnBridge：启动持续订阅，保存取消函数
     */
    private fun handleExtOnBridge(
        appId: String,
        module: String,
        event: String,
        eventKey: String,
        params: JSONObject,
        successCallbackId: String,
        responseCallback: (String) -> Unit,
    ): APIResult {
        val handler = extModules[module] ?: return NoneResult()
        val subscriptionKey = ExtSubscriptionKey(appId, eventKey)

        // 若已有相同订阅，先取消旧的
        extSubscriptions.remove(subscriptionKey)?.run()

        val callback = object : com.didi.dimina.api.ext.ExtCallback {
            override fun onSuccess(result: JSONObject) {
                responseCallback(ApiUtils.createCallbackResponse(successCallbackId, result))
            }

            override fun onFail(error: JSONObject) {
                LogUtils.e(tag, "extOnBridge error ($eventKey): $error")
            }
        }

        val unsubscribe = handler.handle(event, JSONObject(), callback)
        if (unsubscribe != null) {
            extSubscriptions[subscriptionKey] = unsubscribe
        }
        return NoneResult()
    }

    /**
     * 处理 extOffBridge：取消持续订阅
     */
    private fun handleExtOffBridge(appId: String, eventKey: String) {
        extSubscriptions.remove(ExtSubscriptionKey(appId, eventKey))?.run()
        LogUtils.d(tag, "extOffBridge: cancelled subscription for $eventKey")
    }

    /** 取消指定小程序的持续订阅，不影响仍在返回栈中的 opener。 */
    fun clearExtSubscriptions(appId: String) {
        val owned = extSubscriptions.filterKeys { it.appId == appId }
        owned.keys.forEach(extSubscriptions::remove)
        owned.values.forEach { it.run() }
    }
    
    /**
     * Clears all API handlers
     */
    fun clear() {
        extSubscriptions.values.forEach { it.run() }
        extSubscriptions.clear()
        apiHandlers.clear()
    }
    
    /**
     * Gets a set of all registered API names
     * 
     * @return Set of all registered API names
     */
    fun getRegisteredApiNames(): Set<String> {
        return apiHandlers.keys
    }
}
