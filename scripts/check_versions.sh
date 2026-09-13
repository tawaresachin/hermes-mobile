#!/usr/bin/env bash
# Verify every place the version is stated agrees with app versionName.
#   app/build.gradle.kts  versionName = "X"        (authoritative)
#   app/build.gradle.kts  versionCode = <X.patch>
#   plugin/plugin.yaml    version: "X"
#   plugin constants      PLUGIN_VERSION derives from plugin.yaml (assert it)
#   git tags              v<X> on both repo HEADs
# Exit 0 = consistent. Used by release.sh, pre-commit hooks, and CI.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_REPO="$(dirname "$SCRIPT_DIR")"
PLUGIN_REPO="${PLUGIN_REPO:-$HOME/hermes-mobile-plugin}"
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
note "version contract (app versionName = $NAME):"

[ "$PV" = "$NAME" ] && ok "plugin.yaml == $NAME" || bad "plugin.yaml is $PV, expected $NAME"

IFS=. read -r MA MI PA <<< "$NAME"
EXPECT_CODE=$(( 10#$MA * 10000 + 10#$MI * 100 + 10#$PA ))
[ "$CODE" = "$EXPECT_CODE" ] && ok "versionCode $CODE derived from $NAME" \
  || bad "versionCode $CODE != $EXPECT_CODE (release.sh sets them together)"

# PLUGIN_VERSION must be *derived* from plugin.yaml — no hardcoded constant
DERIVED=$(cd "$PLUGIN_REPO" && PYTHONPATH=src "${PYTHON:-python3}" -c \
  'from hermes_mobile_plugin.constants import PLUGIN_VERSION; print(PLUGIN_VERSION)' 2>/dev/null)
if [ "$DERIVED" = "$PV" ]; then ok "PLUGIN_VERSION derives == $PV"
elif [ -z "$DERIVED" ]; then bad "could not import PLUGIN_VERSION (python3 + PYTHONPATH=src?)"
else bad "PLUGIN_VERSION=$DERIVED but plugin.yaml says $PV"; fi

for repo in "$APP_REPO" "$PLUGIN_REPO"; do
  if git -C "$repo" rev-parse -q --verify "HEAD^{}" >/dev/null 2>&1 \
     && git -C "$repo" rev-parse -q --verify "refs/tags/v$NAME" >/dev/null 2>&1; then
    tagged=$(git -C "$repo" rev-list -n1 "v$NAME")
    head=$(git -C "$repo" rev-parse HEAD)
    base=$(basename "$repo")
    [ "$tagged" = "$head" ] && ok "$base: tag v$NAME == HEAD" \
      || bad "$base: HEAD is not v$NAME (tag on $(git -C "$repo" log -1 --oneline "v$NAME" | cut -c1-40))"
  else
    bad "$(basename "$repo"): missing tag v$NAME (run scripts/release.sh $NAME)"
  fi
done

# uncommitted source in either repo
for repo in "$APP_REPO" "$PLUGIN_REPO"; do
  dirty=$(git -C "$repo" status --short -- ':!build' 2>/dev/null | grep -v "problems-report" || true)
  [ -z "$dirty" ] && ok "$(basename "$repo"): working tree clean (src)" \
    || bad "$(basename "$repo"): uncommitted changes: $(echo "$dirty" | head -3 | tr '\n' ' ')"
done

[ $fail -eq 0 ] && note "==> consistent" || note "==> RUN scripts/release.sh $NAME to fix"
exit $fail
