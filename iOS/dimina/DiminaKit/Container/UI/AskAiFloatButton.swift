//
//  AskAiFloatButton.swift
//  dimina
//
//  「问AI悬浮球」——视觉/行为对齐安卓 AskAiFloatButton.kt（2026-11-15 定稿规格）：
//  - 44pt 热区内圆形双星徽章贴满（徽章 42pt：深蓝圆底 #071328 + 白 50% 外圈描边
//    + 白 10% 内细环 + 大四角星 #F5C24B + 右上小四角星 #FFE08A），轻投影。
//    徽章为定稿字面量配色，深浅色同套（对齐安卓）。
//  - 可拖拽：clamp 屏内（左右缘 8pt / 上下安全区外 12pt），松手吸附较近左/右缘；
//    默认落点右缘、可动区高度 62% 处。
//  - 点击（非编辑态）→ onTap（容器外发 dimina.miniProgramAskAiInvoked 事件）；
//  - 长按 → 编辑态：放大 1.1 + rotate ±3° 抖动 + 右上 20pt ✕ 角标（#E5484D 白叉），
//    点 ✕ → onClose（容器持久化关闭）；编辑态下点悬浮球外任意区域退出编辑态。
//  - 手势只属于悬浮球自身（pan/tap/longPress 挂在本体，遮罩仅编辑态存在），
//    不拦截 WebView 手势。
//

import UIKit

// MARK: - 持久化开关（对齐安卓 AskAiFloatStore：同一 key、默认 true）

/// 问AI悬浮球显隐的持久化状态。语义 = 悬浮球显示中(true)/已关闭(false)；
/// ⋯菜单「问AI浮窗」瓦片与悬浮球长按 ✕ 读写同一份状态。
public enum AskAiFloatStore {
    /// 与安卓共用的存储 key，勿改。
    public static let defaultsKey = "dimina_ask_ai_float_enabled"

    public static var isEnabled: Bool {
        get {
            UserDefaults.standard.object(forKey: defaultsKey) as? Bool ?? true
        }
        set {
            UserDefaults.standard.set(newValue, forKey: defaultsKey)
        }
    }
}

// MARK: - 规格常量（对齐安卓 AskAiFloatButton.kt，勿随手改）

/// 悬浮球热区边长（pt）。
private let askAiFloatSize: CGFloat = 44
/// 徽章绘制边长 = 热区 44 − 两侧各 1pt 描边留白。
private let askAiFloatBadgeSize: CGFloat = 42
/// 投影档位（对齐安卓 shadow 4dp 圆形档位）。
private let askAiFloatShadow: CGFloat = 4
/// 吸附后距屏幕左右缘的留白。
private let askAiEdgeMargin: CGFloat = 8
/// 上下 clamp 在安全区之外再留的呼吸边距。
private let askAiVerticalMargin: CGFloat = 12
/// 默认落点 = 可动区高度的 62% 处（右缘）。
private let askAiDefaultYFraction: CGFloat = 0.62
/// 吸附动画时长（秒）。
private let askAiSnapDuration: TimeInterval = 0.18
/// 编辑态放大倍数与抖角（rotate ±3° 往返循环）。
private let askAiEditScale: CGFloat = 1.1
private let askAiEditWobbleDegrees: CGFloat = 3
/// ✕ 角标：20pt、底 #E5484D、白叉；右上角外探 8pt、上探 4pt 压角。
private let askAiCloseBadgeSize: CGFloat = 20
private let askAiCloseBadgeOutset: CGFloat = 8

/// 星河双星徽章定稿色（与安卓同一字面量）。
private let askAiEmblemCircle = UIColor(red: 0x07 / 255.0, green: 0x13 / 255.0, blue: 0x28 / 255.0, alpha: 1)
private let askAiEmblemBigStar = UIColor(red: 0xF5 / 255.0, green: 0xC2 / 255.0, blue: 0x4B / 255.0, alpha: 1)
private let askAiEmblemSmallStar = UIColor(red: 0xFF / 255.0, green: 0xE0 / 255.0, blue: 0x8A / 255.0, alpha: 1)
private let askAiEmblemBorder = UIColor.white.withAlphaComponent(0.5)
private let askAiEmblemInnerRing = UIColor.white.withAlphaComponent(0.1)
/// 置灰态双星（⋯菜单瓦片「已关闭」）。
private let askAiEmblemDimStar = UIColor(red: 0x8A / 255.0, green: 0x93 / 255.0, blue: 0xA6 / 255.0, alpha: 1)

