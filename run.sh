#!/usr/bin/env bash
# MochaNES emulator launcher (Linux/macOS replacement for run.bat)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$ROOT/nes-emulator/target/mochanes-emulator-1.0-SNAPSHOT.jar"
DEFAULT_ROM="resources/nestest.nes"

usage() {
    cat <<'EOF'
Usage: ./run.sh [options] [ROM]
       ./run.sh [options] --replay <log> [ROM]

ROM may be a path, or a bare name looked up under roms/ (e.g. "smb", "zelda").
With no ROM, resources/nestest.nes is used.

Options:
  -b, --build     Force a rebuild before launching
  -t, --test      Run the test suite instead of launching
  -l, --list      List available ROMs
  -c, --crt [P]   Start with the CRT simulation on. Optional preset:
                  trinitron (default), consumer, arcade, monochrome
  -h, --help      Show this help

In-emulator hotkeys:
  F1  toggle CRT      F2  cycle preset     F3  cycle shadow mask
  F4/F5  curvature    F6/F7  screen tilt

Examples:
  ./run.sh                      # nestest
  ./run.sh smb                  # roms/NROM/smb.nes
  ./run.sh roms/MMC3/kirby.nes
  ./run.sh --build zelda
EOF
}

build() {
    echo ">> Building nes-emulator..."
    mvn -pl nes-emulator -am clean package -q
}

# Resolve a bare ROM name to a path under roms/ or resources/.
resolve_rom() {
    local want="$1"
    [[ -f "$want" ]] && { printf '%s\n' "$want"; return; }

    local match
    match="$(find "$ROOT/roms" "$ROOT/resources" -iname "${want}.nes" -type f -print -quit 2>/dev/null || true)"
    if [[ -n "$match" ]]; then
        printf '%s\n' "$match"
        return
    fi

    echo "error: no ROM matching '$want'" >&2
    echo "Run './run.sh --list' to see what's available." >&2
    exit 1
}

FORCE_BUILD=0
ARGS=()
JVM_ARGS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        -b|--build) FORCE_BUILD=1; shift ;;
        -c|--crt)
            # An optional preset name may follow; anything else is the ROM.
            case "${2:-}" in
                trinitron|consumer|arcade|monochrome)
                    JVM_ARGS+=("-Dmochanes.crt=$2"); shift 2 ;;
                *)
                    JVM_ARGS+=("-Dmochanes.crt=true"); shift ;;
            esac ;;
        -h|--help)  usage; exit 0 ;;
        -t|--test)  mvn -pl nes-emulator test; exit $? ;;
        -l|--list)
            # Games only; roms/test holds 250+ accuracy ROMs that would bury them.
            find "$ROOT/roms" "$ROOT/resources" -name '*.nes' -type f \
                -not -path "$ROOT/roms/test/*" 2>/dev/null \
                | sed "s|^$ROOT/||" | sort
            printf '\n(%s accuracy-test ROMs under roms/test/ — pass a path or bare name to run one)\n' \
                "$(find "$ROOT/roms/test" -name '*.nes' -type f 2>/dev/null | wc -l)"
            exit 0 ;;
        --replay)   ARGS+=("$1"); shift ;;
        *)          ARGS+=("$1"); shift ;;
    esac
done

command -v java >/dev/null || { echo "error: java not found (pacman -S jdk21-openjdk)" >&2; exit 1; }

if [[ $FORCE_BUILD -eq 1 || ! -f "$JAR" ]]; then
    command -v mvn >/dev/null || { echo "error: mvn not found (pacman -S maven)" >&2; exit 1; }
    build
fi

# Main resolves the default ROM relative to the working directory.
cd "$ROOT"

# Translate the trailing ROM argument; --replay takes <log> first, then the ROM.
if [[ ${#ARGS[@]} -eq 0 ]]; then
    ARGS=("$DEFAULT_ROM")
elif [[ "${ARGS[0]}" == "--replay" ]]; then
    if [[ ${#ARGS[@]} -ge 3 ]]; then
        ARGS[2]="$(resolve_rom "${ARGS[2]}")"
    fi
else
    ARGS[0]="$(resolve_rom "${ARGS[0]}")"
fi

echo ">> Launching: ${ARGS[*]}"
exec java "${JVM_ARGS[@]}" -jar "$JAR" "${ARGS[@]}"
