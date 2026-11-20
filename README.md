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

| File | Role |
|---|---|
| `WarpClient` / `X25519` | registration like `wgcf-api/worker.js`; optional `fromWorker()` to use a deployed worker (`?format=raw`) |
| `WarpConfig` | builds / parses the `wireguard://` link (same layout as `toV2rayNG()`) |
| `WarpOverrides` | `overrides` JSON: mtu, `foreignDns` (1.1.1.1 / .2 / .3 families), `dnsViaProxy`, `dnsDisableFallback`, `exclude.apps` |
| `MagicV2Ray` | client of the API, from magic-dns-rewrite |
| `MainActivity` / `Widgets` / `Store` | screens: Main, Settings, Account, Advanced, Connection options, DNS settings, Excluded apps |

Settings mapping: *Manage excluded apps* -> `exclude.apps`; *1.1.1.1 for Families* -> `foreignDns`; *DNS over WARP* -> `dnsViaProxy`;
*fallback resolvers* -> `dnsDisableFallback`; *Reset security keys* -> new registration; *Configuration source* -> worker URL.
Not in the screenshots' list because the API has no equivalent: excluded routes, trusted networks, local domain fallback.

Build: `./build.sh --test` (JDK only), `./build.sh` (needs Android SDK). Unofficial; not affiliated with Cloudflare.

## CI

`.github/workflows/ci.yml`: shellcheck, host tests, APK build (artifact `warp-lite`), and a GitHub Release when you push a tag `v*`.
Optional signing: the secret `M2VR_KEYSTORE_ENV` holds the contents of a `M2VR_KEYSTORE.env` file (`MV_KEYSTORE_BASE64`, `MV_KEYSTORE_PASSWORD`, `MV_KEY_ALIAS`, `MV_KEY_PASSWORD`, see `M2VR_KEYSTORE.env.example`). Variables in it take priority over the older secrets `EX_KEYSTORE_BASE64`, `EX_KEYSTORE_PASSWORD`, `EX_KEY_ALIAS`, `EX_KEY_PASSWORD`; with neither, the debug key is used.
