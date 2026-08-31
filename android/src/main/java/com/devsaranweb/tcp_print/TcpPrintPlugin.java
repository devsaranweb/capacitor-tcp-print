package com.devsaranweb.tcp_print;

import android.util.Base64;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stateless raw-TCP print transport (RAW / JetDirect, typically port 9100)
 * plus a subnet port-probe used for printer discovery.
 *
 * One print call = one socket: connect, write, flush, close. No session state
 * is kept — see the TS definitions for why. Socket I/O runs on a dedicated
 * single-thread executor so a slow printer never blocks Capacitor's shared
 * plugin handler thread (and the single thread ALSO serializes concurrent
 * calls from the bridge side, defence in depth under the hosts' own I/O
 * rails).
 *
 * DISCOVERY RUNS ON ITS OWN EXECUTOR, never the print one: a sweep takes
 * seconds, and queueing a kitchen ticket behind an operator browsing for
 * printers would be a real regression on the money path.
 *
 * Error codes are the plugin's API — hosts map them to translated copy, so
 * they must stay stable: INVALID_ARGS / CONNECT_FAILED / TIMEOUT /
 * WRITE_FAILED / NO_NETWORK.
 */
@CapacitorPlugin(name = "TcpPrint")
public class TcpPrintPlugin extends Plugin {

    static final String CODE_INVALID_ARGS = "INVALID_ARGS";
    static final String CODE_CONNECT_FAILED = "CONNECT_FAILED";
    static final String CODE_TIMEOUT = "TIMEOUT";
    static final String CODE_WRITE_FAILED = "WRITE_FAILED";
    static final String CODE_NO_NETWORK = "NO_NETWORK";

    private static final int DEFAULT_TIMEOUT_MS = 5000;
    private static final int DEFAULT_DISCOVER_PORT = 9100;
    private static final int DEFAULT_DISCOVER_TIMEOUT_MS = 400;
    private static final int DEFAULT_PROBE_TIMEOUT_MS = 1000;

    /**
     * Widest range a sweep will ever cover. A printer shares the register's
     * /24 in every real deployment; honouring a /16 literally would open
     * 65k sockets to find it.
     */
    private static final int MIN_SWEEP_PREFIX = 24;

    /**
     * Sockets in flight during a sweep. High enough that a /24 finishes in a
     * few hundred milliseconds per batch, low enough not to exhaust the fd
     * budget of a cheap Android register.
     */
    private static final int DISCOVER_CONCURRENCY = 32;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor();

    @PluginMethod
    public void print(PluginCall call) {
        String host = call.getString("host");
        Integer port = call.getInt("port");
        String dataB64 = call.getString("dataB64");
        int timeoutMs = call.getInt("timeoutMs", DEFAULT_TIMEOUT_MS);

        if (host == null || host.trim().isEmpty()) {
            call.reject("host is required", CODE_INVALID_ARGS);
            return;
        }
        if (port == null || port < 1 || port > 65535) {
            call.reject("port must be 1-65535", CODE_INVALID_ARGS);
            return;
        }
        if (dataB64 == null || dataB64.isEmpty()) {
            call.reject("dataB64 is required", CODE_INVALID_ARGS);
            return;
        }
        final byte[] bytes;
        try {
            bytes = Base64.decode(dataB64, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            call.reject("dataB64 is not valid base64", CODE_INVALID_ARGS);
            return;
        }
        if (bytes.length == 0) {
            call.reject("dataB64 decoded to zero bytes", CODE_INVALID_ARGS);
            return;
        }

        final String targetHost = host.trim();
        final int targetPort = port;
        final int deadline = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;

        executor.execute(() -> {
            // The deadline starts when the job STARTS, never when it was
            // queued: this executor is serial, so a print waiting behind a
            // slow one would otherwise spend its whole budget in the queue and
            // reject TIMEOUT without ever reaching the network. `timeoutMs` is
            // documented as a connect+write deadline, not a submit-to-settle one.
            final long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadline);
            AtomicBoolean settled = new AtomicBoolean(false);
            // The connect phase writes nothing, so distinguishing it from the
            // write phase is what lets hosts retry a CONNECT_FAILED safely
            // while treating WRITE_FAILED as "maybe half-printed".
            try (Socket socket = new Socket()) {
                try {
                    socket.connect(new InetSocketAddress(targetHost, targetPort), deadline);
                } catch (SocketTimeoutException e) {
                    if (settled.compareAndSet(false, true)) {
                        call.reject("Connect to " + targetHost + ":" + targetPort + " timed out", CODE_TIMEOUT);
                    }
                    return;
                } catch (Exception e) {
                    if (settled.compareAndSet(false, true)) {
                        call.reject(
                            "Could not connect to " + targetHost + ":" + targetPort + ": " + e.getMessage(),
                            CODE_CONNECT_FAILED
                        );
                    }
                    return;
                }

                long writeRemainingNanos = deadlineNanos - System.nanoTime();
                if (writeRemainingNanos <= 0) {
                    settled.set(true);
                    call.reject("Print to " + targetHost + ":" + targetPort + " timed out", CODE_TIMEOUT);
                    return;
                }
                ScheduledFuture<?> writeTimeout = timeoutExecutor.schedule(() -> {
                    if (!settled.compareAndSet(false, true)) return;
                    call.reject("Print to " + targetHost + ":" + targetPort + " timed out", CODE_TIMEOUT);
                    try {
                        // Closing the socket from the timer thread interrupts a
                        // blocked write and releases the serial print executor.
                        socket.close();
                    } catch (Exception ignored) {
                        // The promise is already settled as TIMEOUT.
                    }
                }, writeRemainingNanos, TimeUnit.NANOSECONDS);
                try {
                    OutputStream out = socket.getOutputStream();
                    out.write(bytes);
                    out.flush();
                } catch (Exception e) {
                    if (settled.compareAndSet(false, true)) {
                        writeTimeout.cancel(false);
                        call.reject("Write to printer failed: " + e.getMessage(), CODE_WRITE_FAILED);
                    }
                    return;
                }
                if (settled.compareAndSet(false, true)) {
                    writeTimeout.cancel(false);
                    call.resolve();
                }
            } catch (Exception e) {
                // Reachable two ways, and the CAS is what tells them apart:
                // `new Socket()` failing (nothing opened, nothing written, so
                // CONNECT_FAILED is both accurate and safe to retry), or
                // try-with-resources failing to CLOSE after the job already
                // settled — the bytes are on the wire, so that one must not
                // fail a print that happened, and the CAS drops it.
                if (settled.compareAndSet(false, true)) {
                    call.reject(
                        "Could not open socket to " + targetHost + ":" + targetPort + ": " + e.getMessage(),
                        CODE_CONNECT_FAILED
                    );
                }
            }
        });
    }

