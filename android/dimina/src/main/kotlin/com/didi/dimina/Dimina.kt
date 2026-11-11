package com.didi.dimina

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import androidx.annotation.ColorInt
import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.didi.dimina.api.ext.ExtModuleHandler
import com.didi.dimina.bean.MiniProgram
import com.didi.dimina.common.LogUtils
import com.didi.dimina.common.PathUtils
import com.didi.dimina.common.StoreUtils
import com.didi.dimina.core.MiniApp
import com.didi.dimina.core.RemoteUpdateManager
import com.didi.dimina.ui.container.DiminaActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 容器 Header 通知入口的状态。
 *
 * 框架不解释「通知」语义：这里只是一个入口壳（是否显示 / 未读角标 / 点击回调），
 * 显示与否、角标数值、点击后打开什么页面都完全由宿主决定。属性是 Compose 可观察状态，
 * 容器 Compose 树读取后会在宿主推送时自动重组；宿主从未设置时保持「不显示、无角标」，
 * 对不接通知的其它嵌入方零影响。
 */
internal class NotifyEntryState {
    var visible by mutableStateOf(false)
        internal set

    var unread by mutableIntStateOf(0)
        internal set

    var onEntryClick by mutableStateOf<(() -> Unit)?>(null)
        internal set
}

/**
 * Author: Doslin
 */
class Dimina private constructor(context: Context) {
    // 伴生对象用于实现单例模式和提供初始化入口
    companion object {
        @Volatile
        private var instance: Dimina? = null

        /**
         * 初始化 SDK
         * @param context 应用上下文
         * @param config 配置参数
         * @return Dimina 实例
         */
        fun init(context: Context, config: DiminaConfig): Dimina {
            return instance ?: synchronized(this) {
                instance ?: Dimina(context.applicationContext).also { instance = it }.apply {
                    // 应用配置
                    applyConfig(config)
                }
            }
        }

        /**
         * 获取 Dimina 实例
         * @throws IllegalStateException 如果尚未初始化
         */
        fun getInstance(): Dimina {
            return instance ?: throw IllegalStateException("Dimina SDK not initialized. Please call init() first.")
        }
    }

    // 配置类
    class DiminaConfig private constructor(builder: Builder) {
        val showCapsule: Boolean = builder.showCapsule
        @ColorInt val capsuleBackgroundColor: Int = builder.capsuleBackgroundColor
        @ColorInt val capsuleIconColor: Int = builder.capsuleIconColor

        // 浅/深双色(可选):宿主按主题给两套胶囊配色,运行中按当前主题实时选一套。
        // 旧单色 API 没有这两组值时回落为那一套固定色。
        @ColorInt val capsuleLightBackgroundColor: Int? = builder.capsuleLightBackgroundColor
        @ColorInt val capsuleLightIconColor: Int? = builder.capsuleLightIconColor
        @ColorInt val capsuleDarkBackgroundColor: Int? = builder.capsuleDarkBackgroundColor
        @ColorInt val capsuleDarkIconColor: Int? = builder.capsuleDarkIconColor

        /** 宿主是否显式设置过胶囊颜色;false=容器按微信语义自适应(CapsuleStyle)。 */
        val capsuleColorsExplicit: Boolean = builder.capsuleColorsExplicit
        val enableMultiTask: Boolean = builder.enableMultiTask
        val showLaunchLoading: Boolean = builder.showLaunchLoading
        val debugMode: Boolean = builder.debugMode
        val apiNamespaces: List<String> = builder.apiNamespaces
        val virtualFilePrefix: String = builder.virtualFilePrefix

        class Builder {
            var enableMultiTask: Boolean = true
            fun setEnableMultiTask(enable: Boolean): Builder { enableMultiTask = enable; return this }

            var showLaunchLoading: Boolean = true
            fun setShowLaunchLoading(show: Boolean): Builder { showLaunchLoading = show; return this }

            var showCapsule: Boolean = true
            fun setShowCapsule(show: Boolean): Builder { showCapsule = show; return this }

