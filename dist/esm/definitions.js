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
export {};
