#!/usr/bin/env bash
set -euo pipefail

project=/home/leo/mahjong_20260822
site=/var/www/mahjong-yaoming-20260907
backup="$project/backups/nine-ranks-no-honors-20260909"
archive=/tmp/mahjong-nine-ranks-no-honors-20260909.tar.gz
jar=/tmp/mahjong-nine-ranks-no-honors-20260909.jar
jar_hash=${1:?tested JAR SHA256 required}
index_hash=${2:?tested index SHA256 required}
archive_hash=${3:?validated source archive SHA256 required}
restart_mode=${4:-protect-active-games}
[[ "$restart_mode" = protect-active-games || "$restart_mode" = --allow-active-games ]]
test "$(id -u)" = 0
[[ "$jar_hash" =~ ^[0-9a-f]{64}$ && "$index_hash" =~ ^[0-9a-f]{64}$ && "$archive_hash" =~ ^[0-9a-f]{64}$ ]]
test "$(readlink -f "$project")" = /home/leo/mahjong_20260822
test "$(readlink -f "$site")" = /var/www/mahjong-yaoming-20260907
test ! -e "$backup"
test "$(sha256sum "$jar" | cut -d' ' -f1)" = "$jar_hash"
test "$(sha256sum "$archive" | cut -d' ' -f1)" = "$archive_hash"
# Validate every archive member before either extraction. Runtime data and JARs
# are never source-archive entries; symlinks/hardlinks cannot escape the target.
python3 - "$archive" <<'PY'
import sys, tarfile
from pathlib import PurePosixPath
prefixes = ('frontend/src/', 'frontend/dist/', 'backend/src/', 'docs/', 'scripts/')
files = {'backend/pom.xml', 'README.md'}
dirs = {'frontend', 'frontend/src', 'frontend/dist', 'backend', 'backend/src', 'docs', 'scripts'}
with tarfile.open(sys.argv[1], 'r:gz') as archive:
    for member in archive.getmembers():
        name = member.name.rstrip('/')
        path = PurePosixPath(name)
        if not name or path.is_absolute() or '..' in path.parts or '\\' in name:
            raise SystemExit('Unsafe archive path')
        if not (member.isfile() or member.isdir()):
            raise SystemExit('Archive links and special entries are forbidden')
        if not (name in files or name.startswith(prefixes) or (member.isdir() and name in dirs)):
            raise SystemExit('Archive entry is outside the source/static whitelist')
