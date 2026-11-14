package com.didi.dimina.api.permission

import com.didi.dimina.common.LogUtils
import java.io.File

/**
 * 守门链路的 release 可观测日志：常开（不受 debugMode 门控），落
 * `filesDir/dimina_perm_log.txt`，超限砍半保留后半（环形近似）。
 *
 * 背景：release 包 LogUtils 按 FLAG_DEBUGGABLE 门控后，真机上的守门判定/弹窗/记账
 * 全是黑盒（2026-11-14 task-4 真机排查教训）。权限是敏感面，它的一条 fail 决策必须
 * 事后可查证。读取：adb root shell `cat /data/data/<宿主>/files/dimina_perm_log.txt`。
 * 同时同步一份 LogUtils（debugMode 开时走 logcat）。
 */
internal object PermLog {
    private const val FILE_NAME = "dimina_perm_log.txt"
    private const val MAX_BYTES = 64 * 1024
    private const val TAG = "PermLog"

    fun i(event: String, detail: String) {
        val line = "${System.currentTimeMillis()} $event $detail"
        LogUtils.d(TAG, line)
        runCatching {
            val context = MiniProgramPermissionStore.contextOrNull() ?: return
            synchronized(this) {
                val file = File(context.filesDir, FILE_NAME)
                if (file.length() > MAX_BYTES) {
                    val text = file.readText()
                    file.writeText(text.substring(text.length / 2))
                }
                file.appendText("$line\n")
            }
        }
    }
}
