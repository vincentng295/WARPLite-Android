package com.warplite.app;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The user's settings (as plain data) and the Magic V2Ray {@code overrides} JSON they turn into.
 * No Android dependency, so the host tests can check it. See docs/magic-v2ray-overrides-api.md.
 *
 * <h2>Two modes</h2>
 * <pre>
 *  1.1.1.1  Magic V2Ray API level 2 (default, {@link #json(Settings, boolean) bridge = false}):
 *           node = the node in use, else freedom://localhost; no local proxy at all
 *           traffic.hijackDns = true           Xray answers the DNS queries, over HTTPS (https://1.1.1.1/dns-query, by IP)
 *           your routing rules stay in force
 *
 *  1.1.1.1  older Magic V2Ray (bridge = true):
 *           node = socks://127.0.0.1:5353 (our local bridge, see Socks5Server)
 *           traffic.hijackDns = false          every DNS query stays an ordinary port-53 packet and reaches the bridge
 *           routing (replace)                  port 53 -> proxy (the bridge), everything else -> direct
 *           the bridge answers each query by asking Cloudflare over HTTPS (DoH); this app is kept out of the VPN
 *
 *  WARP     node = wireguard://... (Cloudflare WARP, see WireGuardUri), or, when a node of the user is in use,
 *           chain://... with it (see ChainUri, NodePicker.chainable)
 *           traffic.hijackDns = true           Xray answers the DNS queries
 *           DNS over WARP   foreignDns 1.1.1.1 (or .2 / .3), dnsViaProxy = true
 *                           = every any:53 query is rewritten to 1.1.1.1:53 and sent through the proxy outbound
 *           DNS over HTTPS  foreignDns = directDns = https://dns.cloudflare.com/dns-query (or the Families endpoint),
 *                           dnsViaProxy = false
 * </pre>
 * <h2>Always sent, in both modes</h2>
 * <pre>
 *  traffic.fakeDnsLocal = false      FakeDNS is always off
 *  traffic.queryStrategy = UseIP     DNS strategy
 *  network.enableIPv6 = true         IPv6 is always on
 * </pre>
 * Optional parts: excluded apps ({@code exclude}), excluded routes (WARP mode only, {@code routing} prepend),
 * "fallback resolvers off" ({@code dnsDisableFallback}).
 * Everything is temporary: Magic V2Ray applies it to a copy of its settings for the length of the session.
 */
final class WarpOverrides {

    static final int MODE_1111 = 1;          // local SOCKS5 bridge: any:53 -> DNS over HTTPS (no tunnel)
    static final int MODE_WARP = 0;          // WireGuard tunnel to Cloudflare WARP

    static final int FAMILIES_NONE = 0, FAMILIES_MALWARE = 1, FAMILIES_ADULT = 2;
    static final int PROTO_WARP = 0, PROTO_DOH = 1;

    /** The local bridge of the 1.1.1.1 mode. */
    static final int SOCKS_PORT = 5353;
    static final String SOCKS_URI = "socks://127.0.0.1:" + SOCKS_PORT;

    /** Settings the UI edits. */
    static final class Settings {
        int mode = MODE_WARP;
        int families = FAMILIES_NONE;
        int dnsProtocol = PROTO_WARP;            // only used in WARP mode
        boolean fallbackResolvers = true;
        /** WARP mode: chain WARP with the node Magic V2Ray is using (API level 2), when there is one. */
        boolean chainUserNode = true;
        /** Chain order: false (default) = device -> your node -> WARP, true = device -> WARP -> your node. */
        boolean chainWarpFirst = false;
        final List<String> excludedApps = new ArrayList<>();
        final List<String> excludedRoutes = new ArrayList<>();

        /** The 1.1.1.1 mode has no tunnel, so it always resolves over HTTPS. */
        int effectiveProtocol() {
            return mode == MODE_1111 ? PROTO_DOH : dnsProtocol;
        }
    }

    private static final Pattern PKG = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3}\\.){3}\\d{1,3}(/\\d{1,2})?");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:]{2,39}(/\\d{1,3})?");

    private WarpOverrides() {
    }

    // ------------------------------------------------------------------ DNS endpoints

    /** Cloudflare's DoH host: standard, 1.1.1.1 for Families "Malware" (security) and "Malware and adult content" (family). */
    static String dohHost(int families) {
        switch (families) {
            case FAMILIES_ADULT: return "family.cloudflare-dns.com";
            case FAMILIES_MALWARE: return "security.cloudflare-dns.com";
            default: return "dns.cloudflare.com";
        }
    }

    static String dohUrl(int families) {
        return "https://" + dohHost(families) + "/dns-query";
    }

    /** Same resolver by IP (certificate has IP SANs): the bridge uses it when the host name cannot be resolved. */
    static String dohFallbackUrl(int families) {
        return "https://" + firstIp(families) + "/dns-query";
    }

    static String firstIp(int families) {
        return families == FAMILIES_ADULT ? "1.1.1.3" : families == FAMILIES_MALWARE ? "1.1.1.2" : "1.1.1.1";
    }

    /** Plain DNS (port 53) servers of "DNS over WARP". */
    static String plainDns(int families) {
        switch (families) {
            case FAMILIES_ADULT: return "1.1.1.3,1.0.0.3";
            case FAMILIES_MALWARE: return "1.1.1.2,1.0.0.2";
            default: return "1.1.1.1,1.0.0.1";
        }
    }

    static String familiesLabel(int families) {
        return families == FAMILIES_ADULT ? "Malware and adult content" : families == FAMILIES_MALWARE ? "Malware" : "None";
    }

    /** Short label for the home screen / diagnostics. */
    static String dnsLabel(Settings s) {
        return s.effectiveProtocol() == PROTO_DOH ? "DoH · " + dohHost(s.families) : firstIp(s.families) + " over WARP";
    }

    // ------------------------------------------------------------------ validation

    static boolean validPackage(String s) {
        return s != null && s.length() <= 255 && PKG.matcher(s).matches();
    }

    /** A single IPv4 / IPv6 address or CIDR (loose on purpose: Magic V2Ray / Xray do the real parsing). */
    static boolean validRoute(String s) {
        if (s == null) return false;
        if (IPV4.matcher(s).matches()) {
            String[] parts = s.split("/")[0].split("\\.");
            for (String p : parts) if (Integer.parseInt(p) > 255) return false;
            return !s.contains("/") || Integer.parseInt(s.split("/")[1]) <= 32;
        }
        if (s.contains(":") && IPV6.matcher(s).matches()) {
            return !s.contains("/") || Integer.parseInt(s.split("/")[1]) <= 128;
        }
        return false;
    }

    // ------------------------------------------------------------------ the JSON

    /** The overrides JSON text with the local bridge for the 1.1.1.1 mode (Magic V2Ray older than API level 2). */
    static String json(Settings s) {
        return json(s, true);
    }

    /**
     * The overrides JSON text (never null: the always-on settings are part of it).
     *
     * @param bridge only for the 1.1.1.1 mode: true = the local SOCKS5 bridge answers port 53 (old Magic V2Ray),
     *               false = Magic V2Ray API level 2: Xray's own DNS module resolves over HTTPS, no local proxy
     */
    static String json(Settings s, boolean bridge) {
        boolean warp = s.mode == MODE_WARP;
        boolean hijack = warp || !bridge;
        boolean doh = s.effectiveProtocol() == PROTO_DOH;

        StringBuilder sb = new StringBuilder("{\"traffic\":{");
        sb.append("\"hijackDns\":").append(hijack)
                .append(",\"fakeDnsLocal\":false")
                .append(",\"queryStrategy\":\"UseIP\"");
        if (hijack) {
            if (doh) {
                // WARP: by host name (the tunnel or the node resolves it); 1.1.1.1 mode: by IP (the certificate has IP
                // SANs), so there is nothing to resolve before the first query can be answered.
                String url = str(warp ? dohUrl(s.families) : dohFallbackUrl(s.families));
                sb.append(",\"foreignDns\":").append(url).append(",\"directDns\":").append(url)
                        .append(",\"dnsViaProxy\":false");
            } else {
                sb.append(",\"foreignDns\":").append(str(plainDns(s.families))).append(",\"dnsViaProxy\":true");
            }
            if (!s.fallbackResolvers) sb.append(",\"dnsDisableFallback\":true");
        }
        sb.append("},\"network\":{\"enableIPv6\":true}");

        List<String> apps = new ArrayList<>();
        for (String a : s.excludedApps) if (validPackage(a) && apps.size() < 500) apps.add(a);
        if (!apps.isEmpty()) {
            sb.append(",\"exclude\":{\"mode\":\"add\",\"enabled\":true,\"apps\":[");
            for (int i = 0; i < apps.size(); i++) sb.append(i == 0 ? "" : ",").append(str(apps.get(i)));
            sb.append("]}");
        }

        if (!warp && bridge) {
            // 1.1.1.1 mode: only port 53 goes to the bridge, everything else leaves the VPN as direct.
            // "else" takes two rules: Magic V2Ray drops a rule with no match condition, and an ip rule never matches
            // a destination that still has a domain name.
            sb.append(",\"routing\":{\"mode\":\"replace\",\"rules\":[")
                    .append("{\"remarks\":\"DNS (port 53) -> bridge\",\"port\":\"53\",\"outboundTag\":\"proxy\"},")
                    .append("{\"remarks\":\"else direct (ip)\",\"ip\":\"0.0.0.0/0,::/0\",\"outboundTag\":\"direct\"},")
                    .append("{\"remarks\":\"else direct (domain)\",\"domain\":\"regexp:.+\",\"outboundTag\":\"direct\"}")
                    .append("]}");
        } else if (warp) {
            List<String> routes = new ArrayList<>();
            for (String r : s.excludedRoutes) if (validRoute(r) && routes.size() < 500) routes.add(r);
            if (!routes.isEmpty()) {
                sb.append(",\"routing\":{\"mode\":\"prepend\",\"rules\":[");
                for (int i = 0; i < routes.size(); i++) {
                    sb.append(i == 0 ? "" : ",")
                            .append("{\"remarks\":\"excluded route\",\"ip\":").append(str(routes.get(i)))
                            .append(",\"outboundTag\":\"direct\"}");
                }
                sb.append("]}");
            }
        }
        return sb.append("}").toString();
    }

    /** JSON string literal. */
    static String str(String v) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }
}
