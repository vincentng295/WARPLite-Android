package com.warplite.app;

/** Tiny standard-alphabet Base64 (java.util.Base64 needs API 26, we have it, but this keeps the tests/host code uniform). */
final class Base64Lite {
    private static final String ABC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    private Base64Lite() {
    }

    static String encode(byte[] in) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < in.length; i += 3) {
            int b0 = in[i] & 0xff, b1 = i + 1 < in.length ? in[i + 1] & 0xff : 0, b2 = i + 2 < in.length ? in[i + 2] & 0xff : 0;
            sb.append(ABC.charAt(b0 >> 2)).append(ABC.charAt(((b0 & 3) << 4) | (b1 >> 4)));
            sb.append(i + 1 < in.length ? ABC.charAt(((b1 & 15) << 2) | (b2 >> 6)) : '=');
            sb.append(i + 2 < in.length ? ABC.charAt(b2 & 63) : '=');
        }
        return sb.toString();
    }

    /** null when {@code s} is not valid Base64. */
    static byte[] decode(String s) {
        if (s == null) return null;
        String t = s.trim().replace('-', '+').replace('_', '/');
        while (t.endsWith("=")) t = t.substring(0, t.length() - 1);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buf = 0, bits = 0;
        for (int i = 0; i < t.length(); i++) {
            int v = ABC.indexOf(t.charAt(i));
            if (v < 0) return null;
            buf = (buf << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buf >> bits) & 0xff);
            }
        }
        return out.toByteArray();
    }
}
