#!/usr/bin/env bash
# Hermes Mobile — release tool.
#
# App and plugin are SEPARATE products with INDEPENDENT versions:
#   app    : vX = app versionName (Android), from app/build.gradle.kts
#   plugin : vX = plugin's own line (currently 0.0.6), from plugin.yaml
# (The earlier design coupled them to one shared number — wrong, the plugin
# is not the app and its version jumped 0.0.6 -> 0.0.47 with no meaning.)
#
#   scripts/release.sh <app|x.y.z> [<plugin|x.y.z>] ["message"]
#     release.sh 0.0.48              -> app-only bump
#     release.sh none 0.0.7          -> plugin-only bump
#     release.sh 0.0.48 0.0.7        -> both, own numbers
#
# What it does: sets version fields, commits, annotated tag v<ver> on the
# bumped repos, pushes branch+tags, syncs the deployed plugin copy, prints
# the consistency verdict via check_versions.sh.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_REPO="$(dirname "$SCRIPT_DIR")"
PLUGIN_REPO="${PLUGIN_REPO:-$HOME/hermes-mobile-plugin}"
DEPLOY_DIR="$HOME/.hermes/plugins/hermes-mobile-qr"

NEW_APP="${1:?usage: release.sh <app|x.y.z|none> [<plugin|x.y.z|none>] [\"message\"]}"
NEW_PLG="${2:-none}"
MSG="${3:-release}"
[ "$NEW_APP" = "none" ] && NEW_APP=""
[ "$NEW_PLG" = "none" ] && NEW_PLG=""

semver() { [[ $1 =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; }
[ -n "$NEW_APP" ] && { semver "$NEW_APP" || { echo "!! bad app version: $NEW_APP" >&2; exit 1; }; }
[ -n "$NEW_PLG" ] && { semver "$NEW_PLG" || { echo "!! bad plugin version: $NEW_PLG" >&2; exit 1; }; }
[ -n "$NEW_APP" ] || [ -n "$NEW_PLG" ] || { echo "!! nothing to release"; exit 1; }
[ -f "$APP_REPO/app/build.gradle.kts" ] || { echo "!! app repo not found" >&2; exit 1; }
[ -f "$PLUGIN_REPO/plugin.yaml" ] || { echo "!! plugin repo not found (set PLUGIN_REPO)" >&2; exit 1; }

# Termux git-push SIGCHLD workaround (inherited SIG_IGN breaks pack-objects)
gitx() { ( cd "$1" && shift && perl -e '$SIG{CHLD}="DEFAULT"; exec @ARGV' git "$@" ); }

CUR_APP=$(grep -oE 'versionName = "[0-9.]+"' "$APP_REPO/app/build.gradle.kts" | grep -oE '[0-9.]+')
CUR_PLG=$(grep -oE '^version: "[0-9.]+"' "$PLUGIN_REPO/plugin.yaml" | grep -oE '[0-9.]+')
if [ -n "$NEW_APP" ] && [ "$CUR_APP" != "$NEW_APP" ] \
   && [ "$(printf '%s\n%s\n' "$CUR_APP" "$NEW_APP" | sort -V | head -1)" = "$NEW_APP" ]; then
  echo "!! refusing app downgrade: $CUR_APP -> $NEW_APP" >&2; exit 1
fi
if [ -n "$NEW_PLG" ] && [ "$CUR_PLG" != "$NEW_PLG" ] \
   && [ "$(printf '%s\n%s\n' "$CUR_PLG" "$NEW_PLG" | sort -V | head -1)" = "$NEW_PLG" ]; then
  echo "!! refusing plugin downgrade: $CUR_PLG -> $NEW_PLG" >&2; exit 1
fi

release_repo() {  # <repo> <newver> <commit-pathspec> <extra-sync-cmd>
  local repo="$1" ver="$2" pathspec="$3"
  branch="$(gitx "$repo" branch --show-current)"
  head_now="$(gitx "$repo" rev-parse HEAD)"
  if gitx "$repo" rev-parse -q --verify "refs/tags/v$ver" >/dev/null; then
    if [ "$(gitx "$repo" rev-list -n1 "v$ver")" != "$head_now" ]; then
      # v<ver> exists on a different commit (hot-fix re-tag) — move it, remote included
      gitx "$repo" tag -d "v$ver" >/dev/null
      gitx "$repo" push origin ":refs/tags/v$ver" >/dev/null 2>&1 || true
      gitx "$repo" tag -a "v$ver" -m "v$ver"
    fi
  else
    gitx "$repo" tag -a "v$ver" -m "v$ver"
  fi
  gitx "$repo" push -q origin "$branch"
  gitx "$repo" push -q origin "refs/tags/v$ver"
}

# ── app bump ──────────────────────────────────────────────────────────
if [ -n "$NEW_APP" ]; then
  # versionCode must increase monotonically; derive from full semver so a
  # 0.1.0 release still outranks 0.0.47 (1*10000 > 47).
  IFS=. read -r MA MI PA <<< "$NEW_APP"
  CODE=$(( 10#$MA * 10000 + 10#$MI * 100 + 10#$PA ))
  sed -i "s/versionCode = [0-9][0-9]*/versionCode = $CODE/; s/versionName = \"[0-9.]*\"/versionName = \"$NEW_APP\"/" \
    "$APP_REPO/app/build.gradle.kts"
  gitx "$APP_REPO" add app/build.gradle.kts scripts
  gitx "$APP_REPO" diff --cached --quiet || gitx "$APP_REPO" commit -q --no-verify -m "release app v$NEW_APP${MSG:+ — $MSG}"
  release_repo "$APP_REPO" "$NEW_APP"
fi

# ── plugin bump ───────────────────────────────────────────────────────
if [ -n "$NEW_PLG" ]; then
  sed -i "s/^version: \"[0-9.]*\"/version: \"$NEW_PLG\"/" "$PLUGIN_REPO/plugin.yaml"
  sed -i "0,/^version = \"[0-9.]*\"/s//version = \"$NEW_PLG\"/" "$PLUGIN_REPO/pyproject.toml"
  gitx "$PLUGIN_REPO" add plugin.yaml pyproject.toml
  gitx "$PLUGIN_REPO" diff --cached --quiet || gitx "$PLUGIN_REPO" commit -q --no-verify -m "release plugin v$NEW_PLG${MSG:+ — $MSG}"
  release_repo "$PLUGIN_REPO" "$NEW_PLG"
fi

# ── deployed plugin copy (gateway reads the new number on next start) ──
if [ -d "$DEPLOY_DIR/hermes_mobile_plugin" ]; then
  cp "$PLUGIN_REPO"/src/hermes_mobile_plugin/*.py "$DEPLOY_DIR/hermes_mobile_plugin/"
  cp "$PLUGIN_REPO/plugin.yaml" "$DEPLOY_DIR/plugin.yaml"
  rm -rf "$DEPLOY_DIR/__pycache__" "$DEPLOY_DIR/hermes_mobile_plugin/__pycache__"
  echo "   deployed plugin synced: $DEPLOY_DIR"
fi

# ── install the version-contract pre-commit hook in both repos ──
for repo in "$APP_REPO" "$PLUGIN_REPO"; do
  mkdir -p "$repo/.git/hooks"
  cp "$SCRIPT_DIR/git-hooks/pre-commit" "$repo/.git/hooks/pre-commit"
  chmod +x "$repo/.git/hooks/pre-commit"
done

"$SCRIPT_DIR/check_versions.sh"
echo "==> done: app=${NEW_APP:-$CUR_APP} plugin=${NEW_PLG:-$CUR_PLG} (tags pushed)"
