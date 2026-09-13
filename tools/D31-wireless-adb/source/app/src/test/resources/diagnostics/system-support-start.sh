#!/system/bin/sh
ROOT=/data/local/d31-system-support
test -e "$ROOT/disabled" && exit 0
test -x "$ROOT/guard" || exit 1
# 原厂框架按大小写比较自己的地址缓存；只规范本机已有值，不复制母机地址。
cached=$(getprop persist.sys.wifi.addr)
case "$cached" in
  ??:??:??:??:??:??)
    case "$cached" in *[!0-9a-fA-F:]*) ;; *)
      upper=$(printf '%s' "$cached" | tr 'a-f' 'A-F')
      [ "$cached" = "$upper" ] || setprop persist.sys.wifi.addr "$upper"
    ;; esac
  ;;
esac
exec "$ROOT/guard"