    /**
     * Probe every host on this device's own IPv4 /24 for an open port.
     *
     * NOTHING IS WRITTEN to any host — connect, observe, close. A hit means
     * "something accepted a TCP connection here", which is a candidate for
     * the operator to test-print, never a confirmed printer.
     */
    @PluginMethod
    public void discover(PluginCall call) {
        int port = call.getInt("port", DEFAULT_DISCOVER_PORT);
        int timeoutMs = call.getInt("timeoutMs", DEFAULT_DISCOVER_TIMEOUT_MS);
        if (port < 1 || port > 65535) {
            call.reject("port must be 1-65535", CODE_INVALID_ARGS);
            return;
        }
        final int probePort = port;
        final int probeTimeout = timeoutMs > 0 ? timeoutMs : DEFAULT_DISCOVER_TIMEOUT_MS;

        scanExecutor.execute(() -> {
            InterfaceAddress local = findLocalIpv4();
            if (local == null) {
                // Distinct from an empty result BY DESIGN: "you are not on a
                // network" and "the network has no printer" need different
                // things from the operator.
                call.reject("No non-loopback IPv4 interface is available", CODE_NO_NETWORK);
                return;
            }

            byte[] own = local.getAddress().getAddress();
            int ownInt = toInt(own);
            int prefix = Math.max(local.getNetworkPrefixLength(), MIN_SWEEP_PREFIX);
            int hostBits = 32 - prefix;
            // A /31 or /32 has no usable host range; report it honestly as a
            // completed sweep of nothing rather than as a failure.
            int hostCount = hostBits >= 1 ? (1 << hostBits) - 2 : 0;
            int network = hostBits >= 32 ? 0 : ownInt & (0xFFFFFFFF << hostBits);
            String subnet = toIpv4(network) + "/" + prefix;

            if (hostCount <= 0) {
                JSObject empty = new JSObject();
                empty.put("printers", new JSArray());
                empty.put("scanned", 0);
                empty.put("subnet", subnet);
                call.resolve(empty);
                return;
            }

            ExecutorService pool = Executors.newFixedThreadPool(DISCOVER_CONCURRENCY);
            try {
                List<Integer> targets = new ArrayList<>(hostCount);
                List<Future<Boolean>> results = new ArrayList<>(hostCount);
                for (int offset = 1; offset <= hostCount; offset++) {
                    int candidate = network + offset;
                    // Probing our own address would report this device as a
                    // printer whenever anything on it listens on the port.
                    if (candidate == ownInt) continue;
                    targets.add(candidate);
                }
                for (Integer candidate : targets) {
                    final String host = toIpv4(candidate);
                    results.add(pool.submit((Callable<Boolean>) () -> probeHost(host, probePort, probeTimeout)));
                }

                JSArray printers = new JSArray();
                for (int i = 0; i < targets.size(); i++) {
                    boolean reachable;
                    try {
                        reachable = Boolean.TRUE.equals(results.get(i).get());
                    } catch (Exception e) {
                        // A probe that could not run is not a printer. One bad
                        // socket must never fail the whole sweep.
                        reachable = false;
                    }
                    if (!reachable) continue;
                    JSObject entry = new JSObject();
                    entry.put("host", toIpv4(targets.get(i)));
                    entry.put("port", probePort);
                    printers.put(entry);
                }

                JSObject result = new JSObject();
                result.put("printers", printers);
                result.put("scanned", targets.size());
                result.put("subnet", subnet);
                call.resolve(result);
            } finally {
                pool.shutdownNow();
            }
        });
    }

