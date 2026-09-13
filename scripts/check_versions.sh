#!/usr/bin/env bash
# Version contract across the two products. App and plugin carry their OWN
# independent versions (plugin 0.0.6 has nothing to do with app 0.0.47 — the
# earlier "shared number" scheme was wrong and forced a meaningless bump):
#
#   APP    app/build.gradle.kts versionName = "X"  + versionCode derived
#          + tag vX == HEAD
#   PLUGIN plugin.yaml version = "P" (single source; constants.py DERIVES
#          PLUGIN_VERSION from it) + tag vP == HEAD
#          + deployed copy (~/.hermes/plugins/hermes-mobile-qr) matches repo
#
# Exit 0 = consistent. Used by release.sh, pre-commit hooks, and CI.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_REPO="$(dirname "$SCRIPT_DIR")"
PLUGIN_REPO="${PLUGIN_REPO:-$HOME/hermes-mobile-plugin}"
DEPLOY_DIR="$HOME/.hermes/plugins/hermes-mobile-qr"
fail=0
note() { printf '%s\n' "$*"; }
ok()   { note "  ok   $*"; }
bad()  { note "  FAIL $*"; fail=1; }

GLA="$APP_REPO/app/build.gradle.kts"
YAM="$PLUGIN_REPO/plugin.yaml"
[ -f "$GLA" ] || { bad "missing $GLA"; exit 1; }
[ -f "$YAM" ] || { bad "missing $YAM (set PLUGIN_REPO)"; exit 1; }

NAME=$(grep -oE 'versionName = "[0-9.]+"' "$GLA" | grep -oE '[0-9.]+')
CODE=$(grep -oE 'versionCode = [0-9]+'  "$GLA" | grep -oE '[0-9]+')
PV=$(grep -oE '^version: "[0-9.]+"' "$YAM" | grep -oE '[0-9.]+')
note "version contract: app=$NAME plugin=$PV (independent lines)"

IFS=. read -r MA MI PA <<< "$NAME"
EXPECT_CODE=$(( 10#$MA * 10000 + 10#$MI * 100 + 10#$PA ))
[ "$CODE" = "$EXPECT_CODE" ] && ok "app versionCode $CODE derived from $NAME" \
  || bad "app versionCode $CODE != $EXPECT_CODE (use release.sh to bump)"

# PLUGIN_VERSION must be *derived* from plugin.yaml — no hardcoded constant
DERIVED=$(cd "$PLUGIN_REPO" && PYTHONPATH=src "${PYTHON:-python3}" -c \
  'from hermes_mobile_plugin.constants import PLUGIN_VERSION; print(PLUGIN_VERSION)' 2>/dev/null)
if [ "$DERIVED" = "$PV" ]; then ok "PLUGIN_VERSION derives from plugin.yaml == $PV"
elif [ -z "$DERIVED" ]; then bad "could not import PLUGIN_VERSION (python3 + PYTHONPATH=src?)"
else bad "PLUGIN_VERSION=$DERIVED but plugin.yaml says $PV"; fi

# tag == HEAD per repo, per its own version
check_tag() { # <repo> <ver> <label>
  local repo="$1" ver="$2" label="$3" base
  base=$(basename "$repo")
  if git -C "$repo" rev-parse -q --verify "refs/tags/v$ver" >/dev/null 2>&1; then
    tagged=$(git -C "$repo" rev-list -n1 "v$ver")
    head=$(git -C "$repo" rev-parse HEAD)
    [ "$tagged" = "$head" ] && ok "$base: tag v$ver == HEAD" \
      || bad "$base: HEAD moved past tag v$ver (run release.sh to re-tag)"
  else
    bad "$base: no tag v$ver (run scripts/release.sh for $label)"
  fi
}
check_tag "$APP_REPO" "$NAME" "app"
check_tag "$PLUGIN_REPO" "$PV" "plugin"

# deployed plugin copy must match the repo exactly (source is canonical)
if [ -d "$DEPLOY_DIR/hermes_mobile_plugin" ]; then
  if diff -rq "$PLUGIN_REPO/src/hermes_mobile_plugin" "$DEPLOY_DIR/hermes_mobile_plugin" \
       --exclude=__pycache__ >/dev/null 2>&1 \
     && diff -q "$YAM" "$DEPLOY_DIR/plugin.yaml" >/dev/null 2>&1; then
    ok "deployed plugin == repo source"
  else
    bad "deployed plugin drifts from repo (release.sh syncs it)"
  fi
else
  note "  --   deployed plugin dir absent (fresh machine?)"
fi

# uncommitted source in either repo
for repo in "$APP_REPO" "$PLUGIN_REPO"; do
  dirty=$(git -C "$repo" status --short -- ':!build' 2>/dev/null | grep -v "problems-report" || true)
  [ -z "$dirty" ] && ok "$(basename "$repo"): working tree clean (src)" \
    || bad "$(basename "$repo"): uncommitted changes: $(echo "$dirty" | head -3 | tr '\n' ' ')"
done

[ $fail -eq 0 ] && note "==> consistent" || note "==> fix with scripts/release.sh (see usage in file)"
exit $fail
