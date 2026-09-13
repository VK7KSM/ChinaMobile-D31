#!/bin/sh
# 新镜像只读全树核对；原镜像不挂载写入。
set -eu
image=$1
out=$2
mount_point=/tmp/d31-v144-tree-$$
mkdir "$mount_point"
mounted=0
cleanup() {
    if [ "$mounted" = 1 ]; then umount "$mount_point"; fi
    rmdir "$mount_point"
}
trap cleanup EXIT INT TERM
mount -o loop,ro "$image" "$mount_point"
mounted=1
cd "$mount_point"
find . -type f -exec sha256sum {} \; | LC_ALL=C sort > "$out/system-hashes.txt"
find . -exec stat -c '%a|%u|%g|%s|%n' {} \; | LC_ALL=C sort > "$out/system-metadata.txt"
find . -type l -exec sh -c 'printf "%s|%s\n" "$1" "$(readlink "$1")"' sh {} \; | LC_ALL=C sort > "$out/system-links.txt"
cd /
umount "$mount_point"
mounted=0
echo '新镜像全树只读采集完成'
