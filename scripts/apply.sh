#!/usr/bin/env bash
# The whole setup (also after restore.sh or a factory reset):
# debloat phase 1 -> install Zen Home -> make it home -> disable the stock launcher (phase 2).
# Uses launcher/build/ZenHome.apk if you built it, otherwise the latest GitHub release.
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
. "$DIR/common.sh"
HOME_ACTIVITY="com.zen.home/.HomeActivity"

shield_connect

APK="$ROOT/launcher/build/ZenHome.apk"
if [ ! -f "$APK" ]; then
  APK="$ROOT/downloads/ZenHome.apk"
  step "Downloading Zen Home"
  if [ "$DRY_RUN" = 1 ]; then
    say "  [dry-run] download $APK_URL"
  else
    mkdir -p "$ROOT/downloads"
    if curl -fL --progress-bar -o "$APK.part" "$APK_URL"; then
      mv "$APK.part" "$APK"
    else
      rm -f "$APK.part"
      if [ -f "$APK" ]; then warn "Download failed; using the copy downloaded earlier."
      else
        warn "Download failed; building Zen Home from source instead (needs the Android SDK)."
        "$ROOT/launcher/build.sh"
        APK="$ROOT/launcher/build/ZenHome.apk"
      fi
    fi
  fi
fi

step "Disabling telemetry and bloat"
"$DIR/debloat.sh" phase1

step "Installing Zen Home"
if [ "$DRY_RUN" = 1 ]; then say "  [dry-run] adb install -r $APK"
else
  # A locally built APK is signed with this machine's key. If an older Zen Home
  # signed with another key is already installed, Android refuses the update.
  install_out="$(adb install -r "$APK" </dev/null 2>&1 || true)"
  printf '%s\n' "$install_out"
  if ! printf '%s\n' "$install_out" | grep -q Success; then
    if printf '%s\n' "$install_out" | grep -q INSTALL_FAILED_UPDATE_INCOMPATIBLE; then
      warn "Replacing the installed Zen Home (its signature differs, so its saved dock layout is reset)."
      adb uninstall com.zen.home </dev/null || true
      adb install -r "$APK" </dev/null
    else
      die "Installing Zen Home failed."
    fi
  fi
fi
shell_run cmd package set-home-activity --user 0 "$HOME_ACTIVITY"

step "Disabling the stock launcher and its ad feed"
"$DIR/debloat.sh" phase2
shell_run cmd package set-home-activity --user 0 "$HOME_ACTIVITY"
shell_run input keyevent KEYCODE_HOME
[ "$DRY_RUN" = 1 ] || say "Home now resolves to: $(adb shell cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN </dev/null | tail -1 | tr -d '\r')"
