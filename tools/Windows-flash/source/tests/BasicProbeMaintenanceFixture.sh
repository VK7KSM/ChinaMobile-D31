#!/bin/sh
id() { if [ "$D31_MAINTENANCE_FAIL" = root ]; then printf '2000\n'; else printf '0\n'; fi; }
busybox() {
    action=$1
    shift
    [ "$D31_MAINTENANCE_FAIL" != "$action" ] || return 42
    case "$action" in
        ls) command ls "$@" ;;
        grep) command grep "$@" ;;
        *) return 43 ;;
    esac
}
eval "$D31_MAINTENANCE_COMMAND"
