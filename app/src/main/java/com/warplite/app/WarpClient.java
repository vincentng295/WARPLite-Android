package com.warplite.app;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Gets a WARP WireGuard config, the same way wgcf-api/worker.js does:
 * POST https://api.cloudflareclient.com/v0a2158/reg with a fresh X25519 public key, then read
 * config.peers[0] / config.interface / config.client_id (-> "reserved") from the answer.
 * Blocking: call from a background thread.
 */
final class WarpClient {
    static final String API = "https://api.cloudflareclient.com/v0a2158/reg";

    /** What a registration produced. */
    static final class Result {
        String uri = "";          // wireguard://... link for Magic V2Ray
        String deviceId = "";
        String license = "";      // "Key" in the Account screen
        String accountType = "";  // free / plus / unlimited
    }

    private WarpClient() {
    }

    /** Registers a new device on-device (private key never leaves the phone). */
    static Result register(String linkName) throws Exception {
        byte[] priv = new byte[32];
        new SecureRandom().nextBytes(priv);
        priv = X25519.clamp(priv);
        byte[] pub = X25519.publicKey(priv);

        JSONObject body = new JSONObject()
                .put("key", Base64.getEncoder().encodeToString(pub))
                .put("type", "Android")
                .put("model", "Android")
                .put("locale", "en_US");
        JSONObject reg = new JSONObject(http("POST", API, body.toString()));

        JSONObject c = reg.optJSONObject("config");
        JSONObject peer = c == null || c.optJSONArray("peers") == null ? null : c.getJSONArray("peers").optJSONObject(0);
        if (c == null || peer == null || peer.optString("public_key").isEmpty()) {
            throw new Exception("Invalid WARP response");
        }

        WarpConfig w = new WarpConfig();
        w.privateKey = Base64.getEncoder().encodeToString(priv);
        w.peerPublicKey = peer.getString("public_key");
        JSONObject ep = peer.optJSONObject("endpoint");
        if (ep != null) {
            String e = ep.optString("host", ep.optString("v4", ""));
            w.setEndpoint(e);
        }
        String cid = c.optString("client_id", "");
        if (!cid.isEmpty()) w.reserved = WarpConfig.reservedFromClientId(cid);
        JSONObject addr = c.optJSONObject("interface") == null ? null : c.getJSONObject("interface").optJSONObject("addresses");
        StringBuilder a = new StringBuilder();
        if (addr != null && !addr.optString("v4").isEmpty()) a.append(addr.getString("v4")).append("/32");
        if (addr != null && !addr.optString("v6").isEmpty()) {
            if (a.length() > 0) a.append(',');
            a.append(addr.getString("v6")).append("/128");
        }
        if (a.length() > 0) w.address = a.toString();

        Result r = new Result();
        r.uri = w.toUri(linkName);
        r.deviceId = reg.optString("id");
        JSONObject acc = reg.optJSONObject("account");
        if (acc != null) {
            r.license = acc.optString("license");
            r.accountType = acc.optString("account_type");
        }
        return r;
    }

    /** Alternative: ask a deployed wgcf-api worker for the link (format=raw). */
    static String fromWorker(String workerUrl, String linkName) throws Exception {
        String sep = workerUrl.contains("?") ? "&" : "?";
        String url = workerUrl + sep + "format=raw&name=" + java.net.URLEncoder.encode(linkName, "UTF-8").replace("+", "%20");
        String uri = WarpConfig.normalizeWorkerAnswer(http("GET", url, null));
        if (uri == null || WarpConfig.parse(uri) == null) throw new Exception("The worker did not return a wireguard:// link");
        return uri;
    }

    private static String http(String method, String url, String body) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        try {
            h.setRequestMethod(method);
            h.setConnectTimeout(12000);
            h.setReadTimeout(15000);
            h.setRequestProperty("CF-Client-Version", "a-7.21-0721");
            h.setRequestProperty("User-Agent", "okhttp/0.7.21");
            if (body != null) {
                h.setDoOutput(true);
                h.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                try (OutputStream o = h.getOutputStream()) {
                    o.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = h.getResponseCode();
            if (code / 100 != 2) throw new Exception("HTTP " + code);
            try (InputStream in = h.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return bo.toString("UTF-8");
            }
        } finally {
            h.disconnect();
        }
    }
}
