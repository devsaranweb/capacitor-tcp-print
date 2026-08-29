import Foundation
import Capacitor
import Network

/// Stateless raw-TCP print transport (RAW / JetDirect, typically port 9100).
///
/// One call = one `NWConnection`: connect, send, cancel. No session state is
/// kept — see the TS definitions for why.
///
/// iOS 14+ shows the system LOCAL NETWORK privacy prompt on the first
/// connection to a LAN peer; the HOST app must declare
/// `NSLocalNetworkUsageDescription` in its Info.plist. A denied prompt makes
/// every connect fail (reported here as CONNECT_FAILED/TIMEOUT — the OS does
/// not expose the denial distinctly).
///
/// Error codes are the plugin's API — hosts map them to translated copy, so
/// they must stay stable: INVALID_ARGS / CONNECT_FAILED / TIMEOUT /
/// WRITE_FAILED.
@objc(TcpPrintPlugin)
public class TcpPrintPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "TcpPrintPlugin"
    public let jsName = "TcpPrint"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "print", returnType: CAPPluginReturnPromise)
    ]

    private static let codeInvalidArgs = "INVALID_ARGS"
    private static let codeConnectFailed = "CONNECT_FAILED"
    private static let codeTimeout = "TIMEOUT"
    private static let codeWriteFailed = "WRITE_FAILED"
    private static let defaultTimeoutMs = 5000

    // One serial queue for all socket work: settles each call exactly once
    // and serializes concurrent prints (defence in depth under the hosts' own
    // I/O rails).
    private let queue = DispatchQueue(label: "tcp-print")

    @objc func print(_ call: CAPPluginCall) {
        guard let host = call.getString("host")?.trimmingCharacters(in: .whitespaces), !host.isEmpty else {
            call.reject("host is required", Self.codeInvalidArgs)
            return
        }
        guard let portInt = call.getInt("port"), (1...65535).contains(portInt),
              let port = NWEndpoint.Port(rawValue: UInt16(portInt)) else {
            call.reject("port must be 1-65535", Self.codeInvalidArgs)
            return
        }
        guard let dataB64 = call.getString("dataB64"), !dataB64.isEmpty,
              let bytes = Data(base64Encoded: dataB64), !bytes.isEmpty else {
            call.reject("dataB64 must be non-empty base64", Self.codeInvalidArgs)
            return
        }
        let timeoutMs = call.getInt("timeoutMs").flatMap { $0 > 0 ? $0 : nil } ?? Self.defaultTimeoutMs

        let connection = NWConnection(host: NWEndpoint.Host(host), port: port, using: .tcp)

        // Everything below runs on `queue`, so `settled` needs no lock.
        var settled = false
        func settle(_ complete: @escaping () -> Void) {
            if settled { return }
            settled = true
            connection.stateUpdateHandler = nil
            connection.cancel()
            complete()
        }

        let deadline = DispatchWorkItem {
            settle { call.reject("Print to \(host):\(portInt) timed out", Self.codeTimeout) }
        }
        queue.asyncAfter(deadline: .now() + .milliseconds(timeoutMs), execute: deadline)

        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                // The connect phase wrote nothing; from here a failure is
                // WRITE_FAILED ("maybe half-printed"), which hosts must not
                // blindly retry.
                connection.send(content: bytes, completion: .contentProcessed { error in
                    self.queue.async {
                        deadline.cancel()
                        if let error = error {
                            settle { call.reject("Write to printer failed: \(error.localizedDescription)", Self.codeWriteFailed) }
                        } else {
                            settle { call.resolve() }
                        }
                    }
                })
            case .waiting(let error), .failed(let error):
                // A LAN printer either accepts immediately or is off/wrong-IP;
                // .waiting would otherwise spin retries until the deadline.
                self.queue.async {
                    deadline.cancel()
                    settle { call.reject("Could not connect to \(host):\(portInt): \(error.localizedDescription)", Self.codeConnectFailed) }
                }
            default:
                break
            }
        }
        connection.start(queue: queue)
    }
}
