#!/usr/bin/env bash
# One-time guarded deployment for the existing dell host. Run as root after staging/tests.
set -euo pipefail
root=/home/leo/mahjong_20260822
backup="$root/backups/smart-bots-20260909"
stage="$backup/build"
cd "$root"
test "$(id -u)" = 0
test -f "$stage/backend/target/mahjong-server-0.1.0.jar"
test ! -e "$backup/stopped-before.tar.gz"
node --input-type=module -e "const r=await fetch('http://127.0.0.1:5173/api/yaoming/rooms');if(!r.ok)throw Error('room check failed');const rooms=await r.json();if(rooms.some(room=>room.status!=='MATCH_END'))throw Error('unfinished room: postpone restart');console.log('All '+rooms.length+' rooms are finished; safe restart guard passed');"
before=$(curl -fsS http://127.0.0.1:5173/api/yaoming/rooms)
systemctl stop mahjong-backend
trap 'systemctl start mahjong-backend' EXIT
test "$(systemctl show mahjong-backend -p MainPID --value)" = 0
umask 077
tar -czf "$backup/stopped-before.tar.gz" backend/data backend/target/mahjong-server-0.1.0.jar
sha256sum backend/data/yaoming-rooms.json
for file in src/main/java/com/mahjong/yaoming/YmBots.java src/main/java/com/mahjong/yaoming/YmShanten.java src/main/java/com/mahjong/yaoming/YmBotObservation.java src/main/java/com/mahjong/yaoming/YmEngine.java src/main/java/com/mahjong/yaoming/YmService.java src/test/java/com/mahjong/yaoming/YmBotMatchTest.java src/test/java/com/mahjong/yaoming/YmShantenTest.java src/test/java/com/mahjong/yaoming/YmBotObservationTest.java src/test/java/com/mahjong/yaoming/YmBotsTest.java src/test/java/com/mahjong/yaoming/YmBotIntegrationTest.java src/test/java/com/mahjong/yaoming/YmEngineTest.java src/test/java/com/mahjong/yaoming/YmReplayTest.java; do
  install -o leo -g smbshare -m 660 "$stage/backend/$file" "$root/backend/$file"
done
install -o leo -g smbshare -m 660 "$stage/backend/target/mahjong-server-0.1.0.jar" backend/target/mahjong-server-0.1.0.jar.smart-new
mv backend/target/mahjong-server-0.1.0.jar.smart-new backend/target/mahjong-server-0.1.0.jar
sha256sum backend/data/yaoming-rooms.json backend/target/mahjong-server-0.1.0.jar
systemctl start mahjong-backend
trap - EXIT
for attempt in {1..30}; do
  if curl -fsS http://127.0.0.1:8080/api/yaoming/rules -o /dev/null; then break; fi
  sleep 1
done
after=$(curl -fsS http://127.0.0.1:5173/api/yaoming/rooms)
test "$before" = "$after"
systemctl show mahjong-backend -p ActiveState -p SubState -p MainPID -p NRestarts -p ActiveEnterTimestamp
echo 'Backend updated; room summaries preserved; frontend and network configuration untouched.'
