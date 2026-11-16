import Foundation

/// SDK diagnostics are compiled out of release builds. In particular, bridge
/// payloads and WebView console messages must never reach production logs.
public enum DMPLogger {
    public static func debug(
        _ items: Any...,
        separator: String = " ",
        terminator: String = "\n"
    ) {
        #if DEBUG
        let message = items.map { String(describing: $0) }.joined(separator: separator)
        Swift.print(message, terminator: terminator)
        #endif
        // Host-visible diagnostics (syslog): release builds compile print out,
        // so launch/bridge traces were invisible to hosts driving the container
        // remotely. NSLog lands in the unified log and streams via syslog.
        let message = items.map { String(describing: $0) }.joined(separator: separator)
        NSLog("%@", "[DMP] \(message)")
    }
}
