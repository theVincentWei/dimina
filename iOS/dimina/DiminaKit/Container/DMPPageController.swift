//
//  DMPPageController.swift
//  dimina
//
//  Created by Lehem on 2025/5/15.
//

import Foundation
import SwiftUI
import UIKit
import WebKit

/// DMPPageController is a specialized view controller for displaying mini-program pages
/// It directly integrates the functionality of both DMPPage and DMPViewController
public class DMPPageController: UIViewController {

    // Weak reference to the navigator
    private weak var navigator: DMPNavigator?

    // Page properties
    private let pagePath: String
    private let query: [String: Any]?
    private let appConfig: DMPAppConfig
    private weak var app: DMPApp?
    private let isRoot: Bool
    private let showsLaunchLoading: Bool

    // 这个页面为什么被拆掉。默认按路由处理：系统主动回收、宿主自己 pop 都属于路由，
    // 页面照常卸载。退出小程序由关闭方在拆栈之前标注，见 DMPNavigator.markPageTeardownReason。
    private var pageStateTeardownReason: DMPPageStateTeardown = .routing

    // [game-touch] 游戏触摸快路（微维测/AB 期，整块可删）。仅 game 运行时且开关非 '0'
    // 才非空（见 GameTouchNativeRelay.swift）；页面拆毁时 dispose。
    private var gameTouchRelay: DMPGameTouchNativeRelay?

    // WebView related
    private var webview: DMPWebview
    private let loadingStateObserverToken = UUID()
    private var hostingController: UIHostingController<DMPWebViewContainer>?
    private var customNavigationBar: UIView?
    private var customNavigationContentView: UIView?
    private var customNavigationTitleLabel: UILabel?
    private var customNavigationBackButton: UIButton?
    private var customNavigationHomeButton: UIButton?
    // 微信真机 home 图标带灰色圆形底，比返回箭头更粗更显眼；颜色随导航栏深浅切换，见 updateCustomHomeButton
    private var customNavigationHomeButtonBackground: UIView?
    // home 键的两套水平位置：独占左槽（栈底自动规则）/ 紧随返回箭头之后
    // （homeButton: true 的内页，微信实测两者并存），按 showBack 切换激活
    private var homeButtonLeadingToEdge: NSLayoutConstraint?
    private var homeButtonLeadingAfterBack: NSLayoutConstraint?
    private var miniProgramMenuContainerView: UIView?
    private var isClosingMiniProgram = false
    // 「问AI悬浮球」本体与⋯菜单「问AI浮窗」瓦片缩微图标（2026-11-17 对齐安卓）
    private var askAiFloatButton: AskAiFloatButton?
    private var askAiMenuTileMiniature: AskAiFloatMiniatureView?
    private var webViewTopToNavigationConstraint: NSLayoutConstraint?
    private var webViewTopToViewConstraint: NSLayoutConstraint?
    private var loadingView: UIView?
    private weak var loadingParentController: UIViewController?

    // State
    private var isWebViewDestroyed = false
    private var hasDispatchedPageUnload = false
    private var hasStartedLoading = false
    private var hasShownLaunchLoading = false

    /// Initialization method
    /// - Parameters:
    ///   - pagePath: Page path
    ///   - query: Query parameters
    ///   - appConfig: App configuration
    ///   - app: App instance
    ///   - navigator: Navigator
    ///   - isRoot: Whether this is a root view controller
    ///   - showsLaunchLoading: Whether to show the app launch loading overlay
    public init(
        pagePath: String, query: [String: Any]?, appConfig: DMPAppConfig, app: DMPApp?,
        navigator: DMPNavigator?, isRoot: Bool = false, showsLaunchLoading: Bool = false
    ) {
        self.pagePath = pagePath
        self.query = query
        self.appConfig = appConfig
        self.app = app
        self.navigator = navigator
        self.isRoot = isRoot
        self.showsLaunchLoading = showsLaunchLoading

        // Create WebView
        self.webview = (app?.render!.createWebView(appName: appConfig.appName))!

        super.init(nibName: nil, bundle: nil)

        // Configure WebView - Configure immediately to ensure page path is set correctly
        configWebView()
        observeLoadingState()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    // Configure WebView
    private func configWebView() {        
        // Set page path and query parameters
        self.webview.setPagePath(pagePath: pagePath)
        if let query = query {
            self.webview.setQuery(query: query)
        }
        
        DMPLogger.debug("🔧 DMPPageController: WebView (ID: \(webview.getWebViewId())) configuration completed, current page path: \(webview.getPagePath())")
        
        app?.render?.setupJSBridge(webViewId: webview.getWebViewId())

        // [game-touch] BEGIN 原生触摸快路接线（微维测/AB 期，整块可删）：必须在
        // setupJSBridge 之后、loadPageFrame 之前挂——快路的 document-start 用户脚本
        // 要先于渲染层 runtime 求值。非 game 运行时/开关为 '0' 返回 nil，零影响。
        gameTouchRelay = DMPGameTouchNativeRelay.attachIfEnabled(app: app, webview: webview)
        // [game-touch] END

    }

    // View loaded
    public override func viewDidLoad() {
        super.viewDidLoad()

        view.backgroundColor = .white
        setupCustomNavigationBar()

        // 深浅色切换时刷新⋯菜单弹层配色（悬浮球徽章为定稿配色、不随主题）
        registerForTraitChanges([UITraitUserInterfaceStyle.self]) {
            (self: DMPPageController, previousCollection: UITraitCollection) in
            self.refreshMenuForTraitChange()
        }

        // Create SwiftUI view container
        let webViewContainer = DMPWebViewContainer(webview: webview, isRoot: isRoot)
        hostingController = UIHostingController(rootView: webViewContainer)

        // Add child view controller
        if let hostingController = hostingController {
            addChild(hostingController)
            view.addSubview(hostingController.view)
            hostingController.view.translatesAutoresizingMaskIntoConstraints = false
            webViewTopToNavigationConstraint = hostingController.view.topAnchor.constraint(
                equalTo: customNavigationBar?.bottomAnchor ?? view.topAnchor
            )
            webViewTopToViewConstraint = hostingController.view.topAnchor.constraint(equalTo: view.topAnchor)
            NSLayoutConstraint.activate([
                webViewTopToNavigationConstraint!,
                hostingController.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
                hostingController.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
                hostingController.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            ])
            hostingController.didMove(toParent: self)
        }

        // Set navigation bar style
        setupNavigationBar()
    }

    // View will appear
    public override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        navigationController?.setNavigationBarHidden(true, animated: false)
        navigator?.setCapsuleVisible(true)
        if loadingView == nil {
            navigator?.bringCapsuleToFront()
        }
        // 问AI悬浮球显隐跟随持久化开关（返回本页时与新状态对齐）
        syncAskAiFloatButton()
        setupNavigationBar()
        showPageLoadingIfNeeded()
    }

