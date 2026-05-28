#!/usr/bin/env bash
#
# Kermes installer.
#   curl -fsSL https://raw.githubusercontent.com/moshtaghmaveddat/kermes/main/install.sh | bash
#
# Downloads the latest release distribution, installs it under ~/.kermes/app,
# symlinks the `kermes` launcher into ~/.local/bin, and runs `kermes init`.
#
# Requires: Java 17+ (the launcher runs on the JVM), curl, unzip.
set -euo pipefail

REPO="${KERMES_REPO:-moshtaghmaveddat/kermes}"
KERMES_HOME="${KERMES_HOME:-$HOME/.kermes}"
APP_DIR="$KERMES_HOME/app"
BIN_DIR="${KERMES_BIN_DIR:-$HOME/.local/bin}"

say()  { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
err()  { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; }
die()  { err "$*"; exit 1; }

# --- prerequisites ----------------------------------------------------------
command -v curl  >/dev/null 2>&1 || die "curl is required"
command -v unzip >/dev/null 2>&1 || die "unzip is required"
command -v java  >/dev/null 2>&1 || die "Java 17+ is required but 'java' was not found. Install a JDK 17+ (e.g. 'brew install openjdk@21' or your distro's package) and re-run."

JAVA_MAJOR="$(java -version 2>&1 | head -n1 | grep -oE '[0-9]+' | head -n1)"
if [ -z "${JAVA_MAJOR:-}" ] || [ "$JAVA_MAJOR" -lt 17 ]; then
  die "Java 17+ required (found '${JAVA_MAJOR:-unknown}'). Please upgrade your JDK."
fi
say "Java $JAVA_MAJOR detected."

# --- download latest release ------------------------------------------------
ASSET_URL="https://github.com/$REPO/releases/latest/download/kermes.zip"
TMP_ZIP="$(mktemp -t kermes.XXXXXX.zip)"
trap 'rm -f "$TMP_ZIP"' EXIT

say "Downloading $ASSET_URL"
if ! curl -fSL "$ASSET_URL" -o "$TMP_ZIP"; then
  die "Download failed. Has a release been published for $REPO yet?"
fi

# --- install ----------------------------------------------------------------
say "Installing into $APP_DIR"
rm -rf "$APP_DIR"
mkdir -p "$APP_DIR"
unzip -oq "$TMP_ZIP" -d "$APP_DIR"

# The dist zip contains a top-level <name>/bin/kermes; find it robustly.
LAUNCHER="$(find "$APP_DIR" -type f -path '*/bin/kermes' | head -n1 || true)"
[ -n "$LAUNCHER" ] || die "couldn't find the kermes launcher inside the downloaded archive"
chmod +x "$LAUNCHER"

mkdir -p "$BIN_DIR"
ln -sf "$LAUNCHER" "$BIN_DIR/kermes"
say "Linked $BIN_DIR/kermes -> $LAUNCHER"

# --- bootstrap home dir -----------------------------------------------------
"$BIN_DIR/kermes" init || true

# --- PATH guidance ----------------------------------------------------------
echo
say "Installed Kermes."
case ":$PATH:" in
  *":$BIN_DIR:"*) : ;;
  *) printf '\033[1;33mNote:\033[0m %s is not on your PATH. Add this to your shell profile:\n  export PATH="%s:$PATH"\n' "$BIN_DIR" "$BIN_DIR" ;;
esac
cat <<EOF

Next:
  export KERMES_API_KEY=sk-...      # OpenRouter or OpenAI key
  kermes                            # start chatting
  kermes help                       # all commands
EOF
