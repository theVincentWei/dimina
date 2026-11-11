package com.didi.dimina.ui.container

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.animation.AccelerateDecelerateInterpolator
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.isSystemInDarkTheme
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets as ComposeWindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.graphics.toColorInt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.didi.dimina.Dimina
import com.didi.dimina.api.device.ScanCodeOptions
import com.didi.dimina.bean.AppConfig
import com.didi.dimina.bean.BridgeOptions
import com.didi.dimina.bean.MergedPageConfig
import com.didi.dimina.bean.MiniProgram
import com.didi.dimina.bean.PathInfo
import com.didi.dimina.bean.TabBarConfig
import com.didi.dimina.bean.TabBarItem
import com.didi.dimina.common.BundledResourcePolicy
import com.didi.dimina.common.CapsuleStyle
import com.didi.dimina.common.LogUtils
import com.didi.dimina.common.MenuButtonLayout
import com.didi.dimina.common.PathUtils
import com.didi.dimina.common.Utils
import com.didi.dimina.common.VersionUtils
import com.didi.dimina.core.Bridge
import com.didi.dimina.core.MiniApp
import com.didi.dimina.core.PageStateTeardown
import com.didi.dimina.core.RemoteUpdateManager
import com.didi.dimina.ui.theme.DiminaAndroidTheme
import com.didi.dimina.ui.theme.ThemeResolver
import com.didi.dimina.ui.view.ActionSheet
import com.didi.dimina.ui.view.ContactPicker
import com.didi.dimina.ui.view.DiminaTabBar
import com.didi.dimina.ui.view.DiminaWebView
import com.didi.dimina.ui.view.MediaPickerRoot
import com.didi.dimina.ui.view.MediaType
import com.didi.dimina.ui.view.VideoCaptureOptions
import com.didi.dimina.ui.view.NativeComponentHost
import com.didi.dimina.ui.view.ScanCodeLauncher
import com.didi.dimina.ui.view.WebViewCacheManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.math.cos
import kotlin.math.sin

/**
 * Author: Doslin
 */
class DiminaActivity : ComponentActivity() {
    private val tag = "DiminaActivity"
    // 首屏启动遮罩:root 页 domReady 才关(300ms 淡出)。壳布局不再依赖它——
    // 旧实现顶栏/tabBar/胶囊也等 domReady,页面内容先以全屏无壳状态画出、
    // 外壳再瞬间弹入,Scaffold 重测量导致整页抖一下(「小程序页面老抖」根因)。
    private val isLoading = mutableStateOf(true)
    // 壳就绪:页面配置解析并应用后即 true(setInitialStyle 末尾置位,覆盖
    // initialize 首开/updatePath/onNewIntent 复用三条路径),顶栏/tabBar/胶囊
    // 据此从配置期就位,先于 WebView 首帧,消除外壳弹入的整页位移。
    private val shellReady = mutableStateOf(false)
    private var suspendedForMiniProgramNavigation = false

    // UI state for navigation bar
    private val showNavigationBar = mutableStateOf(true)
    private val navigationBarTitle = mutableStateOf("")
    private val navigationBarTextColor = mutableStateOf(Color.Black)
    private val navigationBarBackgroundColor = mutableStateOf("#FFFFFF")
    private val backgroundColor = mutableStateOf("#FFFFFF")
    private val tabBarConfigState = mutableStateOf<TabBarConfig?>(null)
    private val selectedTabIndex = mutableIntStateOf(-1)
    private val tabBarVisible = mutableStateOf(true)
    private val tabBarBadges = mutableStateOf<List<String>>(emptyList())
    private val tabBarRedDots = mutableStateOf<List<Boolean>>(emptyList())
    private val currentPagePath = mutableStateOf("")
    private val homeButtonHiddenForPage = mutableStateOf(false)
    private val homeButtonForcedByConfig = mutableStateOf(false)
    private val useTabBarContainer = mutableStateOf(false)
    private val loadedTabIndices = mutableStateOf<Set<Int>>(emptySet())

    // 深色模式:壳层样式的原始配置值(可能带 "@key" 引用)。setInitialStyle 记录原始值,
    // 主题切换时 reapplyThemeStyles 按新主题重新解析这组值;null 表示尚未应用过页面样式。
    private var rawNavigationBarBackgroundColor: String? = null
    private var rawBackgroundColor: String? = null
    private var rawNavigationBarTextStyle: String? = null

    // 主题 tick:声明 darkmode 的小程序在系统深浅色变化时自增,tabBar 五色等
    // 派生主题值的重组以它为 key 重算。
    private val themeTick = mutableIntStateOf(0)
    // 已推给 JS 快照的主题;主题「真切换」才推 patch(wx.onThemeChange 只在变化时触发,
    // 其它类配置变化不触发)。
    private var lastPushedJsTheme: String? = null

    // State for ActionSheet
    private val showActionSheet = mutableStateOf(false)
    private val showMiniProgramMenu = mutableStateOf(false)
    private var actionTextColor = "#000000"
    private var actionSheetOptions = listOf<String>()
    private var actionSheetCallback: ((Int) -> Unit)? = null

    // 胶囊收起(dsh-go 契约式):页面触摸 → 收起成贴边小把手;6s 空闲自动展开。
    private val capsuleCollapsed = mutableStateOf(false)
    // 主题 tick:每次 resume 自增,胶囊重组时重读容器配色(updateCapsuleTheme 生效)。
    private val capsuleThemeTick = mutableIntStateOf(0)

    private val mediaType = mutableStateOf(MediaType.NONE)
    private val maxImageCount = mutableIntStateOf(1)
    private val videoCaptureOptions = mutableStateOf(VideoCaptureOptions())

    // WebView初始化完成后的回调列表
    private val webViewReadyCallbacks = mutableListOf<(WebView) -> Unit>()

    // 页面加载完成回调
    private var pageReadyCallback: (() -> Unit)? = null

    private var webView: WebView? = null
    private var bridge: Bridge? = null
    private var nativeOverlay: FrameLayout? = null
    private var nativeComponentHost: NativeComponentHost? = null
    private val tabPageStates = mutableMapOf<Int, TabPageState>()
    private var apiBridgeContext: Bridge? = null

    // App configuration
    private lateinit var appConfig: AppConfig

    // Reference to the MiniApp instance
    private lateinit var miniApp: MiniApp

    // 当前小程序对象，用 Compose state 而非普通 lateinit var 承载：它会在
    // onNewIntent/applyUpdate 里脱离 composition 被重新赋值，普通字段读取不会
    // 建立快照订阅——依赖 miniProgram.* 的 composable 只有在同一次重组里恰好
    // 读了别的会触发失效的 state 时才会跟着更新，这个前提并不总成立。
    private val miniProgramState = mutableStateOf<MiniProgram?>(null)

    // @JvmName 用来避开跟下面已有的 getMiniProgram() 方法（RouteApi.kt 在用）的
    // JVM 签名冲突——不加的话 Kotlin 会给这个属性自动生成同名字节码 getter
    private var miniProgram: MiniProgram
        @JvmName("miniProgramValue")
        get() = checkNotNull(miniProgramState.value) { "miniProgram accessed before initialization" }
        set(value) {
            miniProgramState.value = value
        }
    private val isMiniProgramInitialized: Boolean
        get() = miniProgramState.value != null

    // Contact picker for handling contact-related operations
    private lateinit var contactPicker: ContactPicker
    private lateinit var scanCodeLauncher: ScanCodeLauncher

    private var imageChooseCallback: ((List<String>) -> Unit)? = null
    private var messageFileChooseCallback: ((Boolean, List<Uri>) -> Unit)? = null
    private val messageFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = messageFileChooseCallback ?: return@registerForActivityResult
        messageFileChooseCallback = null
        if (result.resultCode != Activity.RESULT_OK) {
            callback(false, emptyList())
            return@registerForActivityResult
        }

