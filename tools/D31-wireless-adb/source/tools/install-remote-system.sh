#!/system/bin/sh
# 首次普通基础到系统完整部署；第四参数为独立校验DEX的SHA-256。
set -eu
[ "$#" = 4 ] || { echo '需要暂存目录、完整APK摘要、原启动钩子摘要、校验DEX摘要' >&2; exit 2; }
exec /system/bin/sh "${0%/*}/remote-system-transaction.sh" install "$@"
