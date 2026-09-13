#!/usr/bin/env bash
# Hermes Mobile — single-source release tool.
#
# One product version shared by BOTH repos (hermes-mobile app +
# hermes-mobile-plugin). Running this is the ONLY sanctioned way to bump a
# version; hand-editing version fields is what caused the 0.0.46-vs-0.0.6
# drift the user reported.
#
#   scripts/release.sh <x.y.z> ["commit message"]
#
# What it does, in order:
#   1. Validates the version (x.y.z, never a downgrade)
#   2. App repo:   app/build.gradle.kts versionCode = <z>, versionName = "x.y.z"
#   3. Plugin repo: plugin.yaml version = "x.y.z"  (PLUGIN_VERSION in code is
#      DERIVED from plugin.yaml at import — nothing else to bump)
#   4. Commits (version + release tooling only; feature code gets its own
#      commit BEFORE you run this)
#   5. Annotated tag v<x.y.z> on BOTH repos, pushes branch + tag
#   6. Syncs the deployed plugin copy (~/.hermes/plugins/hermes-mobile-qr)
#      so the next gateway start reports the new version
#   7. Runs check_versions.sh and prints the verdict
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_REPO="$(dirname "$SCRIPT_DIR")"
PLUGIN_REPO="${PLUGIN_REPO:-$HOME/hermes-mobile-plugin}"
DEPLOY_DIR="$HOME/.hermes/plugins/hermes-mobile-qr"

NEW="${1:?usage: release.sh <x.y.z> [\"message\"]}"
MSG="${2:-release v$NEW}"

[[ $NEW =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "!! version must be x.y.z, got: $NEW" >&2; exit 1; }
[ -f "$APP_REPO/app/build.gradle.kts" ] || { echo "!! app repo not found: $APP_REPO" >&2; exit 1; }
[ -f "$PLUGIN_REPO/plugin.yaml" ] || { echo "!! plugin repo not found: $PLUGIN_REPO (set PLUGIN_REPO)" >&2; exit 1; }

# Termux git-push SIGCHLD workaround (inherited SIG_IGN breaks pack-objects)
gitx() { ( cd "$1" && shift && perl -e '$SIG{CHLD}="DEFAULT"; exec @ARGV' git "$@" ); }

CUR="$(grep -oE 'versionName = "[0-9.]+"' "$APP_REPO/app/build.gradle.kts" | grep -oE '[0-9.]+')"
if [ "$CUR" != "$NEW" ] && [ "$(printf '%s\n%s\n' "$CUR" "$NEW" | sort -V | head -1)" = "$NEW" ]; then
  echo "!! refusing downgrade: app is $CUR, asked for $NEW" >&2; exit 1
fi
# Android requires monotonically increasing versionCode: derive it from the
# full semver, not just the patch (0.1.0's patch 0 would be < 0.0.47's 47).
IFS=. read -r MA MI PA <<< "$NEW"
CODE=$(( 10#$MA * 10000 + 10#$MI * 100 + 10#$PA ))

# ── 1. app version fields ─────────────────────────────────────────────
sed -i "s/versionCode = [0-9][0-9]*/versionCode = $CODE/; s/versionName = \"[0-9.]*\"/versionName = \"$NEW\"/" \
  "$APP_REPO/app/build.gradle.kts"

# ── 2. plugin manifest (single source for PLUGIN_VERSION) ─────────────
sed -i "s/^version: \"[0-9.]*\"/version: \"$NEW\"/" "$PLUGIN_REPO/plugin.yaml"

# ── 3. commits (skip silently when nothing staged — idempotent re-runs).
# --no-verify: the pre-commit version hook checks for tag v$NEW, which only
# exists after step 4; release.sh runs the same contract at step 6 anyway.
gitx "$APP_REPO" add app/build.gradle.kts scripts
gitx "$APP_REPO" diff --cached --quiet || gitx "$APP_REPO" commit -q --no-verify -m "$MSG"
gitx "$PLUGIN_REPO" add plugin.yaml src tests
gitx "$PLUGIN_REPO" diff --cached --quiet || gitx "$PLUGIN_REPO" commit -q --no-verify -m "$MSG"

# ── 4. tag + push both repos ──────────────────────────────────────────
for repo in "$APP_REPO" "$PLUGIN_REPO"; do
  head_now="$(gitx "$repo" rev-parse HEAD)"
  if gitx "$repo" rev-parse -q --verify "refs/tags/v$NEW" >/dev/null; then
    if [ "$(gitx "$repo" rev-list -n1 "v$NEW")" != "$head_now" ]; then
      # v<NEW> exists on a different commit (hot-fix re-tag) — move it, remote included
      gitx "$repo" tag -d "v$NEW" >/dev/null
      gitx "$repo" push origin ":refs/tags/v$NEW" >/dev/null 2>&1 || true
      gitx "$repo" tag -a "v$NEW" -m "v$NEW"
    fi
  else
    gitx "$repo" tag -a "v$NEW" -m "v$NEW"
  fi
  branch="$(gitx "$repo" branch --show-current)"
  gitx "$repo" push -q origin "$branch"
  gitx "$repo" push -q origin "refs/tags/v$NEW"
done

# ── 5. deployed plugin copy (gateway picks the new number on next start) ──
if [ -d "$DEPLOY_DIR/hermes_mobile_plugin" ]; then
  cp "$PLUGIN_REPO"/src/hermes_mobile_plugin/*.py "$DEPLOY_DIR/hermes_mobile_plugin/"
  cp "$PLUGIN_REPO/plugin.yaml" "$DEPLOY_DIR/plugin.yaml"
  rm -rf "$DEPLOY_DIR/__pycache__" "$DEPLOY_DIR/hermes_mobile_plugin/__pycache__"
  echo "   deployed plugin synced: $DEPLOY_DIR"
fi

# ── 5b. install the version-contract pre-commit hook in both repos ──
for repo in "$APP_REPO" "$PLUGIN_REPO"; do
  mkdir -p "$repo/.git/hooks"
  cp "$SCRIPT_DIR/git-hooks/pre-commit" "$repo/.git/hooks/pre-commit"
  chmod +x "$repo/.git/hooks/pre-commit"
done

# ── 6. verdict ────────────────────────────────────────────────────────
"$SCRIPT_DIR/check_versions.sh"
echo "==> released v$NEW (tag v$NEW on both repos, pushed)"