// MARK: - 徽章画笔（96×96 设计稿等比缩放，路径为定稿字面量，勿随手改）

/// 大四角星：M44 21 Q47.5 45.5 71 50 Q47.5 54.5 44 79 Q40.5 54.5 17 50 Q40.5 45.5 44 21 Z
private func askAiBigStarPath() -> UIBezierPath {
    let path = UIBezierPath()
    path.move(to: CGPoint(x: 44, y: 21))
    path.addQuadCurve(to: CGPoint(x: 71, y: 50), controlPoint: CGPoint(x: 47.5, y: 45.5))
    path.addQuadCurve(to: CGPoint(x: 44, y: 79), controlPoint: CGPoint(x: 47.5, y: 54.5))
    path.addQuadCurve(to: CGPoint(x: 17, y: 50), controlPoint: CGPoint(x: 40.5, y: 54.5))
    path.addQuadCurve(to: CGPoint(x: 44, y: 21), controlPoint: CGPoint(x: 40.5, y: 45.5))
    path.close()
    return path
}

/// 小四角星：M68 22 Q69.9 34.3 82 37 Q69.9 39.7 68 52 Q66.1 39.7 54 37 Q66.1 34.3 68 22 Z
private func askAiSmallStarPath() -> UIBezierPath {
    let path = UIBezierPath()
    path.move(to: CGPoint(x: 68, y: 22))
    path.addQuadCurve(to: CGPoint(x: 82, y: 37), controlPoint: CGPoint(x: 69.9, y: 34.3))
    path.addQuadCurve(to: CGPoint(x: 68, y: 52), controlPoint: CGPoint(x: 69.9, y: 39.7))
    path.addQuadCurve(to: CGPoint(x: 54, y: 37), controlPoint: CGPoint(x: 66.1, y: 39.7))
    path.addQuadCurve(to: CGPoint(x: 68, y: 22), controlPoint: CGPoint(x: 66.1, y: 34.3))
    path.close()
    return path
}

/// 星河双星徽章：圆底圆心 (48,48) r46.5 贴满画布，外圈白 50% 主描边宽 2 贴边，
/// 内环白 10% 细环加层次；`withRing=false` 时只画圆底+双星（⋯菜单缩微图标用）。
func drawAskAiEmblem(
    in rect: CGRect,
    bigStarColor: UIColor,
    smallStarColor: UIColor,
    circleColor: UIColor = askAiEmblemCircle,
    withRing: Bool = true
) {
    guard let context = UIGraphicsGetCurrentContext() else { return }
    let side = min(rect.width, rect.height)
    let scale = side / 96.0
    let originX = rect.minX + (rect.width - side) / 2
    let originY = rect.minY + (rect.height - side) / 2

    context.saveGState()
    context.translateBy(x: originX, y: originY)
    context.scaleBy(x: scale, y: scale)

    // 圆底 r=46.5 圆心(48,48)
    context.setFillColor(circleColor.cgColor)
    context.fillEllipse(in: CGRect(x: 48 - 46.5, y: 48 - 46.5, width: 93, height: 93))

    if withRing {
        context.setStrokeColor(askAiEmblemBorder.cgColor)
        context.setLineWidth(2)
        context.strokeEllipse(in: CGRect(x: 48 - 45.5, y: 48 - 45.5, width: 91, height: 91))
        context.setStrokeColor(askAiEmblemInnerRing.cgColor)
        context.setLineWidth(1)
        context.strokeEllipse(in: CGRect(x: 48 - 40, y: 48 - 40, width: 80, height: 80))
    }

    context.setFillColor(bigStarColor.cgColor)
    context.addPath(askAiBigStarPath().cgPath)
    context.fillPath()

    context.setFillColor(smallStarColor.cgColor)
    context.addPath(askAiSmallStarPath().cgPath)
    context.fillPath()

    context.restoreGState()
}

// MARK: - 悬浮球本体

/// 「问AI悬浮球」44pt 圆形徽章控件。挂载/摘除与显隐状态由页面控制器
/// （DMPPageController）按 AskAiFloatStore 门控，本类不认识持久化。
final class AskAiFloatButton: UIControl {

    /// 点击（非编辑态）回调：容器在此 POST dimina.miniProgramAskAiInvoked。
    var onTap: (() -> Void)?
    /// 编辑态 ✕ 点击回调：容器在此持久化关闭并摘除悬浮球。
    var onClose: (() -> Void)?

