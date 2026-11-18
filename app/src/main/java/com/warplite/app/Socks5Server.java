package com.warplite.app;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The bridge of the 1.1.1.1 mode: a small SOCKS5 server (no authentication, loopback only) that answers DNS.
 *
 * <pre>
 *   any app ─ DNS (port 53) ─► Magic V2Ray ──► socks://127.0.0.1:5353 ──► this server ──► DoH ──► Cloudflare
 * </pre>
 *
 * Magic V2Ray routes only port 53 here (see WarpOverrides), so this server does one thing:
 * <ul>
 *   <li>UDP ASSOCIATE: every datagram to port 53 is one DNS query; the answer comes back wrapped in a SOCKS5 UDP
 *       header carrying the ORIGINAL destination, so the client never notices that the resolver was replaced;</li>
 *   <li>CONNECT to port 53: DNS over TCP (2-byte length + message), each message is answered the same way;</li>
 *   <li>anything else (other ports) is refused / dropped: it is not meant to arrive here.</li>
 * </ul>
 * The {@link DnsResolver} does the real work (DoH in the app, a fake in the tests). When it fails the client gets a
 * SERVFAIL at once. Like magic-dns-rewrite, but the destination is not rewritten to another DNS server: the query is
 * resolved over HTTPS. No Android dependency.
 */
final class Socks5Server {

    /** Turns one DNS query (wire format) into its answer. May block; called from pool threads. */
    interface DnsResolver {
        byte[] resolve(byte[] query) throws IOException;
    }

    interface Log {
        void line(String s);
    }

    private static final int MAX_INFLIGHT = 128;

    private final int port;
    private final DnsResolver resolver;
    private final Log log;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final AtomicInteger inflight = new AtomicInteger();
    private volatile boolean running;
    private ServerSocket server;

    Socks5Server(int port, DnsResolver resolver, Log log) {
        this.port = port;
        this.resolver = resolver;
        this.log = log;
    }

    void start() throws IOException {
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        server = s;
        running = true;
        pool.execute(this::acceptLoop);
    }

    /** The port really bound (useful when constructed with 0). */
    int port() {
        return server == null ? port : server.getLocalPort();
    }

