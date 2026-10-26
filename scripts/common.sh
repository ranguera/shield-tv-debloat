# shellcheck shell=bash
# Shared by every script here: finds adb (downloading Google's official platform-tools if it's
# missing), finds the SHIELD on the network and connects to it. Source it, then call
# `shield_connect`.
#
# Honors: ANDROID_SERIAL=<ip>[:port]  skip discovery and use this address
#         DRY_RUN=1                   scripts print what they would change instead of changing it
#         ASSUME_YES=1                answer yes to every question
#         SHIELD_KEEP=pkg1,pkg2       packages from debloat-list.txt to leave alone

COMMON_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$COMMON_DIR")"
ADDRESS_FILE="$ROOT/.shield-address"
REPO="${SHIELD_REPO:-ranguera/shield-tv-debloat}"
APK_URL="https://github.com/$REPO/releases/latest/download/ZenHome.apk"
DRY_RUN="${DRY_RUN:-0}"

if [ -t 1 ]; then B=$'\033[1m'; Y=$'\033[33m'; R=$'\033[31m'; G=$'\033[32m'; N=$'\033[0m'
else B=; Y=; R=; G=; N=; fi
say()  { printf '%s\n' "$*"; }
step() { printf '\n%s==> %s%s\n' "$B" "$*" "$N"; }
ok()   { printf '%s%s%s\n' "$G" "$*" "$N"; }
warn() { printf '%s%s%s\n' "$Y" "$*" "$N" >&2; }
die()  { printf '%sError: %s%s\n' "$R" "$*" "$N" >&2; exit 1; }

# True when there's a person at a terminal to answer questions (also under `curl | bash`).
interactive() { { : </dev/tty; } 2>/dev/null; }

# ask "Question" [default] -> sets REPLY. Reads the terminal, not stdin.
ask() {
  REPLY=""
  if interactive; then read -r -p "$1 " REPLY </dev/tty || true; fi
  [ -n "$REPLY" ] || REPLY="${2:-}"
}

# confirm "Question" -> 0 for yes. Default is yes.
confirm() {
  [ "${ASSUME_YES:-0}" = 1 ] && return 0
  interactive || die "$1 (no terminal to answer on; pass --yes to proceed)"
  ask "$1 [Y/n]" y
  case "$REPLY" in [Yy]*) return 0 ;; *) return 1 ;; esac
}

