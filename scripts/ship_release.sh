#!/usr/bin/env bash
# Sign + ship release artifacts to GitHub Releases.
#   scripts/ship_release.sh <app|plugin> <version> [notes...]
#
# Design: the APP ships one universal (per-OS nothing — every Android device
# takes the same APK) signed APK. The PLUGIN is pure Python (py3-none-any
# wheel), so ONE wheel + ONE sdist already cover Windows/macOS/Linux/Termux
# — its native deps (pyyaml, qrcode) resolve per-OS from PyPI at install time.
# We do NOT ship fake per-OS plugin binaries. Both artifacts are GPG-signed
# with a detached SHA256SUMS.asc so users can verify integrity + author.
#
# Preconditions: the version's tag already exists on origin (release.sh makes
# them first), gh is authenticated, gpg key imported.
set -euo pipefail
KDIR="$HOME/.hermes/keystore"
GPG_KEYID="4623379304AF02835657282605B0890B1CD804A5"
gitx() { ( cd "$1" && shift && perl -e '$SIG{CHLD}="DEFAULT"; exec @ARGV' git "$@" ); }
gpgsign() { perl -e '$SIG{CHLD}="DEFAULT"; exec @ARGV' gpg --batch --yes --pinentry-mode loopback \
  --passphrase "$(cat "$KDIR/gpg-password")" --local-user "$GPG_KEYID" "$@"; }

KIND="${1:?usage: ship_release.sh <app|plugin> <version>}"; shift
VER="${1:?version}"; shift
APP_REPO="$HOME/hermes-mobile-app"; PLG_REPO="$HOME/hermes-mobile-plugin"
case "$KIND" in
  app)    REPO="$APP_REPO"; SLUG="tawaresachin/hermes-mobile";;
  plugin) REPO="$PLG_REPO"; SLUG="tawaresachin/hermes-mobile-plugin";;
  *) echo "!! kind must be app|plugin" >&2; exit 2;;
esac
TAG="v$VER"
gitx "$REPO" fetch -q origin "refs/tags/$TAG:refs/tags/$TAG" 2>/dev/null || \
  perl -e '$SIG{CHLD}="DEFAULT"; exec @ARGV' git -C "$REPO" fetch -q origin "refs/tags/$TAG" 2>/dev/null || true
gitx "$REPO" rev-parse -q --verify "refs/tags/$TAG" >/dev/null || { echo "!! $REPO has no tag $TAG — run release.sh first" >&2; exit 3; }

DIST="$KDIR/dist-$KIND-$TAG"; rm -rf "$DIST"; mkdir -p "$DIST"
ASSETS=()
NOTES_FILE="$DIST/NOTES.md"

if [ "$KIND" = app ]; then
  APK="$APP_REPO/app/build/outputs/apk/release/Hermes-Mobile-v$VER.apk"
  [ -f "$APK" ] || { echo "!! missing $APK — run assembleRelease first" >&2; exit 4; }
  cp "$APK" "$DIST/"
  ASSETS+=("$DIST/Hermes-Mobile-v$VER.apk")
  cat > "$NOTES_FILE" <<EOF
Android client for Hermes Agent. One APK runs on every device (Android 8+); no per-OS variants needed.

**Install:** download \`Hermes-Mobile-v$VER.apk\`, open it, allow "install from unknown sources" once.

**Verify:**
\`\`\`bash
sha256sum -c SHA256SUMS --ignore-missing
gpg --import hermes-release-public-key.asc   # or fetch key $GPG_KEYID
gpg --verify SHA256SUMS.asc SHA256SUMS
\`\`\`
Public key: https://raw.githubusercontent.com/tawaresachin/hermes-mobile-plugin/main/docs/hermes-release-public-key.asc
EOF
else
  ( cd "$PLG_REPO" && perl -e '$SIG{CHLD}="DEFAULT"; exec @ARGV' uv build --out-dir "$DIST" >/dev/null 2>&1 ) || \
    { echo "!! uv build failed"; exit 4; }
  shopt -s nullglob; W=( "$DIST"/*.whl "$DIST"/*.tar.gz ); shopt -u nullglob
  [ ${#W[@]} -ge 2 ] || { echo "!! wheel/sdist missing" >&2; exit 4; }
  ASSETS+=( "${W[@]}" )
  cat > "$NOTES_FILE" <<EOF
Server-side companion plugin for Hermes Agent. **Pure Python** — this single \`py3-none-any\` wheel works unchanged on Windows, macOS, Linux and Termux; native deps (pyyaml, qrcode) resolve per-OS from PyPI at install time. No per-OS binaries exist because none are needed.

**Install (any OS):**
\`\`\`bash
pip install "hermes-mobile-plugin[install] @ git+https://github.com/tawaresachin/hermes-mobile-plugin.git@$TAG"
# or from this release:
pip install hermes_mobile_plugin-$VER-py3-none-any.whl
hermes-mobile-plugin install   # restart gateway, then: hermes-mobile-plugin qr
\`\`\`

**Verify:**
\`\`\`bash
sha256sum -c SHA256SUMS --ignore-missing
gpg --import hermes-release-public-key.asc
gpg --verify SHA256SUMS.asc SHA256SUMS
\`\`\`
EOF
fi

( cd "$DIST" && { for a in "${ASSETS[@]}"; do sha256sum "$(basename "$a")"; done; } > SHA256SUMS )
gpgsign --detach-sign --armor --output "$DIST/SHA256SUMS.asc" "$DIST/SHA256SUMS"
gpgsign --verify "$DIST/SHA256SUMS.asc" "$DIST/SHA256SUMS" 2>&1 | tail -1
cp "$PLG_REPO/docs/hermes-release-public-key.asc" "$DIST/"
ASSETS+=( "$DIST/SHA256SUMS" "$DIST/SHA256SUMS.asc" "$DIST/hermes-release-public-key.asc" )

if perl -e '$SIG{CHLD}="DEFAULT"; exec @ARGV' gh release view "$TAG" -R "$SLUG" >/dev/null 2>&1; then
  perl -e '$SIG{CHLD}="DEFAULT"; exec @ARGV' gh release edit "$TAG" -R "$SLUG" --notes-file "$NOTES_FILE"
  for a in "${ASSETS[@]}"; do
    perl -e '$SIG{CHLD}="DEFAULT"; exec @ARGV' gh release upload "$TAG" "${a}" -R "$SLUG" --clobber 2>/dev/null || true
  done
  echo "==> updated release $TAG on $SLUG"
else
  perl -e '$SIG{CHLD}="DEFAULT"; exec @ARGV' gh release create "$TAG" "${ASSETS[@]}" \
    -R "$SLUG" --target "$(gitx "$REPO" rev-parse "refs/tags/$TAG^{commit}")" \
    --title "$KIND $TAG" --notes-file "$NOTES_FILE"
  echo "==> created release $TAG on $SLUG"
fi
