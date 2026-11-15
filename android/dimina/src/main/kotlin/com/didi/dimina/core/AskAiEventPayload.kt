package com.didi.dimina.core

/**
 * 「问AI」事件 payload（2026-11-15 定稿）。
 *
 * 容器在「问AI浮窗」被点击时，经 [MiniProgramLifecycleListener.onAskAiInvoked]
 * （与 notifyFloated 同一条宿主生命周期链路）外发四个字段：
 *
 * - `appId`   当前小程序 appId
 * - `appName` 当前小程序名（包名为空时回落页面标题，再空回落「小程序」，与 ⋯菜单同源）
 * - `pageTitle` 当前页 navigationBarTitle；拿不到（未配置/空）为 `null`，不强造
 * - `pagePath`  当前页 path；拿不到（尚未 ready/空）为 `null`，不强造
 *
 * 归一化是纯函数，JVM 单测直接压 [normalizePageContext]。
 */
internal object AskAiEventPayload {

    /** 页面标题/路径 blank 一律归一 `null`（trim 后判空），不透传空串。 */
    fun normalizePageContext(title: String?, path: String?): Pair<String?, String?> {
        fun String?.orNullIfBlank(): String? =
            this?.trim()?.takeIf { it.isNotEmpty() }
        return title.orNullIfBlank() to path.orNullIfBlank()
    }
}
