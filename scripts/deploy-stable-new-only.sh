#!/usr/bin/env bash
set -euo pipefail

project=/home/leo/mahjong_20260822
site=/var/www/mahjong-yaoming-20260907
backup="$project/backups/stable-table-new-only-20260909"
archive=/tmp/mahjong-stable-new-only-20260909.tar.gz
jar=/tmp/mahjong-stable-new-only-20260909.jar
test "$(id -u)" = 0
test "$(readlink -f "$project")" = /home/leo/mahjong_20260822
test "$(readlink -f "$site")" = /var/www/mahjong-yaoming-20260907
test ! -e "$backup"
test "$(sha256sum "$jar" | cut -d' ' -f1)" = 4ccd814ddce7260c67fcbd41e9e4bec85db373505521caedd1c779030e4ca360
test "$(sha256sum "$site/index.html" | cut -d' ' -f1)" = b39bb2ca40568aaa8fe57930930cc667459ea8ab2b43d6fd4a2712a360d934c1
test "$(sha256sum /etc/nginx/sites-available/mahjong | cut -d' ' -f1)" = 42d2c4a501048928e3993e444fbcb1dd04368c06aadd7617a0aaec560fb52582
node --input-type=module -e "const r=await fetch('http://127.0.0.1:8080/api/yaoming/rooms');if(!r.ok)throw Error('room check failed');const rooms=await r.json();if(rooms.some(x=>x.status!=='MATCH_END'))throw Error('unfinished room: postpone restart');console.log('Safe restart: '+rooms.length+' finished rooms');"
before=$(curl -fsS http://127.0.0.1:8080/api/yaoming/rooms)
umask 077
mkdir -p "$backup/stage"
cp "$archive" "$backup/source-current.tar.gz"
tar -xzf "$archive" -C "$backup/stage" --no-same-owner
test "$(sha256sum "$backup/stage/frontend/dist/index.html" | cut -d' ' -f1)" = 248845edc9705ee14ab5a39ebd5b0fe86a6085a9b9414d7ebdeded51efff7b43
tar -czf "$backup/source-before.tar.gz" -C "$project" frontend/src frontend/dist frontend/package.json frontend/package-lock.json frontend/vite.config.ts backend/src backend/pom.xml README.md docs deploy
tar -czf "$backup/public-before.tar.gz" -C "$site" .
cp /etc/nginx/sites-available/mahjong "$backup/nginx-before.conf"
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
  cp "$backup/nginx-before.conf" /etc/nginx/sites-available/mahjong
  nginx -t && systemctl reload nginx || true
  systemctl start mahjong-backend
  exit "$code"
}
trap rollback ERR
test "$(systemctl show mahjong-backend -p MainPID --value)" = 0
tar -czf "$backup/stopped-data-before.tar.gz" -C "$project" backend/data
data_before=$(sha256sum "$project/backend/data/yaoming-rooms.json" | cut -d' ' -f1)
# Retire compiled classes as well: copying a new JAR alone leaves old Maven classes.
mv "$project/backend/target" "$backup/target-before"
mv "$project/frontend/dist" "$backup/dist-before"
mkdir "$project/backend/target"
chown leo:smbshare "$project/backend/target"
tar -xzf "$archive" -C "$project" --no-same-owner
while IFS= read -r relative; do
  relative=${relative%$'\r'}
  test -n "$relative" || continue
  case "$relative" in backend/src/*|frontend/src/*) ;; *) exit 1 ;; esac
  case "$relative" in *..*|/*) exit 1 ;; esac
  target="$project/$relative"
  test ! -L "$target"
  if test -f "$target"; then rm -- "$target"; fi
done < "$backup/stage/deploy/new-only-deleted-files.txt"
install -o leo -g smbshare -m 660 "$jar" "$project/backend/target/mahjong-server-0.1.0.jar"
chown -R leo:smbshare "$project/frontend/src" "$project/frontend/dist" "$project/backend/src" "$project/docs" "$project/deploy" "$project/scripts"
chown leo:smbshare "$project/frontend/package.json" "$project/frontend/package-lock.json" "$project/frontend/vite.config.ts" "$project/frontend/hints-preview.html" "$project/frontend/stable-table-preview.html" "$project/frontend/tile-art-preview.html" "$project/backend/pom.xml" "$project/README.md"
cp -a "$backup/stage/frontend/dist/assets/." "$site/assets/"
chmod 755 "$site/assets"
find "$site/assets" -maxdepth 1 -type f -exec chmod 644 {} +
cp "$backup/stage/frontend/dist/index.html" "$site/index.stable-new-only.next"
chmod 644 "$site/index.stable-new-only.next"
mv -f "$site/index.stable-new-only.next" "$site/index.html"
install -m 644 "$backup/stage/deploy/nginx-mahjong.conf" /etc/nginx/sites-available/mahjong
nginx -t
systemctl reload nginx
test "$data_before" = "$(sha256sum "$project/backend/data/yaoming-rooms.json" | cut -d' ' -f1)"
systemctl start mahjong-backend
ready=false
for attempt in {1..30}; do
  if curl -fsS http://127.0.0.1:8080/api/yaoming/rules -o /dev/null; then ready=true; break; fi
  sleep 1
done
test "$ready" = true
after=$(curl -fsS http://127.0.0.1:8080/api/yaoming/rooms)
test "$before" = "$after"
test "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/api/rooms)" = 404
test "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:5173/ws/info)" = 404
# Explicitly retire obsolete bundles, including old entry points that import LegacyApp.
# They are already recoverable from public-before.tar.gz. No room data is removed.
mkdir "$backup/retired-assets"
while IFS= read -r -d '' asset; do
  name=$(basename "$asset")
  if ! test -f "$backup/stage/frontend/dist/assets/$name"; then mv "$asset" "$backup/retired-assets/$name"; fi
done < <(find "$site/assets" -maxdepth 1 -type f -print0)
if test -d /var/www/mahjong; then
  test "$(readlink -f /var/www/mahjong)" = /var/www/mahjong
  grep -q '雀境' /var/www/mahjong/index.html
  mv /var/www/mahjong "$backup/retired-original-site"
fi
trap - ERR
sha256sum "$site/index.html" "$project/backend/target/mahjong-server-0.1.0.jar"
systemctl show mahjong-backend -p MainPID -p ActiveState -p SubState -p NRestarts
printf 'New-only release complete. Recovery directory: %s\n' "$backup"
