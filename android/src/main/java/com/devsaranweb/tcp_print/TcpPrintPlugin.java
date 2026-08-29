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
            // The connect phase writes nothing, so distinguishing it from the
            // write phase is what lets hosts retry a CONNECT_FAILED safely
            // while treating WRITE_FAILED as "maybe half-printed".
            try (Socket socket = new Socket()) {
                try {
                    socket.connect(new InetSocketAddress(targetHost, targetPort), deadline);
                } catch (SocketTimeoutException e) {
                    call.reject("Connect to " + targetHost + ":" + targetPort + " timed out", CODE_TIMEOUT);
                    return;
                } catch (Exception e) {
                    call.reject(
                        "Could not connect to " + targetHost + ":" + targetPort + ": " + e.getMessage(),
                        CODE_CONNECT_FAILED
                    );
                    return;
                }
                try {
                    // SO_TIMEOUT bounds reads; writes block on the send buffer
                    // instead, which a powered-off-mid-job printer can wedge —
                    // the send buffer (typically >64KB, receipts are a few KB)
                    // usually absorbs the whole job, so a hard wedge here is
                    // rare and the host's own print timeout is the backstop.
                    socket.setSoTimeout(deadline);
                    OutputStream out = socket.getOutputStream();
                    out.write(bytes);
                    out.flush();
                } catch (Exception e) {
                    call.reject("Write to printer failed: " + e.getMessage(), CODE_WRITE_FAILED);
                    return;
                }
                call.resolve();
            } catch (Exception e) {
                // Socket close failure after a successful write — the job is
                // on the wire; do not fail a print that happened.
                call.resolve();
            }
        });
    }

    @Override
    protected void handleOnDestroy() {
        executor.shutdown();
        super.handleOnDestroy();
    }
}
