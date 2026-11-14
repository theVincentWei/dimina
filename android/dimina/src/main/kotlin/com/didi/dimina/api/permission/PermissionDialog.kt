package com.didi.dimina.api.permission

import android.app.Activity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.ComposeView
import com.didi.dimina.Dimina

/**
 * 授权级 scope 的原生授权弹窗（契约 §3：首次触达授权级 scope 弹一次，
 * 「"应用名"想使用你的麦克风」+desc+允许/拒绝）。
 *
 * 基建复用 InteractionApi.showModal 的覆盖层做法（mask + ComposeView 挂 decorView），
 * 未引入新弹窗栈。深浅色纪律（skill §8）：跟随系统 `isSystemInDarkTheme()`，宿主显式
 * 覆写（capsuleDarkOverride）优先——与容器菜单弹层/胶囊同一套；**禁止写死
 * Color.White/Black 当底色**，配色取自容器既有弹层调色板。
 *
 * 单飞：同一时刻只允许一个授权弹窗；已有弹窗在屏时新请求直接走拒绝回调（本次 fail，
 * 用户再触发会重新弹——契约「拒绝=本次 fail 下次再弹」的排队能力等价物）。
 */
internal object PermissionDialog {

    /** 当前在屏弹窗的 key（appId+scope）；null=空闲。 */
    @Volatile
    private var activeKey: String? = null

    /** 命中在屏弹窗时，调用方应立即 fail 本次调用（不再排队弹第二个）。 */
    val isBusy: Boolean get() = activeKey != null

    fun show(
        activity: Activity,
        appId: String,
        scope: String,
        appName: String,
        permissionTitle: String,
        desc: String,
        onAllow: () -> Unit,
        onDeny: () -> Unit,
    ) {
        val key = "$appId:$scope"
        if (activeKey != null) {
            onDeny()
            return
        }
        activeKey = key
        activity.runOnUiThread {
            val rootView = activity.window.decorView.rootView as ViewGroup
            var maskView: FrameLayout? = null
            var sheetView: ComposeView? = null

            fun close(allowed: Boolean) {
                if (activeKey != key) return
                activeKey = null
                maskView?.let { rootView.removeView(it) }
                sheetView?.let { rootView.removeView(it) }
                PermLog.i("dialog", "$key ${if (allowed) "allow" else "deny"}")
                if (allowed) onAllow() else onDeny()
            }

            maskView = FrameLayout(activity).apply {
                setBackgroundColor(0x80000000.toInt())
                isClickable = true
            }
            sheetView = ComposeView(activity).apply {
                setContent {
                    AuthorizeSheetContent(
                        appName = appName,
                        permissionTitle = permissionTitle,
                        desc = desc,
                        onAllow = { close(true) },
                        onDeny = { close(false) },
                    )
                }
            }
            // 后加的 ComposeView 在 mask 之上，铺满全屏、内容居中呈现卡片。
            PermLog.i("dialog", "$key shown (app=$appName)")
            rootView.addView(maskView, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            rootView.addView(sheetView, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }
    }
}

/** 弹窗正文（深浅色跟随系统/宿主覆写，调色板=容器弹层既有值）。 */
@Composable
private fun AuthorizeSheetContent(
    appName: String,
    permissionTitle: String,
    desc: String,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    // 面板配色:原生壳层 chrome,微信语义=跟系统深浅色走;宿主显式注入的覆写优先。
    val dark = Dimina.getInstance().capsuleDarkOverride() ?: isSystemInDarkTheme()
    val cardBg = if (dark) Color(0xFF1D1F27) else Color.White
    val titleColor = if (dark) Color(0xFFE8EAED) else Color(0xFF202020)
    val descColor = if (dark) Color(0xFF9AA1A9) else Color(0xFF7F7F7F)
    val dividerColor = if (dark) Color(0xFF2A2D34) else Color.LightGray
    val denyColor = if (dark) Color(0xFF7D90B8) else Color(0xFF576B95)
    val allowColor = if (dark) Color(0xFF07C160) else Color(0xFF07C160)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(cardBg, RoundedCornerShape(14.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(26.dp))
            Text(
                text = "“$appName”$permissionTitle",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = titleColor,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            if (desc.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = desc,
                    fontSize = 14.sp,
                    color = descColor,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            Spacer(modifier = Modifier.height(22.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(dividerColor),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TextButton(onClick = onDeny) {
                    Text(text = "拒绝", fontSize = 16.sp, color = denyColor)
                }
                TextButton(onClick = onAllow) {
                    Text(text = "允许", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = allowColor)
                }
            }
        }
    }
}