    /**
     * Single-host connect check — the "is this typed IP right?" question,
     * answered without spending paper.
     *
     * RESOLVES `{reachable:false}` for an unreachable host rather than
     * rejecting: unreachable is the ANSWER here, not a failure to answer, and
     * a caller must not have to tell a broken probe from an off printer by
     * reading an error code.
     */
    @PluginMethod
    public void probe(PluginCall call) {
        String host = call.getString("host");
        Integer port = call.getInt("port");
        int timeoutMs = call.getInt("timeoutMs", DEFAULT_PROBE_TIMEOUT_MS);
        if (host == null || host.trim().isEmpty()) {
            call.reject("host is required", CODE_INVALID_ARGS);
            return;
        }
        if (port == null || port < 1 || port > 65535) {
            call.reject("port must be 1-65535", CODE_INVALID_ARGS);
            return;
        }
        final String targetHost = host.trim();
        final int targetPort = port;
        final int deadline = timeoutMs > 0 ? timeoutMs : DEFAULT_PROBE_TIMEOUT_MS;

        scanExecutor.execute(() -> {
            JSObject result = new JSObject();
            result.put("reachable", probeHost(targetHost, targetPort, deadline));
            call.resolve(result);
        });
    }

    /** Connect-and-close. Never writes; any failure means "not reachable". */
    private static boolean probeHost(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** The address to sweep from: Wi-Fi if up, else wired, else none. */
    private static InterfaceAddress findLocalIpv4() {
        // Wi-Fi first, then wired. Two passes rather than one, so a device with
        // BOTH up sweeps the network the printer is most likely on.
        InterfaceAddress wifi = findLocalIpv4OnPrefix("wlan");
        if (wifi != null) return wifi;
        return findLocalIpv4OnPrefix("eth");
    }

    /**
     * First usable IPv4 address on an interface whose name starts with
     * {@code prefix}.
     *
     * RESTRICTED to Wi-Fi/Ethernet DELIBERATELY, matching the iOS twin's
     * `en*`/`bridge*` rule. Taking the first up non-loopback interface instead
     * picks up cellular (`rmnet_data0`) and VPN (`tun0`), which on Android
     * commonly enumerate BEFORE `wlan0` — the sweep would then make 254
     * outbound connects to carrier-NAT or tunnel addresses looking for a
     * printer that cannot be there, return nothing, and name a subnet the
     * operator does not recognise. On a cellular-only device the two platforms
     * would also disagree about the plugin's own contract: iOS answers
     * NO_NETWORK and Android would sweep.
     *
     * Uses {@link NetworkInterface} rather than ConnectivityManager: it needs
     * NO permission, so discovery costs the host apps no manifest change.
     *
     * Link-local (169.254.x) is skipped — that address means DHCP never
     * answered, so sweeping it finds nothing and takes the full timeout doing it.
     */
    private static InterfaceAddress findLocalIpv4OnPrefix(String prefix) {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return null;
            while (interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                String name = nif.getName();
                if (name == null || !name.startsWith(prefix)) continue;
                for (InterfaceAddress candidate : nif.getInterfaceAddresses()) {
                    InetAddress address = candidate.getAddress();
                    if (!(address instanceof Inet4Address)) continue;
                    if (address.isLoopbackAddress() || address.isLinkLocalAddress()) continue;
                    if (candidate.getNetworkPrefixLength() <= 0) continue;
                    return candidate;
                }
            }
        } catch (Exception ignored) {
            // Treated as "no network" by the caller.
        }
        return null;
    }

    private static int toInt(byte[] address) {
        return ((address[0] & 0xFF) << 24) | ((address[1] & 0xFF) << 16) | ((address[2] & 0xFF) << 8) | (address[3] & 0xFF);
    }

    private static String toIpv4(int value) {
        return ((value >>> 24) & 0xFF) + "." + ((value >>> 16) & 0xFF) + "." + ((value >>> 8) & 0xFF) + "." + (value & 0xFF);
    }

    @Override
    protected void handleOnDestroy() {
        executor.shutdown();
        scanExecutor.shutdown();
        timeoutExecutor.shutdown();
        super.handleOnDestroy();
    }
}
