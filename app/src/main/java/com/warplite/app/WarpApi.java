package com.warplite.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * How the app gets its WireGuard configuration: {@link #register()} registers this device with Cloudflare (the same
 * request as registerWarp() in the wgcf-api worker): a fresh X25519 key pair is made here, only the public key is sent.
 * All calls block: run them on a background thread.
 */
final class WarpApi {

    static final String API = "https://api.cloudflareclient.com/v0a2158/reg";
    private static final String CF_VERSION = "a-7.21-0721";
    private static final String UA = "okhttp/0.7.21";

    private WarpApi() {
    }

    // ------------------------------------------------------------------ registration

    static WarpAccount register() throws IOException {
        byte[] priv = X25519.generatePrivateKey(new SecureRandom());
        String pub = Base64Lite.encode(X25519.publicKey(priv));
        JSONObject body = new JSONObject();
        try {
            body.put("key", pub);
            body.put("type", "Android");
            body.put("model", "Android");
            body.put("locale", "en_US");
        } catch (JSONException e) {
            throw new IOException(e);
        }
        String res = request("POST", API, null, body.toString());
        try {
            return fromRegistration(new JSONObject(res), Base64Lite.encode(priv));
        } catch (JSONException e) {
            throw new IOException("Invalid WARP response", e);
        }
    }

    /** Turns the registration answer into an account (mirrors the parsing in worker.js). */
    static WarpAccount fromRegistration(JSONObject reg, String privateKeyBase64) throws IOException {
        JSONObject c = reg.optJSONObject("config");
        JSONObject peer = null;
        if (c != null && c.optJSONArray("peers") != null) peer = c.optJSONArray("peers").optJSONObject(0);
        if (c == null || peer == null || peer.optString("public_key").isEmpty()) {
            throw new IOException("Invalid WARP response");
        }
        WarpAccount a = new WarpAccount();
        a.id = reg.optString("id");
        a.token = reg.optString("token");
        JSONObject acc = reg.optJSONObject("account");
        if (acc != null) {
            a.license = acc.optString("license");
            a.warpPlus = acc.optBoolean("warp_plus");
        }
        a.createdAt = System.currentTimeMillis();
        a.privateKey = privateKeyBase64;
        a.peerPublicKey = peer.optString("public_key");

        JSONObject endpoint = peer.optJSONObject("endpoint");
        String ep = "engage.cloudflareclient.com:2408";
        if (endpoint != null) {
            String h = endpoint.optString("host");
            if (h.isEmpty()) h = endpoint.optString("v4");
            if (!h.isEmpty()) ep = h;
        }
        int colon = ep.lastIndexOf(':');
        a.host = colon > 0 ? ep.substring(0, colon) : ep;
        String port = colon > 0 ? ep.substring(colon + 1) : "2408";
        a.port = port.isEmpty() || "0".equals(port) ? "2408" : port;

        String cid = c.optString("client_id");
        a.reserved = cid.isEmpty() ? "" : WireGuardUri.reservedFromClientId(cid);

        JSONObject addrs = null;
        JSONObject itf = c.optJSONObject("interface");
        if (itf != null) addrs = itf.optJSONObject("addresses");
        StringBuilder sb = new StringBuilder();
        if (addrs != null && !addrs.optString("v4").isEmpty()) sb.append(addrs.optString("v4")).append("/32");
        if (addrs != null && !addrs.optString("v6").isEmpty()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(addrs.optString("v6")).append("/128");
        }
        a.address = sb.length() > 0 ? sb.toString() : "172.16.0.2/32";
        a.mtu = 1280;
        return a;
    }

    /** Applies a WARP+ license key to this device. Throws IOException when Cloudflare refuses it. */
    static void setLicense(WarpAccount a, String license) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("license", license);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        String res = request("PUT", API + "/" + a.id + "/account", a.token, body.toString());
        try {
            JSONObject j = new JSONObject(res);
            a.license = j.optString("license", license);
            a.warpPlus = j.optBoolean("warp_plus", true);
        } catch (JSONException e) {
            a.license = license;
        }
    }

    /** Best effort: removes this device from the account (used by "Reset security keys"). */
    static void deleteDevice(WarpAccount a) {
        if (a.id.isEmpty() || a.token.isEmpty()) return;
        try {
            request("DELETE", API + "/" + a.id, a.token, null);
        } catch (IOException ignored) {
            // the new key is what matters; an orphan registration is harmless
        }
    }

    // ------------------------------------------------------------------ http

    private static String request(String method, String url, String bearer, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", UA);
            if (url.startsWith(API)) {
                c.setRequestProperty("CF-Client-Version", CF_VERSION);
                c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            }
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
            if (body != null) {
                c.setDoOutput(true);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : read(in);
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code + (text.isEmpty() ? "" : ": " + shorten(text)));
            return text;
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = i.read(buf)) > 0 && out.size() < 1 << 20) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static String shorten(String s) {
        s = s.replace('\n', ' ');
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }
}
