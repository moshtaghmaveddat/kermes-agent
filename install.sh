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

# --- data-safety invariant --------------------------------------------------
# Install/update ONLY ever replace the two installed-artifact dirs below.
# Everything else under $KERMES_HOME — config (API key), memory, skills,
# vectors, checkpoints, inbox, schedules — is USER DATA and is never removed.
# These guards make that impossible to get wrong (e.g. an empty var → rm /).
[ -n "${KERMES_HOME:-}" ]            || die "KERMES_HOME is unset; refusing to continue"
[ "$APP_DIR" = "$KERMES_HOME/app" ]  || die "unexpected APP_DIR ($APP_DIR); refusing to remove it"
[ "$JRE_DIR" = "$KERMES_HOME/jre" ]  || die "unexpected JRE_DIR ($JRE_DIR); refusing to remove it"

# --- resolve a Java 17+ runtime, pinned to an ABSOLUTE path -----------------
# The launcher must NEVER depend on the ambient PATH: macOS ships a
# /usr/bin/java stub, and a Java that's on PATH in one shell may be absent in
# another. So we always resolve a concrete java.home and pin it in the launcher.
JAVA_HOME_OVERRIDE=""
java_major()   { v=$("$1" -version 2>&1 | grep -oE 'version "[0-9]+' | grep -oE '[0-9]+' | head -n1 || true); echo "${v:-0}"; }
java_home_of() { "$1" -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java\.home = //p' | head -n1 || true; }

# Prefer a JRE we provisioned earlier (most stable — we own it).
PROVISIONED=""
if   [ -x "$JRE_DIR/bin/java" ];              then PROVISIONED="$JRE_DIR"
elif [ -x "$JRE_DIR/Contents/Home/bin/java" ]; then PROVISIONED="$JRE_DIR/Contents/Home"
fi

if [ -n "$PROVISIONED" ] && [ "$(java_major "$PROVISIONED/bin/java")" -ge 17 ]; then
  JAVA_HOME_OVERRIDE="$PROVISIONED"
  say "Using previously provisioned JRE at $JAVA_HOME_OVERRIDE."
elif command -v java >/dev/null 2>&1 && [ "$(java_major "$(command -v java)")" -ge 17 ]; then
  # A real Java is on PATH now — pin ITS home so the launcher works in every
  # shell, not only ones where java happens to be on PATH.
  JAVA_HOME_OVERRIDE="$(java_home_of "$(command -v java)")"
  if [ -n "$JAVA_HOME_OVERRIDE" ] && [ -x "$JAVA_HOME_OVERRIDE/bin/java" ]; then
    say "Pinning existing Java ($(java_major "$JAVA_HOME_OVERRIDE/bin/java")) at $JAVA_HOME_OVERRIDE."
  else
    JAVA_HOME_OVERRIDE=""   # couldn't resolve a stable home → provision below
  fi
fi

if [ -z "$JAVA_HOME_OVERRIDE" ]; then
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

  say "No usable Java 17+ found — downloading a Temurin 21 JRE ($OS/$ARCH)…"
  JRE_URL="https://api.adoptium.net/v3/binary/latest/21/ga/$OS/$ARCH/jre/hotspot/normal/eclipse"
  TMP_JRE="$(mktemp -t kermes-jre.XXXXXX.tar.gz)"
  trap 'rm -f "${TMP_JRE:-}"' EXIT
  curl -fSL "$JRE_URL" -o "$TMP_JRE" || die "JRE download failed"
  rm -rf "$JRE_DIR"; mkdir -p "$JRE_DIR"
  tar -xzf "$TMP_JRE" -C "$JRE_DIR" --strip-components=1
  # macOS bundles live under Contents/Home
  if [ ! -x "$JRE_DIR/bin/java" ] && [ -x "$JRE_DIR/Contents/Home/bin/java" ]; then
    JAVA_HOME_OVERRIDE="$JRE_DIR/Contents/Home"
  else
    JAVA_HOME_OVERRIDE="$JRE_DIR"
  fi
  say "Provisioned JRE → $JAVA_HOME_OVERRIDE"
fi

# Must end with a usable, absolute JAVA_HOME to pin into the launcher.
[ -n "$JAVA_HOME_OVERRIDE" ] && [ -x "$JAVA_HOME_OVERRIDE/bin/java" ] \
  || die "could not resolve a Java 17+ runtime to pin"

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

# --- write a launcher that always pins JAVA_HOME (never relies on PATH) -----
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
