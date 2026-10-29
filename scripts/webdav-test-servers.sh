#!/usr/bin/env bash
# Starts the WebDAV servers the experimental WebDAV sync is tested against, in Docker, and prints
# the KORGNOTE_WEBDAV_SERVERS value that runs the tests against them:
#
#   eval "$(scripts/webdav-test-servers.sh)"
#   ./gradlew :composeApp:jvmTest --tests '*WebDavServersTest*'
#   scripts/webdav-test-servers.sh stop
#
# - Apache mod_dav, a plain file server          http://localhost:18081/notes
#   the same, asking for Digest sign-in only     http://localhost:18081/digest
# - rclone serve webdav                          http://localhost:18082/notes
# - Nextcloud                                    http://localhost:18083/remote.php/dav/files/korg/notes
#
# Every server signs in as korg / korgnote-test. Nextcloud takes a minute or two to install itself
# on its first start.
set -euo pipefail

cd "$(dirname "$0")/.."
user=korg
pass=korgnote-test

if [[ ${1:-} == stop ]]; then
  docker rm -f korgnote-dav-apache korgnote-dav-rclone korgnote-dav-nextcloud >/dev/null 2>&1 || true
  exit 0
fi

running() { [[ -n $(docker ps -q --filter "name=^$1\$") ]]; }

if ! running korgnote-dav-apache; then
  docker rm -f korgnote-dav-apache >/dev/null 2>&1 || true
  docker run -d --name korgnote-dav-apache -p 18081:80 \
    -v "$PWD/scripts/webdav/apache.conf:/usr/local/apache2/conf/dav.conf:ro" \
    httpd:2.4 sh -c "htpasswd -cb conf/dav.passwd $user $pass &&
      echo \"$user:KorgNote test:\$(printf '%s' '$user:KorgNote test:$pass' | md5sum | cut -d' ' -f1)\" > conf/dav.digest &&
      mkdir -p var /dav/notes /dav/digest && chown -R daemon:daemon var /dav &&
      exec httpd-foreground -f conf/dav.conf" >/dev/null
fi

if ! running korgnote-dav-rclone; then
  docker rm -f korgnote-dav-rclone >/dev/null 2>&1 || true
  docker run -d --name korgnote-dav-rclone -p 18082:8080 --entrypoint sh rclone/rclone \
    -c "mkdir -p /data/notes && exec rclone serve webdav /data --addr :8080 --user $user --pass $pass" >/dev/null
fi

if ! running korgnote-dav-nextcloud; then
  docker rm -f korgnote-dav-nextcloud >/dev/null 2>&1 || true
  docker run -d --name korgnote-dav-nextcloud -p 18083:80 \
    -e SQLITE_DATABASE=nextcloud -e NEXTCLOUD_ADMIN_USER=$user -e NEXTCLOUD_ADMIN_PASSWORD=$pass \
    -e NEXTCLOUD_TRUSTED_DOMAINS=localhost nextcloud:apache >/dev/null
fi

# Waits for a server to answer a PROPFIND on its root, then makes sure its notes folder exists.
ready() {
  local root=$1 folder=$2
  for _ in $(seq 1 180); do
    code=$(curl -s -o /dev/null -w '%{http_code}' -u "$user:$pass" -X PROPFIND -H 'Depth: 0' "$root" || true)
    if [[ $code == 207 ]]; then
      curl -s -o /dev/null -u "$user:$pass" -X MKCOL "$folder" || true
      return
    fi
    sleep 2
  done
  echo "$root did not come up" >&2
  exit 1
}

ready http://localhost:18081/ http://localhost:18081/notes/
ready http://localhost:18082/ http://localhost:18082/notes/
ready "http://localhost:18083/remote.php/dav/files/$user/" "http://localhost:18083/remote.php/dav/files/$user/notes/"

echo "export KORGNOTE_WEBDAV_SERVERS='apache=http://localhost:18081/notes;apache-digest=http://localhost:18081/digest;rclone=http://localhost:18082/notes;nextcloud=http://localhost:18083/remote.php/dav/files/$user/notes'"
