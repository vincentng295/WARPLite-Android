# 1.1.1.1 WARP Lite

Source: https://github.com/vincentng295/WARPLite-Android

Android app that gets a free WARP WireGuard config and hands it to **Magic V2Ray** through its connect API. The app itself creates no VPN.

**Requires Magic V2Ray.** If it is not installed, the app asks you to download it from https://magicv2ray.vercel.app (on launch and when you switch the toggle on).

```
toggle ON -> WarpClient.register()  (POST api.cloudflareclient.com/v0a2158/reg, X25519 key made on the phone)
          -> wireguard://<priv>@engage.cloudflareclient.com:2408?address=..&publickey=..&mtu=1280&reserved=a,b,c
          -> MagicV2Ray.connectByIntent(uri, "1.1.1.1 WARP", overrides)   (user confirms in Magic V2Ray)
          <- STATE_CHANGED broadcast / getInfo provider drive the switch
```

**API level 2.** Before connecting the app asks Magic V2Ray which node is in use (`getActiveNode`; the first time the user allows
reading nodes, anonymous level = `nodeid://<id>`).
- **WARP**: if a node of the user is in use, the request is `chain://` of WARP and that node (default order: device → your node → WARP, WireGuard wrapped inside your node;
  *Connection options → Chain order* flips it, *Chain WARP with my node* turns it off). Not chained when Magic V2Ray is not connected,
  runs the no-proxy node, the node is hidden, or it is itself WireGuard or a chain: WARP connects alone.
- **Reverse chain while connected**: when WARP runs chained with your node, the home screen shows *Reverse chain*. It flips the order
  (WARP → node ⇄ node → WARP) with one new connect request (Magic V2Ray replaces the running session); the node uri from the first
  connect is kept, because while the chain runs the node in use is the chain itself. The new order is also saved as *Chain order*.
- **1.1.1.1**: node in use, else `freedom://localhost`, with Hijack DNS + DoH by IP; no local proxy. Only a Magic V2Ray older than
  level 2 still gets the SOCKS5 bridge.

| File | Role |
|---|---|
| `WarpClient` / `X25519` | registration like `wgcf-api/worker.js`; optional `fromWorker()` to use a deployed worker (`?format=raw`) |
| `WarpConfig` | builds / parses the `wireguard://` link (same layout as `toV2rayNG()`) |
| `WarpOverrides` | `overrides` JSON: mtu, `foreignDns` (1.1.1.1 / .2 / .3 families), `dnsViaProxy`, `dnsDisableFallback`, `exclude.apps` |
| `MagicV2Ray` | client of the Magic V2Ray API level 2 (connect, info, state events, `getActiveNode`, node access request) |
| `NodePicker` / `ChainUri` | which node is in use / `chain://localhost/?hopList=[...]` of WARP and that node |
| `MainActivity` / `Widgets` / `Store` | screens: Main, Settings, Account, Advanced, Connection options, DNS settings, Excluded apps |

Settings mapping: *Manage excluded apps* -> `exclude.apps`; *1.1.1.1 for Families* -> `foreignDns`; *DNS over WARP* -> `dnsViaProxy`;
*fallback resolvers* -> `dnsDisableFallback`; *Reset security keys* -> new registration; *Configuration source* -> worker URL.
Not in the screenshots' list because the API has no equivalent: excluded routes, trusted networks, local domain fallback.

Build: `./build.sh --test` (JDK only), `./build.sh` (needs Android SDK). Unofficial; not affiliated with Cloudflare.

## CI

`.github/workflows/ci.yml`: shellcheck, host tests, APK build (artifact `warp-lite`), and a GitHub Release when you push a tag `v*`.
Optional signing: the secret `M2VR_KEYSTORE_ENV` holds the contents of a `M2VR_KEYSTORE.env` file (`MV_KEYSTORE_BASE64`, `MV_KEYSTORE_PASSWORD`, `MV_KEY_ALIAS`, `MV_KEY_PASSWORD`, see `M2VR_KEYSTORE.env.example`). Variables in it take priority over the older secrets `EX_KEYSTORE_BASE64`, `EX_KEYSTORE_PASSWORD`, `EX_KEY_ALIAS`, `EX_KEY_PASSWORD`; with neither, the debug key is used.
