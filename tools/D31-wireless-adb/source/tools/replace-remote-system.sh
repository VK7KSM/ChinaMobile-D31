#!/system/bin/sh
# 仅供低于85的旧基线接替；兼容基线及显式maintenance升级沿用既有Java入口。
set -eu
[ "$#" = 4 ] || { echo '需要暂存目录、完整APK摘要、原系统APK摘要、校验DEX摘要' >&2; exit 2; }
exec /system/bin/sh "${0%/*}/remote-system-transaction.sh" replace "$@"
