#!/usr/bin/env bash
# phonebot-android — top-level build entrypoint.
#
# Usage:
#   ./build.sh            # build debug APK (no signing)
#   ./build.sh release    # build unsigned release APK (needs signing for distribution)
#   ./build.sh sign       # build signed release APK from ./signing/ (for local release prep)
#   ./build.sh pack <ver> # pack the botd payload + manifest into release/dist
#   ./build.sh test       # run the Python botd test battery (selftest + conformance + mock)
#   ./build.sh clean
#
# Requires (for the APK): JDK 17 + Android SDK. The botd payload pack needs
# only python3.

set -euo pipefail
cd "$(dirname "$0")"

GRADLE="./gradlew"
[ -x "$GRADLE" ] || GRADLE="gradle"   # fall back to system gradle when no wrapper yet

need_gradle() {
  if ! command -v java >/dev/null 2>&1; then
    echo "error: java not found — install JDK 17 and set ANDROID_HOME." >&2
    exit 1
  fi
}

cmd="${1:-debug}"
case "$cmd" in
  debug)
    need_gradle
    "$GRADLE" assembleDebug
    echo "APK: app/build/outputs/apk/debug/app-debug.apk"
    ;;
  release)
    need_gradle
    # Unsigned release (no -Psigning.*). Add keystore props to sign.
    "$GRADLE" assembleRelease
    echo "APK: app/build/outputs/apk/release/app-release.apk (unsigned)"
    ;;
  sign)
    need_gradle
    [ -d signing ] || { echo "error: ./signing/ not found — create it with phonebot-release.keystore" >&2; exit 1; }
    "$GRADLE" assembleRelease \
      -Psigning.storeFile="signing/phonebot-release.keystore" \
      -Psigning.storePass="${STORE_PASS:?SET STORE_PASS}" \
      -Psigning.keyAlias="${KEY_ALIAS:?SET KEY_ALIAS}" \
      -Psigning.keyPass="${KEY_PASS:?SET KEY_PASS}"
    echo "APK: app/build/outputs/apk/release/app-release.apk (signed)"
    ;;
  pack)
    ver="${2:?usage: ./build.sh pack <version>  (e.g. 0.1.0)}"
    python3 release/pack.py --version "$ver" --sign
    echo "packed -> release/dist/  (version $ver)"
    ;;
  test)
    python3 -m botd.main --selftest
    python3 test/protocol_conformance.py
    python3 test/tools_dispatch_check.py
    python3 test/mock_openai_check.py
    python3 test/cli_roundtrip_check.py
    python3 test/regression_check.py
    python3 -m botd.main --smoketest-llm --llm-mode fake
    echo "botd test battery complete"
    ;;
  clean)
    "$GRADLE" clean 2>/dev/null || true
    rm -rf release/dist
    echo "cleaned"
    ;;
  *)
    echo "unknown target: $cmd" >&2
    echo "targets: debug | release | sign | pack <ver> | test | clean" >&2
    exit 1
    ;;
esac
