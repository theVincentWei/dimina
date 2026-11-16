import Darwin
import Foundation
import Network

private let localNetworkArrayBufferKey = "__diminaArrayBufferBase64"

/** WeChat-compatible mDNS, UDP socket, and TCP client API bridge. */
public final class LocalNetworkAPI: DMPContainerApi {
    private static func bridge(
        _ name: String,
        _ param: DMPBridgeParam,
        _ env: DMPBridgeEnv,
        _ callback: DMPBridgeCallback?
    ) -> DMPAPIResult {
        if name == "UDPSocket.bind" {
            return LocalNetworkAPIManager.shared.bindUDP(data: param.getMap(), appId: env.appId)
        }
        LocalNetworkAPIManager.shared.handle(name: name, data: param.getMap(), appId: env.appId, callback: callback)
        return DMPAsyncResult()
    }

    @BridgeMethod("startLocalServiceDiscovery") var startLocalServiceDiscovery: DMPBridgeMethodHandler = { bridge("startLocalServiceDiscovery", $0, $1, $2) }
    @BridgeMethod("stopLocalServiceDiscovery") var stopLocalServiceDiscovery: DMPBridgeMethodHandler = { bridge("stopLocalServiceDiscovery", $0, $1, $2) }
    @BridgeMethod("onLocalServiceDiscoveryStop") var onLocalServiceDiscoveryStop: DMPBridgeMethodHandler = { bridge("onLocalServiceDiscoveryStop", $0, $1, $2) }
    @BridgeMethod("offLocalServiceDiscoveryStop") var offLocalServiceDiscoveryStop: DMPBridgeMethodHandler = { bridge("offLocalServiceDiscoveryStop", $0, $1, $2) }
    @BridgeMethod("onLocalServiceFound") var onLocalServiceFound: DMPBridgeMethodHandler = { bridge("onLocalServiceFound", $0, $1, $2) }
    @BridgeMethod("offLocalServiceFound") var offLocalServiceFound: DMPBridgeMethodHandler = { bridge("offLocalServiceFound", $0, $1, $2) }
    @BridgeMethod("onLocalServiceLost") var onLocalServiceLost: DMPBridgeMethodHandler = { bridge("onLocalServiceLost", $0, $1, $2) }
    @BridgeMethod("offLocalServiceLost") var offLocalServiceLost: DMPBridgeMethodHandler = { bridge("offLocalServiceLost", $0, $1, $2) }
    @BridgeMethod("onLocalServiceResolveFail") var onLocalServiceResolveFail: DMPBridgeMethodHandler = { bridge("onLocalServiceResolveFail", $0, $1, $2) }
    @BridgeMethod("offLocalServiceResolveFail") var offLocalServiceResolveFail: DMPBridgeMethodHandler = { bridge("offLocalServiceResolveFail", $0, $1, $2) }

    @BridgeMethod("UDPSocket.bind") var udpBind: DMPBridgeMethodHandler = { bridge("UDPSocket.bind", $0, $1, $2) }
    @BridgeMethod("UDPSocket.close") var udpClose: DMPBridgeMethodHandler = { bridge("UDPSocket.close", $0, $1, $2) }
    @BridgeMethod("UDPSocket.connect") var udpConnect: DMPBridgeMethodHandler = { bridge("UDPSocket.connect", $0, $1, $2) }
    @BridgeMethod("UDPSocket.send") var udpSend: DMPBridgeMethodHandler = { bridge("UDPSocket.send", $0, $1, $2) }
    @BridgeMethod("UDPSocket.write") var udpWrite: DMPBridgeMethodHandler = { bridge("UDPSocket.write", $0, $1, $2) }
    @BridgeMethod("UDPSocket.setTTL") var udpSetTTL: DMPBridgeMethodHandler = { bridge("UDPSocket.setTTL", $0, $1, $2) }
    @BridgeMethod("UDPSocket.onClose") var udpOnClose: DMPBridgeMethodHandler = { bridge("UDPSocket.onClose", $0, $1, $2) }
    @BridgeMethod("UDPSocket.offClose") var udpOffClose: DMPBridgeMethodHandler = { bridge("UDPSocket.offClose", $0, $1, $2) }
    @BridgeMethod("UDPSocket.onError") var udpOnError: DMPBridgeMethodHandler = { bridge("UDPSocket.onError", $0, $1, $2) }
    @BridgeMethod("UDPSocket.offError") var udpOffError: DMPBridgeMethodHandler = { bridge("UDPSocket.offError", $0, $1, $2) }
    @BridgeMethod("UDPSocket.onListening") var udpOnListening: DMPBridgeMethodHandler = { bridge("UDPSocket.onListening", $0, $1, $2) }
    @BridgeMethod("UDPSocket.offListening") var udpOffListening: DMPBridgeMethodHandler = { bridge("UDPSocket.offListening", $0, $1, $2) }
    @BridgeMethod("UDPSocket.onMessage") var udpOnMessage: DMPBridgeMethodHandler = { bridge("UDPSocket.onMessage", $0, $1, $2) }
    @BridgeMethod("UDPSocket.offMessage") var udpOffMessage: DMPBridgeMethodHandler = { bridge("UDPSocket.offMessage", $0, $1, $2) }

