#!/system/bin/sh
set -e
umask 077
stage="$1"
expected_apk="$2"
expected_hook="$3"
case "$stage" in /data/local/d31-remote/deploy-*) ;; *) exit 2 ;; esac
[ "$(id -u)" = 0 ]
[ "$(getprop ro.product.device)" = hct6735_66_m0 ]
[ "$(getprop ro.product.model)" = hct6737t_66_m0 ]
[ ! -e /system/priv-app/D31ElfRemote ]
[ ! -e /system/etc/d31-elfremote.system ]
[ ! -e /system/bin/d31-elfremote-start ]
[ ! -e "$stage/backup" ]
echo "$expected_apk  $stage/remote.apk" | busybox sha256sum -c -
echo "$expected_hook  /system/bin/install-recovery.sh" | busybox sha256sum -c -
sh -n "$stage/install-recovery.sh"
sh -n "$stage/start.sh"
mkdir "$stage/backup"
cp -p /system/bin/install-recovery.sh "$stage/backup/install-recovery.sh"
old_apk=$(pm path net.elfradio.d31bootstrap | busybox sed -n 's/^package://p' | head -n 1)
[ -n "$old_apk" ]
cp -p "$old_apk" "$stage/backup/original.apk"
rollback() {
  mount -o remount,rw /system
  cp -p "$stage/backup/install-recovery.sh" /system/bin/install-recovery.sh
  rm -f /system/etc/d31-elfremote.system /system/bin/d31-elfremote-start
  rm -f /system/priv-app/D31ElfRemote/D31ElfRemote.apk
  rmdir /system/priv-app/D31ElfRemote 2>/dev/null || true
  sync
  mount -o remount,ro /system
  pm disable net.elfradio.d31bootstrap >/dev/null
}
trap 'rollback' EXIT
mount -o remount,rw /system
mkdir /system/priv-app/D31ElfRemote
chmod 0755 /system/priv-app/D31ElfRemote
cp "$stage/remote.apk" /system/priv-app/D31ElfRemote/D31ElfRemote.apk
cp "$stage/start.sh" /system/bin/d31-elfremote-start
cp "$stage/marker" /system/etc/d31-elfremote.system
chmod 0644 /system/priv-app/D31ElfRemote/D31ElfRemote.apk /system/etc/d31-elfremote.system
chmod 0755 /system/bin/d31-elfremote-start
chcon u:object_r:system_file:s0 /system/priv-app/D31ElfRemote /system/priv-app/D31ElfRemote/D31ElfRemote.apk /system/etc/d31-elfremote.system /system/bin/d31-elfremote-start
cp -p /system/bin/install-recovery.sh /system/bin/install-recovery.sh.elfremote-new
cat "$stage/install-recovery.sh" > /system/bin/install-recovery.sh.elfremote-new
mv /system/bin/install-recovery.sh.elfremote-new /system/bin/install-recovery.sh
echo "$expected_apk  /system/priv-app/D31ElfRemote/D31ElfRemote.apk" | busybox sha256sum -c -
cmp "$stage/install-recovery.sh" /system/bin/install-recovery.sh
cmp "$stage/start.sh" /system/bin/d31-elfremote-start
cmp "$stage/marker" /system/etc/d31-elfremote.system
sync
mount -o remount,ro /system
pm install -r /system/priv-app/D31ElfRemote/D31ElfRemote.apk > "$stage/pm-install.log" 2>&1
grep -q Success "$stage/pm-install.log"
# 旧存储入口已迁系统支持；覆盖安装后仍明确停用旧广播和文件选择入口。
for component in BootReceiver VendorNetworkReceiver UsbBrowseActivity UsbInsertPromptActivity; do
  pm disable "net.elfradio.d31bootstrap/.$component" >/dev/null
done
pm enable net.elfradio.d31bootstrap >/dev/null
trap - EXIT
echo SYSTEM_COMPONENT_STAGED
