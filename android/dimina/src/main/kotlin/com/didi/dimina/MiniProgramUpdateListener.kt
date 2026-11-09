package com.didi.dimina

/**
 * 宿主侧小程序升级事件监听。
 *
 * 升级的检测、下载、用户确认与换包生效全部由框架（宿主 + 容器）负责：容器在后台
 * 检查到新版本并准备好 pending 包后弹窗问用户，确认后才 activate。小程序自身不参与
 * 升级（wx.getUpdateManager 的 JS 事件总线仍保留，但只是向后兼容）。
 *
 * 所有回调都在主线程。
 */
interface MiniProgramUpdateListener {
    /** 新包已下载并校验完毕，等用户在容器弹窗里确认。 */
    fun onUpdateReady(appId: String, versionCode: Int, versionName: String) {}

    /** 用户在确认弹窗里选择了暂不升级（保留 pending 包，下次打开再问）。 */
    fun onUpdateDeclined(appId: String, versionCode: Int) {}

    /** 用户确认升级且 pending 包已激活为当前版本（容器随后冷重启）。 */
    fun onUpdateApplied(appId: String, versionCode: Int, versionName: String) {}

    /** manifest 拉取/下载/校验/安装失败。 */
    fun onUpdateFailed(appId: String, message: String?) {}

    /** 首装完成（无本地包时直接从 manifest 装上的那条阻塞路径）。 */
    fun onInstalled(appId: String, versionCode: Int, versionName: String) {}
}
