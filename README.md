# @aybinv7/capacitor-tcp-print

Minimal Capacitor plugin that sends raw printer bytes (ESC/POS etc.) to a LAN
printer over a TCP socket (RAW / JetDirect printing, typically port 9100).

Stateless connect-per-print: one call opens a socket, writes every byte,
flushes and closes. There is deliberately no session API — thermal printers
accept a connection, spool, print; holding a socket open buys nothing.

```ts
import { TcpPrint } from '@aybinv7/capacitor-tcp-print';

await TcpPrint.print({
  host: '192.168.1.50',
  port: 9100,
  dataB64: btoa(String.fromCharCode(...escposBytes)),
  timeoutMs: 5000,
});
```

Rejections carry a stable `code`: `INVALID_ARGS`, `CONNECT_FAILED`, `TIMEOUT`,
`WRITE_FAILED`, `NO_NETWORK` (`UNSUPPORTED_PLATFORM` on web). Map codes to
operator copy — never match on the message.

## Discovery

```ts
const { printers, scanned, subnet } = await TcpPrint.discover({ port: 9100 });
// printers: [{ host: '192.168.1.50', port: 9100 }]

const { reachable } = await TcpPrint.probe({ host: '192.168.1.50', port: 9100 });
```

`discover` sweeps **this device's own IPv4 /24** (a wider interface prefix is
clamped to the /24 containing it) and reports every host that accepted a TCP
connection on the port. Nothing is written to any host.

**A hit is a candidate, not a confirmed printer** — anything listening on the
port answers. Present the list for the operator to test-print; never
auto-configure from it.

`scanned` and `subnet` are returned so an empty result is diagnosable:
`scanned: 254` with no printers means the subnet genuinely has nothing on that
port, which is a different operator action from `NO_NETWORK` ("this device is
not on a LAN").

`probe` **resolves** `{ reachable: false }` for a host that does not answer —
unreachable is the answer to that question, not a failure of it.

### Why a port probe and not mDNS/Bonjour

Deliberate. Port 9100 RAW/JetDirect is the de-facto standard raw-print port
and a connect-probe needs **no extra platform permission** on either OS.
Bonjour (`_pdl-datastream._tcp`) would need `CHANGE_WIFI_MULTICAST_STATE` on
Android and `NSBonjourServices` on iOS, and the cheap ESC/POS hardware this
plugin exists for very often does not advertise itself at all — so it would
find fewer printers while asking for more permissions. It remains available as
a purely additive layer if a fleet ever carries printers that need it.

## iOS

Raw sockets bypass ATS, but iOS 14+ shows the system **local network** privacy
prompt on first use. The HOST app must declare
`NSLocalNetworkUsageDescription` in its Info.plist, or every connect fails as
if the printer were offline — and for `discover` that reads as an empty sweep
rather than an error, so host copy should mention the permission.

Only `en*` / `bridge*` interfaces are swept. Cellular (`pdp_ip*`) is excluded
on purpose: a printer cannot be on the carrier network, and probing 254
carrier addresses to prove it is pure waste.

## Android

Needs only `INTERNET` (declared by the plugin; merges with the host's) —
discovery uses `NetworkInterface`, **not** `ConnectivityManager`, specifically
so it adds no permission to the host apps. Socket I/O runs on a dedicated
background thread, and sweeps run on their own executor so a discovery scan
can never queue behind (or in front of) a print.
