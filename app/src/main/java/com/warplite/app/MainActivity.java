package com.warplite.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 1.1.1.1 WARP Lite: one activity, nine screens drawn in code (Home, Settings, Account, Advanced, Connection
 * options, DNS settings, Excluded apps, Excluded routes, Diagnostics).
 *
 * Two modes, both end in MagicV2Ray.connectByIntent together with the temporary overrides (WarpOverrides):
 *  - WARP:    get a WireGuard config (WarpApi) -> wireguard:// link (WireGuardUri) is the node;
 *  - 1.1.1.1: start the local DNS bridge (Socks5Service / Socks5Server: any:53 -> DNS over HTTPS), node socks://127.0.0.1:5353,
 *             this app excluded from the VPN.
 */
public class MainActivity extends Activity {

    static final String SESSION_NAME = "1.1.1.1 WARP";
    private static final String HELP_URL = "https://github.com/vincentng295/WARPLite-Android";
    private static final String MAGIC_URL = "https://github.com/vincentng295/Magic_V2Ray";

    private enum State { OFF, CONNECTING, ON }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayList<String> stack = new ArrayList<>();

    private WarpStore store;
    private Ui u;
    private WarpOverrides.Settings settings;
    private FrameLayout root;

    private State state = State.OFF;
    private String error;                       // shown under the switch after a failed attempt
    private volatile MagicV2Ray.Info magic;
    private BroadcastReceiver receiver;
    private long connectingSince;
    private Ui.Toggle homeToggle;

