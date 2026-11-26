package com.warplite.app;

import java.util.Locale;

/**
 * Which node does a session use? Pure string logic (no Android, no JSON) so the host tests can check it.
 * Same rules as NodePicker in the Magic V2Ray API example, plus {@link #chainable} for the WARP chain.
 *
 * <ul>
 *   <li>{@link #pick}: the node Magic V2Ray is connected to (as the {@code uri} the API handed us: {@code nodeid://<id>}
 *       at the anonymous level), else {@link #FREEDOM}. Used by the 1.1.1.1 mode, which only changes DNS.</li>
 *   <li>{@link #chainable}: the same node, but only when it makes sense as a hop in front of / behind WARP; else
 *       {@code null} and the WARP mode connects alone.</li>
 * </ul>
 */
final class NodePicker {

    /** The built-in "no proxy" node: Xray as a pure router, every routing rule still applies. */
    static final String FREEDOM = "freedom://localhost";

    private NodePicker() {
    }

    /**
     * @param connected from {@code getActiveNode.connected}
     * @param source    from {@code getActiveNode.source}: "session", "selected" or "direct"
     * @param uri       from {@code getActiveNode.node.uri}
     * @return the {@code uri} for the connect request, never null
     */
    static String pick(boolean connected, String source, String uri) {
        if (!connected) return FREEDOM;
        if ("direct".equals(source)) return FREEDOM;
        if (uri == null || uri.isEmpty()) return FREEDOM;
        if (isFreedom(uri)) return FREEDOM;
        if (isHidden(uri)) return FREEDOM;      // a node an app set that an anonymous reader may not see
        return uri;
    }

    /**
     * The node to chain WARP with, or {@code null} = no usable node, connect WARP alone. That is the case when
     * Magic V2Ray is not connected, runs the built-in no-proxy node, the node is hidden from us, or it is itself a
     * WireGuard node (two WireGuard hops: the inner handshake would have to cross the outer tunnel) or a chain
     * (a chain cannot be a hop of another chain).
     *
     * @param protocol from {@code getActiveNode.node.protocol} (may be empty)
     */
    static String chainable(boolean connected, String source, String uri, String protocol) {
        String u = pick(connected, source, uri);
        if (isFreedom(u)) return null;
        String p = protocol == null ? "" : protocol.toLowerCase(Locale.ROOT);
        if (p.contains("wireguard") || p.equals("wg") || p.contains("chain") || p.contains("freedom")) return null;
        String l = u.toLowerCase(Locale.ROOT);
        if (l.startsWith("chain://") || l.startsWith("wireguard://") || l.startsWith("wg://")) return null;
        return u;
    }

    /**
     * No permission to read nodes (the user said no, or Magic V2Ray is older than API level 2), but the state is
     * public: if it is connected through the user's own node, leave {@code uri} out and Magic V2Ray keeps using that
     * node; with another app's session running we cannot tell which node it is, so use {@link #FREEDOM}.
     *
     * @return {@code null} = send no uri (keep the selected node), otherwise the uri to send
     */
    static String fallback(boolean stateKnown, boolean connected, boolean sessionActive) {
        if (stateKnown && connected && !sessionActive) return null;
        return FREEDOM;
    }

    static boolean isFreedom(String uri) {
        return uri != null && uri.regionMatches(true, 0, "freedom://", 0, 10);
    }

    /** {@code <scheme>://hidden} (or a chain with such a hop): the node exists but this app may not see it. */
    static boolean isHidden(String uri) {
        if (uri == null) return false;
        String u = uri.toLowerCase(Locale.ROOT);
        return u.contains("://hidden") || u.contains("%3a%2f%2fhidden");
    }
}
