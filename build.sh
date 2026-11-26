#!/usr/bin/env bash
#
# warp-lite (1.1.1.1 WARP Lite): builder.
#
#   ./build.sh               run the host tests, then build the release APK -> dist/
#   ./build.sh --debug       build the debug APK instead
#   ./build.sh --test        only the host tests (plain JDK, no Android SDK needed)
#   ./build.sh --clean       clean first
#
# Needs JDK 17+ for the Gradle build and an Android SDK (ANDROID_HOME / ANDROID_SDK_ROOT, or sdk.dir in
# local.properties). Uses ./gradlew when the project has a wrapper, else `gradle` (8.7+ for AGP 8.5).
#
# Signing: set EX_KEYSTORE, EX_KEYSTORE_PASSWORD, EX_KEY_ALIAS, EX_KEY_PASSWORD to sign with your own key;
# otherwise the debug key is used so the APK still installs.
#
set -euo pipefail

cd "$(dirname "$0")"
OUT="dist"
VARIANT="Release"
DO_BUILD=1
DO_CLEAN=0

for arg in "$@"; do
    case "$arg" in
        --debug) VARIANT="Debug" ;;
        --test)  DO_BUILD=0 ;;
        --clean) DO_CLEAN=1 ;;
        -h|--help) sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $arg (see --help)" >&2; exit 2 ;;
    esac
done

APP_ID=$(sed -n "s/^[[:space:]]*applicationId[[:space:]]*'\(.*\)'.*/\1/p" app/build.gradle | head -n 1)
VERSION=$(sed -n "s/^[[:space:]]*versionName[[:space:]]*'\(.*\)'.*/\1/p" app/build.gradle | head -n 1)
[[ -n $APP_ID && -n $VERSION ]] || { echo "cannot read applicationId / versionName from app/build.gradle" >&2; exit 1; }
NAME="warp-lite"

# --------------------------------------------------------------------------- host tests
# X25519, Base64Lite, WireGuardUri, WarpOverrides, DnsMessage, DohResolver and Socks5Server have no Android
# dependency: plain javac + java (the bridge is tested with a fake resolver on a loopback port).
run_tests() {
    command -v javac >/dev/null || { echo "javac not found (JDK needed for the tests)" >&2; exit 1; }
    local pkg=app/src/main/java/com/warplite/app
    local tmp; tmp=$(mktemp -d)
    echo "==> host tests"
    javac -Xlint:all -d "$tmp" "$pkg/X25519.java" "$pkg/Base64Lite.java" "$pkg/WarpAccount.java" \
        "$pkg/WireGuardUri.java" "$pkg/WarpOverrides.java" "$pkg/NodePicker.java" "$pkg/ChainUri.java" "$pkg/DnsMessage.java" "$pkg/DohResolver.java" \
        "$pkg/Socks5Server.java" tests/CoreTest.java tests/Socks5ServerTest.java
    java -cp "$tmp" com.warplite.app.CoreTest
    java -cp "$tmp" com.warplite.app.Socks5ServerTest
    rm -rf "$tmp"
}

# ------------------------------------------------------------------------------- build
find_sdk() {
    local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [[ -z $sdk && -f local.properties ]]; then
        sdk=$(sed -n 's/^sdk\.dir=//p' local.properties | head -n 1)
    fi
    [[ -n $sdk && -d $sdk ]] || { echo "Android SDK not found: set ANDROID_HOME or sdk.dir in local.properties" >&2; exit 1; }
    echo "$sdk"
}

gradle_cmd() {
    if [[ -x ./gradlew ]]; then echo "./gradlew"; return; fi
    command -v gradle >/dev/null || { echo "neither ./gradlew nor gradle found" >&2; exit 1; }
    echo "gradle"
}

build_apk() {
    local sdk; sdk=$(find_sdk)
    export ANDROID_HOME="$sdk"
    local g; g=$(gradle_cmd)
    echo "==> ${NAME} ${VERSION} (${VARIANT}), SDK: ${sdk}"
    if [[ -n ${EX_KEYSTORE:-} ]]; then
        [[ -f $EX_KEYSTORE ]] || { echo "EX_KEYSTORE does not exist: $EX_KEYSTORE" >&2; exit 1; }
        echo "==> signing with the key from EX_KEYSTORE"
    else
        echo "==> no EX_KEYSTORE: signing with the debug key"
    fi
    [[ $DO_CLEAN == 1 ]] && $g --no-daemon -q clean
    $g --no-daemon ":app:assemble${VARIANT}"

    local lower; lower=$(echo "$VARIANT" | tr 'A-Z' 'a-z')
    local apk; apk=$(find "app/build/outputs/apk/${lower}" -name '*.apk' | head -n 1)
    [[ -n $apk ]] || { echo "no APK produced under app/build/outputs/apk/${lower}" >&2; exit 1; }

    mkdir -p "$OUT"
    local dest="${OUT}/${NAME}-${VERSION}-${lower}.apk"
    cp -f "$apk" "$dest"
    unzip -tq "$dest" >/dev/null || { echo "$dest is not a valid zip/APK" >&2; exit 1; }
    unzip -p "$dest" AndroidManifest.xml >/dev/null
    echo "==> ${dest} ($(du -h "$dest" | cut -f1)), applicationId ${APP_ID}"
}

run_tests
[[ $DO_BUILD == 1 ]] && build_apk
exit 0