    @BridgeMethod("TCPSocket.bindWifi") var tcpBindWifi: DMPBridgeMethodHandler = { bridge("TCPSocket.bindWifi", $0, $1, $2) }
    @BridgeMethod("TCPSocket.onBindWifi") var tcpOnBindWifi: DMPBridgeMethodHandler = { bridge("TCPSocket.onBindWifi", $0, $1, $2) }
    @BridgeMethod("TCPSocket.offBindWifi") var tcpOffBindWifi: DMPBridgeMethodHandler = { bridge("TCPSocket.offBindWifi", $0, $1, $2) }
    @BridgeMethod("TCPSocket.close") var tcpClose: DMPBridgeMethodHandler = { bridge("TCPSocket.close", $0, $1, $2) }
    @BridgeMethod("TCPSocket.connect") var tcpConnect: DMPBridgeMethodHandler = { bridge("TCPSocket.connect", $0, $1, $2) }
    @BridgeMethod("TCPSocket.write") var tcpWrite: DMPBridgeMethodHandler = { bridge("TCPSocket.write", $0, $1, $2) }
    @BridgeMethod("TCPSocket.onClose") var tcpOnClose: DMPBridgeMethodHandler = { bridge("TCPSocket.onClose", $0, $1, $2) }
    @BridgeMethod("TCPSocket.offClose") var tcpOffClose: DMPBridgeMethodHandler = { bridge("TCPSocket.offClose", $0, $1, $2) }
    @BridgeMethod("TCPSocket.onConnect") var tcpOnConnect: DMPBridgeMethodHandler = { bridge("TCPSocket.onConnect", $0, $1, $2) }
    @BridgeMethod("TCPSocket.offConnect") var tcpOffConnect: DMPBridgeMethodHandler = { bridge("TCPSocket.offConnect", $0, $1, $2) }
    @BridgeMethod("TCPSocket.onError") var tcpOnError: DMPBridgeMethodHandler = { bridge("TCPSocket.onError", $0, $1, $2) }
    @BridgeMethod("TCPSocket.offError") var tcpOffError: DMPBridgeMethodHandler = { bridge("TCPSocket.offError", $0, $1, $2) }
    @BridgeMethod("TCPSocket.onMessage") var tcpOnMessage: DMPBridgeMethodHandler = { bridge("TCPSocket.onMessage", $0, $1, $2) }
    @BridgeMethod("TCPSocket.offMessage") var tcpOffMessage: DMPBridgeMethodHandler = { bridge("TCPSocket.offMessage", $0, $1, $2) }
}

private final class IOSUDPSocketState {
    let appId: String
    let socketId: String
    let fd: Int32
    private let lock = NSLock()
    private var didClose = false
    var receiving = false

    init(appId: String, socketId: String) throws {
        self.appId = appId
        self.socketId = socketId
        fd = Darwin.socket(AF_INET6, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else { throw LocalNetworkError.posix("socket") }
        var enabled: Int32 = 1
        var disabled: Int32 = 0
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &enabled, socklen_t(MemoryLayout.size(ofValue: enabled)))
        setsockopt(fd, IPPROTO_IPV6, IPV6_V6ONLY, &disabled, socklen_t(MemoryLayout.size(ofValue: disabled)))
    }

    var closed: Bool {
        lock.lock(); defer { lock.unlock() }
        return didClose
    }

    @discardableResult func close() -> Bool {
        lock.lock(); defer { lock.unlock() }
        if didClose { return false }
        didClose = true
        Darwin.shutdown(fd, SHUT_RDWR)
        Darwin.close(fd)
        return true
    }
}

private final class IOSTCPSocketState {
    let appId: String
    let socketId: String
    private let lock = NSLock()
    var fd: Int32 = -1
    var receiving = false
    private var didClose = false

    init(appId: String, socketId: String) {
        self.appId = appId
        self.socketId = socketId
    }

    var closed: Bool {
        lock.lock(); defer { lock.unlock() }
        return didClose
    }

    func install(fd: Int32) throws {
        lock.lock(); defer { lock.unlock() }
        if didClose {
            Darwin.close(fd)
            throw LocalNetworkError.message("socket is closed")
        }
        self.fd = fd
    }

    @discardableResult func close() -> Bool {
        lock.lock(); defer { lock.unlock() }
        if didClose { return false }
        didClose = true
        if fd >= 0 {
            Darwin.shutdown(fd, SHUT_RDWR)
            Darwin.close(fd)
            fd = -1
        }
        return true
    }

    /// bindWifi 生效后记录 WiFi 接口索引，并给已建立的连接补绑（后续收发包走 WiFi，
    /// 对齐安卓 `network.bindSocket(socket)` 对既有 socket 生效的语义）。
    func applyWifiBinding(index: UInt32) {
        lock.lock(); defer { lock.unlock() }
        wifiInterfaceIndex = index
        guard fd >= 0, !didClose else { return }
        bindSocketToInterface(fd: fd, index: index)
    }

    /// WiFi 路径丢失后清掉记录。不主动解绑既有 fd（IP_BOUND_IF 无公开解绑口），
    /// 连接本身会因路由失效报错断开并由 onError/onClose 通知。
    func clearWifiBinding() {
        lock.lock(); defer { lock.unlock() }
        wifiInterfaceIndex = 0
    }

    private var wifiInterfaceIndex: UInt32 = 0
}

/// 把 socket 绑定到指定网络接口索引：IPv4 走 IP_BOUND_IF，IPv6（含 v4 映射）走
/// IPV6_BOUND_IF，两个都设互不影响。这是 iOS 上对应安卓 `network.bindSocket` 的能力。
private func bindSocketToInterface(fd: Int32, index: UInt32) {
    var index = index
    _ = setsockopt(fd, IPPROTO_IP, IP_BOUND_IF, &index, socklen_t(MemoryLayout<UInt32>.size))
    _ = setsockopt(fd, IPPROTO_IPV6, IPV6_BOUND_IF, &index, socklen_t(MemoryLayout<UInt32>.size))
}

/// TCPSocket.bindWifi 的每个 socket 一份订阅（学 NetworkTypeAPI 的订阅表模式）：
/// NWPathMonitor 以 `requiredInterfaceType = .wifi` 约束路径，「连接的当前路径是否为
/// WiFi」变化时由 manager 回点火 TCPSocket.onBindWifi / TCPSocket.onError；
/// 首个 path 回调必报（没 WiFi 立刻回 onError，对齐安卓 bindWifi 调用即出结果）。
private final class TCPWifiBindState {
    let appId: String
    let socketId: String
    let monitor: NWPathMonitor
    let queue: DispatchQueue
    private let lock = NSLock()
    private let pathConfirmed = DispatchSemaphore(value: 0)
    private var bound = false
    private var reportedOnce = false
    private var announced = false
    private var interfaceIndex: UInt32 = 0

    init(appId: String, socketId: String) {
        self.appId = appId
        self.socketId = socketId
        queue = DispatchQueue(label: "com.didi.dimina.tcp-wifi.\(appId)")
        let parameters = NWParameters()
        parameters.requiredInterfaceType = .wifi
        // NWPathMonitor 不收 NWParameters:用 requiredInterfaceTypes 专盯 WiFi 路径。
        let _ = parameters
        monitor = NWPathMonitor(requiredInterfaceTypes: [.wifi])
    }

