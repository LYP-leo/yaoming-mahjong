#!/usr/bin/env bash
set -euo pipefail
umask 077

# Frontend only, run as the existing project owner. No sudo or service restart.
project=/home/leo/mahjong_20260822
site=/var/www/mahjong-yaoming-20260907
backup="$project/backups/entry-dialog-dismiss-20260911"
archive=/tmp/mahjong-entry-dialog-dismiss-20260911.tar.gz
archive_sha=${1:?Expected release archive SHA256}
new_index=5b23389dc7b12a15ca3797fc4b549d1cae61f68e8ab67c586cef04fe7869c1bd

test "$(readlink -f "$project")" = "$project"
test "$(readlink -f "$site")" = "$site"
test ! -e "$backup"
test "$(sha256sum "$archive" | cut -d' ' -f1)" = "$archive_sha"
test "$(sha256sum "$site/index.html" | cut -d' ' -f1)" = 55c8eb533f45cba9cf728ab5b945d2d7e7f62edb3456370891520273e5ccd273
test "$(sha256sum "$project/frontend/src/yaoming/YaomingApp.vue" | cut -d' ' -f1)" = 562b179679b4152b22f1bfe52f06bb37654768b372ecf509e08e517066bcd1fe
test "$(sha256sum "$project/frontend/src/yaoming/playability.css" | cut -d' ' -f1)" = c623b634f906a1672380b0f88ca07c874ec77e2e69ec74cf3face3c9779e150a
python3 - "$archive" <<'PY'
import sys, tarfile
allowed = {
    'frontend/src/yaoming/YaomingApp.vue',
    'frontend/src/yaoming/playability.css',
    'frontend/src/yaoming/entry-dialog.test.ts',
    'frontend/dist/index.html',
    'frontend/dist/assets/index-kEiFxg4X.js',
    'frontend/dist/assets/index-DwTcSYl5.css',
    'frontend/dist/assets/mahjong-graphic-atlas-C-odaEvj.svg',
    'docs/ENTRY_DIALOG_DISMISS_PLAN.md',
    'docs/ENTRY_DIALOG_DISMISS_TEST_REPORT.md',
    'scripts/deploy-entry-dialog-dismiss.sh',
}
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
tar -czf "$backup/source-before.tar.gz" -C "$project" frontend/src/yaoming/YaomingApp.vue frontend/src/yaoming/playability.css frontend/dist/index.html
cp "$archive" "$backup/release.tar.gz"
tar -xzf "$archive" -C "$backup/stage" --no-same-owner
test "$(sha256sum "$backup/stage/frontend/dist/index.html" | cut -d' ' -f1)" = "$new_index"

restore_index() {
  code=$?
  if [ "$code" -ne 0 ]; then
    install -m 644 "$backup/index-before.html" "$site/index.entry-rollback.next"
    mv -f "$site/index.entry-rollback.next" "$site/index.html"
    printf 'Serving previous frontend; source backup: %s\n' "$backup" >&2
  fi
  exit "$code"
}
trap restore_index EXIT

# Keep every old hashed asset for browsers still using the previous entry point.
for destination in "$site/assets" "$project/frontend/dist/assets"; do
  for asset in index-kEiFxg4X.js index-DwTcSYl5.css mahjong-graphic-atlas-C-odaEvj.svg; do
    if [ -e "$destination/$asset" ]; then
      cmp "$backup/stage/frontend/dist/assets/$asset" "$destination/$asset"
    else
      install -m 644 "$backup/stage/frontend/dist/assets/$asset" "$destination/$asset"
    fi
  done
done
for path in frontend/src/yaoming/YaomingApp.vue frontend/src/yaoming/playability.css frontend/src/yaoming/entry-dialog.test.ts frontend/dist/index.html docs/ENTRY_DIALOG_DISMISS_PLAN.md docs/ENTRY_DIALOG_DISMISS_TEST_REPORT.md scripts/deploy-entry-dialog-dismiss.sh; do
  install -m 644 "$backup/stage/$path" "$project/$path.entry-next"
  mv -f "$project/$path.entry-next" "$project/$path"
done
install -m 644 "$backup/stage/frontend/dist/index.html" "$site/index.entry-next"
mv -f "$site/index.entry-next" "$site/index.html"
curl --fail --silent --show-error --max-time 15 http://127.0.0.1:5173/ -o "$backup/served-index.html"
test "$(sha256sum "$backup/served-index.html" | cut -d' ' -f1)" = "$new_index"
test "$(systemctl show mahjong-backend -p MainPID --value)" = "$before_pid"
test "$(sha256sum "$project/backend/target/mahjong-server-0.1.0.jar" | cut -d' ' -f1)" = "$before_jar"
systemctl is-active --quiet mahjong-backend nginx frpc_tencent
printf 'Frontend deployed; backend PID unchanged: %s; backup: %s\n' "$before_pid" "$backup"
sha256sum "$site/index.html" "$site/assets/index-kEiFxg4X.js" "$site/assets/index-DwTcSYl5.css"
trap - EXIT
