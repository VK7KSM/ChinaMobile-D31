#!/bin/sh
set -eu

if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
    echo "用法：$0 system.img config-tab模板 [版本]" >&2
    exit 2
fi

image=$1
config_template=$2
version=${3:-1.4.3}
case "$version" in 1.4.3|1.4.4|1.4.5) ;; *) exit 2;; esac
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

mount -o loop,ro,noload "$image" "$mount_point"
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

if [ "$version" = 1.4.4 ] || [ "$version" = 1.4.5 ]; then
    for relative in priv-app/D31ElfRemote priv-app/D31ElfRemote/lib priv-app/D31ElfRemote/lib/arm; do
        [ -d "$mount_point/$relative" ] && [ ! -L "$mount_point/$relative" ]
        [ "$(stat -c '%a:%u:%g' "$mount_point/$relative")" = 755:0:0 ]
    done
    [ ! -e "$mount_point/priv-app/D31ElfRemote/lib/arm64" ]
    [ ! -L "$mount_point/priv-app/D31ElfRemote/lib/arm64" ]
    apk="$mount_point/priv-app/D31ElfRemote/D31ElfRemote.apk"
    library="$mount_point/priv-app/D31ElfRemote/lib/arm/libjingle_peerconnection_so.so"
    apk_sha=3da0a647b602163098ecb110dea881dc519a6b3f15c25797806a5205bf861df8
    if [ "$version" = 1.4.5 ]; then apk_sha=55e70aa54e97b6c42539db044bc13bcc6a72a4791bfc73fea9bf45aad16d93bf; fi
    [ "$(sha256sum "$apk" | cut -d ' ' -f 1)" = "$apk_sha" ]
    [ "$(sha256sum "$library" | cut -d ' ' -f 1)" = 976ad84ff585eb7121ff7d800a172e60995f634d59a64a7f913e4dac8907b08e ]
    [ "$(stat -c '%a:%u:%g:%s' "$library")" = 644:0:0:6536680 ]
    for relative in priv-app/D31ElfRemote/D31ElfRemote.apk priv-app/D31ElfRemote/lib/arm/libjingle_peerconnection_so.so etc/d31-elfremote.system; do
        [ -f "$mount_point/$relative" ] && [ ! -L "$mount_point/$relative" ]
        [ "$(stat -c '%a:%u:%g' "$mount_point/$relative")" = 644:0:0 ]
    done
    [ "$(stat -c '%a:%u:%g' "$mount_point/bin/d31-elfremote-start")" = 755:0:0 ]
    grep -Fq '# D31_ELFREMOTE_BEGIN' "$startup_hook"
    grep -Fq '/system/bin/sh /system/bin/d31-elfremote-start' "$startup_hook"
    echo "通过，版本对应完整APK及唯一ARM32库摘要、ABI目录和权限匹配。"
fi

umount "$mount_point"
mounted=0
rmdir "$mount_point"
trap - EXIT INT TERM

sha256sum "$image" "$config_template"
echo "D31 system镜像独立只读验证通过。"