    func start(onUpdate: @escaping (NWPath) -> Void) {
        monitor.pathUpdateHandler = onUpdate
        monitor.start(queue: queue)
    }

    /// 摘订阅（socket 关闭 / clearApp / 重复 bindWifi 换绑）。
    func stop() {
        monitor.cancel()
        announcePath()
    }

    /// 决定是否回点火：首个 path 回调必报（对齐安卓「bindWifi 调用即出结果」，没 WiFi
    /// 立刻回 onError），之后仅在「是否 WiFi」跃迁时报。返回 nil 表示不点火。
    func reportTransition(satisfied: Bool) -> Bool? {
        lock.lock(); defer { lock.unlock() }
        let isFirst = !reportedOnce
        let changed = bound != satisfied
        bound = satisfied
        reportedOnce = true
        if isFirst || changed { return satisfied }
        return nil
    }

    func setInterfaceIndex(_ index: UInt32) {
        lock.lock(); defer { lock.unlock() }
        interfaceIndex = index
    }

    func currentInterfaceIndex() -> UInt32 {
        lock.lock(); defer { lock.unlock() }
        return interfaceIndex
    }

    /// 首个 path 回调到达时置位并唤醒可能在 connect 前等待确认的一方。
    func announcePath() {
        lock.lock()
        let first = !announced
        announced = true
        lock.unlock()
        if first { pathConfirmed.signal() }
    }

    /// connect 前取 WiFi 接口索引：path 已确认直接返回；否则等首个 path 回调握手
    /// （信号量 + 有界超时，非盲等）。超时/未绑定返回 0，连接按系统默认路由走。
    func waitForInterfaceIndex(timeout: Double) -> UInt32 {
        lock.lock()
        let announcedNow = announced
        lock.unlock()
        if !announcedNow {
            _ = pathConfirmed.wait(timeout: .now() + timeout)
        }
        return currentInterfaceIndex()
    }
}

private enum LocalNetworkError: Error {
    case message(String)
    case posix(String)

    var text: String {
        switch self {
        case .message(let value): return value
        case .posix(let operation): return "\(operation): \(String(cString: strerror(errno)))"
        }
    }
}

private struct ResolvedSocketAddress {
    var storage: sockaddr_storage
    let length: socklen_t
    let family: Int32
}

final class LocalNetworkAPIManager {
    static let shared = LocalNetworkAPIManager()

    private let stateQueue = DispatchQueue(label: "com.didi.dimina.local-network.state")
    private let ioQueue = DispatchQueue(label: "com.didi.dimina.local-network.io", qos: .utility, attributes: .concurrent)
    private var udpSockets: [String: IOSUDPSocketState] = [:]
    private var tcpSockets: [String: IOSTCPSocketState] = [:]
    private var wifiBinds: [String: TCPWifiBindState] = [:]
    private var listeners: [String: DMPBridgeCallback] = [:]
    private var discoveries: [String: LocalServiceDiscoveryState] = [:]

    func handle(name: String, data: DMPMap, appId: String, callback: DMPBridgeCallback?) {
        switch name {
        case "startLocalServiceDiscovery": startDiscovery(data: data, appId: appId, callback: callback)
        case "stopLocalServiceDiscovery": stopDiscovery(appId: appId, callback: callback)
        case "onLocalServiceDiscoveryStop", "onLocalServiceFound", "onLocalServiceLost", "onLocalServiceResolveFail",
             "UDPSocket.onClose", "UDPSocket.onError", "UDPSocket.onListening", "UDPSocket.onMessage",
             "TCPSocket.onBindWifi", "TCPSocket.onClose", "TCPSocket.onConnect", "TCPSocket.onError", "TCPSocket.onMessage":
            addListener(event: name, data: data, appId: appId, callback: callback)
        case "offLocalServiceDiscoveryStop", "offLocalServiceFound", "offLocalServiceLost", "offLocalServiceResolveFail",
             "UDPSocket.offClose", "UDPSocket.offError", "UDPSocket.offListening", "UDPSocket.offMessage",
             "TCPSocket.offBindWifi", "TCPSocket.offClose", "TCPSocket.offConnect", "TCPSocket.offError", "TCPSocket.offMessage":
            removeListener(offEvent: name, data: data, appId: appId)
        case "UDPSocket.close": closeUDP(data: data, appId: appId)
        case "UDPSocket.connect": connectUDP(data: data, appId: appId)
        case "UDPSocket.send", "UDPSocket.write": sendUDP(data: data, appId: appId)
        case "UDPSocket.setTTL": setUDPTTL(data: data, appId: appId)
        case "TCPSocket.bindWifi": bindTcpWifi(data: data, appId: appId)
        case "TCPSocket.close": closeTCP(data: data, appId: appId)
        case "TCPSocket.connect": connectTCP(data: data, appId: appId)
        case "TCPSocket.write": writeTCP(data: data, appId: appId)
        default: break
        }
    }

    func bindUDP(data: DMPMap, appId: String) -> DMPAPIResult {
        do {
            let socketId = try required(data, "socketId")
            let port = data.getInt(key: "port") ?? 0
            guard (0...65535).contains(port) else { throw LocalNetworkError.message("invalid port") }
            let state = try udpState(appId: appId, socketId: socketId)

            var current = sockaddr_storage()
            var currentLength = socklen_t(MemoryLayout<sockaddr_storage>.size)
            if getsockname(state.fd, withUnsafeMutablePointer(to: &current) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { $0 }
            }, &currentLength) == 0, socketPort(current) != 0 {
                return DMPSyncResult(socketPort(current))
            }

            var address = sockaddr_in6()
            address.sin6_len = UInt8(MemoryLayout<sockaddr_in6>.size)
            address.sin6_family = sa_family_t(AF_INET6)
            address.sin6_port = in_port_t(port).bigEndian
            address.sin6_addr = in6addr_any
            let result = withUnsafePointer(to: &address) { pointer in
                pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    Darwin.bind(state.fd, $0, socklen_t(MemoryLayout<sockaddr_in6>.size))
                }
            }
            guard result == 0 else { throw LocalNetworkError.posix("bind") }

