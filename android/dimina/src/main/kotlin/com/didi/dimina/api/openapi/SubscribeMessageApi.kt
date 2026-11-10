package com.didi.dimina.api.openapi

import com.didi.dimina.api.APIResult
import com.didi.dimina.api.ApiRegistry
import com.didi.dimina.api.BaseApiHandler
import com.didi.dimina.api.NoneResult
import com.didi.dimina.api.ext.ExtCallback
import com.didi.dimina.common.ApiUtils
import com.didi.dimina.common.LogUtils
import com.didi.dimina.ui.container.DiminaActivity
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一次性模板消息订阅（微信兼容 API 面）。
 *
 * 框架职责只到「参数校验 + 桥给宿主 + 把宿主结果映射回微信形状」：
 * 弹面板、记账、投递都不在这里，也不直接依赖任何宿主实现。
 * 宿主通过 `Dimina.registerExtModule("dsh-notify", ...)` 注册 [ExtCallback] 处理器：
 *  - `showSubscribePanel(data={appId,tmplIds})` → success `{results:{tplId:'accept'|'reject'}}`
 *  - 非用户点击态 / 无宿主 → 走 fail（微信语义）。
 */
class SubscribeMessageApi(
    private val apiRegistry: ApiRegistry,
) : BaseApiHandler() {
    private companion object {
        const val REQUEST_SUBSCRIBE_MESSAGE = "requestSubscribeMessage"
        const val NOTIFY_MODULE = "dsh-notify"
        const val EVENT_SHOW_PANEL = "showSubscribePanel"
        const val MAX_TMPL_IDS_PER_REQUEST = 5
        const val RESULT_ACCEPT = "accept"
        const val RESULT_REJECT = "reject"
        const val RESULT_BAN = "ban"
        const val RESULT_FILTER = "filter"
        val VALID_RESULTS = setOf(RESULT_ACCEPT, RESULT_REJECT, RESULT_BAN, RESULT_FILTER)
        const val FAIL_INVALID_TMPL_IDS = "$REQUEST_SUBSCRIBE_MESSAGE:fail invalid tmplIds"
        const val FAIL_NOT_SUPPORTED = "$REQUEST_SUBSCRIBE_MESSAGE:fail not supported"
        const val FAIL_INVALID_RESULT = "$REQUEST_SUBSCRIBE_MESSAGE:fail invalid host result"
        const val FAIL_NON_TAP = "$REQUEST_SUBSCRIBE_MESSAGE:fail can only be invoked by user TAP gesture"
    }

    private val tag = "SubscribeMessageApi"

    override val apiNames = setOf(REQUEST_SUBSCRIBE_MESSAGE)

    override fun handleAction(
        activity: DiminaActivity,
        appId: String,
        apiName: String,
        params: JSONObject,
        responseCallback: (String) -> Unit,
    ): APIResult = when (apiName) {
        REQUEST_SUBSCRIBE_MESSAGE -> requestSubscribeMessage(appId, params, responseCallback)
        else -> super.handleAction(activity, appId, apiName, params, responseCallback)
    }

    private fun requestSubscribeMessage(
        appId: String,
        params: JSONObject,
        responseCallback: (String) -> Unit,
    ): APIResult {
        val tmplIds = parseTmplIds(params)
            ?: return emitFailure(FAIL_INVALID_TMPL_IDS, null, params, responseCallback)
        if (tmplIds.isEmpty() || tmplIds.size > MAX_TMPL_IDS_PER_REQUEST) {
            return emitFailure(FAIL_INVALID_TMPL_IDS, null, params, responseCallback)
        }

        val data = JSONObject().apply {
            put("appId", appId)
            put("tmplIds", JSONArray(tmplIds))
        }
        // 宿主可能同步或异步回调；同时 invokeExtModule 也可能报告「未注册」，
        // 两种失败路径都只能走到一次终态回调。
        val resolved = AtomicBoolean(false)
        val handled = apiRegistry.invokeExtModule(
            NOTIFY_MODULE,
            EVENT_SHOW_PANEL,
            data,
            object : ExtCallback {
                override fun onSuccess(result: JSONObject) {
                    if (!resolved.compareAndSet(false, true)) return
                    val payload = mapResults(tmplIds, result)
                    if (payload == null) {
                        LogUtils.e(tag, "showSubscribePanel returned an invalid result: $result")
                        emitFailure(FAIL_INVALID_RESULT, null, params, responseCallback)
                    } else {
                        ApiUtils.invokeSuccess(params, payload, responseCallback)
                        ApiUtils.invokeComplete(params, responseCallback, payload)
                    }
                }

                override fun onFail(error: JSONObject) {
                    if (!resolved.compareAndSet(false, true)) return
                    emitFailure(normalizeFailure(error), error, params, responseCallback)
                }
            },
        )
        if (!handled && resolved.compareAndSet(false, true)) {
            LogUtils.e(tag, "no host ext module registered for \"$NOTIFY_MODULE\"")
            emitFailure(FAIL_NOT_SUPPORTED, null, params, responseCallback)
        }
        return NoneResult()
    }

    /** tmplIds 必须是 string[]；非法形状返回 null（与微信的参数错误语义一致）。 */
    private fun parseTmplIds(params: JSONObject): List<String>? {
        val array = params.optJSONArray("tmplIds") ?: return null
        val ids = ArrayList<String>(array.length())
        for (index in 0 until array.length()) {
            val id = array.opt(index) as? String ?: return null
            if (id.isEmpty()) return null
            ids.add(id)
        }
        return ids
    }

    /**
     * 把宿主返回的 `{results:{tplId:'accept'|'reject'}}` 映射成微信形状
     * `{errMsg:'requestSubscribeMessage:ok', tplId:'accept'}`。
     * 宿主漏掉的模板按 `reject` 处理（绝不把缺失的同意当成 accept）；
     * 出现值域外的值则视为宿主契约被破坏，整次调用 fail。
     */
    private fun mapResults(tmplIds: List<String>, result: JSONObject): JSONObject? {
        val results = result.optJSONObject("results") ?: return null
        val payload = JSONObject().apply {
            put("errMsg", "$REQUEST_SUBSCRIBE_MESSAGE:ok")
        }
        tmplIds.forEach { tmplId ->
            val decision = results.opt(tmplId) as? String
            when {
                decision == null -> {
                    LogUtils.w(tag, "host omitted consent for $tmplId, treat as reject")
                    payload.put(tmplId, RESULT_REJECT)
                }
                decision !in VALID_RESULTS -> return null
                else -> payload.put(tmplId, decision)
            }
        }
        return payload
    }

    private fun normalizeFailure(error: JSONObject): String {
        val raw = error.optString("errMsg")
        if (raw.startsWith("$REQUEST_SUBSCRIBE_MESSAGE:fail")) return raw
        if (raw.isNotEmpty()) return "$REQUEST_SUBSCRIBE_MESSAGE:fail $raw"
        return FAIL_NON_TAP
    }

    private fun emitFailure(
        errMsg: String,
        source: JSONObject?,
        params: JSONObject,
        responseCallback: (String) -> Unit,
    ): APIResult {
        val result = JSONObject().apply {
            put("errMsg", errMsg)
            source?.opt("errCode")?.let { if (it != JSONObject.NULL) put("errCode", it) }
        }
        ApiUtils.invokeFail(params, result, responseCallback)
        ApiUtils.invokeComplete(params, responseCallback, result)
        return NoneResult()
    }
}
