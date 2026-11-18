package com.warplite.app;

/** The few bits of the DNS wire format (RFC 1035) the bridge needs. No Android dependency. */
final class DnsMessage {

    private DnsMessage() {
    }

    /** First question as "name TYPE" for the log, "?" when the packet is not a readable query. */
    static String describe(byte[] q, int len) {
        try {
            if (len < 13) return "?";
            StringBuilder name = new StringBuilder();
            int o = 12;
            while (o < len) {
                int l = q[o++] & 0xff;
                if (l == 0) break;
                if (l > 63 || o + l > len) return "?";
                if (name.length() > 0) name.append('.');
                for (int i = 0; i < l; i++) name.append((char) (q[o + i] & 0xff));
                o += l;
            }
            if (o + 2 > len) return "?";
            int type = ((q[o] & 0xff) << 8) | (q[o + 1] & 0xff);
            return (name.length() == 0 ? "." : name) + " " + typeName(type);
        } catch (RuntimeException e) {
            return "?";
        }
    }

    private static String typeName(int t) {
        switch (t) {
            case 1: return "A";
            case 28: return "AAAA";
            case 5: return "CNAME";
            case 15: return "MX";
            case 16: return "TXT";
            case 33: return "SRV";
            case 64: return "SVCB";
            case 65: return "HTTPS";
            default: return "TYPE" + t;
        }
    }

    /**
     * A SERVFAIL answer to {@code q}: same id and question, QR = 1, RA = 1, RCODE = 2, no records.
     * Sent when every DoH endpoint failed, so the client fails fast instead of waiting for a timeout.
     */
    static byte[] servfail(byte[] q, int len) {
        if (len < 12) return new byte[0];
        int end = 12;
        boolean ok = false;
        if ((((q[4] & 0xff) << 8) | (q[5] & 0xff)) == 1) {                // one question: keep it
            int o = 12;
            while (o < len) {
                int l = q[o++] & 0xff;
                if (l == 0) { ok = o + 4 <= len; if (ok) end = o + 4; break; }
                if (l > 63) break;
                o += l;
            }
        }
        byte[] r = new byte[end];
        System.arraycopy(q, 0, r, 0, end);
        r[2] = (byte) (0x80 | (q[2] & 0x01));                              // QR = 1, keep RD, opcode 0
        r[3] = (byte) 0x82;                                                // RA = 1, RCODE = 2
        r[4] = 0;
        r[5] = (byte) (ok ? 1 : 0);                                        // QDCOUNT
        for (int i = 6; i < 12; i++) r[i] = 0;                             // AN / NS / AR = 0
        return r;
    }
}
