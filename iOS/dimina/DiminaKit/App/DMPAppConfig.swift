//
//  DMPAppConfig.swift
//  dimina
//
//  Created by Lehem on 2025/4/17.
//

import SwiftUI

public struct DMPAppConfig : Identifiable {
    var appName: String
    var appId: String

    /// Public for host apps (dsh-go iOS bridge): the Android MiniProgram contract
    /// exposes path/versionCode/versionName; keep parity so hosts can drive installs.
    public var path: String?
    public var versionCode: Int?
    public var versionName: String?
    public var updateManifestUrl: String?
    public var isDebugMode: Bool = false

    /// Keep service collection and pageFrame activation on the same policy.
    var isVConsoleEnabled: Bool {
        isDebugMode
    }

    var color: Color?

    /// Public for host apps (dsh-go iOS bridge): png 文件绝对路径,容器 ⋯菜单头部
    /// 按 it 加载真包图标;nil/文件缺失回落首字母瓦片(对齐安卓 iconPng 通道)。
    public var icon: String?

    // 符合Identifiable协议的id属性
    public var id: String { appId }

    public init(appName: String, appId: String) {
        self.appName = appName
        self.appId = appId
    }
}
