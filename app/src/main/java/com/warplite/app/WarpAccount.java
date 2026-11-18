package com.warplite.app;

/**
 * One WARP registration plus the WireGuard parameters the registration returned. Plain data, no Android
 * dependency (the JSON read / write lives in WarpApi / WarpStore).
 */
final class WarpAccount {
    // identity (needed to talk to the Cloudflare API about this device)
    String id = "";
    String token = "";
    String license = "";
    boolean warpPlus;
    long createdAt;

    // WireGuard parameters (what ends up in the wireguard:// link)
    String privateKey = "";            // base64
    String host = "engage.cloudflareclient.com";
    String port = "2408";
    String peerPublicKey = "";         // base64
    String reserved = "";              // "a,b,c" or ""
    String address = "172.16.0.2/32";  // comma separated CIDRs
    int mtu = 1280;

    boolean usable() {
        return !privateKey.isEmpty() && !peerPublicKey.isEmpty();
    }
}
