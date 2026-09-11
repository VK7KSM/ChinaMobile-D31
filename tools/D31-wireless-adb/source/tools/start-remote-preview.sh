#!/system/bin/sh
set -e
candidate="$1"
state="$2"
case "$candidate" in /data/local/d31-remote/*/*.apk) ;; *) exit 2 ;; esac
case "$state" in /data/local/d31-remote/*/state) ;; *) exit 2 ;; esac
[ "$(id -u)" = 0 ]
[ -f "$candidate" ]
[ -d "$state" ]
export CLASSPATH="$candidate"
exec /system/bin/busybox setsid /system/bin/app_process /system/bin --nice-name=d31-remote-preview net.elfradio.d31bootstrap.RemoteDaemon "$state" run
