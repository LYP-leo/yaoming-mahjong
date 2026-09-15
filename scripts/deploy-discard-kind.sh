#!/usr/bin/env bash
set -euo pipefail

project=/home/leo/mahjong_20260822
site=/var/www/mahjong-yaoming-20260907
backup="$project/backups/discard-kind-20260909"
archive=/tmp/mahjong-discard-kind-20260909.tar.gz
jar=/tmp/mahjong-discard-kind-20260909.jar
jar_hash=${1:?tested JAR SHA256 required}
index_hash=${2:?tested index SHA256 required}
test "$(id -u)" = 0
[[ "$jar_hash" =~ ^[0-9a-f]{64}$ && "$index_hash" =~ ^[0-9a-f]{64}$ ]]
test "$(readlink -f "$project")" = /home/leo/mahjong_20260822
test "$(readlink -f "$site")" = /var/www/mahjong-yaoming-20260907
test ! -e "$backup"
test "$(sha256sum "$jar" | cut -d' ' -f1)" = "$jar_hash"
test "$(sha256sum "$project/backend/target/mahjong-server-0.1.0.jar" | cut -d' ' -f1)" = 4ccd814ddce7260c67fcbd41e9e4bec85db373505521caedd1c779030e4ca360
test "$(sha256sum "$site/index.html" | cut -d' ' -f1)" = 248845edc9705ee14ab5a39ebd5b0fe86a6085a9b9414d7ebdeded51efff7b43
check_rooms() {
  node --input-type=module -e "const r=await fetch('http://127.0.0.1:8080/api/yaoming/rooms');if(!r.ok)throw Error('room check failed');const rooms=await r.json();if(rooms.some(x=>!['WAITING','MATCH_END'].includes(x.status)))throw Error('game in progress: postpone restart');console.log('Safe restart: '+rooms.length+' waiting/finished rooms');"
}
check_rooms
umask 077
mkdir -p "$backup/stage"
cp "$archive" "$backup/source-current.tar.gz"
tar -xzf "$archive" -C "$backup/stage" --no-same-owner
test "$(sha256sum "$backup/stage/frontend/dist/index.html" | cut -d' ' -f1)" = "$index_hash"
tar -czf "$backup/source-before.tar.gz" -C "$project" frontend/src frontend/dist backend/src backend/pom.xml README.md docs scripts
tar -czf "$backup/public-before.tar.gz" -C "$site" .
# Recheck immediately before stopping; a waiting lobby is durable, a live hand is protected.
check_rooms
before=$(curl -fsS http://127.0.0.1:8080/api/yaoming/rooms)
systemctl stop mahjong-backend
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
cp "$backup/stage/frontend/dist/index.html" "$site/index.discard-kind.next"
chmod 644 "$site/index.discard-kind.next"
mv -f "$site/index.discard-kind.next" "$site/index.html"
test "$data_before" = "$(sha256sum "$project/backend/data/yaoming-rooms.json" | cut -d' ' -f1)"
systemctl start mahjong-backend
ready=false
for attempt in {1..30}; do
  if curl -fsS http://127.0.0.1:8080/api/yaoming/rules -o /dev/null 2>/dev/null; then ready=true; break; fi
  sleep 1
done
test "$ready" = true
after=$(curl -fsS http://127.0.0.1:8080/api/yaoming/rooms)
test "$before" = "$after"
test "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/api/rooms)" = 404
trap - ERR
sha256sum "$site/index.html" "$project/backend/target/mahjong-server-0.1.0.jar"
systemctl show mahjong-backend -p MainPID -p ActiveState -p SubState -p NRestarts
printf 'Discard kinds released. Recovery: %s\n' "$backup"
