import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Development-only Minecraft protocol load-test client for Gina-Hub.
 *
 * Speaks just enough of the wire protocol (handshake -> login -> configuration -> play)
 * to become a real tracked player: full login, movement, chunk receipt, keep-alive,
 * teleport-ack, and periodic chat/commands. Everything not required to progress the
 * connection or drive the benchmark is skipped by length rather than parsed, since
 * packet framing gives an exact byte length regardless of payload semantics.
 *
 * NOT shipped in the production jar. Run manually, e.g.:
 *   java GinaLoadBot <host> <port> <botCount> <durationSeconds> [rampSeconds]
 */
public class GinaLoadBot {

    // Play-state serverbound packet IDs (0-indexed registration order in GameProtocols.SERVERBOUND_TEMPLATE)
    static final int SB_ACCEPT_TELEPORTATION = 0;
    static final int SB_CHAT_COMMAND = 6;
    static final int SB_CHAT = 8;
    static final int SB_CLIENT_INFORMATION = 13;
    static final int SB_CONFIGURATION_ACKNOWLEDGED = 15;
    static final int SB_KEEP_ALIVE = 27;
    static final int SB_MOVE_PLAYER_POS = 29;
    static final int SB_MOVE_PLAYER_POS_ROT = 30;
    static final int SB_PLAYER_LOADED = 43;

    // Play-state clientbound packet IDs
    static final int CB_KEEP_ALIVE = 43;
    static final int CB_LEVEL_CHUNK_WITH_LIGHT = 44;
    static final int CB_LOGIN = 48;
    static final int CB_PLAYER_POSITION = 70;
    static final int CB_DISCONNECT = 32;

    // Configuration-state serverbound
    static final int CFG_SB_CLIENT_INFORMATION = 0;
    static final int CFG_SB_COOKIE_RESPONSE = 1;
    static final int CFG_SB_FINISH_CONFIGURATION = 3;
    static final int CFG_SB_KEEP_ALIVE = 4;
    static final int CFG_SB_PONG = 5;
    static final int CFG_SB_SELECT_KNOWN_PACKS = 7;

    // Configuration-state clientbound
    static final int CFG_CB_COOKIE_REQUEST = 0;
    static final int CFG_CB_DISCONNECT = 2;
    static final int CFG_CB_FINISH_CONFIGURATION = 3;
    static final int CFG_CB_KEEP_ALIVE = 4;
    static final int CFG_CB_PING = 5;
    static final int CFG_CB_REGISTRY_DATA = 7;
    static final int CFG_CB_SELECT_KNOWN_PACKS = 14;

    static final int PROTOCOL_VERSION = 774;
    static final boolean DEBUG = Boolean.getBoolean("gina.debug");

