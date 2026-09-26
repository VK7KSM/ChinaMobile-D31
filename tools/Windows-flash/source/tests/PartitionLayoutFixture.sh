#!/bin/sh
# 只构造离线目录，不挂载真实 /dev 或 /sys；节点仅供 test -b，绝不打开节点。
set -eu
root=$(mktemp -d /tmp/d31-partition-layout.XXXXXX)
trap 'rm -rf "$root"' EXIT HUP INT TERM
mkdir -p "$root/bin" "$root/dev/block" "$root/sys/class/block"
cp /bin/busybox "$root/bin/busybox"
# 兼容静态和动态 BusyBox；只复制运行时加载器和库。
for lib in $(ldd /bin/busybox 2>/dev/null | awk '{for (i=1;i<=NF;i++) if ($i ~ /^\//) print $i}'); do
    mkdir -p "$root$(dirname "$lib")"
    cp "$lib" "$root$lib"
done
for app in sh readlink printf tr cat; do ln -s busybox "$root/bin/$app"; done
byname=/dev/block/platform/mtk-msdc.0/11230000.msdc0/by-name
mkdir -p "$root$byname"
index=0
start=2048
for spec in boot:32768 recovery:32768 logo:16384 system:3145728 userdata:26401792 nvram:2048 nvdata:2048 protect1:2048 protect2:2048 proinfo:2048 secro:2048 seccfg:2048 frp:2048; do
    index=$((index+1))
    name=${spec%:*}
    sectors=${spec#*:}
    node=mmcblk0p$index
    mknod "$root/dev/block/$node" b 240 "$index"
    ln -s "/dev/block/$node" "$root$byname/$name"
    mkdir -p "$root/sys/class/block/$node"
    printf '%s\n' "$start" > "$root/sys/class/block/$node/start"
    printf '%s\n' "$sectors" > "$root/sys/class/block/$node/size"
    start=$((start+sectors))
done
sys="$root/sys/class/block/mmcblk0p2"
case '__SCENARIO__' in
    valid) ;;
    missing-link) rm "$root$byname/recovery" ;;
    regular-file) rm "$root/dev/block/mmcblk0p2"; touch "$root/dev/block/mmcblk0p2" ;;
    wrong-disk)
        mknod "$root/dev/block/mmcblk1p2" b 240 22
        mv "$sys" "$root/sys/class/block/mmcblk1p2"
        ln -sf /dev/block/mmcblk1p2 "$root$byname/recovery" ;;
    duplicate) ln -sf /dev/block/mmcblk0p1 "$root$byname/recovery" ;;
    overlap) printf '2048\n' > "$sys/start" ;;
    zero-start) printf '0\n' > "$sys/start" ;;
    negative-start) printf '%s\n' -1 > "$sys/start" ;;
    huge-start) printf '4294967297\n' > "$sys/start" ;;
    overflow-start) printf '9223372036854775808\n' > "$sys/start" ;;
    zero-size) printf '0\n' > "$sys/size" ;;
    huge-size) printf '4294967297\n' > "$sys/size" ;;
    overflow-size) printf '9223372036854775808\n' > "$sys/size" ;;
    wrong-size) printf '32767\n' > "$sys/size" ;;
    invalid-size) printf '32768x\n' > "$sys/size" ;;
    extra-line) printf '32768\nextra\n' > "$sys/size" ;;
    empty-size) : > "$sys/size" ;;
    missing-start) rm "$sys/start" ;;
    missing-size) rm "$sys/size" ;;
    *) echo '未知离线测试情形' >&2; exit 93 ;;
esac
printf '%s' '__COMMAND_BASE64__' | base64 -d > "$root/check.sh"
# 保持实际收到的脚本字节不变，真实执行 readlink、test -b、printf、tr、cat。
chroot_command=$(command -v chroot)
PATH=/bin "$chroot_command" "$root" /bin/sh /check.sh
