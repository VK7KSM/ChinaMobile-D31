#!/system/bin/sh
set -e
umask 077
stage="$1"
expected="$2"
previous="$3"
target=/system/priv-app/D31ElfRemote/D31ElfRemote.apk
case "$stage" in /data/local/d31-remote/deploy-*) ;; *) exit 2 ;; esac
[ "$(id -u)" = 0 ]
[ "$(getprop ro.product.device)" = hct6735_66_m0 ]
[ "$(getprop ro.product.model)" = hct6737t_66_m0 ]
[ -f /system/etc/d31-elfremote.system ]
[ -f /data/local/d31-remote/runtime/state/stop ]
[ ! -e /data/local/d31-remote/runtime/state/remote.pid ]
[ ! -e "$stage/backup" ]
echo "$expected  $stage/remote.apk" | busybox sha256sum -c -
echo "$previous  $target" | busybox sha256sum -c -
mkdir "$stage/backup"
cp -p "$target" "$stage/backup/system.apk"
rollback() {
  mount -o remount,rw /system
  cp -p "$stage/backup/system.apk" "$target"
  chcon u:object_r:system_file:s0 "$target"
  rm -f "$target.new"
  sync
  mount -o remount,ro /system
}
trap 'rollback' EXIT
mount -o remount,rw /system
cp "$stage/remote.apk" "$target.new"
chmod 0644 "$target.new"
chcon u:object_r:system_file:s0 "$target.new"
echo "$expected  $target.new" | busybox sha256sum -c -
mv "$target.new" "$target"
sync
mount -o remount,ro /system
pm install -r "$target" > "$stage/pm-install.log" 2>&1
grep -q Success "$stage/pm-install.log"
trap - EXIT
echo SYSTEM_BASELINE_REPLACED
