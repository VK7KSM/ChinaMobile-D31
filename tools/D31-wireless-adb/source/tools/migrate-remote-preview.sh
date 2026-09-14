#!/system/bin/sh
set -e
umask 077
source="$1"
target=/data/local/d31-remote/runtime
case "$source" in /data/local/d31-remote/phase1-*/state) ;; *) exit 2 ;; esac
[ "$(id -u)" = 0 ]
[ -e "$source/stop" ]
[ ! -e "$source/remote.pid" ]
[ -f "$source/identity.json" ]
[ ! -e "$target" ]
mkdir "$target"
mkdir "$target/state"
for item in "$source"/*; do
  case "${item##*/}" in stop|remote.pid|remote.lock) continue ;; esac
  cp -Rp "$item" "$target/state/"
done
cmp "$source/identity.json" "$target/state/identity.json"
chmod 0700 "$target" "$target/state"
sync
echo REMOTE_STATE_MIGRATED