    /// 徽章左上角（容器坐标）；nil = 尚未按容器尺寸落默认位（避免 (0,0) 闪现）。
    private var position: CGPoint?
    private var isEditingMode = false
    private weak var editMaskView: UIView?
    private var closeBadgeView: UIView?
    private var isSnapAnimating = false

    private let badgeView = AskAiEmblemBadgeView()

    override init(frame: CGRect) {
        super.init(frame: frame)

        isUserInteractionEnabled = true
        clipsToBounds = false
        backgroundColor = .clear
        accessibilityLabel = "问AI"

        // 轻投影（对齐安卓 shadow 4dp 圆形档位）
        layer.shadowColor = UIColor.black.cgColor
        layer.shadowOpacity = 0.25
        layer.shadowRadius = askAiFloatShadow
        layer.shadowOffset = CGSize(width: 0, height: 1)

        addSubview(badgeView)

        // 手势只属于悬浮球自身：pan 跟手拖拽、tap 触发问AI、longPress 进编辑态
        let pan = UIPanGestureRecognizer(target: self, action: #selector(handlePan(_:)))
        addGestureRecognizer(pan)
        let tap = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
        addGestureRecognizer(tap)
        let longPress = UILongPressGestureRecognizer(target: self, action: #selector(handleLongPress(_:)))
        addGestureRecognizer(longPress)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override var intrinsicContentSize: CGSize {
        CGSize(width: askAiFloatSize, height: askAiFloatSize)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        layoutBadge()

        // 容器尺寸变化（首次布局/旋转）时：未落位则落默认位，已落位则重 clamp
        guard let container = superview, container.bounds.width > 0 else { return }
        if position == nil {
            position = defaultPosition(in: container)
        } else if !isSnapAnimating {
            position = clampPosition(position!, in: container)
        }
        if let position {
            frame.origin = position
        }
    }

    private func layoutBadge() {
        let inset = (askAiFloatSize - askAiFloatBadgeSize) / 2
        badgeView.frame = CGRect(
            x: inset, y: inset,
            width: askAiFloatBadgeSize, height: askAiFloatBadgeSize
        )
    }

    // MARK: 位置计算（clamp 屏内，对齐安卓 clampPosition/defaultPosition）

    private func clampPosition(_ pos: CGPoint, in container: UIView) -> CGPoint {
        let bounds = container.bounds
        let safeTop = container.safeAreaInsets.top
        let safeBottom = container.safeAreaInsets.bottom
        let minX = askAiEdgeMargin
        let maxX = max(minX, bounds.width - askAiFloatSize - askAiEdgeMargin)
        let minY = safeTop + askAiVerticalMargin
        let maxY = max(minY, bounds.height - safeBottom - askAiVerticalMargin - askAiFloatSize)
        return CGPoint(
            x: min(max(pos.x, minX), maxX),
            y: min(max(pos.y, minY), maxY)
        )
    }

    private func defaultPosition(in container: UIView) -> CGPoint {
        let clamped = clampPosition(.zero, in: container)
        let y = clamped.y + max(0, clampedMaxBottom(in: container) - clamped.y) * askAiDefaultYFraction
        return CGPoint(x: clampedMaxRight(in: container), y: y)
    }

    private func clampedMaxRight(in container: UIView) -> CGFloat {
        max(askAiEdgeMargin, container.bounds.width - askAiFloatSize - askAiEdgeMargin)
    }

    private func clampedMaxBottom(in container: UIView) -> CGFloat {
        let safeBottom = container.safeAreaInsets.bottom
        return max(
            safeTopMargin(in: container),
            container.bounds.height - safeBottom - askAiVerticalMargin - askAiFloatSize
        )
    }

    private func safeTopMargin(in container: UIView) -> CGFloat {
        container.safeAreaInsets.top + askAiVerticalMargin
    }

    // MARK: 拖拽（编辑态停用）+ 松手吸附较近左/右缘

    @objc private func handlePan(_ gesture: UIPanGestureRecognizer) {
        guard !isEditingMode, let container = superview else { return }

        switch gesture.state {
        case .began:
            cancelSnapAnimation()
            if let position {
                self.position = clampPosition(position, in: container)
            }
        case .changed:
            let translation = gesture.translation(in: container)
            gesture.setTranslation(.zero, in: container)
            if let position {
                self.position = clampPosition(
                    CGPoint(x: position.x + translation.x, y: position.y + translation.y),
                    in: container
                )
                frame.origin = self.position!
            }
        case .ended, .cancelled:
            snapToNearestEdge(in: container)
        default:
            break
        }
    }

    private func snapToNearestEdge(in container: UIView) {
        guard let current = position else { return }
        let clamped = clampPosition(current, in: container)
        let centerX = clamped.x + askAiFloatSize / 2
        let targetX: CGFloat
        if centerX <= container.bounds.width / 2 {
            targetX = askAiEdgeMargin
        } else {
            targetX = clampedMaxRight(in: container)
        }
        isSnapAnimating = true
        UIView.animate(
            withDuration: askAiSnapDuration,
            delay: 0,
            options: [.curveEaseInOut, .allowUserInteraction]
        ) {
            self.frame.origin.x = targetX
        } completion: { [weak self] _ in
            guard let self else { return }
            self.isSnapAnimating = false
            if let container = self.superview, let pos = self.position {
                self.position = self.clampPosition(
                    CGPoint(x: self.frame.origin.x, y: pos.y), in: container
                )
            }
        }
        self.position = CGPoint(x: targetX, y: clamped.y)
    }

    private func cancelSnapAnimation() {
        isSnapAnimating = false
        // 已排队/在途的 snap 动画随新帧写入自然失效；位置以当前 frame 为准
        if let container = superview, let current = position {
            position = clampPosition(CGPoint(x: frame.origin.x, y: current.y), in: container)
        }
    }

    // MARK: 点击 / 长按编辑态

    @objc private func handleTap(_ gesture: UITapGestureRecognizer) {
        if isEditingMode {
            exitEditingMode()
        } else {
            onTap?()
        }
    }

    @objc private func handleLongPress(_ gesture: UILongPressGestureRecognizer) {
        guard gesture.state == .began, !isEditingMode else { return }
        enterEditingMode()
    }

    private func enterEditingMode() {
        guard let container = superview, !isEditingMode else { return }
        isEditingMode = true

        // 编辑态遮罩：点悬浮球以外任何区域退出编辑态（消费点击，不透传给页面）。
        let mask = UIControl()
        mask.translatesAutoresizingMaskIntoConstraints = false
        mask.backgroundColor = .clear
        mask.addTarget(self, action: #selector(editMaskTapped), for: .touchUpInside)
        container.addSubview(mask)
        container.bringSubviewToFront(mask)
        container.insertSubview(self, aboveSubview: mask)
        NSLayoutConstraint.activate([
            mask.topAnchor.constraint(equalTo: container.topAnchor),
            mask.bottomAnchor.constraint(equalTo: container.bottomAnchor),
            mask.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            mask.trailingAnchor.constraint(equalTo: container.trailingAnchor),
        ])
        editMaskView = mask

        // 右上 ✕ 角标：20pt 圆、#E5484D 底白叉（对齐安卓压角位置）。
        let closeBadge = AskAiCloseBadgeView(frame: .zero)
        closeBadge.frame = CGRect(
            x: askAiFloatSize - askAiCloseBadgeSize + askAiCloseBadgeOutset,
            y: -askAiCloseBadgeOutset / 2,
            width: askAiCloseBadgeSize,
            height: askAiCloseBadgeSize
        )
        closeBadge.addGestureRecognizer(UITapGestureRecognizer(
            target: self, action: #selector(closeBadgeTapped)
        ))
        addSubview(closeBadge)
        closeBadgeView = closeBadge

        // 放大 1.1 + rotate ±3° 往返抖动（对齐安卓 graphicsLayer + infiniteTransition）。
        UIView.animate(withDuration: 0.2) {
            self.transform = CGAffineTransform(scaleX: askAiEditScale, y: askAiEditScale)
        }
        let wobble = CABasicAnimation(keyPath: "transform.rotation.z")
        wobble.fromValue = -askAiEditWobbleDegrees * .pi / 180
        wobble.toValue = askAiEditWobbleDegrees * .pi / 180
        wobble.duration = 0.26
        wobble.autoreverses = true
        wobble.repeatCount = .infinity
        badgeView.layer.add(wobble, forKey: "askAiWobble")
    }

    private func exitEditingMode() {
        guard isEditingMode else { return }
        isEditingMode = false
        editMaskView?.removeFromSuperview()
        editMaskView = nil
        closeBadgeView?.removeFromSuperview()
        closeBadgeView = nil
        badgeView.layer.removeAnimation(forKey: "askAiWobble")
        UIView.animate(withDuration: 0.2) {
            self.transform = .identity
        }
    }

    @objc private func editMaskTapped() {
        exitEditingMode()
    }

    @objc private func closeBadgeTapped() {
        exitEditingMode()
        onClose?()
    }

    /// 从父视图摘除前清干净编辑态（遮罩/角标/动画），供容器销毁时调用。
    func prepareForRemoval() {
        exitEditingMode()
        removeFromSuperview()
    }
}

// MARK: - 徽章 / 角标 / 菜单缩微图标绘制

/// 悬浮球圆形双星徽章（42pt，定稿配色）。
private final class AskAiEmblemBadgeView: UIView {
    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isUserInteractionEnabled = false
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func draw(_ rect: CGRect) {
        drawAskAiEmblem(
            in: rect,
            bigStarColor: askAiEmblemBigStar,
            smallStarColor: askAiEmblemSmallStar
        )
    }
}

/// 编辑态右上 ✕ 角标（20pt 圆 #E5484D 底白叉）。
private final class AskAiCloseBadgeView: UIView {
    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isUserInteractionEnabled = true
        clipsToBounds = false
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func draw(_ rect: CGRect) {
        guard let context = UIGraphicsGetCurrentContext() else { return }
        let side = min(rect.width, rect.height)
        let circleRect = CGRect(
            x: rect.minX + (rect.width - side) / 2,
            y: rect.minY + (rect.height - side) / 2,
            width: side, height: side
        )
        context.setFillColor(UIColor(red: 0xE5 / 255.0, green: 0x48 / 255.0, blue: 0x4D / 255.0, alpha: 1).cgColor)
        context.fillEllipse(in: circleRect)

        // 白叉：10pt 线长、1.6pt 圆头（对齐安卓 Canvas 10dp 画叉）
        let center = CGPoint(x: circleRect.midX, y: circleRect.midY)
        let half: CGFloat = 5
        let stroke = UIColor.white
        stroke.setStroke()
        let cross = UIBezierPath()
        cross.move(to: CGPoint(x: center.x - half, y: center.y - half))
        cross.addLine(to: CGPoint(x: center.x + half, y: center.y + half))
        cross.move(to: CGPoint(x: center.x + half, y: center.y - half))
        cross.addLine(to: CGPoint(x: center.x - half, y: center.y + half))
        cross.lineCapStyle = .round
        cross.lineWidth = 1.6
        stroke.setStroke()
        cross.stroke()
    }
}

/// ⋯菜单「问AI浮窗」瓦片缩微图标（对齐安卓 AskAiFloatMiniature，30pt）：
/// 外圈细 hairline 描边（深色 tile 白 @0.45 / 浅色 tile #333 @0.30）+
/// 内圆底 + 双星；置灰态整图标 alpha≈0.45 且双星统一 #8A93A6。
final class AskAiFloatMiniatureView: UIView {
    private var dimmed: Bool
    private var isDark: Bool

    init(dimmed: Bool, isDark: Bool) {
        self.dimmed = dimmed
        self.isDark = isDark
        super.init(frame: CGRect(x: 0, y: 0, width: 30, height: 30))
        backgroundColor = .clear
        isUserInteractionEnabled = false
        alpha = dimmed ? 0.45 : 1
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func update(dimmed: Bool, isDark: Bool) {
        self.dimmed = dimmed
        self.isDark = isDark
        alpha = dimmed ? 0.45 : 1
        setNeedsDisplay()
    }

    override func draw(_ rect: CGRect) {
        guard let context = UIGraphicsGetCurrentContext() else { return }
        let side = min(rect.width, rect.height)
        let square = CGRect(
            x: rect.minX + (rect.width - side) / 2,
            y: rect.minY + (rect.height - side) / 2,
            width: side, height: side
        )

        // 外圈细描边：深浅两套（对齐安卓 AskAiFloatMiniature 的 borderColor 派生）
        let borderColor = isDark
            ? UIColor.white.withAlphaComponent(0.45)
            : UIColor(red: 0x33 / 255.0, green: 0x33 / 255.0, blue: 0x33 / 255.0, alpha: 1).withAlphaComponent(0.3)
        context.setStrokeColor(borderColor.cgColor)
        context.setLineWidth(1)
        context.strokeEllipse(in: square.insetBy(dx: 0.5, dy: 0.5))

        let bigStar = dimmed ? askAiEmblemDimStar : askAiEmblemBigStar
        let smallStar = dimmed ? askAiEmblemDimStar : askAiEmblemSmallStar
        drawAskAiEmblem(in: square, bigStarColor: bigStar, smallStarColor: smallStar, withRing: false)
    }
}
