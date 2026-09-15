#!/usr/bin/env bash
set -euo pipefail

# Update the existing SSH/nginx deployment; no new hosting or network exposure.
project=/home/leo/mahjong_20260822
site=/var/www/mahjong-yaoming-20260907
backup="$project/backups/rulebook2-20260911"
archive=/tmp/mahjong-rulebook2-20260911.tar.gz
jar=/tmp/mahjong-rulebook2-20260911.jar
jar_hash=${1:?tested JAR SHA256 required}
index_hash=${2:?tested index SHA256 required}
archive_hash=${3:?source archive SHA256 required}
test "$(id -u)" = 0
[[ "$jar_hash" =~ ^[0-9a-f]{64}$ && "$index_hash" =~ ^[0-9a-f]{64}$ && "$archive_hash" =~ ^[0-9a-f]{64}$ ]]
test "$(readlink -f "$project")" = /home/leo/mahjong_20260822
test "$(readlink -f "$site")" = /var/www/mahjong-yaoming-20260907
# This host's nginx currently serves only this Mahjong site. Refuse a wider outage.
test "$(find /etc/nginx/sites-enabled -maxdepth 1 -type l -printf '%f\n')" = mahjong
test "$(readlink -f /etc/nginx/sites-enabled/mahjong)" = /etc/nginx/sites-available/mahjong
test ! -e "$backup"
test "$(sha256sum "$jar" | cut -d' ' -f1)" = "$jar_hash"
test "$(sha256sum "$archive" | cut -d' ' -f1)" = "$archive_hash"
test "$(sha256sum "$project/backend/target/mahjong-server-0.1.0.jar" | cut -d' ' -f1)" = ae73ccbe7349b536057ab515d7fd770cd58f5e0992e14b1a63d4a7b634333cfc
test "$(sha256sum "$site/index.html" | cut -d' ' -f1)" = 5b23389dc7b12a15ca3797fc4b549d1cae61f68e8ab67c586cef04fe7869c1bd
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
            raise SystemExit('Archive links and special entries forbidden')
        if not (name in files or name.startswith(prefixes) or (member.isdir() and name in dirs)):
            raise SystemExit('Archive member outside source/static whitelist')
PY
check_rooms() {
  node --input-type=module -e "const r=await fetch('http://127.0.0.1:8080/api/yaoming/rooms');if(!r.ok)throw Error('room check failed');const a=await r.json();if(a.some(x=>!['WAITING','MATCH_END'].includes(x.status)))throw Error('Active game: postpone deployment');console.log('Safe restart: '+a.length+' inactive rooms');"
}
check_rooms
umask 077
mkdir -p "$backup/stage"
cp "$archive" "$backup/source-current.tar.gz"
tar -xzf "$archive" -C "$backup/stage" --no-same-owner
test "$(sha256sum "$backup/stage/frontend/dist/index.html" | cut -d' ' -f1)" = "$index_hash"
tar -czf "$backup/source-before.tar.gz" -C "$project" frontend/src frontend/dist backend/src backend/pom.xml README.md docs scripts
tar -czf "$backup/public-before.tar.gz" -C "$site" .
cp "$project/backend/target/mahjong-server-0.1.0.jar" "$backup/backend-before.jar"
check_rooms
data_saved=false
rollback() {
  code=$?
  trap - ERR
  if ! systemctl stop nginx mahjong-backend; then
    printf 'Rollback paused: cannot safely stop writers. Recovery: %s\n' "$backup" >&2
    exit "$code"
  fi
  if test "$(systemctl show mahjong-backend -p MainPID --value)" != 0 || test "$(systemctl show nginx -p MainPID --value)" != 0; then
    printf 'Rollback paused: service still running. Recovery: %s\n' "$backup" >&2
    exit "$code"
  fi
  cp "$backup/backend-before.jar" "$project/backend/target/mahjong-server-0.1.0.jar"
  # Old code cannot be assumed to accept the new snapshot fingerprint field.
  if test "$data_saved" = true; then
    tar -xzf "$backup/stopped-data-before.tar.gz" -C "$project"
  fi
  tar -xzf "$backup/source-before.tar.gz" -C "$project"
  tar -xzf "$backup/public-before.tar.gz" -C "$site"
  systemctl start mahjong-backend
  systemctl start nginx
  exit "$code"
}
trap rollback ERR
# A short frontend maintenance window prevents new requests during migration or
# rollback. The backend binds only loopback; health checks below stay available.
systemctl stop nginx
test "$(systemctl show nginx -p MainPID --value)" = 0
check_rooms
systemctl stop mahjong-backend
test "$(systemctl show mahjong-backend -p MainPID --value)" = 0
tar -czf "$backup/stopped-data-before.tar.gz" -C "$project" backend/data
cp "$project/backend/data/yaoming-rooms.json" "$backup/yaoming-before.json"
data_saved=true
tar -xzf "$archive" -C "$project" --no-same-owner
chown -R leo:smbshare "$project/frontend/src" "$project/frontend/dist" "$project/backend/src" "$project/docs" "$project/scripts"
chown leo:smbshare "$project/backend/pom.xml" "$project/README.md"
install -o leo -g smbshare -m 660 "$jar" "$project/backend/target/mahjong-server-0.1.0.jar.next"
mv -f "$project/backend/target/mahjong-server-0.1.0.jar.next" "$project/backend/target/mahjong-server-0.1.0.jar"
cmp "$backup/yaoming-before.json" "$project/backend/data/yaoming-rooms.json"
systemctl start mahjong-backend
ready=false
for attempt in {1..30}; do
  if curl -fsS http://127.0.0.1:8080/api/yaoming/rules -o /dev/null 2>/dev/null; then ready=true; break; fi
  sleep 1
