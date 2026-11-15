package com.didi.dimina.ui.container

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ---- 「问AI浮窗」规格常量（2026-11-15 定稿，勿随手改） ----

/**
 * 浮窗热区 44×44dp（2026-11-15 三次拍板：由 56 缩小，用户反馈 56 太大）；
 * 视觉=圆形双星徽章贴满（徽章直径 42dp、四周各 1dp 描边留白）。
 */
private const val ASK_AI_FLOAT_SIZE_DP = 44

/** 徽章 Canvas 边长 = 热区 44dp − 两侧各 1dp 描边留白。 */
private const val ASK_AI_FLOAT_ICON_DP = 42

/** 浮窗投影档位（参照宿主浮窗球风格，挂在圆形徽章上）。 */
private const val ASK_AI_FLOAT_SHADOW_DP = 4

/** 吸附后距屏幕左右缘的留白。 */
private const val ASK_AI_EDGE_MARGIN_DP = 8

/** 上下 clamp 在状态栏/导航栏 inset 之外再留的呼吸边距。 */
private const val ASK_AI_VERTICAL_MARGIN_DP = 12

/** 默认落点 = 可动区高度的 62% 处（右缘）。 */
private const val ASK_AI_DEFAULT_Y_FRACTION = 0.62f

/** 编辑态放大倍数与抖角（rotate ±3° 无限循环）。 */
private const val ASK_AI_EDIT_SCALE = 1.1f
private const val ASK_AI_EDIT_WOBBLE_DEGREES = 3f

/** ✕ 角标：20dp、底 #E5484D、白叉；探出右上角 8dp 压角（参照效果图）。 */
private const val ASK_AI_BADGE_SIZE_DP = 20
private const val ASK_AI_BADGE_OUTSET_DP = 8

/** 星河双星徽章定稿色：圆底 #071328、大四角星 #F5C24B、小四角星 #FFE08A。 */
private val ASK_AI_EMBLEM_CIRCLE = Color(0xFF071328)
private val ASK_AI_EMBLEM_BIG_STAR = Color(0xFFF5C24B)
private val ASK_AI_EMBLEM_SMALL_STAR = Color(0xFFFFE08A)

/** 徽章外圈描边与内环（2026-11-15 二次拍板：白 50% 主描边 + 10% 内细环）。 */
private val ASK_AI_EMBLEM_BORDER = Color.White.copy(alpha = 0.50f)
private val ASK_AI_EMBLEM_INNER_RING = Color.White.copy(alpha = 0.10f)

/** 置灰态双星（⋯菜单瓦片「浮窗已关闭」）。 */
private val ASK_AI_EMBLEM_DIM_STAR = Color(0xFF8A93A6)

/**
 * 星河双星徽章画笔：96×96 设计稿等比缩放绘制——圆底圆心 (48,48) r44，
 * 大四角星 + 右上小四角星（路径为定稿字面量，勿随手改）。
 * [canvasSize] 缺省取当前 DrawScope 尺寸；需要居中/偏移由调用方 translate。
 */
internal fun DrawScope.drawAskAiEmblem(
    bigStarColor: Color,
    smallStarColor: Color,
    canvasSize: Size = size,
    circleColor: Color = ASK_AI_EMBLEM_CIRCLE,
    withRing: Boolean = true,
) {
    val scaleTo96 = canvasSize.minDimension / 96f
    scale(scaleTo96, scaleTo96, pivot = Offset.Zero) {
        // 圆底贴满 96 画布（r=46.5），外圈主描边白 50% 宽 2 贴边，内环 10% 细环加层次。
        drawCircle(color = circleColor, radius = 46.5f, center = Offset(48f, 48f))
        if (withRing) {
            drawCircle(
                color = ASK_AI_EMBLEM_BORDER,
                radius = 45.5f,
                center = Offset(48f, 48f),
                style = Stroke(width = 2f),
            )
            drawCircle(
                color = ASK_AI_EMBLEM_INNER_RING,
                radius = 40f,
                center = Offset(48f, 48f),
                style = Stroke(width = 1f),
            )
        }
        drawPath(askAiBigStarPath(), color = bigStarColor)
        drawPath(askAiSmallStarPath(), color = smallStarColor)
    }
}

