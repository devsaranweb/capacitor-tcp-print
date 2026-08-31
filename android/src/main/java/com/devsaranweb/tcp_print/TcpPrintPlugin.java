package com.devsaranweb.tcp_print;

import android.util.Base64;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stateless raw-TCP print transport (RAW / JetDirect, typically port 9100).
 *
 * One call = one socket: connect, write, flush, close. No session state is
 * kept — see the TS definitions for why. Socket I/O runs on a dedicated
 * single-thread executor so a slow printer never blocks Capacitor's shared
 * plugin handler thread (and the single thread ALSO serializes concurrent
 * calls from the bridge side, defence in depth under the hosts' own I/O
 * rails).
 *
 * Error codes are the plugin's API — hosts map them to translated copy, so
 * they must stay stable: INVALID_ARGS / CONNECT_FAILED / TIMEOUT /
 * WRITE_FAILED.
 */
@CapacitorPlugin(name = "TcpPrint")
public class TcpPrintPlugin extends Plugin {

    static final String CODE_INVALID_ARGS = "INVALID_ARGS";
    static final String CODE_CONNECT_FAILED = "CONNECT_FAILED";
    static final String CODE_TIMEOUT = "TIMEOUT";
    static final String CODE_WRITE_FAILED = "WRITE_FAILED";

    private static final int DEFAULT_TIMEOUT_MS = 5000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
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

    @Override
    protected void handleOnDestroy() {
        executor.shutdown();
        timeoutExecutor.shutdown();
        super.handleOnDestroy();
    }
}
