#!/usr/bin/env bash
#
# Kermes installer.
#   curl -fsSL https://raw.githubusercontent.com/moshtaghmaveddat/kermes-agent/main/install.sh | bash
#
# - Auto-provisions a Java 17+ runtime (Eclipse Temurin) if one isn't present,
#   so nothing needs to be preinstalled except curl + tar/unzip.
# - Downloads the latest Kermes release, installs under ~/.kermes/app,
#   writes a `kermes` launcher into ~/.local/bin, and runs `kermes init`.
set -euo pipefail

REPO="${KERMES_REPO:-moshtaghmaveddat/kermes-agent}"
KERMES_HOME="${KERMES_HOME:-$HOME/.kermes}"
APP_DIR="$KERMES_HOME/app"
JRE_DIR="$KERMES_HOME/jre"
BIN_DIR="${KERMES_BIN_DIR:-$HOME/.local/bin}"

say() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

command -v curl >/dev/null 2>&1 || die "curl is required"
command -v tar  >/dev/null 2>&1 || die "tar is required"
command -v unzip >/dev/null 2>&1 || die "unzip is required"

# --- resolve a Java 17+ runtime --------------------------------------------
JAVA_HOME_OVERRIDE=""
java_major() { "$1" -version 2>&1 | head -n1 | grep -oE '[0-9]+' | head -n1; }

if command -v java >/dev/null 2>&1 && [ "$(java_major java || echo 0)" -ge 17 ] 2>/dev/null; then
  say "Using existing Java ($(java_major java))."
elif [ -x "$JRE_DIR/bin/java" ] && [ "$(java_major "$JRE_DIR/bin/java" || echo 0)" -ge 17 ] 2>/dev/null; then
  JAVA_HOME_OVERRIDE="$JRE_DIR"
  say "Using previously provisioned JRE at $JRE_DIR."
else
  # detect os/arch for Adoptium
  case "$(uname -s)" in
    Darwin) OS=mac ;;
    Linux)  OS=linux ;;
    *) die "unsupported OS $(uname -s); install Java 17+ manually and re-run" ;;
  esac
  case "$(uname -m)" in
    x86_64|amd64) ARCH=x64 ;;
    arm64|aarch64) ARCH=aarch64 ;;
    *) die "unsupported arch $(uname -m); install Java 17+ manually and re-run" ;;
  esac

  say "No Java 17+ found — downloading a Temurin 21 JRE ($OS/$ARCH)…"
  JRE_URL="https://api.adoptium.net/v3/binary/latest/21/ga/$OS/$ARCH/jre/hotspot/normal/eclipse"
  TMP_JRE="$(mktemp -t kermes-jre.XXXXXX.tar.gz)"
  trap 'rm -f "$TMP_JRE"' EXIT
  curl -fSL "$JRE_URL" -o "$TMP_JRE" || die "JRE download failed"
  rm -rf "$JRE_DIR"; mkdir -p "$JRE_DIR"
  tar -xzf "$TMP_JRE" -C "$JRE_DIR" --strip-components=1
  # macOS bundles live under Contents/Home
  if [ ! -x "$JRE_DIR/bin/java" ] && [ -x "$JRE_DIR/Contents/Home/bin/java" ]; then
    JAVA_HOME_OVERRIDE="$JRE_DIR/Contents/Home"
  else
    JAVA_HOME_OVERRIDE="$JRE_DIR"
  fi
  [ -x "$JAVA_HOME_OVERRIDE/bin/java" ] || die "provisioned JRE looks broken (no bin/java)"
  say "Provisioned JRE → $JAVA_HOME_OVERRIDE"
fi

# --- download + install the app --------------------------------------------
ASSET_URL="https://github.com/$REPO/releases/latest/download/kermes.zip"
TMP_ZIP="$(mktemp -t kermes.XXXXXX.zip)"
trap 'rm -f "${TMP_JRE:-}" "$TMP_ZIP"' EXIT

say "Downloading $ASSET_URL"
curl -fSL "$ASSET_URL" -o "$TMP_ZIP" || die "Download failed. Has a release been published for $REPO yet?"

say "Installing into $APP_DIR"
rm -rf "$APP_DIR"; mkdir -p "$APP_DIR"
unzip -oq "$TMP_ZIP" -d "$APP_DIR"
DIST_LAUNCHER="$(find "$APP_DIR" -type f -path '*/bin/kermes' | head -n1 || true)"
[ -n "$DIST_LAUNCHER" ] || die "couldn't find the kermes launcher inside the archive"
chmod +x "$DIST_LAUNCHER"

# --- write a launcher that pins JAVA_HOME (if we provisioned one) ----------
mkdir -p "$BIN_DIR"
WRAPPER="$BIN_DIR/kermes"
{
  echo '#!/usr/bin/env sh'
  if [ -n "$JAVA_HOME_OVERRIDE" ]; then
    echo "export JAVA_HOME=\"$JAVA_HOME_OVERRIDE\""
  fi
  echo "exec \"$DIST_LAUNCHER\" \"\$@\""
} > "$WRAPPER"
chmod +x "$WRAPPER"
say "Installed launcher → $WRAPPER"

# --- bootstrap --------------------------------------------------------------
"$WRAPPER" init || true

# --- next steps -------------------------------------------------------------
echo
say "Kermes installed."
case ":$PATH:" in
  *":$BIN_DIR:"*) : ;;
  *) printf '\033[1;33mNote:\033[0m add %s to your PATH:\n  export PATH="%s:$PATH"\n' "$BIN_DIR" "$BIN_DIR" ;;
esac
cat <<EOF

Next:
  kermes setup        # configure your LLM provider + API key (+ optional Telegram)
  kermes              # start chatting

(or skip setup and: export KERMES_API_KEY=sk-... )
EOF