            // 宿主显式配置过任一胶囊颜色 → 容器停用微信默认语义(半透明 scrim + 图标随
            // navigationBarTextStyle),改为完全使用宿主给定的颜色(可自带 alpha)。
            internal var capsuleColorsExplicit: Boolean = false
                private set

            @ColorInt
            internal var capsuleBackgroundColor: Int = Color.WHITE
                private set

            /** Sets the capsule fill to an ARGB color. Configure before [Dimina.init].
             *  显式配置后胶囊底色固定用该值(微信默认语义停用)。 */
            fun setCapsuleBackgroundColor(@ColorInt color: Int): Builder {
                capsuleBackgroundColor = color
                capsuleColorsExplicit = true
                return this
            }

            @ColorInt
            internal var capsuleIconColor: Int = 0xFF1F1F1F.toInt()
                private set

            /** Sets both capsule icons to an ARGB color. Configure before [Dimina.init].
             *  显式配置后图标色固定用该值(不再随 navigationBarTextStyle 自适应)。 */
            fun setCapsuleIconColor(@ColorInt color: Int): Builder {
                capsuleIconColor = color
                capsuleColorsExplicit = true
                return this
            }

            @ColorInt internal var capsuleLightBackgroundColor: Int? = null
                private set
            @ColorInt internal var capsuleLightIconColor: Int? = null
                private set
            @ColorInt internal var capsuleDarkBackgroundColor: Int? = null
                private set
            @ColorInt internal var capsuleDarkIconColor: Int? = null
                private set

            /**
             * 按主题给两套胶囊配色(浅色/深色)。Configure before [Dimina.init].
             * 容器在运行中按当前系统深浅色选对应一套:切主题胶囊当场跟随,不用重启进程;
             * 旧单色 API 的 config 初始化后不可变,胶囊会冻结在 init 那一刻的配色
             * (运行中切主题不动、重启后忽然变——宿主实测的「时对时不对」)。
             */
            fun setCapsuleThemeColors(
                @ColorInt lightBackgroundColor: Int,
                @ColorInt lightIconColor: Int,
                @ColorInt darkBackgroundColor: Int,
                @ColorInt darkIconColor: Int,
            ): Builder {
                capsuleLightBackgroundColor = lightBackgroundColor
                capsuleLightIconColor = lightIconColor
                capsuleDarkBackgroundColor = darkBackgroundColor
                capsuleDarkIconColor = darkIconColor
                capsuleColorsExplicit = true
                return this
            }

            var debugMode: Boolean = false
            internal var apiNamespaces: MutableList<String> = mutableListOf()
            internal var virtualFilePrefix: String = PathUtils.DEFAULT_VIRTUAL_DOMAIN_URL

            fun setDebugMode(debugMode: Boolean): Builder {
                this.debugMode = debugMode
                return this
            }

            fun addApiNamespace(name: String): Builder {
                apiNamespaces.add(name)
                return this
            }

            /**
             * Configure the virtual file URI scheme used by both native path resolution and
             * the service JavaScript runtime. Call this before [Dimina.init].
             */
            fun setVirtualFilePrefix(prefix: String): Builder {
                virtualFilePrefix = PathUtils.normalizeVirtualFilePrefix(prefix)
                return this
            }

            fun build(): DiminaConfig {
                return DiminaConfig(this)
            }
        }
    }

    /**
     * 检查宿主是否显式启用调试模式，与 SDK AAR 的构建类型无关。
     * @return 是否为调试模式
     */
    fun isDebugMode(): Boolean {
        return config.debugMode
    }

    @MainThread
    fun configureRetention(policy: com.didi.dimina.core.RetentionPolicy) {
        MiniApp.getInstance().configureRetention(policy)
    }

    /**
     * 注册宿主侧升级事件监听(见 [MiniProgramUpdateListener])。
     *
     * 升级由框架全权负责:容器后台检测+下载,不询问用户(契约 §6.4「强制自动 + 仅告知」),
     * pending 包在下次打开时静默激活;宿主用 onUpdateApplied 的版本号发告知。
     * 小程序侧不参与升级决策。传 null 注销。回调都在主线程。
     */
    fun setOnMiniProgramUpdateListener(listener: MiniProgramUpdateListener?) {
        MiniApp.getInstance().setOnUpdateListener(listener)
    }