/** 大四角星 #F5C24B（放大 25% 版）：M44 21 Q47.5 45.5 71 50 Q47.5 54.5 44 79 Q40.5 54.5 17 50 Q40.5 45.5 44 21 Z */
private fun askAiBigStarPath(): Path = Path().apply {
    moveTo(44f, 21f)
    quadraticBezierTo(47.5f, 45.5f, 71f, 50f)
    quadraticBezierTo(47.5f, 54.5f, 44f, 79f)
    quadraticBezierTo(40.5f, 54.5f, 17f, 50f)
    quadraticBezierTo(40.5f, 45.5f, 44f, 21f)
    close()
}

/** 小四角星 #FFE08A（放大 25% 版）：M68 22 Q69.9 34.3 82 37 Q69.9 39.7 68 52 Q66.1 39.7 54 37 Q66.1 34.3 68 22 Z */
private fun askAiSmallStarPath(): Path = Path().apply {
    moveTo(68f, 22f)
    quadraticBezierTo(69.9f, 34.3f, 82f, 37f)
    quadraticBezierTo(69.9f, 39.7f, 68f, 52f)
    quadraticBezierTo(66.1f, 39.7f, 54f, 37f)
    quadraticBezierTo(66.1f, 34.3f, 68f, 22f)
    close()
}

/**
 * 「问AI浮窗」（2026-11-15 二次拍板）：56dp 热区内圆形双星徽章贴满（52dp 徽章+
 * 外圈白 50% 描边），无毛玻璃方块底；贴边可拖拽，点击触发 askAi。
 *
 * - 视觉 = 圆形双星徽章贴满（2026-11-15 二次拍板）：深蓝圆底 #071328 + 外圈白 50%
 *   描边 + 10% 内细环 + 轻投影；四周仅 2dp 描边留白，双星放大 25%。无毛玻璃方块底。
 * - 默认位置右缘、可动区高度 62% 处；拖拽跟手，松手吸附较近的左/右缘；
 *   上下 clamp 留状态栏/导航栏 inset + 呼吸边距。
 * - 点击（非编辑态）→ [onClick]：容器透传宿主 onClick 并外发 onAskAiInvoked 事件
 *   （见 DiminaActivity.onAskAiFloatClicked）；本组件不认识「问AI」业务语义。
 * - 长按 → 编辑态：放大 1.1 + rotate ±3° 无限抖动 + 右上 20dp ✕ 角标（#E5484D 白叉），
 *   点 ✕ → [onClose]（容器持久化 enabled=false）；编辑态下点其它区域（遮罩）取消编辑态。
 *
 * 显隐由调用方按 `Dimina.askAiEntryState().visible` 门控，本组件不管持久化。
 */
