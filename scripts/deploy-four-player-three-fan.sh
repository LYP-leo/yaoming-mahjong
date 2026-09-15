#!/usr/bin/env bash
set -euo pipefail

# Deploy only the already-tested application to the existing SSH/nginx/FRP host.
project=/home/leo/mahjong_20260822
site=/var/www/mahjong-yaoming-20260907
backup="$project/backups/four-player-three-fan-20260912"
archive=/tmp/mahjong-four-player-three-fan-20260912.tar.gz
jar=/tmp/mahjong-four-player-three-fan-20260912.jar
jar_hash=${1:?tested JAR SHA256 required}
index_hash=${2:?tested index SHA256 required}
archive_hash=${3:?source archive SHA256 required}
test "$(id -u)" = 0
[[ "$jar_hash" =~ ^[0-9a-f]{64}$ && "$index_hash" =~ ^[0-9a-f]{64}$ && "$archive_hash" =~ ^[0-9a-f]{64}$ ]]
test "$(readlink -f "$project")" = /home/leo/mahjong_20260822
test "$(readlink -f "$site")" = /var/www/mahjong-yaoming-20260907
test "$(find /etc/nginx/sites-enabled -maxdepth 1 -type l -printf '%f\n')" = mahjong
test "$(readlink -f /etc/nginx/sites-enabled/mahjong)" = /etc/nginx/sites-available/mahjong
test ! -e "$backup"
test "$(sha256sum "$jar" | cut -d' ' -f1)" = "$jar_hash"
test "$(sha256sum "$archive" | cut -d' ' -f1)" = "$archive_hash"
test "$(sha256sum "$project/backend/target/mahjong-server-0.1.0.jar" | cut -d' ' -f1)" = b2a6e3a9f922ff38c3f6e16c5fbc58c82b7a5b9e03b5ab4eee29e592c0caebca
test "$(sha256sum "$site/index.html" | cut -d' ' -f1)" = 3f54c3fdcebc71ab9ac7f6f0a61df599c3b6c3412bf036acb78aec380a5a5a1f
python3 - "$archive" <<'PY'
import sys, tarfile
from pathlib import PurePosixPath
prefixes = ('frontend/src/', 'frontend/dist/', 'backend/src/', 'docs/', 'scripts/')
files = {'backend/pom.xml', 'frontend/index.html', 'README.md'}
dirs = {'frontend', 'frontend/src', 'frontend/dist', 'backend', 'backend/src', 'docs', 'scripts'}
seen = set()
with tarfile.open(sys.argv[1], 'r:gz') as archive:
    for member in archive.getmembers():
        name = member.name.rstrip('/')
        path = PurePosixPath(name)
        if not name or path.is_absolute() or '..' in path.parts or '\\' in name or name in seen:
            raise SystemExit('Unsafe or duplicate archive path')
        seen.add(name)
        if not (member.isfile() or member.isdir()):
            raise SystemExit('Archive links and special entries forbidden')
        if not (name in files or name.startswith(prefixes) or (member.isdir() and name in dirs)):
            raise SystemExit('Archive member outside source/static whitelist')
PY
check_rooms() {
  node --input-type=module -e "const r=await fetch('http://127.0.0.1:8080/api/yaoming/rooms');if(!r.ok)throw Error('room check failed');const a=await r.json();if(a.some(x=>!['WAITING','MATCH_END'].includes(x.status)))throw Error('A game is now active; stop and reassess release');console.log('Safe restart: '+a.length+' inactive rooms');"
}
check_rooms
umask 077
mkdir -p "$backup/stage"
cp "$archive" "$backup/source-current.tar.gz"
tar -xzf "$archive" -C "$backup/stage" --no-same-owner
test "$(sha256sum "$backup/stage/frontend/dist/index.html" | cut -d' ' -f1)" = "$index_hash"
tar -czf "$backup/source-before.tar.gz" -C "$project" frontend/src frontend/dist frontend/index.html backend/src backend/pom.xml README.md docs scripts
tar -czf "$backup/public-before.tar.gz" -C "$site" .
cp "$project/backend/target/mahjong-server-0.1.0.jar" "$backup/backend-before.jar"
python3 - "$backup/stage" "$project" "$backup/new-paths.txt" <<'PY'
import sys
from pathlib import Path
stage, project, output = map(Path, sys.argv[1:])
new = [str(p.relative_to(stage)) for p in stage.rglob('*') if p.is_file() and not (project / p.relative_to(stage)).exists()]
output.write_text('\n'.join(new), encoding='utf8')
PY
data_saved=false
rollback() {
  code=$?
  trap - ERR
  systemctl stop nginx mahjong-backend || { printf 'Cannot stop writers; recovery: %s\n' "$backup" >&2; exit "$code"; }
  test "$(systemctl show mahjong-backend -p MainPID --value)" = 0 || exit "$code"
  test "$(systemctl show nginx -p MainPID --value)" = 0 || exit "$code"
  cp "$backup/backend-before.jar" "$project/backend/target/mahjong-server-0.1.0.jar"
  if test "$data_saved" = true; then tar -xzf "$backup/stopped-data-before.tar.gz" -C "$project"; fi
  # Keep newly introduced source files recoverable, but out of a rolled-back build.
  while IFS= read -r name || test -n "$name"; do
    test -n "$name" || continue
    if test -f "$project/$name"; then
      mkdir -p "$backup/new-files-rollback/$(dirname "$name")"
      mv "$project/$name" "$backup/new-files-rollback/$name"
    fi
  done < "$backup/new-paths.txt"
  tar -xzf "$backup/source-before.tar.gz" -C "$project"
  tar -xzf "$backup/public-before.tar.gz" -C "$site"
  systemctl start mahjong-backend
  systemctl start nginx
  exit "$code"
}
trap rollback ERR
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
chown leo:smbshare "$project/backend/pom.xml" "$project/frontend/index.html" "$project/README.md"
install -o leo -g smbshare -m 660 "$jar" "$project/backend/target/mahjong-server-0.1.0.jar.next"
mv -f "$project/backend/target/mahjong-server-0.1.0.jar.next" "$project/backend/target/mahjong-server-0.1.0.jar"
cmp "$backup/yaoming-before.json" "$project/backend/data/yaoming-rooms.json"
systemctl start mahjong-backend
ready=false
for attempt in {1..30}; do
  if curl -fsS http://127.0.0.1:8080/api/yaoming/rulesets -o /dev/null 2>/dev/null; then ready=true; break; fi
  sleep 1
