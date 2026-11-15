package com.didi.dimina.common

import android.content.Context
import android.content.SharedPreferences

/**
 * 「问AI浮窗」显隐开关的持久化（2026-11-15 定稿）。
 *
 * 全局单 key（[KEY_ENABLED]），不按 appId 分账：浮窗是宿主级产品入口，长按 ✕ 关闭
 * 与 ⋯菜单「问AI浮窗」瓦片关闭写同一状态，对所有小程序一致。落 SharedPreferences
 * 直存（不经 MMKV/StoreUtils）：读取发生在 Dimina.init 最早期，键名对宿主可读、
 * 零第三方依赖；`off` 后本次会话不再挂浮窗，直到宿主或瓦片重新打开。
 *
 * 读写在 JVM 单测不可实例化（android.jar 桩），故 read/write 的核心重载直接收
 * [SharedPreferences]，测试注入内存实现即可压真实读写路径。
 */
object AskAiFloatStore {

    /** 全局 key（规格定稿字面量，勿改：宿主排障/回归都按它找）。 */
    const val KEY_ENABLED = "dimina_ask_ai_float_enabled"

    /** prefs 文件名（仅容器内部用；规格只约束 key）。 */
    const val PREFS_NAME = "dimina_ask_ai_prefs"

    /** 默认开启：进入小程序即挂「问AI浮窗」。 */
    const val DEFAULT_ENABLED = true

    fun readEnabled(context: Context): Boolean =
        readEnabled(prefs(context))

    fun writeEnabled(context: Context, enabled: Boolean) {
        writeEnabled(prefs(context), enabled)
    }

    fun readEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun writeEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
