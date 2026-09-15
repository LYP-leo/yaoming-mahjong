#!/usr/bin/env bash
set -euo pipefail

# Frontend-only update on the existing host. Never stop services or touch game data.
project=/home/leo/mahjong_20260822
site=/var/www/mahjong-yaoming-20260907
backup="$project/backups/four-player-compact-20260912"
archive=/tmp/mahjong-four-player-compact-20260912.tar.gz
index_hash=${1:?validated index SHA256 required}
archive_hash=${2:?validated archive SHA256 required}
[[ "$index_hash" =~ ^[0-9a-f]{64}$ && "$archive_hash" =~ ^[0-9a-f]{64}$ ]]
test "$(readlink -f "$project")" = /home/leo/mahjong_20260822
test "$(readlink -f "$site")" = /var/www/mahjong-yaoming-20260907
test ! -e "$backup"
test "$(sha256sum "$archive" | cut -d' ' -f1)" = "$archive_hash"
test "$(sha256sum "$site/index.html" | cut -d' ' -f1)" = b936c8d3962efcdbc539821d050823af7c200a0204aa91b500498ddc72f7c8fa
jar="$project/backend/target/mahjong-server-0.1.0.jar"
jar_hash=53b1ef2fb5fd0b26d070a1f04bd333ae418805ab678d243ba1242f68a6e7e1bb
test "$(sha256sum "$jar" | cut -d' ' -f1)" = "$jar_hash"
backend_pid=$(systemctl show mahjong-backend -p MainPID --value)
test "$backend_pid" -gt 0
systemctl is-active mahjong-backend nginx frpc_tencent

python3 - "$archive" <<'PY'
import sys, tarfile
from pathlib import PurePosixPath
allowed = {
    'frontend/src/yaoming/stable-player-lanes.css',
    'frontend/src/yaoming/four-player-table.test.ts',
    'docs/FOUR_PLAYER_COMPACT_LAYOUT_PLAN.md',
    'docs/FOUR_PLAYER_COMPACT_LAYOUT_TEST_REPORT.md',
    'scripts/deploy-four-player-compact.sh',
}
seen = set()
with tarfile.open(sys.argv[1], 'r:gz') as archive:
    for member in archive.getmembers():
        name = member.name.rstrip('/')
        path = PurePosixPath(name)
        if not name or path.is_absolute() or '..' in path.parts or '\\' in name or name in seen:
            raise SystemExit('Unsafe or duplicate archive path')
        seen.add(name)
        if not (member.isfile() or member.isdir()):
            raise SystemExit('Links and special entries forbidden')
        if not (name in allowed or name == 'frontend/dist' or name.startswith('frontend/dist/')):
            raise SystemExit('Archive member outside frontend release scope')
PY
umask 022
mkdir -m 700 "$backup"
mkdir "$backup/stage"
tar -xzf "$archive" -C "$backup/stage" --no-same-owner
test "$(sha256sum "$backup/stage/frontend/dist/index.html" | cut -d' ' -f1)" = "$index_hash"
tar -czf "$backup/source-before.tar.gz" -C "$project" frontend/src/yaoming/stable-player-lanes.css frontend/src/yaoming/four-player-table.test.ts frontend/dist
tar -czf "$backup/public-before.tar.gz" -C "$site" .
cp "$site/index.html" "$backup/index-before.html"
rollback() {
  code=$?
  trap - ERR
  cp "$backup/index-before.html" "$site/index.compact-rollback.next"
  chmod 644 "$site/index.compact-rollback.next"
  mv -f "$site/index.compact-rollback.next" "$site/index.html"
  tar -xzf "$backup/source-before.tar.gz" -C "$project" --no-same-owner
  printf 'Frontend restored; unused hashed assets kept. Backup: %s\n' "$backup" >&2
  exit "$code"
}
trap rollback ERR
# Existing content-addressed resources may be reused, but never changed in place.
for asset in "$backup/stage/frontend/dist/assets/"*; do
  target="$site/assets/$(basename "$asset")"
  if test -e "$target"; then cmp "$asset" "$target"; else install -m 644 "$asset" "$target"; fi
done
tar -xzf "$archive" -C "$project" --no-same-owner
cp "$backup/stage/frontend/dist/index.html" "$site/index.compact.next"
chmod 644 "$site/index.compact.next"
mv -f "$site/index.compact.next" "$site/index.html"
curl -fsS http://127.0.0.1:5173/ -o "$backup/public-index-after.html"
test "$(sha256sum "$backup/public-index-after.html" | cut -d' ' -f1)" = "$index_hash"
curl -fsS http://127.0.0.1:5173/api/yaoming/rulesets -o /dev/null
test "$(sha256sum "$jar" | cut -d' ' -f1)" = "$jar_hash"
test "$(systemctl show mahjong-backend -p MainPID --value)" = "$backend_pid"
systemctl is-active mahjong-backend nginx frpc_tencent
trap - ERR
printf 'Frontend published without backend restart. PID: %s. Backup: %s\n' "$backend_pid" "$backup"
