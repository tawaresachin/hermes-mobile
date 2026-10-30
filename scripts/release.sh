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
# the consistency verdict via check_versions.sh. The app's signed release is
# then published by CI on the tag (release.yml) — no signing key here.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_REPO="$(dirname "$SCRIPT_DIR")"
# The plugin repo is the app repo's sibling in both layouts in use
# (~/hermes-mobile-app + ~/hermes-mobile-plugin, and
# ~/Documents/github/hermes-mobile + .../hermes-mobile-plugin).
PLUGIN_REPO="${PLUGIN_REPO:-$(dirname "$APP_REPO")/hermes-mobile-plugin}"
DEPLOY_DIR="$HOME/.hermes/plugins/hermes-mobile-plugin"

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

# A release commit carries only the version bump, so anything left
# uncommitted here would be silently absent from the tag and from the APK
# built off it. Fail before the bump instead of warning after the push.
require_clean() {  # <repo> <label>
  if [ -n "$(gitx "$1" status --porcelain)" ]; then
    echo "!! $2 has uncommitted changes — commit them first, or the tag" >&2
    echo "   would point at a commit that does not contain them:" >&2
    gitx "$1" status --short >&2
    exit 1
  fi
}
[ -n "$NEW_APP" ] && require_clean "$APP_REPO" hermes-mobile
[ -n "$NEW_PLG" ] && require_clean "$PLUGIN_REPO" hermes-mobile-plugin

release_repo() {  # <repo> <newver>
  local repo="$1" ver="$2"
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
  # versionCode must increase monotonically; 10K/100/1 lanes support
  # 99 patches per minor, 99 minors per major (rollover at 100).
  # Formula: major*10000 + minor*100 + patch
  IFS=. read -r MA MI PA <<< "$NEW_APP"
  # Validate 0-99 range for minor and patch (major unbounded)
  if (( 10#$MI > 99 || 10#$PA > 99 )); then
    echo "!! version component exceeds 99: minor=$MI patch=$PA (max 99)" >&2
    exit 1
  fi
  CODE=$(( 10#$MA * 10000 + 10#$MI * 100 + 10#$PA ))
  # perl -i, not sed -i: BSD/macOS sed rejects the bare -i form. Values reach
  # perl through the environment so shell and perl quoting cannot collide.
  CODE="$CODE" V="$NEW_APP" perl -i -pe '
    s/versionCode = [0-9]+/versionCode = $ENV{CODE}/;
    s/versionName = "[0-9.]+"/versionName = "$ENV{V}"/;
  ' "$APP_REPO/app/build.gradle.kts"
  gitx "$APP_REPO" add app/build.gradle.kts scripts
  gitx "$APP_REPO" diff --cached --quiet || gitx "$APP_REPO" commit -q --no-verify -m "release app v$NEW_APP${MSG:+ — $MSG}"
  release_repo "$APP_REPO" "$NEW_APP"
fi

# ── plugin bump ───────────────────────────────────────────────────────
if [ -n "$NEW_PLG" ]; then
  V="$NEW_PLG" perl -i -pe 's/^version: "[0-9.]+"/version: "$ENV{V}"/' "$PLUGIN_REPO/plugin.yaml"
  cp "$PLUGIN_REPO/plugin.yaml" "$PLUGIN_REPO/src/hermes_mobile_plugin/plugin.yaml"
  # -0777 slurps the file, so a plain s/// (no /g) replaces only the FIRST
  # `version = ` line — what GNU sed's non-portable 0,/re/ address did.
  V="$NEW_PLG" perl -0777 -i -pe 's/^version = "[0-9.]+"/version = "$ENV{V}"/m' "$PLUGIN_REPO/pyproject.toml"
  gitx "$PLUGIN_REPO" add plugin.yaml src/hermes_mobile_plugin/plugin.yaml pyproject.toml
  gitx "$PLUGIN_REPO" diff --cached --quiet || gitx "$PLUGIN_REPO" commit -q --no-verify -m "release plugin v$NEW_PLG${MSG:+ — $MSG}"
  if [ "$CUR_PLG" = "$NEW_PLG" ]; then
    # Same version = docs/tooling only. auto-tag.yml ignores a plugin.yaml that
    # did not change, so it will never tag this commit; moving the tag here is
    # the only way back to "tag == HEAD".
    release_repo "$PLUGIN_REPO" "$NEW_PLG"
  else
    # Push the bump and stop. hermes-mobile-plugin's auto-tag.yml owns everything
    # after this (tag, wheel+sdist, GitHub release, PyPI) and triggers on this
    # push. Tagging here would make it see an existing tag and skip all of it,
    # leaving the version tagged with no artifacts and no PyPI upload.
    gitx "$PLUGIN_REPO" push -q origin "$(gitx "$PLUGIN_REPO" branch --show-current)"
  fi
fi

# ── deployed plugin copy (gateway reads the new number on next start) ──
if [ -d "$DEPLOY_DIR/hermes_mobile_plugin" ]; then
  cp "$PLUGIN_REPO"/src/hermes_mobile_plugin/*.py "$DEPLOY_DIR/hermes_mobile_plugin/"
  cp "$PLUGIN_REPO/src/hermes_mobile_plugin/plugin.yaml" "$DEPLOY_DIR/hermes_mobile_plugin/plugin.yaml"
  cp "$PLUGIN_REPO/plugin.yaml" "$DEPLOY_DIR/plugin.yaml"
  rm -rf "$DEPLOY_DIR/__pycache__" "$DEPLOY_DIR/hermes_mobile_plugin/__pycache__"
  echo "   deployed plugin synced: $DEPLOY_DIR"
fi

# ── install the version-contract pre-commit hook ──
# App repo only. The plugin repo releases itself now (its auto-tag.yml creates
# the tag), so "tag == HEAD" can never hold at plugin-commit time, and its
# contract is enforced there.
mkdir -p "$APP_REPO/.git/hooks"
cp "$SCRIPT_DIR/git-hooks/pre-commit" "$APP_REPO/.git/hooks/pre-commit"
chmod +x "$APP_REPO/.git/hooks/pre-commit"

# ── the app publishes from CI ──
# The tag push above starts release.yml: it builds + verifies the signed APK,
# GPG-signs SHA256SUMS and creates the GitHub release. Signing lives there so
# no key or passphrase (or plaintext password file) sits in a working tree;
# the key is only in the repo secrets GPG_PRIVATE_KEY / GPG_PASSPHRASE.
if [ -n "$NEW_APP" ]; then
  echo "   app release publishes from CI on tag v$NEW_APP (release.yml)"
fi

if [ -n "$NEW_PLG" ]; then
  # auto-tag.yml creates the plugin tag moments from now, so the contract check
  # would otherwise report the tag missing on this immediate run.
  PLUGIN_TAG_PENDING=1 "$SCRIPT_DIR/check_versions.sh"
else
  "$SCRIPT_DIR/check_versions.sh"
fi
echo "==> done: app=${NEW_APP:-$CUR_APP} plugin=${NEW_PLG:-$CUR_PLG} (tags pushed${NEW_APP:+; app publishes from CI})"