        val uris = buildList {
            result.data?.clipData?.let { clipData ->
                for (index in 0 until clipData.itemCount) {
                    add(clipData.getItemAt(index).uri)
                }
            }
            result.data?.data?.let(::add)
        }.distinct()
        callback(uris.isNotEmpty(), uris)
    }
    private val bluetoothPermissionCallbacks = mutableListOf<(Boolean) -> Unit>()
    private var bluetoothPermissionRequestInFlight = false
    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }
        bluetoothPermissionRequestInFlight = false
        val callbacks = bluetoothPermissionCallbacks.toList()
        bluetoothPermissionCallbacks.clear()
        callbacks.forEach { it(granted) }
    }
    private val nearbyWifiPermissionCallbacks = mutableListOf<(Boolean) -> Unit>()
    private var nearbyWifiPermissionRequestInFlight = false
    private val nearbyWifiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }
        nearbyWifiPermissionRequestInFlight = false
        val callbacks = nearbyWifiPermissionCallbacks.toList()
        nearbyWifiPermissionCallbacks.clear()
        callbacks.forEach { it(granted) }
    }
    private data class AuthorizationRequest(
        val permissions: Array<String>,
        val callback: (Boolean) -> Unit,
    )
    private val authorizationPermissionQueue = java.util.ArrayDeque<AuthorizationRequest>()
    private var activeAuthorizationRequest: AuthorizationRequest? = null
    private val authorizationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val request = activeAuthorizationRequest
        activeAuthorizationRequest = null
        request?.callback?.invoke(request.permissions.all { permission ->
            result[permission] == true || ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        })
        launchNextAuthorizationRequest()
    }
    private var openSettingsCallback: (() -> Unit)? = null
    private val openSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        openSettingsCallback?.invoke()
        openSettingsCallback = null
    }
    
    private var adjustBottom = 0.0
    private var preserveMiniAppOnDestroy = false
    private var pageResourcesReleased = false

    /**
     * 这个页面 Activity 因何而销毁。每个页面都是一个 Activity，onDestroy 既覆盖 navigateBack
     * 这类路由卸载，也覆盖退出小程序时整栈一起 finish——只有前者该派发 Page.onUnload。默认按
     * 路由处理（含系统回收），关栈的一方在 finish() 之前改写它。
     */
    private var pageStateTeardownReason = PageStateTeardown.ROUTING

    // 屏幕高度
    private var screenHeight = 0

    private data class TabPageState(
        val index: Int,
        var pathInfo: PathInfo,
        var root: String,
        var configInfo: MergedPageConfig,
        var webView: WebView? = null,
        var bridge: Bridge? = null,
        var nativeOverlay: FrameLayout? = null,
        var nativeComponentHost: NativeComponentHost? = null,
        val webViewReadyCallbacks: MutableList<(WebView) -> Unit> = mutableListOf(),
        var pageReadyCallback: (() -> Unit)? = null,
        var bridgeStarted: Boolean = false,
        // wx.hideHomeButton 按页面（tab）实例记账：后台 tab 的迟到调用只影响自己
        val homeButtonHidden: MutableState<Boolean> = mutableStateOf(false),
    )


    /**
     * 调整WebView的位置以适应键盘
     * @param bottom 元素到屏幕底部的距离，单位为像素
     */
    fun adjustViewPosition(bottom: Double) {
        // 获取设备像素比率（density）
        val density = Resources.getSystem().displayMetrics.density
        // CSS 像素转换为物理像素
        adjustBottom = bottom * density
    }

    fun handleChooseMedia(
        type: MediaType,
        count: Int,
        allowAlbum: Boolean,
        allowCamera: Boolean,
        captureOptions: VideoCaptureOptions = VideoCaptureOptions(),
        callback: (List<String>) -> Unit,
    ) {
        imageChooseCallback = callback
        videoCaptureOptions.value = captureOptions

        if (allowCamera && !allowAlbum) {
            // 只允许相机
            openSystemCamera(type)
        } else if (allowAlbum && !allowCamera) {
            // 只允许相册
            openSystemGallery(type, count)
        } else {
            // 设置ActionSheet的状态和回调
            showActionSheet(listOf("拍摄", "从相册选择")) { index ->
                when (index) {
                    0 -> openSystemCamera(type)
                    1 -> openSystemGallery(type, count)
                }
            }
        }
    }

    fun handleChooseMessageFile(
        intent: Intent,
        callback: (Boolean, List<Uri>) -> Unit,
    ): Boolean {
        if (messageFileChooseCallback != null) return false
        messageFileChooseCallback = callback
        return try {
            messageFilePickerLauncher.launch(intent)
            true
        } catch (_: Exception) {
            messageFileChooseCallback = null
            false
        }
    }

    fun showActionSheet(
        options: List<String>,
        itemColor: String = "#000000",
        callback: (Int) -> Unit,
    ) {
        actionTextColor = itemColor
        actionSheetOptions = options
        actionSheetCallback = callback
        showActionSheet.value = true
    }

    fun handleAddContact(callback: (Boolean) -> Unit) {
        contactPicker.handleAddContact(callback)
    }

    fun handleChooseContact(callback: (Boolean, JSONObject) -> Unit) {
        contactPicker.handleChooseContact(callback)
    }

    fun handleScanCode(options: ScanCodeOptions, callback: (Boolean, JSONObject) -> Unit) {
        scanCodeLauncher.launch(options, callback)
    }

    fun handleBluetoothPermission(callback: (Boolean) -> Unit) {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }
        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            callback(true)
            return
        }
        bluetoothPermissionCallbacks.add(callback)
        if (bluetoothPermissionRequestInFlight) return
        bluetoothPermissionRequestInFlight = true
        bluetoothPermissionLauncher.launch(permissions)
    }

    fun handleNearbyWifiPermission(callback: (Boolean) -> Unit) {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }
        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            callback(true)
            return
        }
        nearbyWifiPermissionCallbacks.add(callback)
        if (nearbyWifiPermissionRequestInFlight) return
        nearbyWifiPermissionRequestInFlight = true
        nearbyWifiPermissionLauncher.launch(permissions)
    }

    fun handleAuthorization(permissions: Array<String>, callback: (Boolean) -> Unit) {
        authorizationPermissionQueue.addLast(AuthorizationRequest(permissions, callback))
        launchNextAuthorizationRequest()
    }

    private fun launchNextAuthorizationRequest() {
        if (activeAuthorizationRequest != null) return
        val request = authorizationPermissionQueue.pollFirst() ?: return
        if (request.permissions.isEmpty() || request.permissions.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) {
            request.callback(true)
            launchNextAuthorizationRequest()
            return
        }
        activeAuthorizationRequest = request
        authorizationPermissionLauncher.launch(request.permissions)
    }

    fun handleOpenAppSettings(callback: () -> Unit): Boolean {
        if (openSettingsCallback != null) return false
        openSettingsCallback = callback
        return runCatching {
            openSettingsLauncher.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            })
            true
        }.getOrElse {
            openSettingsCallback = null
            false
        }
    }

    private fun openSystemGallery(type: MediaType, maxCount: Int) {
        // Set the maximum number of images that can be selected
        maxImageCount.intValue = maxCount
        // Show the MediaPicker UI
        mediaType.value = type
    }

    private fun openSystemCamera(type: MediaType) {
        mediaType.value = if (type == MediaType.VIDEO) MediaType.CAMERA_VIDEO else MediaType.CAMERA
    }


    fun getSoftKeyboardHeight(rootView: View, callback: (Int) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            rootView.setOnApplyWindowInsetsListener { _, insets ->
                // 获取软键盘的高度（px）
                val imeHeight = insets.getInsets(WindowInsets.Type.ime()).bottom
                callback(imeHeight)
                insets // 返回 insets 以保持默认行为
            }
        } else {
            ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
                val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                callback(imeHeight)
                insets
            }
        }
    }

    private var initializationJob: kotlinx.coroutines.Job? = null
    private var destroyedByHost = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isObsoleteHostLaunch(intent)) {
            destroyedByHost = true
            preserveMiniAppOnDestroy = true
            finish()
            return
        }
        enableEdgeToEdge()

        // 获取屏幕高度
        screenHeight = resources.displayMetrics.heightPixels

        // 设置键盘监听
        getSoftKeyboardHeight(window.decorView.rootView) { keyBoardHeight ->
            withWebView { webView ->
                if (adjustBottom > 0 && adjustBottom < keyBoardHeight) {
                    val translationY = adjustBottom - keyBoardHeight
                    webView.translationY = translationY.toFloat()
                    LogUtils.d(tag, "调整WebView位置: translationY=$translationY, bottom=$adjustBottom, keyboardHeight=$keyBoardHeight")
                } else {
                    adjustBottom = 0.0
                    webView.translationY = 0f
                }
            }
        }

        // 接收 MiniProgram 对象
        val program = getMiniProgramFromIntent(intent)

        if (program == null) {
            // 陈旧任务记录的 intent 读不出来时必须连任务记录一起删:只 finish
            // 的话记录还在,系统下次又用它重建本 Activity,死循环「打开即闪退」。
            LogUtils.e("DiminaActivity", "Unreadable/absent mini program intent (data=${intent.data}, extras=${intent.extras?.keySet()}); removing task")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask()
            } else {
                finish()
            }
            return
        }

        // Get MiniApp instance and JsCore for this MiniProgram
        try {
            miniApp = MiniApp.getInstance()
            miniProgram = program
            activityRegistry.register(miniProgram.appId, this)
            pendingLaunches.remove(miniProgram.appId)
            LogUtils.d(
                tag,
                "Successfully obtained MiniApp instance and JsCore for appId: ${miniProgram.appId}"
            )
        } catch (e: Exception) {
            LogUtils.e(tag, "Failed to get MiniApp instance or JsCore: ${e.message}")
            finish()
            return
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (miniProgram.root) hideMiniProgram() else finish()
            }
        })

        // Initialize the ContactPicker
        contactPicker = ContactPicker(this)
        scanCodeLauncher = ScanCodeLauncher(this)

        setContent {
            DiminaAndroidTheme {
                val pageBackgroundColor = parseCssColor(backgroundColor.value)
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = pageBackgroundColor
                ) { innerPadding ->
                    DiminaContent(
                        miniProgram = miniProgram,
                        isLoading = isLoading,
                        shellReady = shellReady,
                        modifier = Modifier
                            .background(pageBackgroundColor)
                            .padding(
                                0.dp,
                                0.dp,
                                0.dp,
                                innerPadding.calculateBottomPadding()
                            )
                    )
                }
            }
        }

        // 使用协程初始化JS引擎并加载小程序
        initializationJob = CoroutineScope(Dispatchers.Main).launch {
            initialize()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (destroyedByHost || isObsoleteHostLaunch(intent)) return
        setIntent(intent)

        val program = getMiniProgramFromIntent(intent) ?: return
        if (isMiniProgramInitialized && program.appId != miniProgram.appId) {
            return
        }

        miniProgram = program
        if (isMiniProgramInitialized && program.root) {
            miniApp.setPendingAppShowOptions(
                program.appId, MiniProgramEntryOptions.from(program, getLaunchReferrerInfo()),
            )
            if (visibilityTracker.isForeground(program.appId)) {
                dispatchMiniProgramShow()
            }
        }
        val url = program.path ?: return
        if (!::appConfig.isInitialized) {
            return
        }

        // 若这个 Activity 实例被复用（intent 落到已存在的顶层实例）：全部页面实例作废，
        // 页面级 hideHomeButton 标记随之重置（TabPageState 会被 switchTab/updatePath 复用，
        // 不重置会把旧页面实例的隐藏标记带进新页面）
        homeButtonHiddenForPage.value = false
        tabPageStates.values.forEach { it.homeButtonHidden.value = false }

        if (isTabBarPageUrl(url)) {
            switchTab(url)
        } else {
            updatePath(url)
        }
    }


    /**
     * Create and initialize the QuickJS engine after UI setup
     */
    private suspend fun initialize() {
        // 在IO线程中执行耗时操作
        withContext(Dispatchers.IO) {
            // 1.解压小程序资源包 jsApp
            val appId = miniProgram.appId
            try {
                // jsapp uses its own version and extracted-file readiness. The host-app
                // update marker is shared with JSSDK and is therefore not a safe gate.
                val shouldExtract = if (miniProgram.root) {
                    val localVersion = VersionUtils.getAppVersion(appId)
                    BundledResourcePolicy.shouldExtract(
                        bundledVersion = miniProgram.versionCode,
                        hostManaged = RemoteUpdateManager.getAppVersionInfo(applicationContext, appId)?.optBoolean("hostManaged") == true,
                        installedVersion = localVersion,
                        requiredResourcePresent =
                            findAppConfigFile("jsapp/$appId") != null &&
                                File(filesDir, "jsapp/$appId/main/logic.js").isFile,
                    )
                } else false

                if (shouldExtract) {
                    if (Utils.unzipAssets(
                            this@DiminaActivity,
                            "jsapp/$appId/$appId.zip",
                            "jsapp/$appId",
                            requiredPaths = listOf(
                                "main/app-config.json",
                                "main/logic.js",
                            ),
                        )
                    ) {
                        VersionUtils.setAppVersion(appId, miniProgram.versionCode)
                        LogUtils.d(tag, "Mini program extraction completed successfully")
                    } else {
                        LogUtils.e(tag, "Failed to extract mini program for appId: $appId")
                    }
                }

                val hasRunnablePackage =
                    findAppConfigFile("jsapp/$appId") != null &&
                        File(filesDir, "jsapp/$appId/main/logic.js").isFile
                if (!hasRunnablePackage) {
                    miniProgram = RemoteUpdateManager.installInitialPackage(
                        applicationContext,
                        miniProgram,
                    )
                    LogUtils.d(tag, "Installed initial mini program package from manifest")
                    // 首装完成 → 通知宿主监听器(宿主 UI 按真实状态刷新,不再靠轮询猜)。
                    MiniApp.getInstance().notifyInstalled(
                        appId, miniProgram.versionCode, miniProgram.versionName,
                    )
                } else {
                    // Bundle 强制自动升级(契约 §6.4):上次运行检测/下载好的 pending 新包
                    // 还在 → 本次启动前**静默激活**,不询问用户(autoUpdate 包的既有旁路
                    // 由此全量化:弹窗与 declined 记账已删,所有包一致)。此时运行时/页面栈
                    // 尚未建立,换包就是「下次打开自动生效」,不存在中途重启。激活成功即发
                    // onUpdateApplied(带版本号)供宿主发告知通知;激活失败则照旧跑当前版本,
                    // 保持静默(下载失败本来就走 no-update,不打扰)。
                    val pending = RemoteUpdateManager.peekPendingUpdate(applicationContext, appId)
                    if (pending != null && pending.versionCode > VersionUtils.getAppVersion(appId)) {
                        if (RemoteUpdateManager.activatePendingUpdate(applicationContext, appId)) {
                            miniProgram = miniProgram.copy(versionCode = pending.versionCode)
                            MiniApp.getInstance().notifyUpdateApplied(appId, pending.versionCode, pending.versionName)
                        }
                    }
                }
            } catch (e: Exception) {
                LogUtils.e(tag, "Error preparing mini program resources: ${e.message}")
                withContext(Dispatchers.Main) { finish() }
                return@withContext
            }

            // 2.读取配置文件 - Only after extraction is complete
            try {
                val config = readAppConfig("jsapp/$appId")
                if (config == null) {
                    LogUtils.e(tag, "Failed to read app config for appId: $appId")
                    withContext(Dispatchers.Main) { finish() }
                    return@withContext
                }
                appConfig = config
                // app.json's `networkTimeout.connectSocket` is this mini program's default
                // connect timeout; the WebSocket state machine needs it before any connectSocket
                // call can land, so it is published as soon as the config is parsed.
                com.didi.dimina.api.network.WebSocketManager.shared.updateNetworkTimeout(
                    appId,
                    config.app.networkTimeout?.connectSocket,
                )
                LogUtils.d(tag, "Successfully loaded app config")
            } catch (e: Exception) {
                LogUtils.e(tag, "Error reading app config: ${e.message}")
                withContext(Dispatchers.Main) { finish() }
                return@withContext
            }

            // 3.读取页面配置
            val entryPagePath =
                (if (appConfig.app.runtimeType == "game") null else miniProgram.path)
                    ?: getDefaultEntryPagePath() ?: run {
                    withContext(Dispatchers.Main) { finish() }
                    return@withContext
                }
            val pathInfo = Utils.queryPath(entryPagePath)
            val pageConfig = appConfig.modules[pathInfo.pagePath]
            val mergedPageConfig = Utils.mergePageConfig(appConfig.app, pageConfig)

            // 切换到主线程设置UI
            withContext(Dispatchers.Main) {
                // 4.设置标题栏以及状态栏颜色模式
                tabBarConfigState.value = appConfig.app.tabBar
                resetTabBarDynamicState(appConfig.app.tabBar)
                val initialTabIndex = getTabBarIndex(pathInfo.pagePath)
                useTabBarContainer.value = miniProgram.root && initialTabIndex >= 0
                syncTabBarState(pathInfo.pagePath)
                setInitialStyle(mergedPageConfig)

                if (useTabBarContainer.value) {
                    ensureTabLoaded(initialTabIndex, pathInfo)
                } else {
                    withWebView { webView ->
                        // 5.创建通信 bridge
                        val entryPageBridge = createBridge(
                            BridgeOptions(
                                pathInfo = pathInfo,
                                scene = miniProgram.scene,
                                jscore = miniApp.getJsCore(appId, this@DiminaActivity),
                                webview = webView,
                                isRoot = true,
                                root = pageConfig?.root ?: "main",
                                appId = miniProgram.appId,
                                runtimeType = appConfig.app.runtimeType,
                                pages = appConfig.app.pages,
                                configInfo = mergedPageConfig,
                                referrerInfo = getLaunchReferrerInfo(),
                            )
                        )
                        // Add bridge to MiniApp's bridge list for this appId
                        miniApp.addBridge(miniProgram.appId, entryPageBridge)

                        withWebViewPageLoaded {
                            LogUtils.d(tag, "Page loaded, starting bridge")
                            entryPageBridge.start()
                        }
                    }
                }
            }
        }


    }

    private fun createBridge(options: BridgeOptions, setAsActive: Boolean = true): Bridge {
        val bridge = Bridge(options = options, parent = this)
        bridge.init()
        if (setAsActive) {
            this.bridge = bridge
        }
        miniApp.startUpdateCheckIfNeeded(applicationContext, miniProgram)
        reconcileAppVisibilityWithCore()
        return bridge
    }


    private fun setInitialStyle(config: MergedPageConfig) {
        // Set navigation bar visibility based on navigationStyle
        showNavigationBar.value = config.navigationStyle != "custom"

        // hideHomeButton() 只隐藏调用它的那个页面的 home 按钮；这个 Activity 背后
        // 的页面身份一变就会重置
        homeButtonHiddenForPage.value = false
        homeButtonForcedByConfig.value = config.homeButton

        // Set navigation bar title
        navigationBarTitle.value = config.navigationBarTitleText

        // 记录原始配置值(可能带 "@key" 引用),再按当前主题解析应用;
        // 主题切换时 onConfigurationChanged 会对同一组原始值重算。
        rawNavigationBarBackgroundColor = config.navigationBarBackgroundColor
        rawBackgroundColor = config.backgroundColor
        rawNavigationBarTextStyle = config.navigationBarTextStyle
        reapplyThemeStyles()
        // 页面身份变化时 JS 侧看到的 systemInfo 快照也会随之刷新,推主题的必要性重新记账
        lastPushedJsTheme = currentJsTheme()
        // 壳就绪放在最后:此时 backgroundColor 已按主题解析为页面配置底色,
        // 壳就位瞬间内容区即页面底色,不存在默认白中间帧。setInitialStyle 被
        // initialize(首开)/updatePath(redirect)/onNewIntent(复用)三条路径共用,
        // 幂等置 true,后两条路径 shellReady 已为 true 不触发额外重组。
        shellReady.value = true
    }

    /**
     * darkmode 门禁:仅 app.json 声明 "darkmode": true 的小程序解析 "@key" 引用;
     * 其余包所有配置值原样直通,行为与现状完全一致。
     */
    private fun isDarkModeEnabled(): Boolean {
        return ::appConfig.isInitialized && appConfig.app.darkmode
    }

    /** 按当前系统主题解析一个壳层配置值(未声明 darkmode 时原样返回)。 */
    private fun resolveThemeValue(value: String): String {
        if (!isDarkModeEnabled()) {
            return value
        }
        return ThemeResolver.resolve(ThemeResolver.currentTheme(this), appConfig.app.theme, value)
    }

    /**
     * 按当前主题重新解析壳层颜色并应用到全部应用点。
     * setInitialStyle(进入/切换页面)与 onConfigurationChanged(系统深浅色变化)共用:
     * 导航栏与页面背景是 Compose state,reset 即触发重组;系统导航栏与 WebView 背景
     * 没有重组机制,这里手动重调。
     */
    private fun reapplyThemeStyles() {
        val rawNavBg = rawNavigationBarBackgroundColor ?: return
        val rawPageBg = rawBackgroundColor ?: return
        val rawTextStyle = rawNavigationBarTextStyle ?: return

        navigationBarBackgroundColor.value = resolveThemeValue(rawNavBg)
        backgroundColor.value = resolveThemeValue(rawPageBg)

        updateSystemNavigationBarColor(backgroundColor.value)
        updateWebViewBackgroundColor(backgroundColor.value)

        // 多 tab 场景:后台 tab 的 WebView/组件宿主也按各自页面配置刷新,切回时不残留旧色
        if (useTabBarContainer.value) {
            tabPageStates.values.forEach { state ->
                val argb = parseCssColor(resolveThemeValue(state.configInfo.backgroundColor)).toArgb()
                state.webView?.setBackgroundColor(argb)
                state.nativeComponentHost?.updatePageBackgroundColor(argb)
            }
        }

        // Update status bar style based on text style
        updateActionColorStyle(resolveThemeValue(rawTextStyle))

        // WebView 内容色算法(ForceDark)随主题刷新:所有已创建的页面 WebView 一并覆盖
        (listOfNotNull(webView) + tabPageStates.values.mapNotNull { it.webView })
            .forEach(::applyWebViewColorScheme)
    }

    /** tabBar 五色(color/selectedColor/backgroundColor/borderStyle)按当前主题解析;未开 darkmode 原样返回。 */
    private fun resolveTabBarColors(config: TabBarConfig): TabBarConfig {
        if (!isDarkModeEnabled()) {
            return config
        }
        return config.copy(
            color = resolveThemeValue(config.color),
            selectedColor = resolveThemeValue(config.selectedColor),
            backgroundColor = resolveThemeValue(config.backgroundColor),
            borderStyle = resolveThemeValue(config.borderStyle),
        )
    }

    /**
     * JS 侧的 theme 值:仅声明 darkmode 的小程序返回 "dark"/"light"
     * (微信语义:未适配包 systemInfo 不带 theme,永远按浅色)。
     * 供 SystemApi 构建 systemInfo 时取用。
     */
    fun currentJsTheme(): String? {
        return if (isDarkModeEnabled()) ThemeResolver.currentTheme(this) else null
    }

    /**
     * 把带当前主题的全量 systemInfo patch 推给该 appId 的 JsCore:基础库侧
     * hostEnvUpdate 消息合并进 hostEnv 快照,onThemeChange 按 patch.systemInfo?.theme
     * 消费。推全量而非单字段,避免快照合并把其余字段丢掉;JsCore 未就绪时
     * ?. 静默(同 postUpdateStatus 写法)。
     */
    private fun pushSystemInfoPatchToJsCore() {
        val patch = JSONObject().apply {
            val systemInfo = Utils.getMiniProgramSystemInfo(this@DiminaActivity)
            // Utils 构建器保持平台通用、不含 theme;这里按门禁补上,否则基础库
            // if (!theme) return,wx.onThemeChange 永远不触发。
            currentJsTheme()?.let { systemInfo.put("theme", it) }
            put("systemInfo", systemInfo)
        }
        miniApp.peekJsCore(miniProgram.appId)?.postMessage("hostEnvUpdate", patch)
    }

    private fun parseCssColor(value: String, fallback: Color = Color.White): Color {
        return try {
            Color(value.toColorInt())
        } catch (_: Exception) {
            fallback
        }
    }

    private fun updateSystemNavigationBarColor(color: String) {
        val navigationBarColor = parseCssColor(color)
        @Suppress("DEPRECATION")
        window.navigationBarColor = navigationBarColor.toArgb()
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars =
            isLightColor(navigationBarColor)
    }

    private fun updateWebViewBackgroundColor(color: String) {
        val backgroundColor = parseCssColor(color).toArgb()
        val activeWebView = getWebViewForBridge(apiBridgeContext)
        val activeHost = if (activeWebView != null) {
            tabPageStates.values.firstOrNull { it.webView === activeWebView }?.nativeComponentHost
                ?: nativeComponentHost.takeIf { webView === activeWebView }
        } else nativeComponentHost
        activeHost?.let {
            it.updatePageBackgroundColor(backgroundColor)
            return
        }
        if (activeWebView != null) {
            activeWebView.setBackgroundColor(backgroundColor)
        } else {
            withWebView { webView ->
                webView.setBackgroundColor(backgroundColor)
            }
        }
    }

    /**
     * WebView 内容深浅色。宿主 targetSdk=28,WebView 对 targetSdk<33 的应用
     * prefers-color-scheme 恒报 light(算法化适配 API 不可用),只能走 legacy
     * ForceDark。语义:声明 darkmode 的小程序页面内容算法反转,深色下可读;
     * 未声明永远 OFF(纯浅色,与现状一致)。FORCE_DARK 不支持时静默跳过。
     */
    @Suppress("DEPRECATION")
    private fun applyWebViewColorScheme(target: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            return
        }
        WebSettingsCompat.setForceDark(
            target.settings,
            if (isDarkModeEnabled()) {
                // AUTO=跟随 app uiMode(深色系统才反转内容,浅色系统保持原样);
                // ON 是无条件反转,浅色系统下会把页面也染黑——不能用(实测踩过)。
                WebSettingsCompat.FORCE_DARK_AUTO
            } else {
                WebSettingsCompat.FORCE_DARK_OFF
            },
        )
    }

    private fun isLightColor(color: Color): Boolean {
        val argb = color.toArgb()
        val red = android.graphics.Color.red(argb)
        val green = android.graphics.Color.green(argb)
        val blue = android.graphics.Color.blue(argb)
        return (red * 299 + green * 587 + blue * 114) / 1000 > 128
    }

    private fun isWhiteNavigationTextStyle(color: String): Boolean {
        return when (color.trim().lowercase()) {
            "white", "#fff", "#ffffff", "#ffffffff" -> true
            else -> false
        }
    }

    fun isTabBarPageUrl(url: String): Boolean {
        if (!::appConfig.isInitialized) {
            return false
        }
        return getTabBarIndex(Utils.queryPath(url).pagePath) >= 0
    }

    /**
     * 导航栏「返回首页」按钮的显示判据（微信真机实测语义）：默认导航栏 + 未被
     * hideHomeButton 隐藏 + 当前页非应用首页 + 非 tabBar 页（这两条排除
     * homeButton: true 也不能突破），且满足其一：页面栈栈底（自动规则），
     * 或页面配置 homeButton: true（此时与返回箭头并存显示）
     */
    private fun shouldShowHomeButton(): Boolean {
        if (!::appConfig.isInitialized) {
            return false
        }
        if (!showNavigationBar.value || activePageHomeButtonHidden()) {
            return false
        }
        // 归一化前导斜杠再比较：currentPagePath 可能来自宿主直启 path，
        // entryPagePath 来自配置，两者写法不受控
        val pagePath = currentPagePath.value.trimStart('/')
        if (pagePath.isEmpty() || pagePath == getDefaultEntryPagePath()?.trimStart('/')) {
            return false
        }
        if (getTabBarIndex(pagePath) >= 0) {
            return false
        }
        return miniProgram.root || homeButtonForcedByConfig.value
    }

    /**
     * 当前可见页面的 hideHomeButton 标记：tab 容器下读当前选中 tab 自己的标记，
     * 非 tab 根页面读 activity 级标记（一个 Activity 只承载一个非 tab 页，
     * redirectTo/reLaunch 换页时在 setInitialStyle 重置）
     */
    private fun activePageHomeButtonHidden(): Boolean {
        if (useTabBarContainer.value) {
            tabPageStates[selectedTabIndex.intValue]?.let { return it.homeButtonHidden.value }
        }
        return homeButtonHiddenForPage.value
    }

    /**
     * 隐藏调用页的返回首页按钮（wx.hideHomeButton）。经 apiBridgeContext 归属调用方：
     * tab 页标记在自己的 TabPageState 上——后台 tab 的迟到调用不会隐藏当前可见 tab 的按钮
     */
    fun hideHomeButton() {
        val caller = apiBridgeContext
        if (caller != null) {
            tabPageStates.values.firstOrNull { it.bridge === caller }?.let { state ->
                state.homeButtonHidden.value = true
                return
            }
        }
        homeButtonHiddenForPage.value = true
    }

    fun switchTab(url: String): Boolean {
        if (!::appConfig.isInitialized) {
            return false
        }

        val pathInfo = Utils.queryPath(url)
        val targetIndex = getTabBarIndex(pathInfo.pagePath)
        if (targetIndex < 0) {
            return false
        }

        if (!miniProgram.root) {
            // CLEAR_TOP 按 Activity 组件匹配，清不掉共享同一 DiminaActivity 类的下层
            // 实例；改用 activityRegistry 精确找到 root 实例并同进程直接调用它的
            // switchTab，不需要任何 Intent flag
            val root = activityRegistry.closeAllExcept(
                miniProgram.appId,
                keep = { it.miniProgram.root },
            ) { activity ->
                activity.preserveMiniAppOnDestroy = true
                activity.finish()
            }
            if (root != null) {
                root.runOnUiThread {
                    root.switchTab(url)
                }
            } else {
                // 异常态：栈里没有 root 实例（如宿主直启非 tab 内页），退化为清栈重启
                relaunchStack(url)
            }
            return true
        }

        runOnUiThread {
            switchTabInRoot(targetIndex, pathInfo)
        }
        return true
    }

    private fun switchTabInRoot(targetIndex: Int, pathInfo: PathInfo) {
        val previousIndex = selectedTabIndex.intValue
        val previousBridge = getActiveBridge()
        val wasUsingTabBarContainer = useTabBarContainer.value

        useTabBarContainer.value = true
        ensureTabLoaded(targetIndex, pathInfo)
        val targetState = tabPageStates[targetIndex] ?: return

        if (!wasUsingTabBarContainer && previousBridge != null) {
            miniApp.removeBridge(miniProgram.appId, previousBridge)?.destroy()
            webView = null
            bridge = null
            nativeOverlay = null
            nativeComponentHost = null
        } else if (previousIndex != targetIndex) {
            previousBridge?.pageHide()
        }

        selectedTabIndex.intValue = targetIndex
        currentPagePath.value = targetState.pathInfo.pagePath
        setInitialStyle(targetState.configInfo)
        activateTabState(targetIndex)

        if (!wasUsingTabBarContainer || previousIndex != targetIndex) {
            targetState.bridge?.pageShow()
        }
    }

    private fun ensureTabLoaded(index: Int, pathInfo: PathInfo? = null): TabPageState? {
        val tabBarConfig = appConfig.app.tabBar ?: return null
        val tabItem = tabBarConfig.list.getOrNull(index) ?: return null
        val resolvedPathInfo = pathInfo ?: Utils.queryPath(tabItem.pagePath)
        val pageConfig = appConfig.modules[resolvedPathInfo.pagePath]
        val mergedPageConfig = Utils.mergePageConfig(appConfig.app, pageConfig)
        val state = tabPageStates.getOrPut(index) {
            TabPageState(
                index = index,
                pathInfo = resolvedPathInfo,
                root = pageConfig?.root ?: "main",
                configInfo = mergedPageConfig,
            )
        }

        state.pathInfo = resolvedPathInfo
        state.root = pageConfig?.root ?: "main"
        state.configInfo = mergedPageConfig

        if (!loadedTabIndices.value.contains(index)) {
            loadedTabIndices.value = loadedTabIndices.value + index
        }
        return state
    }

    private fun activateTabState(index: Int) {
        val state = tabPageStates[index] ?: return
        webView = state.webView
        bridge = state.bridge
        nativeOverlay = state.nativeOverlay
        nativeComponentHost = state.nativeComponentHost
    }

    private fun getActiveBridge(): Bridge? {
        if (useTabBarContainer.value) {
            return tabPageStates[selectedTabIndex.intValue]?.bridge ?: bridge
        }
        return bridge
    }

    private fun getWebViewForBridge(sourceBridge: Bridge?): WebView? {
        if (sourceBridge != null) {
            tabPageStates.values.firstOrNull { it.bridge === sourceBridge }?.webView?.let {
                return it
            }
            if (bridge === sourceBridge) {
                return webView
            }
        }
        return if (useTabBarContainer.value) {
            tabPageStates[selectedTabIndex.intValue]?.webView ?: webView
        } else {
            webView
        }
    }

    /**
     * Cancels the touch stream owned by the WebView that invoked the current API.
     *
     * A modal can be requested while that WebView is still handling a gesture.
     * Adding a view above it does not retarget an existing Android touch stream,
     * so the WebView must receive ACTION_CANCEL before the modal is mounted.
     */
    fun cancelActiveWebViewTouch() {
        val targetWebView = getWebViewForBridge(apiBridgeContext) ?: return
        runOnUiThread {
            val eventTime = SystemClock.uptimeMillis()
            val cancelEvent = MotionEvent.obtain(
                eventTime,
                eventTime,
                MotionEvent.ACTION_CANCEL,
                0f,
                0f,
                0,
            )
            try {
                targetWebView.dispatchTouchEvent(cancelEvent)
            } finally {
                cancelEvent.recycle()
            }
        }
    }

    private fun syncTabBarState(pagePath: String) {
        currentPagePath.value = pagePath
        val tabIndex = getTabBarIndex(pagePath)
        if (tabIndex >= 0) {
            selectedTabIndex.intValue = tabIndex
        }
    }

    private fun getTabBarIndex(pagePath: String): Int {
        // 两侧都归一化前导斜杠：pagePath 可能来自宿主直启 path 或 config，
        // tabBar.list 的 pagePath 也是配置值，写法不受控
        val normalized = pagePath.trimStart('/')
        return appConfig.app.tabBar?.list?.indexOfFirst { item ->
            item.pagePath.trimStart('/') == normalized
        } ?: -1
    }

    private fun resetTabBarDynamicState(config: TabBarConfig?) {
        val listLength = config?.list?.size ?: 0
        tabBarVisible.value = true
        tabBarBadges.value = List(listLength) { "" }
        tabBarRedDots.value = List(listLength) { false }
    }

    fun getTabBarItemCount(): Int {
        val stateCount = tabBarConfigState.value?.list?.size ?: 0
        if (stateCount > 0) {
            return stateCount
        }
        if (!::appConfig.isInitialized) {
            return 0
        }
        return appConfig.app.tabBar?.list?.size ?: 0
    }

    fun setTabBarStyle(
        color: String?,
        selectedColor: String?,
        backgroundColor: String?,
        borderStyle: String?,
    ) {
        runOnUiThread {
            val config = tabBarConfigState.value ?: return@runOnUiThread
            val safeBorderStyle = if (borderStyle == "black" || borderStyle == "white") {
                borderStyle
            } else {
                null
            }
            tabBarConfigState.value = config.copy(
                color = color ?: config.color,
                selectedColor = selectedColor ?: config.selectedColor,
                backgroundColor = backgroundColor ?: config.backgroundColor,
                borderStyle = safeBorderStyle ?: config.borderStyle,
            )
        }
    }

    fun setTabBarItem(
        index: Int,
        text: String?,
        iconPath: String?,
        selectedIconPath: String?,
    ) {
        runOnUiThread {
            val config = tabBarConfigState.value ?: return@runOnUiThread
            val oldItem = config.list.getOrNull(index) ?: return@runOnUiThread
            val newList = config.list.toMutableList()
            newList[index] = TabBarItem(
                pagePath = oldItem.pagePath,
                iconPath = iconPath ?: oldItem.iconPath,
                selectedIconPath = selectedIconPath ?: oldItem.selectedIconPath,
                text = text ?: oldItem.text,
            )
            tabBarConfigState.value = config.copy(list = newList)
        }
    }

    fun showTabBar() {
        runOnUiThread {
            tabBarVisible.value = true
        }
    }

    fun hideTabBar() {
        runOnUiThread {
            tabBarVisible.value = false
        }
    }

    fun setTabBarBadge(index: Int, text: String) {
        runOnUiThread {
            val listLength = getTabBarItemCount()
            val badges = normalizedBadgeList(listLength).toMutableList()
            val redDots = normalizedRedDotList(listLength).toMutableList()
            if (index in badges.indices) {
                badges[index] = text
                redDots[index] = false
                tabBarBadges.value = badges
                tabBarRedDots.value = redDots
            }
        }
    }

    fun removeTabBarBadge(index: Int) {
        runOnUiThread {
            val listLength = getTabBarItemCount()
            val badges = normalizedBadgeList(listLength).toMutableList()
            if (index in badges.indices) {
                badges[index] = ""
                tabBarBadges.value = badges
            }
        }
    }

    fun showTabBarRedDot(index: Int) {
        runOnUiThread {
            val listLength = getTabBarItemCount()
            val badges = normalizedBadgeList(listLength).toMutableList()
            val redDots = normalizedRedDotList(listLength).toMutableList()
            if (index in redDots.indices) {
                redDots[index] = true
                badges[index] = ""
                tabBarBadges.value = badges
                tabBarRedDots.value = redDots
            }
        }
    }

    fun hideTabBarRedDot(index: Int) {
        runOnUiThread {
            val listLength = getTabBarItemCount()
            val redDots = normalizedRedDotList(listLength).toMutableList()
            if (index in redDots.indices) {
                redDots[index] = false
                tabBarRedDots.value = redDots
            }
        }
    }

    private fun normalizedBadgeList(listLength: Int): List<String> {
        return List(listLength) { index -> tabBarBadges.value.getOrElse(index) { "" } }
    }

    private fun normalizedRedDotList(listLength: Int): List<Boolean> {
        return List(listLength) { index -> tabBarRedDots.value.getOrElse(index) { false } }
    }

    private fun tabWebViewIdentifier(index: Int): String {
        val pagePath = appConfig.app.tabBar?.list?.getOrNull(index)?.pagePath ?: "unknown"
        return "tab_${miniProgram.appId}_${index}_${pagePath}"
    }

    fun setNavigationBarTitle(title: String) {
        navigationBarTitle.value = title
    }

    fun setNavigationBarColor(frontColor:String, backgroundColor: String) {
        updateActionColorStyle(frontColor)
        navigationBarBackgroundColor.value = backgroundColor
    }

    /**
     * 设置状态栏样式
     */
    private fun updateActionColorStyle(color: String) {
        val isWhiteTextStyle = isWhiteNavigationTextStyle(color)
        if (isWhiteTextStyle) {
            navigationBarTextColor.value = Color.White
        } else {
            navigationBarTextColor.value = Color.Black
        }
        runOnUiThread {
            WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars =
                !isWhiteTextStyle
        }
    }

    fun pageScrollTo(scrollTop: Int, duration: Int) {
        val targetWebView = getWebViewForBridge(apiBridgeContext)
        val runScroll: (WebView) -> Unit = { webView ->
            try {
                if (duration > 0) {
                    // Get current scroll position
                    runOnUiThread {
                        val startScroll = webView.scrollY
                        val distance = scrollTop - startScroll

                        // Create and configure the animator
                        ValueAnimator.ofFloat(0f, 1f).apply {
                            this.duration = duration.toLong()
                            interpolator =
                                AccelerateDecelerateInterpolator() // Smooth ease-in-out effect

                            addUpdateListener { animation ->
                                val fraction = animation.animatedValue as Float
                                val newScroll = startScroll + (distance * fraction).toInt()
                                webView.scrollTo(0, newScroll)
                            }

                            addListener(object : AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: Animator) {
                                    LogUtils.d(
                                        tag,
                                        "Scroll animation completed to position: $scrollTop"
                                    )
                                }
                            })

                            start()
                        }
                    }
                } else {
                    // Immediate scroll without animation
                    webView.scrollTo(0, scrollTop)
                    LogUtils.d(tag, "Immediate scroll to position: $scrollTop")
                }
            } catch (e: Exception) {
                LogUtils.e(tag, "Error during page scroll: ${e.message}")
            }
        }
        if (targetWebView != null) {
            runScroll(targetWebView)
        } else {
            withWebView(runScroll)
        }
    }


    /**
     * Reads and parses the app-config.json file from the extracted mini program
     * @return The AppConfig containing the app configuration, or null if failed
     */
    private fun readAppConfig(target: String, root: String = "main"): AppConfig? {
        try {
            val miniProgramDir = File(filesDir, target)
            val configFile = findAppConfigFile(target, root)
            if (configFile == null) {
                // Log the directory structure to help debug
                LogUtils.e(tag, "app-config.json not found in the package")
                logDirectoryStructure(miniProgramDir)
                return null
            }

            // Parse the JSON file
            val appConfigContent = configFile.readText()
            val json = Json { ignoreUnknownKeys = true }
            return json.decodeFromString<AppConfig>(appConfigContent)

        } catch (e: IOException) {
            LogUtils.e(tag, "Error reading app config: ${e.message}")
            e.printStackTrace()
            return null
        } catch (e: Exception) {
            LogUtils.e(tag, "Unexpected error reading config: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    private fun findAppConfigFile(target: String, root: String = "main"): File? {
        val miniProgramDir = File(filesDir, target)
        return listOf(
            File(miniProgramDir, "$root/app-config.json"),
            File(miniProgramDir, "app-config.json"),
        ).firstOrNull(File::isFile)
    }

    /**
     * Helper method to log the directory structure for debugging
     */
    private fun logDirectoryStructure(directory: File, indent: String = "") {
        LogUtils.d(tag, "${indent}Directory: ${directory.name}")
        directory.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                logDirectoryStructure(file, "$indent  ")
            } else {
                LogUtils.d(tag, "$indent  File: ${file.name} (${file.length()} bytes)")
            }
        } ?: LogUtils.d(tag, "${indent}Empty or not a directory")
    }

    /**
     * 安全地执行WebView操作，如果WebView尚未初始化，则将操作添加到回调队列中
     * @param action 要在WebView上执行的操作
     * @return true如果操作立即执行，false如果操作被加入队列
     */
    private fun withWebView(action: (WebView) -> Unit): Boolean {
        if (destroyedByHost) return false
        if (useTabBarContainer.value) {
            val state = tabPageStates[selectedTabIndex.intValue]
            return state?.webView?.let {
                applyWebViewColorScheme(it)
                action(it)
                true
            } ?: run {
                if (state != null) {
                    state.webViewReadyCallbacks.add(action)
                } else {
                    webViewReadyCallbacks.add(action)
                }
                LogUtils.w(tag, "Tab WebView not initialized yet, adding to callback queue")
                false
            }
        }
        return webView?.let {
            applyWebViewColorScheme(it)
            action(it)
            true
        } ?: run {
            LogUtils.w(tag, "WebView not initialized yet, adding to callback queue")
            webViewReadyCallbacks.add(action)
            false
        }
    }

    private fun withWebViewPageLoaded(action: () -> Unit) {
        pageReadyCallback = action
    }

    private fun withTabWebViewPageLoaded(index: Int, action: () -> Unit) {
        tabPageStates[index]?.pageReadyCallback = action
    }

    /**
     * 当WebView初始化完成时调用此方法
     * @param webView 初始化完成的WebView实例
     */
    private fun onWebViewReady(webView: WebView) {
        if (destroyedByHost) {
            WebViewCacheManager.evictAndDestroy(listOf(webView))
            return
        }
        this.webView = webView
        webView.setBackgroundColor(parseCssColor(backgroundColor.value).toArgb())
        applyWebViewColorScheme(webView)
        bindNativeComponentHost()
        LogUtils.d(tag, "WebView is now initialized and ready")

        // 执行所有等待的回调
        val callbacks = ArrayList(webViewReadyCallbacks) // 创建副本以避免并发修改
        webViewReadyCallbacks.clear()

        callbacks.forEach { callback ->
            try {
                callback(webView)
                LogUtils.d(tag, "Executed queued WebView callback")
            } catch (e: Exception) {
                LogUtils.e(tag, "Error executing WebView callback: ${e.message}")
            }
        }
    }

    private fun onPageReady() {
        pageReadyCallback?.invoke()
    }

    private fun onTabWebViewReady(index: Int, webView: WebView) {
        if (destroyedByHost) {
            WebViewCacheManager.evictAndDestroy(listOf(webView))
            return
        }
        val state = tabPageStates[index] ?: return
        state.webView = webView
        webView.setBackgroundColor(parseCssColor(resolveThemeValue(state.configInfo.backgroundColor)).toArgb())
        applyWebViewColorScheme(webView)
        if (index == selectedTabIndex.intValue) {
            this.webView = webView
        }
        bindNativeComponentHost(index)
        LogUtils.d(tag, "Tab WebView is ready: index=$index")

        val callbacks = ArrayList(state.webViewReadyCallbacks)
        state.webViewReadyCallbacks.clear()
        callbacks.forEach { callback ->
            try {
                callback(webView)
                LogUtils.d(tag, "Executed queued tab WebView callback: index=$index")
            } catch (e: Exception) {
                LogUtils.e(tag, "Error executing tab WebView callback: ${e.message}")
            }
        }

        if (state.bridge == null) {
            val tabBridge = createBridge(
                BridgeOptions(
                    pathInfo = state.pathInfo,
                    scene = miniProgram.scene,
                    jscore = miniApp.getJsCore(miniProgram.appId, this@DiminaActivity),
                    webview = webView,
                    isRoot = index == selectedTabIndex.intValue,
                    root = state.root,
                    appId = miniProgram.appId,
                    runtimeType = appConfig.app.runtimeType,
                    pages = appConfig.app.pages,
                    configInfo = state.configInfo,
                    referrerInfo = getLaunchReferrerInfo(),
                ),
                setAsActive = index == selectedTabIndex.intValue
            )
            state.bridge = tabBridge
            miniApp.addBridge(miniProgram.appId, tabBridge)
            withTabWebViewPageLoaded(index) {
                if (!state.bridgeStarted) {
                    LogUtils.d(tag, "Tab page loaded, starting bridge: index=$index")
                    state.bridgeStarted = true
                    tabBridge.start()
                }
            }
        }

        if (index == selectedTabIndex.intValue) {
            activateTabState(index)
        }
    }

    private fun onTabPageReady(index: Int) {
        val state = tabPageStates[index] ?: return
        state.pageReadyCallback?.invoke()
    }

    private fun onNativeOverlayReady(overlay: FrameLayout) {
        nativeOverlay = overlay
        bindNativeComponentHost()
    }

    private fun onTabNativeOverlayReady(index: Int, overlay: FrameLayout) {
        val state = tabPageStates[index] ?: return
        state.nativeOverlay = overlay
        if (index == selectedTabIndex.intValue) {
            nativeOverlay = overlay
        }
        bindNativeComponentHost(index)
    }

    private fun bindNativeComponentHost() {
        val currentWebView = webView ?: return
        val overlay = nativeOverlay ?: return
        nativeComponentHost = NativeComponentHost(this, currentWebView, overlay) { message ->
            bridgeForWebView(currentWebView)?.handleEmbeddedWebViewMessage(message)
        }.also { it.updatePageBackgroundColor(parseCssColor(backgroundColor.value).toArgb()) }
    }

    private fun bindNativeComponentHost(index: Int) {
        val state = tabPageStates[index] ?: return
        val currentWebView = state.webView ?: return
        val overlay = state.nativeOverlay ?: return
        state.nativeComponentHost = NativeComponentHost(this, currentWebView, overlay) { message ->
            bridgeForWebView(currentWebView)?.handleEmbeddedWebViewMessage(message)
        }.also { it.updatePageBackgroundColor(parseCssColor(resolveThemeValue(state.configInfo.backgroundColor)).toArgb()) }
        if (index == selectedTabIndex.intValue) {
            nativeComponentHost = state.nativeComponentHost
        }
    }

    private fun bridgeForWebView(sourceWebView: WebView): Bridge? {
        tabPageStates.values.firstOrNull { it.webView === sourceWebView }?.bridge?.let {
            return it
        }
        return bridge.takeIf { webView === sourceWebView }
    }

    fun <T> runWithBridgeContext(sourceBridge: Bridge, action: () -> T): T {
        val previousBridge = apiBridgeContext
        apiBridgeContext = sourceBridge
        return try {
            action()
        } finally {
            apiBridgeContext = previousBridge
        }
    }

    private fun getNativeComponentHostForBridge(sourceBridge: Bridge?): NativeComponentHost? {
        if (sourceBridge != null) {
            tabPageStates.values.firstOrNull { it.bridge === sourceBridge }?.nativeComponentHost?.let {
                return it
            }
            if (bridge === sourceBridge) {
                return nativeComponentHost
            }
        }
        return nativeComponentHost
    }

    fun handleNativeComponentAction(apiName: String, params: JSONObject): Boolean {
        return getNativeComponentHostForBridge(apiBridgeContext)?.handle(apiName, params) ?: false
    }

    fun dispatchNativeComponentTouch(params: JSONObject, sourceBridge: Bridge? = null): Boolean {
        return getNativeComponentHostForBridge(sourceBridge)?.dispatchTouchFromWeb(params) ?: false
    }

    fun clearNativeComponents(sourceBridge: Bridge? = null) {
        getNativeComponentHostForBridge(sourceBridge)?.clear()
    }

    private fun clearAllNativeComponents() {
        nativeComponentHost?.clear()
        tabPageStates.values.forEach { state ->
            state.nativeComponentHost?.clear()
        }
    }

    private fun releasePageResources(reason: PageStateTeardown) {
        if (pageResourcesReleased || !isMiniProgramInitialized) return
        pageResourcesReleased = true

        val bridgesToDestroy = buildList {
            bridge?.let { add(it) }
            tabPageStates.values.forEach { state ->
                state.bridge?.let { add(it) }
            }
        }.distinct()

        bridgesToDestroy.forEach { cBridge ->
            miniApp.removeBridge(miniProgram.appId, cBridge)?.destroy(reason = reason)
        }
        bridge = null
        tabPageStates.values.forEach { it.bridge = null }

        clearAllNativeComponents()
        nativeComponentHost = null
        nativeOverlay = null
        tabPageStates.values.forEach { state ->
            state.nativeComponentHost = null
            state.nativeOverlay = null
        }
    }

    private fun prepareForColdRestart() {
        // 这批 Activity 的 onDestroy 可能晚于新 Activity 创建，由重启协调者统一
        // clear MiniApp，避免旧页面再次清掉刚创建的新 JS 运行时。
        preserveMiniAppOnDestroy = true
        // 冷重启是「关掉再重开」而不是一次路由：旧运行时紧接着整体销毁，不补 onUnload。
        releasePageResources(PageStateTeardown.EXIT)

        val ownedWebViews = buildList {
            webView?.let { add(it) }
            tabPageStates.values.forEach { state ->
                state.webView?.let { add(it) }
            }
        }.distinct()
        WebViewCacheManager.evictAndDestroy(ownedWebViews)
        webView = null
        tabPageStates.values.forEach { it.webView = null }
    }

    fun onDomReady() {
        // 6.关闭首屏启动遮罩(壳布局已随 shellReady 在配置期就位,这里不再牵动壳;
        // root 页遮罩以 300ms 淡出退场,非 root 页无遮罩)
        LogUtils.d(
            tag,
            "Mini program initialization complete, hiding loading indicator"
        )
        isLoading.value = false
    }

    /**
     * 系统前后台变化由 onStart/onStop 驱动；跨小程序 API 则在 owner 交接时显式更新同一份
     * [visibilityTracker]。onPause 不作为 App 后台判据，避免权限弹窗或系统面板误杀连接。
     */
    override fun onStart() {
        super.onStart()
        if (!destroyedByHost && isMiniProgramInitialized && visibilityTracker.onActivityVisible(miniProgram.appId, this)) {
            dispatchMiniProgramShow()
        }
        suspendedForMiniProgramNavigation = false
    }

    override fun onStop() {
        if (isMiniProgramInitialized && visibilityTracker.onActivityHidden(miniProgram.appId, this)) {
            dispatchMiniProgramHide()
        }
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 系统深浅色切换「当场生效」:原生侧自行感知(manifest 声明 uiMode,
        // 不重建 Activity),按新 uiMode 更新胶囊主题并触发重组——不依赖宿主。
        val dark = (newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        Dimina.getInstance().updateCapsuleTheme(dark)
        capsuleThemeTick.intValue++
        // 壳层颜色(导航栏背景/页面背景/系统导航栏/WebView 背景/状态栏字色)按新主题重算:
        // 此时 resources.configuration 已是新值,resolveThemeValue 取到的即新主题。
        if (rawNavigationBarBackgroundColor != null) {
            reapplyThemeStyles()
        }
        // theme→JS:仅声明 darkmode 的小程序在主题「真切换」时推 systemInfo patch,
        // 触发 wx.onThemeChange;未声明 darkmode 完全不推(微信语义:未适配=永远浅色)。
        if (isDarkModeEnabled()) {
            themeTick.intValue++
            if (isMiniProgramInitialized && ThemeResolver.currentTheme(this) != lastPushedJsTheme) {
                lastPushedJsTheme = ThemeResolver.currentTheme(this)
                pushSystemInfoPatchToJsCore()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (destroyedByHost) return
        // 主题 tick:让胶囊重组重读容器配色(updateCapsuleTheme 在宿主侧发生)。
        capsuleThemeTick.intValue++
        if (suspendedForMiniProgramNavigation) {
            if (visibilityTracker.onActivityVisible(miniProgram.appId, this)) {
                dispatchMiniProgramShow()
            }
            suspendedForMiniProgramNavigation = false
        }
        getActiveBridge()?.let {
            it.pageShow()
        }
    }

    override fun onPause() {
        getActiveBridge()?.let {
            it.pageHide()
        }
        super.onPause()
    }

    override fun onDestroy() {
        initializationJob?.cancel()
        if (!isMiniProgramInitialized) {
            super.onDestroy()
            return
        }
        if (isMiniProgramInitialized) {
            activityRegistry.unregister(miniProgram.appId, this)
            if (activityRegistry.lastRegistered(miniProgram.appId) == null) {
                returnTasks.remove(miniProgram.appId)
            }
        }

        releasePageResources(pageStateTeardownReason)

        if (!preserveMiniAppOnDestroy && miniApp.isBridgeListEmpty(miniProgram.appId)) {
            // Clear resources for this specific MiniProgram
            miniApp.clear(miniProgram.appId)
        } else if (!preserveMiniAppOnDestroy && miniApp.isBridgeListEmpty()) {
            miniApp.clearAll()
        }
        super.onDestroy()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun DiminaContent(
        miniProgram: MiniProgram,
        isLoading: State<Boolean>,
        shellReady: State<Boolean>,
        modifier: Modifier = Modifier,
    ) {
        if (showActionSheet.value) {
            ActionSheet(
                buttonLabelsColor = actionTextColor,
                buttonLabels = actionSheetOptions,
                onButtonClick = { index ->
                    actionSheetCallback?.invoke(index)
                    showActionSheet.value = false
                },
                onDismiss = {
                    actionSheetCallback?.invoke(-1)
                    showActionSheet.value = false
                }
            )
        }
        if (showMiniProgramMenu.value) {
            MiniProgramMenuSheet(
                appName = miniProgram.name.ifBlank {
                    navigationBarTitle.value.ifBlank { "小程序" }
                },
                iconPng = miniProgram.iconPng,
                onReenterClick = {
                    showMiniProgramMenu.value = false
                    reenterMiniProgram()
                },
                onCloseClick = {
                    showMiniProgramMenu.value = false
                    hideMiniProgram()
                },
                onDismiss = {
                    showMiniProgramMenu.value = false
                }
            )
        }
        MediaPickerRoot(
            type = mediaType.value,
            maxCount = maxImageCount.intValue,
            videoCaptureOptions = videoCaptureOptions.value,
            context = this,
            onSelected = { uris ->
                // Convert URIs to file paths
                val paths =
                    uris.mapNotNull { uri -> PathUtils.uriToTempFile(this@DiminaActivity, uri, miniProgram.appId) }
                // Invoke the callback with the selected image paths
                imageChooseCallback?.invoke(paths)
                // Reset the callback and hide the picker
                imageChooseCallback = null
                mediaType.value = MediaType.NONE
            },
        )
        // Convert color strings to Color objects
        val bgColor = parseCssColor(backgroundColor.value)

        val navBarBgColor = parseCssColor(navigationBarBackgroundColor.value)
        val isCustomNavigation = !showNavigationBar.value
        val tabBarConfig = tabBarConfigState.value
        // 壳门控用 shellReady(配置就绪),不用 isLoading(domReady):tabBar 若等
        // domReady 才出现,内容 Box 会在首帧渲染后被压矮一截,整页上收抖动。
        val shouldShowTabBar = shellReady.value &&
            tabBarConfig != null &&
            tabBarVisible.value &&
            getTabBarIndex(currentPagePath.value) >= 0
        // tabBar 五色按当前主题解析;themeTick 在系统深浅色变化时自增,remember 据此重算
        val resolvedTabBarConfig = remember(tabBarConfig, themeTick.intValue) {
            tabBarConfig?.let { resolveTabBarColors(it) }
        }
        val statusBarHeight = ComposeWindowInsets.statusBars.asPaddingValues().calculateTopPadding()

        Box(modifier = modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    // 顶栏随 shellReady(配置就绪)在首帧前就位;若等 domReady,
                    // 内容先全屏画出、顶栏弹入时把内容整体下推,Scaffold 重测即整页抖动。
                    if (shellReady.value && showNavigationBar.value) {
                        CenterAlignedTopAppBar(
                            title = {
                                Text(
                                    text = navigationBarTitle.value,
                                    style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = navigationBarTextColor.value)
                                )
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = navBarBgColor
                            ),
                            navigationIcon = {
                                // 返回箭头（非栈底页面，或宿主隐藏胶囊）与返回首页按钮可并存：
                                // 页面配置 homeButton: true 的内页两者同时显示（微信实测样式）。
                                // 两个 IconButton 默认触摸热区都比各自图标大（自带留白），
                                // 不再额外叠加 Row 间距，否则视觉间距会远超微信原生
                                // `.navigator-hd a+a{margin-left:10px}` 的 10dp
                                Row {
                                    if (!miniProgram.root || !Dimina.getInstance().shouldShowCapsule()) {
                                        IconButton(onClick = {
                                            if (miniProgram.root) hideMiniProgram() else finish()
                                        }) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                                contentDescription = "Back",
                                                tint = navigationBarTextColor.value,
                                                modifier = Modifier.size(30.dp)
                                            )
                                        }
                                    }
                                    if (shouldShowHomeButton()) {
                                        IconButton(onClick = { navigateHome() }) {
                                            // 微信真机 home 图标带灰色圆形底，比返回箭头更粗更显眼；
                                            // 圆底色随导航栏深浅切换（浅色导航栏用深灰，深色导航栏用半透明白）
                                            Box(
                                                modifier = Modifier
                                                    .size(32.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (navigationBarTextColor.value == Color.White) {
                                                            Color(0x3DFFFFFF)
                                                        } else {
                                                            Color(0xFFD6D6D6)
                                                        }
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Home,
                                                    contentDescription = "Home",
                                                    tint = navigationBarTextColor.value,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                            actions = {
                                Spacer(modifier = Modifier.width(MenuButtonLayout.TRAILING_OCCUPIED_WIDTH_DP.dp))
                            }
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
                containerColor = bgColor
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            top = if (isCustomNavigation) 0.dp else innerPadding.calculateTopPadding()
                        )
                        .background(bgColor)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .pointerInput(Unit) {
                                // 页面触摸 → 胶囊收起成贴边把手(契约式隐藏)。
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    capsuleCollapsed.value = true
                                }
                            }
                    ) {
                        if (useTabBarContainer.value) {
                            loadedTabIndices.value.sorted().forEach { tabIndex ->
                                key(tabIndex) {
                                    val isSelected = tabIndex == selectedTabIndex.intValue
                                    DiminaWebView(
                                        onInitReady = { webView -> onTabWebViewReady(tabIndex, webView) },
                                        onPageCompleted = { onTabPageReady(tabIndex) },
                                        appId = miniProgram.appId,
                                        onNativeOverlayReady = { overlay ->
                                            onTabNativeOverlayReady(tabIndex, overlay)
                                        },
                                        identifier = tabWebViewIdentifier(tabIndex),
                                        isPageActive = isSelected,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .alpha(if (isSelected) 1f else 0f)
                                            .zIndex(if (isSelected) 1f else 0f)
                                    )
                                }
                            }
                        } else {
                            // 始终创建DiminaWebView
                            DiminaWebView(
                                onInitReady = { webView -> onWebViewReady(webView) },
                                onPageCompleted = { onPageReady() },
                                appId = miniProgram.appId,
                                onNativeOverlayReady = { overlay -> onNativeOverlayReady(overlay) },
                            )
                        }

                        // 加载遮罩层使用 AnimatedVisibility 只添加淡出效果
                        androidx.compose.animation.AnimatedVisibility(
                            visible = isLoading.value && miniProgram.root && Dimina.getInstance().shouldShowLaunchLoading(),
                            exit = fadeOut(animationSpec = tween(300)),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            LoadingAnimation(miniProgram)
                        }
                    }

                    resolvedTabBarConfig?.takeIf { shouldShowTabBar }?.let { visibleTabBarConfig ->
                        DiminaTabBar(
                            tabBarConfig = visibleTabBarConfig,
                            selectedIndex = selectedTabIndex.intValue,
                            appId = miniProgram.appId,
                            filesDir = filesDir,
                            badges = tabBarBadges.value,
                            redDots = tabBarRedDots.value,
                            onSelected = { index ->
                                visibleTabBarConfig.list.getOrNull(index)?.let { item ->
                                    switchTab(item.pagePath)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            if (!isCustomNavigation) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .height(statusBarHeight)
                        .background(navBarBgColor)
                )
            }

            if (shellReady.value && com.didi.dimina.Dimina.getInstance().shouldShowCapsule()) {
                val configuration = LocalConfiguration.current
                val (windowInfo, menuRect) = remember(
                    configuration.screenWidthDp,
                    configuration.screenHeightDp,
                    configuration.orientation,
                    statusBarHeight,
                ) {
                    val currentWindowInfo = Utils.getMiniProgramSystemInfo(this@DiminaActivity)
                    currentWindowInfo to MenuButtonLayout.calculate(
                        windowWidth = currentWindowInfo.getInt("windowWidth"),
                        statusBarHeight = currentWindowInfo.getInt("statusBarHeight"),
                    )
                }
                // 胶囊垂直锚点与 CenterAlignedTopAppBar 的 windowInsets 同源:systemBars ∪
                // displayCutout 的顶缘(compose 精确值;M3 内部即
                // systemBarsForVisualComponents.only(Horizontal+Top))。M3 标题内容区高
                // NAVIGATION_BAR_CONTENT_HEIGHT_DP(64dp),胶囊 HEIGHT_DP(32dp)垂直居中
                // → 上缘再 +16dp,胶囊中线即标题中线。旧实现经 Utils.getMiniProgramSystemInfo
                // 的 px→dp 往返(roundToInt,兜底还可能落到 status_bar_height 资源值)是
                // 独立第二通道,四舍五入与 fallback 相对标题栏产生垂直漂移(用户反馈的
                // 「胶囊偏下」);这里改成同源锚点后不再有第二通道。
                val topBarInsetTop = ComposeWindowInsets.systemBars
                    .union(ComposeWindowInsets.displayCutout)
                    .asPaddingValues()
                    .calculateTopPadding()
                val capsuleTopPadding = topBarInsetTop +
                    ((MenuButtonLayout.NAVIGATION_BAR_CONTENT_HEIGHT_DP - MenuButtonLayout.HEIGHT_DP) / 2).dp

                // 微信语义配色:底=导航栏解析色半透明 scrim(透出导航栏,随页面
                // navigationBarBackgroundColor 变化);图标=navigationBarTextStyle
                // (black→深色,white→浅色,与标题同源,深浅色切换实时刷新)。
                // 宿主显式配置过 Builder.setCapsuleBackgroundColor/setCapsuleIconColor
                // 时优先用宿主颜色(可自带 alpha,微信默认语义停用)。remember 把
                // compose 色值纳入 key:@ 变量解析/深浅色切换即时生效;tick 保留
                // resume 后重读宿主配置的语义。
                val capsuleBg = remember(
                    capsuleThemeTick.intValue,
                    navBarBgColor,
                    navigationBarTextColor.value,
                ) {
                    Color(
                        CapsuleStyle.backgroundColor(
                            navBarColor = navBarBgColor.toArgb(),
                            explicit = Dimina.getInstance().takeIf { it.hasExplicitCapsuleColors() }
                                ?.getCapsuleBackgroundColor(),
                        )
                    )
                }
                val capsuleFg = remember(
                    capsuleThemeTick.intValue,
                    navigationBarTextColor.value,
                ) {
                    Color(
                        CapsuleStyle.iconColor(
                            textStyleWhite = navigationBarTextColor.value == Color.White,
                            explicit = Dimina.getInstance().takeIf { it.hasExplicitCapsuleColors() }
                                ?.getCapsuleIconColor(),
                        )
                    )
                }
                // Header 右侧通知入口:宿主通过 Dimina.setNotifyEntry 打开,从未调用则不渲染。
                // 它只是入口壳(不参与胶囊收起逻辑——收起态下仍常驻),点击回调给宿主;
                // 配色跟随胶囊同一套 capsuleThemeTick / navigationBarTextStyle 机制。
                val notifyEntryState = Dimina.getInstance().notifyEntryState()
                if (notifyEntryState.visible) {
                    MiniProgramNotifyEntry(
                        iconColor = capsuleFg,
                        unread = notifyEntryState.unread,
                        onClick = { notifyEntryState.onEntryClick?.invoke() },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(
                                top = capsuleTopPadding,
                                end = (windowInfo.getInt("windowWidth") - menuRect.right).dp +
                                    MenuButtonLayout.WIDTH_DP.dp + NOTIFY_ENTRY_GAP_DP.dp
                            )
                            .zIndex(10f)
                    )
                }
                // 收起后不再自动展开(旧「6s 空闲定时器」已删,2026-11-11):收起即保持隐藏,
                // 唯一的展开入口是下方贴边把手的点击热区。滑动/静置都不会再把胶囊召唤出来。
                if (capsuleCollapsed.value) {
                    // 收起态:贴边发丝细条(2026-11-11 重设计,取代旧「半透明方块+黑描边+两点」)。
                    // 视觉 = 5×32dp 细条:与胶囊同高(MenuButtonLayout.HEIGHT_DP)、同一垂直锚点,
                    // 颜色 = 图标色 @ COLLAPSED_HANDLE_VISUAL_ALPHA(随 navigationBarTextStyle 自
                    // 适应,深浅色页面都可见);无底、无描边。
                    // 热区 = 44×48dp(视觉上下各外扩 8dp,满足 Android 48dp 最小可点标准),
                    // 从屏幕右缘向内延伸,手指不用精确瞄准细条。
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .zIndex(10f)
                            .padding(top = capsuleTopPadding - COLLAPSED_HANDLE_HIT_OVERHANG_DP.dp)
                            .size(
                                width = COLLAPSED_HANDLE_HIT_WIDTH_DP.dp,
                                height =
                                    (MenuButtonLayout.HEIGHT_DP + 2 * COLLAPSED_HANDLE_HIT_OVERHANG_DP).dp,
                            )
                            .clickable { capsuleCollapsed.value = false },
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(
                                    width = COLLAPSED_HANDLE_VISUAL_WIDTH_DP.dp,
                                    height = MenuButtonLayout.HEIGHT_DP.dp,
                                )
                                .background(
                                    capsuleFg.copy(alpha = COLLAPSED_HANDLE_VISUAL_ALPHA),
                                    RoundedCornerShape(
                                        topStart = (COLLAPSED_HANDLE_VISUAL_WIDTH_DP / 2).dp,
                                        bottomStart = (COLLAPSED_HANDLE_VISUAL_WIDTH_DP / 2).dp,
                                    ),
                                ),
                        )
                    }
                } else {
                    MiniProgramCapsuleButton(
                        backgroundColor = capsuleBg,
                        iconColor = capsuleFg,
                        onMoreClick = {
                            showMiniProgramMenu.value = true
                        },
                        onCloseClick = { hideMiniProgram() },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(
                                top = capsuleTopPadding,
                                end = (windowInfo.getInt("windowWidth") - menuRect.right).dp
                            )
                            .zIndex(10f)
                    )
                }
            }
        }
    }

    /** Hide the owned task without destroying its Activities, WebViews or JS runtime. */
    fun isMiniProgramForeground(): Boolean = isMiniProgramInitialized && visibilityTracker.isForeground(miniProgram.appId)

    fun hideMiniProgram() {
        if (!isMiniProgramForeground() || isFinishing) return
        if (!Dimina.getInstance().isMultiTaskEnabled()) {
            // Shared-task pages must be popped; moving this task back would hide the host too.
            exitMiniProgram()
            return
        }
        activityRegistry.snapshot(miniProgram.appId).forEach { it.retainedByHost = true }
        window.decorView.clearFocus()
        // Queue options before another task can receive onStart.
        queueOpenerReturn(null)
        moveTaskToBack(true)
        val returnTask = returnTasks[miniProgram.appId]
        getSystemService(ActivityManager::class.java).appTasks
            .firstOrNull { it.taskInfo.taskId == returnTask }?.moveToFront()
    }

    private fun closeMiniProgram() {
        activityRegistry.closeAll(miniProgram.appId) { activity ->
            // 退出对齐微信的「小程序切入后台」：只派发 App.onHide，整栈页面都不收 onUnload。
            activity.pageStateTeardownReason = PageStateTeardown.EXIT
            activity.finish()
        }
    }

    /** Opens a resolved bundled mini program as a new root above this one. */
    fun navigateToMiniProgram(target: MiniProgram) {
        suspendForMiniProgramNavigation()
        try {
            miniApp.openApp(this, target)
        } catch (error: Exception) {
            resumeAfterMiniProgramNavigationFailure()
            throw error
        }
    }

    /**
     * Returns to the mini program that opened this one. The Android task stack reveals the opener;
     * [MiniApp] supplies exactly one fresh App.onShow payload when its top Activity resumes.
     */
    fun navigateBackMiniProgram(extraData: JSONObject): Boolean {
        if (!queueOpenerReturn(extraData)) return false
        suspendForMiniProgramNavigation()
        closeMiniProgram()
        return true
    }

    fun exitMiniProgram() {
        // exit has no return extraData, but revealing a live opener is still scene 1038
        // ("returned from another mini program"). Queue it before closing this Activity;
        // MiniApp consumes the payload exactly once when the opener resumes.
        if (queueOpenerReturn(extraData = null)) {
            suspendForMiniProgramNavigation()
        }
        closeMiniProgram()
    }

    private fun suspendForMiniProgramNavigation() {
        if (suspendedForMiniProgramNavigation) return
        getActiveBridge()?.pageHide()
        if (visibilityTracker.onMiniProgramHidden(miniProgram.appId)) {
            dispatchMiniProgramHide()
        }
        suspendedForMiniProgramNavigation = true
    }

    private fun resumeAfterMiniProgramNavigationFailure() {
        if (!suspendedForMiniProgramNavigation) return
        if (visibilityTracker.onActivityVisible(miniProgram.appId, this)) {
            dispatchMiniProgramShow()
        }
        getActiveBridge()?.pageShow()
        suspendedForMiniProgramNavigation = false
    }

    /**
     * [MiniApp.getJsCore] would synchronously unzip and evaluate service.js on whatever thread
     * calls it; onStart/onStop run on the main thread, so this only forwards to an instance that
     * `initialize()` has already bootstrapped off it. A recreate that bypasses [MiniApp.openApp]
     * (a config change, or a process-death restore from Recents) can call this before that
     * bootstrap lands - the pending intent is not lost, it is left for
     * [reconcileAppVisibilityWithCore] to replay once a real JsCore exists.
     */
    private var retainedByHost = false

    private fun dispatchMiniProgramShow() {
        retainedByHost = false
        miniApp.retentionVisibility(miniProgram.appId, true)
        val jsCore = miniApp.peekJsCore(miniProgram.appId)
        if (jsCore != null) {
            val pendingEntry = miniApp.consumePendingAppShowOptions(miniProgram.appId)
            val showOptions = (pendingEntry ?: JSONObject().apply {
                put("scene", miniProgram.scene)
                put("referrerInfo", JSONObject())
            }).apply {
                getActiveBridge()?.options?.pathInfo?.let { pathInfo ->
                    if (!has("pagePath")) put("pagePath", pathInfo.pagePath)
                    if (!has("query")) put("query", pathInfo.query ?: JSONObject())
                }
            }
            jsCore.appShow(showOptions, newEntry = pendingEntry != null)
        }
        com.didi.dimina.api.network.WebSocketManager.shared.setBackgrounded(miniProgram.appId, false)
    }

    private fun dispatchMiniProgramHide() {
        miniApp.retentionVisibility(miniProgram.appId, false)
        miniApp.peekJsCore(miniProgram.appId)?.appHide()
        com.didi.dimina.api.network.WebSocketManager.shared.setBackgrounded(miniProgram.appId, true)
    }

    /**
     * A freshly-created JsCore's AppVisibilityLedger defaults to "shown" - the service-side App's
     * own construction already fires an implicit onShow. [dispatchMiniProgramShow]/
     * [dispatchMiniProgramHide] may have run earlier in onStart/onStop with no JsCore to forward
     * to yet; reconcile that default against the tracker's live truth once this Activity's
     * `initialize()` hands a real JsCore to its Bridge. Idempotent when the JsCore already
     * existed and was already current.
     */
    private fun reconcileAppVisibilityWithCore() {
        if (visibilityTracker.isForeground(miniProgram.appId)) {
            dispatchMiniProgramShow()
        } else {
            dispatchMiniProgramHide()
        }
    }

    private fun queueOpenerReturn(extraData: JSONObject?): Boolean {
        val openerAppId = miniProgram.openerAppId ?: return false
        if (!miniApp.isRunning(openerAppId)) return false
        val referrerInfo = JSONObject().apply {
            put("appId", miniProgram.appId)
            if (extraData != null) {
                put("extraData", JSONObject(extraData.toString()))
            }
        }
        miniApp.setPendingAppShowOptions(
            openerAppId,
            JSONObject().apply {
                put(
                    "scene",
                    com.didi.dimina.api.route.MiniProgramRouteContract.SCENE_RETURNED_FROM_MINI_PROGRAM,
                )
                put("referrerInfo", referrerInfo)
            },
        )
        // Returning ends this presentation's opener relationship. A later host-side cleanup
        // of the retained task must not enqueue a second return on the already-restored app.
        activityRegistry.snapshot(miniProgram.appId).forEach { activity ->
            activity.miniProgram = activity.miniProgram.copy(openerAppId = null, referrerExtraData = null)
        }
        return true
    }

    fun restartMiniProgram(path: String) {
        coldRestartMiniProgram(miniProgram.copy(root = true, path = path))
    }

    private fun reenterMiniProgram() {
        val entryPagePath = getDefaultEntryPagePath() ?: miniProgram.path
        val reentryProgram = miniProgram.copy(root = true, path = entryPagePath)
        coldRestartMiniProgram(reentryProgram)
    }

    fun applyUpdate() {
        CoroutineScope(Dispatchers.IO).launch {
            // 激活前先读 pending 版本:激活后 pending 目录已被搬进 active。
            val pending = RemoteUpdateManager.peekPendingUpdate(applicationContext, miniProgram.appId)
            val activated = RemoteUpdateManager.activatePendingUpdate(
                applicationContext,
                miniProgram.appId,
            )
            if (!activated) {
                miniApp.postUpdateStatus(miniProgram.appId, "updatefail")
                MiniApp.getInstance().notifyUpdateFailed(miniProgram.appId, null)
                return@launch
            }
            MiniApp.getInstance().notifyUpdateApplied(
                miniProgram.appId,
                pending?.versionCode ?: VersionUtils.getAppVersion(miniProgram.appId),
                pending?.versionName ?: "",
            )

            withContext(Dispatchers.Main) {
                // Read the entry page from the newly activated app-config instead
                // of carrying the old package's route into the new runtime.
                val updatedMiniProgram = miniProgram.copy(root = true, path = null)
                coldRestartMiniProgram(updatedMiniProgram)
            }
        }
    }

    private fun coldRestartMiniProgram(program: MiniProgram) {
        if (isFinishing || isDestroyed) return
        // Re-enter is an app-level reload, not wx.reLaunch: destroy the shared
        // JS runtime and transient API resources so the new root Activity runs
        // the complete initialization and loading flow again.
        activityRegistry.snapshot(miniProgram.appId).asReversed().forEach { activity ->
            activity.prepareForColdRestart()
        }
        miniApp.clear(miniProgram.appId)
        // Start while the source Activity still belongs to a live task. Finishing the
        // entire stack first lets Android allocate a new task and leave the old card behind.
        DiminaActivity.launch(this, program)
        activityRegistry.closeAll(miniProgram.appId) { it.finish() }
    }

    private fun getDefaultEntryPagePath(): String? {
        if (!::appConfig.isInitialized) {
            return null
        }
        return appConfig.app.entryPagePath ?: appConfig.app.pages.firstOrNull()
    }

    private fun getLaunchReferrerInfo(): JSONObject? {
        val openerAppId = miniProgram.openerAppId ?: return null
        val extraData = miniProgram.referrerExtraData
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: JSONObject()
        return JSONObject().apply {
            put("appId", openerAppId)
            put("extraData", extraData)
        }
    }

    /**
     * 清空页面栈并重新打开到 [url]，与 wx.reLaunch 行为一致
     */
    fun reLaunchTo(url: String) {
        relaunchStack(url)
    }

    /**
     * 精确关闭该小程序全部页面 Activity 实例并以新根页重启：preserve=true 因为
     * wx.reLaunch/switchTab 找不到 root 实例时的兜底都只清页面栈，不重启 JS 实例
     * （与 applyUpdate 的关键区别）。CLEAR_TOP 只能按 Activity 组件匹配，清不掉
     * 共享同一 DiminaActivity 类的下层实例，所以改用 activityRegistry 精确关栈
     */
    private fun relaunchStack(url: String) {
        if (isFinishing || isDestroyed) return
        activityRegistry.snapshot(miniProgram.appId).forEach { activity ->
            activity.preserveMiniAppOnDestroy = true
        }
        // Keep the source task alive until its replacement root has been launched.
        // No NEW_TASK/CLEAR_TOP flags: shared host tasks and other apps must survive.
        DiminaActivity.launch(this, miniProgram.copy(root = true, path = url))
        activityRegistry.closeAll(miniProgram.appId) { it.finish() }
    }

    /**
     * 返回首页（导航栏 home 按钮的唯一路由入口），终态都是只剩首页：
     * 首页是 tab 页走 switchTab（保留其它 tab 状态并露出 tabBar，自带清非 tab 栈）；
     * 首页非 tab 且当前是栈底，按 redirectTo 语义原地换页（updatePath）；
     * 非栈底（homeButton: true 的内页）须清整栈，走 reLaunchTo
     */
    private fun navigateHome() {
        val entryPagePath = getDefaultEntryPagePath() ?: return
        when {
            getTabBarIndex(entryPagePath) >= 0 -> switchTab(entryPagePath)
            miniProgram.root -> updatePath(entryPagePath)
            else -> reLaunchTo(entryPagePath)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MiniProgramMenuSheet(
        appName: String,
        iconPng: ByteArray?,
        onReenterClick: () -> Unit,
        onCloseClick: () -> Unit,
        onDismiss: () -> Unit
    ) {
        // 面板配色:原生壳层 chrome,微信语义=跟系统深浅色走(与小程序是否声明
        // darkmode 无关);宿主显式注入的覆写仍优先(dsh-go 按宿主主题注入)。
        val dark = Dimina.getInstance().capsuleDarkOverride() ?: isSystemInDarkTheme()
        val sheetContainer = if (dark) Color(0xFF1D1F27) else Color.White
        val sheetIconBg = if (dark) Color(0xFF2A2D34) else Color(0xFFF4F4F4)
        val sheetAvatar = if (dark) Color(0xFF9AA1A9) else Color(0xFF8A8A8A)
        val sheetTitle = if (dark) Color(0xFFE8EAED) else Color(0xFF202020)
        val sheetSub = if (dark) Color(0xFF9AA1A9) else Color(0xFF9A9A9A)
        val sheetTileBg = if (dark) Color(0xFF2A2D34) else Color(0xFFF8F8F8)
        val sheetTileIcon = if (dark) Color(0xFFE8EAED) else Color(0xFF333333)
        val sheetTileLabel = if (dark) Color(0xFFB9BFC7) else Color(0xFF686868)
        val sheetDividerTop = if (dark) Color(0xFF2A2D34) else Color(0xFFF2F2F2)
        val sheetDividerBottom = if (dark) Color(0xFF2A2D34) else Color(0xFFEDEDED)
        val sheetCancel = if (dark) Color(0xFF7D90B8) else Color(0xFF576B95)
        // 头部图标:宿主传入的 PNG(桌面/市场同款字形)优先,无则回落首字母瓦片
        val headerIcon = remember(iconPng) {
            iconPng?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
        }
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
            containerColor = sheetContainer,
            dragHandle = { }
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(sheetIconBg),
                        contentAlignment = Alignment.Center
                    ) {
                        val header = headerIcon
                        if (header != null) {
                            Image(
                                bitmap = header.asImageBitmap(),
                                contentDescription = appName,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text(
                                text = appName.take(1).ifBlank { "小" },
                                fontSize = 18.sp,
                                color = sheetAvatar,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    Column(modifier = Modifier.padding(start = 14.dp)) {
                        Text(
                            text = appName,
                            fontSize = 18.sp,
                            color = sheetTitle,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "小程序",
                            fontSize = 14.sp,
                            color = sheetSub
                        )
                    }
                }

                HorizontalDivider(color = sheetDividerTop, thickness = 1.dp)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    MiniProgramMenuItem(
                        label = "重新进入\n小程序",
                        icon = Icons.Filled.Refresh,
                        contentDescription = "重新进入小程序",
                        onClick = onReenterClick,
                        tileBackground = sheetTileBg,
                        iconTint = sheetTileIcon,
                        labelColor = sheetTileLabel,
                        modifier = Modifier.width(78.dp)
                    )
                    MiniProgramMenuItem(
                        label = "关闭小程序",
                        icon = Icons.Filled.Close,
                        contentDescription = "关闭小程序",
                        onClick = onCloseClick,
                        tileBackground = sheetTileBg,
                        iconTint = sheetTileIcon,
                        labelColor = sheetTileLabel,
                        modifier = Modifier.width(78.dp)
                    )
                }

                HorizontalDivider(color = sheetDividerBottom, thickness = 1.dp)
                Text(
                    text = "取消",
                    fontSize = 18.sp,
                    color = sheetCancel,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onDismiss)
                        .padding(vertical = 18.dp)
                )
            }
        }
    }

    @Composable
    private fun MiniProgramMenuItem(
        label: String,
        icon: ImageVector,
        contentDescription: String,
        onClick: () -> Unit,
        tileBackground: Color,
        iconTint: Color,
        labelColor: Color,
        modifier: Modifier = Modifier
    ) {
        Column(
            modifier = modifier.clickable(onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tileBackground),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = iconTint,
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                text = label,
                fontSize = 13.sp,
                color = labelColor,
                textAlign = TextAlign.Center,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }

    /**
     * Header 通知入口(铃铛) + 未读角标。
     *
     * 框架不认识「通知」语义:这只是宿主打开的入口壳,图标/角标颜色跟随胶囊主题
     * ([capsuleFg] 由 capsuleThemeTick + navigationBarTextStyle 派生),不读任何业务状态。
     * 角标:未读 >0 显示红点+数字,=0 只显示铃铛。
     */
    @Composable
    private fun MiniProgramNotifyEntry(
        iconColor: Color,
        unread: Int,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val badgeColor = Color(0xFFFA5151)
        // 外层不裁剪:角标允许探出圆角热区;内层单独裁剪出圆形点击涟漪。
        Box(modifier = modifier.size(NOTIFY_ENTRY_SIZE_DP.dp)) {
            Box(
                modifier = Modifier
                    .size(NOTIFY_ENTRY_SIZE_DP.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.size(20.dp)) {
                    val w = size.width
                    val h = size.height
                    val stroke = 1.6.dp.toPx()
                    // 铃身:圆顶 + 下摆外扩,描边绘制(不依赖 material-icons-extended)。
                    val bell = Path().apply {
                        moveTo(w * 0.16f, h * 0.70f)
                        cubicTo(w * 0.16f, h * 0.44f, w * 0.26f, h * 0.30f, w * 0.50f, h * 0.30f)
                        cubicTo(w * 0.74f, h * 0.30f, w * 0.84f, h * 0.44f, w * 0.84f, h * 0.70f)
                        lineTo(w * 0.92f, h * 0.78f)
                        lineTo(w * 0.08f, h * 0.78f)
                        close()
                    }
                    drawPath(
                        path = bell,
                        color = iconColor,
                        style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                    // 顶部提钮
                    drawLine(
                        color = iconColor,
                        start = Offset(w * 0.50f, h * 0.20f),
                        end = Offset(w * 0.50f, h * 0.30f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                    // 铃舌
                    drawCircle(color = iconColor, radius = w * 0.075f, center = Offset(w * 0.50f, h * 0.86f))
                }
            }
            if (unread > 0) {
                val label = if (unread > 99) "99+" else unread.toString()
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 3.dp, y = (-1).dp)
                        .height(14.dp)
                        .clip(CircleShape)
                        .background(badgeColor)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }

    @Composable
    private fun MiniProgramCapsuleButton(
        backgroundColor: Color,
        iconColor: Color,
        onMoreClick: () -> Unit,
        onCloseClick: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        // 描边/中缝随图标色派生(微信观感:深图标≈浅灰 hairline,白图标≈半透明白),
        // 半透明底上不固定灰——固定灰在深色导航栏上不可见。
        val borderColor = Color(CapsuleStyle.borderColor(iconColor.toArgb()))
        val separatorColor = Color(CapsuleStyle.separatorColor(iconColor.toArgb()))
        val shape = RoundedCornerShape((MenuButtonLayout.HEIGHT_DP / 2).dp)

        Box(
            modifier = modifier
                .size(
                    width = MenuButtonLayout.WIDTH_DP.dp,
                    height = MenuButtonLayout.HEIGHT_DP.dp,
                )
                // Elevation shadows fill the outline and would darken translucent backgrounds.
                .shadow(if (backgroundColor.alpha == 1f) 1.dp else 0.dp, shape, clip = false)
                .clip(shape)
                .background(backgroundColor)
                .border(BorderStroke(0.5.dp, borderColor), shape)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(
                            width = ((MenuButtonLayout.WIDTH_DP - 1) / 2).dp,
                            height = MenuButtonLayout.HEIGHT_DP.dp,
                        )
                        .clickable(onClick = onMoreClick),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(width = 20.dp, height = 10.dp)) {
                        val centerY = size.height / 2
                        val gap = 7.2.dp.toPx()
                        val centerX = size.width / 2
                        drawCircle(iconColor, 1.9.dp.toPx(), Offset(centerX - gap, centerY))
                        drawCircle(iconColor, 3.2.dp.toPx(), Offset(centerX, centerY))
                        drawCircle(iconColor, 1.9.dp.toPx(), Offset(centerX + gap, centerY))
                    }
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(16.dp)
                        .align(Alignment.CenterVertically)
                        .background(separatorColor)
                )

                Box(
                    modifier = Modifier
                        .size(
                            width = ((MenuButtonLayout.WIDTH_DP - 1) / 2).dp,
                            height = MenuButtonLayout.HEIGHT_DP.dp,
                        )
                        .clickable(onClick = onCloseClick),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(22.dp)) {
                        val center = Offset(size.width / 2, size.height / 2)
                        drawCircle(
                            color = iconColor,
                            radius = 7.8.dp.toPx(),
                            center = center,
                            style = Stroke(width = 2.4.dp.toPx())
                        )
                        drawCircle(
                            color = iconColor,
                            radius = 3.1.dp.toPx(),
                            center = center
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun LoadingAnimation(miniProgram: MiniProgram) {
        // 使用 remember 记忆颜色值，避免每次重组时重新创建
        val iconColor = remember(miniProgram.name) { Color(Utils.generateColorFromName(miniProgram.name)) }
        val trackColor = remember { Color.LightGray.copy(alpha = 0.3f) }
        val dotColor = remember { Color(0xFF4CAF50) }
        val firstLetter = remember(miniProgram.name) { miniProgram.name.take(1).ifBlank { "小" } }

        // 使用 InfiniteTransition 创建无限循环动画
        val infiniteTransition = rememberInfiniteTransition(label = "loadingRotation")
        val rotation by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 1000,
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Restart
            ),
            label = "rotation"
        )

        // 添加背景的Box作为遮罩层
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White), // 添加背景遮住后面的 Webview
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier.size(80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val dotRadiusDp = 4.dp

                    Canvas(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // 预计算常量值，避免在绘制时重复计算
                        val minSize = minOf(size.width, size.height)
                        val circleRadius = minSize / 4
                        val centerX = size.width / 2
                        val centerY = size.height / 2
                        val orbitRadius = circleRadius * 1.5f
                        val dotRadius = dotRadiusDp.toPx()

                        // 绘制大圆（背景）
                        drawCircle(
                            color = iconColor,
                            radius = circleRadius,
                            center = Offset(centerX, centerY)
                        )

                        // 绘制轨道
                        drawCircle(
                            color = trackColor,
                            radius = orbitRadius,
                            center = Offset(centerX, centerY),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                        )

                        // 绘制小圆点（旋转）- 只有这部分需要在每一帧更新
                        val angleInRadians = Math.toRadians(rotation.toDouble()).toFloat()
                        val dotX = centerX + orbitRadius * cos(angleInRadians)
                        val dotY = centerY + orbitRadius * sin(angleInRadians)

                        drawCircle(
                            color = dotColor,
                            radius = dotRadius,
                            center = Offset(dotX, dotY)
                        )
                    }

                    Text(
                        text = firstLetter,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                // 底部文本
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = miniProgram.name,
                    style = TextStyle(
                        fontSize = 16.sp,
                        color = Color.Black,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }
    }

    /**
     * 更新当前页面的路径并重新加载
     * @param url 新的页面路径
     */
    fun updatePath(url: String) {
        runOnUiThread {
            // 获取当前的 bridge
            getActiveBridge()?.let { currentBridge ->
                val activeTabIndex = if (useTabBarContainer.value) selectedTabIndex.intValue else -1
                val activeTabState = tabPageStates[activeTabIndex]
                val pathInfo = Utils.queryPath(url)
                val pageConfig = appConfig.modules[pathInfo.pagePath]
                val mergedPageConfig = Utils.mergePageConfig(appConfig.app, pageConfig)

                // 更新页面配置和样式
                syncTabBarState(pathInfo.pagePath)
                setInitialStyle(mergedPageConfig)
                activeTabState?.let { state ->
                    state.pathInfo = pathInfo
                    state.root = pageConfig?.root ?: "main"
                    state.configInfo = mergedPageConfig
                    state.bridgeStarted = false
                    // 页面身份被替换，清掉上一任页面的隐藏标记，否则会跨 redirectTo 泄漏到新页面
                    state.homeButtonHidden.value = false
                }

                // redirectTo 修复（2026-11-11）：旧实现 destroy(true)+init(false) 原位复用
                // Bridge/WebView，跳过 addJavascriptInterface 重挂、jscore 回调重注册与
                // canvasChannel 重建——reload 后 render→container 事件链静默死亡，新页面
                // 所有 bindtap 永久失效（真机实证；navigateTo=新 Activity 全新桥、
                // switchTab=独立桥完整 init 均正常）。改走与首次加载完全一致的已验证
                // 路径：完整销毁旧桥 → createBridge/init(true) 重建全新桥（旧 WebView
                // 复用，重挂注入接口幂等；新桥=新 bridgeId 无回调冲突）。
                miniApp.removeBridge(miniProgram.appId, currentBridge)
                currentBridge.destroy(false)

                if (activeTabState != null && activeTabIndex >= 0) {
                    // tab 容器：置空桥状态，走 onTabWebViewReady 既有重建路径
                    // （bridge==null 即全量 createBridge，start 仍由 pageLoaded 门控）
                    activeTabState.bridge = null
                    activeTabState.webView?.let { onTabWebViewReady(activeTabIndex, it) }
                } else {
                    // 非 tab 页：克隆旧桥 options（保留 isRoot/scene/referrerInfo 等语义，
                    // 仅替换路径/根目录/页面配置），与 navigateTo 同款全量建桥
                    withWebView { webView ->
                        val freshBridge = createBridge(
                            BridgeOptions(
                                pathInfo = pathInfo,
                                scene = currentBridge.options.scene,
                                jscore = currentBridge.options.jscore,
                                webview = webView,
                                isRoot = currentBridge.options.isRoot,
                                root = pageConfig?.root ?: "main",
                                appId = currentBridge.options.appId,
                                runtimeType = currentBridge.options.runtimeType,
                                pages = currentBridge.options.pages,
                                configInfo = mergedPageConfig,
                                referrerInfo = currentBridge.options.referrerInfo,
                            )
                        )
                        // Add bridge to MiniApp's bridge list for this appId
                        miniApp.addBridge(miniProgram.appId, freshBridge)

                        withWebViewPageLoaded {
                            LogUtils.d(tag, "Page loaded, starting fresh bridge (redirectTo)")
                            freshBridge.start()
                        }
                    }
                }
            }
        }
    }

    fun getMiniProgram(): MiniProgram {
        return this.miniProgram
    }

    companion object {
        const val MINI_PROGRAM_KEY = "mini_program"

        // 字段化启动参数(见 [launch]):parcel 对象 extra 在混淆构建下是雷。
        private const val EXTRA_APP_ID = "dimina_mp_app_id"
        private const val EXTRA_NAME = "dimina_mp_name"
        private const val EXTRA_ROOT = "dimina_mp_root"
        private const val EXTRA_PATH = "dimina_mp_path"
        private const val EXTRA_VERSION_CODE = "dimina_mp_version_code"
        private const val EXTRA_VERSION_NAME = "dimina_mp_version_name"
        private const val EXTRA_ICON_PNG = "dimina_mp_icon_png"
        private const val EXTRA_UPDATE_MANIFEST_URL = "dimina_mp_update_manifest_url"
        private const val EXTRA_SCENE = "dimina_mp_scene"
        private const val EXTRA_OPENER_APP_ID = "dimina_mp_opener_app_id"
        private const val EXTRA_REFERRER_EXTRA_DATA = "dimina_mp_referrer_extra_data"
        private const val EXTRA_ENV_VERSION = "dimina_mp_env_version"

        private val activityRegistry = MiniProgramActivityRegistry<DiminaActivity>()

        /** 小程序前后台判据的唯一真相源，见 [DiminaActivity.onStart]/[DiminaActivity.onStop]。 */
        private val visibilityTracker = MiniProgramVisibilityTracker<DiminaActivity>()

        private const val HOST_GENERATION_KEY = "dimina_host_generation"
        private const val HOST_SESSION_KEY = "dimina_host_session"

        // Header 通知入口:铃铛热区尺寸与它到胶囊左缘的间距(§6.3)。
        private const val NOTIFY_ENTRY_SIZE_DP = 32
        private const val NOTIFY_ENTRY_GAP_DP = 8

        // 胶囊收起态把手(2026-11-11 重设计):视觉细条 + 超尺寸点击热区。
        private const val COLLAPSED_HANDLE_VISUAL_WIDTH_DP = 5

        /** 收起态细条不透明度:图标色 @ alpha,深浅色页面都安静可见。 */
        private const val COLLAPSED_HANDLE_VISUAL_ALPHA = 0.35f

        /** 收起态热区宽:从屏幕右缘向内,远大于视觉宽,点哪都能展开。 */
        private const val COLLAPSED_HANDLE_HIT_WIDTH_DP = 44

        /** 热区垂直外扩:32dp 视觉上下各扩 8dp → 48dp,满足 Android 最小可点标准。 */
        private const val COLLAPSED_HANDLE_HIT_OVERHANG_DP = 8

        private val hostSession = java.util.UUID.randomUUID().toString()
        private var hostGeneration = 0L
        private val pendingLaunches = mutableSetOf<String>()
        private val returnTasks = mutableMapOf<String, Int>()

        private fun isObsoleteHostLaunch(intent: Intent): Boolean {
            // TaskInfo.baseIntent may use the boot ClassLoader. On Android 12, even a
            // string lookup unparcels all extras, including our MiniProgram Parcelable.
            intent.setExtrasClassLoader(MiniProgram::class.java.classLoader)
            // A previous process's saved Intent is a legitimate cold restore, not an
            // in-flight launch canceled by this process's destroyAllMiniPrograms call.
            return intent.getStringExtra(HOST_SESSION_KEY) == hostSession &&
                intent.getLongExtra(HOST_GENERATION_KEY, hostGeneration) != hostGeneration
        }

        private fun getMiniProgramFromIntent(intent: Intent): MiniProgram? {
            // 首选字段化 extras(当前 launch 的写入方式);字符串/整数不受混淆影响。
            val appId = intent.getStringExtra(EXTRA_APP_ID)
            if (!appId.isNullOrBlank()) {
                return MiniProgram(
                    appId = appId,
                    name = intent.getStringExtra(EXTRA_NAME) ?: "",
                    root = intent.getBooleanExtra(EXTRA_ROOT, true),
                    path = intent.getStringExtra(EXTRA_PATH),
                    versionCode = intent.getIntExtra(EXTRA_VERSION_CODE, 0),
                    versionName = intent.getStringExtra(EXTRA_VERSION_NAME) ?: "",
                    updateManifestUrl = intent.getStringExtra(EXTRA_UPDATE_MANIFEST_URL) ?: "",
                    scene = intent.getIntExtra(EXTRA_SCENE, 1001),
                    openerAppId = intent.getStringExtra(EXTRA_OPENER_APP_ID),
                    referrerExtraData = intent.getStringExtra(EXTRA_REFERRER_EXTRA_DATA),
                    envVersion = intent.getStringExtra(EXTRA_ENV_VERSION) ?: "release",
                    iconPng = intent.getByteArrayExtra(EXTRA_ICON_PNG),
                )
            }
            // 兼容通道:宿主直接构造 Parcelable extra 的旧用法。Bundle 惰性解包下
            // 陈旧 parcel(上一代构建的混淆类名)可能在此抛 BadParcelableException
            // ——绝不能炸出 onCreate 带走整个宿主进程,捕获后走 null→finish。
            intent.setExtrasClassLoader(MiniProgram::class.java.classLoader)
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(MINI_PROGRAM_KEY, MiniProgram::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(MINI_PROGRAM_KEY) as? MiniProgram
                }
            } catch (error: Exception) {
                discardUnreadableIntent("stale parcel: ${error.message}", intent)
                null
            }
        }

        private fun discardUnreadableIntent(reason: String, intent: Intent) {
            LogUtils.e(
                "DiminaActivity",
                "Discarding mini program intent ($reason): data=${intent.data}, extras=${intent.extras?.keySet()}",
            )
        }

        /** Move the entire task to the front; CLEAR_TOP would discard the retained page stack. */
        internal fun resumeRetainedMiniProgram(context: Context, appId: String): Boolean {
            val activity = findLiveActivity(appId)
            if (!Dimina.getInstance().isMultiTaskEnabled()) {
                // A shared host task cannot be selected by moving the whole task forward.
                return context === activity
            }
            val manager = context.getSystemService(ActivityManager::class.java)
            val tasks = manager.appTasks
            val task = MiniProgramTaskLookup.find(
                tasks, activity?.taskId, appId,
                taskId = { it.taskInfo.taskId },
                rootAppId = { task ->
                    val intent = task.taskInfo.baseIntent
                    if (intent.component?.className == DiminaActivity::class.java.name &&
                        !isObsoleteHostLaunch(intent)) {
                        MiniProgramTaskIdentity.appId(intent, hostSession, hostGeneration)
                        // 只读字段化 extra:baseIntent 可能来自恢复的任务记录,
                        // extras 会被系统剥掉;这里静默读,别走 parcel 兼容分支。
                            ?: intent.getStringExtra(EXTRA_APP_ID)
                    } else null
                },
            ) ?: return false
            if (activity == null) {
                // 根 Activity 已死、只剩任务记录:恢复它只会让系统用被剥掉 extras
                // 的 baseIntent 重建 Activity,读不出 MiniProgram,表现为「打开即
                // 闪退」。把记录连根删掉,让调用方走全新 launch(带完整 extras)。
                try {
                    task.finishAndRemoveTask()
                } catch (error: Exception) {
                    LogUtils.e("DiminaActivity", "Failed to drop dead task for $appId: ${error.message}")
                }
                return false
            }
            task.moveToFront()
            return true
        }

        private fun findLiveActivity(appId: String): DiminaActivity? =
            activityRegistry.lastMatching(appId) { !it.isFinishing && !it.isDestroyed }

        internal fun openMiniProgram(context: Activity, program: MiniProgram) {
            // Page navigation belongs to the current task; only app launches may reuse a task.
            if (!program.root) {
                launch(context, program)
                return
            }
            val existing = findLiveActivity(program.appId)
            if (existing?.taskId != context.taskId) returnTasks[program.appId] = context.taskId
            if (existing != null && !existing.isFinishing && !existing.isDestroyed) {
                activityRegistry.snapshot(program.appId).forEach { activity ->
                    activity.miniProgram = activity.miniProgram.copy(
                        openerAppId = program.openerAppId,
                        referrerExtraData = program.referrerExtraData,
                        scene = program.scene,
                    )
                }
            }
            // The Android task may survive after all its Activity objects were reclaimed.
            // Queue the new entry even in that case, before restoring the task.
            MiniApp.getInstance().setPendingAppShowOptions(
                program.appId, MiniProgramEntryOptions.from(program, existing?.getLaunchReferrerInfo()),
            )
            if (resumeRetainedMiniProgram(context, program.appId)) {
                // A new entry while already visible has no onStart transition to deliver it.
                if (visibilityTracker.isForeground(program.appId)) existing?.dispatchMiniProgramShow()
                return
            }
            MiniApp.getInstance().consumePendingAppShowOptions(program.appId)
            // A second tap can arrive before the first Activity's onCreate registers its task.
            if (!pendingLaunches.add(program.appId)) return
            try {
                // Excluded tasks are trimmed by Android when another task becomes active.
                // Keep a normal task so switching mini programs does not destroy its pages.
                launch(context, program, if (Dimina.getInstance().isMultiTaskEnabled()) {
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                } else null)
            } catch (error: Exception) {
                pendingLaunches.remove(program.appId)
                throw error
            }
        }

        @androidx.annotation.MainThread
        internal fun hideMiniProgramFromHost(appId: String): Boolean {
            val activity = activityRegistry.lastRegistered(appId) ?: return false
            activity.hideMiniProgram()
            return true
        }

        fun launch(
            context: Context,
            miniProgram: MiniProgram,
            flag: Int? = null,
        ) {
            val intent = Intent(context, DiminaActivity::class.java).apply {
                if (miniProgram.root) {
                    MiniProgramTaskIdentity.attach(this, miniProgram.appId, hostSession, hostGeneration)
                }
                // 字段化 extras,不传 Parcelable 对象:Bundle 惰性解包 + 任务记录缓存
                // 会让 parcel 跨构建版本存活,混淆类名一换就是 BadParcelableException
                // 炸掉 onCreate(真机「打开即闪退」根因)。字符串/整数无此问题。
                putExtra(EXTRA_APP_ID, miniProgram.appId)
                putExtra(EXTRA_NAME, miniProgram.name)
                putExtra(EXTRA_ROOT, miniProgram.root)
                putExtra(EXTRA_PATH, miniProgram.path)
                putExtra(EXTRA_VERSION_CODE, miniProgram.versionCode)
                putExtra(EXTRA_VERSION_NAME, miniProgram.versionName)
                putExtra(EXTRA_UPDATE_MANIFEST_URL, miniProgram.updateManifestUrl)
                putExtra(EXTRA_SCENE, miniProgram.scene)
                putExtra(EXTRA_OPENER_APP_ID, miniProgram.openerAppId)
                putExtra(EXTRA_REFERRER_EXTRA_DATA, miniProgram.referrerExtraData)
                putExtra(EXTRA_ENV_VERSION, miniProgram.envVersion)
                putExtra(EXTRA_ICON_PNG, miniProgram.iconPng)
                putExtra(HOST_GENERATION_KEY, hostGeneration)
                putExtra(HOST_SESSION_KEY, hostSession)
                flag?.let {
                    addFlags(flag)
                }
            }
            context.startActivity(intent)
        }

        /**
         * 宿主主动关闭指定小程序。必须从最上层 Activity 进入正常 exit 链路，不能直接
         * closeAll/clear：后两者不会恢复跨小程序来源，也会绕开 Page/App 隐藏顺序。
         */
        @androidx.annotation.MainThread
        internal fun closeMiniProgramFromHost(appId: String): Boolean {
            val activity = activityRegistry.lastRegistered(appId) ?: return false
            activity.exitMiniProgram()
            return true
        }

        /**
         * autoSync 静默升级的宿主入口(autoSync 扩展):新包就绪且小程序正在运行时
         * 由 MiniApp 在主线程调用,直接走 [DiminaActivity.applyUpdate](激活+冷重启,
         * 与用户确认路径同一套,无弹窗)。返回 false = 该小程序没有在运行的 Activity,
         * 调用方应自行激活 pending(下次打开即新版本)。
         */
        @androidx.annotation.MainThread
        internal fun autoApplyPendingUpdateFromHost(appId: String): Boolean {
            val activity = activityRegistry.lastRegistered(appId) ?: return false
            activity.applyUpdate()
            return true
        }


        internal fun canEvictRetainedApp(appId: String): Boolean {
            val activity = activityRegistry.lastRegistered(appId) ?: return true
            return activity.retainedByHost && !visibilityTracker.isForeground(appId)
        }

        @androidx.annotation.MainThread
        internal fun destroyAllMiniProgramsFromHost() {
            hostGeneration++
            pendingLaunches.clear()
            returnTasks.clear()
            activityRegistry.closeEveryApp { activity ->
                activity.destroyedByHost = true
                activity.initializationJob?.cancel()
                activity.webViewReadyCallbacks.clear()
                activity.pageReadyCallback = null
                activity.tabPageStates.values.forEach { state ->
                    state.webViewReadyCallbacks.clear()
                    state.pageReadyCallback = null
                }
                activity.suspendForMiniProgramNavigation()
                activity.prepareForColdRestart()
                // Remove only tasks rooted in Dimina; never remove the host's shared task.
                if (activity.isTaskRoot) activity.finishAndRemoveTask() else activity.finish()
            }
        }

        internal fun closeForUninstall(appId: String) {
            activityRegistry.closeAll(appId) { activity ->
                activity.prepareForColdRestart()
                activity.finish()
            }
        }
    }
}
