package com.warplite.app;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * Builds the v2rayNG style link Magic V2Ray understands, exactly like toV2rayNG() in worker.js:
 * wireguard://PRIVATEKEY@host:port?address=..&publickey=..&mtu=..&reserved=a,b,c#name
 * No Android dependency.
 */
final class WireGuardUri {

    private WireGuardUri() {
    }

    static String build(WarpAccount a, String name) {
        StringBuilder q = new StringBuilder();
        q.append("address=").append(enc(a.address));
        q.append("&publickey=").append(enc(a.peerPublicKey));
        q.append("&mtu=").append(a.mtu);
        if (!a.reserved.isEmpty()) q.append("&reserved=").append(enc(a.reserved));
        return "wireguard://" + enc(a.privateKey) + "@" + a.host + ":" + a.port + "?" + q + "#" + enc(name);
    }

    /** Like JavaScript's encodeURIComponent for the characters we meet (+ -> %2B, space -> %20). */
    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** "AAECAw==" -> "0,1,2" (the first 3 bytes of the client_id), "" when it cannot be decoded. */
    static String reservedFromClientId(String clientIdBase64) {
        byte[] b = Base64Lite.decode(clientIdBase64);
        if (b == null || b.length < 3) return "";
        return (b[0] & 0xff) + "," + (b[1] & 0xff) + "," + (b[2] & 0xff);
    }
}
