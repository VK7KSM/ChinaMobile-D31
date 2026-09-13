#!/system/bin/sh
set -u
root=/data/local/d31-startup-handover
boot=$(cat /proc/sys/kernel/random/boot_id) || exit 1
case "$boot" in ''|*[!0-9a-f-]*) exit 1;; esac
umask 077
mkdir "$root/runs/$boot" 2>/dev/null || exit 0
exec > "$root/runs/$boot/result.txt" 2>&1
echo VERSION=postboot-transaction-v11-first-boot
export CLASSPATH="$root/handover.jar"
if [ -f "$root/pending.properties" ]; then
    echo RECOVERY_ONLY_PREVIOUS_TRANSACTION
    n=0
    while [ "$n" -lt 30 ]; do
        if /system/bin/busybox timeout -t 3 -s KILL /system/bin/pm path com.starnet.nexui | grep -q '^package:' &&
           /system/bin/busybox timeout -t 3 -s KILL /system/bin/pm path com.starnet.cmcc.imscc | grep -q '^package:' &&
           /system/bin/busybox timeout -t 3 -s KILL /system/bin/pm path com.starnet.getnumber | grep -q '^package:'; then
            /system/bin/busybox timeout -t 30 -s KILL /system/bin/app_process /system/bin HandoverRuntime --recover
            exit $?
        fi
        n=$((n+1)); sleep 1
    done
    echo RECOVERY_PACKAGE_TIMEOUT
    exit 1
fi
[ ! -e "$root/disabled" ] || { echo HANDOVER_DISABLED_NO_PATCH; exit 0; }
while [ -e "$root/factory-init-required" ]; do
    [ ! -e "$root/disabled" ] || { echo HANDOVER_DISABLED_DURING_INIT; exit 0; }
    /system/bin/busybox timeout -t 60 -s KILL /system/bin/app_process /system/bin FactoryInit --apply
    rc=$?
    echo "FACTORY_INIT_EXIT=$rc"
    [ "$rc" = 0 ] && break
    sleep 30
done
n=0
while [ "$(getprop sys.boot_completed)" != 1 ]; do
    [ ! -e "$root/disabled" ] || { echo HANDOVER_DISABLED_WHILE_WAITING; exit 0; }
    if [ $((n % 6)) -eq 0 ]; then echo "WAITING_ANDROID_BOOT_SECONDS=$((n * 5))"; fi
    n=$((n+1)); sleep 5
done
/system/bin/busybox timeout -t 45 -s KILL /system/bin/app_process /system/bin HandoverRuntime --apply
rc=$?
echo "HANDOVER_EXIT=$rc"
exit "$rc"
