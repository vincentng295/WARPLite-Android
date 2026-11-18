package com.warplite.app;

import java.math.BigInteger;
import java.security.SecureRandom;

/**
 * X25519 (RFC 7748) in plain Java, no Android dependency. Used to turn a random WireGuard private key into the
 * public key that is sent to Cloudflare's registration API. Port of the ladder in docs/wgcf-worker-reference.js.
 * Not constant-time (BigInteger): fine for a key that is generated and used on the user's own device.
 */
final class X25519 {

    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger A24 = BigInteger.valueOf(121665);

    private X25519() {
    }

    /** 32 random bytes, already clamped: a valid WireGuard private key. */
    static byte[] generatePrivateKey(SecureRandom rnd) {
        byte[] k = new byte[32];
        rnd.nextBytes(k);
        return clamp(k);
    }

    static byte[] clamp(byte[] k) {
        byte[] c = k.clone();
        c[0] &= (byte) 248;
        c[31] &= 127;
        c[31] |= 64;
        return c;
    }

    /** Public key of a private key (scalar mult of the base point u = 9). */
    static byte[] publicKey(byte[] privateKey) {
        byte[] base = new byte[32];
        base[0] = 9;
        return scalarMult(privateKey, base);
    }

    /** RFC 7748 X25519(k, u): both arguments 32 bytes little-endian. */
    static byte[] scalarMult(byte[] scalar, byte[] u) {
        if (scalar.length != 32 || u.length != 32) throw new IllegalArgumentException("32 bytes expected");
        BigInteger k = leToBig(clamp(scalar));
        byte[] ub = u.clone();
        ub[31] &= 127;                                   // mask the top bit of u
        BigInteger x1 = leToBig(ub);
        BigInteger x2 = BigInteger.ONE, z2 = BigInteger.ZERO, x3 = x1, z3 = BigInteger.ONE;
        int swap = 0;
        for (int t = 254; t >= 0; t--) {
            int kt = k.testBit(t) ? 1 : 0;
            swap ^= kt;
            if (swap == 1) {
                BigInteger a = x2; x2 = x3; x3 = a;
                a = z2; z2 = z3; z3 = a;
            }
            swap = kt;
            BigInteger a = x2.add(z2).mod(P), aa = a.multiply(a).mod(P);
            BigInteger b = x2.subtract(z2).mod(P), bb = b.multiply(b).mod(P);
            BigInteger e = aa.subtract(bb).mod(P);
            BigInteger c = x3.add(z3).mod(P), d = x3.subtract(z3).mod(P);
            BigInteger da = d.multiply(a).mod(P), cb = c.multiply(b).mod(P);
            BigInteger s = da.add(cb).mod(P), df = da.subtract(cb).mod(P);
            x3 = s.multiply(s).mod(P);
            z3 = x1.multiply(df.multiply(df).mod(P)).mod(P);
            x2 = aa.multiply(bb).mod(P);
            z2 = e.multiply(aa.add(A24.multiply(e)).mod(P)).mod(P);
        }
        if (swap == 1) {
            BigInteger a = x2; x2 = x3; x3 = a;
            a = z2; z2 = z3; z3 = a;
        }
        return bigToLe(x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P));
    }

    private static BigInteger leToBig(byte[] le) {
        byte[] be = new byte[le.length];
        for (int i = 0; i < le.length; i++) be[le.length - 1 - i] = le[i];
        return new BigInteger(1, be);
    }

    private static byte[] bigToLe(BigInteger n) {
        byte[] out = new byte[32];
        byte[] be = n.toByteArray();                     // big-endian, may carry a leading 0 or be short
        for (int i = 0; i < 32 && i < be.length; i++) out[i] = be[be.length - 1 - i];
        return out;
    }
}
