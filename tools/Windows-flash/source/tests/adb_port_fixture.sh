#!/bin/sh
# 所有设备命令均为进程内替身；只用宿主awk验证实际待发送脚本。
getprop() {
    case "$1" in
        service.adb.tcp.port) printf '%s\n' "$D31_TEST_SERVICE" ;;
        persist.adb.tcp.port) printf '%s\n' "$D31_TEST_PERSIST" ;;
        init.svc.adbd) printf '%s\n' "$D31_TEST_STATE" ;;
        *) return 81 ;;
    esac
}
busybox() {
    [ "$D31_TEST_FAIL" != awk ] || return 23
    [ "$1" = awk ] || return 82
    shift
    command awk "$@"
}
setprop() {
    [ "$D31_TEST_FAIL" != setprop ] || return 22
    [ "$#" = 2 ] || return 90
    [ "$1" = service.adb.tcp.port ] || return 83
    D31_TEST_SERVICE=$2
    printf 'PORT=%s\n' "$2"
}
# 模拟stop返回时进程尚未退出，不能用同步停止替身掩盖时序退化。
stop() { [ "$1" = adbd ] || return 84; D31_TEST_STATE=stopping; printf 'STOP=adbd\n'; }
start() {
    [ "$1" = adbd ] || return 85
    if [ "$D31_TEST_STATE" != stopped ]; then printf 'EARLY_START_REJECTED\n'; return 87; fi
    D31_TEST_STATE=running
    printf 'START=adbd\n'
}
sleep() {
    [ "$1" = 1 ] || [ "$1" = 2 ] || return 86
    [ "$D31_TEST_FAIL" != sleep ] || return 88
    if [ "$1" = 1 ]; then
        [ "$D31_TEST_STATE" = stopping ] || return 89
        D31_TEST_STATE=stopped
    fi
    printf 'WAIT=%s\n' "$1"
}
eval "$D31_TEST_COMMAND"
result=$?
printf 'PERSIST=%s\nSTATE=%s\n' "$D31_TEST_PERSIST" "$D31_TEST_STATE"
exit "$result"
