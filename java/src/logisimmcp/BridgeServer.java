package logisimmcp;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Localhost-only TCP bridge server with framed JSON and mutual HMAC-SHA256 auth.
 */
public final class BridgeServer {
    private static final int IDLE_TIMEOUT_MS = 300000;
    private static volatile BridgeServer instance;

    private final byte[] secretKey;
    private final ServerSocket serverSocket;
    private final ExecutorService workers;
    private volatile boolean running = true;

    private BridgeServer() throws IOException {
        this.secretKey = WireProtocol.loadOrCreateKey();
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        this.serverSocket = new ServerSocket(0, 16, loopback);
        this.workers = Executors.newCachedThreadPool(new ThreadFactory() {
            private int seq = 1;
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "LogisimMCP-Worker-" + (seq++));
                t.setDaemon(true);
                return t;
            }
        });
        WireProtocol.writeDiscoveryFile(serverSocket.getLocalPort());
    }

    public static synchronized void ensureStarted() {
        if (instance != null) return;
        try {
            instance = new BridgeServer();
            Thread acceptThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    instance.acceptLoop();
                }
            }, "LogisimMCP-Acceptor");
            acceptThread.setDaemon(true);
            acceptThread.start();
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    shutdown();
                }
            }, "LogisimMCP-Shutdown"));
        } catch (Throwable t) {
            System.err.println("[logisim-mcp] Failed to start bridge server: " + t.getMessage());
        }
    }

    public static void shutdown() {
        BridgeServer s = instance;
        if (s != null) {
            s.running = false;
            try { s.serverSocket.close(); } catch (IOException ignored) {}
            s.workers.shutdownNow();
            WireProtocol.deleteDiscoveryFile();
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket client = serverSocket.accept();
                workers.submit(new Runnable() {
                    @Override
                    public void run() {
                        handleClient(client);
                    }
                });
            } catch (IOException e) {
                if (running) {
                    System.err.println("[logisim-mcp] Accept error: " + e.getMessage());
                }
            }
        }
    }

    private void handleClient(Socket sock) {
        try {
            sock.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(new BufferedInputStream(sock.getInputStream()));
            OutputStream out = new BufferedOutputStream(sock.getOutputStream());

            if (!WireProtocol.performServerHandshake(sock, secretKey, in, out)) {
                return;
            }

            sock.setSoTimeout(IDLE_TIMEOUT_MS);
            while (running) {
                byte[] frame;
                try {
                    frame = WireProtocol.readFrame(in);
                } catch (SocketTimeoutException timeout) {
                    return; // Idle keepalive expired cleanly
                }
                if (frame == null) {
                    return; // Client closed cleanly
                }

                Object id = null;
                Map<String, Object> resp = new LinkedHashMap<String, Object>();
                try {
                    String raw = new String(frame, StandardCharsets.UTF_8);
                    Map<String, Object> req = Json.parseObject(raw);
                    id = req.get("id");
                    Object cmdObj = req.get("cmd");
                    if (!(cmdObj instanceof String) || ((String) cmdObj).trim().isEmpty()) {
                        throw new IllegalArgumentException("Missing 'cmd'");
                    }
                    String cmd = (String) cmdObj;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> params = (req.get("params") instanceof Map<?, ?>)
                            ? (Map<String, Object>) req.get("params")
                            : new LinkedHashMap<String, Object>();

                    Object result = Commands.dispatch(cmd, params);
                    resp.put("id", id);
                    resp.put("ok", Boolean.TRUE);
                    resp.put("result", result);
                } catch (Throwable t) {
                    resp.put("id", id);
                    resp.put("ok", Boolean.FALSE);
                    String msg = t.getMessage();
                    if (msg == null || msg.isEmpty()) msg = t.getClass().getSimpleName();
                    resp.put("error", msg);
                }

                WireProtocol.writeJsonFrame(out, resp);
            }
        } catch (IOException ignored) {
            // Connection terminated
        } finally {
            try { sock.close(); } catch (IOException ignored) {}
        }
    }
}
