package com.warplite.app;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Host-side tests (plain JDK, no Android, no JSON library) of the pure classes:
 * X25519 (RFC 7748 vectors), Base64Lite, WireGuardUri (same link as toV2rayNG() in worker.js) and
 * WarpOverrides (JSON well-formed, only what Magic V2Ray's whitelist accepts).
 */
public class CoreTest {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
        if (!ok) failures++;
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    private static boolean balanced(String s) {
        StringBuilder stack = new StringBuilder();
        boolean str = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (str) {
                if (c == '\\') i++;
                else if (c == '"') str = false;
            } else if (c == '"') str = true;
            else if (c == '{' || c == '[') stack.append(c);
            else if (c == '}' || c == ']') {
                if (stack.length() == 0) return false;
                char o = stack.charAt(stack.length() - 1);
                if ((c == '}') != (o == '{')) return false;
                stack.setLength(stack.length() - 1);
            }
        }
        return !str && stack.length() == 0;
    }

    public static void main(String[] args) {
        // ---- X25519
        byte[] out = X25519.scalarMult(
                hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
                hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"));
        check(Arrays.equals(out, hex("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552")),
                "X25519 RFC 7748 section 5.2 vector 1");
        byte[] pub = X25519.publicKey(hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"));
        check(Arrays.equals(pub, hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")),
                "X25519 RFC 7748 section 6.1 public key (Alice)");
        byte[] k = X25519.generatePrivateKey(new SecureRandom());
        check(k.length == 32 && (k[0] & 7) == 0 && (k[31] & 0x80) == 0 && (k[31] & 0x40) != 0, "generated private key is clamped");
        // Diffie-Hellman agrees both ways
        byte[] k2 = X25519.generatePrivateKey(new SecureRandom());
        check(Arrays.equals(X25519.scalarMult(k, X25519.publicKey(k2)), X25519.scalarMult(k2, X25519.publicKey(k))),
                "shared secret is symmetric");

        // ---- Base64Lite
        byte[] rnd = new byte[37];
        new SecureRandom().nextBytes(rnd);
        String std = java.util.Base64.getEncoder().encodeToString(rnd);
        check(Base64Lite.encode(rnd).equals(std), "Base64Lite.encode == java.util.Base64");
        check(Arrays.equals(Base64Lite.decode(std), rnd), "Base64Lite.decode round trip");
        check(Base64Lite.decode("***") == null, "Base64Lite.decode rejects garbage");
        check(WireGuardUri.reservedFromClientId("AAECAw==").equals("0,1,2"), "reserved from client_id (first 3 bytes)");
        check(WireGuardUri.reservedFromClientId("AA==").isEmpty(), "reserved: too short -> empty");

        // ---- wireguard:// link
        WarpAccount a = new WarpAccount();
        a.privateKey = "aB+/cd0123456789aB+/cd0123456789aB+/cd01234=";
        a.peerPublicKey = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=";
        a.host = "engage.cloudflareclient.com";
        a.port = "2408";
        a.reserved = "12,34,56";
        a.address = "172.16.0.2/32,2606:4700:110:8a36::2/128";
        String link = WireGuardUri.build(a, "WARP Lite");
        check(link.startsWith("wireguard://aB%2B%2Fcd0123456789aB%2B%2Fcd0123456789aB%2B%2Fcd01234%3D@engage.cloudflareclient.com:2408?"),
                "link: encoded private key @ host:port");
        check(link.contains("address=172.16.0.2%2F32%2C2606%3A4700%3A110%3A8a36%3A%3A2%2F128"), "link: address");
        check(link.contains("publickey=bmXOC%2BF1FxEMF9dyiK2H5%2F1SUtzH0JuVo51h2wPfgyo%3D"), "link: publickey");
        check(link.contains("mtu=1280") && link.contains("reserved=12%2C34%2C56"), "link: mtu + reserved");
        check(link.endsWith("#WARP%20Lite"), "link: fragment name");
        a.reserved = "";
        check(!WireGuardUri.build(a, "x").contains("reserved="), "link: no reserved when empty");

        // ---- overrides: WARP mode, DNS over WARP (default)
        WarpOverrides.Settings s = new WarpOverrides.Settings();
        String j = WarpOverrides.json(s);
        check(balanced(j), "WARP/WARP: balanced JSON");
        check(j.equals("{\"traffic\":{\"hijackDns\":true,\"fakeDnsLocal\":false,\"queryStrategy\":\"UseIP\","
                + "\"foreignDns\":\"1.1.1.1,1.0.0.1\",\"dnsViaProxy\":true},\"network\":{\"enableIPv6\":true}}"), "WARP/WARP: " + j);
        s.families = WarpOverrides.FAMILIES_ADULT;
        check(WarpOverrides.json(s).contains("\"1.1.1.3,1.0.0.3\""), "WARP/WARP families: malware + adult -> 1.1.1.3");

        // ---- WARP mode, DNS over HTTPS
        s.families = WarpOverrides.FAMILIES_NONE;
        s.dnsProtocol = WarpOverrides.PROTO_DOH;
        j = WarpOverrides.json(s);
        check(j.contains("\"hijackDns\":true") && j.contains("\"directDns\":\"https://dns.cloudflare.com/dns-query\"")
                && j.contains("\"foreignDns\":\"https://dns.cloudflare.com/dns-query\"") && j.contains("\"dnsViaProxy\":false"),
                "WARP/DoH: hijack on, direct DNS = https://dns.cloudflare.com/dns-query");
        s.families = WarpOverrides.FAMILIES_MALWARE;
        check(WarpOverrides.json(s).contains("https://security.cloudflare-dns.com/dns-query"), "WARP/DoH Families malware endpoint");
        s.families = WarpOverrides.FAMILIES_ADULT;
        check(WarpOverrides.json(s).contains("https://family.cloudflare-dns.com/dns-query"), "WARP/DoH Families adult endpoint");
        check(WarpOverrides.dohFallbackUrl(WarpOverrides.FAMILIES_ADULT).equals("https://1.1.1.3/dns-query"), "DoH fallback by IP");

        // ---- always on, every mode
        for (int mode : new int[]{WarpOverrides.MODE_WARP, WarpOverrides.MODE_1111}) {
            for (int proto : new int[]{WarpOverrides.PROTO_WARP, WarpOverrides.PROTO_DOH}) {
                WarpOverrides.Settings t = new WarpOverrides.Settings();
                t.mode = mode;
                t.dnsProtocol = proto;
                String x = WarpOverrides.json(t);
                check(balanced(x) && x.contains("\"fakeDnsLocal\":false") && x.contains("\"queryStrategy\":\"UseIP\"")
                        && x.contains("\"enableIPv6\":true"), "always: FakeDNS off, UseIP, IPv6 on (mode " + mode + ", protocol " + proto + ")");
            }
        }

        // ---- 1.1.1.1 mode: bridge
        WarpOverrides.Settings b = new WarpOverrides.Settings();
        b.mode = WarpOverrides.MODE_1111;
        b.dnsProtocol = WarpOverrides.PROTO_WARP;                       // ignored in this mode
        j = WarpOverrides.json(b);
        check(j.contains("\"hijackDns\":false"), "1.1.1.1: Hijack DNS off (queries reach the bridge)");
        check(b.effectiveProtocol() == WarpOverrides.PROTO_DOH, "1.1.1.1: always DNS over HTTPS");
        check(j.contains("\"mode\":\"replace\"") && j.indexOf("\"port\":\"53\",\"outboundTag\":\"proxy\"") > 0
                && j.indexOf("0.0.0.0/0,::/0") > j.indexOf("\"port\":\"53\"") && j.indexOf("regexp:.+") > j.indexOf("0.0.0.0/0,::/0"),
                "1.1.1.1: routing replace: port 53 -> proxy, then else direct (ip, domain)");
        check(!j.contains("foreignDns") && !j.contains("directDns"), "1.1.1.1: no DNS servers handed to Xray");
        check(WarpOverrides.SOCKS_URI.equals("socks://127.0.0.1:5353"), "bridge node uri");

        // ---- optional parts
        s = new WarpOverrides.Settings();
        s.fallbackResolvers = false;
        s.excludedApps.add("com.example.app");
        s.excludedApps.add("not a package");
        s.excludedRoutes.add("192.168.0.0/16");
        s.excludedRoutes.add("300.1.1.1");
        s.excludedRoutes.add("fd00::/8");
        j = WarpOverrides.json(s);
        check(balanced(j), "full: balanced JSON");
        check(j.contains("\"dnsDisableFallback\":true"), "fallback resolvers off is sent");
        check(j.contains("\"apps\":[\"com.example.app\"]") && !j.contains("not a package"), "excluded apps: valid only");
        check(j.contains("\"ip\":\"192.168.0.0/16\"") && j.contains("\"ip\":\"fd00::/8\"") && !j.contains("300.1.1.1"), "excluded routes: valid only");
        check(!j.contains("\"outboundTag\":\"proxy\"") && j.contains("\"outboundTag\":\"direct\""), "routes only ever go direct");
        s.mode = WarpOverrides.MODE_1111;
        j = WarpOverrides.json(s);
        check(j.contains("com.example.app") && !j.contains("192.168.0.0/16") && !j.contains("dnsDisableFallback"),
                "1.1.1.1: apps kept, routes / fallback not used");
        check(WarpOverrides.validPackage("com.huskydg.magicv2ray") && !WarpOverrides.validPackage("nodots"), "validPackage");
        check(WarpOverrides.validRoute("10.0.0.0/8") && !WarpOverrides.validRoute("10.0.0.0/33") && !WarpOverrides.validRoute("host.name"), "validRoute");


        // ---- API level 2: node choice + chain
        check(NodePicker.pick(false, "", "").equals(NodePicker.FREEDOM), "pick: not connected -> freedom");
        check(NodePicker.pick(true, "direct", "freedom://localhost").equals(NodePicker.FREEDOM), "pick: direct -> freedom");
        check(NodePicker.pick(true, "session", "vless://hidden").equals(NodePicker.FREEDOM), "pick: hidden -> freedom");
        check(NodePicker.pick(true, "selected", "nodeid://k3x").equals("nodeid://k3x"), "pick: selected node kept");
        check(NodePicker.chainable(true, "selected", "nodeid://k3x", "vless").equals("nodeid://k3x"), "chainable: vless node");
        check(NodePicker.chainable(false, "selected", "nodeid://k3x", "vless") == null, "chainable: not connected -> null");
        check(NodePicker.chainable(true, "direct", "freedom://localhost", "") == null, "chainable: no-proxy node -> null");
        check(NodePicker.chainable(true, "selected", "nodeid://a", "wireguard") == null, "chainable: wireguard node -> null");
        check(NodePicker.chainable(true, "selected", "nodeid://a", "chain") == null, "chainable: chain node -> null");
        check(NodePicker.chainable(true, "session", "chain://hidden", "") == null, "chainable: hidden chain (our own session) -> null");
        check(NodePicker.fallback(true, true, false) == null && NodePicker.fallback(true, true, true).equals(NodePicker.FREEDOM)
                && NodePicker.fallback(false, false, false).equals(NodePicker.FREEDOM), "fallback without permission");
        String wl = WireGuardUri.build(a, "x");
        String ch = ChainUri.build(wl, "nodeid://k3x", true);
        check(ch.startsWith("chain://localhost/?hopList=%5B%22wireguard%3A%2F%2F"), "chain: WARP first, JSON array url-encoded");
        check(ch.indexOf("wireguard") < ch.indexOf("nodeid") && ch.endsWith("%22nodeid%3A%2F%2Fk3x%22%5D"), "chain: order warp, node");
        String ch2 = ChainUri.build(wl, "nodeid://k3x", false);
        check(ch2.indexOf("nodeid") < ch2.indexOf("wireguard"), "chain: node first when asked");
        // 1.1.1.1 without bridge: Xray's DNS over HTTPS by IP, user routing untouched
        WarpOverrides.Settings nb = new WarpOverrides.Settings();
        nb.mode = WarpOverrides.MODE_1111;
        j = WarpOverrides.json(nb, false);
        check(balanced(j) && j.contains("\"hijackDns\":true") && j.contains("\"foreignDns\":\"https://1.1.1.1/dns-query\"")
                && j.contains("\"dnsViaProxy\":false") && !j.contains("routing"), "1.1.1.1 level 2: hijack + DoH by IP, no routing");
        check(WarpOverrides.json(nb).contains("\"hijackDns\":false"), "json(s) keeps the bridge variant");

        // ---- DNS message helpers
        byte[] q = hex("1234010000010000000000000377777707657861" + "6d706c6503636f6d0000010001");
        check(DnsMessage.describe(q, q.length).equals("www.example.com A"), "DnsMessage.describe");
        byte[] sf = DnsMessage.servfail(q, q.length);
        check(sf.length == q.length && sf[0] == 0x12 && sf[1] == 0x34 && (sf[2] & 0x80) != 0 && (sf[3] & 0x0f) == 2 && sf[5] == 1,
                "DnsMessage.servfail: same id + question, QR=1, RCODE=2");

        System.out.println(failures == 0 ? "\nall tests passed" : "\n" + failures + " FAILED");
        if (failures > 0) System.exit(1);
    }
}