done
test "$ready" = true
node --input-type=module - "$backup/yaoming-before.json" "$project/backend/data/yaoming-rooms.json" <<'JS'
import {readFileSync} from 'node:fs';
import assert from 'node:assert/strict';
const before=JSON.parse(readFileSync(process.argv[2])), after=JSON.parse(readFileSync(process.argv[3]));
const oldRooms=Array.isArray(before)?before:before.rooms;
assert.equal(after.rulebookSha256,'035c0d8708dec106c15bdc56604815d458bcc08a0ad17b052dce04fb50c7711c');
assert.deepEqual(after.rooms.map(r=>r.id),oldRooms.map(r=>r.id));
for(const old of oldRooms){
  const now=after.rooms.find(r=>r.id===old.id);
  assert.equal(now.version,old.version+1);
  assert.deepEqual(now.result,old.result);
  assert.deepEqual(now.replayHands,old.replayHands);
  assert.deepEqual(now.wall,old.wall);
  for(const p of old.players){const n=now.players.find(q=>q.id===p.id);assert.deepEqual(n.hand,p.hand);assert.equal(n.score,p.score);}
}
assert.deepEqual(after.replayArchives,before.replayArchives||[]);
const r=await fetch('http://127.0.0.1:8080/api/yaoming/rules');assert.ok(r.ok);
const v=await r.json(), fans=Object.fromEntries(v.fans.map(f=>[f.id,f.fan]));
assert.equal(v.fans.length,20);assert.equal(fans.PINGHE,1);assert.equal(fans.MENQING,2);
assert.equal(fans.QINGQUANDAIYAO,3);assert.ok(!('DUANYAO' in fans));
assert.ok(v.fans.find(f=>f.id==='QINGYISE').description.includes('不含字牌'));
assert.ok(v.notes.some(n=>n.includes('打过或放过同种牌仍可点和')));
console.log(JSON.stringify({rulesVerified:true,restoredRooms:oldRooms.length,historicalResultsAndReplaysUnchanged:true}));
JS
cp -a "$backup/stage/frontend/dist/assets/." "$site/assets/"
find "$site/assets" -maxdepth 1 -type f -exec chmod 644 {} +
cp "$backup/stage/frontend/dist/index.html" "$site/index.rulebook2.next"
chmod 644 "$site/index.rulebook2.next"
mv -f "$site/index.rulebook2.next" "$site/index.html"
test "$(sha256sum "$site/index.html" | cut -d' ' -f1)" = "$index_hash"
test "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/api/rooms)" = 404
nginx -t
systemctl start nginx
trap - ERR
sha256sum "$site/index.html" "$project/backend/target/mahjong-server-0.1.0.jar"
systemctl show mahjong-backend -p MainPID -p ActiveState -p SubState -p NRestarts
printf 'Rulebook 2 released. Recovery: %s\n' "$backup"