    static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x ", x));
        return sb.toString();
    }

    static final AtomicInteger connected = new AtomicInteger();
    static final AtomicInteger loggedIn = new AtomicInteger();
    static final AtomicInteger failed = new AtomicInteger();
    static final AtomicLong chunksReceived = new AtomicLong();
    static final AtomicLong packetsReceived = new AtomicLong();
    static final AtomicLong bytesReceived = new AtomicLong();
    static final AtomicLong bytesSent = new AtomicLong();

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 25565;
        int botCount = args.length > 2 ? Integer.parseInt(args[2]) : 10;
        int durationSeconds = args.length > 3 ? Integer.parseInt(args[3]) : 60;
        int rampSeconds = args.length > 4 ? Integer.parseInt(args[4]) : Math.max(1, botCount / 20);

        System.out.printf("GinaLoadBot: connecting %d bots to %s:%d over %ds, running %ds%n",
            botCount, host, port, rampSeconds, durationSeconds);

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < botCount; i++) {
            final int idx = i;
            Thread t = new Thread(() -> runBot(host, port, idx, durationSeconds), "gina-bot-" + i);
            t.setDaemon(true);
            threads.add(t);
            t.start();
            if (botCount > 1) {
                Thread.sleep(Math.max(1, (rampSeconds * 1000L) / botCount));
            }
        }

        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < (rampSeconds + durationSeconds + 15) * 1000L) {
            Thread.sleep(5000);
            System.out.printf("[status +%ds] connected=%d loggedIn=%d failed=%d chunksRecv=%d packetsRecv=%d bytesRecv=%d bytesSent=%d%n",
                (System.currentTimeMillis() - start) / 1000, connected.get(), loggedIn.get(), failed.get(),
                chunksReceived.get(), packetsReceived.get(), bytesReceived.get(), bytesSent.get());
            boolean anyAlive = threads.stream().anyMatch(Thread::isAlive);
            if (!anyAlive) break;
        }

        System.out.printf("FINAL: connected=%d loggedIn=%d failed=%d chunksRecv=%d packetsRecv=%d bytesRecv=%d bytesSent=%d%n",
            connected.get(), loggedIn.get(), failed.get(), chunksReceived.get(), packetsReceived.get(), bytesReceived.get(), bytesSent.get());
    }

    static void runBot(String host, int port, int index, int durationSeconds) {
        String username = "GinaBot" + index;
        try (Socket socket = new Socket(host, port)) {
            socket.setTcpNoDelay(true);
            Conn c = new Conn(socket);
            connected.incrementAndGet();

            // Handshake (state=HANDSHAKING, only packet, id=0)
            ByteArrayOutputStream hs = new ByteArrayOutputStream();
            writeVarInt(hs, PROTOCOL_VERSION);
            writeUtf(hs, host);
            hs.write((port >> 8) & 0xFF);
            hs.write(port & 0xFF);
            writeVarInt(hs, 2); // intention = LOGIN
            c.sendRaw(0, hs.toByteArray());

            // Login start (state=LOGIN, id=0)
            UUID offlineUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream hello = new ByteArrayOutputStream();
            writeUtf(hello, username);
            writeUuid(hello, offlineUuid);
            c.send(0, hello.toByteArray());

            boolean loginDone = false;
            while (!loginDone) {
                Packet p = c.readPacket();
                if (p == null) throw new IOException("connection closed during login");
                switch (p.id) {
                    case 1 -> throw new IOException("server requested encryption (online-mode) - bot only supports offline-mode test servers");
                    case 2 -> { // LOGIN_FINISHED
                        c.send(3, new byte[0]); // LOGIN_ACKNOWLEDGED -> transitions to CONFIGURATION
                        loginDone = true;
                    }
                    case 3 -> { // LOGIN_COMPRESSION
                        int threshold = readVarInt(new ByteArrayInputStream(p.data));
                        c.compressionThreshold = threshold;
                    }
                    case 0 -> {
                        ByteArrayInputStream din = new ByteArrayInputStream(p.data);
                        int slen = readVarInt(din);
                        byte[] sbuf = new byte[slen];
                        din.read(sbuf);
                        throw new IOException("login disconnect: " + new String(sbuf, StandardCharsets.UTF_8));
                    }
                    default -> { /* custom query etc, ignore */ }
                }
            }

            // Configuration state
            runConfiguration(c);

            loggedIn.incrementAndGet();

            // Play state
            runPlay(c, durationSeconds);
        } catch (Exception e) {
            failed.incrementAndGet();
            System.err.println(username + " failed: " + e);
        }
    }

    static void runConfiguration(Conn c) throws IOException {
        // Send client information early, as real clients do
        ByteArrayOutputStream ci = new ByteArrayOutputStream();
        writeUtf(ci, "en_us");
        ci.write(8); // view distance
        writeVarInt(ci, 0); // chat visibility = FULL
        ci.write(1); // chat colors
        ci.write(0); // model customisation
        writeVarInt(ci, 0); // main hand = LEFT/RIGHT ordinal 0
        ci.write(0); // text filtering
        ci.write(0); // allow listing
        writeVarInt(ci, 0); // particle status
        c.send(CFG_SB_CLIENT_INFORMATION, ci.toByteArray());

        boolean finished = false;
        while (!finished) {
            Packet p = c.readPacket();
            if (p == null) throw new IOException("connection closed during configuration");
            InputStream in = new ByteArrayInputStream(p.data);
            switch (p.id) {
                case CFG_CB_SELECT_KNOWN_PACKS -> c.send(CFG_SB_SELECT_KNOWN_PACKS, new byte[]{0}); // empty known-pack list
                case CFG_CB_KEEP_ALIVE -> c.send(CFG_SB_KEEP_ALIVE, p.data); // echo the 8-byte long payload
                case CFG_CB_PING -> c.send(CFG_SB_PONG, p.data);
                case CFG_CB_COOKIE_REQUEST -> {
                    // reply with the same identifier and "no value" (empty optional)
                    ByteArrayOutputStream resp = new ByteArrayOutputStream();
                    resp.write(p.data, 0, findUtfEnd(p.data)); // identifier bytes as received
                    resp.write(0); // Optional.empty
                    c.send(CFG_SB_COOKIE_RESPONSE, resp.toByteArray());
                }
                case CFG_CB_FINISH_CONFIGURATION -> {
                    c.send(CFG_SB_FINISH_CONFIGURATION, new byte[0]);
                    finished = true;
                }
                case CFG_CB_DISCONNECT -> throw new IOException("configuration disconnect");
                default -> { /* registry data, tags, features, resource pack, server links, dialogs: not needed, skip */ }
            }
        }
    }

    static void runPlay(Conn c, int durationSeconds) throws IOException, InterruptedException {
        long endAt = System.currentTimeMillis() + durationSeconds * 1000L;
        double x = 0.5, y = 65.0, z = 0.5;
        float yaw = 0f, pitch = 0f;
        Random rnd = new Random();
        boolean spawned = false;
        long lastMove = 0;

        c.socket.setSoTimeout(2000);
        while (System.currentTimeMillis() < endAt) {
            Packet p;
            try {
                p = c.readPacket();
            } catch (java.net.SocketTimeoutException e) {
                p = null;
            }
            if (p != null) {
                packetsReceived.incrementAndGet();
                switch (p.id) {
                    case CB_KEEP_ALIVE -> c.send(SB_KEEP_ALIVE, p.data); // echo 8-byte long
                    case CB_LOGIN -> {
                        spawned = true;
                        c.send(SB_PLAYER_LOADED, new byte[0]);
                    }
                    case CB_PLAYER_POSITION -> {
                        if (DEBUG) System.err.println("CB_PLAYER_POSITION raw data (" + p.data.length + " bytes): " + bytesToHex(p.data));
                        int teleportId = readVarInt(new ByteArrayInputStream(p.data));
                        if (DEBUG) System.err.println("parsed teleportId=" + teleportId);
                        ByteArrayOutputStream ack = new ByteArrayOutputStream();
                        writeVarInt(ack, teleportId);
                        byte[] ackBytes = ack.toByteArray();
                        if (DEBUG) System.err.println("sending accept_teleportation payload (" + ackBytes.length + " bytes): " + bytesToHex(ackBytes));
                        c.send(SB_ACCEPT_TELEPORTATION, ackBytes);
                    }
                    case CB_LEVEL_CHUNK_WITH_LIGHT -> chunksReceived.incrementAndGet();
                    case CB_DISCONNECT -> throw new IOException("play disconnect");
                    default -> { /* everything else: entity tracking, tab, scoreboard, sounds etc - received, not parsed */ }
                }
            }

            long now = System.currentTimeMillis();
            if (spawned && now - lastMove > 200) {
                // Continuous walk-speed wander (~4.3 blocks/s max, well under vanilla's per-tick
                // movement-speed check) that periodically changes heading, so bots drift across
                // chunk boundaries realistically over several seconds rather than teleporting.
                if (rnd.nextInt(15) == 0) {
                    yaw = rnd.nextFloat() * 360f - 180f;
                }
                double stepBlocksPerTick = 0.8; // ~4 blocks/s at 200ms/tick, under walk speed cap
                double yawRad = Math.toRadians(yaw);
                x += -Math.sin(yawRad) * stepBlocksPerTick;
                z += Math.cos(yawRad) * stepBlocksPerTick;
                // benchmark platform is a 101x101 stone floor (x/z -50..50); keep a safety
                // margin so bots never walk off the edge into the void
                x = Math.max(-45, Math.min(45, x));
                z = Math.max(-45, Math.min(45, z));
                pitch = Math.max(-90, Math.min(90, pitch + rnd.nextInt(10) - 5));

                ByteArrayOutputStream mv = new ByteArrayOutputStream();
                writeDouble(mv, x);
                writeDouble(mv, y);
                writeDouble(mv, z);
                writeFloat(mv, yaw);
                writeFloat(mv, pitch);
                mv.write(0x01); // on ground flags byte
                c.send(SB_MOVE_PLAYER_POS_ROT, mv.toByteArray());
                lastMove = now;
            }
            // NOTE: chat/command stimulus is intentionally disabled. ServerboundChatCommandPacket's
            // signed-argument wire layout (signature tracking, per-argument signatures, last-seen
            // message acknowledgements) is nontrivial to reproduce exactly; an incorrect encoding
            // was found to corrupt server-side decoding of the packet, taking connections down. This
            // is a documented limitation of the harness (see benchmark/loadbot/README.md) rather than
            // a simulated behavior -- movement, keep-alive, teleport-ack and chunk receipt are real.
        }
    }

    static int findUtfEnd(byte[] data) {
        // cookie identifier is a namespaced key string; walk the varint-prefixed UTF and
        // return the byte offset right after it.
        int[] off = {0};
        readVarIntTracked(data, off);
        int len = readVarIntTracked(data, off);
        return off[0] + len;
    }

    static int readVarIntTracked(byte[] data, int[] off) {
        int result = 0, shift = 0;
        while (true) {
            byte b = data[off[0]++];
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        return result;
    }

    // ---- framing / connection ----

    static class Packet {
        int id;
        byte[] data;
    }

    static class Conn {
        final Socket socket;
        final DataInputStream in;
        final DataOutputStream out;
        int compressionThreshold = -1;

        Conn(Socket s) throws IOException {
            this.socket = s;
            this.in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 65536));
            this.out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 65536));
        }

        // send without compression consideration (used pre-compression-negotiation in login/handshake)
        void sendRaw(int id, byte[] payload) throws IOException {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeVarInt(body, id);
            body.write(payload);
            byte[] b = body.toByteArray();
            writeVarIntStream(out, b.length);
            out.write(b);
            out.flush();
            bytesSent.addAndGet(b.length);
        }

        // send respecting negotiated compression
        void send(int id, byte[] payload) throws IOException {
            if (DEBUG) System.err.println("SEND id=" + id + " payloadLen=" + payload.length + " threshold=" + compressionThreshold + " bytes=" + bytesToHex(payload));
            if (compressionThreshold < 0) {
                sendRaw(id, payload);
                return;
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeVarInt(body, id);
            body.write(payload);
            byte[] uncompressed = body.toByteArray();

            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            if (uncompressed.length < compressionThreshold) {
                writeVarInt(frame, 0); // dataLength = 0 => not compressed
                frame.write(uncompressed);
            } else {
                Deflater deflater = new Deflater();
                deflater.setInput(uncompressed);
                deflater.finish();
                byte[] buf = new byte[8192];
                ByteArrayOutputStream compressed = new ByteArrayOutputStream();
                while (!deflater.finished()) {
                    int n = deflater.deflate(buf);
                    compressed.write(buf, 0, n);
                }
                deflater.end();
                writeVarInt(frame, uncompressed.length);
                frame.write(compressed.toByteArray());
            }
            byte[] f = frame.toByteArray();
            writeVarIntStream(out, f.length);
            out.write(f);
            out.flush();
            bytesSent.addAndGet(f.length);
        }

        // read without compression consideration (login/handshake phase before compression turns on)
        Packet readRaw() throws IOException {
            int len = readVarIntStream(in);
            byte[] buf = new byte[len];
            in.readFully(buf);
            bytesReceived.addAndGet(len);
            ByteArrayInputStream bin = new ByteArrayInputStream(buf);
            int id = readVarInt(bin);
            Packet p = new Packet();
            p.id = id;
            p.data = bin.readAllBytes();
            return p;
        }

        Packet readPacket() throws IOException {
            int len = readVarIntStream(in);
            byte[] buf = new byte[len];
            in.readFully(buf);
            bytesReceived.addAndGet(len);
            byte[] payload;
            if (compressionThreshold >= 0) {
                ByteArrayInputStream bin = new ByteArrayInputStream(buf);
                int dataLength = readVarInt(bin);
                if (dataLength == 0) {
                    payload = bin.readAllBytes();
                } else {
                    byte[] compressed = bin.readAllBytes();
                    Inflater inflater = new Inflater();
                    inflater.setInput(compressed);
                    byte[] out = new byte[dataLength];
                    try {
                        inflater.inflate(out);
                    } catch (Exception e) {
                        throw new IOException("inflate failed", e);
                    }
                    inflater.end();
                    payload = out;
                }
            } else {
                payload = buf;
            }
            ByteArrayInputStream bin = new ByteArrayInputStream(payload);
            int id = readVarInt(bin);
            Packet p = new Packet();
            p.id = id;
            p.data = bin.readAllBytes();
            return p;
        }
    }

    // ---- primitive encode/decode helpers ----

    static void writeVarInt(OutputStream out, int value) throws IOException {
        while (true) {
            if ((value & ~0x7F) == 0) {
                out.write(value);
                return;
            }
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    static void writeVarIntStream(DataOutputStream out, int value) throws IOException {
        writeVarInt((OutputStream) out, value);
    }

    static int readVarInt(InputStream in) throws IOException {
        int result = 0, shift = 0;
        while (true) {
            int b = in.read();
            if (b < 0) throw new EOFException();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        return result;
    }

    static int readVarIntStream(DataInputStream in) throws IOException {
        return readVarInt((InputStream) in);
    }

    static void writeUtf(OutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, b.length);
        out.write(b);
    }

    static void writeUuid(OutputStream out, UUID uuid) throws IOException {
        writeLong(out, uuid.getMostSignificantBits());
        writeLong(out, uuid.getLeastSignificantBits());
    }

    static void writeLong(OutputStream out, long v) throws IOException {
        for (int i = 7; i >= 0; i--) out.write((int) (v >>> (i * 8)) & 0xFF);
    }

    static void writeDouble(OutputStream out, double d) throws IOException {
        writeLong(out, Double.doubleToLongBits(d));
    }

    static void writeFloat(OutputStream out, float f) throws IOException {
        int bits = Float.floatToIntBits(f);
        for (int i = 3; i >= 0; i--) out.write((bits >>> (i * 8)) & 0xFF);
    }
}
