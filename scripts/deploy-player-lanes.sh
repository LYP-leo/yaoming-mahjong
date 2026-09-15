#!/usr/bin/env bash
set -euo pipefail
umask 077
# Frontend-only deployment. Never stop a match or modify the backend/proxy.
project=/home/leo/mahjong_20260822
site=/var/www/mahjong-yaoming-20260907
backup="$project/backups/player-lanes-20260911"
archive=/tmp/mahjong-player-lanes-20260911.tar.gz
archive_sha=${1:?Expected archive SHA256}
new_index=fd0fe443b506f8ae762346485749e6b834cfa0b07393c44c9f8a57fa8dc752e6
test "$(readlink -f "$project")" = "$project"
test "$(readlink -f "$site")" = "$site"
test ! -e "$backup"
test "$(sha256sum "$archive" | cut -d' ' -f1)" = "$archive_sha"
test "$(sha256sum "$site/index.html" | cut -d' ' -f1)" = 74ff889919af47d0b2e3462488e4af82d76cead5b0e890d42acf95f1459c958a
python3 - "$archive" <<'PY'
import sys, tarfile
allowed = set(["frontend/src/yaoming/TableView.vue","frontend/src/yaoming/YaomingApp.vue","frontend/src/yaoming/PlayerLane.vue","frontend/src/yaoming/RoomSidebar.vue","frontend/src/yaoming/RiverDialog.vue","frontend/src/yaoming/player-lanes.css","frontend/src/yaoming/components.test.ts","frontend/src/yaoming/discard-kind.test.ts","frontend/src/yaoming/downstream-vector.test.ts","frontend/src/yaoming/playability.test.ts","frontend/src/yaoming/stable-layout.test.ts","frontend/src/yaoming/trustee-presentation.test.ts","frontend/src/yaoming/player-lane.test.ts","frontend/src/yaoming/room-sidebar.test.ts","frontend/src/yaoming/river-dialog.test.ts","frontend/src/yaoming/app-lanes.test.ts","frontend/src/yaoming/dev/StableTablePreview.vue","docs/PLAYER_LANES_LAYOUT_PLAN.md","docs/PLAYER_LANES_LAYOUT_TEST_REPORT.md","scripts/player-lanes-smoke.mjs","scripts/deploy-player-lanes.sh","frontend/dist/index.html","frontend/dist/assets/index-BRt-83RP.js","frontend/dist/assets/index-DH9u_reI.css","frontend/dist/assets/mahjong-graphic-atlas-C-odaEvj.svg"])
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
tar -czf "$backup/source-before.tar.gz" -C "$project" frontend/src/yaoming frontend/dist/index.html
curl --fail --silent --show-error --max-time 15 http://127.0.0.1:5173/api/yaoming/rooms -o "$backup/rooms-before.json"
cp "$archive" "$backup/release.tar.gz"
tar -xzf "$archive" -C "$backup/stage" --no-same-owner
test "$(sha256sum "$backup/stage/frontend/dist/index.html" | cut -d' ' -f1)" = "$new_index"
rollback() {
  code=$?
  if [ "$code" -ne 0 ]; then
    install -m 644 "$backup/index-before.html" "$site/index.lanes-rollback.next"
    mv -f "$site/index.lanes-rollback.next" "$site/index.html"
    tar -xzf "$backup/source-before.tar.gz" -C "$project" --no-same-owner
    printf 'Previous entry/source restored. Backup: %s\n' "$backup" >&2
  fi
  exit "$code"
}
trap rollback EXIT
for destination in "$site/assets" "$project/frontend/dist/assets"; do
  for asset in index-BRt-83RP.js index-DH9u_reI.css mahjong-graphic-atlas-C-odaEvj.svg; do
    if [ -e "$destination/$asset" ]; then
      cmp "$backup/stage/frontend/dist/assets/$asset" "$destination/$asset"
    else
      install -m 644 "$backup/stage/frontend/dist/assets/$asset" "$destination/$asset"
    fi
  done
done
for path in frontend/src/yaoming/TableView.vue frontend/src/yaoming/YaomingApp.vue frontend/src/yaoming/PlayerLane.vue frontend/src/yaoming/RoomSidebar.vue frontend/src/yaoming/RiverDialog.vue frontend/src/yaoming/player-lanes.css frontend/src/yaoming/components.test.ts frontend/src/yaoming/discard-kind.test.ts frontend/src/yaoming/downstream-vector.test.ts frontend/src/yaoming/playability.test.ts frontend/src/yaoming/stable-layout.test.ts frontend/src/yaoming/trustee-presentation.test.ts frontend/src/yaoming/player-lane.test.ts frontend/src/yaoming/room-sidebar.test.ts frontend/src/yaoming/river-dialog.test.ts frontend/src/yaoming/app-lanes.test.ts frontend/src/yaoming/dev/StableTablePreview.vue docs/PLAYER_LANES_LAYOUT_PLAN.md docs/PLAYER_LANES_LAYOUT_TEST_REPORT.md scripts/player-lanes-smoke.mjs scripts/deploy-player-lanes.sh frontend/dist/index.html; do
  test -d "$(dirname "$project/$path")"
  install -m 644 "$backup/stage/$path" "$project/$path.lanes-next"
  mv -f "$project/$path.lanes-next" "$project/$path"
done
install -m 644 "$backup/stage/frontend/dist/index.html" "$site/index.lanes-next"
mv -f "$site/index.lanes-next" "$site/index.html"
curl --fail --silent --show-error --max-time 15 http://127.0.0.1:5173/ -o "$backup/served-index.html"
test "$(sha256sum "$backup/served-index.html" | cut -d' ' -f1)" = "$new_index"
test "$(systemctl show mahjong-backend -p MainPID --value)" = "$before_pid"
test "$(sha256sum "$project/backend/target/mahjong-server-0.1.0.jar" | cut -d' ' -f1)" = "$before_jar"
systemctl is-active --quiet mahjong-backend nginx frpc_tencent
curl --fail --silent --show-error --max-time 15 http://127.0.0.1:5173/api/yaoming/rooms -o "$backup/rooms-after.json"
printf 'Frontend deployed; backend PID unchanged: %s; backup: %s\n' "$before_pid" "$backup"
sha256sum "$site/index.html" "$site/assets/index-BRt-83RP.js" "$site/assets/index-DH9u_reI.css"
trap - EXIT