    /** Hosts may forward their own pressure source here as well. */
    fun notifyMemoryPressure() { MiniApp.getInstance().notifyMemoryPressure() }

    fun getApiNamespaces(): List<String> = config.apiNamespaces

    private val appContext: Context = context
    private val operationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var config: DiminaConfig

    init {
        // 基础初始化逻辑
        setupCoreComponents()
    }

    // 初始化核心组件
    private fun setupCoreComponents() {
        StoreUtils.initialize(context = appContext)
        appContext.registerComponentCallbacks(object : android.content.ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
            override fun onLowMemory() { notifyMemoryPressure() }
            override fun onTrimMemory(level: Int) {
                if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                    level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                    level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
                    notifyMemoryPressure()
                }
            }
        })
    }

    // 应用配置
    private fun applyConfig(config: DiminaConfig) {
        // 存储配置到类属性
        this.config = config
        PathUtils.configureVirtualFilePrefix(config.virtualFilePrefix)

		// Debugging is explicitly controlled by the host, including with a release AAR.
		LogUtils.initialize(isDebugMode())
    }

    /**
     * 启动小程序
     * @param context 上下文
     * @param miniProgram 要启动的小程序对象
     */
    fun startMiniProgram(context: Activity, miniProgram: MiniProgram) {
        val miniApp = MiniApp.getInstance()
        miniApp.openApp(context, miniProgram)
    }

    /**
     * autoSync 静默同步(autoSync 扩展):后台把 [miniProgram.updateManifestUrl]
     * 描述的包装进本机,全程无界面、无确认弹窗。没装 → 首装;已装旧版 → 下载
     * 并立即激活;已最新 → noupdate。installed/applied 里程碑照常经
     * [setOnMiniProgramUpdateListener] 广播;[callback] 在主线程回调,参数为
     * (outcome: installed|updated|noupdate|failed, versionCode, versionName)。
     */
    fun syncMiniProgram(
        context: Context,
        miniProgram: MiniProgram,
        callback: (outcome: String, versionCode: Int, versionName: String) -> Unit,
    ) {
        MiniApp.getInstance().syncInBackground(context, miniProgram) { result ->
            callback(result.outcome, result.versionCode, result.versionName)
        }
    }

    /** Hide an app while retaining its page stack and runtime for the next startMiniProgram. */
    @MainThread
    fun hideMiniProgram(appId: String): Boolean {
        val normalizedAppId = appId.trim()
        if (normalizedAppId.isEmpty()) return false
        return DiminaActivity.hideMiniProgramFromHost(normalizedAppId)
    }

    /**
     * Requests a normal mini-program exit from the host application.
     *
     * The request is routed through the top Activity's exit path so lifecycle ordering and a
     * possible opener mini program are preserved. The method must be called on the main thread.
     *
     * @return `true` when a running Activity accepted the request, or `false` when [appId] is
     * blank or no Activity is currently registered for it.
     */
    @MainThread
    fun closeMiniProgram(appId: String): Boolean {
        val normalizedAppId = appId.trim()
        if (normalizedAppId.isEmpty()) return false
        return DiminaActivity.closeMiniProgramFromHost(normalizedAppId)
    }

    /** Destroy every foreground/background instance, preserving installed packages and user data.
     * Call on the main thread. Stop issuing launches before calling this API.
     */
    @MainThread
    fun destroyAllMiniPrograms() {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            "destroyAllMiniPrograms must run on the main thread"
        }
        DiminaActivity.destroyAllMiniProgramsFromHost()
        MiniApp.getInstance().clearAll()
    }

    fun isMultiTaskEnabled(): Boolean = config.enableMultiTask

    fun shouldShowLaunchLoading(): Boolean = config.showLaunchLoading

    fun shouldShowCapsule(): Boolean = config.showCapsule

    // 系统深浅色标记:仅驱动菜单等面板配色(isCapsuleThemeDark)。胶囊本体的配色已改
    // 微信语义(半透明 scrim 透出导航栏 + 图标随 navigationBarTextStyle,见 CapsuleStyle),
    // 随容器主题重组自动适应,不再吃运行时实底覆写。
    private var capsuleDarkOverride: Boolean? = null

    /** 运行时标记胶囊主题深浅(菜单等面板配色跟它走)。胶囊本体配色不再由该方法决定:
     *  默认=导航栏解析色半透明 scrim + 图标随 navigationBarTextStyle 自适应(微信语义,
     *  对所有小程序一致,与是否声明 darkmode 无关);仅当宿主通过 Builder 显式配置过
     *  setCapsuleThemeColors(浅/深双色,按当前主题实时选)或旧的单色
     *  setCapsuleBackgroundColor/setCapsuleIconColor 时才使用宿主颜色。 */
    fun updateCapsuleTheme(dark: Boolean) {
        capsuleDarkOverride = dark
    }

    /** 当前胶囊主题是否深色(菜单等面板配色跟它走)。 */
    fun isCapsuleThemeDark(): Boolean = capsuleDarkOverride ?: false

    /** 宿主注入的深浅覆写原值;null=宿主未注入,菜单等面板配色退回系统深浅色。 */
    fun capsuleDarkOverride(): Boolean? = capsuleDarkOverride

    @ColorInt
    internal fun getCapsuleBackgroundColor(): Int = config.capsuleBackgroundColor

    @ColorInt
    internal fun getCapsuleIconColor(): Int = config.capsuleIconColor

    /**
     * 宿主显式配置的胶囊配色(background to icon),按当前主题选一套;
     * null = 宿主未显式配置,走微信语义(CapsuleStyle)。
     * 新 [DiminaConfig.Builder.setCapsuleThemeColors] 提供浅/深两套,主题切换时容器
     * 重读;旧单色 API 没有双色,浅/深都回落到那一个值(行为与之前一致)。
     */
    internal fun capsuleColors(dark: Boolean): Pair<Int, Int>? {
        if (!config.capsuleColorsExplicit) return null
        return if (dark) {
            (config.capsuleDarkBackgroundColor ?: config.capsuleBackgroundColor) to
                (config.capsuleDarkIconColor ?: config.capsuleIconColor)
        } else {
            (config.capsuleLightBackgroundColor ?: config.capsuleBackgroundColor) to
                (config.capsuleLightIconColor ?: config.capsuleIconColor)
        }
    }

    /** 宿主是否显式配置过胶囊颜色;false=走微信默认语义(CapsuleStyle)。 */
    internal fun hasExplicitCapsuleColors(): Boolean = config.capsuleColorsExplicit

    // ---- 容器 Header 通知入口（宿主设置，框架只做入口壳） ----

    private val notifyEntryStateImpl = NotifyEntryState()

    /** 容器 Compose 树读取通知入口状态；宿主通过 [setNotifyEntry] 等三个方法推送。 */
    internal fun notifyEntryState(): NotifyEntryState = notifyEntryStateImpl

    /**
     * 设置 Header 通知入口是否显示。
     *
     * 宿主从未调用时入口不显示（对其它嵌入方零影响）。容器不认识「通知」语义，
     * 只按此开关渲染一个铃铛入口。
     */
    fun setNotifyEntry(visible: Boolean) {
        runOnMainThread { notifyEntryStateImpl.visible = visible }
    }

    /**
     * 设置通知未读数：`> 0` 显示红点角标与数字，`0` 隐藏数字（入口本身是否显示见 [setNotifyEntry]）。
     * 未读数由宿主主动推送（轮询/已读变化时），容器不拉取。
     */
    fun setNotifyUnread(count: Int) {
        runOnMainThread { notifyEntryStateImpl.unread = count.coerceAtLeast(0) }
    }

    /**
     * 设置通知入口的点击回调；打开通知中心是宿主的事情，框架只负责把点击透传出来。
     * 传 `null` 可清除回调，此时点击入口不做任何事。
     */
    fun setOnNotifyEntryClick(callback: (() -> Unit)?) {
        runOnMainThread { notifyEntryStateImpl.onEntryClick = callback }
    }

    private fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else Handler(Looper.getMainLooper()).post(block)
    }

    /** Queries installed disk contents, not whether a runtime is currently alive. */
    fun isExistsApp(appId: String): Boolean = getAppVersionInfo(appId) != null

    fun getAppVersionInfo(appId: String): org.json.JSONObject? =
        RemoteUpdateManager.getAppVersionInfo(appContext, appId.trim())

    /** Installs a Dimina ZIP containing config.json and main/. Keeps the source ZIP and user data. */
    fun installMiniProgram(appId: String, packagePath: String,
        completion: (Result<org.json.JSONObject>) -> Unit) {
        val normalizedAppId = appId.trim()
        try {
            RemoteUpdateManager.beginUninstall(normalizedAppId)
        } catch (error: Throwable) {
            completion(Result.failure(error))
            return
        }
        operationScope.launch {
            val result = runCatching {
                withContext(Dispatchers.Main.immediate) {
                    DiminaActivity.closeForUninstall(normalizedAppId)
                    MiniApp.getInstance().clear(normalizedAppId)
                }
                RemoteUpdateManager.installLocalPackage(appContext, normalizedAppId, packagePath)
            }
            RemoteUpdateManager.endUninstall(normalizedAppId)
            withContext(Dispatchers.Main.immediate) { completion(result) }
        }
    }

    /**
     * Stops and uninstalls one mini program without clearing persistent user data by default.
     * Bundled packages are restored from the host application the next time they are launched.
     */
    fun uninstallMiniProgram(
        appId: String,
        clearUserData: Boolean = false,
        completion: (Result<Unit>) -> Unit = {},
    ) {
        val normalizedAppId = appId.trim()
        try {
            RemoteUpdateManager.beginUninstall(normalizedAppId)
        } catch (error: Throwable) {
            completion(Result.failure(error))
            return
        }

        operationScope.launch {
            val result = runCatching {
                withContext(Dispatchers.Main.immediate) {
                    DiminaActivity.closeForUninstall(normalizedAppId)
                    MiniApp.getInstance().clear(normalizedAppId)
                }
                RemoteUpdateManager.uninstallPackage(
                    context = appContext,
                    appId = normalizedAppId,
                    clearUserData = clearUserData,
                )
            }
            RemoteUpdateManager.endUninstall(normalizedAppId)
            withContext(Dispatchers.Main.immediate) {
                completion(result)
            }
        }
    }

    /**
     * 注册第三方扩展 bridge 模块。
     *
     * 小程序通过 `wx.extBridge` / `wx.extOnBridge` / `wx.extOffBridge` 与 native 模块通信，
     * 宿主通过此方法向框架注册对应的处理器。建议在 [init] 之后、[startMiniProgram] 之前调用。
     *
     * 示例：
     * ```kotlin
     * Dimina.getInstance().registerExtModule("MyModule") { event, data, callback ->
     *     when (event) {
     *         "doSomething" -> {
     *             // 一次性调用
     *             callback.onSuccess(JSONObject().apply { put("result", 42) })
     *             null
     *         }
     *         "onDataChange" -> {
     *             // 持续订阅，返回取消函数
     *             val listener = registerListener { res -> callback.onSuccess(res) }
     *             Runnable { unregisterListener(listener) }
     *         }
     *         else -> { callback.onFail(JSONObject().apply { put("errMsg", "unknown event") }); null }
     *     }
     * }
     * ```
     *
     * @param moduleName 模块名，与小程序侧 `module` 参数一致
     * @param handler    处理器，参见 [ExtModuleHandler]
     */
    fun registerExtModule(moduleName: String, handler: ExtModuleHandler) {
        MiniApp.getInstance().registerExtModule(moduleName, handler)
    }
}
