/**
 * Stateless raw-TCP print transport ("RAW" / JetDirect printing, typically
 * port 9100). One call = one socket: connect, write every byte, close.
 *
 * There is deliberately NO session API. Thermal printers accept a connection,
 * spool the bytes, and print; holding a socket open buys nothing and lets a
 * half-dead connection strand later jobs. Callers that need serialization
 * across jobs do it app-side (all three host apps already run device I/O on a
 * single rail).
 *
 * iOS: raw sockets bypass ATS, but iOS 14+ shows the system LOCAL NETWORK
 * privacy prompt on first use — the HOST app must declare
 * `NSLocalNetworkUsageDescription` in its Info.plist or every connect fails
 * as if the printer were offline.
 */
export interface TcpPrintPlugin {
    /**
     * Send raw bytes to `host:port` and close the connection.
     *
     * Rejects with a STABLE `code` (Capacitor error `code` field) so hosts can
     * map failures to translated operator copy — never match on the message:
     * - `INVALID_ARGS`    host/port/data missing or malformed
     * - `CONNECT_FAILED`  the TCP connection could not be opened (wrong IP,
     *                     printer off, different subnet)
     * - `TIMEOUT`         connect or write did not complete inside `timeoutMs`
     * - `WRITE_FAILED`    the connection opened but writing failed midway —
     *                     the printer MAY have printed part of the job
     * - `UNSUPPORTED_PLATFORM` web
     *
     * @param options.host      printer IPv4/IPv6 address or hostname
     * @param options.port      TCP port (RAW printing is almost always 9100)
     * @param options.dataB64   base64-encoded printer bytes (ESC/POS etc.)
     * @param options.timeoutMs connect + write deadline in ms (default 5000)
     */
    print(options: {
        host: string;
        port: number;
        dataB64: string;
        timeoutMs?: number;
    }): Promise<void>;
}
