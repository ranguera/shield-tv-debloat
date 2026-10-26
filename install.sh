#!/usr/bin/env bash
# One-line installer for macOS and Linux:
#
#   curl -fsSL https://raw.githubusercontent.com/ranguera/shield-tv-debloat/main/install.sh | bash
#
# Downloads this project to ~/shield-tv-debloat (earlier backups there are kept) and starts the
# guided setup. Options go after `bash -s --`, e.g. `... | bash -s -- --restore`; see
# scripts/setup.sh --help. Run from a clone (./install.sh), it uses the files in that clone.
set -euo pipefail

main() {
  local repo="${SHIELD_REPO:-ranguera/shield-tv-debloat}" dir
  if [ -f "${BASH_SOURCE[0]:-}" ] && [ -f "$(dirname "${BASH_SOURCE[0]}")/scripts/setup.sh" ]; then
    dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  else
    dir="${SHIELD_DIR:-$HOME/shield-tv-debloat}"
    echo "Downloading shield-tv-debloat into $dir ..."
    mkdir -p "$dir"
    curl -fsSL "https://github.com/$repo/archive/refs/heads/main.tar.gz" \
      | tar -xz --strip-components=1 -C "$dir"
  fi
  # Under `curl | bash` stdin is this script, so questions are read from the terminal.
  if { : </dev/tty; } 2>/dev/null; then
    exec bash "$dir/scripts/setup.sh" "$@" </dev/tty
  fi
  exec bash "$dir/scripts/setup.sh" "$@"
}

main "$@"