    void stop() {
        running = false;
        closeQuietly(server);
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket c = server.accept();
                pool.execute(() -> handle(c));
            } catch (IOException e) {
                if (running) log.line("accept failed: " + e.getMessage());
                return;
            }
        }
    }

    // ------------------------------------------------------------------ protocol

    /** A SOCKS5 address as it arrived (kept raw so UDP answers can echo it back unchanged). */
    private static final class Addr {
        int atyp;
        byte[] raw;       // 4 / 16 address bytes, or the domain name bytes
        int port;

        String host() {
            if (atyp == 3) return new String(raw, StandardCharsets.UTF_8);
            try {
                return InetAddress.getByAddress(raw).getHostAddress();
            } catch (IOException e) {
                return "?";
            }
        }

        byte[] encode() {
            int n = atyp == 3 ? 1 + raw.length : raw.length;
            byte[] b = new byte[1 + n + 2];
            b[0] = (byte) atyp;
            int o = 1;
            if (atyp == 3) b[o++] = (byte) raw.length;
            System.arraycopy(raw, 0, b, o, raw.length);
            o += raw.length;
            b[o] = (byte) (port >> 8);
            b[o + 1] = (byte) port;
            return b;
        }
    }

    private static Addr readAddr(DataInputStream in) throws IOException {
        Addr a = new Addr();
        a.atyp = in.readUnsignedByte();
        switch (a.atyp) {
            case 1: a.raw = new byte[4]; break;
            case 4: a.raw = new byte[16]; break;
            case 3: a.raw = new byte[in.readUnsignedByte()]; break;
            default: throw new IOException("bad address type " + a.atyp);
        }
        in.readFully(a.raw);
        a.port = in.readUnsignedShort();
        return a;
    }

    private static void reply(OutputStream out, int code, int bindPort) throws IOException {
        out.write(new byte[]{5, (byte) code, 0, 1, 127, 0, 0, 1, (byte) (bindPort >> 8), (byte) bindPort});
        out.flush();
    }

    private void handle(Socket c) {
        try {
            c.setSoTimeout(30_000);
            DataInputStream in = new DataInputStream(c.getInputStream());
            OutputStream out = c.getOutputStream();

            if (in.readUnsignedByte() != 5) return;
            in.readFully(new byte[in.readUnsignedByte()]);          // offered methods: we only do "no auth"
            out.write(new byte[]{5, 0});
            out.flush();

            if (in.readUnsignedByte() != 5) return;
            int cmd = in.readUnsignedByte();
            in.readUnsignedByte();                                  // reserved
            Addr dst = readAddr(in);
            c.setSoTimeout(0);

            if (cmd == 1) {
                if (dst.port == 53) dnsOverTcp(in, out, dst);
                else {
                    log.line("refused TCP " + dst.host() + ":" + dst.port + " (the bridge only does DNS)");
                    reply(out, 2, 0);                               // connection not allowed by ruleset
                }
            } else if (cmd == 3) {
                udpAssociate(c, in, out);
            } else {
                reply(out, 7, 0);                                   // command not supported
            }
        } catch (IOException ignored) {
            // client went away
        } finally {
            closeQuietly(c);
        }
    }

    // ----------------------------------------------------------------------- DNS

    /** Resolves one query; on failure builds a SERVFAIL so the client does not wait for a timeout. */
    private byte[] answer(String proto, byte[] q, int len) {
        byte[] query = java.util.Arrays.copyOf(q, len);
        String what = DnsMessage.describe(query, len);
        try {
            byte[] r = resolver.resolve(query);
            log.line("DoH/" + proto + "  " + what);
            return r;
        } catch (IOException | RuntimeException e) {
            log.line("DoH/" + proto + "  " + what + "  FAILED: " + e.getMessage());
            return DnsMessage.servfail(query, len);
        }
    }

    // ----------------------------------------------------------------------- TCP

    private void dnsOverTcp(DataInputStream in, OutputStream out, Addr dst) throws IOException {
        reply(out, 0, 0);
        try {
            while (true) {
                int len = in.readUnsignedShort();
                if (len < 12) return;
                byte[] q = new byte[len];
                in.readFully(q);
                byte[] r = answer("tcp", q, len);
                if (r.length == 0) return;
                byte[] frame = new byte[2 + r.length];
                frame[0] = (byte) (r.length >> 8);
                frame[1] = (byte) r.length;
                System.arraycopy(r, 0, frame, 2, r.length);
                out.write(frame);
                out.flush();
            }
        } catch (EOFException done) {
            // client closed the connection
        }
    }

    // ----------------------------------------------------------------------- UDP

    private void udpAssociate(final Socket control, final DataInputStream in, OutputStream out) throws IOException {
        final DatagramSocket relay = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        reply(out, 0, relay.getLocalPort());

        // The association lives as long as the TCP control connection.
        pool.execute(() -> {
            try {
                while (in.read() >= 0) { /* ignore */ }
            } catch (IOException ignored) {
            } finally {
                closeQuietly(relay);
            }
        });

        InetAddress clientAddr = null;
        int clientPort = -1;
        byte[] buf = new byte[65535];
        try {
            while (true) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                relay.receive(p);
                if (clientAddr == null) {
                    clientAddr = p.getAddress();
                    clientPort = p.getPort();
                } else if (!p.getAddress().equals(clientAddr) || p.getPort() != clientPort) {
                    continue;                                       // not our client
                }
                query(relay, p.getData(), p.getLength(), clientAddr, clientPort);
            }
        } catch (IOException ignored) {                             // relay closed: the association ended
        } finally {
            closeQuietly(relay);
            closeQuietly(control);
        }
    }

    /** One SOCKS5 UDP datagram: header + DNS query. Answered on a pool thread. */
    private void query(final DatagramSocket relay, byte[] d, int len, final InetAddress clientAddr, final int clientPort) {
        try {
            if (len < 10 || d[2] != 0) return;                      // too short, or fragmented (unsupported)
            DataInputStream hdr = new DataInputStream(new ByteArrayInputStream(d, 3, len - 3));
            final Addr dst = readAddr(hdr);
            int offset = len - hdr.available();
            if (dst.port != 53 || len - offset < 12) return;        // not DNS: not meant for us, UDP so just drop
            if (inflight.incrementAndGet() > MAX_INFLIGHT) {
                inflight.decrementAndGet();
                return;
            }
            final byte[] q = java.util.Arrays.copyOfRange(d, offset, len);
            final byte[] head = dst.encode();
            pool.execute(() -> {
                try {
                    byte[] r = answer("udp", q, q.length);
                    if (r.length == 0) return;
                    byte[] out = new byte[3 + head.length + r.length];
                    System.arraycopy(head, 0, out, 3, head.length);
                    System.arraycopy(r, 0, out, 3 + head.length, r.length);
                    relay.send(new DatagramPacket(out, out.length, clientAddr, clientPort));
                } catch (IOException ignored) {
                    // relay closed meanwhile
                } finally {
                    inflight.decrementAndGet();
                }
            });
        } catch (IOException ignored) {                             // bad packet: UDP, so drop it
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        try {
            if (c != null) c.close();
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(DatagramSocket s) {
        if (s != null) s.close();
    }
}
