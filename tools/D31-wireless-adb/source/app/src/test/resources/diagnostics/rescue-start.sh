#!/system/bin/sh
[ -f '/data/local/d31-rescue/enabled' ] || exit 0
export CLASSPATH=/data/local/d31-rescue/daemon.apk
exec /system/bin/busybox setsid /system/bin/app_process /system/bin --nice-name=d31-rescue net.elfradio.d31bootstrap.RescueDaemon /data/local/d31-rescue '/data/local/d31-rescue/enabled'
