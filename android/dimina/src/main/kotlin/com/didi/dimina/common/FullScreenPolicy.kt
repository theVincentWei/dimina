package com.didi.dimina.common

import com.didi.dimina.bean.WindowConfig

/**
 * 微信语义沉浸式全屏判定(2026-11-13)。
 *
 * 两条触发线,与微信行为一一对应:
 * - 小游戏(`runtimeType == "game"`)一律沉浸 —— 微信小游戏无状态栏,容器对齐;
 *   编译器对 game.json 产物合成 `runtimeType: "game"`(env.js storeAppConfig),
 *   容器侧无需新字段。
 * - 小程序显式声明 `app.json → window.fullscreen: true` 才沉浸。编译器对 window
 *   整键透传(config-compiler.js),此处是唯一判定点。
 *
 * 未命中的包(旧包、普通小程序)返回 false,系统状态栏行为与历史版本完全一致。
 */
object FullScreenPolicy {
    const val GAME_RUNTIME_TYPE = "game"

    fun shouldGoImmersive(runtimeType: String?, window: WindowConfig?): Boolean =
        runtimeType == GAME_RUNTIME_TYPE || window?.fullscreen == true
}