PY
test "$(sha256sum "$project/backend/target/mahjong-server-0.1.0.jar" | cut -d' ' -f1)" = 64164f6b4ea83cdcd13494ed07cc80b4a89435ca0b0aa7158d8fc95e98c38117
test "$(sha256sum "$site/index.html" | cut -d' ' -f1)" = 25ac667c78d8066ce34d38af3335a9d71e962c3d1069ec57392693a751ce7e51
check_rooms() {
  RESTART_MODE="$restart_mode" node --input-type=module -e "const r=await fetch('http://127.0.0.1:8080/api/yaoming/rooms');if(!r.ok)throw Error('room check failed');const rooms=await r.json();const active=rooms.filter(x=>!['WAITING','MATCH_END'].includes(x.status));if(active.length && process.env.RESTART_MODE!=='--allow-active-games')throw Error('game in progress: postpone restart');console.log('Restart check: '+rooms.length+' rooms, '+active.length+' active; mode='+process.env.RESTART_MODE);"
}
check_rooms
umask 077
mkdir -p "$backup/stage"
cp "$archive" "$backup/source-current.tar.gz"
tar -xzf "$archive" -C "$backup/stage" --no-same-owner
test "$(sha256sum "$backup/stage/frontend/dist/index.html" | cut -d' ' -f1)" = "$index_hash"
tar -czf "$backup/source-before.tar.gz" -C "$project" frontend/src frontend/dist backend/src backend/pom.xml README.md docs scripts
tar -czf "$backup/public-before.tar.gz" -C "$site" .
# Default protects live games; this turn's explicit flag authorizes interruption.
check_rooms
before=$(curl -fsS http://127.0.0.1:8080/api/yaoming/rooms)
rollback() {
  code=$?
  trap - ERR
  systemctl stop mahjong-backend || true
  if test -d "$backup/target-before"; then
    if test -d "$project/backend/target"; then mv "$project/backend/target" "$backup/target-failed"; fi
    mv "$backup/target-before" "$project/backend/target"
  fi
  tar -xzf "$backup/source-before.tar.gz" -C "$project"
  tar -xzf "$backup/public-before.tar.gz" -C "$site"
  systemctl start mahjong-backend
  exit "$code"
}
trap rollback ERR
systemctl stop mahjong-backend
test "$(systemctl show mahjong-backend -p MainPID --value)" = 0
tar -czf "$backup/stopped-data-before.tar.gz" -C "$project" backend/data
data_before=$(sha256sum "$project/backend/data/yaoming-rooms.json" | cut -d' ' -f1)
mv "$project/backend/target" "$backup/target-before"
mv "$project/frontend/dist" "$backup/dist-before"
mkdir "$project/backend/target"
chown leo:smbshare "$project/backend/target"
tar -xzf "$archive" -C "$project" --no-same-owner
install -o leo -g smbshare -m 660 "$jar" "$project/backend/target/mahjong-server-0.1.0.jar"
chown -R leo:smbshare "$project/frontend/src" "$project/frontend/dist" "$project/backend/src" "$project/docs" "$project/scripts"
chown leo:smbshare "$project/backend/pom.xml" "$project/README.md"
cp -a "$backup/stage/frontend/dist/assets/." "$site/assets/"
find "$site/assets" -maxdepth 1 -type f -exec chmod 644 {} +
cp "$backup/stage/frontend/dist/index.html" "$site/index.nine-ranks-no-honors.next"
chmod 644 "$site/index.nine-ranks-no-honors.next"
mv -f "$site/index.nine-ranks-no-honors.next" "$site/index.html"
test "$data_before" = "$(sha256sum "$project/backend/data/yaoming-rooms.json" | cut -d' ' -f1)"
systemctl start mahjong-backend
ready=false
for attempt in {1..30}; do
  if curl -fsS http://127.0.0.1:8080/api/yaoming/rules -o /dev/null 2>/dev/null; then ready=true; break; fi
  sleep 1
done
test "$ready" = true
after=$(curl -fsS http://127.0.0.1:8080/api/yaoming/rooms)
if test "$restart_mode" = protect-active-games; then
  test "$before" = "$after"
else
  # Existing deadlines and robot actions can advance a restored active game.
  # The exact snapshot was backed up and verified unchanged before startup.
  printf 'Authorized active-game restart: before=%s\nafter=%s\n' "$before" "$after"
fi
node --input-type=module -e "const r=await fetch('http://127.0.0.1:8080/api/yaoming/rules');if(!r.ok)throw Error('rules check failed');const v=await r.json();if(!v.notes.some(n=>n.includes('打过或放过同种牌仍可点和')))throw Error('new rule missing');if(v.notes.some(n=>n.includes('下一次摸牌前不能点和')))throw Error('old restriction remains');const f=v.fans.find(f=>f.id==='JIUSHUQI');if(!f || f.fan!==4 || !f.description.includes('字牌'))throw Error('nine ranks rule missing');console.log('Nine ranks excludes honors; no-furiten preserved');"
test "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/api/rooms)" = 404
trap - ERR
sha256sum "$site/index.html" "$project/backend/target/mahjong-server-0.1.0.jar"
systemctl show mahjong-backend -p MainPID -p ActiveState -p SubState -p NRestarts
printf 'Nine ranks correction released. Recovery: %s\n' "$backup"
