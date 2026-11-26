package com.warplite.app;

/**
 * Builds the {@code chain://} uri of Magic V2Ray's connect API (level 2):
 * {@code chain://localhost/?hopList=<urlencode(JSON array of node uris)>}, 2 to 16 hops, a hop may be
 * {@code nodeid://<id>}. Hop 1 is the first one the device's traffic goes through, the last hop is the exit.
 * No Android dependency.
 *
 * <p>Your node first (default): device -> your node (the dialer hop) -> WARP -> Internet. The WireGuard tunnel is
 * wrapped inside your node's connection; the exit IP is Cloudflare's. Magic V2Ray notes that WireGuard as a later
 * hop can be unreliable (its UDP handshake has to cross the earlier hop), but this is the intended default.
 * <p>WARP first: device -> Cloudflare WARP -> your node -> Internet. The WireGuard handshake leaves the device
 * directly and your node's connection travels inside the WARP tunnel; the exit IP is your node's.
 */
final class ChainUri {

    private ChainUri() {
    }

    static String build(String warpLink, String userNodeUri, boolean warpFirst) {
        String a = warpFirst ? warpLink : userNodeUri;
        String b = warpFirst ? userNodeUri : warpLink;
        String hops = "[" + WarpOverrides.str(a) + "," + WarpOverrides.str(b) + "]";
        return "chain://localhost/?hopList=" + WireGuardUri.enc(hops);
    }
}
