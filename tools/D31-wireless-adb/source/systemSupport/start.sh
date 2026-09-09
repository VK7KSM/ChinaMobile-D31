#!/system/bin/sh
ROOT=/data/local/d31-system-support
test -e "$ROOT/disabled" && exit 0
test -x "$ROOT/guard" || exit 1
exec "$ROOT/guard"
