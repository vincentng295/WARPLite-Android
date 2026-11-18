package com.warplite.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Client side of the Magic V2Ray external API (taken from the magic-dns-rewrite example, connect() generalised).
 * (Everything an AI assistant or another developer needs to call the API is described here.)
 *
 * <h2>What Magic V2Ray offers</h2>
 * <pre>
 *  WHAT                    HOW                                                     NEEDS UI?
 *  ----------------------  ------------------------------------------------------  -----------------------
 *  1. Connect              startActivity(action CONNECT, extras uri/name/exclude/   yes: the user confirms
 *                          overrides)  or the deep link magicv2ray://connect?...
 *  2. Disconnect           startActivity(action DISCONNECT) or magicv2ray://disconnect   no
 *  3. Info                 ContentProvider call "getInfo"  (version, API level,     no
 *                          supported actions / features, current state)
 *  4. Am I connected?      ContentProvider call "getState"                          no
 *  5. Tell me on change    broadcast  com.huskydg.magicv2ray.event.STATE_CHANGED    no
 *                          (we listen with {@link #listen})
 * </pre>
 *
 * <h2>The rules you must know</h2>
 * <ol>
 *   <li><b>Android 11+ package visibility.</b> Your manifest needs
 *       {@code <queries><package android:name="com.huskydg.magicv2ray"/></queries>}, or every call below
 *       behaves as if Magic V2Ray were not installed.</li>
 *   <li><b>A connect is only a request.</b> The user always confirms it. The server of an external session is
 *       temporary: the user's own nodes and settings are never changed.</li>
 *   <li><b>API level.</b> Info and state events exist from API level 1 ({@link Info#apiLevel}). An older Magic V2Ray
 *       has no provider: {@link #info} then reports {@code apiLevel == 0}. Test {@link Info#has(String)} for a
 *       feature, never the app's version number.</li>
 *   <li><b>Never call {@link #info} / {@link #state} on the main thread.</b> In root mode Magic V2Ray may have to
 *       ask the root service (up to a few seconds).</li>
 *   <li><b>Events can arrive twice</b> (once as a normal broadcast, once addressed to your package). {@link #listen}
 *       removes the duplicates for you; do the same if you write your own receiver (key: {@code seq + time}).</li>
 *   <li><b>No event if Magic V2Ray itself is killed</b> (non-root mode: the VPN dies with it). If that matters,
 *       also call {@link #state} now and then.</li>
 *   <li>The data carries no server address and no settings, only the state.</li>
 * </ol>
 *
 * <h2>Connect: two ways, same result</h2>
 * <ul>
 *   <li>explicit intent ({@link #connectByIntent}): Android tells Magic V2Ray which app asked, so the user may choose
 *       "Always trust this app". That also makes Magic V2Ray send the state events straight to this app's package;</li>
 *   <li>deep link ({@link #connectByLink}): works for websites too, but is always confirmed and the caller is unknown.</li>
 * </ul>
 * Both carry {@link WarpOverrides#json} as {@code overrides} (DNS servers, excluded apps / routes). A request with
 * overrides is always confirmed by the user in Magic V2Ray.
 *
 * <h2>Typical flow of an app (this example does exactly this)</h2>
 * <pre>
 *  1. new Thread(() -> info = MagicV2Ray.info(ctx)).start();      // installed? version? apiLevel &gt;= 1?
 *  2. start your local proxy, wait until its port is open
 *  3. MagicV2Ray.connectByIntent(activity, uri, name);             // user confirms
 *  4. receiver = MagicV2Ray.listen(service, event -&gt; { ... });    // ALIVE as long as your proxy runs
 *       event.connected == false &amp;&amp; event.session            // our session ended -&gt; stop our proxy
 *  5. to stop yourself: MagicV2Ray.disconnect(activity), THEN stop the proxy
 *  6. MagicV2Ray.unlisten(service, receiver);
 * </pre>
 */
final class MagicV2Ray {

    // ------------------------------------------------------------------ names of the API

    /** Package of Magic V2Ray. */
    static final String PKG = "com.huskydg.magicv2ray";

    /** Activity action: ask for a connection. Extras: uri (String), name (String), exclude (String[]), overrides (JSON String). */
    static final String ACTION_CONNECT = "com.huskydg.magicv2ray.action.CONNECT";
    /** Activity action: end the external session (no extras, no confirmation). */
    static final String ACTION_DISCONNECT = "com.huskydg.magicv2ray.action.DISCONNECT";

    /** Authority of the read-only provider that answers {@link #METHOD_INFO} and {@link #METHOD_STATE}. */
    static final String AUTHORITY = "com.huskydg.magicv2ray.api";
    private static final Uri PROVIDER = Uri.parse("content://" + AUTHORITY);
    /** Observe this Uri with a ContentObserver to learn that the state changed (then call {@link #state}). */
    static final Uri STATE_URI = Uri.parse("content://" + AUTHORITY + "/state");
    /** provider.call(): version, API level, features and state. Bundle: json, versionName, versionCode, apiLevel, mode, connected, known. */
    static final String METHOD_INFO = "getInfo";
    /** provider.call(): only the state. Bundle: json, connected, known, mode. */
    static final String METHOD_STATE = "getState";

    /** Broadcast sent on every connect / disconnect, see {@link StateEvent}. */
    static final String EVENT_STATE_CHANGED = "com.huskydg.magicv2ray.event.STATE_CHANGED";

    /** Feature names found in {@code api.features} of the info JSON. */
    static final String FEATURE_INFO = "info";
    static final String FEATURE_STATE_EVENTS = "state_events";

    private MagicV2Ray() {
    }

    // ------------------------------------------------------------------ 3. info

    /**
     * What {@link #info} learned about the installed Magic V2Ray.
     * <ul>
     *   <li>{@code installed == false}: not installed (or hidden from us: see rule 1 above);</li>
     *   <li>{@code installed && apiLevel == 0}: installed but older than API level 1 (no info, no events);</li>
     *   <li>{@code apiLevel >= 1}: everything else is filled in.</li>
     * </ul>
     */
    static final class Info {
        boolean installed;
        String versionName = "";
        long versionCode;
        /** 0 = too old for info / events. Grows when the API gets new things. */
        int apiLevel;
        /** "root" or "nonroot". */
        String mode = "";
        /** True while the proxy runs. Only meaningful when {@link #stateKnown}. */
        boolean connected;
        /** False in root mode when the state could not be read (no root granted, service not running). */
        boolean stateKnown;
        /** Is an external session (a connect from an app) active, and who started it. */
        boolean sessionActive;
        String sessionName = "";
        String sessionCaller = "";
        /** The complete answer, for anything not exposed as a field (api.actions, api.schemes, api.overrides ...). */
        JSONObject raw = new JSONObject();

        /** True if {@code api.features} of the answer lists {@code feature} (e.g. {@link #FEATURE_STATE_EVENTS}). */
        boolean has(String feature) {
            JSONObject api = raw.optJSONObject("api");
            JSONArray f = api == null ? null : api.optJSONArray("features");
            for (int i = 0; f != null && i < f.length(); i++) {
                if (feature.equals(f.optString(i))) return true;
            }
            return false;
        }

        /** One line for a status label, e.g. "Magic V2Ray v2.5-alpha (API 1, root) - connected". */
        String summary() {
            if (!installed) return "Magic V2Ray is not installed";
            if (apiLevel < 1) return "Magic V2Ray " + versionName + " (too old: no info / state events)";
            return "Magic V2Ray " + versionName + " (API " + apiLevel + ", " + mode + ") - "
                    + (!stateKnown ? "state unknown" : connected ? "connected" : "not connected")
                    + (sessionActive ? ", session \"" + sessionName + "\"" : "");
        }
    }

    /**
     * Asks Magic V2Ray for its version, API level and current state ({@link #METHOD_INFO}).
     * BLOCKS (see rule 4): call it from a background thread. Never returns null and never throws.
     */
    static Info info(Context c) {
        Info out = new Info();
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(PKG, 0);
            out.installed = true;
            out.versionName = pi.versionName == null ? "" : pi.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return out;
        }
        Bundle b = callProvider(c, METHOD_INFO);
        if (b == null) return out;                      // installed, but it has no provider: older than API level 1
        try {
            JSONObject j = new JSONObject(b.getString("json", "{}"));
            out.raw = j;
            out.versionName = j.optString("versionName", out.versionName);
            out.versionCode = j.optLong("versionCode");
            out.apiLevel = j.optJSONObject("api") == null ? 0 : j.getJSONObject("api").optInt("level");
            out.mode = j.optString("mode");
            JSONObject st = j.optJSONObject("state");
            if (st != null) {
                out.connected = st.optBoolean("connected");
                out.stateKnown = st.optBoolean("known");
                JSONObject s = st.optJSONObject("session");
                if (s != null) {
                    out.sessionActive = s.optBoolean("active");
                    out.sessionName = s.optString("name");
                    out.sessionCaller = s.optString("caller");
                }
            }
        } catch (Exception ignored) {
            // an answer we cannot read: keep what we have
        }
        return out;
    }

    /**
     * Cheap "is the proxy up right now?" ({@link #METHOD_STATE}). BLOCKS, background thread only.
     * @return TRUE / FALSE, or null when Magic V2Ray cannot say (not installed, too old, state unknown)
     */
    static Boolean state(Context c) {
        Bundle b = callProvider(c, METHOD_STATE);
        if (b == null || !b.getBoolean("known")) return null;
        return b.getBoolean("connected");
    }

    private static Bundle callProvider(Context c, String method) {
        try {
            return c.getContentResolver().call(PROVIDER, method, null, null);
        } catch (RuntimeException e) {
            // IllegalArgumentException: no such provider (old Magic V2Ray / not visible); SecurityException, DeadObject ...
            return null;
        }
    }

    // ------------------------------------------------------------------ 5. state events

    /**
     * One {@link #EVENT_STATE_CHANGED} broadcast. Read with {@link #parse(Intent)}.
     * Extras on the wire: state, connected, reason, session, sessionName, caller, mode, time, seq.
     */
    static final class StateEvent {
        /** true = the proxy is up, false = it went down (this is the "Magic V2Ray disconnected" signal). */
        boolean connected;
        /**
         * Why: "started", "session_started" (up, a session is the cause), "session_ended" (the external session ended),
         * "stopped" (stopped normally), "error" (engine failed / VPN permission revoked), "crashed". Treat unknown
         * values like "stopped": new reasons may be added.
         */
        String reason = "";
        /** An external session (a connect from an app) is involved in this change. */
        boolean session;
        /** Name of that session, as given in the {@code name} extra of the connect (or the app's label). */
        String sessionName = "";
        /** Package that started the session, "" when Magic V2Ray could not tell (deep link, old Android). */
        String caller = "";
        /** "root" or "nonroot". */
        String mode = "";
        /** System.currentTimeMillis() of Magic V2Ray when it happened. */
        long time;
        /** Counter, grows with every event of one Magic V2Ray process. With {@link #time} it identifies an event. */
        long seq;

        @Override
        public String toString() {
            return (connected ? "connected" : "disconnected") + " (" + reason + ")"
                    + (session ? ", session \"" + sessionName + "\"" : "");
        }
    }

    /** Receives events from {@link #listen}. Called on the main thread. */
    interface Listener {
        void onState(StateEvent event);
    }

    /** The event inside {@code i}, or null when {@code i} is not a state event. */
    static StateEvent parse(Intent i) {
        if (i == null || !EVENT_STATE_CHANGED.equals(i.getAction())) return null;
        StateEvent e = new StateEvent();
        e.connected = i.getBooleanExtra("connected", false);
        e.reason = nz(i.getStringExtra("reason"));
        e.session = i.getBooleanExtra("session", false);
        e.sessionName = nz(i.getStringExtra("sessionName"));
        e.caller = nz(i.getStringExtra("caller"));
        e.mode = nz(i.getStringExtra("mode"));
        e.time = i.getLongExtra("time", 0);
        e.seq = i.getLongExtra("seq", 0);
        return e;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * Starts listening for state events. Keep the returned receiver and give it to {@link #unlisten} when you stop
     * caring (e.g. in the service's onDestroy). It lives as long as {@code c}: use a Service for "while my proxy runs".
     *
     * <p>Duplicates (the same event as a normal broadcast and as one addressed to this package) are dropped.
     * The listener runs on the main thread.
     */
    static BroadcastReceiver listen(Context c, final Listener listener) {
        BroadcastReceiver r = new BroadcastReceiver() {
            private long lastSeq = -1, lastTime = -1;

            @Override
            public void onReceive(Context ctx, Intent i) {
                StateEvent e = parse(i);
                if (e == null) return;
                if (e.seq == lastSeq && e.time == lastTime) return;     // second copy of the same event
                lastSeq = e.seq;
                lastTime = e.time;
                listener.onState(e);
            }
        };
        IntentFilter f = new IntentFilter(EVENT_STATE_CHANGED);
        // The sender is another app: the receiver must be exported (the flag exists from Android 13).
        if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        else c.registerReceiver(r, f);
        return r;
    }

    /** Stops {@link #listen}. Safe to call twice. */
    static void unlisten(Context c, BroadcastReceiver r) {
        if (r == null) return;
        try {
            c.unregisterReceiver(r);
        } catch (IllegalArgumentException ignored) {
            // already unregistered
        }
    }

    // ------------------------------------------------------------------ 1. connect

    /** True if Magic V2Ray is installed and new enough to know the connect API. Main thread is fine (no provider call). */
    static boolean available(Activity a) {
        Intent i = new Intent(ACTION_CONNECT).setPackage(PKG);
        return i.resolveActivity(a.getPackageManager()) != null;
    }

    /**
     * Connect with an explicit intent. Magic V2Ray shows a confirmation, then routes the device through {@code uri}.
     * @param uri       node to use (here a wireguard:// link); {@code null} = keep the node the user selected in
     *                  Magic V2Ray and only apply {@code overrides} (DNS-only mode)
     * @param name      label shown in Magic V2Ray (status chip, notification) and reported back as {@code sessionName}
     * @param overrides temporary settings as JSON text, see {@link WarpOverrides#json}
     */
    static boolean connectByIntent(Activity a, String uri, String name, String overrides) {
        return connectByIntent(a, uri, name, overrides, null);
    }

    /**
     * Same, plus {@code exclude}: packages kept OUT of the VPN for this session (max 8). The 1.1.1.1 mode passes this
     * app's own package: its SOCKS5 bridge talks to Cloudflare through the real network, or its DoH requests would
     * loop back into the bridge.
     */
    static boolean connectByIntent(Activity a, String uri, String name, String overrides, String[] exclude) {
        Intent i = new Intent(ACTION_CONNECT)
                .setPackage(PKG)
                .putExtra("name", name)
                // Intent / broadcast: the extra is the JSON text itself.
                .putExtra("overrides", overrides);
        if (uri != null) i.putExtra("uri", uri);
        if (exclude != null && exclude.length > 0) i.putExtra("exclude", exclude);
        return start(a, i);
    }

    /** Same as {@link #connectByIntent} through the deep link (always confirmed; Magic V2Ray cannot tell who called). */
    static boolean connectByLink(Activity a, String uri, String name, String overrides) {
        Uri.Builder b = new Uri.Builder().scheme("magicv2ray").authority("connect")
                .appendQueryParameter("name", name)
                // Deep link: base64url (no padding, no wrapping) of the JSON.
                .appendQueryParameter("overrides", Base64.encodeToString(
                        overrides.getBytes(StandardCharsets.UTF_8),
                        Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING));
        if (uri != null) b.appendQueryParameter("uri", uri);   // appendQueryParameter URL-encodes the value
        return start(a, new Intent(Intent.ACTION_VIEW, b.build()).setPackage(PKG));
    }

    // ------------------------------------------------------------------ 2. disconnect

    /**
     * Ends the external session (the user's own node comes back). Do this BEFORE stopping your proxy: stopping it
     * first would cut the connection while Magic V2Ray still routes through it.
     */
    static boolean disconnect(Activity a) {
        return start(a, new Intent(ACTION_DISCONNECT).setPackage(PKG));
    }

    private static boolean start(Activity a, Intent i) {
        try {
            a.startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }
}
