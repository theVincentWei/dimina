package com.didi.dimina.core

/**
 * 宿主侧小程序生命周期事件（浮窗链路，2026-11-12）。
 *
 * - [onFloated]：⋯菜单「浮窗」把小程序整栈收后台（页面栈+运行时保留），
 *   宿主应显示浮窗球（带图标，点击可原地恢复）；
 * - [onForegrounded]：小程序任一页面回到前台（浮窗恢复/桌面重开均可），
 *   宿主应收起对应的浮窗球；
 * - [onRuntimeDestroyed]：运行时被销毁（真退出、后台超时回收、内存压力回收、
 *   宿主 close/uninstall），宿主应收起对应的浮窗球。
 *
 * 回调统一在主线程。传 null 注销。
 */
interface MiniProgramLifecycleListener {
    fun onFloated(appId: String, appName: String, iconPng: ByteArray?)
    fun onForegrounded(appId: String)
    fun onRuntimeDestroyed(appId: String)
}
