package logisimmcp;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Localhost-only framed wire protocol + mutual HMAC-SHA256 authentication.
 * Frame format: 4-byte unsigned big-endian payload length + UTF-8 JSON bytes.
 */
public final class WireProtocol {
    public static final int PROTOCOL_VERSION = 2;
    public static final String SERVER_VERSION = "2.0.0";
    public static final int MAX_FRAME_BYTES = 32 * 1024 * 1024;
    private static final int HANDSHAKE_TIMEOUT_MS = 10000;
    private static final SecureRandom RNG = new SecureRandom();

    private WireProtocol() {}

    public static File getStateDir() {
        String custom = System.getenv("LOGISIM_MCP_DIR");
        File dir = (custom != null && !custom.trim().isEmpty())
                ? new File(custom.trim())
                : new File(System.getProperty("user.home"), ".logisim-mcp");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static File getKeyFile() {
        return new File(getStateDir(), "bridge.key");
    }

    public static File getDiscoveryFile() {
        return new File(getStateDir(), "bridge.json");
    }

    public static synchronized byte[] loadOrCreateKey() throws IOException {
        File keyFile = getKeyFile();
        if (keyFile.exists() && keyFile.isFile()) {
            String raw = new String(Files.readAllBytes(keyFile.toPath()), StandardCharsets.UTF_8).trim();
            if (isValidHexKey(raw)) {
                return hexToBytes(raw);
            }
        }
        byte[] key = new byte[32];
        RNG.nextBytes(key);
        String hex = bytesToHex(key);
        File tmp = new File(getStateDir(), "bridge.key.tmp." + System.nanoTime());
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write((hex + "\n").getBytes(StandardCharsets.UTF_8));
        }
        restrictOwnerOnly(tmp);
        Files.move(tmp.toPath(), keyFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        restrictOwnerOnly(keyFile);
        return key;
    }

    public static void writeDiscoveryFile(int port) throws IOException {
        Map<String, Object> info = new LinkedHashMap<String, Object>();
        info.put("protocol", Integer.valueOf(PROTOCOL_VERSION));
        info.put("version", SERVER_VERSION);
        info.put("host", "127.0.0.1");
        info.put("port", Integer.valueOf(port));
        info.put("pid", Long.valueOf(getCurrentPid()));
        info.put("process", ManagementFactory.getRuntimeMXBean().getName());
        File infoFile = getDiscoveryFile();
        File tmp = new File(getStateDir(), "bridge.json.tmp." + System.nanoTime());
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(Json.stringify(info).getBytes(StandardCharsets.UTF_8));
        }
        restrictOwnerOnly(tmp);
        Files.move(tmp.toPath(), infoFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        restrictOwnerOnly(infoFile);
    }

    public static void deleteDiscoveryFile() {
        try {
            File f = getDiscoveryFile();
            if (f.exists()) f.delete();
        } catch (Throwable ignored) {}
    }

    private static void restrictOwnerOnly(File f) {
        try {
            f.setReadable(false, false);
            f.setReadable(true, true);
            f.setWritable(false, false);
            f.setWritable(true, true);
            f.setExecutable(false, false);
        } catch (Throwable ignored) {}
    }

    private static long getCurrentPid() {
        try {
            String name = ManagementFactory.getRuntimeMXBean().getName();
            int idx = name.indexOf('@');
            if (idx > 0) return Long.parseLong(name.substring(0, idx));
        } catch (Throwable ignored) {}
        return -1L;
    }

    public static void writeFrame(OutputStream out, byte[] payload) throws IOException {
        if (payload.length > MAX_FRAME_BYTES) {
            throw new IOException("Outgoing frame exceeds MAX_FRAME_BYTES");
        }
        int len = payload.length;
        out.write((len >>> 24) & 0xFF);
        out.write((len >>> 16) & 0xFF);
        out.write((len >>> 8) & 0xFF);
        out.write(len & 0xFF);
        out.write(payload);
        out.flush();
    }

    public static void writeJsonFrame(OutputStream out, Object obj) throws IOException {
        writeFrame(out, Json.stringify(obj).getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] readFrame(InputStream in) throws IOException {
        DataInputStream din = (in instanceof DataInputStream) ? (DataInputStream) in : new DataInputStream(in);
        int b0 = din.read();
        if (b0 < 0) return null; // Clean EOF before header
        int b1 = din.read();
        int b2 = din.read();
        int b3 = din.read();
        if ((b1 | b2 | b3) < 0) {
            throw new EOFException("Truncated frame header");
        }
        int len = (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
        if (len <= 0 || len > MAX_FRAME_BYTES) {
            throw new IOException("Invalid frame length: " + len);
        }
        byte[] buf = new byte[len];
        din.readFully(buf);
        return buf;
    }

    public static boolean performServerHandshake(Socket sock, byte[] key, DataInputStream in, OutputStream out) throws IOException {
        sock.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
        byte[] nonceSBytes = new byte[32];
        RNG.nextBytes(nonceSBytes);
        String nonceS = bytesToHex(nonceSBytes);

        Map<String, Object> challenge = new LinkedHashMap<String, Object>();
        challenge.put("op", "challenge");
        challenge.put("protocol", Integer.valueOf(PROTOCOL_VERSION));
        challenge.put("version", SERVER_VERSION);
        challenge.put("nonce_s", nonceS);
        writeJsonFrame(out, challenge);

        byte[] replyBytes = readFrame(in);
        if (replyBytes == null) {
            return false;
        }
        Map<String, Object> reply;
        try {
            reply = Json.parseObject(new String(replyBytes, StandardCharsets.UTF_8));
        } catch (Exception ex) {
            sendAuthError(out, "Malformed auth frame");
            return false;
        }

        String op = reply.get("op") instanceof String ? (String) reply.get("op") : "";
        String nonceC = reply.get("nonce_c") instanceof String ? (String) reply.get("nonce_c") : "";
        String proofC = reply.get("proof_c") instanceof String ? (String) reply.get("proof_c") : "";
        if (!"auth".equals(op) || nonceC.length() < 32 || nonceC.length() > 128 || !isHexString(nonceC) || proofC.length() != 64 || !isHexString(proofC)) {
            sendAuthError(out, "Invalid auth parameters");
            return false;
        }

        String expectedProofC = hmacSha256Hex(key, "logisim-mcp:c2s:" + nonceS + ":" + nonceC);
        if (!constantTimeEquals(proofC.toLowerCase(), expectedProofC)) {
            sendAuthError(out, "Unauthorized");
            return false;
        }

        String proofS = hmacSha256Hex(key, "logisim-mcp:s2c:" + nonceC + ":" + nonceS);
        Map<String, Object> ok = new LinkedHashMap<String, Object>();
        ok.put("op", "auth_ok");
        ok.put("protocol", Integer.valueOf(PROTOCOL_VERSION));
        ok.put("version", SERVER_VERSION);
        ok.put("proof_s", proofS);
        writeJsonFrame(out, ok);
        return true;
    }

    private static void sendAuthError(OutputStream out, String msg) {
        try {
            Map<String, Object> err = new LinkedHashMap<String, Object>();
            err.put("op", "auth_error");
            err.put("error", msg);
            writeJsonFrame(out, err);
        } catch (IOException ignored) {}
    }

    public static String hmacSha256Hex(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isValidHexKey(String s) {
        return s.length() == 64 && isHexString(s);
    }

    private static boolean isHexString(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return !s.isEmpty();
    }

    private static byte[] hexToBytes(String s) {
        int len = s.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return out;
    }

    public static String bytesToHex(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hex[i * 2] = digits[v >>> 4];
            hex[i * 2 + 1] = digits[v & 0x0F];
        }
        return new String(hex);
    }
}