            startUDPReceive(state)
            emit(event: "UDPSocket.onListening", appId: appId, socketId: socketId, value: [:])
            var bound = sockaddr_storage()
            var boundLength = socklen_t(MemoryLayout<sockaddr_storage>.size)
            guard getsockname(state.fd, withUnsafeMutablePointer(to: &bound) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { $0 }
            }, &boundLength) == 0 else { throw LocalNetworkError.posix("getsockname") }
            return DMPSyncResult(socketPort(bound))
        } catch {
            return DMPSyncResult(["error": "UDPSocket.bind:fail \(errorText(error))"])
        }
    }

    private func connectUDP(data: DMPMap, appId: String) {
        do {
            let socketId = try required(data, "socketId")
            let host = try required(data, "address")
            let port = try requiredPort(data)
            let state = try udpState(appId: appId, socketId: socketId)
            try ensureUDPBound(state)
            ioQueue.async { [weak self] in
                do {
                    guard let self else { return }
                    var address = try self.resolve(host: host, port: port, socketType: SOCK_DGRAM, family: AF_INET6, flags: AI_V4MAPPED)
                    let addressLength = address.length
                    let result = self.withSocketAddress(&address) { Darwin.connect(state.fd, $0, addressLength) }
                    if result != 0 { throw LocalNetworkError.posix("connect") }
                } catch {
                    self?.emitError(event: "UDPSocket.onError", appId: appId, socketId: socketId, error: error)
                }
            }
        } catch {
            emitError(event: "UDPSocket.onError", appId: appId, socketId: data.getString(key: "socketId") ?? "", error: error)
        }
    }

    private func sendUDP(data: DMPMap, appId: String) {
        do {
            let socketId = try required(data, "socketId")
            let host = try required(data, "address")
            let port = try requiredPort(data)
            let state = try udpState(appId: appId, socketId: socketId)
            let messageValue = data.get("message")
            let message = try messageData(messageValue)
            let isArrayBuffer = (messageValue as? [String: Any])?[localNetworkArrayBufferKey] is String
            let offset = isArrayBuffer ? max(data.getInt(key: "offset") ?? 0, 0) : 0
            let defaultLength = max(message.count - offset, 0)
            let length = isArrayBuffer ? min(data.getInt(key: "length") ?? defaultLength, defaultLength) : message.count
            guard offset <= message.count, length >= 0 else { throw LocalNetworkError.message("invalid offset or length") }
            try ensureUDPBound(state)
            if data.getBool(key: "setBroadcast") == true {
                var enabled: Int32 = 1
                setsockopt(state.fd, SOL_SOCKET, SO_BROADCAST, &enabled, socklen_t(MemoryLayout.size(ofValue: enabled)))
            }
            ioQueue.async { [weak self] in
                do {
                    guard let self else { return }
                    var address = try self.resolve(host: host, port: port, socketType: SOCK_DGRAM, family: AF_INET6, flags: AI_V4MAPPED)
                    let addressLength = address.length
                    let sent = message.withUnsafeBytes { bytes in
                        self.withSocketAddress(&address) {
                            Darwin.sendto(state.fd, bytes.baseAddress?.advanced(by: offset), length, 0, $0, addressLength)
                        }
                    }
                    if sent < 0 { throw LocalNetworkError.posix("sendto") }
                } catch {
                    self?.emitError(event: "UDPSocket.onError", appId: appId, socketId: socketId, error: error)
                }
            }
        } catch {
            emitError(event: "UDPSocket.onError", appId: appId, socketId: data.getString(key: "socketId") ?? "", error: error)
        }
    }

    private func setUDPTTL(data: DMPMap, appId: String) {
        do {
            let socketId = try required(data, "socketId")
            guard let ttl = data.getInt(key: "ttl"), (0...255).contains(ttl) else {
                throw LocalNetworkError.message("ttl must be between 0 and 255")
            }
            let state = try udpState(appId: appId, socketId: socketId)
            var value = Int32(ttl)
            let length = socklen_t(MemoryLayout.size(ofValue: value))
            let ipv6Result = setsockopt(state.fd, IPPROTO_IPV6, IPV6_UNICAST_HOPS, &value, length)
            let ipv4Result = setsockopt(state.fd, IPPROTO_IP, IP_TTL, &value, length)
            if ipv6Result != 0 && ipv4Result != 0 { throw LocalNetworkError.posix("setsockopt") }
        } catch {
            emitError(event: "UDPSocket.onError", appId: appId, socketId: data.getString(key: "socketId") ?? "", error: error)
        }
    }

    private func ensureUDPBound(_ state: IOSUDPSocketState) throws {
        var address = sockaddr_storage()
        var length = socklen_t(MemoryLayout<sockaddr_storage>.size)
        if getsockname(state.fd, withUnsafeMutablePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { $0 }
        }, &length) == 0, socketPort(address) != 0 { return }

        var bindAddress = sockaddr_in6()
        bindAddress.sin6_len = UInt8(MemoryLayout<sockaddr_in6>.size)
        bindAddress.sin6_family = sa_family_t(AF_INET6)
        bindAddress.sin6_addr = in6addr_any
        let result = withUnsafePointer(to: &bindAddress) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(state.fd, $0, socklen_t(MemoryLayout<sockaddr_in6>.size))
            }
        }
        guard result == 0 else { throw LocalNetworkError.posix("bind") }
        startUDPReceive(state)
        emit(event: "UDPSocket.onListening", appId: state.appId, socketId: state.socketId, value: [:])
    }

    private func startUDPReceive(_ state: IOSUDPSocketState) {
        if state.receiving { return }
        state.receiving = true
        ioQueue.async { [weak self] in
            var buffer = [UInt8](repeating: 0, count: 65_507)
            while !state.closed {
                var remote = sockaddr_storage()
                var remoteLength = socklen_t(MemoryLayout<sockaddr_storage>.size)
                let count = buffer.withUnsafeMutableBytes { bytes in
                    withUnsafeMutablePointer(to: &remote) {
                        $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                            Darwin.recvfrom(state.fd, bytes.baseAddress, bytes.count, 0, $0, &remoteLength)
                        }
                    }
                }
                if count < 0 {
                    if !state.closed { self?.emitError(event: "UDPSocket.onError", appId: state.appId, socketId: state.socketId, error: LocalNetworkError.posix("recvfrom")) }
                    break
                }
                var local = sockaddr_storage()
                var localLength = socklen_t(MemoryLayout<sockaddr_storage>.size)
                _ = getsockname(state.fd, withUnsafeMutablePointer(to: &local) {
                    $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { $0 }
                }, &localLength)
                let payload = Data(buffer.prefix(count))
                self?.emit(event: "UDPSocket.onMessage", appId: state.appId, socketId: state.socketId, value: [
                    "message": [localNetworkArrayBufferKey: payload.base64EncodedString()],
                    "remoteInfo": self?.addressInfo(remote, size: count) ?? [:],
                    "localInfo": self?.addressInfo(local, size: nil) ?? [:],
                ])
            }
        }
    }

    private func closeUDP(data: DMPMap, appId: String) {
        guard let socketId = data.getString(key: "socketId") else { return }
        let state = stateQueue.sync { udpSockets.removeValue(forKey: socketKey(appId, socketId)) }
        guard state?.close() == true else { return }
        emit(event: "UDPSocket.onClose", appId: appId, socketId: socketId, value: [:])
        clearSocketListeners(appId: appId, prefix: "UDPSocket", socketId: socketId)
    }

    private func connectTCP(data: DMPMap, appId: String) {
        do {
            let socketId = try required(data, "socketId")
            let host = try required(data, "address")
            let port = try requiredPort(data)
            let timeout = max(data.getDouble(key: "timeout") ?? 2, 0)
            let state = tcpState(appId: appId, socketId: socketId)
            ioQueue.async { [weak self] in
                do {
                    guard let self else { return }
                    var address = try self.resolve(host: host, port: port, socketType: SOCK_STREAM)
                    let fd = Darwin.socket(address.family, SOCK_STREAM, IPPROTO_TCP)
                    guard fd >= 0 else { throw LocalNetworkError.posix("socket") }
                    do {
                        try state.install(fd: fd)
                        // bindWifi 生效中：把 socket 绑到 WiFi 接口索引，保证连接走 WiFi
                        let wifiIndex = self.tcpWifiInterfaceIndex(appId: appId, socketId: socketId)
                        if wifiIndex != 0 { bindSocketToInterface(fd: fd, index: wifiIndex) }
                        try self.connectWithTimeout(fd: fd, address: &address, timeout: timeout)
                        self.emit(event: "TCPSocket.onConnect", appId: appId, socketId: socketId, value: [:])
                        self.startTCPReceive(state)
                    } catch {
                        if state.fd == fd { _ = state.close() } else { Darwin.close(fd) }
                        _ = self.stateQueue.sync { self.tcpSockets.removeValue(forKey: self.socketKey(appId, socketId)) }
                        throw error
                    }
                } catch {
                    self?.emitError(event: "TCPSocket.onError", appId: appId, socketId: socketId, error: error)
                }
            }
        } catch {
            emitError(event: "TCPSocket.onError", appId: appId, socketId: data.getString(key: "socketId") ?? "", error: error)
        }
    }

    private func connectWithTimeout(fd: Int32, address: inout ResolvedSocketAddress, timeout: Double) throws {
        let originalFlags = fcntl(fd, F_GETFL, 0)
        guard originalFlags >= 0, fcntl(fd, F_SETFL, originalFlags | O_NONBLOCK) == 0 else {
            throw LocalNetworkError.posix("fcntl")
        }
        let addressLength = address.length
        let result = withSocketAddress(&address) { Darwin.connect(fd, $0, addressLength) }
        if result != 0 && errno != EINPROGRESS { throw LocalNetworkError.posix("connect") }
        if result != 0 {
            var descriptor = pollfd(fd: fd, events: Int16(POLLOUT), revents: 0)
            let timeoutMilliseconds = Int32(min(timeout * 1_000, Double(Int32.max)))
            let selected = Darwin.poll(&descriptor, 1, timeoutMilliseconds)
            guard selected > 0 else {
                if selected == 0 { throw LocalNetworkError.message("connect timeout") }
                throw LocalNetworkError.posix("select")
            }
            var socketError: Int32 = 0
            var length = socklen_t(MemoryLayout.size(ofValue: socketError))
            guard getsockopt(fd, SOL_SOCKET, SO_ERROR, &socketError, &length) == 0, socketError == 0 else {
                errno = socketError
                throw LocalNetworkError.posix("connect")
            }
        }
        guard fcntl(fd, F_SETFL, originalFlags) == 0 else { throw LocalNetworkError.posix("fcntl") }
    }

    private func startTCPReceive(_ state: IOSTCPSocketState) {
        if state.receiving { return }
        state.receiving = true
        var buffer = [UInt8](repeating: 0, count: 16 * 1024)
        while !state.closed {
            let count = buffer.withUnsafeMutableBytes { Darwin.recv(state.fd, $0.baseAddress, $0.count, 0) }
            if count == 0 {
                closeTCPState(state)
                return
            }
            if count < 0 {
                if !state.closed {
                    emitError(event: "TCPSocket.onError", appId: state.appId, socketId: state.socketId, error: LocalNetworkError.posix("recv"))
                    closeTCPState(state)
                }
                return
            }
            var remote = sockaddr_storage()
            var remoteLength = socklen_t(MemoryLayout<sockaddr_storage>.size)
            _ = getpeername(state.fd, withUnsafeMutablePointer(to: &remote) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { $0 }
            }, &remoteLength)
            var local = sockaddr_storage()
            var localLength = socklen_t(MemoryLayout<sockaddr_storage>.size)
            _ = getsockname(state.fd, withUnsafeMutablePointer(to: &local) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { $0 }
            }, &localLength)
            let payload = Data(buffer.prefix(count))
            emit(event: "TCPSocket.onMessage", appId: state.appId, socketId: state.socketId, value: [
                "message": [localNetworkArrayBufferKey: payload.base64EncodedString()],
                "remoteInfo": addressInfo(remote, size: nil),
                "localInfo": addressInfo(local, size: nil),
            ])
        }
    }

    private func writeTCP(data: DMPMap, appId: String) {
        do {
            let socketId = try required(data, "socketId")
            let state = tcpState(appId: appId, socketId: socketId)
            let message = try messageData(data.get("data"))
            ioQueue.async { [weak self] in
                do {
                    var total = 0
                    while total < message.count {
                        let count = message.withUnsafeBytes {
                            Darwin.send(state.fd, $0.baseAddress?.advanced(by: total), message.count - total, 0)
                        }
                        if count <= 0 { throw LocalNetworkError.posix("send") }
                        total += count
                    }
                } catch {
                    self?.emitError(event: "TCPSocket.onError", appId: appId, socketId: socketId, error: error)
                }
            }
        } catch {
            emitError(event: "TCPSocket.onError", appId: appId, socketId: data.getString(key: "socketId") ?? "", error: error)
        }
    }

    private func closeTCP(data: DMPMap, appId: String) {
        guard let socketId = data.getString(key: "socketId") else { return }
        let state = stateQueue.sync { tcpSockets.removeValue(forKey: socketKey(appId, socketId)) }
        if let state { closeTCPState(state) }
    }

    /// 安卓契约：参数 socketId + BSSID（必填），结果走持久事件——成功回点火
    /// TCPSocket.onBindWifi（空对象），失败回点火 TCPSocket.onError（{errMsg}）。
    /// iOS 用 NWPathMonitor(requiredInterfaceType = .wifi) 复刻：WiFi 路径就绪视为绑定
    /// 成功，「当前路径是否为 WiFi」跃迁时重复回点火；无法做安卓那样的 AP（BSSID）级
    /// 匹配——iOS 公开 API 拿不到当前 AP 的 BSSID，差异见交付说明。
    private func bindTcpWifi(data: DMPMap, appId: String) {
        do {
            let socketId = try required(data, "socketId")
            // 契约对齐安卓：BSSID 必填校验；iOS 不校验具体 AP（拿不到），仅要求非空。
            _ = try required(data, "BSSID")
            let state = tcpState(appId: appId, socketId: socketId)
            let bind = TCPWifiBindState(appId: appId, socketId: socketId)
            let previous = stateQueue.sync { () -> TCPWifiBindState? in
                let key = socketKey(appId, socketId)
                let old = wifiBinds[key]
                wifiBinds[key] = bind
                return old
            }
            previous?.stop()
            bind.start { [weak self, weak bind] path in
                guard let self, let bind else { return }
                self.handleWifiPath(bind: bind, state: state, path: path)
            }
        } catch {
            emitError(event: "TCPSocket.onError", appId: appId, socketId: data.getString(key: "socketId") ?? "", error: error)
        }
    }

    private func handleWifiPath(bind: TCPWifiBindState, state: IOSTCPSocketState, path: NWPath) {
        let satisfied = path.status == .satisfied && path.usesInterfaceType(.wifi)
        if satisfied, let interface = path.availableInterfaces.first(where: { $0.type == .wifi }) {
            bind.setInterfaceIndex(UInt32(interface.index))
        }
        bind.announcePath()
        guard let reportedSatisfied = bind.reportTransition(satisfied: satisfied) else { return }
        if reportedSatisfied {
            // 已建立的连接补绑到 WiFi 接口；之后的 connect 也会取该索引绑接口
            let index = bind.currentInterfaceIndex()
            if index != 0 { state.applyWifiBinding(index: index) }
            emit(event: "TCPSocket.onBindWifi", appId: bind.appId, socketId: bind.socketId, value: [:])
        } else {
            state.clearWifiBinding()
            // 与安卓失败文案一致：找不到满足约束的 WiFi 网络时回 onError
            emitError(event: "TCPSocket.onError", appId: bind.appId, socketId: bind.socketId, message: "wifi network not found")
        }
    }

    /// connect 前取 bindWifi 生效中的 WiFi 接口索引（0 = 未绑定，按默认路由连接）。
    private func tcpWifiInterfaceIndex(appId: String, socketId: String) -> UInt32 {
        guard let bind = stateQueue.sync(execute: { wifiBinds[socketKey(appId, socketId)] }) else { return 0 }
        return bind.waitForInterfaceIndex(timeout: 0.3)
    }

    private func closeTCPState(_ state: IOSTCPSocketState) {
        guard state.close() else { return }
        let wifiBind = stateQueue.sync { () -> TCPWifiBindState? in
            let key = socketKey(state.appId, state.socketId)
            tcpSockets.removeValue(forKey: key)
            return wifiBinds.removeValue(forKey: key)
        }
        wifiBind?.stop()
        emit(event: "TCPSocket.onClose", appId: state.appId, socketId: state.socketId, value: [:])
        clearSocketListeners(appId: state.appId, prefix: "TCPSocket", socketId: state.socketId)
    }

    private func startDiscovery(data: DMPMap, appId: String, callback: DMPBridgeCallback?) {
        guard let serviceType = data.getString(key: "serviceType")?.trimmingCharacters(in: .whitespacesAndNewlines), !serviceType.isEmpty else {
            fail("startLocalServiceDiscovery", callback, "invalid param")
            return
        }
        let created: Bool = stateQueue.sync {
            guard discoveries[appId] == nil else { return false }
            let state = LocalServiceDiscoveryState(appId: appId, serviceType: serviceType, manager: self, startCallback: callback)
            discoveries[appId] = state
            DispatchQueue.main.async { state.start() }
            return true
        }
        if !created { fail("startLocalServiceDiscovery", callback, "scan task already exist") }
    }

    private func stopDiscovery(appId: String, callback: DMPBridgeCallback?) {
        guard let state = stateQueue.sync(execute: { discoveries[appId] }) else {
            success("stopLocalServiceDiscovery", callback)
            return
        }
        state.stopCallback = callback
        DispatchQueue.main.async { state.stop() }
    }

    fileprivate func discoveryDidStop(_ state: LocalServiceDiscoveryState) {
        let wasCurrent = stateQueue.sync { () -> Bool in
            guard discoveries[state.appId] === state else { return false }
            discoveries.removeValue(forKey: state.appId)
            return true
        }
        guard wasCurrent else { return }
        emit(event: "onLocalServiceDiscoveryStop", appId: state.appId, socketId: nil, value: [:])
        success("stopLocalServiceDiscovery", state.stopCallback)
    }

    fileprivate func discoveryDidFail(_ state: LocalServiceDiscoveryState, error: [String: NSNumber]) {
        let wasCurrent = stateQueue.sync { () -> Bool in
            guard discoveries[state.appId] === state else { return false }
            discoveries.removeValue(forKey: state.appId)
            return true
        }
        if wasCurrent { fail("startLocalServiceDiscovery", state.startCallback, "error \(error)") }
    }

    fileprivate func discoveryFound(_ state: LocalServiceDiscoveryState, service: NetService, ip: String) {
        emit(event: "onLocalServiceFound", appId: state.appId, socketId: nil, value: [
            "serviceType": state.serviceType,
            "serviceName": service.name,
            "ip": ip,
            "port": service.port,
        ])
    }

    fileprivate func discoveryLost(_ state: LocalServiceDiscoveryState, service: NetService) {
        emit(event: "onLocalServiceLost", appId: state.appId, socketId: nil, value: serviceValue(state, service))
    }

    fileprivate func discoveryResolveFailed(_ state: LocalServiceDiscoveryState, service: NetService) {
        emit(event: "onLocalServiceResolveFail", appId: state.appId, socketId: nil, value: serviceValue(state, service))
    }

    private func serviceValue(_ state: LocalServiceDiscoveryState, _ service: NetService) -> [String: Any] {
        ["serviceType": state.serviceType, "serviceName": service.name]
    }

    fileprivate func success(_ name: String, _ callback: DMPBridgeCallback?) {
        callback?(DMPMap(["errMsg": "\(name):ok"]), .success)
        callback?(DMPMap(), .complete)
    }

    fileprivate func fail(_ name: String, _ callback: DMPBridgeCallback?, _ message: String) {
        callback?(DMPMap(["errMsg": "\(name):fail \(message)"]), .fail)
        callback?(DMPMap(), .complete)
    }

    private func addListener(event: String, data: DMPMap, appId: String, callback: DMPBridgeCallback?) {
        guard let callback else { return }
        let socketId = data.getString(key: "socketId") ?? ""
        stateQueue.sync { listeners[eventKey(appId, event, socketId)] = callback }
    }

    private func removeListener(offEvent: String, data: DMPMap, appId: String) {
        let event: String
        if offEvent.hasPrefix("off") { event = "on" + offEvent.dropFirst(3) }
        else { event = offEvent.replacingOccurrences(of: ".off", with: ".on") }
        let socketId = data.getString(key: "socketId") ?? ""
        _ = stateQueue.sync { listeners.removeValue(forKey: eventKey(appId, event, socketId)) }
    }

    fileprivate func emit(event: String, appId: String, socketId: String?, value: [String: Any]) {
        let callback = stateQueue.sync { listeners[eventKey(appId, event, socketId ?? "")] }
        callback?(DMPMap(value), .success)
    }

    private func emitError(event: String, appId: String, socketId: String, error: Error) {
        emitError(event: event, appId: appId, socketId: socketId, message: errorText(error))
    }

    private func emitError(event: String, appId: String, socketId: String, message: String) {
        emit(event: event, appId: appId, socketId: socketId, value: ["errMsg": message])
    }

    private func udpState(appId: String, socketId: String) throws -> IOSUDPSocketState {
        try stateQueue.sync {
            let key = socketKey(appId, socketId)
            if let state = udpSockets[key] { return state }
            let state = try IOSUDPSocketState(appId: appId, socketId: socketId)
            udpSockets[key] = state
            return state
        }
    }

    private func tcpState(appId: String, socketId: String) -> IOSTCPSocketState {
        stateQueue.sync {
            let key = socketKey(appId, socketId)
            if let state = tcpSockets[key] { return state }
            let state = IOSTCPSocketState(appId: appId, socketId: socketId)
            tcpSockets[key] = state
            return state
        }
    }

    private func resolve(
        host: String,
        port: Int,
        socketType: Int32,
        family: Int32 = AF_UNSPEC,
        flags: Int32 = 0
    ) throws -> ResolvedSocketAddress {
        var hints = addrinfo(
            ai_flags: flags,
            ai_family: family,
            ai_socktype: socketType,
            ai_protocol: 0,
            ai_addrlen: 0,
            ai_canonname: nil,
            ai_addr: nil,
            ai_next: nil
        )
        var result: UnsafeMutablePointer<addrinfo>?
        let code = getaddrinfo(host, String(port), &hints, &result)
        guard code == 0, let info = result else {
            throw LocalNetworkError.message(String(cString: gai_strerror(code)))
        }
        defer { freeaddrinfo(result) }
        var storage = sockaddr_storage()
        memcpy(&storage, info.pointee.ai_addr, Int(info.pointee.ai_addrlen))
        return ResolvedSocketAddress(storage: storage, length: info.pointee.ai_addrlen, family: info.pointee.ai_family)
    }

    private func withSocketAddress<T>(_ address: inout ResolvedSocketAddress, _ body: (UnsafePointer<sockaddr>) -> T) -> T {
        withUnsafePointer(to: &address.storage) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1, body)
        }
    }

    private func addressInfo(_ storage: sockaddr_storage, size: Int?) -> [String: Any] {
        var storage = storage
        var buffer = [CChar](repeating: 0, count: Int(INET6_ADDRSTRLEN))
        var port = 0
        var family = "IPv4"
        if Int32(storage.ss_family) == AF_INET6 {
            family = "IPv6"
            withUnsafePointer(to: &storage) {
                $0.withMemoryRebound(to: sockaddr_in6.self, capacity: 1) { pointer in
                    var address = pointer.pointee.sin6_addr
                    inet_ntop(AF_INET6, &address, &buffer, socklen_t(buffer.count))
                    port = Int(UInt16(bigEndian: pointer.pointee.sin6_port))
                }
            }
        } else if Int32(storage.ss_family) == AF_INET {
            withUnsafePointer(to: &storage) {
                $0.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { pointer in
                    var address = pointer.pointee.sin_addr
                    inet_ntop(AF_INET, &address, &buffer, socklen_t(buffer.count))
                    port = Int(UInt16(bigEndian: pointer.pointee.sin_port))
                }
            }
        }
        var address = String(cString: buffer)
        if address.hasPrefix("::ffff:") {
            address = String(address.dropFirst(7))
            family = "IPv4"
        }
        var result: [String: Any] = ["address": address, "family": family, "port": port]
        if let size { result["size"] = size }
        return result
    }

    private func socketPort(_ storage: sockaddr_storage) -> Int {
        if Int32(storage.ss_family) == AF_INET6 {
            return withUnsafePointer(to: storage) {
                $0.withMemoryRebound(to: sockaddr_in6.self, capacity: 1) { Int(UInt16(bigEndian: $0.pointee.sin6_port)) }
            }
        }
        if Int32(storage.ss_family) == AF_INET {
            return withUnsafePointer(to: storage) {
                $0.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { Int(UInt16(bigEndian: $0.pointee.sin_port)) }
            }
        }
        return 0
    }

    private func messageData(_ value: Any?) throws -> Data {
        if let string = value as? String { return Data(string.utf8) }
        if let dictionary = value as? [String: Any], let base64 = dictionary[localNetworkArrayBufferKey] as? String,
           let data = Data(base64Encoded: base64) { return data }
        throw LocalNetworkError.message("message must be a string or ArrayBuffer")
    }

    private func required(_ data: DMPMap, _ key: String) throws -> String {
        guard let value = data.getString(key: key), !value.isEmpty else { throw LocalNetworkError.message("\(key) is required") }
        return value
    }

    private func requiredPort(_ data: DMPMap) throws -> Int {
        guard let port = data.getInt(key: "port"), (0...65535).contains(port) else { throw LocalNetworkError.message("invalid port") }
        return port
    }

    private func socketKey(_ appId: String, _ socketId: String) -> String { "\(appId)\u{0}\(socketId)" }
    private func eventKey(_ appId: String, _ event: String, _ socketId: String) -> String { "\(appId)\u{0}\(event)\u{0}\(socketId)" }

    private func clearSocketListeners(appId: String, prefix: String, socketId: String) {
        stateQueue.sync {
            listeners.keys.filter { $0.hasPrefix("\(appId)\u{0}\(prefix).") && $0.hasSuffix("\u{0}\(socketId)") }
                .forEach { listeners.removeValue(forKey: $0) }
        }
    }

    private func errorText(_ error: Error) -> String {
        (error as? LocalNetworkError)?.text ?? error.localizedDescription
    }

    func clearApp(_ appId: String) {
        let resources = stateQueue.sync { () -> ([IOSUDPSocketState], [IOSTCPSocketState], LocalServiceDiscoveryState?, [TCPWifiBindState]) in
            let udp = udpSockets.filter { $0.value.appId == appId }.map(\.value)
            let tcp = tcpSockets.filter { $0.value.appId == appId }.map(\.value)
            let binds = wifiBinds.filter { $0.value.appId == appId }.map(\.value)
            udpSockets = udpSockets.filter { $0.value.appId != appId }
            tcpSockets = tcpSockets.filter { $0.value.appId != appId }
            wifiBinds = wifiBinds.filter { $0.value.appId != appId }
            listeners = listeners.filter { !$0.key.hasPrefix("\(appId)\u{0}") }
            return (udp, tcp, discoveries.removeValue(forKey: appId), binds)
        }
        resources.0.forEach { $0.close() }
        resources.1.forEach { $0.close() }
        resources.3.forEach { $0.stop() }
        DispatchQueue.main.async { resources.2?.stop() }
    }
}

