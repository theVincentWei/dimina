package com.didi.dimina.api.permission

import android.app.Activity

/**
 * 授权级 scope 的授权动线（契约 §3）：查 consent → 未落盘则弹一次原生弹窗 →
 * 允许=落盘放行；拒绝=本次 fail、下次再弹（拒绝不落盘）；宿主管理页显式拒绝位
 * （set(false)）=不再弹窗直接 fail。
 *
 * 供两处消费：ApiRegistry 预分发闸（wx.* 直调）与 SettingApi.authorize（显式授权入口），
 * 两者共享同一弹窗与记账。
 */
internal object PermissionFlow {

    /** 弹窗标题谓语（拼在「"应用名"」后面）。 */
    internal fun permissionTitle(scope: String): String = when (scope) {
        "scope.record" -> "想使用你的麦克风"
        "scope.notification" -> "想给你发送通知"
        "scope.camera" -> "想使用你的相机"
        "scope.writePhotosAlbum" -> "想保存内容到你的相册"
        "scope.bluetooth" -> "想使用你的蓝牙"
        "scope.clipboard" -> "想读取你的剪贴板"
        "scope.contacts" -> "想访问你的通讯录"
        else -> "想获取相关权限"
    }

    /** 老包无声明文案时的 scope 通用说明。 */
    internal fun genericDesc(scope: String): String = when (scope) {
        "scope.record" -> "用于录音和语音输入"
        "scope.notification" -> "用于接收小程序的通知消息"
        "scope.camera" -> "用于拍摄照片和视频"
        "scope.writePhotosAlbum" -> "用于保存图片或视频到相册"
        "scope.bluetooth" -> "用于发现和连接蓝牙设备"
        "scope.clipboard" -> "用于获取你复制的内容"
        "scope.contacts" -> "用于添加或选择联系人"
        else -> "用于提供对应功能"
    }

    /** 弹窗 desc：manifest 声明里的 usage 优先，老包走通用文案（契约 §3）。 */
    internal fun descFor(appId: String, scope: String): String =
        MiniProgramPermissionStore.scopeDesc(appId, scope) ?: genericDesc(scope)

    /**
     * 确保 [scope] 已授权（per-appid consent）。已授权/允许后调 [onAllowed]；
     * 显式拒绝位或弹窗拒绝/弹窗被占调 [onDenied]（调用方自行 fail 本次调用）。
     * 允许落盘在 [onAllowed] 之前完成（放行后的 redispatch 不再二次弹窗）。
     */
    fun ensureAuthorized(
        activity: Activity,
        appId: String,
        scope: String,
        onAllowed: () -> Unit,
        onDenied: () -> Unit,
    ) {
        when (MiniProgramPermissionStore.consentRecord(appId, scope)?.granted) {
            true -> {
                PermLog.i("flow", "$appId $scope consented, proceed")
                onAllowed()
                return
            }
            false -> {
                // 宿主管理页显式拒绝位：不再弹窗（管理页是用户的最终意志）。
                PermLog.i("flow", "$appId $scope explicitly revoked, deny")
                onDenied()
                return
            }
            null -> Unit // 未落盘：走弹窗
        }
        if (PermissionDialog.isBusy) {
            // 已有授权弹窗在屏：本次调用先 fail，用户重新触发会再次弹（契约「下次再弹」）。
            PermLog.i("flow", "$appId $scope dialog busy, deny this call")
            onDenied()
            return
        }
        val appName = MiniProgramPermissionStore.displayName(appId)
        PermLog.i("flow", "$appId $scope show dialog (app=$appName desc=${descFor(appId, scope)})")
        PermissionDialog.show(
            activity = activity,
            appId = appId,
            scope = scope,
            appName = appName,
            permissionTitle = permissionTitle(scope),
            desc = descFor(appId, scope),
            onAllow = {
                MiniProgramPermissionStore.recordGrant(appId, scope)
                onAllowed()
            },
            onDeny = onDenied,
        )
    }
}
