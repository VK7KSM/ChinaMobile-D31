#!/bin/sh
# Linux离线验证；只创建临时文件及隔离根目录，需root执行chroot，不使用真实块设备。
set -eu
source_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
output_dir=${1:-$(mktemp -d /tmp/d31-native-146-results-XXXXXX)}
mkdir -p "$output_dir"
output_dir=$(CDPATH= cd -- "$output_dir" && pwd)
test "$(id -u)" = 0 || { echo '测试需root，以便在无真实设备的隔离根目录中运行'; exit 1; }
wraps='open openat close fstat fstatat unlinkat ioctl write read fsync mount umount umount2 sync reboot lgetxattr'
set --
for name in $wraps; do set -- "$@" "-Wl,--wrap=$name"; done
for version in 144 145 146; do
    define="-DD31_PACKAGE_$version"
    cc -std=c11 -D_GNU_SOURCE -D_FILE_OFFSET_BITS=64 -Wall -Wextra -Werror -O2 "$define" \
        "$source_dir/update_binary.c" -lz -o "$output_dir/update-binary-$version"
    # 同一测试源保留只在另一版本使用的辅助函数，允许它们未被引用。
    cc -std=c11 -D_FILE_OFFSET_BITS=64 -Wall -Wextra -Werror -Wno-unused-function -O2 "$define" \
        "$source_dir/test_native_146.c" "$@" -lz -o "$output_dir/test-native-146-$version"
    "$output_dir/test-native-146-$version" "$output_dir/fixture-$version" > "$output_dir/test-$version.log" 2>&1 || {
        cat "$output_dir/test-$version.log"
        exit 1
    }
    cat "$output_dir/test-$version.log"
done
printf '验证输出：%s\n' "$output_dir"
