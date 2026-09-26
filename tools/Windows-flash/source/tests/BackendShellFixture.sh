#!/bin/sh
# 全部设备路径均在临时chroot内；真实执行grep和管道，绝不调用ADB。
set -eu
root=$(mktemp -d /tmp/d31-backend-shell.XXXXXX)
trap 'rm -rf "$root"' EXIT HUP INT TERM
mkdir -p "$root/bin" "$root/data/local/d31-startup-handover" "$root/data/local/d31-system-support" "$root/data/data/com.starnet.nexui/shared_prefs"
cp /bin/busybox "$root/bin/busybox"
for lib in $(ldd /bin/busybox 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i ~ /^\//) print $i}'); do
    mkdir -p "$root$(dirname "$lib")"
    cp "$lib" "$root$lib"
done
for app in sh grep cat id; do ln -s busybox "$root/bin/$app"; done
printf '1.4.6\n' > "$root/data/local/d31-startup-handover/factory-runtime-complete"
value=false
if [ '__SCENARIO__' = tcp-enabled ]; then value=true; fi
printf '<map><boolean name="TcpAcclerate" value="%s" /></map>\n' "$value" > "$root/data/data/com.starnet.nexui/shared_prefs/starNetBaseConfigFile.xml"
printf '#!/bin/sh\necho net.elfradio.d31system\necho /data/local/d31-system-support/guard\n' > "$root/bin/ps"
printf '#!/bin/sh\necho GUARD_MUST_NOT_EXECUTE\nexit 99\n' > "$root/data/local/d31-system-support/guard"
chmod +x "$root/bin/ps" "$root/data/local/d31-system-support/guard"
printf '%s' '__COMMAND_BASE64__' | base64 -d > "$root/command.sh"
printf '%s\n' 'test() { if [ "$1" = -S ]; then return 0; else /bin/busybox test "$@"; fi; }' '. /command.sh' > "$root/run.sh"
chroot_command=$(command -v chroot)
PATH=/bin "$chroot_command" "$root" /bin/sh /run.sh
