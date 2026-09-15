#!/usr/bin/env bash
set -euo pipefail

# Fresh-host install only. Refuse to overwrite a project or an existing site.
project=/home/leo/mahjong_20260822
site=/var/www/mahjong-yaoming-20260907
stage=/home/leo/mahjong-redeploy-20260914
archive="$stage/application.tar.gz"
backup=/root/mahjong-redeploy-backup-20260914
archive_hash=${1:?archive SHA256 required}
jar_hash=${2:?tested JAR SHA256 required}
index_hash=${3:?tested index SHA256 required}
test "$(id -u)" = 0
[[ "$archive_hash" =~ ^[0-9a-f]{64}$ && "$jar_hash" =~ ^[0-9a-f]{64}$ && "$index_hash" =~ ^[0-9a-f]{64}$ ]]
test "$(readlink -f "$stage")" = "$stage"
test "$(sha256sum "$archive" | cut -d' ' -f1)" = "$archive_hash"
test ! -e "$project" && test ! -L "$project"
test ! -e "$site" && test ! -L "$site"
test ! -e /etc/systemd/system/mahjong-backend.service
test ! -e /etc/nginx/sites-available/mahjong
test ! -e /etc/nginx/sites-enabled/mahjong
test ! -e "$backup"
test "$(find /etc/nginx/sites-enabled -mindepth 1 -maxdepth 1 ! -name default -printf '%f\n')" = ''
if test -e /etc/nginx/sites-enabled/default; then
  test -L /etc/nginx/sites-enabled/default
  test "$(readlink -f /etc/nginx/sites-enabled/default)" = /etc/nginx/sites-available/default
fi
python3 - "$archive" <<'PY'
import sys, tarfile
from pathlib import PurePosixPath
prefixes = ('backend/src/', 'frontend/src/', 'frontend/dist/', 'frontend/public/',
            'frontend/artwork/', 'frontend/scripts/')
files = {'README.md', '.gitignore', 'backend/pom.xml', 'backend/target/mahjong-server-0.1.0.jar',
         'frontend/package.json', 'frontend/package-lock.json', 'frontend/vite.config.ts',
         'frontend/tsconfig.json', 'frontend/tsconfig.app.json', 'frontend/tsconfig.node.json',
         'frontend/index.html', 'frontend/hints-preview.html', 'frontend/stable-table-preview.html',
         'frontend/tile-art-preview.html', 'frontend/replay-layout-preview.html',
         'deploy/mahjong-backend.service', 'deploy/nginx-mahjong.conf',
         'docs/SERVER_DEPLOYMENT.md', 'docs/DELL_REDEPLOY_20260914_PLAN.md'}
dirs = {'backend', 'backend/src', 'backend/target', 'frontend', 'frontend/src', 'frontend/dist',
        'frontend/public', 'frontend/artwork', 'frontend/scripts', 'deploy', 'docs'}
seen = set()
with tarfile.open(sys.argv[1], 'r:gz') as archive:
    for member in archive.getmembers():
        name = member.name.rstrip('/')
        path = PurePosixPath(name)
        if not name or path.is_absolute() or '..' in path.parts or '\\' in name or name in seen:
            raise SystemExit('Unsafe archive path')
        seen.add(name)
        if not (member.isfile() or member.isdir()):
            raise SystemExit('Links and special files are forbidden')
        if not (name in files or name.startswith(prefixes) or member.isdir() and name in dirs):
            raise SystemExit('Archive member outside the deployment allowlist: ' + name)
        if any(p in ('node_modules', '.git', '.venv', 'data') for p in path.parts):
            raise SystemExit('Runtime data or dependency cache in archive')
print('Archive safety check passed:', len(seen), 'entries')
PY

umask 077
mkdir "$backup"
cp -a /etc/nginx/nginx.conf /etc/nginx/sites-available/default "$backup/"
sha256sum /usr/local/lib/frp_0.63.0_linux_amd64/frpc_tencent.ini > "$backup/frp-config.sha256"
systemctl show frpc_tencent -p MainPID > "$backup/frp-before.txt"
install -d -m 755 -o leo -g leo "$project"
tar -xzf "$archive" -C "$project" --no-same-owner --no-same-permissions
test "$(readlink -f "$project")" = "$project"
chown -R leo:leo "$project"
chmod -R u+rwX,go-rwx "$project"
chmod 755 "$project"
test "$(sha256sum "$project/backend/target/mahjong-server-0.1.0.jar" | cut -d' ' -f1)" = "$jar_hash"
test "$(sha256sum "$project/frontend/dist/index.html" | cut -d' ' -f1)" = "$index_hash"
install -d -m 700 -o leo -g leo "$project/backend/data"
install -d -m 755 "$site"
cp -a "$project/frontend/dist/." "$site/"
chown -R root:root "$site"
find "$site" -type d -exec chmod 755 {} +
find "$site" -type f -exec chmod 644 {} +
install -m 644 "$project/deploy/mahjong-backend.service" /etc/systemd/system/mahjong-backend.service
install -m 644 "$project/deploy/nginx-mahjong.conf" /etc/nginx/sites-available/mahjong
# Preserve the distro default symlink in a recoverable directory, not delete it.
if test -L /etc/nginx/sites-enabled/default; then
  mv /etc/nginx/sites-enabled/default "$backup/default-enabled"
fi
ln -s /etc/nginx/sites-available/mahjong /etc/nginx/sites-enabled/mahjong
nginx -t
systemctl daemon-reload
systemctl enable --now mahjong-backend
healthy=false
for attempt in $(seq 1 45); do
  if curl -fsS --max-time 2 http://127.0.0.1:8080/api/yaoming/rulesets > "$backup/rulesets.json"; then
    healthy=true; break
  fi
  sleep 1
done
test "$healthy" = true
systemctl enable nginx
systemctl reload-or-restart nginx
curl -fsS --max-time 5 http://127.0.0.1:5173/ -o "$backup/served-index.html"
test "$(sha256sum "$backup/served-index.html" | cut -d' ' -f1)" = "$index_hash"
curl -fsS --max-time 5 http://127.0.0.1:5173/api/yaoming/rooms
sha256sum -c "$backup/frp-config.sha256"
systemctl show frpc_tencent -p MainPID > "$backup/frp-after.txt"
cmp "$backup/frp-before.txt" "$backup/frp-after.txt"
systemctl is-active mahjong-backend nginx frpc_tencent
systemctl is-enabled mahjong-backend nginx frpc_tencent
printf 'Fresh deployment complete. Project: %s, static site: %s, recovery: %s\n' "$project" "$site" "$backup"
