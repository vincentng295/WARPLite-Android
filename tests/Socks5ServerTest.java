package com.warplite.app;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Host-side test of the bridge (plain JDK): a real SOCKS5 client talks to Socks5Server on a loopback port; the
 * resolver is a fake that records the queries. Checks DNS over UDP ASSOCIATE (answer wrapped with the ORIGINAL
 * destination), DNS over TCP CONNECT, SERVFAIL when the resolver fails, and that non-DNS is refused / dropped.
 */
public class Socks5ServerTest {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
        if (!ok) failures++;
    }

    private static final CopyOnWriteArrayList<String> LOG = new CopyOnWriteArrayList<>();
    private static volatile boolean failResolver;
    private static final CopyOnWriteArrayList<byte[]> SEEN = new CopyOnWriteArrayList<>();

    /** The "DoH": returns the query with QR set and a marker byte appended. */
    private static final Socks5Server.DnsResolver FAKE = q -> {
        SEEN.add(q);
        if (failResolver) throw new IOException("boom");
        byte[] r = Arrays.copyOf(q, q.length + 1);
        r[2] |= (byte) 0x80;
        r[q.length] = 0x42;
        return r;
    };

    /** id 0x1234, www.example.com A */
    private static byte[] query(int id) {
        byte[] q = {0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 3, 'w', 'w', 'w', 7, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 3, 'c', 'o', 'm', 0, 0, 1, 0, 1};
        q[0] = (byte) (id >> 8);
        q[1] = (byte) id;
        return q;
    }

    private static DataInputStream handshake(Socket s) throws IOException {
        OutputStream out = s.getOutputStream();
        DataInputStream in = new DataInputStream(s.getInputStream());
        out.write(new byte[]{5, 1, 0});
        byte[] m = new byte[2];
        in.readFully(m);
        if (m[0] != 5 || m[1] != 0) throw new IOException("handshake");
        return in;
    }

    /** Sends a request with a v4 destination, returns the reply code and the bound port. */
    private static int[] request(Socket s, DataInputStream in, int cmd, String ip, int port) throws IOException {
        byte[] a = InetAddress.getByName(ip).getAddress();
        s.getOutputStream().write(new byte[]{5, (byte) cmd, 0, 1, a[0], a[1], a[2], a[3], (byte) (port >> 8), (byte) port});
        byte[] r = new byte[10];
        in.readFully(r);
        return new int[]{r[1], ((r[8] & 0xff) << 8) | (r[9] & 0xff)};
    }

    public static void main(String[] args) throws Exception {
        Socks5Server server = new Socks5Server(0, FAKE, LOG::add);
        server.start();
        int port = server.port();

        // ---- UDP ASSOCIATE: the answer comes back from the ORIGINAL destination (8.8.8.8:53)
        try (Socket ctl = new Socket("127.0.0.1", port); DatagramSocket udp = new DatagramSocket()) {
            udp.setSoTimeout(3000);
            DataInputStream in = handshake(ctl);
            int[] rep = request(ctl, in, 3, "0.0.0.0", 0);
            check(rep[0] == 0 && rep[1] > 0, "UDP ASSOCIATE accepted");
            byte[] q = query(0x1234);
            byte[] frame = new byte[10 + q.length];
            frame[3] = 1;
            frame[4] = 8; frame[5] = 8; frame[6] = 8; frame[7] = 8;
            frame[8] = 0; frame[9] = 53;
            System.arraycopy(q, 0, frame, 10, q.length);
            udp.send(new DatagramPacket(frame, frame.length, InetAddress.getByName("127.0.0.1"), rep[1]));
            byte[] buf = new byte[2048];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            udp.receive(p);
            check(buf[3] == 1 && buf[4] == 8 && buf[5] == 8 && buf[6] == 8 && buf[7] == 8 && buf[9] == 53,
                    "UDP answer carries the original destination 8.8.8.8:53");
            int off = 10;
            check(p.getLength() == off + q.length + 1 && buf[off] == 0x12 && buf[off + 1] == 0x34 && (buf[off + 2] & 0x80) != 0
                    && buf[p.getLength() - 1] == 0x42, "UDP answer is the resolver's answer (id kept, QR set)");
            check(SEEN.size() == 1 && Arrays.equals(SEEN.get(0), q), "resolver got exactly the DNS payload");

            // not DNS (port 443): dropped
            frame[9] = (byte) 0xbb; frame[8] = 1;
            udp.send(new DatagramPacket(frame, frame.length, InetAddress.getByName("127.0.0.1"), rep[1]));
            udp.setSoTimeout(500);
            boolean silent = false;
            try {
                udp.receive(new DatagramPacket(new byte[2048], 2048));
            } catch (java.net.SocketTimeoutException e) {
                silent = true;
            }
            check(silent && SEEN.size() == 1, "UDP to another port is dropped, never resolved");

            // resolver failure: SERVFAIL
            failResolver = true;
            udp.setSoTimeout(3000);
            frame[8] = 0; frame[9] = 53;
            udp.send(new DatagramPacket(frame, frame.length, InetAddress.getByName("127.0.0.1"), rep[1]));
            p = new DatagramPacket(buf, buf.length);
            udp.receive(p);
            check((buf[off + 2] & 0x80) != 0 && (buf[off + 3] & 0x0f) == 2, "resolver failure -> SERVFAIL answer");
            failResolver = false;
        }

        // ---- TCP CONNECT to port 53: DNS over TCP, two queries on one connection
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(3000);
            DataInputStream in = handshake(s);
            int[] rep = request(s, in, 1, "1.2.3.4", 53);
            check(rep[0] == 0, "CONNECT to :53 accepted");
            for (int id = 0x0a01; id <= 0x0a02; id++) {
                byte[] q = query(id);
                byte[] f = new byte[2 + q.length];
                f[0] = 0; f[1] = (byte) q.length;
                System.arraycopy(q, 0, f, 2, q.length);
                s.getOutputStream().write(f);
                int len = in.readUnsignedShort();
                byte[] r = new byte[len];
                in.readFully(r);
                check(len == q.length + 1 && (r[0] & 0xff) == (id >> 8) && (r[1] & 0xff) == (id & 0xff) && r[len - 1] == 0x42,
                        "DNS over TCP answer for id " + Integer.toHexString(id));
            }
        }

        // ---- TCP CONNECT elsewhere: refused
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(3000);
            DataInputStream in = handshake(s);
            int[] rep = request(s, in, 1, "1.2.3.4", 443);
            check(rep[0] == 2, "CONNECT to :443 refused (the bridge only does DNS)");
        }

        check(LOG.stream().anyMatch(l -> l.contains("DoH/udp  www.example.com A")), "log names the query: " + LOG.get(0));
        server.stop();

        System.out.println(failures == 0 ? "\nall bridge tests passed" : "\n" + failures + " FAILED");
        if (failures > 0) System.exit(1);
    }
}
