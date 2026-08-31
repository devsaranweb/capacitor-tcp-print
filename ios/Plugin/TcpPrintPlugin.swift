import Foundation
import Capacitor
import Network

/// Stateless raw-TCP print transport (RAW / JetDirect, typically port 9100)
/// plus a subnet port-probe used for printer discovery.
///
/// One print call = one `NWConnection`: connect, send, cancel. No session
/// state is kept — see the TS definitions for why.
///
/// iOS 14+ shows the system LOCAL NETWORK privacy prompt on the first
/// connection to a LAN peer; the HOST app must declare
/// `NSLocalNetworkUsageDescription` in its Info.plist. A denied prompt makes
/// every connect fail (reported here as CONNECT_FAILED/TIMEOUT — the OS does
/// not expose the denial distinctly), and for `discover` that reads as an
/// empty sweep rather than an error, which is why the host copy must mention
/// the permission.
///
/// Error codes are the plugin's API — hosts map them to translated copy, so
/// they must stay stable: INVALID_ARGS / CONNECT_FAILED / TIMEOUT /
/// WRITE_FAILED / NO_NETWORK.
@objc(TcpPrintPlugin)
public class TcpPrintPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "TcpPrintPlugin"
    public let jsName = "TcpPrint"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "print", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "discover", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "probe", returnType: CAPPluginReturnPromise)
    ]

    private static let codeInvalidArgs = "INVALID_ARGS"
    private static let codeConnectFailed = "CONNECT_FAILED"
    private static let codeTimeout = "TIMEOUT"
    private static let codeWriteFailed = "WRITE_FAILED"
    private static let codeNoNetwork = "NO_NETWORK"
    private static let defaultTimeoutMs = 5000
    private static let defaultDiscoverPort = 9100
    private static let defaultDiscoverTimeoutMs = 400
    private static let defaultProbeTimeoutMs = 1000

    /// Widest range a sweep will ever cover — a printer shares the register's
    /// /24 in every real deployment, and honouring a /16 literally would open
    /// 65k connections to find it.
    private static let minSweepPrefix = 24

    /// Connections in flight during a sweep.
    private static let discoverConcurrency = 32

    // One serial queue for all socket work: settles each call exactly once
    // and serializes concurrent prints (defence in depth under the hosts' own
    // I/O rails).
    private let queue = DispatchQueue(label: "tcp-print")

    /// Sweeps run here, NEVER on `queue`: a sweep takes seconds, and queueing
    /// a kitchen ticket behind an operator browsing for printers would be a
    /// real regression on the money path.
    private let scanQueue = DispatchQueue(label: "tcp-print.scan")

    /// Probe connections run concurrently here. It must be distinct from
    /// `scanQueue`, which blocks on the concurrency semaphore while a sweep
    /// is in flight — sharing them would deadlock.
    private let probeQueue = DispatchQueue(label: "tcp-print.probe", attributes: .concurrent)

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

    /// Probe every host on this device's own IPv4 /24 for an open port.
    ///
    /// NOTHING IS SENT to any host — connect, observe, cancel. A hit means
    /// "something accepted a TCP connection here", which is a candidate for
    /// the operator to test-print, never a confirmed printer.
    @objc func discover(_ call: CAPPluginCall) {
        let portInt = call.getInt("port") ?? Self.defaultDiscoverPort
        guard (1...65535).contains(portInt), let port = NWEndpoint.Port(rawValue: UInt16(portInt)) else {
            call.reject("port must be 1-65535", Self.codeInvalidArgs)
            return
        }
        let timeoutMs = call.getInt("timeoutMs").flatMap { $0 > 0 ? $0 : nil } ?? Self.defaultDiscoverTimeoutMs

        scanQueue.async {
            guard let local = Self.localIPv4() else {
                // Distinct from an empty result BY DESIGN: "you are not on a
                // LAN" and "the LAN has no printer" need different things
                // from the operator.
                call.reject("No usable Wi-Fi/Ethernet IPv4 interface is available", Self.codeNoNetwork)
                return
            }

            let prefix = max(local.prefix, Self.minSweepPrefix)
            let hostBits = 32 - prefix
            // A /31 or /32 has no usable host range; report it honestly as a
            // completed sweep of nothing rather than as a failure.
            let hostCount = hostBits >= 1 ? (1 << hostBits) - 2 : 0
            let network = hostBits >= 32 ? UInt32(0) : local.address & (UInt32.max << UInt32(hostBits))
            let subnet = "\(Self.toIPv4(network))/\(prefix)"

            guard hostCount > 0 else {
                call.resolve(["printers": [], "scanned": 0, "subnet": subnet])
                return
            }

            var targets: [UInt32] = []
            targets.reserveCapacity(hostCount)
            for offset in 1...hostCount {
                let candidate = network &+ UInt32(offset)
                // Probing our own address would report this device as a
                // printer whenever anything on it listens on the port.
                if candidate == local.address { continue }
                targets.append(candidate)
            }

            let group = DispatchGroup()
            let semaphore = DispatchSemaphore(value: Self.discoverConcurrency)
            let lock = NSLock()
            var reachableIndexes = Set<Int>()

            for (index, candidate) in targets.enumerated() {
                semaphore.wait()
                group.enter()
                self.probeHost(Self.toIPv4(candidate), port: port, timeoutMs: timeoutMs) { reachable in
                    if reachable {
                        lock.lock()
                        reachableIndexes.insert(index)
                        lock.unlock()
                    }
                    semaphore.signal()
                    group.leave()
                }
            }
            group.wait()

            // Emitted in ascending address order — completions arrive out of
            // order, so the INDEX is what preserves it.
            let printers: [[String: Any]] = targets.enumerated()
                .filter { reachableIndexes.contains($0.offset) }
                .map { ["host": Self.toIPv4($0.element), "port": portInt] }

            call.resolve(["printers": printers, "scanned": targets.count, "subnet": subnet])
        }
    }

    /// Single-host connect check — the "is this typed IP right?" question,
    /// answered without spending paper.
    ///
    /// RESOLVES `{reachable:false}` for an unreachable host rather than
    /// rejecting: unreachable is the ANSWER here, not a failure to answer, and
    /// a caller must not have to tell a broken probe from an off printer by
    /// reading an error code.
    @objc func probe(_ call: CAPPluginCall) {
        guard let host = call.getString("host")?.trimmingCharacters(in: .whitespaces), !host.isEmpty else {
            call.reject("host is required", Self.codeInvalidArgs)
            return
        }
        guard let portInt = call.getInt("port"), (1...65535).contains(portInt),
              let port = NWEndpoint.Port(rawValue: UInt16(portInt)) else {
            call.reject("port must be 1-65535", Self.codeInvalidArgs)
            return
        }
        let timeoutMs = call.getInt("timeoutMs").flatMap { $0 > 0 ? $0 : nil } ?? Self.defaultProbeTimeoutMs

        probeHost(host, port: port, timeoutMs: timeoutMs) { reachable in
            call.resolve(["reachable": reachable])
        }
    }

    /// Connect-and-cancel. Never sends; any failure means "not reachable".
    /// The completion fires exactly once, on `probeQueue`.
    private func probeHost(_ host: String, port: NWEndpoint.Port, timeoutMs: Int, completion: @escaping (Bool) -> Void) {
        let connection = NWConnection(host: NWEndpoint.Host(host), port: port, using: .tcp)
        let settleQueue = probeQueue
        var settled = false
        let lock = NSLock()

        func settle(_ reachable: Bool) {
            lock.lock()
            if settled {
                lock.unlock()
                return
            }
            settled = true
            lock.unlock()
            connection.stateUpdateHandler = nil
            connection.cancel()
            completion(reachable)
        }

        let deadline = DispatchWorkItem { settle(false) }
        settleQueue.asyncAfter(deadline: .now() + .milliseconds(timeoutMs), execute: deadline)

        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                deadline.cancel()
                settle(true)
            case .waiting, .failed, .cancelled:
                // `.waiting` is a refusal in practice on a LAN — without this
                // arm every closed port would burn the full timeout.
                deadline.cancel()
                settle(false)
            default:
                break
            }
        }
        connection.start(queue: settleQueue)
    }

    /// First usable Wi-Fi/Ethernet IPv4 address and its prefix length.
    ///
    /// Restricted to `en*` / `bridge*` interfaces DELIBERATELY: cellular
    /// (`pdp_ip*`) would otherwise be picked up and the sweep would probe 254
    /// carrier addresses looking for a printer that cannot be there. Returning
    /// nil there is the honest answer, and the host renders it as NO_NETWORK.
    ///
    /// Link-local (169.254/16) is skipped — that address means DHCP never
    /// answered, so a sweep finds nothing and takes the full timeout doing it.
    private static func localIPv4() -> (address: UInt32, prefix: Int)? {
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return nil }
        defer { freeifaddrs(head) }

        for pointer in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let flags = Int32(pointer.pointee.ifa_flags)
            guard (flags & IFF_UP) == IFF_UP, (flags & IFF_LOOPBACK) == 0 else { continue }
            guard let rawAddress = pointer.pointee.ifa_addr, rawAddress.pointee.sa_family == UInt8(AF_INET) else { continue }
            guard let rawNetmask = pointer.pointee.ifa_netmask else { continue }

            let name = String(cString: pointer.pointee.ifa_name)
            guard name.hasPrefix("en") || name.hasPrefix("bridge") else { continue }

            let address = rawAddress.withMemoryRebound(to: sockaddr_in.self, capacity: 1) {
                UInt32(bigEndian: $0.pointee.sin_addr.s_addr)
            }
            let netmask = rawNetmask.withMemoryRebound(to: sockaddr_in.self, capacity: 1) {
                UInt32(bigEndian: $0.pointee.sin_addr.s_addr)
            }
            if (address >> 16) == 0xA9FE { continue }
            let prefix = netmask.nonzeroBitCount
            if prefix <= 0 { continue }
            return (address, prefix)
        }
        return nil
    }

    private static func toIPV4Components(_ value: UInt32) -> [UInt32] {
        [(value >> 24) & 0xFF, (value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF]
    }

    private static func toIPv4(_ value: UInt32) -> String {
        toIPV4Components(value).map(String.init).joined(separator: ".")
    }
}
