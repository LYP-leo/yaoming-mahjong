#!/usr/bin/env bash
set -euo pipefail

# One-time frontend-only release. Keep old hashed assets for existing browsers.
project=/home/leo/mahjong_20260822
site=/var/www/mahjong-yaoming-20260907
backup="$project/backups/auto-hints-20260909"
archive=/tmp/mahjong-auto-hints-20260909.tar.gz
test "$(readlink -f "$project")" = /home/leo/mahjong_20260822
test "$(readlink -f "$site")" = /var/www/mahjong-yaoming-20260907
test -r "$archive"
test ! -e "$backup"
test "$(sha256sum "$site/index.html" | cut -d' ' -f1)" = 4e81a0c3eec37ab350b0eb9f84201d2263b51f8bc727c2203368d4f9bcf9916b
before_pid=$(systemctl show mahjong-backend -p MainPID --value)
mkdir -p "$backup"
tar -czf "$backup/public-before.tar.gz" -C "$site" .
tar -czf "$backup/source-before.tar.gz" -C "$project" frontend/src frontend/dist README.md docs backend/src/test/java/com/mahjong/yaoming/YmHintsTest.java
cp "$archive" "$backup/source-current.tar.gz"
mkdir "$backup/stage"
tar -xzf "$archive" -C "$backup/stage" --no-same-owner
test "$(sha256sum "$backup/stage/frontend/dist/index.html" | cut -d' ' -f1)" = b39bb2ca40568aaa8fe57930930cc667459ea8ab2b43d6fd4a2712a360d934c1
tar -xzf "$archive" -C "$project" --no-same-owner
cp -a "$backup/stage/frontend/dist/assets/." "$site/assets/"
cp "$backup/stage/frontend/dist/index.html" "$site/index.auto-hints-20260909.next"
mv -f "$site/index.auto-hints-20260909.next" "$site/index.html"
test "$(systemctl show mahjong-backend -p MainPID --value)" = "$before_pid"
sha256sum "$site/index.html" "$site/assets/index-DH2vQUVD.js"
systemctl show mahjong-backend -p MainPID -p ActiveState -p NRestarts
printf 'Frontend release complete; previous static site: %s/public-before.tar.gz\n' "$backup"
