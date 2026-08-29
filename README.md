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
`WRITE_FAILED` (`UNSUPPORTED_PLATFORM` on web). Map codes to operator copy —
never match on the message.

## iOS

Raw sockets bypass ATS, but iOS 14+ shows the system **local network** privacy
prompt on first use. The HOST app must declare
`NSLocalNetworkUsageDescription` in its Info.plist, or every connect fails as
if the printer were offline.

## Android

Needs only `INTERNET` (declared by the plugin; merges with the host's).
Socket I/O runs on a dedicated background thread.
