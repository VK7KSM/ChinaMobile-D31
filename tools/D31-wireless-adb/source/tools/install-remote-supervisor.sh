#!/system/bin/sh
set -e
umask 077
stage="$1"
apk_hash="$2"
old_apk_hash="$3"
old_start_hash="$4"
case "$stage" in /data/local/d31-remote/deploy-*) ;; *) exit 2 ;; esac
[ "$(id -u)" = 0 ]
[ ! -e "$stage/start.before" ]
[ ! -e /data/local/d31-remote/runtime/updates/supervisor.json ]
echo "$old_start_hash  /system/bin/d31-elfremote-start" | busybox sha256sum -c -
sh -n "$stage/start.sh"
sh -n "$stage/replace.sh"
cp -p /system/bin/d31-elfremote-start "$stage/start.before"
sh "$stage/replace.sh" "$stage" "$apk_hash" "$old_apk_hash"
# APK安装完成后，旧入口也能运行新核心；入口替换失败时回退入口，不清数据。
rollback_start() {
  mount -o remount,rw /system
  cp -p "$stage/start.before" /system/bin/d31-elfremote-start
  chcon u:object_r:system_file:s0 /system/bin/d31-elfremote-start
  sync
  mount -o remount,ro /system
}
trap 'rollback_start' EXIT
mount -o remount,rw /system
cp "$stage/start.sh" /system/bin/d31-elfremote-start.new
chmod 0755 /system/bin/d31-elfremote-start.new
chcon u:object_r:system_file:s0 /system/bin/d31-elfremote-start.new
cmp "$stage/start.sh" /system/bin/d31-elfremote-start.new
mv /system/bin/d31-elfremote-start.new /system/bin/d31-elfremote-start
sync
mount -o remount,ro /system
trap - EXIT
rm /data/local/d31-remote/runtime/state/stop
echo REMOTE_SUPERVISOR_STAGED