done
test "$ready" = true
node --input-type=module - "$backup/yaoming-before.json" "$project/backend/data/yaoming-rooms.json" <<'JS'
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';
const before = JSON.parse(readFileSync(process.argv[2])), after = JSON.parse(readFileSync(process.argv[3]));
const oldRooms = Array.isArray(before) ? before : before.rooms;
const newRooms = Array.isArray(after) ? after : after.rooms;
assert.deepEqual(newRooms.map(r => r.id), oldRooms.map(r => r.id));
for (const old of oldRooms) {
  const now = newRooms.find(r => r.id === old.id);
  assert.equal(now.ruleId || 'yaoming-3p', old.ruleId || 'yaoming-3p');
  assert.equal(now.round, old.round); assert.equal(now.phase, old.phase);
  assert.deepEqual(now.result, old.result); assert.deepEqual(now.wall, old.wall);
  for (const player of old.players) {
    const current = now.players.find(p => p.id === player.id);
    assert.ok(current); assert.equal(current.score, player.score); assert.deepEqual(current.hand, player.hand);
    assert.deepEqual(current.melds, player.melds); assert.deepEqual(current.discards, player.discards);
  }
}
// New metadata may be materialized, but every historical frame/result remains intact.
const strip = value => Array.isArray(value) ? value.map(strip) : value && typeof value === 'object'
  ? Object.fromEntries(Object.entries(value).filter(([key]) => !['ruleId','ruleName','capacity'].includes(key)).map(([key,v]) => [key,strip(v)])) : value;
for (const old of oldRooms) assert.deepEqual(strip(newRooms.find(r => r.id === old.id).replayHands), strip(old.replayHands));
assert.deepEqual(strip(after.replayArchives || []), strip(before.replayArchives || []));
const rules = await (await fetch('http://127.0.0.1:8080/api/yaoming/rulesets')).json();
assert.deepEqual(rules.map(r=>r.id).sort(), ['yaoming-3p','yaoming-4p']);
for (const size of [3,4]) {
  const rule=rules.find(r=>r.id===`yaoming-${size}p`);
  assert.equal(rule.playerCount,size); assert.equal(rule.tileCount,size===4?136:108); assert.equal(rule.totalRounds,size*2);
  assert.equal(rule.fans.find(f=>f.id==='MENQING').fan,size===4?1:2);
  assert.equal(rule.fans.some(f=>f.id==='FENGLONG'),size===3);
  assert.equal(rule.tiles.length,size===4?34:27);
  assert.ok(rule.description.includes(`${size===4?3:4}番起和`));
}
const lobby=await (await fetch('http://127.0.0.1:8080/api/yaoming/rooms')).json();
assert.equal(lobby.length,oldRooms.length);
for(const room of lobby){const old=oldRooms.find(r=>r.id===room.id);assert.ok(old);assert.equal(room.ruleId,old.ruleId||'yaoming-3p');assert.equal(room.capacity,room.ruleId==='yaoming-4p'?4:3);}
console.log(JSON.stringify({ restoredRooms:oldRooms.length,historicalResultsAndReplaysUnchanged:true,bothRulesAvailable:true }));
JS
cp -a "$backup/stage/frontend/dist/assets/." "$site/assets/"
find "$site/assets" -maxdepth 1 -type f -exec chmod 644 {} +
cp "$backup/stage/frontend/dist/index.html" "$site/index.four-player.next"
chmod 644 "$site/index.four-player.next"
mv -f "$site/index.four-player.next" "$site/index.html"
systemctl start nginx
curl -fsS http://127.0.0.1:5173/ -o "$backup/public-index-after.html"
test "$(sha256sum "$backup/public-index-after.html" | cut -d' ' -f1)" = "$index_hash"
curl -fsS http://127.0.0.1:5173/api/yaoming/rulesets -o /dev/null
systemctl is-active mahjong-backend nginx frpc_tencent
trap - ERR
printf 'Published four-player three-fan update. Backup: %s\n' "$backup"
