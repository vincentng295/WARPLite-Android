package com.warplite.app;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A WireGuard config in the form Magic V2Ray (v2rayNG style) understands:
 * {@code wireguard://<privateKey>@host:port?address=..&publickey=..&mtu=..&reserved=a,b,c#name}
 * Same layout as {@code toV2rayNG()} in wgcf-api/worker.js. No Android dependency (host-tested).
 */
final class WarpConfig {
    String host = "engage.cloudflareclient.com";
    String port = "2408";
    String privateKey = "";
    String peerPublicKey = "";
    /** "a,b,c" (3 bytes of the client_id) or "". */
    String reserved = "";
    String address = "172.16.0.2/32";
    int mtu = 1280;

    /** "a-7.21-0721"-style client_id (base64) -> "12,34,56". */
    static String reservedFromClientId(String clientId) {
        try {
            byte[] b = Base64.getDecoder().decode(clientId);
            if (b.length < 3) return "";
            return (b[0] & 255) + "," + (b[1] & 255) + "," + (b[2] & 255);
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** "host:port" as returned by the API (host may also be a bare name) -> sets host and port. */
    void setEndpoint(String ep) {
        if (ep == null || ep.isEmpty()) return;
        int i = ep.lastIndexOf(':');
        if (i > 0 && ep.indexOf(':') == i) {          // exactly one ':' -> host:port (a bare IPv6 is not expected here)
            host = ep.substring(0, i);
            port = ep.substring(i + 1);
        } else {
            host = ep;
        }
        if (port.isEmpty()) port = "2408";
    }

    /** JS encodeURIComponent: URLEncoder with "+" turned back into "%20". */
    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String dec(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    String toUri(String name) {
        StringBuilder q = new StringBuilder();
        q.append("address=").append(enc(address));
        q.append("&publickey=").append(enc(peerPublicKey));
        q.append("&mtu=").append(mtu);
        if (!reserved.isEmpty()) q.append("&reserved=").append(enc(reserved));
        return "wireguard://" + enc(privateKey) + "@" + host + ":" + port + "?" + q + "#" + enc(name);
    }

    /** Parses what {@link #toUri} writes (and what the worker returns). Returns null when it is not a wireguard:// link. */
    static WarpConfig parse(String uri) {
        if (uri == null) return null;
        uri = uri.trim();
        if (!uri.startsWith("wireguard://")) return null;
        String rest = uri.substring("wireguard://".length());
        int hash = rest.indexOf('#');
        if (hash >= 0) rest = rest.substring(0, hash);
        int at = rest.lastIndexOf('@');
        int qm = rest.indexOf('?');
        if (at < 0 || qm < at) return null;
        WarpConfig c = new WarpConfig();
        c.privateKey = dec(rest.substring(0, at));
        c.setEndpoint(rest.substring(at + 1, qm));
        Map<String, String> q = new LinkedHashMap<>();
        for (String kv : rest.substring(qm + 1).split("&")) {
            int e = kv.indexOf('=');
            if (e > 0) q.put(kv.substring(0, e), dec(kv.substring(e + 1)));
        }
        if (q.containsKey("address")) c.address = q.get("address");
        if (q.containsKey("publickey")) c.peerPublicKey = q.get("publickey");
        if (q.containsKey("reserved")) c.reserved = q.get("reserved");
        try {
            if (q.containsKey("mtu")) c.mtu = Integer.parseInt(q.get("mtu"));
        } catch (NumberFormatException ignored) {
            // keep the default
        }
        return c.privateKey.isEmpty() || c.peerPublicKey.isEmpty() ? null : c;
    }

    /** Worker answers (format=raw) with the link; without format=raw it is base64 of the link. Accepts both. */
    static String normalizeWorkerAnswer(String body) {
        String t = body == null ? "" : body.trim();
        if (t.startsWith("wireguard://")) return t;
        try {
            String d = new String(Base64.getMimeDecoder().decode(t), "UTF-8").trim();
            return d.startsWith("wireguard://") ? d : null;
        } catch (Exception e) {
            return null;
        }
    }
}