final class LocalServiceDiscoveryState: NSObject, NetServiceBrowserDelegate, NetServiceDelegate {
    let appId: String
    let serviceType: String
    weak var manager: LocalNetworkAPIManager?
    let startCallback: DMPBridgeCallback?
    var stopCallback: DMPBridgeCallback?
    private let browser = NetServiceBrowser()
    private var services: [String: NetService] = [:]
    private var stopping = false

    init(appId: String, serviceType: String, manager: LocalNetworkAPIManager, startCallback: DMPBridgeCallback?) {
        self.appId = appId
        self.serviceType = serviceType
        self.manager = manager
        self.startCallback = startCallback
        super.init()
        browser.delegate = self
    }

    func start() { browser.searchForServices(ofType: serviceType, inDomain: "local.") }

    func stop() {
        if stopping { return }
        stopping = true
        browser.stop()
    }

    func netServiceBrowserWillSearch(_ browser: NetServiceBrowser) {
        manager?.success("startLocalServiceDiscovery", startCallback)
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didNotSearch errorDict: [String: NSNumber]) {
        manager?.discoveryDidFail(self, error: errorDict)
    }

    func netServiceBrowserDidStopSearch(_ browser: NetServiceBrowser) {
        manager?.discoveryDidStop(self)
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        services[serviceKey(service)] = service
        service.delegate = self
        service.resolve(withTimeout: 5)
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
        services.removeValue(forKey: serviceKey(service))
        manager?.discoveryLost(self, service: service)
    }