find_adb() {
  command -v adb >/dev/null 2>&1 && return
  local d
  for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" "$ROOT/tools"; do
    if [ -n "$d" ] && [ -x "$d/platform-tools/adb" ]; then PATH="$d/platform-tools:$PATH"; return; fi
  done

  local os
  case "$(uname -s)/$(uname -m)" in
    Darwin/*) os=darwin ;;
    Linux/x86_64) os=linux ;;
    *) die "adb isn't installed. Install it with your package manager (e.g. 'sudo apt install adb') and run this again." ;;
  esac
  step "Downloading adb (Google's Android platform-tools) into $ROOT/tools"
  mkdir -p "$ROOT/tools"
  curl -fL --progress-bar -o "$ROOT/tools/platform-tools.zip" \
    "https://dl.google.com/android/repository/platform-tools-latest-$os.zip" \
    || die "couldn't download platform-tools. Check your internet connection."
  unzip -q -o "$ROOT/tools/platform-tools.zip" -d "$ROOT/tools"
  rm -f "$ROOT/tools/platform-tools.zip"
  PATH="$ROOT/tools/platform-tools:$PATH"
}

device_state() { adb devices 2>/dev/null | awk -v s="$1" '$1 == s { print $2 }'; }

# SHIELDs advertise themselves over mDNS while Network debugging is on.
discover() {
  local i found=""
  for i in 1 2 3 4 5 6; do
    found="$(adb mdns services 2>/dev/null | awk '$2 ~ /^_adb\._tcp/ { print $3 }' | sort -u)"
    [ -n "$found" ] && break
    sleep 1
  done
  printf '%s' "$found"
}

# try_connect <ip:port>: 0 if adb can talk to it (authorized or waiting for the TV prompt).
try_connect() {
  adb connect "$1" >/dev/null 2>&1 || true
  case "$(device_state "$1")" in device|unauthorized) return 0 ;; *) return 1 ;; esac
}

print_enable_help() {
  cat <<'EOF'

Make sure the SHIELD is on, on the same network as this computer, and has Network debugging on:
  1. On the SHIELD: Settings > Device Preferences > About, then click "Build" 7 times.
  2. Back in Device Preferences > Developer options, turn on "Network debugging".
  3. The IP address is shown right under the switch (also in About > Status).
EOF
}

shield_connect() {
  [ "${SHIELD_CONNECTED:-}" = "${ANDROID_SERIAL:-x}" ] && { find_adb; return; }
  find_adb
  adb start-server >/dev/null 2>&1 || true

  local addr="" cand
  if [ -n "${ANDROID_SERIAL:-}" ]; then
    addr="$ANDROID_SERIAL"
    case "$addr" in *:*|emulator-*) ;; *[0-9].[0-9]*) addr="$addr:5555" ;; esac
    if ! try_connect "$addr" && [ -z "$(device_state "$addr")" ]; then
      print_enable_help; die "can't reach a SHIELD at $addr."
    fi
  else
    # 1) the address that worked last time, 2) one device already attached, 3) mDNS, 4) ask.
    if [ -f "$ADDRESS_FILE" ] && cand="$(cat "$ADDRESS_FILE")" && try_connect "$cand"; then
      addr="$cand"
    elif [ "$(adb devices | awk 'NR > 1 && $2 ~ /^(device|unauthorized)$/' | wc -l)" -eq 1 ]; then
      addr="$(adb devices | awk 'NR > 1 && $2 ~ /^(device|unauthorized)$/ { print $1 }')"
    else
      say "Looking for your SHIELD on the network..."
      local found; found="$(discover)"
      for cand in $found; do
        if try_connect "$cand"; then addr="$cand"; break; fi
      done
      while [ -z "$addr" ]; do
        [ -n "$found" ] || say "No SHIELD found automatically."
        print_enable_help
        interactive || die "no SHIELD found. Set ANDROID_SERIAL=<SHIELD IP> and run again."
        ask $'\nType the SHIELD\'s IP address (e.g. 192.168.1.50):'
        [ -n "$REPLY" ] || continue
        cand="$REPLY"; case "$cand" in *:*) ;; *) cand="$cand:5555" ;; esac
        if try_connect "$cand"; then addr="$cand"; else warn "Can't reach $cand."; found=x; fi
      done
    fi
  fi
  export ANDROID_SERIAL="$addr"

  if [ "$(device_state "$addr")" = unauthorized ]; then
    say ""
    say "${B}Look at your TV:${N} a prompt asks to allow debugging from this computer."
    say "Tick ${B}\"Always allow from this computer\"${N} and choose ${B}OK${N}. Waiting..."
    local i
    for i in $(seq 1 120); do
      [ "$(device_state "$addr")" = device ] && break
      sleep 1
      [ $((i % 15)) = 0 ] && adb reconnect offline >/dev/null 2>&1 || true
    done
  fi
  [ "$(device_state "$addr")" = device ] \
    || die "the SHIELD at $addr didn't allow the connection. Run again and accept the prompt on the TV."

  case "$addr" in *:*) printf '%s\n' "$addr" > "$ADDRESS_FILE" ;; esac

  local maker model
  maker="$(adb shell getprop ro.product.manufacturer </dev/null | tr -d '\r')"
  model="$(adb shell getprop ro.product.model </dev/null | tr -d '\r')"
  ok "Connected to $model ($addr)."
  if [ "$maker" != NVIDIA ]; then
    warn "This doesn't look like an NVIDIA SHIELD (manufacturer: ${maker:-unknown})."
    confirm "Continue anyway?" || exit 1
  fi
  export SHIELD_CONNECTED="$addr"
}

# kept <package>: 0 if the user asked to keep it (SHIELD_KEEP="pkg1,pkg2" or --keep).
kept() { case ",${SHIELD_KEEP:-}," in *",$1,"*) return 0 ;; *) return 1 ;; esac; }

# shell_run <args...>: adb shell, or just print it under DRY_RUN=1.
shell_run() {
  if [ "$DRY_RUN" = 1 ]; then say "  [dry-run] adb shell $*"; else adb shell "$@" </dev/null; fi
}
