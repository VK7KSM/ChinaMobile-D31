#!/system/bin/sh
set -e
root=/data/local/d31-remote/runtime
[ "$(id -u)" = 0 ]
[ -f /system/etc/d31-elfremote.system ] || exit 0
umask 077
mkdir -p "$root/state"
chmod 0700 /data/local/d31-remote "$root" "$root/state"
apk=/system/priv-app/D31ElfRemote/D31ElfRemote.apk
# 监督进程固定使用系统基线；它校验并启动/data中的活动版本。
[ -r "$apk" ]
export CLASSPATH="$apk"
exec /system/bin/busybox setsid /system/bin/app_process /system/bin --nice-name=d31-remote-supervisor net.elfradio.d31bootstrap.RemoteSupervisor
