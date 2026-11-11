package com.didi.dimina.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import com.didi.dimina.bean.TabBarConfig
import com.didi.dimina.bean.TabBarItem
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import java.io.File

@Composable
fun DiminaTabBar(
    tabBarConfig: TabBarConfig,
    selectedIndex: Int,
    appId: String,
    filesDir: File,
    badges: List<String> = emptyList(),
    redDots: List<Boolean> = emptyList(),
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tabBarConfig.list.isEmpty()) {
        return
    }

    val backgroundColor = parseComposeColor(tabBarConfig.backgroundColor, Color.White)
    // 微信语义的 tabBar 上边框是 0.5px 低对比发丝线：white 是压到很低对比的白，
    // black 是近黑的细线。原来写死不透明 #FFFFFF / #E0E0E0：深色皮肤里 borderStyle
    // 取 white 时会变成一条横贯全屏的刺眼白线，取 black 时 #E0E0E0 同样发亮。
    val borderColor = if (tabBarConfig.borderStyle == "white") {
        Color.White.copy(alpha = BORDER_ALPHA_WHITE)
    } else {
        Color.Black.copy(alpha = BORDER_ALPHA_BLACK)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(borderColor)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(49.dp)
                .background(backgroundColor),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabBarConfig.list.forEachIndexed { index, item ->
                DiminaTabBarItem(
                    item = item,
                    selected = index == selectedIndex,
                    color = tabBarConfig.color,
                    selectedColor = tabBarConfig.selectedColor,
                    appId = appId,
                    filesDir = filesDir,
                    badge = badges.getOrElse(index) { "" },
                    showRedDot = redDots.getOrElse(index) { false },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelected(index) }
                )
            }
        }
    }
}

@Composable
private fun DiminaTabBarItem(
    item: TabBarItem,
    selected: Boolean,
    color: String,
    selectedColor: String,
    appId: String,
    filesDir: File,
    badge: String,
    showRedDot: Boolean,
    modifier: Modifier = Modifier,
) {
    val textColor = parseComposeColor(if (selected) selectedColor else color, Color(0xFF999999))
    val iconPath = if (selected && item.selectedIconPath.isNotEmpty()) {
        item.selectedIconPath
    } else {
        item.iconPath
    }
    val imageModel = remember(iconPath, appId, filesDir.absolutePath) {
        resolveTabBarIconModel(iconPath, appId, filesDir)
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (imageModel != null) {
                CoilImage(
                    imageModel = { imageModel },
                    imageOptions = ImageOptions(
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.Center
                    ),
                    modifier = Modifier.size(24.dp)
                )
            } else {
                Spacer(modifier = Modifier.size(24.dp))
            }

            Spacer(modifier = Modifier.height(3.dp))

            Text(
                text = item.text,
                color = textColor,
                fontSize = 10.sp,
                // 不写 lineHeight 会继承 Material3 正文的 ~24sp 行盒：24dp 图标 + 3dp
                // + 24dp 行盒远超 49dp 的行高，内容被顶到上边框——图标几乎贴住白线、
                // 上下留白不对称（真机实测白线到图标只有 0.8dp）。显式行高后整块
                // ≈39dp，在 49dp 行里居中，白线到图标 ≈5dp，与微信原生 TabBar 一致。
                lineHeight = 12.sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(72.dp)
            )
        }

        if (badge.isNotEmpty()) {
            Text(
                text = badge,
                color = Color.White,
                fontSize = 10.sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(x = 16.dp, y = 4.dp)
                    .background(Color(0xFFFA5151), RoundedCornerShape(8.dp))
                    .widthIn(min = 16.dp)
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        } else if (showRedDot) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(x = 16.dp, y = 6.dp)
                    .size(8.dp)
                    .background(Color(0xFFFA5151), RoundedCornerShape(4.dp))
            )
        }
    }
}

/**
 * 上边框发丝线的透明度。微信原生 TabBar 的上边框在浅色下实测是 #DEDEE0（白底上
 * 相当于约 13% 的黑），深色下用 white 时同样是低对比的一线提亮；不透明 #FFFFFF /
 * #E0E0E0 会把它画成一条横贯全屏的白线。
 */
private const val BORDER_ALPHA_WHITE = 0.16f
private const val BORDER_ALPHA_BLACK = 0.10f

private fun parseComposeColor(value: String, fallback: Color): Color {
    return try {
        Color(value.toColorInt())
    } catch (_: Exception) {
        fallback
    }
}

private fun resolveTabBarIconModel(rawPath: String, appId: String, filesDir: File): Any? {
    if (rawPath.isEmpty()) {
        return null
    }
    if (
        rawPath.startsWith("http://") ||
        rawPath.startsWith("https://") ||
        rawPath.startsWith("file://") ||
        rawPath.startsWith("data:")
    ) {
        return rawPath
    }

    val appRoot = File(filesDir, "jsapp/$appId")
    val appIdPrefix = "/$appId/"
    return when {
        rawPath.startsWith(appIdPrefix) -> File(appRoot, rawPath.removePrefix(appIdPrefix))
        rawPath.startsWith("/") -> File(rawPath)
        else -> File(appRoot, "main/$rawPath")
    }
}
