package com.didi.dimina.core

/**
 * 宿主侧小程序生命周期事件（浮窗链路，2026-11-12）。
 *
 * - [onFloated]：⋯菜单「浮窗」把小程序整栈收后台（页面栈+运行时保留），
 *   宿主应显示浮窗球（带图标，点击可原地恢复）；
 * - [onForegrounded]：小程序任一页面回到前台（浮窗恢复/桌面重开均可），
 *   宿主应收起对应的浮窗球；
 * - [onRuntimeDestroyed]：运行时被销毁（真退出、后台超时回收、内存压力回收、
 *   宿主 close/uninstall），宿主应收起对应的浮窗球；
 * - [onAskAiInvoked]：「问AI浮窗」被点击（2026-11-15），宿主应打开问AI聊天
 *   并经 [com.didi.dimina.Dimina.floatCurrentMiniProgram] 把当前小程序收后台。
 *
 * 回调统一在主线程。传 null 注销。
 */
interface MiniProgramLifecycleListener {
    fun onFloated(appId: String, appName: String, iconPng: ByteArray?)
    fun onForegrounded(appId: String)
    fun onRuntimeDestroyed(appId: String)

    /**
     * 「问AI浮窗」被点击（2026-11-15）。容器在透传宿主 onClick 回调的同时经本方法
     * 外发事件，payload=(appId, appName, pageTitle, pagePath)；pageTitle=当前页
     * navigationBarTitle、pagePath=当前页 path，拿不到为 null（不强造）。
     * 默认空实现：存量监听器不接此事件零影响。
     */
    fun onAskAiInvoked(appId: String, appName: String, pageTitle: String?, pagePath: String?) {}
}
