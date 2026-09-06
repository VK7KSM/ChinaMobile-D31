package net.elfradio.d31bootstrap;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class UsbStorageControl {
    private static final String TARGET_PREFIX = "/data/media/0/USB-";
    private static final String PUBLIC_PREFIX = "/storage/emulated/0/USB-";
    private static final String LEGACY_TARGET_PATH = "/data/media/0/USB";
    private static final String SOURCE_PREFIX = "/mnt/media_rw/";
    static final String VENDOR_USB_PROMPT_COMPONENT =
            "com.starnet.files/com.starnet.files.UsbMountNotifyActivity";
    private static final Pattern SAFE_UUID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private UsbStorageControl() {
    }

    static AdbControl.ActionResult ensureMappingFromIntent(Context context, Intent intent) {
        String uuid = uuidFromIntent(intent);
        return ensureMapping(uuid);
    }

    static AdbControl.ActionResult refreshAllMappings() {
        List<String> uuids = findMountedUuids();
        AdbControl.ActionResult legacy = AdbControl.executeRoot(
                "移除旧版USB存储入口", buildRemoveLegacyCommand());
        if (!legacy.succeeded) return legacy;
        if (uuids.isEmpty()) {
            return new AdbControl.ActionResult("当前没有已挂载的USB存储卷。\n", true);
        }
        StringBuilder log = new StringBuilder(legacy.log);
        for (String uuid : uuids) {
            AdbControl.ActionResult result = ensureMapping(uuid);
            log.append(result.log);
            if (!result.succeeded) return new AdbControl.ActionResult(log.toString(), false);
        }
        return new AdbControl.ActionResult(log.toString(), true);
    }

    static AdbControl.ActionResult ensureMapping(String uuid) {
        if (!isSafeUuid(uuid)) {
            return new AdbControl.ActionResult("没有找到可用的USB存储卷。\n", false);
        }
        if (!findMountedUuids().contains(uuid)) {
            return new AdbControl.ActionResult(
                    "卷" + uuid + "不是已挂载的可移动存储设备。\n", false);
        }
        String sourceDevice = sourceDevice(uuid);
        String targetDevice = targetDevice(uuid);
        StringBuilder log = new StringBuilder();
        boolean commandsSucceeded = true;

        if (sourceDevice.equals(targetDevice)) {
            return new AdbControl.ActionResult("映射已经存在且设备号匹配。\n映射回读=通过\n", true);
        }

        if (targetDevice != null) {
            AdbControl.ActionResult unmount = AdbControl.executeRoot(
                    "卸载失效的USB存储入口", buildUnmountCommand(uuid));
            log.append(unmount.log);
            commandsSucceeded &= unmount.succeeded && targetDevice(uuid) == null;
            if (targetDevice(uuid) != null) {
                log.append("旧映射回读=仍存在，停止重建\n");
                return new AdbControl.ActionResult(log.toString(), false);
            }
        }

        AdbControl.ActionResult steps = AdbControl.executeRootSequence(
                "建立USB存储入口", buildEnsureCommands(uuid));
        log.append(steps.log);
        commandsSucceeded &= steps.succeeded;
        boolean verified = mappingMatches(uuid);
        return new AdbControl.ActionResult(log
                + "映射回读=" + (verified ? "通过" : "失败") + "\n",
                commandsSucceeded && verified);
    }

    static AdbControl.ActionResult removeMapping(String uuid) {
        if (!isSafeUuid(uuid)) return removeAllMappings();
        if (targetDevice(uuid) == null) {
            return new AdbControl.ActionResult("USB存储入口已不存在，无需重复清理。\n", true);
        }
        return AdbControl.executeRoot("移除USB存储入口", buildRemoveCommand(uuid));
    }

    static AdbControl.ActionResult removeAllMappings() {
        return AdbControl.executeRoot("移除全部USB存储入口", buildRemoveAllCommand());
    }

    static AdbControl.ActionResult disableVendorUsbPrompt() {
        return AdbControl.executeRoot("禁用原厂U盘重复弹窗", buildDisableVendorPromptCommand());
    }

    static String uuidFromIntent(Intent intent) {
        if (intent == null) return null;
        Uri data = intent.getData();
        if (data == null) return null;
        String value = data.getLastPathSegment();
        return isSafeUuid(value) ? value : null;
    }

    static boolean isSafeUuid(String value) {
        return value != null && SAFE_UUID.matcher(value).matches();
    }

    static String publicPath(String uuid) {
        if (!isSafeUuid(uuid)) throw new IllegalArgumentException("不安全的USB卷标识");
        return PUBLIC_PREFIX + uuid;
    }

    static boolean isMountedUuid(String uuid) {
        return isSafeUuid(uuid) && findMountedUuids().contains(uuid);
    }

    static List<String> buildEnsureCommands(String uuid) {
        if (!isSafeUuid(uuid)) throw new IllegalArgumentException("不安全的USB卷标识");
        String source = SOURCE_PREFIX + uuid;
        String target = TARGET_PREFIX + uuid;
        List<String> commands = new ArrayList<>();
        commands.add("mkdir -p '" + target + "'");
        commands.add("chmod 0777 '" + target + "'");
        commands.add("mount -o bind '" + source + "' '" + target + "'");
        return commands;
    }

    static String buildUnmountCommand(String uuid) {
        if (!isSafeUuid(uuid)) throw new IllegalArgumentException("不安全的USB卷标识");
        return "umount '" + TARGET_PREFIX + uuid + "'";
    }

    static String buildRemoveCommand(String uuid) {
        if (!isSafeUuid(uuid)) throw new IllegalArgumentException("不安全的USB卷标识");
        return "TARGET='" + TARGET_PREFIX + uuid + "'; "
                + "if grep -q \" $TARGET \" /proc/mounts; then "
                + "umount \"$TARGET\" 2>/dev/null || umount -l \"$TARGET\" 2>/dev/null || exit 24; fi; "
                + "rmdir \"$TARGET\" 2>/dev/null || true";
    }

    static String buildRemoveAllCommand() {
        return "for TARGET in '" + TARGET_PREFIX + "'*; do "
                + "test -e \"$TARGET\" || continue; "
                + "if grep -q \" $TARGET \" /proc/mounts; then "
                + "umount \"$TARGET\" 2>/dev/null || umount -l \"$TARGET\" 2>/dev/null || exit 24; fi; "
                + "rmdir \"$TARGET\" 2>/dev/null || true; done";
    }

    static String buildRemoveLegacyCommand() {
        return "TARGET='" + LEGACY_TARGET_PATH + "'; "
                + "if grep -q \" $TARGET \" /proc/mounts; then "
                + "umount \"$TARGET\" 2>/dev/null || umount -l \"$TARGET\" 2>/dev/null || exit 24; fi; "
                + "rmdir \"$TARGET\" 2>/dev/null || true";
    }

    static String buildDisableVendorPromptCommand() {
        return "pm disable --user 0 " + VENDOR_USB_PROMPT_COMPONENT;
    }

    private static List<String> findMountedUuids() {
        List<String> uuids = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/mounts"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String uuid = publicUuidFromMountLine(line);
                if (isSafeUuid(uuid) && !uuids.contains(uuid)) uuids.add(uuid);
            }
        } catch (Throwable ignored) {
        }
        return uuids;
    }

    static String publicUuidFromMountLine(String line) {
        if (line == null) return null;
        String[] fields = line.split(" ");
        if (fields.length < 3 || !fields[0].startsWith("/dev/block/vold/public:")) return null;
        if (!fields[1].startsWith(SOURCE_PREFIX)) return null;
        String uuid = fields[1].substring(SOURCE_PREFIX.length());
        return isSafeUuid(uuid) ? uuid : null;
    }

    private static boolean mappingMatches(String uuid) {
        String sourceDevice = sourceDevice(uuid);
        String targetDevice = targetDevice(uuid);
        return sourceDevice != null && sourceDevice.equals(targetDevice);
    }

    private static String sourceDevice(String uuid) {
        return deviceForMountPath(SOURCE_PREFIX + uuid, true);
    }

    private static String targetDevice(String uuid) {
        return deviceForMountPath(TARGET_PREFIX + uuid, false);
    }

    private static String deviceForMountPath(String path, boolean requirePublicDevice) {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/mounts"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split(" ");
                if (fields.length < 3) continue;
                if (fields[1].equals(path)
                        && (!requirePublicDevice
                        || fields[0].startsWith("/dev/block/vold/public:"))) {
                    return fields[0];
                }
            }
        } catch (Throwable ignored) {
            return null;
        }
        return null;
    }

}
