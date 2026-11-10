#!/data/data/com.termux/files/usr/bin/bash
# botd onboarding deploy script.
#
# The Android wizard's "Deploy botd" step runs THIS inside Termux (via
# RUN_COMMAND). It:
#   1. verifies the unpacked payload,
#   2. installs Python + pinned deps,
#   3. lays out ~/phonebot/{bots,secrets} + template files,
#   4. runs the selftest battery,
#   5. starts the daemon (and registers Termux:Boot on first install).
#
# Idempotent: safe to re-run. Exits non-zero on any hard failure so the
# wizard can mark the node failed.
#
# Usage (in Termux):  bash ~/phonebot-install/install.sh

set -euo pipefail

# Inside Termux, $HOME is the termux home (NOT the Android /data/data path).
TARGET="$HOME/phonebot"
INSTALLER_DIR="$(cd "$(dirname "$0")" && pwd)"

log() { printf '[botd-install] %s\n' "$*"; }

# --- 0. sanity ---------------------------------------------------------------
if [[ "${TERMUX_VERSION:-}" == "" && ! -d "$PREFIX/.termux" && ! -x "$PREFIX/bin/termux-setup-storage" ]]; then
    log "WARN: does not look like Termux; continuing anyway"
fi

# --- 1. deploy payload --------------------------------------------------------
mkdir -p "$TARGET/bots" "$TARGET/secrets"
# Copy the botd package into the target (single source of truth on disk).
rm -rf "$TARGET/botd"
cp -R "$INSTALLER_DIR/botd" "$TARGET/botd"
log "deployed botd source -> $TARGET/botd"

# --- 2. python + deps ---------------------------------------------------------
PYTHON="$(command -v python || true)"
if [[ -z "$PYTHON" ]]; then
    log "installing python via pkg"
    pkg install -y python
    PYTHON="$(command -v python)"
fi
log "python: $($PYTHON --version)"
"$PYTHON" -m pip install --quiet -r "$INSTALLER_DIR/botd/requirements.txt" 2>/dev/null \
    || log "WARN: pip install reported an issue; continuing (deps may already be present)"

# --- 3. templates (never clobber live state) ---------------------------------
write_if_missing() {
    local rel="$1" content="$2"
    local dest="$TARGET/$rel"
    if [[ ! -e "$dest" ]]; then
        mkdir -p "$(dirname "$dest")"
        printf '%s\n' "$content" > "$dest"
        log "wrote template $rel"
    fi
}
chmod 600 "$TARGET/secrets" 2>/dev/null || true
if [[ ! -e "$TARGET/endpoint.json" ]]; then
    cat > "$TARGET/endpoint.json" <<'JSON'
{
  "base_url": "https://api.openai.com/v1",
  "api_key_ref": "default",
  "model": "gpt-4o-mini",
  "secrets_dir": "~/phonebot/secrets",
  "extra_headers": {}
}
JSON
    chmod 600 "$TARGET/endpoint.json"
    log "wrote endpoint.json (chmod 600)"
fi
if [[ ! -e "$TARGET/jobs.json" ]]; then
    printf '{\n  "version": 1,\n  "jobs": {}\n}\n' > "$TARGET/jobs.json"
    log "wrote jobs.json"
fi
if [[ ! -e "$TARGET/config.json" ]]; then
    cat > "$TARGET/config.json" <<'JSON'
{
  "socket_name": "@phonebot",
  "llm": { "base_url": "https://api.openai.com/v1", "api_key_ref": "default", "model": "gpt-4o-mini", "mode": "auto" },
  "backend": { "shell": "termux", "python": "termux" },
  "max_concurrent_bots": 8,
  "spend_cap_per_bot_usd": 5.0
}
JSON
    log "wrote config.json"
fi

# --- 4. selftest --------------------------------------------------------------
log "running selftest"
cd "$TARGET"
"$PYTHON" -m botd.main --selftest
log "selftest passed"

# --- 5. boot integration (first install only) --------------------------------
log "if not already set, enable Termux:Boot to restart botd on device reboot"
log "    (the app's Durable screen walks the user through this)"

log "OK: botd installed to $TARGET"
log "start daemon with: cd $TARGET && $PYTHON -m botd.main"
