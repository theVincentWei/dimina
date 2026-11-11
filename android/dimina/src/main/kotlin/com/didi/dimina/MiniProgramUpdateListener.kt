package com.didi.dimina

/**
 * 宿主侧小程序升级事件监听。
 *
 * 升级的检测、下载与换包生效全部由框架（宿主 + 容器）负责，并采用「强制自动 + 仅告知」
 * 策略（契约 §6.4）：检测到新版本后**不询问用户**，pending 包在下次冷启动时由容器静默
 * 激活；运行中不中途重启。小程序自身不参与升级（wx.getUpdateManager 的 JS 事件总线
 * 仍保留，但只是向后兼容）。宿主用 [onUpdateApplied] 的版本号发本地告知通知。
 *
 * 所有回调都在主线程。
 */
interface MiniProgramUpdateListener {
    /** 新包已下载并校验完毕，将在下次打开时静默生效；容器不弹确认框。 */
    fun onUpdateReady(appId: String, versionCode: Int, versionName: String) {}

    /**
     * 不再触发（v1.8.1 起 Bundle 升级强制自动，没有「暂不升级」路径）。
     * 仅为宿主既有实现的源码/二进制兼容保留该默认方法。
     */
    fun onUpdateDeclined(appId: String, versionCode: Int) {}

    /** pending 包已静默激活为当前版本（冷启动前换包；运行中不中途重启）。 */
    fun onUpdateApplied(appId: String, versionCode: Int, versionName: String) {}

    /** manifest 拉取/下载/校验/安装失败。 */
    fun onUpdateFailed(appId: String, message: String?) {}

    /** 首装完成（无本地包时直接从 manifest 装上的那条阻塞路径）。 */
    fun onInstalled(appId: String, versionCode: Int, versionName: String) {}
}
