#!/usr/bin/env bash
set -euo pipefail
umask 077
# Frontend-only release: existing backend, games, FRP and nginx stay running.
project=/home/leo/mahjong_20260822
site=/var/www/mahjong-yaoming-20260907
backup="$project/backups/draw-selection-space-20260911"
archive=/tmp/mahjong-draw-selection-space-20260911.tar.gz
archive_sha=${1:?Expected archive SHA256}
new_index=57fe1dcfa3b620b31088b43f56d65223d576cc994d5d25dc750e04c0c21f192a
test "$(readlink -f "$project")" = "$project"
test "$(readlink -f "$site")" = "$site"
test ! -e "$backup"
test "$(sha256sum "$archive" | cut -d' ' -f1)" = "$archive_sha"
test "$(sha256sum "$site/index.html" | cut -d' ' -f1)" = acb2f899c2a0bbeb02e011b650577d4dba8634a5ef5d6d177a53dfb4bec25343
python3 - "$archive" <<'PY'
import sys, tarfile
allowed = set(["README.md","frontend/src/yaoming/TableView.vue","frontend/src/yaoming/useTableKeyboard.ts","frontend/src/yaoming/table-keyboard.test.ts","frontend/src/yaoming/playability.test.ts","frontend/src/yaoming/draw-selection.test.ts","docs/DRAW_SELECTION_SPACE_PLAN.md","docs/DRAW_SELECTION_SPACE_TEST_REPORT.md","scripts/deploy-draw-selection-space.sh","frontend/dist/index.html","frontend/dist/assets/index-tMcLslvO.js","frontend/dist/assets/index-BjeYD7i5.css"])
with tarfile.open(sys.argv[1]) as bundle:
    members = bundle.getmembers()
    assert len(members) == len(allowed)
    assert {member.name for member in members} == allowed
    assert all(member.isfile() for member in members)
PY
before_pid=$(systemctl show mahjong-backend -p MainPID --value)
before_jar=$(sha256sum "$project/backend/target/mahjong-server-0.1.0.jar" | cut -d' ' -f1)
test "$before_pid" != 0
systemctl is-active --quiet mahjong-backend nginx frpc_tencent
mkdir -p "$backup/stage"
cp "$site/index.html" "$backup/index-before.html"
tar -czf "$backup/source-before.tar.gz" -C "$project" README.md frontend/src/yaoming frontend/dist/index.html
curl --fail --silent --show-error --max-time 15 http://127.0.0.1:5173/api/yaoming/rooms -o "$backup/rooms-before.json"
cp "$archive" "$backup/release.tar.gz"
tar -xzf "$archive" -C "$backup/stage" --no-same-owner
test "$(sha256sum "$backup/stage/frontend/dist/index.html" | cut -d' ' -f1)" = "$new_index"
rollback() {
  code=$?
  if [ "$code" -ne 0 ]; then
    install -m 644 "$backup/index-before.html" "$site/index.controls-rollback.next"
    mv -f "$site/index.controls-rollback.next" "$site/index.html"
    tar -xzf "$backup/source-before.tar.gz" -C "$project" --no-same-owner
    printf 'Previous entry/source restored. Backup: %s\n' "$backup" >&2
  fi
  exit "$code"
}
trap rollback EXIT
for destination in "$site/assets" "$project/frontend/dist/assets"; do
  for asset in index-tMcLslvO.js index-BjeYD7i5.css; do
    if [ -e "$destination/$asset" ]; then
      cmp "$backup/stage/frontend/dist/assets/$asset" "$destination/$asset"
    else
      install -m 644 "$backup/stage/frontend/dist/assets/$asset" "$destination/$asset"
    fi
  done
done
for path in README.md frontend/src/yaoming/TableView.vue frontend/src/yaoming/useTableKeyboard.ts frontend/src/yaoming/table-keyboard.test.ts frontend/src/yaoming/playability.test.ts frontend/src/yaoming/draw-selection.test.ts docs/DRAW_SELECTION_SPACE_PLAN.md docs/DRAW_SELECTION_SPACE_TEST_REPORT.md scripts/deploy-draw-selection-space.sh frontend/dist/index.html; do
  test -d "$(dirname "$project/$path")"
  install -m 644 "$backup/stage/$path" "$project/$path.controls-next"
  mv -f "$project/$path.controls-next" "$project/$path"
done
install -m 644 "$backup/stage/frontend/dist/index.html" "$site/index.controls-next"
mv -f "$site/index.controls-next" "$site/index.html"
curl --fail --silent --show-error --max-time 15 http://127.0.0.1:5173/ -o "$backup/served-index.html"
test "$(sha256sum "$backup/served-index.html" | cut -d' ' -f1)" = "$new_index"
test "$(systemctl show mahjong-backend -p MainPID --value)" = "$before_pid"
test "$(sha256sum "$project/backend/target/mahjong-server-0.1.0.jar" | cut -d' ' -f1)" = "$before_jar"
systemctl is-active --quiet mahjong-backend nginx frpc_tencent
curl --fail --silent --show-error --max-time 15 http://127.0.0.1:5173/api/yaoming/rooms -o "$backup/rooms-after.json"
printf 'Frontend deployed; backend PID unchanged: %s; backup: %s\n' "$before_pid" "$backup"
sha256sum "$site/index.html" "$site/assets/index-tMcLslvO.js" "$site/assets/index-BjeYD7i5.css"
trap - EXIT





