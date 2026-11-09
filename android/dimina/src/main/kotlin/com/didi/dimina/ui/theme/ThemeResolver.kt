package com.didi.dimina.ui.theme

import android.content.Context
import android.content.res.Configuration

/**
 * 微信语义的深色模式取值器。
 *
 * 小程序在 app.json 声明 "darkmode": true 后（编译器把 theme.json 的两套变量表打进
 * app-config.json 的 app.theme），壳层配置值允许用 "@key" 引用变量，容器按当前系统
 * 深浅色解析。回退链与微信一致：
 *
 *   "@key" -> theme[当前主题][key] -> theme[另一主题][key] -> 去掉 @ 的字面值
 *
 * 非 "@" 值原样返回，因此未声明 darkmode 的包所有配置值都走直通路径，行为与
 * 不开启深色模式时完全一致。调用方（DiminaActivity）负责 darkmode 门禁。
 */
object ThemeResolver {

    /** 主题变量表的两层键,超出集合的顶层键在 lookup 时自然被忽略。 */
    const val LIGHT = "light"
    const val DARK = "dark"

    /**
     * 当前主题。读 resources.configuration.uiMode 的 NIGHT_MASK：
     * UI_MODE_NIGHT_YES 记 dark，其余（NOT_YES / UNDEFINED）一律记 light，
     * 与微信「未声明 = 永远浅色」的兜底方向一致。
     */
    fun currentTheme(context: Context): String {
        val mask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (mask == Configuration.UI_MODE_NIGHT_YES) DARK else LIGHT
    }

    fun otherTheme(theme: String): String = if (theme == DARK) LIGHT else DARK

    /**
     * 解析一个壳层配置值。
     * @param current 当前主题（[ThemeResolver.currentTheme] 的结果）
     * @param theme app-config.json 的 theme 变量表，null/空表时 "@key" 直接回退字面值
     * @param value 原始配置值，可带 "@key" 引用
     */
    fun resolve(
        current: String,
        theme: Map<String, Map<String, String>>?,
        value: String
    ): String {
        if (!value.startsWith("@")) {
            return value
        }
        val key = value.substring(1)
        if (theme == null) {
            return key
        }
        theme[current]?.get(key)?.let { return it }
        theme[otherTheme(current)]?.get(key)?.let { return it }
        return key
    }
}