    // ------------------------------------------------------------------ life cycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new WarpStore(this);
        settings = store.settings();
        root = new FrameLayout(this);
        setContentView(root);
        stack.add("home");
        applyTheme();
        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        receiver = MagicV2Ray.listen(this, e -> {
            Log.add("Magic V2Ray: " + e);
            if (e.connected && (e.session || SESSION_NAME.equals(e.sessionName))) setState(State.ON);
            else if (!e.connected && state != State.OFF) setState(State.OFF);
        });
    }

    @Override
    protected void onStop() {
        MagicV2Ray.unlisten(this, receiver);
        receiver = null;
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        pollState(0);
    }

    @Override
    public void onBackPressed() {
        if (stack.size() > 1) {
            stack.remove(stack.size() - 1);
            render();
        } else {
            super.onBackPressed();
        }
    }

    private void go(String screen) {
        stack.add(screen);
        render();
    }

    private void back() {
        onBackPressed();
    }

    // ------------------------------------------------------------------ connection

    private void setState(State s) {
        state = s;
        if (s != State.CONNECTING) connectingSince = 0;
        if (s == State.ON) error = null;
        if ("home".equals(top())) render();
    }

    private String top() {
        return stack.get(stack.size() - 1);
    }

    /** Asks Magic V2Ray for the state; while we wait for the user's confirmation it asks again (max 25 s). */
    private void pollState(final int round) {
        new Thread(() -> {
            final MagicV2Ray.Info i = MagicV2Ray.info(getApplicationContext());
            magic = i;
            ui.post(() -> {
                boolean ours = i.stateKnown && i.connected && (!i.sessionActive || SESSION_NAME.equals(i.sessionName));
                if (ours && i.sessionActive) setState(State.ON);
                else if (state == State.CONNECTING && round < 12) ui.postDelayed(() -> pollState(round + 1), 2000);
                else if (state != State.OFF && !(i.stateKnown && i.connected && !i.sessionActive)) setState(State.OFF);
                else if ("diag".equals(top())) render();
            });
        }, "magic-info").start();
    }

    private void onToggle() {
        if (state != State.OFF) {
            MagicV2Ray.disconnect(this);
            Log.add("disconnect requested");
            setState(State.OFF);
            stopBridgeLater();
            return;
        }
        if (!MagicV2Ray.available(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Magic V2Ray needed")
                    .setMessage("This app hands its configuration to Magic V2Ray, which creates the VPN. "
                            + "Install a version with the connect API first.")
                    .setPositiveButton("Open GitHub", (d, w) -> open(MAGIC_URL))
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        error = null;
        connectingSince = System.currentTimeMillis();
        setState(State.CONNECTING);
        final String overrides = WarpOverrides.json(settings);
        Log.add("mode " + (settings.mode == WarpOverrides.MODE_WARP ? "WARP" : "1.1.1.1") + ", overrides " + overrides);
        if (settings.mode == WarpOverrides.MODE_1111) connectBridge(overrides);
        else connectWarp(overrides);
    }

    /** 1.1.1.1: start the DNS bridge, wait until its port is really open, then connect Magic V2Ray to it. */
    private void connectBridge(final String overrides) {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        Socks5Service.start(this);
        final long deadline = System.currentTimeMillis() + 3000;
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (state != State.CONNECTING) return;
                if (Socks5Service.listening) {
                    // this app stays OUT of the VPN: the bridge reaches Cloudflare through the real network
                    boolean ok = MagicV2Ray.connectByIntent(MainActivity.this, WarpOverrides.SOCKS_URI, SESSION_NAME,
                            overrides, new String[]{getPackageName()});
                    if (!ok) fail("Could not open Magic V2Ray");
                    else pollState(0);
                } else if (System.currentTimeMillis() < deadline) {
                    ui.postDelayed(this, 100);
                } else {
                    fail("The DNS bridge did not start, see Diagnostics");
                }
            }
        });
    }

    /** WARP: get the WireGuard configuration (registers this device the first time), then connect with its link. */
    private void connectWarp(final String overrides) {
        new Thread(() -> {
            try {
                WarpAccount a = ensureAccount();
                final String link = WireGuardUri.build(a, SESSION_NAME);
                Log.add("WireGuard " + a.host + ":" + a.port);
                ui.post(() -> {
                    if (state != State.CONNECTING) return;
                    if (!MagicV2Ray.connectByIntent(this, link, SESSION_NAME, overrides)) fail("Could not open Magic V2Ray");
                    else pollState(0);
                });
            } catch (IOException e) {
                Log.add("config error: " + e.getMessage());
                ui.post(() -> fail(e.getMessage()));
            }
        }, "warp-config").start();
    }

    /** Order matters: the bridge keeps running until Magic V2Ray has let go of it. */
    private void stopBridgeLater() {
        if (!Socks5Service.listening) return;
        ui.postDelayed(() -> Socks5Service.stop(this), 1500);
    }

    private void fail(String msg) {
        error = msg;
        setState(State.OFF);
        if (Socks5Service.listening) Socks5Service.stop(this);
        render();
    }

    /** The saved account, or a new registration. Background thread. */
    private WarpAccount ensureAccount() throws IOException {
        WarpAccount a = store.account();
        if (a == null) {
            a = WarpApi.register();
            store.saveAccount(a);
            Log.add("registered a new WARP device " + a.id);
        }
        return a;
    }

    // ------------------------------------------------------------------ rendering

    private void applyTheme() {
        u = new Ui(this, store.dark());
        getWindow().setStatusBarColor(u.bg);
        getWindow().setNavigationBarColor(u.bg);
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        if (!u.dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private void render() {
        root.removeAllViews();
        root.setBackgroundColor(u.bg);
        View v;
        switch (top()) {
            case "settings": v = settingsScreen(); break;
            case "account": v = accountScreen(); break;
            case "advanced": v = advancedScreen(); break;
            case "conn": v = connectionScreen(); break;
            case "dns": v = dnsScreen(); break;
            case "apps": v = appsScreen(); break;
            case "routes": v = routesScreen(); break;
            case "diag": v = diagnosticsScreen(); break;
            default: v = homeScreen();
        }
        root.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private LinearLayout column(String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.addView(u.appBar(title, this::back));
        return c;
    }

    private View scrolled(String title, View... rows) {
        LinearLayout c = column(title);
        ScrollView sv = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(u.gap(8));
        for (View r : rows) body.addView(r);
        sv.addView(body);
        c.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return c;
    }

    // ------------------------------------------------------------------ Home

    private View homeScreen() {
        boolean warp = settings.mode == WarpOverrides.MODE_WARP;
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        // header: wordmark + LITE badge on the left, round help / menu buttons on the right
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(u.dp(20), u.dp(14), u.dp(14), 0);
        TextView name = u.tv("1.1.1.1", 22, u.text, true);
        name.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        bar.addView(name);
        TextView badge = u.tv("LITE", 10, 0xFFFFFFFF, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(u.dp(7), u.dp(2), u.dp(7), u.dp(2));
        badge.setBackground(u.rounded(Ui.ORANGE, 8, 8));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.leftMargin = u.dp(8);
        bar.addView(badge, bp);
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        bar.addView(roundButton("?", () -> open(HELP_URL)));
        bar.addView(roundButton("☰", () -> go("settings")));
        page.addView(bar);

        // mode: 1.1.1.1 | WARP
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.setMargins(u.dp(20), u.dp(16), u.dp(20), 0);
        page.addView(modeSwitch(), mp);

        // hero: power button, status, one line of explanation
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.CENTER);
        mid.setPadding(u.dp(24), 0, u.dp(24), 0);
        Ui.PowerButton power = new Ui.PowerButton(this, u, 230);
        power.setState(state == State.ON, state == State.CONNECTING);
        power.setOnClickListener(v -> onToggle());
        mid.addView(power, new LinearLayout.LayoutParams(power.side(), power.side()));

        String status = state == State.ON ? "Connected" : state == State.CONNECTING ? "Connecting…" : "Disconnected";
        TextView st = u.tv(status, 26, u.text, false);
        st.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        st.setGravity(Gravity.CENTER);
        st.setPadding(0, u.dp(4), 0, 0);
        mid.addView(st, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        String line;
        if (error != null) line = error;
        else if (state == State.ON) line = warp ? "Your Internet is private." : "DNS is resolved over HTTPS.";
        else if (state == State.CONNECTING) line = "Confirm the request in Magic V2Ray.";
        else line = warp ? "Your Internet is not private." : "Your DNS is not private.";
        TextView sl = u.tv(line, 14, error != null ? Ui.RED : u.sub, false);
        sl.setGravity(Gravity.CENTER);
        sl.setPadding(0, u.dp(8), 0, 0);
        mid.addView(sl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(mid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // details card: tap = DNS settings
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(u.rounded(u.surface, 22, 22));
        card.setElevation(u.dp(2));
        card.setPadding(u.dp(18), u.dp(4), u.dp(18), u.dp(4));
        card.addView(infoRow("Mode", warp ? "WARP · WireGuard" : "1.1.1.1 · SOCKS5 bridge", true));
        card.addView(infoRow("DNS", WarpOverrides.dnsLabel(settings), true));
        card.addView(infoRow("Filter", WarpOverrides.familiesLabel(settings.families), false));
        card.setOnClickListener(v -> go("dns"));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.setMargins(u.dp(16), u.dp(8), u.dp(16), u.dp(18));
        page.addView(card, cp);
        return page;
    }

    private View roundButton(String glyph, Runnable click) {
        TextView t = u.tv(glyph, 18, u.text, false);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(u.surface);
        t.setBackground(g);
        t.setElevation(u.dp(1));
        t.setOnClickListener(v -> click.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(u.dp(40), u.dp(40));
        lp.leftMargin = u.dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    /** The two-way switch under the header: 1.1.1.1 (DNS only) or WARP. */
    private View modeSwitch() {
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackground(u.rounded(u.track, 22, 22));
        track.setPadding(u.dp(4), u.dp(4), u.dp(4), u.dp(4));
        track.addView(modeTab("1.1.1.1", WarpOverrides.MODE_1111), new LinearLayout.LayoutParams(0, u.dp(40), 1f));
        track.addView(modeTab("WARP", WarpOverrides.MODE_WARP), new LinearLayout.LayoutParams(0, u.dp(40), 1f));
        return track;
    }

    private View modeTab(String label, final int mode) {
        boolean sel = settings.mode == mode;
        TextView t = u.tv(label, 15, sel ? u.text : u.sub, sel);
        t.setGravity(Gravity.CENTER);
        if (sel) {
            t.setBackground(u.rounded(u.surface, 18, 18));
            t.setElevation(u.dp(2));
        }
        t.setOnClickListener(v -> chooseMode(mode));
        return t;
    }

    private View infoRow(String label, String value, boolean divider) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, u.dp(12), 0, u.dp(12));
        r.addView(u.tv(label, 13, u.sub, false), new LinearLayout.LayoutParams(u.dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView v = u.tv(value, 14, u.text, false);
        v.setGravity(Gravity.END);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        r.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(r);
        if (divider) {
            View d = new View(this);
            d.setBackgroundColor(u.divider);
            box.addView(d, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
        return box;
    }

    // ------------------------------------------------------------------ Settings

    private View settingsScreen() {
        LinearLayout c = column("Settings");
        ScrollView sv = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(u.gap(8));
        body.addView(u.choiceRow("1.1.1.1", "DNS only. A local SOCKS5 bridge intercepts every DNS query (any:53) and resolves it over HTTPS (DoH). Your traffic is not tunnelled.",
                settings.mode == WarpOverrides.MODE_1111, () -> chooseMode(WarpOverrides.MODE_1111)));
        body.addView(u.choiceRow("WARP", "Everything from 1.1.1.1 plus a WireGuard tunnel to Cloudflare WARP for the whole device.",
                settings.mode == WarpOverrides.MODE_WARP, () -> chooseMode(WarpOverrides.MODE_WARP)));
        body.addView(u.gap(16));
        body.addView(u.row("Account", null, null, () -> go("account")));
        body.addView(u.row("Advanced", null, null, () -> go("advanced")));
        body.addView(u.gap(8));
        body.addView(u.row("Version", null, versionName(), null));
        sv.addView(body);
        c.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return c;
    }

    private void chooseMode(int mode) {
        if (mode == settings.mode) return;
        if (state != State.OFF) {
            Toast.makeText(this, "Disconnect first to change the mode", Toast.LENGTH_SHORT).show();
            return;
        }
        settings.mode = mode;
        store.saveSettings(settings);
        render();
    }

    private String versionName() {
        try {
            android.content.pm.PackageInfo p = getPackageManager().getPackageInfo(getPackageName(), 0);
            return p.versionName + " (" + p.getLongVersionCode() + ")";
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ Account

    private View accountScreen() {
        WarpAccount a = store.account();
        boolean plus = a != null && a.warpPlus;
        View head = u.row(plus ? "WARP+" : "WARP", plus ? "Your WARP+ key is active" : "Enter a WARP+ key to get an even better Internet", null, null);
        View key = u.row("Key", null, a == null || a.license.isEmpty() ? "—" : a.license, () -> enterLicense());
        View devices = u.row("Manage devices", null, null, () -> showDevice());
        return scrolled("Account", head, key, devices);
    }

    private void enterLicense() {
        WarpAccount a = store.account();
        if (a == null || a.id.isEmpty()) {
            Toast.makeText(this, "Connect once in WARP mode first (that registers this device)", Toast.LENGTH_LONG).show();
            return;
        }
        final EditText in = new EditText(this);
        in.setHint("xxxxxxxx-xxxxxxxx-xxxxxxxx");
        in.setSingleLine(true);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        new AlertDialog.Builder(this).setTitle("WARP+ key").setView(in)
                .setPositiveButton("Apply", (d, w) -> {
                    final String k = in.getText().toString().trim();
                    if (k.isEmpty()) return;
                    new Thread(() -> {
                        try {
                            WarpApi.setLicense(a, k);
                            store.saveAccount(a);
                            ui.post(() -> { Toast.makeText(this, "Key applied", Toast.LENGTH_SHORT).show(); render(); });
                        } catch (IOException e) {
                            ui.post(() -> Toast.makeText(this, "Key refused: " + e.getMessage(), Toast.LENGTH_LONG).show());
                        }
                    }, "warp-license").start();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void showDevice() {
        WarpAccount a = store.account();
        String msg = a == null ? "No device registered yet. It is created the first time you connect."
                : "Device ID: " + (a.id.isEmpty() ? "—" : a.id)
                + "\nEndpoint: " + a.host + ":" + a.port
                + "\nCreated: " + DateFormat.getDateTimeInstance().format(new Date(a.createdAt));
        new AlertDialog.Builder(this).setTitle("This device").setMessage(msg).setPositiveButton("OK", null).show();
    }

    // ------------------------------------------------------------------ Advanced

    private View advancedScreen() {
        return scrolled("Advanced",
                u.row("Help center", null, null, () -> open(HELP_URL)),
                u.switchRow("Use dark theme", store.dark(), on -> {
                    store.setDark(on);
                    applyTheme();
                    render();
                }),
                u.row("Connection options", null, null, () -> go("conn")),
                u.row("Diagnostics", null, null, () -> go("diag")));
    }

    // ------------------------------------------------------------------ Connection options

    private View connectionScreen() {
        return scrolled("Connection options",
                u.row("Manage excluded apps", settings.excludedApps.isEmpty() ? null : settings.excludedApps.size() + " selected", null, () -> go("apps")),
                u.row("Manage excluded routes", settings.mode == WarpOverrides.MODE_1111 ? "WARP mode only" : null, settings.excludedRoutes.isEmpty() ? null : settings.excludedRoutes.size() + " routes", () -> go("routes")),
                u.row("DNS settings", null, null, () -> go("dns")),
                u.switchRow("Use fallback resolvers for NXDomain", settings.fallbackResolvers, on -> {
                    settings.fallbackResolvers = on;
                    store.saveSettings(settings);
                }),
                u.row("Tunnel protocol", null, "WireGuard", null),
                u.row("Reset security keys", null, null, this::resetKeys),
                u.row("Reset all settings", null, null, this::resetAll));
    }

    private void resetKeys() {
        new AlertDialog.Builder(this).setTitle("Reset security keys")
                .setMessage("A new WireGuard key pair and a new WARP registration are created the next time you connect.")
                .setPositiveButton("Reset", (d, w) -> {
                    final WarpAccount old = store.account();
                    store.clearAccount();
                    if (state != State.OFF) onToggle();
                    if (old != null) new Thread(() -> WarpApi.deleteDevice(old), "warp-delete").start();
                    Log.add("keys reset");
                    Toast.makeText(this, "Keys reset", Toast.LENGTH_SHORT).show();
                    render();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void resetAll() {
        new AlertDialog.Builder(this).setTitle("Reset all settings")
                .setMessage("Mode, DNS, excluded apps and routes, and the theme go back to their defaults.")
                .setPositiveButton("Reset", (d, w) -> {
                    store.resetSettings();
                    settings = store.settings();
                    applyTheme();
                    render();
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ------------------------------------------------------------------ DNS settings

    private View dnsScreen() {
        String[] fam = {"None", "Malware", "Malware and adult content"};
        String[] proto = {"DNS over WARP", "DNS over HTTPS (DoH)"};
        final boolean bridge = settings.mode == WarpOverrides.MODE_1111;
        int eff = settings.effectiveProtocol();
        String protoSub;
        if (bridge) protoSub = "1.1.1.1 mode: DNS (any:53) is intercepted by the local SOCKS5 bridge and resolved over HTTPS.";
        else if (eff == WarpOverrides.PROTO_DOH) protoSub = "DNS is hijacked and resolved by Cloudflare's DoH endpoint.";
        else protoSub = "Every DNS query (any:53) is rewritten to " + WarpOverrides.firstIp(settings.families) + ":53 and sent through the WARP tunnel.";

        View families = u.row("1.1.1.1 for Families", null, fam[settings.families],
                () -> pick("1.1.1.1 for Families", fam, settings.families, i -> settings.families = i));
        View protocol = u.row("Protocol options", protoSub, proto[eff], bridge
                ? () -> Toast.makeText(this, "The 1.1.1.1 mode always uses DNS over HTTPS", Toast.LENGTH_SHORT).show()
                : () -> pick("Protocol options", proto, settings.dnsProtocol, i -> settings.dnsProtocol = i));
        View resolver = u.row("Resolver", null, eff == WarpOverrides.PROTO_DOH
                ? WarpOverrides.dohHost(settings.families) : WarpOverrides.plainDns(settings.families).replace(",", ", "), null);
        TextView hint = u.tv("FakeDNS is always off, the DNS strategy is UseIP and IPv6 is always on. Changes apply the next time you connect.", 12, u.sub, false);
        hint.setPadding(u.dp(16), u.dp(12), u.dp(16), u.dp(12));
        return scrolled("DNS settings", families, protocol, resolver, hint);
    }

    private interface IntSetter {
        void set(int i);
    }

    private void pick(String title, String[] items, int current, IntSetter setter) {
        new AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(items, current, (d, which) -> {
                    setter.set(which);
                    store.saveSettings(settings);
                    d.dismiss();
                    render();
                }).show();
    }

    // ------------------------------------------------------------------ Excluded apps

    private static final class AppItem {
        String label, pkg;
    }

    private View appsScreen() {
        LinearLayout c = column("Excluded apps");
        TextView hint = u.tv("Selected apps skip the VPN. Changes apply the next time you connect.", 12, u.sub, false);
        hint.setPadding(u.dp(16), u.dp(10), u.dp(16), u.dp(10));
        c.addView(hint);
        final ListView list = new ListView(this);
        list.setDivider(new android.graphics.drawable.ColorDrawable(u.divider));
        list.setDividerHeight(1);
        c.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        new Thread(() -> {
            final List<AppItem> items = installedApps();
            ui.post(() -> {
                if (!"apps".equals(top())) return;
                list.setAdapter(new BaseAdapter() {
                    @Override public int getCount() { return items.size(); }
                    @Override public Object getItem(int p) { return items.get(p); }
                    @Override public long getItemId(int p) { return p; }
                    @Override
                    public View getView(int p, View convert, ViewGroup parent) {
                        final AppItem it = items.get(p);
                        LinearLayout r = new LinearLayout(MainActivity.this);
                        r.setGravity(Gravity.CENTER_VERTICAL);
                        r.setBackgroundColor(u.surface);
                        r.setPadding(u.dp(16), u.dp(8), u.dp(16), u.dp(8));
                        LinearLayout col = new LinearLayout(MainActivity.this);
                        col.setOrientation(LinearLayout.VERTICAL);
                        col.addView(u.tv(it.label, 16, u.text, false));
                        col.addView(u.tv(it.pkg, 11, u.sub, false));
                        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                        final CheckBox cb = new CheckBox(MainActivity.this);
                        cb.setChecked(settings.excludedApps.contains(it.pkg));
                        cb.setClickable(false);
                        r.addView(cb);
                        r.setOnClickListener(v -> {
                            if (settings.excludedApps.contains(it.pkg)) settings.excludedApps.remove(it.pkg);
                            else settings.excludedApps.add(it.pkg);
                            cb.setChecked(settings.excludedApps.contains(it.pkg));
                            store.saveSettings(settings);
                        });
                        return r;
                    }
                });
            });
        }, "apps").start();
        return c;
    }

    private List<AppItem> installedApps() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<AppItem> out = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            if (ai.packageName.equals(getPackageName()) || !seen.add(ai.packageName)) continue;
            AppItem it = new AppItem();
            it.pkg = ai.packageName;
            it.label = String.valueOf(pm.getApplicationLabel(ai));
            out.add(it);
        }
        Collections.sort(out, (x, y) -> x.label.compareToIgnoreCase(y.label));
        return out;
    }

    // ------------------------------------------------------------------ Excluded routes

    private View routesScreen() {
        LinearLayout c = column("Excluded routes");
        ScrollView sv = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        TextView hint = u.tv("Addresses or ranges (for example 192.168.0.0/16) that bypass the VPN. Tap a route to remove it.", 12, u.sub, false);
        hint.setPadding(u.dp(16), u.dp(10), u.dp(16), u.dp(10));
        body.addView(hint);
        for (final String r : new ArrayList<>(settings.excludedRoutes)) {
            body.addView(u.row(r, null, "Remove", () -> {
                settings.excludedRoutes.remove(r);
                store.saveSettings(settings);
                render();
            }));
        }
        body.addView(u.gap(8));
        body.addView(u.row("Add route", null, "+", this::addRoute));
        sv.addView(body);
        c.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return c;
    }

    private void addRoute() {
        final EditText in = new EditText(this);
        in.setHint("10.0.0.0/8");
        in.setSingleLine(true);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        new AlertDialog.Builder(this).setTitle("Add route").setView(in)
                .setPositiveButton("Add", (d, w) -> {
                    String v = in.getText().toString().trim();
                    if (!WarpOverrides.validRoute(v)) {
                        Toast.makeText(this, "Not an IP address or CIDR range", Toast.LENGTH_LONG).show();
                    } else if (!settings.excludedRoutes.contains(v)) {
                        settings.excludedRoutes.add(v);
                        store.saveSettings(settings);
                    }
                    render();
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ------------------------------------------------------------------ Diagnostics

    private View diagnosticsScreen() {
        pollStateOnce();
        LinearLayout c = column("Diagnostics");
        ScrollView sv = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(u.dp(16), u.dp(12), u.dp(16), u.dp(12));
        WarpAccount a = store.account();
        MagicV2Ray.Info m = magic;
        StringBuilder sb = new StringBuilder();
        sb.append(m == null ? "Magic V2Ray: checking…" : m.summary()).append('\n');
        sb.append("State: ").append(state).append('\n');
        sb.append("Mode: ").append(settings.mode == WarpOverrides.MODE_WARP ? "WARP" : "1.1.1.1 (DNS bridge)").append('\n');
        sb.append("DNS: ").append(WarpOverrides.dnsLabel(settings)).append('\n');
        sb.append("Bridge: ").append(Socks5Service.listening ? "listening on 127.0.0.1:" + WarpOverrides.SOCKS_PORT : "stopped").append('\n');
        sb.append("Always on: FakeDNS off, UseIP, IPv6\n");
        if (a != null) {
            sb.append("Endpoint: ").append(a.host).append(':').append(a.port).append('\n');
            sb.append("Address: ").append(a.address).append('\n');
            sb.append("Reserved: ").append(a.reserved.isEmpty() ? "—" : a.reserved).append('\n');
        }
        sb.append("\n").append(Log.text());
        TextView t = u.tv(sb.toString(), 12, u.text, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        body.addView(t);
        sv.addView(body);
        c.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return c;
    }

    private boolean polledForDiag;

    /** Diagnostics refreshes Magic V2Ray's info once per visit (render -> info -> render would loop otherwise). */
    private void pollStateOnce() {
        if (polledForDiag) {
            polledForDiag = false;
            return;
        }
        polledForDiag = true;
        new Thread(() -> {
            magic = MagicV2Ray.info(getApplicationContext());
            ui.post(() -> { if ("diag".equals(top())) render(); });
        }, "magic-info2").start();
    }

    // ------------------------------------------------------------------ helpers

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException e) {
            Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show();
        }
    }
}