    func netServiceDidResolveAddress(_ sender: NetService) {
        manager?.discoveryFound(self, service: sender, ip: Self.ipAddress(sender.addresses) ?? sender.hostName ?? "")
    }

    func netService(_ sender: NetService, didNotResolve errorDict: [String: NSNumber]) {
        manager?.discoveryResolveFailed(self, service: sender)
    }

    private func serviceKey(_ service: NetService) -> String { "\(service.name)\u{0}\(service.type)\u{0}\(service.domain)" }

    private static func ipAddress(_ addresses: [Data]?) -> String? {
        for data in addresses ?? [] {
            let value: String? = data.withUnsafeBytes { bytes in
                guard let base = bytes.baseAddress?.assumingMemoryBound(to: sockaddr.self) else { return nil }
                var buffer = [CChar](repeating: 0, count: Int(INET6_ADDRSTRLEN))
                if Int32(base.pointee.sa_family) == AF_INET {
                    let address = bytes.baseAddress!.assumingMemoryBound(to: sockaddr_in.self).pointee.sin_addr
                    var copy = address
                    guard inet_ntop(AF_INET, &copy, &buffer, socklen_t(buffer.count)) != nil else { return nil }
                } else if Int32(base.pointee.sa_family) == AF_INET6 {
                    let address = bytes.baseAddress!.assumingMemoryBound(to: sockaddr_in6.self).pointee.sin6_addr
                    var copy = address
                    guard inet_ntop(AF_INET6, &copy, &buffer, socklen_t(buffer.count)) != nil else { return nil }
                } else { return nil }
                return String(cString: buffer)
            }
            if let value { return value }
        }
        return nil
    }
}
