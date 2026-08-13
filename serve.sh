#!/usr/bin/env bash
# Builds the browser version and serves it locally, for development.
#
# The site is plain static files, but it cannot be opened straight off disk:
# fetching a ROM and starting audio both need a real origin, so file:// gives a
# page that half works. This serves it properly and prints an address the phone
# on your wifi can reach, which is the only way to try the touch controls.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SITE="$ROOT/nes-web/target/site"
SMOKE="$ROOT/nes-web/src/test/js/smoke.js"
PORT=8000
BUILD=1
TEST=0
OPEN=0
ROM=""

usage() {
    cat <<'EOF'
Usage: ./serve.sh [options]

Builds the browser version and serves it on http://localhost:8000

Options:
  -p, --port <n>   Port to listen on (default 8000)
  -n, --no-build   Serve what is already built, without rebuilding
  -t, --test       Run the smoke test after building
  -o, --open       Open a browser once the server is up
  -r, --rom <name> Load a ROM on startup. It is copied into the site, so a
                   file from anywhere works: --rom roms/MMC3/kirby.nes
  -h, --help       Show this help

Ctrl-C stops the server. Reload with Ctrl-Shift-R after a rebuild: the script
is cached hard, and an ordinary reload will show you the previous build.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -p|--port)     PORT="${2:?--port needs a number}"; shift 2 ;;
        -n|--no-build) BUILD=0; shift ;;
        -t|--test)     TEST=1; shift ;;
        -o|--open)     OPEN=1; shift ;;
        -r|--rom)      ROM="${2:?--rom needs a path}"; shift 2 ;;
        -h|--help)     usage; exit 0 ;;
        *) echo "error: unknown option $1" >&2; usage; exit 1 ;;
    esac
done

command -v python3 >/dev/null || { echo "error: python3 not found" >&2; exit 1; }

if [[ $BUILD -eq 1 ]]; then
    command -v mvn >/dev/null || { echo "error: mvn not found (pacman -S maven)" >&2; exit 1; }
    echo ">> Building the browser version (mvn -Pweb package)"
    ( cd "$ROOT" && mvn -q -Pweb package -DskipTests )
fi

[[ -s "$SITE/mochanes.js" ]] || {
    echo "error: no build found at $SITE" >&2
    echo "       run without --no-build first" >&2
    exit 1
}

if [[ $TEST -eq 1 ]]; then
    command -v node >/dev/null || { echo "error: node not found (pacman -S nodejs)" >&2; exit 1; }
    echo ">> Smoke-testing the compiled build"
    node "$SMOKE" "$SITE"
fi

QUERY=""
if [[ -n "$ROM" ]]; then
    if [[ ! -f "$ROM" ]]; then
        echo "error: no such ROM: $ROM" >&2
        exit 1
    fi
    # The page can only fetch from its own origin, so put a copy in the site.
    # It lands in target/, which is disposable and gitignored.
    cp "$ROM" "$SITE/$(basename "$ROM")"
    QUERY="?rom=$(basename "$ROM")"
    echo ">> Serving $(basename "$ROM") on startup"
fi

if ! python3 -c "
import socket, sys
s = socket.socket()
try:
    s.bind(('', $PORT))
except OSError:
    sys.exit(1)
finally:
    s.close()
" 2>/dev/null; then
    echo "error: port $PORT is already in use" >&2
    echo "       stop the other server, or pass --port $((PORT + 1))" >&2
    exit 1
fi

# The address the phone on your wifi should use. Best effort: a machine with no
# route, or an unusual setup, simply does not get the second line.
LAN="$(ip -4 -o addr show scope global 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | head -1 || true)"

echo
echo "   Desktop   http://localhost:$PORT/$QUERY"
[[ -n "$LAN" ]] && echo "   Phone     http://$LAN:$PORT/$QUERY"
echo
echo "   Ctrl-C to stop. After a rebuild, reload with Ctrl-Shift-R."
echo

if [[ $OPEN -eq 1 ]] && command -v xdg-open >/dev/null; then
    ( sleep 1 && xdg-open "http://localhost:$PORT/$QUERY" >/dev/null 2>&1 || true ) &
fi

cd "$SITE"
exec python3 -m http.server "$PORT" --bind 0.0.0.0