@Composable
internal fun AskAiFloatButton(
    onClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    // 浮窗左上角 px 位置；null = 尚未按容器尺寸落默认位（首帧不画，避免 (0,0) 闪现）。
    var positionPx by remember { mutableStateOf<Offset?>(null) }
    var editing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val statusBarPx = with(density) {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding().toPx()
    }
    val navBarPx = with(density) {
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().toPx()
    }
    // 键盘避让(2026-11-15 用户反馈浮窗被键盘遮挡):ime 底部 inset 计入下界,
    // 键盘弹出时浮窗自动浮在键盘上方;ime 变化时对当前位置重跑 clamp(见下 LaunchedEffect)。
    val imeBottomPx = with(density) {
        WindowInsets.ime.getBottom(density)
    }
    val sizePx = with(density) { ASK_AI_FLOAT_SIZE_DP.dp.toPx() }
    val edgeMarginPx = with(density) { ASK_AI_EDGE_MARGIN_DP.dp.toPx() }
    val topBoundPx = statusBarPx + with(density) { ASK_AI_VERTICAL_MARGIN_DP.dp.toPx() }
    val bottomBoundPx = navBarPx + imeBottomPx + with(density) { ASK_AI_VERTICAL_MARGIN_DP.dp.toPx() }

    fun clampPosition(pos: Offset): Offset {
        val minX = edgeMarginPx
        val maxX = (containerSize.width - sizePx - edgeMarginPx).coerceAtLeast(minX)
        val minY = topBoundPx
        val maxY = (containerSize.height - bottomBoundPx - sizePx).coerceAtLeast(minY)
        return Offset(pos.x.coerceIn(minX, maxX), pos.y.coerceIn(minY, maxY))
    }

    fun defaultPosition(): Offset {
        val x = containerSize.width - sizePx - edgeMarginPx
        val y = topBoundPx +
            (containerSize.height - bottomBoundPx - sizePx - topBoundPx)
                .coerceAtLeast(0f) * ASK_AI_DEFAULT_Y_FRACTION
        return Offset(x, y)
    }

    // 键盘弹出/收起(ime inset 变化)时,把当前浮窗位置重新夹进新的可动区,
    // 避免被键盘盖住(2026-11-15 用户反馈)。
    LaunchedEffect(imeBottomPx) {
        if (positionPx != null) {
            positionPx = clampPosition(positionPx!!)
        }
    }

    LaunchedEffect(containerSize) {
        if (containerSize != IntSize.Zero && positionPx == null) {            positionPx = defaultPosition()
        }
    }

    // 编辑态抖动：rotate ±3° 无限循环（只在编辑态被 graphicsLayer 采用）。
    val wobbleDegrees by rememberInfiniteTransition(label = "askAiWobble").animateFloat(
        initialValue = -ASK_AI_EDIT_WOBBLE_DEGREES,
        targetValue = ASK_AI_EDIT_WOBBLE_DEGREES,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 260, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "askAiWobbleDegrees",
    )

    // 吸附动画进行中持有的 job：新一轮拖拽/容器尺寸变化时取消，避免旧目标写穿新布局。
    val snapJob = remember { mutableStateOf<Job?>(null) }
    DisposableEffect(containerSize) {
        onDispose { snapJob.value?.cancel() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
    ) {
        if (editing) {
            // 编辑态遮罩：点浮窗以外任何区域取消编辑态（消费点击，不透传给页面）。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { editing = false }
            )
        }
        val resolved = positionPx
        if (resolved != null) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(resolved.x.roundToInt(), resolved.y.roundToInt()) }
                    .size(ASK_AI_FLOAT_SIZE_DP.dp)
                    .graphicsLayer {
                        if (editing) {
                            scaleX = ASK_AI_EDIT_SCALE
                            scaleY = ASK_AI_EDIT_SCALE
                            rotationZ = wobbleDegrees
                        }
                    }
                    // 拖拽（编辑态停用）：跟手 + clamp；松手吸附较近的左/右缘。
                    // positionPx 是 Compose state，闭包读到的一直是最新值。
                    .pointerInput(containerSize, editing, topBoundPx, bottomBoundPx) {
                        if (!editing) {
                            detectDragGestures(
                                onDragStart = {
                                    snapJob.value?.cancel()
                                    positionPx?.let { positionPx = clampPosition(it) }
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val current = positionPx
                                    if (current != null) {
                                        positionPx = clampPosition(current + dragAmount)
                                    }
                                },
                                onDragEnd = {
                                    val current = positionPx
                                    if (current != null) {
                                        val targetX =
                                            if (current.x + sizePx / 2 <= containerSize.width / 2f) {
                                                edgeMarginPx
                                            } else {
                                                containerSize.width - sizePx - edgeMarginPx
                                            }
                                        snapJob.value?.cancel()
                                        snapJob.value = scope.launch {
                                            animate(
                                                initialValue = current.x,
                                                targetValue = targetX,
                                                animationSpec = tween(durationMillis = 180),
                                            ) { value, _ ->
                                                positionPx = clampPosition(Offset(value, current.y))
                                            }
                                        }
                                    }
                                },
                                onDragCancel = {
                                    positionPx?.let { positionPx = clampPosition(it) }
                                },
                            )
                        }
                    }
                    // 点击/长按：非编辑态点击触发 askAi；长按进入编辑态；编辑态点击本体退出。
                    .pointerInput(editing) {
                        detectTapGestures(
                            onTap = {
                                if (editing) editing = false else onClick()
                            },
                            onLongPress = { editing = true },
                        )
                    }
                    .shadow(
                        ASK_AI_FLOAT_SHADOW_DP.dp,
                        CircleShape,
                        clip = false,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // 视觉 = 圆形双星徽章直接贴满（2026-11-15 二次拍板）：无毛玻璃方块底，
                // 徽章自带深蓝圆底 + 外圈白 50% 主描边 + 10% 内细环；四周仅 2dp 描边留白。
                Canvas(modifier = Modifier.size(ASK_AI_FLOAT_ICON_DP.dp)) {
                    drawAskAiEmblem(
                        bigStarColor = ASK_AI_EMBLEM_BIG_STAR,
                        smallStarColor = ASK_AI_EMBLEM_SMALL_STAR,
                    )
                }
                if (editing) {
                    // 右上 ✕ 角标：20dp 圆、#E5484D 底白叉；子级 clickable 消费点击，
                    // 父级 detectTapGestures 不会误触发 onClick。
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(
                                x = ASK_AI_BADGE_OUTSET_DP.dp,
                                y = -(ASK_AI_BADGE_OUTSET_DP / 2).dp,
                            )
                            .size(ASK_AI_BADGE_SIZE_DP.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE5484D))
                            .clickable { onClose() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(modifier = Modifier.size(10.dp)) {
                            val stroke = 1.6.dp.toPx()
                            drawLine(
                                color = Color.White,
                                start = Offset(0f, 0f),
                                end = Offset(size.width, size.height),
                                strokeWidth = stroke,
                                cap = StrokeCap.Round,
                            )
                            drawLine(
                                color = Color.White,
                                start = Offset(size.width, 0f),
                                end = Offset(0f, size.height),
                                strokeWidth = stroke,
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * ⋯菜单「问AI浮窗」开关瓦片（2026-11-15 定稿）：78dp 宽、52dp tile，样式与既有三枚
 * 一致（tileBackground/label 色沿用 sheetTile* 体系）；图标 = 浮窗本体缩微
 * （圆角方形细白描边 + 内圆底 + 双星）。
 *
 * 两态即开关：[floatVisible]=true 图标亮（双星 #F5C24B/#FFE08A）= 浮窗显示中；
 * false 整图标 alpha≈0.45、双星换 #8A93A6 = 浮窗已关闭。不做斜杠、不做单独开关行。
 * 点击 → [onClick]（容器 toggle 同一持久化状态）。
 */
@Composable
internal fun MiniProgramAskAiMenuTile(
    floatVisible: Boolean,
    dark: Boolean,
    tileBackground: Color,
    labelColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tileBackground),
            contentAlignment = Alignment.Center,
        ) {
            AskAiFloatMiniature(
                dimmed = !floatVisible,
                dark = dark,
                modifier = Modifier.size(30.dp),
            )
        }
        Text(
            text = "问AI浮窗",
            fontSize = 13.sp,
            color = labelColor,
            textAlign = TextAlign.Center,
            lineHeight = 17.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * 浮窗本体缩微图标（2026-11-15 二次拍板同步圆形徽章）：外圈细白描边（深色 tile 上白
 * @0.45；浅色 tile 上白不可见，按 sheetTileIcon(#333) 系派生深 hairline 保轮廓语义）+
 * 内圆底 #071328 + 双星。置灰态整图标 alpha≈0.45 且双星统一 #8A93A6。
 * 96×96 徽章按 22dp 内圆呈现（56dp 浮窗 30dp 图标的等比缩微）。
 */
@Composable
private fun AskAiFloatMiniature(
    dimmed: Boolean,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    val bigStar = if (dimmed) ASK_AI_EMBLEM_DIM_STAR else ASK_AI_EMBLEM_BIG_STAR
    val smallStar = if (dimmed) ASK_AI_EMBLEM_DIM_STAR else ASK_AI_EMBLEM_SMALL_STAR
    val borderColor =
        if (dark) Color.White.copy(alpha = 0.45f) else Color(0xFF333333).copy(alpha = 0.30f)
    Box(modifier.alpha(if (dimmed) 0.45f else 1f)) {
        Canvas(modifier = Modifier.matchParentSize()) {
            drawCircle(
                color = borderColor,
                style = Stroke(width = 1.dp.toPx()),
            )
            val emblemSizePx = size.minDimension
            drawAskAiEmblem(
                bigStarColor = bigStar,
                smallStarColor = smallStar,
                canvasSize = Size(emblemSizePx, emblemSizePx),
                withRing = false,
            )
        }
    }
}
