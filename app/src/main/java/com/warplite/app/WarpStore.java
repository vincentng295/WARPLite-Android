package com.warplite.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Everything the app remembers: the WARP account, the settings, the theme. SharedPreferences, private to the app. */
final class WarpStore {

    private final SharedPreferences sp;

    WarpStore(Context c) {
        sp = c.getSharedPreferences("warp", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ account

    WarpAccount account() {
        String j = sp.getString("account", null);
        if (j == null) return null;
        try {
            JSONObject o = new JSONObject(j);
            WarpAccount a = new WarpAccount();
            a.id = o.optString("id");
            a.token = o.optString("token");
            a.license = o.optString("license");
            a.warpPlus = o.optBoolean("warpPlus");
            a.createdAt = o.optLong("createdAt");
            a.privateKey = o.optString("privateKey");
            a.host = o.optString("host", a.host);
            a.port = o.optString("port", a.port);
            a.peerPublicKey = o.optString("peerPublicKey");
            a.reserved = o.optString("reserved");
            a.address = o.optString("address", a.address);
            a.mtu = o.optInt("mtu", a.mtu);
            return a.usable() ? a : null;
        } catch (JSONException e) {
            return null;
        }
    }

    void saveAccount(WarpAccount a) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", a.id).put("token", a.token).put("license", a.license).put("warpPlus", a.warpPlus)
                    .put("createdAt", a.createdAt).put("privateKey", a.privateKey)
                    .put("host", a.host).put("port", a.port).put("peerPublicKey", a.peerPublicKey)
                    .put("reserved", a.reserved).put("address", a.address).put("mtu", a.mtu);
            sp.edit().putString("account", o.toString()).apply();
        } catch (JSONException ignored) {
            // cannot happen: plain strings and numbers
        }
    }

    void clearAccount() {
        sp.edit().remove("account").apply();
    }

    // ------------------------------------------------------------------ settings

    WarpOverrides.Settings settings() {
        WarpOverrides.Settings s = new WarpOverrides.Settings();
        s.mode = sp.getInt("mode", WarpOverrides.MODE_WARP) == WarpOverrides.MODE_1111 ? WarpOverrides.MODE_1111 : WarpOverrides.MODE_WARP;
        s.families = sp.getInt("families", WarpOverrides.FAMILIES_NONE);
        s.dnsProtocol = sp.getInt("dnsProtocol", WarpOverrides.PROTO_WARP);
        s.fallbackResolvers = sp.getBoolean("fallback", true);
        s.chainUserNode = sp.getBoolean("chainUserNode", true);
        s.chainWarpFirst = sp.getBoolean("chainWarpFirstV2", false);
        readList("apps", s.excludedApps);
        readList("routes", s.excludedRoutes);
        return s;
    }

    void saveSettings(WarpOverrides.Settings s) {
        sp.edit().putInt("mode", s.mode).putInt("families", s.families).putInt("dnsProtocol", s.dnsProtocol)
                .putBoolean("fallback", s.fallbackResolvers)
                .putBoolean("chainUserNode", s.chainUserNode).putBoolean("chainWarpFirstV2", s.chainWarpFirst)
                .putString("apps", new JSONArray(s.excludedApps).toString())
                .putString("routes", new JSONArray(s.excludedRoutes).toString()).apply();
    }

    private void readList(String key, java.util.List<String> out) {
        try {
            JSONArray a = new JSONArray(sp.getString(key, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (JSONException ignored) {
            // empty list
        }
    }

    // ------------------------------------------------------------------ running chain

    /** The user's node of the chain that is running, so the chain can be reversed after the app restarted. */
    void saveChain(String userUri, String name, boolean warpFirst) {
        sp.edit().putString("chainUri", userUri).putString("chainName", name).putBoolean("chainRunWarpFirst", warpFirst).apply();
    }

    void clearChain() {
        sp.edit().remove("chainUri").remove("chainName").remove("chainRunWarpFirst").apply();
    }

    String chainUri() {
        return sp.getString("chainUri", null);
    }

    String chainName() {
        return sp.getString("chainName", "your node");
    }

    boolean chainWarpFirst() {
        return sp.getBoolean("chainRunWarpFirst", false);
    }

    // ------------------------------------------------------------------ misc

    boolean dark() {
        return sp.getBoolean("dark", false);
    }

    void setDark(boolean v) {
        sp.edit().putBoolean("dark", v).apply();
    }

    /** "Reset all settings": everything except the account (that is "Reset security keys"). */
    void resetSettings() {
        sp.edit().remove("mode").remove("families").remove("dnsProtocol").remove("fallback").remove("chainUserNode").remove("chainWarpFirst").remove("chainWarpFirstV2").remove("apps")
                .remove("routes").remove("dark").remove("workerUrl").apply();
    }
}
