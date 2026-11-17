//
//  DMPiOSContainerEvents.swift
//  dimina
//
//  iOS 容器 → 宿主事件契约（2026-11-17「iOS 容器对齐安卓」工程）。
//
//  宿主侧（如 dsh-go 的 DiminaPlugin）按同名字符串注册 NotificationCenter 监听，
//  userInfo key 必须与本文件常量一字不差：
//  - dimina.miniProgramFloated      「浮窗」瓦片成功收栈后发出
//      userInfo: ["appId": String, "appName": String]
//  - dimina.miniProgramAskAiInvoked 问AI悬浮球被点击时发出
//      userInfo: ["appId": String, "pageTitle": String?, "pagePath": String?]
//      （pageTitle/pagePath 缺省时该 key 不出现，读侧 `as? String` 得 nil 即可）
//

import Foundation

public extension Notification.Name {
    /// 小程序已成功收栈为后台浮窗（⋯菜单「浮窗」瓦片触发）。
    /// userInfo: ["appId": String, "appName": String]——宿主据此展示浮窗球。
    static let diminaMiniProgramFloated = Notification.Name("dimina.miniProgramFloated")

    /// 问AI悬浮球被点击（容器只上报，不认识「问AI」业务语义；收小程序由宿主决定）。
    /// userInfo: ["appId": String, "pageTitle": String?, "pagePath": String?]
    static let diminaMiniProgramAskAiInvoked = Notification.Name("dimina.miniProgramAskAiInvoked")
}

/// 事件 userInfo key 契约（宿主侧按同名 key 读取，勿改字面量）。
public enum DMPMiniProgramEventUserInfo {
    public static let appId = "appId"
    public static let appName = "appName"
    public static let pageTitle = "pageTitle"
    public static let pagePath = "pagePath"
}
