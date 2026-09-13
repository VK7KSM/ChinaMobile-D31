#!/system/bin/sh
set -u
umask 077
root=/data/local/d31-recovery-entry
[ -f "$root/enabled" ] && [ ! -L "$root/enabled" ] || exit 0
[ "$(cat /sys/class/BOOT/BOOT/boot/boot_mode)" = 0 ] || exit 0
boot=$(cat /proc/sys/kernel/random/boot_id) || exit 1
case "$boot" in ''|*[!0-9a-f-]*) exit 1;; esac
[ "${#boot}" = 36 ] || exit 1
[ -d "$root/runs" ] && [ ! -L "$root/runs" ] || exit 1
run="$root/runs/$boot"
mkdir "$run" 2>/dev/null || exit 0
exec > "$run/result.txt" 2>&1
echo VERSION=persistent-volume-entry-v1
echo "BOOT=$boot"
# A recovery request suppresses the entire next normal boot.
if [ -e "$root/latch" ] || [ -L "$root/latch" ]; then
    echo PREVIOUS_REQUEST_SUPPRESS_THIS_BOOT
    for n in $(seq 1 180); do
        if [ "$(getprop sys.boot_completed)" = 1 ]; then
            [ -f "$root/latch" ] && [ ! -L "$root/latch" ] || exit 1
            rm "$root/latch" || exit 1
            echo HEALTHY_BOOT_REARM_NEXT_BOOT
            exit 0
        fi
        sleep 2
    done
    echo BOOT_NOT_COMPLETE_KEEP_LATCH
    exit 0
fi
hash=$(/system/bin/busybox sha256sum "$root/volume-watch") || exit 1
[ "${hash%% *}" = c87672777990570ebc518edc2a0646dcb72f9896ce82ea4230699224d80ad4a9 ] || exit 1
hash=$(/system/bin/busybox sha256sum "$root/gate.sh") || exit 1
[ "${hash%% *}" = f841d6866eb103953fdaea0d8fd371dc62f117dbe9d7f19e70790f950fc5b3ea ] || exit 1
"$root/volume-watch" --watch
rc=$?
echo "WATCH_EXIT=$rc"
[ "$rc" = 10 ] || exit 0
/system/bin/sh "$root/gate.sh" --gate-only || exit 1
(set -C; printf '%s\n' "$boot" > "$root/latch") || exit 1
sync
/system/bin/sh "$root/gate.sh" --gate-only || exit 1
echo REQUEST_REBOOT_RECOVERY
/system/bin/reboot recovery
echo "REBOOT_RETURN=$?"
