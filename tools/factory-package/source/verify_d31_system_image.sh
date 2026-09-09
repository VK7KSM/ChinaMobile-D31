#!/bin/sh
set -eu

if [ "$#" -ne 2 ]; then
    echo "用法：$0 system.img config-tab模板" >&2
    exit 2
fi

image=$1
config_template=$2
expected_bytes=1610612736

[ -f "$image" ] || { echo "system镜像不存在。" >&2; exit 3; }
[ -f "$config_template" ] || { echo "config-tab模板不存在。" >&2; exit 4; }
[ "$(stat -c %s "$image")" = "$expected_bytes" ] || {
    echo "system镜像长度不匹配。" >&2
    exit 5
}

e2fsck -fn "$image"

mount_point="/tmp/d31-system-verify-$PPID-$$"
case "$mount_point" in
    /tmp/d31-system-verify-*) ;;
    *) echo "挂载点安全检查失败。" >&2; exit 6 ;;
esac
mkdir -p "$mount_point"
mounted=0
cleanup() {
    if [ "$mounted" -eq 1 ]; then
        umount "$mount_point" || umount -l "$mount_point" || true
    fi
    rmdir "$mount_point" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

mount -o loop,ro "$image" "$mount_point"
mounted=1

for relative in \
    vendor/3rd-app/android.apk \
    vendor/3rd-app/moffice.apk \
    app/BluetoothMidiService \
    app/QuickSearchBox \
    app/Exchange2 \
    vendor/operator/app/Baidu_Location \
    app/MtkCalendar \
    priv-app/CalendarProvider \
    app/CalendarImporter \
    app/MtkBrowser \
    vendor/3rd-app/i-jetty.apk \
    app/HTMLViewer \
    app/Music \
    vendor/3rd-app/tr069.apk \
    vendor/3rd-app/tr069proxy.apk \
    vendor/3rd-app/emu.apk \
    vendor/3rd-app/daemon.apk \
    app/Omacp
do
    if [ -e "$mount_point/$relative" ] || [ -L "$mount_point/$relative" ]; then
        echo "禁止路径仍存在：/$relative" >&2
        exit 10
    fi
    echo "通过，禁止路径不存在：/$relative"
done

for required in \
    vendor/3rd-app/nexui.apk \
    vendor/3rd-app/imscc.apk \
    vendor/3rd-app/dial.apk \
    vendor/3rd-app/vsdkpinyin.apk \
    app/Calculator \
    app/EngineerMode \
    app/MTKLogger \
    app/factory-test \
    vendor/3rd-app/screensaver.apk
do
    [ -e "$mount_point/$required" ] || {
        echo "必须保留的路径缺失：/$required" >&2
        exit 11
    }
    echo "通过，必须路径存在：/$required"
done

cmp "$config_template" "$mount_point/vendor/starnet/launcher/config/config-tab"
echo "通过，system内config-tab与无账号8入口模板逐字一致。"

startup_hook="$mount_point/bin/install-recovery.sh"
[ -f "$startup_hook" ] || {
    echo "缺少开机补丁启动脚本：/bin/install-recovery.sh" >&2
    exit 12
}
grep -Fq '# D31_HOME_PATCH_BEGIN' "$startup_hook"
grep -Fq '/system/bin/sh /data/local/d31-patches/apply-home-patch.sh &' "$startup_hook"
grep -Fq '# D31_DUAL_NETWORK_ARP_BEGIN' "$startup_hook"
grep -Fq '# D31_VOLUME_RECOVERY_BEGIN' "$startup_hook"
grep -Fq '# D31_RESCUE_BEGIN' "$startup_hook"
grep -Fq '/data/local/d31-recovery-entry/persistent.sh' "$startup_hook"
grep -Fq '/data/local/d31-system-support/start.sh' "$startup_hook"
[ "$(stat -c %a "$startup_hook")" = 750 ]
[ "$(sha256sum "$mount_point/vendor/3rd-app/getnumber.apk" | cut -d ' ' -f 1)" = 2162cac91fa16a676e4b2987ad313e70e9e351bea2448af87e1e13c02f4cf94f ]
echo "通过，取号组件为已审核的无代码、无短信权限制品。"
echo "通过，开机补丁和双网卡ARP策略启动钩子存在。"

umount "$mount_point"
mounted=0
rmdir "$mount_point"
trap - EXIT INT TERM

sha256sum "$image" "$config_template"
echo "D31 system镜像独立只读验证通过。"
