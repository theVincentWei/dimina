package com.didi.dimina.bean

import kotlinx.serialization.Serializable

/**
 * Author: Doslin
 */
@Serializable
data class AppConfig(
    val app: App,
    val modules: Map<String, PageModule>
)

@Serializable
data class App(
    val entryPagePath: String? = null,
    val pages: List<String>,
    val runtimeType: String = "miniProgram",
    val window: WindowConfig? = null,
    val tabBar: TabBarConfig? = null,
    val subPackages: List<SubPackage>? = null,
    val networkTimeout: NetworkTimeout? = null,
    val debug: Boolean? = null,
    /**
     * 微信语义深色模式：app.json 声明 "darkmode": true 且编译器把 theme.json 变量表打进
     * app.theme 后，壳层配置（window/tabBar 及页面级 window 段）的 "@key" 引用才按当前
     * 系统主题解析。默认 false，旧包与未适配包完全走现状路径。
     * 编译器契约：darkmode 为 true 时必有 theme；theme.json 缺失/损坏时编译器会丢弃
     * darkmode，容器不处理「true 但无表」的半配置状态。
     */
    val darkmode: Boolean = false,
    val theme: Map<String, Map<String, String>>? = null
)

@Serializable
data class WindowConfig(
    val navigationBarTextStyle: String? = null,
    val navigationBarTitleText: String? = null,
    val navigationBarBackgroundColor: String? = null,
    val backgroundColor: String? = null,
    val navigationStyle: String? = null,
    val homeButton: Boolean? = null,
)

@Serializable
data class TabBarConfig(
    val color: String = "#999999",
    val selectedColor: String = "#1890ff",
    val borderStyle: String = "black",
    val backgroundColor: String = "#FFFFFF",
    val list: List<TabBarItem> = emptyList()
)

@Serializable
data class TabBarItem(
    val pagePath: String,
    val iconPath: String = "",
    val selectedIconPath: String = "",
    val text: String = ""
)

@Serializable
data class SubPackage(
    val root: String,
    val pages: List<String>
)

/**
 * `app.json`'s `networkTimeout` block. Every entry is independently optional and defaults to
 * 60000 ms, so a mini program may configure only the timeouts it cares about.
 *
 * The defaults are load-bearing rather than cosmetic: `app-config.json` is decoded as one unit
 * when the mini program opens, and a missing required field aborts that whole decode - a
 * partially-configured block would stop the mini program from starting instead of degrading a
 * single timeout. `ignoreUnknownKeys` does not help here; it forgives extra keys, not absent ones.
 */
@Serializable
data class NetworkTimeout(
    val request: Int = DEFAULT_NETWORK_TIMEOUT_MS,
    val connectSocket: Int = DEFAULT_NETWORK_TIMEOUT_MS,
    val uploadFile: Int = DEFAULT_NETWORK_TIMEOUT_MS,
    val downloadFile: Int = DEFAULT_NETWORK_TIMEOUT_MS
) {
    companion object {
        /** The documented default for every `networkTimeout` entry a mini program leaves unset. */
        const val DEFAULT_NETWORK_TIMEOUT_MS = 60000
    }
}

@Serializable
data class PageModule(
    val navigationBarTitleText: String? = null,
    val root: String? = null,
    val enablePullDownRefresh: Boolean? = null,
    val navigationBarBackgroundColor: String? = null,
    val navigationBarTextStyle: String? = null,
    val backgroundColor: String? = null,
    val navigationStyle: String? = null,
    val homeButton: Boolean? = null,
    val usingComponents: Map<String, String>? = null
)
