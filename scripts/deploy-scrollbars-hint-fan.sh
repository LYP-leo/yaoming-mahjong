#!/usr/bin/env bash
set -euo pipefail
umask 077

# Frontend only: retain the running Java process, room data, and old hashed assets.
project=/home/leo/mahjong_20260822
site=/var/www/mahjong-yaoming-20260907
backup="$project/backups/scrollbars-hint-fan-20260909"
archive=/tmp/mahjong-scrollbars-hint-fan-20260909.tar.gz
new_index=${1:?Expected tested index SHA256}
archive_sha=${2:?Expected release archive SHA256}
test "$(id -u)" = 0
test "$(readlink -f "$project")" = "$project"
test "$(readlink -f "$site")" = "$site"
test ! -e "$backup"
test "$(sha256sum "$archive" | cut -d' ' -f1)" = "$archive_sha"
test "$(sha256sum "$site/index.html" | cut -d' ' -f1)" = 25ac667c78d8066ce34d38af3335a9d71e962c3d1069ec57392693a751ce7e51
python3 - "$archive" <<'PY'
import sys, tarfile
from pathlib import PurePosixPath
with tarfile.open(sys.argv[1]) as archive:
    for member in archive:
        path = PurePosixPath(member.name)
        assert not path.is_absolute() and '..' not in path.parts, member.name
        assert member.isfile() or member.isdir(), member.name
        assert (member.name == 'frontend/src' or member.name.startswith('frontend/src/')
                or member.name == 'frontend/dist' or member.name.startswith('frontend/dist/')
                or member.name in ['docs/SCROLLBARS_HINT_FAN_PLAN.md',
                                   'docs/SCROLLBARS_HINT_FAN_TEST_REPORT.md',
                                   'scripts/deploy-scrollbars-hint-fan.sh']), member.name
PY
before_pid=$(systemctl show mahjong-backend -p MainPID --value)
before_jar=$(sha256sum "$project/backend/target/mahjong-server-0.1.0.jar" | cut -d' ' -f1)
test "$before_pid" != 0
systemctl is-active --quiet mahjong-backend
mkdir -p "$backup/stage"
tar -czf "$backup/public-before.tar.gz" -C "$site" .
tar -czf "$backup/source-before.tar.gz" -C "$project" frontend/src frontend/dist
cp "$site/index.html" "$backup/index-before.html"
cp "$archive" "$backup/source-current.tar.gz"
tar -xzf "$archive" -C "$backup/stage" --no-same-owner
test "$(sha256sum "$backup/stage/frontend/dist/index.html" | cut -d' ' -f1)" = "$new_index"
applied=0
rollback() {
  code=$?
  if [ "$code" -ne 0 ] && [ "$applied" = 1 ]; then
    tar -xzf "$backup/source-before.tar.gz" -C "$project" --no-same-owner
    install -m 644 "$backup/index-before.html" "$site/index.scrollbars-rollback.next"
    mv -f "$site/index.scrollbars-rollback.next" "$site/index.html"
    printf 'Frontend rolled back; backup: %s\n' "$backup" >&2
  fi
  exit "$code"
}
trap rollback EXIT
applied=1
tar -xzf "$archive" -C "$project" --no-same-owner
install -m 644 "$backup/stage/frontend/dist/assets/"* "$site/assets/"
install -m 644 "$backup/stage/frontend/dist/index.html" "$site/index.scrollbars.next"
mv -f "$site/index.scrollbars.next" "$site/index.html"
test "$(sha256sum "$site/index.html" | cut -d' ' -f1)" = "$new_index"
test "$(systemctl show mahjong-backend -p MainPID --value)" = "$before_pid"
test "$(sha256sum "$project/backend/target/mahjong-server-0.1.0.jar" | cut -d' ' -f1)" = "$before_jar"
systemctl is-active --quiet mahjong-backend nginx frpc_tencent
curl --fail --silent --show-error --max-time 15 http://127.0.0.1:5173/ -o "$backup/served-index.html"
test "$(sha256sum "$backup/served-index.html" | cut -d' ' -f1)" = "$new_index"
printf 'Frontend deployed; backend PID unchanged: %s; backup: %s\n' "$before_pid" "$backup"
sha256sum "$site/index.html" "$site/assets/index-"*.css "$site/assets/index-"*.js
trap - EXIT
