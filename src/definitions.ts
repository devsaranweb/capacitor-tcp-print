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
 * as if the printer were offline. That applies to {@link
 * TcpPrintPlugin.discover} too, which touches many more LAN peers than a
 * print does.
 *
 * DISCOVERY IS A PORT PROBE, NOT A SERVICE BROWSE, and that is a deliberate
 * choice rather than a shortcut:
 *
 * - Port 9100 RAW/JetDirect is the de-facto standard raw-print port, and a
 *   subnet connect-probe on it is how mainstream POS software finds generic
 *   thermal printers. It needs NO extra platform permission on either OS.
 * - mDNS/Bonjour (`_pdl-datastream._tcp`) was considered and rejected: it
 *   requires `CHANGE_WIFI_MULTICAST_STATE` on Android and `NSBonjourServices`
 *   on iOS — neither of which the host apps declare — and the cheap ESC/POS
 *   hardware this plugin exists for very often does not advertise itself at
 *   all, so it would find fewer printers while asking for more permissions.
 *   It stays available as a purely ADDITIVE layer if the fleet ever carries
 *   printers that need it.
 *
 * The consequence to state at every call site: a candidate is a host that
 * ACCEPTED a TCP connection on the port. It is not proof that a printer is
 * there — anything listening on 9100 answers. Treat the result as a
 * shortlist for the operator to test-print, never as an auto-configuration.
 */

/** A host that accepted a TCP connection on the probed port. */
export interface TcpPrinterCandidate {
  /** IPv4 address that answered. */
  host: string;
  /** The port it answered on (the probed port — echoed for convenience). */
  port: number;
}

export interface TcpDiscoverResult {
  /** Hosts that accepted a connection, in ascending address order. */
  printers: TcpPrinterCandidate[];
  /**
   * How many addresses were probed. Reported so an EMPTY result is
   * diagnosable: `scanned: 0` means the sweep never ran, while `scanned: 254`
   * with no printers means the subnet genuinely has nothing on that port —
   * two different things for the operator to do next.
   */
  scanned: number;
  /** The swept range as `a.b.c.0/24`, or null when none could be resolved. */
  subnet: string | null;
}

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
  print(options: { host: string; port: number; dataB64: string; timeoutMs?: number }): Promise<void>;

  /**
   * Sweep the device's own IPv4 /24 for hosts accepting `port`, so an
   * operator picks a printer from a list instead of reading an IP off a
   * self-test page.
   *
   * The range is ALWAYS at most one /24 (254 hosts): a wider interface prefix
   * is clamped to the /24 containing this device's address. Sweeping a /16
   * would open 65k sockets for a printer that is, in practice, always on the
   * same /24 as the register.
   *
   * Nothing is written to any host — connect, observe, close. See the module
   * docblock for why this is a port probe rather than an mDNS browse, and for
   * why a hit is a candidate rather than a confirmed printer.
   *
   * Rejects with a stable `code`:
   * - `INVALID_ARGS`         port out of range
   * - `NO_NETWORK`           no Wi-Fi or Ethernet IPv4 interface is up. BOTH
   *                          platforms restrict the sweep to those (iOS
   *                          `en*`/`bridge*`, Android `wlan*`/`eth*`) — a
   *                          cellular or VPN address would otherwise send 254
   *                          probes to carrier-NAT or tunnel hosts looking for
   *                          a printer that cannot be there. So a cellular-only
   *                          device answers NO_NETWORK rather than sweeping.
   *                          Distinct from an empty result BECAUSE the operator
   *                          fix is different ("join the printer's network" vs
   *                          "check the printer").
   * - `UNSUPPORTED_PLATFORM` web
   *
   * @param options.port      port to probe (default 9100)
   * @param options.timeoutMs PER-HOST connect deadline in ms (default 400).
   *                          The whole sweep is bounded by this times the
   *                          number of batches, not by a separate budget.
   */
  discover(options?: { port?: number; timeoutMs?: number }): Promise<TcpDiscoverResult>;

  /**
   * Ask whether a single host accepts a connection on `port` — the cheap
   * "is this IP right?" check behind a typed-in address, with no paper spent.
   *
   * RESOLVES `{ reachable: false }` rather than rejecting when the host does
   * not answer: unreachable is the ANSWER to this question, not a failure of
   * it, and a caller must not have to tell "the probe broke" apart from "the
   * printer is off" by inspecting an error code. Rejects only for
   * `INVALID_ARGS` and `UNSUPPORTED_PLATFORM`.
   *
   * @param options.timeoutMs connect deadline in ms (default 1000)
   */
  probe(options: { host: string; port: number; timeoutMs?: number }): Promise<{ reachable: boolean }>;
}