    public override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        if let customNavigationBar = customNavigationBar, !customNavigationBar.isHidden {
            view.bringSubviewToFront(customNavigationBar)
        }
        if let miniProgramMenuContainerView = miniProgramMenuContainerView {
            miniProgramMenuContainerView.superview?.bringSubviewToFront(miniProgramMenuContainerView)
        }
        loadingView?.superview?.bringSubviewToFront(loadingView!)
    }

    // View did appear
    public override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        startLoadingIfNeeded()
    }

    private func startLoadingIfNeeded() {
        guard !hasStartedLoading, !isWebViewDestroyed else {
            return
        }

        hasStartedLoading = true
        showPageLoadingIfNeeded()
        webview.poolState = .loading
        webview.loadPageFrame(enableVConsole: appConfig.isVConsoleEnabled)
    }

    public func preparePageLoading(in parentController: UIViewController) {
        guard showsLaunchLoading else {
            return
        }

        installPageLoading(in: parentController, isVisible: false)
    }

    private func observeLoadingState() {
        webview.setLoadingStateObserver(ownerToken: loadingStateObserverToken) { [weak self] isLoading in
            let updateLoading = {
                if isLoading {
                    self?.showPageLoadingIfNeeded()
                } else {
                    self?.hidePageLoading()
                }
            }

            if Thread.isMainThread {
                updateLoading()
            } else {
                DispatchQueue.main.async {
                    updateLoading()
                }
            }
        }
    }

    private func showPageLoadingIfNeeded() {
        guard showsLaunchLoading, !hasShownLaunchLoading else {
            return
        }

        hasShownLaunchLoading = true
        showPageLoading()
    }

    private func showPageLoading() {
        let parentController = loadingParentController ?? navigationController ?? self
        installPageLoading(in: parentController, isVisible: true)
    }

    private func installPageLoading(in parentController: UIViewController, isVisible: Bool) {
        let parentView = parentController.view!

        if let loadingView = self.loadingView {
            loadingView.superview?.bringSubviewToFront(loadingView)
            UIView.performWithoutAnimation {
                loadingView.alpha = isVisible ? 1 : 0
                loadingView.isUserInteractionEnabled = isVisible
                parentView.layoutIfNeeded()
            }
            return
        }

        let loadingView = makePageLoadingView()
        loadingView.translatesAutoresizingMaskIntoConstraints = false
        loadingView.alpha = isVisible ? 1 : 0
        loadingView.isUserInteractionEnabled = isVisible

        parentView.addSubview(loadingView)
        NSLayoutConstraint.activate([
            loadingView.topAnchor.constraint(equalTo: parentView.topAnchor),
            loadingView.bottomAnchor.constraint(equalTo: parentView.bottomAnchor),
            loadingView.leadingAnchor.constraint(equalTo: parentView.leadingAnchor),
            loadingView.trailingAnchor.constraint(equalTo: parentView.trailingAnchor),
        ])
        parentView.bringSubviewToFront(loadingView)
        UIView.performWithoutAnimation {
            parentView.layoutIfNeeded()
        }

        self.loadingView = loadingView
        self.loadingParentController = parentController
    }

    private func setupCustomNavigationBar() {
        let navigationBar = UIView()
        navigationBar.translatesAutoresizingMaskIntoConstraints = false

        let contentView = UIView()
        contentView.translatesAutoresizingMaskIntoConstraints = false

        let backButton = UIButton(type: .custom)
        backButton.translatesAutoresizingMaskIntoConstraints = false
        backButton.addTarget(self, action: #selector(customBackButtonTapped), for: .touchUpInside)

        let homeButton = UIButton(type: .custom)
        homeButton.translatesAutoresizingMaskIntoConstraints = false
        homeButton.accessibilityLabel = "Home"
        homeButton.addTarget(self, action: #selector(homeButtonTapped), for: .touchUpInside)

        let homeButtonBackground = UIView()
        homeButtonBackground.translatesAutoresizingMaskIntoConstraints = false
        homeButtonBackground.isUserInteractionEnabled = false
        homeButtonBackground.layer.cornerRadius = 16

        let titleLabel = UILabel()
        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        titleLabel.textAlignment = .center
        titleLabel.font = .systemFont(ofSize: 17, weight: .semibold)
        titleLabel.lineBreakMode = .byTruncatingTail

        view.addSubview(navigationBar)
        navigationBar.addSubview(contentView)
        contentView.addSubview(backButton)
        contentView.addSubview(homeButtonBackground)
        contentView.addSubview(homeButton)
        contentView.addSubview(titleLabel)

        NSLayoutConstraint.activate([
            navigationBar.topAnchor.constraint(equalTo: view.topAnchor),
            navigationBar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            navigationBar.trailingAnchor.constraint(equalTo: view.trailingAnchor),

            contentView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            contentView.leadingAnchor.constraint(equalTo: navigationBar.leadingAnchor),
            contentView.trailingAnchor.constraint(equalTo: navigationBar.trailingAnchor),
            contentView.bottomAnchor.constraint(equalTo: navigationBar.bottomAnchor),
            contentView.heightAnchor.constraint(equalToConstant: DMPMenuButtonLayout.navigationBarContentHeight),

            backButton.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 8),
            backButton.centerYAnchor.constraint(equalTo: contentView.centerYAnchor),
            backButton.widthAnchor.constraint(equalToConstant: 44),
            backButton.heightAnchor.constraint(equalToConstant: 44),

            homeButton.centerYAnchor.constraint(equalTo: contentView.centerYAnchor),
            homeButton.widthAnchor.constraint(equalToConstant: 44),
            homeButton.heightAnchor.constraint(equalToConstant: 44),

            homeButtonBackground.centerXAnchor.constraint(equalTo: homeButton.centerXAnchor),
            homeButtonBackground.centerYAnchor.constraint(equalTo: homeButton.centerYAnchor),
            homeButtonBackground.widthAnchor.constraint(equalToConstant: 32),
            homeButtonBackground.heightAnchor.constraint(equalToConstant: 32),

            titleLabel.centerXAnchor.constraint(equalTo: contentView.centerXAnchor),
            titleLabel.centerYAnchor.constraint(equalTo: contentView.centerYAnchor),
            titleLabel.leadingAnchor.constraint(greaterThanOrEqualTo: backButton.trailingAnchor, constant: 8),
            titleLabel.trailingAnchor.constraint(
                lessThanOrEqualTo: contentView.trailingAnchor,
                constant: -DMPMenuButtonLayout.titleTrailingInset
            )
        ])

        let homeLeadingToEdge = homeButton.leadingAnchor.constraint(
            equalTo: contentView.leadingAnchor, constant: 8)
        // 44pt 触摸热区本身比图标大（图标 24pt 居中，热区两侧各留 10pt），
        // 热区自带的留白已经提供了视觉间距，这里不再叠加额外 margin，
        // 否则视觉间距会远超微信原生 `.navigator-hd a+a{margin-left:10px}` 的 10pt
        let homeLeadingAfterBack = homeButton.leadingAnchor.constraint(
            equalTo: backButton.trailingAnchor)
        homeLeadingToEdge.isActive = true
        homeButtonLeadingToEdge = homeLeadingToEdge
        homeButtonLeadingAfterBack = homeLeadingAfterBack

        customNavigationBar = navigationBar
        customNavigationContentView = contentView
        customNavigationBackButton = backButton
        customNavigationHomeButton = homeButton
        customNavigationHomeButtonBackground = homeButtonBackground
        customNavigationTitleLabel = titleLabel
    }

    private func makePageLoadingView() -> UIView {
        let container = UIView()
        container.accessibilityIdentifier = "dimina.launchLoading"
        container.backgroundColor = .white

        let stackView = UIStackView()
        stackView.axis = .vertical
        stackView.alignment = .center
        stackView.spacing = 8
        stackView.translatesAutoresizingMaskIntoConstraints = false

        let iconView = UIView()
        iconView.translatesAutoresizingMaskIntoConstraints = false

        let ringView = UIView()
        ringView.layer.borderColor = UIColor.gray.withAlphaComponent(0.3).cgColor
        ringView.layer.borderWidth = 1
        ringView.layer.cornerRadius = 30
        ringView.translatesAutoresizingMaskIntoConstraints = false

        let appIconView = UIView()
        appIconView.backgroundColor = loadingIconColor(for: webview.appName)
        appIconView.layer.cornerRadius = 20
        appIconView.translatesAutoresizingMaskIntoConstraints = false

        let rotorView = UIView()
        rotorView.translatesAutoresizingMaskIntoConstraints = false

        let dotView = UIView()
        dotView.backgroundColor = .systemGreen
        dotView.layer.cornerRadius = 3
        dotView.translatesAutoresizingMaskIntoConstraints = false

        let initialLabel = UILabel()
        initialLabel.text = String(webview.appName.prefix(1))
        initialLabel.textColor = .white
        initialLabel.font = .systemFont(ofSize: 16, weight: .bold)
        initialLabel.textAlignment = .center
        initialLabel.translatesAutoresizingMaskIntoConstraints = false

        let appNameLabel = UILabel()
        appNameLabel.text = webview.appName
        appNameLabel.textColor = .label
        appNameLabel.font = .systemFont(ofSize: 14, weight: .medium)
        appNameLabel.textAlignment = .center

        container.addSubview(stackView)
        stackView.addArrangedSubview(iconView)
        stackView.addArrangedSubview(appNameLabel)

        iconView.addSubview(ringView)
        iconView.addSubview(appIconView)
        iconView.addSubview(rotorView)
        rotorView.addSubview(dotView)
        appIconView.addSubview(initialLabel)

        NSLayoutConstraint.activate([
            stackView.centerXAnchor.constraint(equalTo: container.centerXAnchor),
            stackView.centerYAnchor.constraint(equalTo: container.centerYAnchor),

            iconView.widthAnchor.constraint(equalToConstant: 60),
            iconView.heightAnchor.constraint(equalToConstant: 60),

            ringView.centerXAnchor.constraint(equalTo: iconView.centerXAnchor),
            ringView.centerYAnchor.constraint(equalTo: iconView.centerYAnchor),
            ringView.widthAnchor.constraint(equalToConstant: 60),
            ringView.heightAnchor.constraint(equalToConstant: 60),

            appIconView.centerXAnchor.constraint(equalTo: iconView.centerXAnchor),
            appIconView.centerYAnchor.constraint(equalTo: iconView.centerYAnchor),
            appIconView.widthAnchor.constraint(equalToConstant: 40),
            appIconView.heightAnchor.constraint(equalToConstant: 40),

            rotorView.centerXAnchor.constraint(equalTo: iconView.centerXAnchor),
            rotorView.centerYAnchor.constraint(equalTo: iconView.centerYAnchor),
            rotorView.widthAnchor.constraint(equalToConstant: 60),
            rotorView.heightAnchor.constraint(equalToConstant: 60),

            dotView.centerXAnchor.constraint(equalTo: rotorView.centerXAnchor),
            dotView.centerYAnchor.constraint(equalTo: rotorView.topAnchor),
            dotView.widthAnchor.constraint(equalToConstant: 6),
            dotView.heightAnchor.constraint(equalToConstant: 6),

            initialLabel.centerXAnchor.constraint(equalTo: appIconView.centerXAnchor),
            initialLabel.centerYAnchor.constraint(equalTo: appIconView.centerYAnchor),
        ])

        let rotation = CABasicAnimation(keyPath: "transform.rotation.z")
        rotation.fromValue = 0
        rotation.toValue = Double.pi * 2
        rotation.duration = 1.2
        rotation.repeatCount = .infinity
        rotation.timingFunction = CAMediaTimingFunction(name: .linear)
        rotorView.layer.add(rotation, forKey: "loading.rotation")

        return container
    }

    private func loadingIconColor(for name: String) -> UIColor {
        guard !name.isEmpty else {
            return UIColor(red: 33 / 255, green: 150 / 255, blue: 243 / 255, alpha: 1)
        }

        var hash: Int32 = 0
        for scalar in name.unicodeScalars {
            hash = 31 &* hash &+ Int32(scalar.value)
        }

        let hue = CGFloat(abs(hash % 360)) / 360.0
        let saturation = CGFloat(0.7 + Float(hash % 3000) / 10000.0)
        let brightness = CGFloat(0.8 + Float(hash % 2000) / 10000.0)
        return UIColor(hue: hue, saturation: saturation, brightness: brightness, alpha: 1)
    }

    private func hidePageLoading() {
        guard let loadingView = loadingView else {
            return
        }

        loadingView.removeFromSuperview()

        self.loadingView = nil
        self.loadingParentController = nil

        setupNavigationBar()
        navigator?.bringCapsuleToFront()
    }

    public func updateNavigationTitle(_ title: String) {
        let nextTitle = title.isEmpty ? appConfig.appName : title
        customNavigationTitleLabel?.text = nextTitle
        navigationItem.title = nextTitle
    }

    public func updateNavigationColor(backgroundColor: UIColor, textColor: UIColor, darkStyle: Bool) {
        customNavigationBar?.backgroundColor = backgroundColor
        customNavigationTitleLabel?.textColor = textColor
        updateCustomBackButton(darkStyle: darkStyle)
        updateCustomHomeButton(darkStyle: darkStyle)
    }

    private func updateCustomBackButton(darkStyle: Bool) {
        guard let backButton = customNavigationBackButton else {
            return
        }

        if let bundle = DMPResourceManager.assetsBundle {
            let imageName = darkStyle ? "arrow-back-dark" : "arrow-back-light"
            if let image = UIImage(named: imageName, in: bundle, compatibleWith: nil) {
                backButton.setImage(image.withRenderingMode(.alwaysOriginal), for: .normal)
                backButton.setTitle(nil, for: .normal)
                return
            }
        }

        backButton.setImage(nil, for: .normal)
        backButton.setTitle("back", for: .normal)
        backButton.setTitleColor(darkStyle ? .white : .black, for: .normal)
    }

    private func updateCustomHomeButton(darkStyle: Bool) {
        guard let homeButton = customNavigationHomeButton else {
            return
        }

        // 微信真机 home 图标的灰色圆形底，颜色随导航栏深浅切换
        customNavigationHomeButtonBackground?.backgroundColor = darkStyle
            ? UIColor.white.withAlphaComponent(0.24)
            : UIColor(red: 0.839, green: 0.839, blue: 0.839, alpha: 1)

        // 与返回箭头同机制：按 darkStyle 选择 Material home 造型的 SVG 资产
        if let bundle = DMPResourceManager.assetsBundle {
            let imageName = darkStyle ? "home-dark" : "home-light"
            if let image = UIImage(named: imageName, in: bundle, compatibleWith: nil) {
                homeButton.setImage(image.withRenderingMode(.alwaysOriginal), for: .normal)
                return
            }
        }

        // 资产缺失时退回 SF Symbol 模板渲染
        if let homeImage = UIImage(systemName: "house") {
            homeButton.setImage(homeImage.withRenderingMode(.alwaysTemplate), for: .normal)
        }
        homeButton.tintColor = darkStyle ? .white : .black
    }

    @objc private func customBackButtonTapped() {
        backButtonTapped()
    }

    // 路由判定收敛在 DMPNavigator.navigateHome（switchTab / redirectTo 的选择），按钮只发起
    @objc private func homeButtonTapped() {
        Task { @MainActor [weak self] in
            await self?.navigator?.navigateHome()
        }
    }

    func showMiniProgramMenuFromCapsule() {
        showMiniProgramMenu()
    }

    func closeMiniProgramFromCapsule() {
        guard !isClosingMiniProgram else {
            return
        }
        isClosingMiniProgram = true

        dismissMiniProgramMenu()
        navigator?.setCapsuleEnabled(false)

        guard let app else {
            isClosingMiniProgram = false
            navigator?.setCapsuleEnabled(true)
            return
        }
        Task { @MainActor [weak self] in
            do {
                try await app.hideMiniProgram()
                self?.isClosingMiniProgram = false
            } catch {
                self?.isClosingMiniProgram = false
                self?.navigator?.setCapsuleEnabled(true)
                DMPLogger.debug("capsule close rejected: \(error.localizedDescription)")
            }
        }
    }

    // MARK: ⋯菜单（对齐安卓 MiniProgramMenuSheet：头部/分割线/四瓦片/取消）

    /// ⋯菜单弹层配色（对齐安卓 DiminaActivity 的 sheet* 色板，深浅各一套；
    /// 语义=原生壳层 chrome，跟系统深浅色走，与小程序是否声明 darkmode 无关）。
    private struct MiniProgramMenuPalette {
        let container: UIColor
        let iconBg: UIColor
        let avatar: UIColor
        let title: UIColor
        let sub: UIColor
        let tileBg: UIColor
        let tileIcon: UIColor
        let tileLabel: UIColor
        let dividerTop: UIColor
        let dividerBottom: UIColor
        let cancel: UIColor

        static func standard(isDark: Bool) -> MiniProgramMenuPalette {
            func rgb(_ r: Int, _ g: Int, _ b: Int) -> UIColor {
                UIColor(red: CGFloat(r) / 255, green: CGFloat(g) / 255, blue: CGFloat(b) / 255, alpha: 1)
            }
            if isDark {
                return MiniProgramMenuPalette(
                    container: rgb(0x1D, 0x1F, 0x27),
                    iconBg: rgb(0x2A, 0x2D, 0x34),
                    avatar: rgb(0x9A, 0xA1, 0xA9),
                    title: rgb(0xE8, 0xEA, 0xED),
                    sub: rgb(0x9A, 0xA1, 0xA9),
                    tileBg: rgb(0x2A, 0x2D, 0x34),
                    tileIcon: rgb(0xE8, 0xEA, 0xED),
                    tileLabel: rgb(0xB9, 0xBF, 0xC7),
                    dividerTop: rgb(0x2A, 0x2D, 0x34),
                    dividerBottom: rgb(0x2A, 0x2D, 0x34),
                    cancel: rgb(0x7D, 0x90, 0xB8)
                )
            }
            return MiniProgramMenuPalette(
                container: .white,
                iconBg: rgb(0xF4, 0xF4, 0xF4),
                avatar: rgb(0x8A, 0x8A, 0x8A),
                title: rgb(0x20, 0x20, 0x20),
                sub: rgb(0x9A, 0x9A, 0x9A),
                tileBg: rgb(0xF8, 0xF8, 0xF8),
                tileIcon: rgb(0x33, 0x33, 0x33),
                tileLabel: rgb(0x68, 0x68, 0x68),
                dividerTop: rgb(0xF2, 0xF2, 0xF2),
                dividerBottom: rgb(0xED, 0xED, 0xED),
                cancel: rgb(0x57, 0x6B, 0x95)
            )
        }
    }

    private var isDarkMenuChrome: Bool {
        traitCollection.userInterfaceStyle == .dark
    }

    /// 版本副标题（对齐安卓：v版本名；缺版本名回落「小程序」）。
    private var menuVersionSubtitle: String {
        let trimmed = appConfig.versionName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !trimmed.isEmpty else {
            return "小程序"
        }
        let normalized = trimmed.hasPrefix("v") || trimmed.hasPrefix("V") ? String(trimmed.dropFirst()) : trimmed
        return "v" + normalized
    }

    private func showMiniProgramMenu() {
        guard miniProgramMenuContainerView == nil else {
            return
        }

        let palette = MiniProgramMenuPalette.standard(isDark: isDarkMenuChrome)

        let overlay = UIControl()
        overlay.translatesAutoresizingMaskIntoConstraints = false
        overlay.backgroundColor = UIColor.black.withAlphaComponent(0.55)
        overlay.addTarget(self, action: #selector(dismissMiniProgramMenu), for: .touchUpInside)

        let sheetView = UIView()
        sheetView.translatesAutoresizingMaskIntoConstraints = false
        sheetView.backgroundColor = palette.container
        sheetView.layer.cornerRadius = 18
        if #available(iOS 11.0, *) {
            sheetView.layer.maskedCorners = [.layerMinXMinYCorner, .layerMaxXMinYCorner]
        }

        // 头部图标：宿主传入的真包图标（DMPAppConfig.icon=png 文件路径）优先，
        // 无则回落首字母瓦片（对齐安卓 headerIcon 空分支）。
        let headerIconView: UIView
        if let iconPath = appConfig.icon, !iconPath.isEmpty,
            let iconImage = UIImage(contentsOfFile: iconPath)
        {
            let imageView = UIImageView(image: iconImage)
            imageView.contentMode = .scaleAspectFill
            imageView.clipsToBounds = true
            imageView.layer.cornerRadius = 22
            headerIconView = imageView
        } else {
            let iconLabel = UILabel()
            iconLabel.text = String(appConfig.appName.prefix(1)).isEmpty
                ? "小" : String(appConfig.appName.prefix(1))
            iconLabel.textAlignment = .center
            iconLabel.font = .systemFont(ofSize: 18, weight: .medium)
            iconLabel.textColor = palette.avatar
            iconLabel.backgroundColor = palette.iconBg
            iconLabel.layer.cornerRadius = 22
            iconLabel.clipsToBounds = true
            headerIconView = iconLabel
        }
        headerIconView.translatesAutoresizingMaskIntoConstraints = false

        let titleLabel = UILabel()
        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        titleLabel.text = appConfig.appName
        titleLabel.font = .systemFont(ofSize: 18, weight: .semibold)
        titleLabel.textColor = palette.title

        let subtitleLabel = UILabel()
        subtitleLabel.translatesAutoresizingMaskIntoConstraints = false
        subtitleLabel.text = menuVersionSubtitle
        subtitleLabel.font = .systemFont(ofSize: 14)
        subtitleLabel.textColor = palette.sub

        let titleStack = UIStackView(arrangedSubviews: [titleLabel, subtitleLabel])
        titleStack.translatesAutoresizingMaskIntoConstraints = false
        titleStack.axis = .vertical
        titleStack.spacing = 3

        let headerView = UIView()
        headerView.translatesAutoresizingMaskIntoConstraints = false
        headerView.addSubview(headerIconView)
        headerView.addSubview(titleStack)

        let topDivider = UIView()
        topDivider.translatesAutoresizingMaskIntoConstraints = false
        topDivider.backgroundColor = palette.dividerTop

        let symbolConfiguration = UIImage.SymbolConfiguration(pointSize: 24, weight: .medium)
        func tintedSymbol(_ name: String) -> UIImage {
            UIImage(systemName: name, withConfiguration: symbolConfiguration)?
                .withTintColor(palette.tileIcon, renderingMode: .alwaysOriginal) ?? UIImage()
        }

        // 四瓦片顺序对齐安卓：问AI浮窗 / 浮窗 / 重新进入小程序 / 关闭小程序
        let askAiItem = makeMiniProgramAskAiMenuItem(palette: palette, action: #selector(miniProgramMenuAskAiTapped))
        let floatItem = makeMiniProgramMenuItem(
            title: "浮窗",
            image: tintedSymbol("picture.in.picture"),
            action: #selector(miniProgramMenuFloatTapped),
            palette: palette
        )
        let reenterItem = makeMiniProgramMenuItem(
            title: "重新进入\n小程序",
            image: tintedSymbol("arrow.clockwise"),
            action: #selector(miniProgramMenuReenterTapped),
            palette: palette
        )
        let closeItem = makeMiniProgramMenuItem(
            title: "关闭小程序",
            image: tintedSymbol("xmark"),
            action: #selector(miniProgramMenuCloseTapped),
            palette: palette
        )

        let actionStack = UIStackView(arrangedSubviews: [askAiItem, floatItem, reenterItem, closeItem])
        actionStack.translatesAutoresizingMaskIntoConstraints = false
        actionStack.axis = .horizontal
        actionStack.distribution = .fill
        actionStack.alignment = .top

        let bottomDivider = UIView()
        bottomDivider.translatesAutoresizingMaskIntoConstraints = false
        bottomDivider.backgroundColor = palette.dividerBottom

        let cancelButton = UIButton(type: .custom)
        cancelButton.translatesAutoresizingMaskIntoConstraints = false
        cancelButton.setTitle("取消", for: .normal)
        cancelButton.setTitleColor(palette.cancel, for: .normal)
        cancelButton.titleLabel?.font = .systemFont(ofSize: 18)
        cancelButton.addTarget(self, action: #selector(dismissMiniProgramMenu), for: .touchUpInside)

        guard let presentationView = navigationController?.view ?? parent?.view ?? view else {
            return
        }
        presentationView.addSubview(overlay)
        overlay.addSubview(sheetView)
        sheetView.addSubview(headerView)
        sheetView.addSubview(topDivider)
        sheetView.addSubview(actionStack)
        sheetView.addSubview(bottomDivider)
        sheetView.addSubview(cancelButton)

        // 瓦片间距按屏宽自适应（78pt 固定瓦片宽对齐安卓；窄屏收间距、弹层居中）
        let tileWidth: CGFloat = 78
        let tileSpacing = miniProgramMenuTileSpacing(in: presentationView, tileWidth: tileWidth, tileCount: 4)
        let stackWidth = tileWidth * 4 + tileSpacing * 3
        let stackLeading = max(8, (presentationView.bounds.width - stackWidth) / 2)

        let bottomInset = presentationView.safeAreaInsets.bottom
        NSLayoutConstraint.activate([
            overlay.topAnchor.constraint(equalTo: presentationView.topAnchor),
            overlay.leadingAnchor.constraint(equalTo: presentationView.leadingAnchor),
            overlay.trailingAnchor.constraint(equalTo: presentationView.trailingAnchor),
            overlay.bottomAnchor.constraint(equalTo: presentationView.bottomAnchor),

            sheetView.leadingAnchor.constraint(equalTo: overlay.leadingAnchor),
            sheetView.trailingAnchor.constraint(equalTo: overlay.trailingAnchor),
            sheetView.bottomAnchor.constraint(equalTo: overlay.bottomAnchor),

            headerView.topAnchor.constraint(equalTo: sheetView.topAnchor),
            headerView.leadingAnchor.constraint(equalTo: sheetView.leadingAnchor),
            headerView.trailingAnchor.constraint(equalTo: sheetView.trailingAnchor),
            headerView.heightAnchor.constraint(equalToConstant: 84),

            headerIconView.leadingAnchor.constraint(equalTo: headerView.leadingAnchor, constant: 24),
            headerIconView.centerYAnchor.constraint(equalTo: headerView.centerYAnchor),
            headerIconView.widthAnchor.constraint(equalToConstant: 44),
            headerIconView.heightAnchor.constraint(equalToConstant: 44),

            titleStack.leadingAnchor.constraint(equalTo: headerIconView.trailingAnchor, constant: 14),
            titleStack.centerYAnchor.constraint(equalTo: headerView.centerYAnchor),
            titleStack.trailingAnchor.constraint(lessThanOrEqualTo: headerView.trailingAnchor, constant: -24),

            topDivider.topAnchor.constraint(equalTo: headerView.bottomAnchor),
            topDivider.leadingAnchor.constraint(equalTo: sheetView.leadingAnchor),
            topDivider.trailingAnchor.constraint(equalTo: sheetView.trailingAnchor),
            topDivider.heightAnchor.constraint(equalToConstant: 1 / UIScreen.main.scale),

            actionStack.topAnchor.constraint(equalTo: topDivider.bottomAnchor, constant: 20),
            actionStack.leadingAnchor.constraint(equalTo: sheetView.leadingAnchor, constant: stackLeading),
            actionStack.widthAnchor.constraint(equalToConstant: stackWidth),
            actionStack.heightAnchor.constraint(equalToConstant: 94),

            bottomDivider.topAnchor.constraint(equalTo: actionStack.bottomAnchor, constant: 20),
            bottomDivider.leadingAnchor.constraint(equalTo: sheetView.leadingAnchor),
            bottomDivider.trailingAnchor.constraint(equalTo: sheetView.trailingAnchor),
            bottomDivider.heightAnchor.constraint(equalToConstant: 1 / UIScreen.main.scale),

            cancelButton.topAnchor.constraint(equalTo: bottomDivider.bottomAnchor),
            cancelButton.leadingAnchor.constraint(equalTo: sheetView.leadingAnchor),
            cancelButton.trailingAnchor.constraint(equalTo: sheetView.trailingAnchor),
            cancelButton.heightAnchor.constraint(equalToConstant: 58),
            cancelButton.bottomAnchor.constraint(equalTo: sheetView.bottomAnchor, constant: -bottomInset),
        ])

        miniProgramMenuContainerView = overlay
        presentationView.bringSubviewToFront(overlay)
    }

    /// 瓦片横向间距：宽屏 16pt（对齐安卓 spacedBy 16），窄屏等比收紧、下限 6pt。
    private func miniProgramMenuTileSpacing(in presentationView: UIView, tileWidth: CGFloat, tileCount: Int) -> CGFloat {
        let horizontalPadding: CGFloat = 24
        let available = presentationView.bounds.width - horizontalPadding * 2
        let ideal = floor((available - CGFloat(tileCount) * tileWidth) / CGFloat(max(tileCount - 1, 1)))
        return max(6, min(16, ideal))
    }

    private func makeMiniProgramMenuItem(
        title: String,
        image: UIImage,
        action: Selector,
        palette: MiniProgramMenuPalette
    ) -> UIControl {
        let control = UIControl()
        control.translatesAutoresizingMaskIntoConstraints = false
        control.addTarget(self, action: action, for: .touchUpInside)

        let iconContainer = UIView()
        iconContainer.translatesAutoresizingMaskIntoConstraints = false
        iconContainer.backgroundColor = palette.tileBg
        iconContainer.layer.cornerRadius = 10
        iconContainer.isUserInteractionEnabled = false

        let imageView = UIImageView(image: image)
        imageView.translatesAutoresizingMaskIntoConstraints = false
        imageView.contentMode = .center

        let label = UILabel()
        label.translatesAutoresizingMaskIntoConstraints = false
        label.text = title
        label.font = .systemFont(ofSize: 13)
        label.textColor = palette.tileLabel
        label.textAlignment = .center
        label.numberOfLines = 2

        control.addSubview(iconContainer)
        iconContainer.addSubview(imageView)
        control.addSubview(label)

        NSLayoutConstraint.activate([
            control.widthAnchor.constraint(equalToConstant: 78),

            iconContainer.topAnchor.constraint(equalTo: control.topAnchor),
            iconContainer.centerXAnchor.constraint(equalTo: control.centerXAnchor),
            iconContainer.widthAnchor.constraint(equalToConstant: 52),
            iconContainer.heightAnchor.constraint(equalToConstant: 52),

            imageView.centerXAnchor.constraint(equalTo: iconContainer.centerXAnchor),
            imageView.centerYAnchor.constraint(equalTo: iconContainer.centerYAnchor),
            imageView.widthAnchor.constraint(equalToConstant: 24),
            imageView.heightAnchor.constraint(equalToConstant: 24),

            label.topAnchor.constraint(equalTo: iconContainer.bottomAnchor, constant: 8),
            label.leadingAnchor.constraint(equalTo: control.leadingAnchor),
            label.trailingAnchor.constraint(equalTo: control.trailingAnchor),
            label.bottomAnchor.constraint(lessThanOrEqualTo: control.bottomAnchor),
        ])

        return control
    }

    /// 「问AI浮窗」开关瓦片（对齐安卓 MiniProgramAskAiMenuTile）：图标=悬浮球本体缩微，
    /// 亮=悬浮球显示中、整图标置灰=已关闭；点击 toggle 同一持久化状态，弹层不收。
    private func makeMiniProgramAskAiMenuItem(
        palette: MiniProgramMenuPalette,
        action: Selector
    ) -> UIControl {
        let control = UIControl()
        control.translatesAutoresizingMaskIntoConstraints = false
        control.addTarget(self, action: action, for: .touchUpInside)

        let iconContainer = UIView()
        iconContainer.translatesAutoresizingMaskIntoConstraints = false
        iconContainer.backgroundColor = palette.tileBg
        iconContainer.layer.cornerRadius = 10
        iconContainer.isUserInteractionEnabled = false

        let miniature = AskAiFloatMiniatureView(dimmed: !AskAiFloatStore.isEnabled, isDark: isDarkMenuChrome)
        miniature.translatesAutoresizingMaskIntoConstraints = false
        askAiMenuTileMiniature = miniature

        let label = UILabel()
        label.translatesAutoresizingMaskIntoConstraints = false
        label.text = "问AI浮窗"
        label.font = .systemFont(ofSize: 13)
        label.textColor = palette.tileLabel
        label.textAlignment = .center
        label.numberOfLines = 1

        control.addSubview(iconContainer)
        iconContainer.addSubview(miniature)
        control.addSubview(label)

        NSLayoutConstraint.activate([
            control.widthAnchor.constraint(equalToConstant: 78),

            iconContainer.topAnchor.constraint(equalTo: control.topAnchor),
            iconContainer.centerXAnchor.constraint(equalTo: control.centerXAnchor),
            iconContainer.widthAnchor.constraint(equalToConstant: 52),
            iconContainer.heightAnchor.constraint(equalToConstant: 52),

            miniature.centerXAnchor.constraint(equalTo: iconContainer.centerXAnchor),
            miniature.centerYAnchor.constraint(equalTo: iconContainer.centerYAnchor),
            miniature.widthAnchor.constraint(equalToConstant: 30),
            miniature.heightAnchor.constraint(equalToConstant: 30),

            label.topAnchor.constraint(equalTo: iconContainer.bottomAnchor, constant: 8),
            label.leadingAnchor.constraint(equalTo: control.leadingAnchor),
            label.trailingAnchor.constraint(equalTo: control.trailingAnchor),
            label.bottomAnchor.constraint(lessThanOrEqualTo: control.bottomAnchor),
        ])

        return control
    }

    @objc private func dismissMiniProgramMenu() {
        miniProgramMenuContainerView?.removeFromSuperview()
        miniProgramMenuContainerView = nil
        askAiMenuTileMiniature = nil
    }

    /// 深浅色切换：菜单开着则按新配色重建（瓦片缩微图标/悬浮球徽章随之重绘，
    /// 悬浮球徽章本身为定稿配色、不随主题）。
    private func refreshMenuForTraitChange() {
        guard miniProgramMenuContainerView != nil else {
            return
        }
        dismissMiniProgramMenu()
        showMiniProgramMenu()
    }

    @objc private func miniProgramMenuReenterTapped() {
        dismissMiniProgramMenu()
        Task { @MainActor in
            await app?.reEnter()
        }
    }

    @objc private func miniProgramMenuCloseTapped() {
        dismissMiniProgramMenu()
        closeMiniProgramFromCapsule()
    }

    @objc private func miniProgramMenuAskAiTapped() {
        let next = !AskAiFloatStore.isEnabled
        AskAiFloatStore.isEnabled = next
        askAiMenuTileMiniature?.update(dimmed: !next, isDark: isDarkMenuChrome)
        // 同步当前页面实例的悬浮球显隐（其它页面 viewWillAppear 时会对齐）
        syncAskAiFloatButton()
    }

    @objc private func miniProgramMenuFloatTapped() {
        dismissMiniProgramMenu()
        floatMiniProgramFromMenu()
    }

    /// 「浮窗」瓦片：整栈收后台（不销毁运行时/页面栈，对齐安卓 hideMiniProgram），
    /// 成功后 POST dimina.miniProgramFloated 让宿主展示浮窗球。
    private func floatMiniProgramFromMenu() {
        guard let app, !isClosingMiniProgram else {
            return
        }
        isClosingMiniProgram = true
        navigator?.setCapsuleEnabled(false)

        let appId = app.getAppId()
        let appName = appConfig.appName
        Task { @MainActor [weak self] in
            do {
                try await app.hideMiniProgram()
                self?.isClosingMiniProgram = false
                NotificationCenter.default.post(
                    name: .diminaMiniProgramFloated,
                    object: nil,
                    userInfo: [
                        DMPMiniProgramEventUserInfo.appId: appId,
                        DMPMiniProgramEventUserInfo.appName: appName
                    ]
                )
            } catch {
                self?.isClosingMiniProgram = false
                self?.navigator?.setCapsuleEnabled(true)
                DMPLogger.debug("menu float rejected: \(error.localizedDescription)")
            }
        }
    }

    // MARK: 问AI悬浮球（挂载/摘除与事件上报）

    /// 悬浮球显隐跟随持久化开关 dimina_ask_ai_float_enabled（默认显示中）。
    private func syncAskAiFloatButton() {
        if AskAiFloatStore.isEnabled {
            mountAskAiFloatButtonIfNeeded()
        } else {
            removeAskAiFloatButton()
        }
    }

    private func mountAskAiFloatButtonIfNeeded() {
        guard askAiFloatButton == nil else {
            return
        }
        let button = AskAiFloatButton(frame: CGRect(x: 0, y: 0, width: 44, height: 44))
        button.onTap = { [weak self] in
            self?.handleAskAiFloatTapped()
        }
        button.onClose = { [weak self] in
            self?.handleAskAiFloatClosed()
        }
        view.addSubview(button)
        view.bringSubviewToFront(button)
        askAiFloatButton = button
    }

    private func removeAskAiFloatButton() {
        guard let button = askAiFloatButton else {
            return
        }
        askAiFloatButton = nil
        button.prepareForRemoval()
    }

    /// 长按 ✕：持久化关闭并摘除悬浮球（对齐安卓 setAskAiFloatEnabled(false)）。
    private func handleAskAiFloatClosed() {
        AskAiFloatStore.isEnabled = false
        removeAskAiFloatButton()
    }

    /// 悬浮球点击：POST dimina.miniProgramAskAiInvoked（容器只上报，不认识
    /// 「问AI」业务语义；收小程序由宿主收到事件后决定）。
    private func handleAskAiFloatTapped() {
        let appId = app?.getAppId() ?? appConfig.appId
        var userInfo: [String: Any] = [DMPMiniProgramEventUserInfo.appId: appId]
        if let pageTitle = normalizedPageContext(customNavigationTitleLabel?.text) {
            userInfo[DMPMiniProgramEventUserInfo.pageTitle] = pageTitle
        }
        if let pagePath = normalizedPageContext(webview.getPagePath()) {
            userInfo[DMPMiniProgramEventUserInfo.pagePath] = pagePath
        }
        NotificationCenter.default.post(
            name: .diminaMiniProgramAskAiInvoked,
            object: nil,
            userInfo: userInfo
        )
    }

    /// 事件上下文归一（对齐安卓 AskAiEventPayload：blank 归 nil，不强造）。
    private func normalizedPageContext(_ value: String?) -> String? {
        guard let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines),
            !trimmed.isEmpty
        else {
            return nil
        }
        return trimmed
    }

    // Set navigation bar style
    private func setupNavigationBar() {
        navigationController?.setNavigationBarHidden(true, animated: false)
        navigationItem.hidesBackButton = true
        navigationItem.backButtonTitle = ""

        let pageRecord = navigator?.pageRecord(webViewId: webview.getWebViewId())
        let navStyle = pageRecord?.navStyle
            ?? app?.getBundleAppConfig()?.getPageConfig(pagePath: pagePath)
        let darkStyle = (navStyle?["navigationBarTextStyle"] as? String) == "white"
        var title = appConfig.appName
        var backgroundColor = UIColor.white
        var textColor = darkStyle ? UIColor.white : UIColor.black
        var isCustomNavigationStyle = false

        if let navStyle = navStyle {
            if let navTitle = navStyle["navigationBarTitleText"] as? String, !navTitle.isEmpty {
                title = navTitle
            }

            if let navigationStyle = navStyle["navigationStyle"] as? String {
                isCustomNavigationStyle = navigationStyle == "custom"
            }

            if let navBackgroundColor = navStyle["navigationBarBackgroundColor"] as? String {
                backgroundColor = DMPUtil.colorFromHexString(navBackgroundColor) ?? .white
            }

            if let navTextStyle = navStyle["navigationBarTextStyle"] as? String {
                textColor = navTextStyle == "white" ? .white : .black
            }

            // Only set default style if navigationItem has not been customized
            // This ensures that styles set via API are not overridden
            if navigationItem.standardAppearance == nil,
                let backgroundColor = navStyle["navigationBarBackgroundColor"] as? String,
                let textStyle = navStyle["navigationBarTextStyle"] as? String
            {

                let darkStyle = textStyle == "white"
                let bgColor = DMPUtil.colorFromHexString(backgroundColor) ?? .white
                let textColor: UIColor = darkStyle ? .white : .black

                let appearance = UINavigationBarAppearance()
                appearance.configureWithOpaqueBackground()
                appearance.backgroundColor = bgColor
                appearance.titleTextAttributes = [.foregroundColor: textColor]

                // Set navigation bar appearance for the current controller
                navigationItem.standardAppearance = appearance
                navigationItem.scrollEdgeAppearance = appearance
                navigationItem.compactAppearance = appearance

                if #available(iOS 15.0, *) {
                    navigationItem.compactScrollEdgeAppearance = appearance
                }

                // Set navigation bar button color
                navigationController?.navigationBar.tintColor = textColor

                DMPUIManager.updateWindowStyle(isDarkTheme: darkStyle)
            }
        }

        updateNavigationTitle(title)
        updateNavigationColor(backgroundColor: backgroundColor, textColor: textColor, darkStyle: darkStyle)

        let leftNav = resolveLeftNavAffordances(
            isCustomNavigationStyle: isCustomNavigationStyle,
            homeButtonForceHidden: pageRecord?.homeButtonForceHidden ?? false,
            homeButtonForcedByConfig: (navStyle?["homeButton"] as? Bool) == true
        )
        customNavigationBackButton?.isHidden = !leftNav.showBack
        customNavigationHomeButton?.isHidden = !leftNav.showHome
        customNavigationHomeButtonBackground?.isHidden = !leftNav.showHome
        // home 键位置随返回箭头存在与否切换：并存时紧随箭头，否则独占左槽
        if leftNav.showBack {
            homeButtonLeadingToEdge?.isActive = false
            homeButtonLeadingAfterBack?.isActive = true
        } else {
            homeButtonLeadingAfterBack?.isActive = false
            homeButtonLeadingToEdge?.isActive = true
        }

        customNavigationBar?.isHidden = isCustomNavigationStyle
        webViewTopToNavigationConstraint?.isActive = !isCustomNavigationStyle
        webViewTopToViewConstraint?.isActive = isCustomNavigationStyle

        if !isCustomNavigationStyle, let customNavigationBar = customNavigationBar {
            view.bringSubviewToFront(customNavigationBar)
        }
    }

    /// 导航栏左侧的两个 affordance（微信真机实测语义）：
    /// 返回箭头 = 非栈底页面或宿主隐藏胶囊；home 键 = 非首页 + 非 tabBar 页（这两条排除
    /// `homeButton: true` 也不能突破）且（栈底自动显示 ‖ 页面配置
    /// `homeButton: true`——此时与返回箭头并存）。`wx.hideHomeButton()` 只压制
    /// home 键。
    private func resolveLeftNavAffordances(
        isCustomNavigationStyle: Bool,
        homeButtonForceHidden: Bool,
        homeButtonForcedByConfig: Bool
    ) -> (showBack: Bool, showHome: Bool) {
        guard !isCustomNavigationStyle else {
            return (false, false)
        }

        // isRoot 表示这个页面在栈底（没有可以返回的上一页）
        let showBack = !isRoot || !DMPAppManager.sharedInstance().showCapsule

        if homeButtonForceHidden {
            return (showBack, false)
        }

        guard let bundleAppConfig = app?.getBundleAppConfig() else {
            return (showBack, false)
        }

        if bundleAppConfig.isTabBarPage(pagePath: pagePath) {
            return (showBack, false)
        }

        // entryPagePath 由 DMPBundleAppConfig 统一输出规范形态（无前导斜杠），
        // 只需归一化当前页一侧。pagePath 不保证已归一化：站内路由 URL
        // （navigateTo/redirectTo/switchTab/reLaunch）经 DMPUtil.queryPath 已
        // 归一化，但 tabBar 页的 pagePath 直接取自 tabBar 配置项
        // （DMPTabBarContainerController 的 onSelect/prepareInitialTab），
        // 未经 queryPath，写法不受调用方控制；entry 未知（空）时自动规则关闭
        let entryPagePath = bundleAppConfig.entryPagePath
        if entryPagePath.isEmpty || DMPUtil.normalizePagePath(pagePath) == entryPagePath {
            return (showBack, false)
        }

        return (showBack, isRoot || homeButtonForcedByConfig)
    }

    /// 仅当这个 controller 当前显示的正是 `webViewId` 时才重新计算导航栏（含
    /// home 按钮显隐）；其它页面这里不做任何事——它们的 `viewWillAppear` 会
    /// 重新跑一次 `setupNavigationBar()`，自然会读到后台期间被设置的页面标记
    public func refreshNavigationBar(ifDisplaying webViewId: Int) {
        guard webview.getWebViewId() == webViewId else { return }
        setupNavigationBar()
    }

    // Back button tap event
    @objc private func backButtonTapped() {
        if isRoot && !DMPAppManager.sharedInstance().showCapsule {
            closeMiniProgramFromCapsule()
            return
        }
        if let navigator = navigator {
            navigator.handleBackButtonTapped()
        } else {
            navigationController?.popViewController(animated: true)
        }
    }

    // Get WebView instance
    public func getWebView() -> DMPWebview {
        return webview
    }

    // Called when page is shown
    public func onShow() {
        // Add your logic here
    }
    
    // MARK: - Lifecycle Methods
    
    public override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        hidePageLoading()
        
        // Notify lifecycle management when page completely disappears
        if isMovingFromParent && navigator?.isRetainedInBackground != true {
            // Page is removed from navigation stack
            destroyWebView()
        }
    }
    
    // Destroy WebView
    private func destroyWebView() {
        // Add state check to prevent duplicate destruction
        guard !isWebViewDestroyed else {
            DMPLogger.debug("🟡 DMPPageController: WebView (ID: \(webview.getWebViewId())) has already been destroyed, skipping duplicate operation")
            return
        }
        
        DMPLogger.debug("🗑️ DMPPageController: Destroy WebView (ID: \(webview.getWebViewId()))")
        isWebViewDestroyed = true
        webview.clearLoadingStateObserver(ownerToken: loadingStateObserverToken)

        // 问AI悬浮球随页面拆毁移除（deinit 可能不在主线程，UI 操作投递主线程）
        if let floatButton = askAiFloatButton {
            askAiFloatButton = nil
            DispatchQueue.main.async {
                floatButton.prepareForRemoval()
            }
        }

        // [game-touch] BEGIN 桥销毁即摘快路：丢挂起 move + 摘手势（只摘自己实例，
        // WebView 复用场景不影响新页面刚挂的快路）（整块可删）。
        gameTouchRelay?.dispose()
        gameTouchRelay = nil
        // [game-touch] END

        notifyRoutingUnloadIfNeeded()
        
        // Release WebView back to pool
        app?.render?.releaseWebView(webview)
    }
    
    /// 标注这次拆栈的原因。必须在页面离开导航栈之前调用：销毁可能要等转场动画结束才发生，
    /// 那时导航栈里已经找不到这个控制器了。
    public func markTeardownReason(_ reason: DMPPageStateTeardown) {
        pageStateTeardownReason = reason
    }

    /// 路由层在修改原生导航栈之前调用，保证旧页 onUnload 先于新页 onShow；真正的桥消息仍由
    /// 页面控制器单点拥有。稍后的 viewDidDisappear/deinit 会再次走这里，但不会重复派发。
    func notifyRoutingUnloadIfNeeded() {
        guard pageStateTeardownReason == .routing,
              !hasDispatchedPageUnload,
              let app
        else {
            return
        }
        hasDispatchedPageUnload = true
        DMPPageLifecycle(app: app).onUnload(webviewId: webview.getWebViewId())
    }

    // Manual destroy method (for external calls)
    public func destroy() {
        destroyWebView()
    }
    
    deinit {
        DMPLogger.debug("🗑️ DMPPageController: deinit (WebView ID: \(webview.getWebViewId()))")
        webview.clearLoadingStateObserver(ownerToken: loadingStateObserverToken)
        // Ensure WebView is correctly released
        destroyWebView()
    }
}

// SwiftUI view container for displaying WebView
public struct DMPWebViewContainer: View {
    @ObservedObject var webview: DMPWebview
    var isRoot: Bool = false

    public init(webview: DMPWebview, isRoot: Bool = false) {
        self.webview = webview
        self.isRoot = isRoot
    }

    public var body: some View {
        ZStack {
            DMPWebview.WebViewRepresentable(webview: webview)
        }
        .ignoresSafeArea(.container, edges: [.top, .bottom])
    }
}
