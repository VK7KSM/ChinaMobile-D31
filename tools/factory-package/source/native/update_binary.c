#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/fs.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mount.h>
#include <sys/stat.h>
#ifdef D31_PACKAGE_146
#include <sys/sysmacros.h>
#endif
#include <sys/types.h>
#include <sys/xattr.h>
#include <unistd.h>
#include <zlib.h>

#define SYSTEM_BLOCK "/dev/block/platform/mtk-msdc.0/11230000.msdc0/by-name/system"
#define BOOT_BLOCK "/dev/block/platform/mtk-msdc.0/11230000.msdc0/by-name/boot"
#define RECOVERY_BLOCK "/dev/block/platform/mtk-msdc.0/11230000.msdc0/by-name/recovery"
#define DATA_BLOCK "/dev/block/platform/mtk-msdc.0/11230000.msdc0/by-name/userdata"
#define LOGO_BLOCK "/dev/block/platform/mtk-msdc.0/11230000.msdc0/by-name/logo"
#ifdef D31_PACKAGE_146
#define CACHE_BLOCK "/dev/block/platform/mtk-msdc.0/11230000.msdc0/by-name/cache"
#define EXPECTED_CACHE_SIZE 419430400ULL
#endif
#define EXPECTED_LOGO_SIZE 8388608ULL
#define EXPECTED_SYSTEM_SIZE 1610612736ULL
#define EXPECTED_BOOT_SIZE 16777216ULL
#define EXPECTED_RECOVERY_SIZE 16777216ULL
#define EXPECTED_DATA_SIZE 13517717504ULL
#define EXPECTED_FINGERPRINT "alps/full_hct6737t_66_m0/hct6737t_66_m0:6.0/MRA58K/1583081804:userdebug/test-keys"

typedef struct {
    uint64_t data_offset;
    uint32_t compressed_size;
    uint32_t uncompressed_size;
    uint32_t crc32_value;
    uint16_t method;
    uint16_t flags;
} ZipEntry;

typedef struct {
    const char *entry;
    const char *destination;
    mode_t mode;
    uid_t uid;
    gid_t gid;
} PayloadFile;

static int output_fd = -1;

static const PayloadFile payload_files[] = {
    {"payload/apps/Firefox-142.0.apk", "/data/app/org.mozilla.firefox-2/base.apk", 0644, 1000, 1000},
    {"payload/apps/VLC-3.7.1.apk", "/data/app/org.videolan.vlc-1/base.apk", 0644, 1000, 1000},
    {"payload/apps/Zello-5.30.1.apk", "/data/app/com.loudtalks-1/base.apk", 0644, 1000, 1000},
    {"payload/apps/Telegram-12.10.1.apk", "/data/app/org.telegram.messenger.web-1/base.apk", 0644, 1000, 1000},
    {"payload/apps/Thunderbird-22.0.apk", "/data/app/net.thunderbird.android-1/base.apk", 0644, 1000, 1000},
    {"payload/apps/D31-File-Manager-1.7.4-d31.2.apk", "/data/app/me.zhanghai.android.files-2/base.apk", 0644, 1000, 1000},
    {"payload/apps/D31-Messages.apk", "/data/app/net.elfradio.d31phone.debug-1/base.apk", 0644, 1000, 1000},
    {"payload/apps/D31-Zello-Guard-0.2.7.apk", "/data/app/net.elfradio.d31zelloguard-2/base.apk", 0644, 1000, 1000},
    {"payload/system-patches/apply-home-patch.sh", "/data/local/d31-patches/apply-home-patch.sh", 0750, 0, 0},
    {"payload/system-patches/startup-handover.jar", "/data/local/d31-startup-handover/handover.jar", 0700, 0, 0},
    {"payload/system-patches/startup-handover.sh", "/data/local/d31-startup-handover/start.sh", 0700, 0, 0},
    {"payload/system-patches/startup-cellular-enabled", "/data/local/d31-startup-handover/cellular-enabled", 0600, 0, 0},
    {"payload/system-patches/recovery-volume-persistent.sh", "/data/local/d31-recovery-entry/persistent.sh", 0700, 0, 0},
    {"payload/system-patches/recovery-volume-watch", "/data/local/d31-recovery-entry/volume-watch", 0700, 0, 0},
    {"payload/system-patches/recovery-volume-gate.sh", "/data/local/d31-recovery-entry/gate.sh", 0700, 0, 0},
    {"payload/system-patches/recovery-volume-enabled", "/data/local/d31-recovery-entry/enabled", 0600, 0, 0},
    {"payload/system-patches/config-tab", "/data/local/d31-patches/config-tab", 0644, 0, 0},
    {"payload/system-patches/getnumber-cellular-labels-v1-unsigned.apk", "/data/local/d31-patches/getnumber-cellular-labels-v1-unsigned.apk", 0644, 0, 0},
    {"payload/system-patches/libvsip-tls12-dns-transport-v2.so", "/data/local/d31-patches/libvsip-tls12-dns-transport-v2.so", 0644, 0, 0},
    {"payload/system-patches/nexui-v8-ethernet-gate.apk", "/data/local/d31-patches/nexui-v8-ethernet-gate.apk", 0644, 0, 0},
    {"payload/apps/D31-System-Support-1.1.0.apk", "/data/app/net.elfradio.d31system-1/base.apk", 0644, 1000, 1000},
    {"payload/system-patches/support-guard", "/data/local/d31-system-support/guard", 0700, 0, 0},
    {"payload/system-patches/support-start.sh", "/data/local/d31-system-support/start.sh", 0700, 0, 0},
    {"payload/system-patches/support-state.jar", "/data/local/d31-system-support/state.jar", 0700, 0, 0},
    {"payload/system-patches/curtain.jar", "/data/local/d31-startup-curtain/curtain.jar", 0700, 0, 0},
    {"payload/system-patches/curtain-frame.jpg", "/data/local/d31-startup-curtain/frame.jpg", 0600, 0, 0},
    {"payload/system-patches/rescue-daemon.apk", "/data/local/d31-rescue/daemon.apk", 0700, 0, 0},
    {"payload/system-patches/rescue-start.sh", "/data/local/d31-rescue/start.sh", 0700, 0, 0},
    {"payload/system-patches/rescue-enabled", "/data/local/d31-rescue/enabled", 0600, 0, 0},
    {"payload/system-patches/init-package-restrictions.xml", "/data/local/d31-startup-handover/init-package-restrictions.xml", 0600, 0, 0},
    {"payload/system-patches/init-runtime-permissions.xml", "/data/local/d31-startup-handover/init-runtime-permissions.xml", 0600, 0, 0},
    {"payload/system-patches/factory-init-required", "/data/local/d31-startup-handover/factory-init-required", 0600, 0, 0},
    {"payload/system-patches/remote-updates-enabled", "/data/local/d31-remote/runtime/updates/enabled", 0600, 0, 0},
    {"payload/system-patches/imscc-firefox-telegram-messages-wrapper-v3.apk", "/data/local/d31-patches/imscc-firefox-telegram-messages-wrapper-v3.apk", 0644, 0, 0},
    {"payload/system-patches/tca8418.kl", "/data/system/devices/keylayout/tca8418.kl", 0644, 1000, 1000},
    {"payload/runtime/Thunderbird-22.0-arm64-interpret-only.odex", "/data/app/net.thunderbird.android-1/oat/arm64/base.odex", 0644, 1000, 39999},
    {"payload/runtime/Firefox-142.0-arm64.odex", "/data/app/org.mozilla.firefox-2/oat/arm64/base.odex", 0644, 1000, 39999},
    {"payload/runtime/VLC-3.7.1-arm64.odex", "/data/app/org.videolan.vlc-1/oat/arm64/base.odex", 0644, 1000, 39999},
    {"payload/runtime/Zello-5.30.1-arm64.odex", "/data/app/com.loudtalks-1/oat/arm64/base.odex", 0644, 1000, 39999},
    {"payload/runtime/Telegram-12.10.1-arm64.odex", "/data/app/org.telegram.messenger.web-1/oat/arm64/base.odex", 0644, 1000, 39999},
    {"payload/runtime/D31-File-Manager-1.7.4-d31.2-arm64.odex", "/data/app/me.zhanghai.android.files-2/oat/arm64/base.odex", 0644, 1000, 39999},
    {"payload/runtime/D31-Messages-arm64.odex", "/data/app/net.elfradio.d31phone.debug-1/oat/arm64/base.odex", 0644, 1000, 39999},
    {"payload/runtime/D31-Zello-Guard-0.2.7-arm64.odex", "/data/app/net.elfradio.d31zelloguard-2/oat/arm64/base.odex", 0644, 1000, 39999},
};

static const char *forbidden_system_paths[] = {
    "/system/vendor/3rd-app/android.apk",
    "/system/vendor/3rd-app/moffice.apk",
    "/system/app/BluetoothMidiService",
    "/system/app/QuickSearchBox",
    "/system/app/Exchange2",
    "/system/vendor/operator/app/Baidu_Location",
    "/system/app/MtkCalendar",
    "/system/priv-app/CalendarProvider",
    "/system/app/CalendarImporter",
    "/system/app/MtkBrowser",
    "/system/vendor/3rd-app/i-jetty.apk",
    "/system/app/HTMLViewer",
    "/system/app/Music",
    "/system/vendor/3rd-app/tr069.apk",
    "/system/vendor/3rd-app/tr069proxy.apk",
    "/system/vendor/3rd-app/emu.apk",
    "/system/vendor/3rd-app/daemon.apk",
    "/system/app/Omacp",
};

static const char *required_system_paths[] = {
    "/system/priv-app/D31ElfRemote/D31ElfRemote.apk",
    "/system/priv-app/D31ElfRemote/lib/arm/libjingle_peerconnection_so.so",
    "/system/bin/d31-elfremote-start",
    "/system/etc/d31-elfremote.system",
    "/system/vendor/3rd-app/nexui.apk",
    "/system/vendor/3rd-app/imscc.apk",
    "/system/vendor/3rd-app/dial.apk",
    "/system/vendor/3rd-app/vsdkpinyin.apk",
    "/system/app/Calculator",
    "/system/app/EngineerMode",
    "/system/app/MTKLogger",
    "/system/app/factory-test",
    "/system/vendor/3rd-app/screensaver.apk",
};

static uint16_t read_u16(const unsigned char *p) {
    return (uint16_t)p[0] | ((uint16_t)p[1] << 8);
}

static uint32_t read_u32(const unsigned char *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) |
           ((uint32_t)p[3] << 24);
}

static int write_all(int fd, const void *buffer, size_t length) {
    const unsigned char *cursor = (const unsigned char *)buffer;
    while (length > 0) {
        ssize_t count = write(fd, cursor, length);
        if (count < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (count == 0) return -1;
        cursor += count;
        length -= (size_t)count;
    }
    return 0;
}

static int read_all(int fd, void *buffer, size_t length) {
    unsigned char *cursor = (unsigned char *)buffer;
    while (length > 0) {
        ssize_t count = read(fd, cursor, length);
        if (count < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (count == 0) return -1;
        cursor += count;
        length -= (size_t)count;
    }
    return 0;
}

static void ui_print(const char *message) {
    static const char prefix[] = "ui_print ";
    static const char suffix[] = "\nui_print\n";
    if (output_fd < 0) return;
    write_all(output_fd, prefix, sizeof(prefix) - 1);
    write_all(output_fd, message, strlen(message));
    write_all(output_fd, suffix, sizeof(suffix) - 1);
}

static void ui_error(const char *step) {
    char message[256];
    snprintf(message, sizeof(message), "失败：%s，errno=%d (%s)", step, errno, strerror(errno));
    ui_print(message);
}

#ifndef D31_PACKAGE_146
static int block_size_matches(const char *path, uint64_t expected) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    uint64_t size = 0;
    if (fd < 0) return -1;
    if (ioctl(fd, BLKGETSIZE64, &size) != 0) {
        close(fd);
        return -1;
    }
    close(fd);
    return size == expected ? 0 : -1;
}
#endif

static int file_contains(const char *path, const char *needle) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    char buffer[16384];
    ssize_t count;
    if (fd < 0) return -1;
    count = read(fd, buffer, sizeof(buffer) - 1);
    close(fd);
    if (count < 0) return -1;
    buffer[count] = '\0';
    return strstr(buffer, needle) != NULL ? 0 : -1;
}

#ifdef D31_PACKAGE_146
static int property_equals(const char *key, const char *value) {
    FILE *file = fopen("/default.prop", "r");
    char line[1024];
    size_t key_length = strlen(key);
    int matches = 0, result = 0;
    if (file == NULL) return -1;
    while (fgets(line, sizeof(line), file) != NULL) {
        if (strchr(line, '\n') == NULL && !feof(file)) {
            result = -1;
            break;
        }
        line[strcspn(line, "\r\n")] = '\0';
        if (strncmp(line, key, key_length) != 0 || line[key_length] != '=') continue;
        if (++matches != 1 || strcmp(line + key_length + 1, value) != 0) result = -1;
    }
    if (ferror(file)) result = -1;
    fclose(file);
    return result == 0 && matches == 1 ? 0 : -1;
}

static int validate_platform_146(void) {
    /* 取自原厂Recovery属性；构建日期、版本及指纹均不参与硬件准入。 */
    return property_equals("ro.board.platform", "mt6737t") == 0 &&
           property_equals("ro.mediatek.platform", "MT6737T") == 0 &&
           property_equals("ro.product.name", "full_hct6737t_66_m0") == 0 &&
           property_equals("ro.product.device", "hct6735_66_m0") == 0 ? 0 : -1;
}

static int read_sysfs_number(const char *path, uint64_t *value) {
    FILE *file = fopen(path, "r");
    char buffer[64], *end;
    unsigned long long parsed;
    if (file == NULL) return -1;
    if (fgets(buffer, sizeof(buffer), file) == NULL || fgetc(file) != EOF || ferror(file)) {
        fclose(file);
        return -1;
    }
    fclose(file);
    if (buffer[0] < '0' || buffer[0] > '9') return -1;
    errno = 0;
    parsed = strtoull(buffer, &end, 10);
    if (errno != 0 || (*end != '\0' && strcmp(end, "\n") != 0)) return -1;
    *value = (uint64_t)parsed;
    return 0;
}

static int validate_layout_146(void) {
    static const struct {
        const char *path;
        unsigned int partition;
        uint64_t size;
    } targets[] = {
        {BOOT_BLOCK, 7, EXPECTED_BOOT_SIZE},
        {RECOVERY_BLOCK, 8, EXPECTED_RECOVERY_SIZE},
        {LOGO_BLOCK, 9, EXPECTED_LOGO_SIZE},
        {SYSTEM_BLOCK, 21, EXPECTED_SYSTEM_SIZE},
        {DATA_BLOCK, 23, EXPECTED_DATA_SIZE},
        {CACHE_BLOCK, 22, EXPECTED_CACHE_SIZE},
    };
    uint64_t starts[24], sizes[24], disk_size, number;
    char path[128], resolved[4096];
    unsigned int i, j, partitions = 0;
    struct dirent *item;
    DIR *directory;
    if (read_sysfs_number("/sys/block/mmcblk0/size", &disk_size) != 0) return -1;
    directory = opendir("/sys/block/mmcblk0");
    if (directory == NULL) return -1;
    while ((item = readdir(directory)) != NULL) {
        char *end;
        unsigned long index;
        if (strncmp(item->d_name, "mmcblk0p", 8) != 0) continue;
        errno = 0;
        index = strtoul(item->d_name + 8, &end, 10);
        if (errno != 0 || *end != '\0' || index < 1 || index > 24) {
            closedir(directory);
            return -1;
        }
        ++partitions;
    }
    closedir(directory);
    if (partitions != 24) return -1;
    for (i = 0; i < 24; ++i) {
        snprintf(path, sizeof(path), "/sys/block/mmcblk0/mmcblk0p%u/partition", i + 1);
        if (read_sysfs_number(path, &number) != 0 || number != i + 1) return -1;
        snprintf(path, sizeof(path), "/sys/block/mmcblk0/mmcblk0p%u/start", i + 1);
        if (read_sysfs_number(path, &starts[i]) != 0) return -1;
        snprintf(path, sizeof(path), "/sys/block/mmcblk0/mmcblk0p%u/size", i + 1);
        if (read_sysfs_number(path, &sizes[i]) != 0 || starts[i] == 0 || sizes[i] == 0 ||
            starts[i] >= disk_size || sizes[i] > disk_size - starts[i]) return -1;
        /* 包含所有非目标分区，防止目标范围侵入身份、校准或引导分区。 */
        for (j = 0; j < i; ++j) {
            if (starts[i] < starts[j] + sizes[j] && starts[j] < starts[i] + sizes[i]) return -1;
        }
    }
    for (i = 0; i < sizeof(targets) / sizeof(targets[0]); ++i) {
        struct stat status;
        FILE *file;
        unsigned int device_major, device_minor;
        int fd, fields;
        char trailing;
        uint64_t bytes = 0;
        snprintf(path, sizeof(path), "/dev/block/mmcblk0p%u", targets[i].partition);
        if (realpath(targets[i].path, resolved) == NULL || strcmp(path, resolved) != 0 ||
            sizes[targets[i].partition - 1] != targets[i].size / 512) return -1;
        snprintf(path, sizeof(path), "/sys/block/mmcblk0/mmcblk0p%u/dev", targets[i].partition);
        file = fopen(path, "r");
        if (file == NULL) return -1;
        fields = fscanf(file, "%u:%u %c", &device_major, &device_minor, &trailing);
        fclose(file);
        if (fields != 2) return -1;
        fd = open(targets[i].path, O_RDONLY | O_CLOEXEC);
        if (fd < 0) return -1;
        int valid = fstat(fd, &status) == 0 && S_ISBLK(status.st_mode) &&
                    major(status.st_rdev) == device_major && minor(status.st_rdev) == device_minor &&
                    ioctl(fd, BLKGETSIZE64, &bytes) == 0 && bytes == targets[i].size;
        close(fd);
        if (!valid) return -1;
    }
    return 0;
}
#endif

static int find_zip_entry(int zip_fd, const char *wanted, ZipEntry *entry) {
    unsigned char header[30];
    if (lseek(zip_fd, 0, SEEK_SET) < 0) return -1;
    for (;;) {
        uint32_t signature;
        uint16_t name_length;
        uint16_t extra_length;
        char name[512];
        if (read_all(zip_fd, header, 4) != 0) return -1;
        signature = read_u32(header);
        if (signature == 0x02014b50U || signature == 0x06054b50U) return -1;
        if (signature != 0x04034b50U) return -1;
        if (read_all(zip_fd, header + 4, sizeof(header) - 4) != 0) return -1;
        name_length = read_u16(header + 26);
        extra_length = read_u16(header + 28);
        if (name_length == 0 || name_length >= sizeof(name)) return -1;
        if (read_all(zip_fd, name, name_length) != 0) return -1;
        name[name_length] = '\0';
        if (lseek(zip_fd, extra_length, SEEK_CUR) < 0) return -1;
        if (strcmp(name, wanted) == 0) {
            entry->flags = read_u16(header + 6);
            entry->method = read_u16(header + 8);
            entry->crc32_value = read_u32(header + 14);
            entry->compressed_size = read_u32(header + 18);
            entry->uncompressed_size = read_u32(header + 22);
            entry->data_offset = (uint64_t)lseek(zip_fd, 0, SEEK_CUR);
            if ((entry->flags & 0x0009U) != 0 || entry->method != 0) return -1;
            return 0;
        }
        if (lseek(zip_fd, read_u32(header + 18), SEEK_CUR) < 0) return -1;
    }
}

static int validate_required_entries(int zip_fd) {
    ZipEntry entry;
    size_t index;
    if (find_zip_entry(zip_fd, "payload/system.img.gz", &entry) != 0) return -1;
    if (find_zip_entry(zip_fd, "payload/logo.img", &entry) != 0 ||
        entry.uncompressed_size != EXPECTED_LOGO_SIZE) return -1;
    if (find_zip_entry(zip_fd, "payload/boot.img", &entry) != 0 ||
        entry.uncompressed_size != EXPECTED_BOOT_SIZE) return -1;
    if (find_zip_entry(zip_fd, "payload/recovery.img", &entry) != 0 ||
        entry.uncompressed_size != EXPECTED_RECOVERY_SIZE) return -1;
    for (index = 0; index < sizeof(payload_files) / sizeof(payload_files[0]); ++index) {
        if (find_zip_entry(zip_fd, payload_files[index].entry, &entry) != 0 ||
            entry.uncompressed_size == 0) return -1;
    }
    return 0;
}

static int copy_stored_entry(int zip_fd, const ZipEntry *entry, int output) {
    unsigned char buffer[1024 * 1024];
    uint32_t remaining = entry->uncompressed_size;
    uLong crc = crc32(0L, Z_NULL, 0);
    if (lseek(zip_fd, (off_t)entry->data_offset, SEEK_SET) < 0) return -1;
    while (remaining > 0) {
        size_t chunk = remaining < sizeof(buffer) ? remaining : sizeof(buffer);
        if (read_all(zip_fd, buffer, chunk) != 0) return -1;
        crc = crc32(crc, buffer, (uInt)chunk);
        if (write_all(output, buffer, chunk) != 0) return -1;
        remaining -= (uint32_t)chunk;
    }
    return (uint32_t)crc == entry->crc32_value ? 0 : -1;
}

#ifdef D31_PACKAGE_146
static int validate_android_image_146(int zip_fd, const char *name, uint64_t size) {
    ZipEntry entry;
    unsigned char magic[8];
    int check, result;
    if (find_zip_entry(zip_fd, name, &entry) != 0 || entry.uncompressed_size != size ||
        entry.compressed_size != size ||
        lseek(zip_fd, (off_t)entry.data_offset, SEEK_SET) < 0 ||
        read_all(zip_fd, magic, sizeof(magic)) != 0 || memcmp(magic, "ANDROID!", 8) != 0) return -1;
    check = open("/dev/null", O_WRONLY | O_CLOEXEC);
    if (check < 0) return -1;
    result = copy_stored_entry(zip_fd, &entry, check);
    close(check);
    return result;
}
#endif

static int verify_stored_block(int zip_fd, const char *entry_name, const char *block,
                              uint64_t expected_size) {
    ZipEntry entry;
    int block_fd;
    uint64_t size = 0;
    unsigned char expected[65536], actual[65536];
    uint64_t remaining = expected_size;
    uLong crc = crc32(0L, Z_NULL, 0);
    if (find_zip_entry(zip_fd, entry_name, &entry) != 0 ||
        entry.uncompressed_size != expected_size) return -1;
    block_fd = open(block, O_RDONLY | O_CLOEXEC);
    if (block_fd < 0) return -1;
    if (ioctl(block_fd, BLKGETSIZE64, &size) != 0 || size != expected_size ||
        lseek(block_fd, 0, SEEK_SET) < 0 || lseek(zip_fd, (off_t)entry.data_offset, SEEK_SET) < 0) {
        close(block_fd);
        return -1;
    }
    while (remaining > 0) {
        size_t count = remaining < sizeof(expected) ? (size_t)remaining : sizeof(expected);
        if (read_all(zip_fd, expected, count) != 0 || read_all(block_fd, actual, count) != 0 ||
            memcmp(expected, actual, count) != 0) {
            close(block_fd);
            return -1;
        }
        crc = crc32(crc, expected, (uInt)count);
        remaining -= count;
    }
    close(block_fd);
    return (uint32_t)crc == entry.crc32_value ? 0 : -1;
}

static int flash_gzip_system(int zip_fd) {
    ZipEntry entry;
    int duplicate_fd;
    int block_fd;
    gzFile gzip_file;
    unsigned char buffer[1024 * 1024];
    uint64_t written = 0;
    int count;
    int close_result;
    if (find_zip_entry(zip_fd, "payload/system.img.gz", &entry) != 0) return -1;
    duplicate_fd = dup(zip_fd);
    if (duplicate_fd < 0 || lseek(duplicate_fd, (off_t)entry.data_offset, SEEK_SET) < 0) {
        if (duplicate_fd >= 0) close(duplicate_fd);
        return -1;
    }
    gzip_file = gzdopen(duplicate_fd, "rb");
    if (gzip_file == NULL) {
        close(duplicate_fd);
        return -1;
    }
    block_fd = open(SYSTEM_BLOCK, O_WRONLY | O_SYNC | O_CLOEXEC);
    if (block_fd < 0) {
        gzclose(gzip_file);
        return -1;
    }
    while ((count = gzread(gzip_file, buffer, sizeof(buffer))) > 0) {
        if (write_all(block_fd, buffer, (size_t)count) != 0) {
            close(block_fd);
            gzclose(gzip_file);
            return -1;
        }
        written += (uint64_t)count;
        if (written > EXPECTED_SYSTEM_SIZE) {
            close(block_fd);
            gzclose(gzip_file);
            return -1;
        }
    }
    close_result = gzclose(gzip_file);
    if (count < 0 || close_result != Z_OK || written != EXPECTED_SYSTEM_SIZE ||
        fsync(block_fd) != 0) {
        close(block_fd);
        return -1;
    }
    close(block_fd);
    return 0;
}

static int mkdir_recursive(const char *path, mode_t mode) {
    char copy[512];
    char *cursor;
    if (strlen(path) >= sizeof(copy)) return -1;
    strcpy(copy, path);
    for (cursor = copy + 1; *cursor != '\0'; ++cursor) {
        if (*cursor != '/') continue;
        *cursor = '\0';
        if (mkdir(copy, mode) != 0 && errno != EEXIST) return -1;
        *cursor = '/';
    }
    if (mkdir(copy, mode) != 0 && errno != EEXIST) return -1;
    return 0;
}

static int remove_tree(const char *path) {
    struct stat status;
    DIR *directory;
    struct dirent *item;
    char child[1024];
    if (lstat(path, &status) != 0) return errno == ENOENT ? 0 : -1;
    if (!S_ISDIR(status.st_mode) || S_ISLNK(status.st_mode)) return unlink(path);
    directory = opendir(path);
    if (directory == NULL) return -1;
    while ((item = readdir(directory)) != NULL) {
        if (strcmp(item->d_name, ".") == 0 || strcmp(item->d_name, "..") == 0) continue;
        if (snprintf(child, sizeof(child), "%s/%s", path, item->d_name) >= (int)sizeof(child)) {
            closedir(directory);
            return -1;
        }
        if (remove_tree(child) != 0) {
            closedir(directory);
            return -1;
        }
    }
    closedir(directory);
    return rmdir(path);
}

static int wipe_directory_contents(const char *path) {
    DIR *directory = opendir(path);
    struct dirent *item;
    char child[1024];
    if (directory == NULL) return -1;
    while ((item = readdir(directory)) != NULL) {
        if (strcmp(item->d_name, ".") == 0 || strcmp(item->d_name, "..") == 0) continue;
        if (snprintf(child, sizeof(child), "%s/%s", path, item->d_name) >= (int)sizeof(child)) {
            closedir(directory);
            return -1;
        }
        if (remove_tree(child) != 0) {
            closedir(directory);
            return -1;
        }
    }
    closedir(directory);
    return 0;
}

#ifdef D31_PACKAGE_146
static int open_verified_cache_146(int zip_fd, const char *package_path) {
    struct stat block_status, directory_status, zip_status;
    char resolved[4096], line[16384];
    int block_fd, cache_fd, matches = 0, valid = 1;
    FILE *mounts;
    if (realpath(package_path, resolved) == NULL || strcmp(resolved, "/cache") == 0 ||
        strncmp(resolved, "/cache/", 7) == 0) return -1;
    block_fd = open(CACHE_BLOCK, O_RDONLY | O_CLOEXEC);
    if (block_fd < 0) return -1;
    valid = fstat(block_fd, &block_status) == 0 && S_ISBLK(block_status.st_mode);
    close(block_fd);
    if (!valid || fstat(zip_fd, &zip_status) != 0 || zip_status.st_dev == block_status.st_rdev) return -1;
    cache_fd = open("/cache", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (cache_fd < 0) return -1;
    if (fstat(cache_fd, &directory_status) != 0 || directory_status.st_dev != block_status.st_rdev) {
        close(cache_fd);
        return -1;
    }
    mounts = fopen("/proc/self/mountinfo", "r");
    if (mounts == NULL) { close(cache_fd); return -1; }
    while (fgets(line, sizeof(line), mounts) != NULL) {
        unsigned int device_major, device_minor;
        char root[4096], point[4096], options[256], type[64], source[4096];
        char *separator;
        if (strchr(line, '\n') == NULL ||
            sscanf(line, "%*u %*u %u:%u %4095s %4095s %255s", &device_major, &device_minor,
                   root, point, options) != 5) { valid = 0; break; }
        /* 禁止子挂载及bind子树，删除范围必须完全落在cache文件系统内。 */
        if (strncmp(point, "/cache/", 7) == 0) { valid = 0; break; }
        if (strcmp(point, "/cache") != 0) continue;
        separator = strstr(line, " - ");
        if (++matches != 1 || strcmp(root, "/") != 0 ||
            makedev(device_major, device_minor) != block_status.st_rdev ||
            (strcmp(options, "rw") != 0 && strncmp(options, "rw,", 3) != 0) ||
            separator == NULL || sscanf(separator + 3, "%63s %4095s", type, source) != 2 ||
            strcmp(type, "ext4") != 0 || realpath(source, resolved) == NULL ||
            strcmp(resolved, "/dev/block/mmcblk0p22") != 0) { valid = 0; break; }
    }
    if (ferror(mounts)) valid = 0;
    fclose(mounts);
    if (!valid || matches != 1) { close(cache_fd); return -1; }
    return cache_fd;
}

static int clear_cache_directory_146(int fd, dev_t device, int top_level) {
    int copy = dup(fd);
    DIR *directory;
    struct dirent *item;
    int result = 0;
    if (copy < 0) return -1;
    directory = fdopendir(copy);
    if (directory == NULL) { close(copy); return -1; }
    for (;;) {
        struct stat status;
        errno = 0;
        item = readdir(directory);
        if (item == NULL) { if (errno != 0) result = -1; break; }
        if (strcmp(item->d_name, ".") == 0 || strcmp(item->d_name, "..") == 0) continue;
        if (fstatat(fd, item->d_name, &status, AT_SYMLINK_NOFOLLOW) != 0 || status.st_dev != device) {
            result = -1; break;
        }
        if (top_level && strcmp(item->d_name, "recovery") == 0) {
            if (!S_ISDIR(status.st_mode)) { result = -1; break; }
            continue;
        }
        if (S_ISDIR(status.st_mode)) {
            int child = openat(fd, item->d_name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
            if (child < 0) { result = -1; break; }
            struct stat opened;
            int removed = fstat(child, &opened) == 0 && opened.st_dev == device &&
                          opened.st_ino == status.st_ino && clear_cache_directory_146(child, device, 0) == 0;
            if (close(child) != 0) removed = 0;
            if (!removed || unlinkat(fd, item->d_name, AT_REMOVEDIR) != 0) { result = -1; break; }
        } else if (unlinkat(fd, item->d_name, 0) != 0) { result = -1; break; }
    }
    if (closedir(directory) != 0) result = -1;
    if (result == 0 && fsync(fd) != 0) result = -1;
    return result;
}

static int clear_cache_146(int zip_fd, const char *package_path) {
    int fd = open_verified_cache_146(zip_fd, package_path);
    struct stat status;
    if (fd < 0) return -1;
    int result = fstat(fd, &status) == 0 ? clear_cache_directory_146(fd, status.st_dev, 1) : -1;
    if (close(fd) != 0) result = -1;
    return result;
}
#endif

static int mount_partition(const char *block, const char *mount_point, unsigned long flags) {
    mkdir(mount_point, 0755);
    if (mount(block, mount_point, "ext4", flags, "") == 0) return 0;
    return errno == EBUSY ? 0 : -1;
}

static int extract_payload_file(int zip_fd, const PayloadFile *payload) {
    ZipEntry entry;
    char parent[512];
    char *slash;
    int fd;
    if (find_zip_entry(zip_fd, payload->entry, &entry) != 0) return -1;
    if (strlen(payload->destination) >= sizeof(parent)) return -1;
    strcpy(parent, payload->destination);
    slash = strrchr(parent, '/');
    if (slash == NULL) return -1;
    *slash = '\0';
    if (mkdir_recursive(parent, 0755) != 0) return -1;
    if (strncmp(payload->destination, "/data/app/", 10) == 0 &&
        chown(parent, 1000, 1000) != 0) return -1;
    fd = open(payload->destination, O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC, payload->mode);
    if (fd < 0) return -1;
    if (copy_stored_entry(zip_fd, &entry, fd) != 0 || fsync(fd) != 0) {
        close(fd);
        unlink(payload->destination);
        return -1;
    }
    close(fd);
    if (chmod(payload->destination, payload->mode) != 0 ||
        chown(payload->destination, payload->uid, payload->gid) != 0) return -1;
    if (strncmp(payload->destination, "/data/app/", 10) == 0 && strstr(parent, "/oat/") != NULL) {
        if (chown(parent, 1000, 1012) != 0 || chmod(parent, 0771) != 0) return -1;
        slash = strrchr(parent, '/');
        if (slash == NULL) return -1;
        *slash = '\0';
        if (chown(parent, 1000, 1012) != 0 || chmod(parent, 0771) != 0) return -1;
    }
    return 0;
}

static int flash_stored_block(int zip_fd, const char *name, const char *block,
                              uint64_t size, int skip_identical) {
    ZipEntry entry;
    if (find_zip_entry(zip_fd, name, &entry) != 0 || entry.uncompressed_size != size) return -1;
    int check = open("/dev/null", O_WRONLY | O_CLOEXEC);
    if (check < 0) return -1;
    int result = copy_stored_entry(zip_fd, &entry, check);
    close(check);
    if (result != 0) return -1;
    if (skip_identical && verify_stored_block(zip_fd, name, block, size) == 0) return 0;
    int fd = open(block, O_WRONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    result = copy_stored_entry(zip_fd, &entry, fd);
    if (fsync(fd) != 0) result = -1;
#ifdef D31_PACKAGE_146
    if (close(fd) != 0) result = -1;
#else
    close(fd);
#endif
    return result == 0 ? verify_stored_block(zip_fd, name, block, size) : -1;
}

static int flash_logo(int zip_fd) {
    return flash_stored_block(zip_fd, "payload/logo.img", LOGO_BLOCK, EXPECTED_LOGO_SIZE, 0);
}

static int prepare_clean_data(int zip_fd) {
    size_t index;
    umount2("/data", MNT_DETACH);
    if (mount_partition(DATA_BLOCK, "/data", MS_NOATIME | MS_NOSUID | MS_NODEV) != 0) return -1;
    if (wipe_directory_contents("/data") != 0) return -1;
    if (mkdir_recursive("/data/app", 0771) != 0 || chown("/data/app", 1000, 1000) != 0) return -1;
    if (mkdir_recursive("/data/local/d31-patches", 0755) != 0) return -1;
    if (mkdir_recursive("/data/local/snSudoSerialize", 0777) != 0 || chmod("/data/local/snSudoSerialize", 0777) != 0) return -1;
    if (mkdir_recursive("/data/system/users/0", 0771) != 0 ||
        chown("/data/system/users", 1000, 1000) != 0 ||
        chown("/data/system/users/0", 1000, 1000) != 0) return -1;
    if (mkdir_recursive("/data/system/devices/keylayout", 0755) != 0 ||
        chown("/data/system", 1000, 1000) != 0 ||
        chown("/data/system/devices", 1000, 1000) != 0 ||
        chown("/data/system/devices/keylayout", 1000, 1000) != 0) return -1;
    if (mkdir_recursive("/data/local/d31-remote/runtime/updates", 0700) != 0 ||
        chmod("/data/local/d31-remote", 0700) != 0 ||
        chown("/data/local/d31-remote", 0, 0) != 0 ||
        chmod("/data/local/d31-remote/runtime", 0700) != 0 ||
        chown("/data/local/d31-remote/runtime", 0, 0) != 0 ||
        chmod("/data/local/d31-remote/runtime/updates", 0700) != 0 ||
        chown("/data/local/d31-remote/runtime/updates", 0, 0) != 0) return -1;
    for (index = 0; index < sizeof(payload_files) / sizeof(payload_files[0]); ++index) {
        if (extract_payload_file(zip_fd, &payload_files[index]) != 0) return -1;
    }
    if (mkdir_recursive("/data/local/d31-startup-handover/runs", 0700) != 0 ||
        mkdir_recursive("/data/local/d31-recovery-entry/runs", 0700) != 0 ||
        chmod("/data/local/d31-startup-handover", 0700) != 0 ||
        chmod("/data/local/d31-recovery-entry", 0700) != 0) return -1;
    if (chmod("/data/local/d31-rescue", 0700) != 0 ||
        chmod("/data/local/d31-system-support", 0700) != 0 ||
        chmod("/data/local/d31-startup-curtain", 0700) != 0) return -1;
    if (chown("/data/app/net.thunderbird.android-1/oat", 1000, 1012) != 0 ||
        chmod("/data/app/net.thunderbird.android-1/oat", 0771) != 0 ||
        chown("/data/app/net.thunderbird.android-1/oat/arm64", 1000, 1012) != 0 ||
        chmod("/data/app/net.thunderbird.android-1/oat/arm64", 0771) != 0) return -1;
    mkdir("/data/lost+found", 0700);
    sync();
    if (umount("/data") != 0) return -1;
    return 0;
}

static int verify_remote_native_layout(void) {
    static const char *directories[] = {
        "/system/priv-app/D31ElfRemote",
        "/system/priv-app/D31ElfRemote/lib",
        "/system/priv-app/D31ElfRemote/lib/arm"
    };
    static const char library[] = "/system/priv-app/D31ElfRemote/lib/arm/libjingle_peerconnection_so.so";
    static const char label[] = "u:object_r:system_file:s0";
    struct stat status;
    char actual_label[64];
    unsigned char elf[20];
    size_t index;
    int fd;
    for (index = 0; index <= sizeof(directories) / sizeof(directories[0]); ++index) {
        int is_file = index == sizeof(directories) / sizeof(directories[0]);
        const char *path = is_file ? library : directories[index];
        if (lstat(path, &status) != 0 || status.st_uid != 0 || status.st_gid != 0 ||
            (status.st_mode & 07777) != (is_file ? 0644 : 0755) ||
            (is_file ? !S_ISREG(status.st_mode) : !S_ISDIR(status.st_mode))) return -1;
        if (lgetxattr(path, "security.selinux", actual_label, sizeof(actual_label)) != sizeof(label) ||
            memcmp(actual_label, label, sizeof(label)) != 0) return -1;
        if (is_file && status.st_size != 6536680) return -1;
    }
    errno = 0;
    if (lstat("/system/priv-app/D31ElfRemote/lib/arm64", &status) == 0 || errno != ENOENT) return -1;
    fd = open(library, O_RDONLY | O_NOFOLLOW);
    if (fd < 0) return -1;
    int result = read_all(fd, elf, sizeof(elf));
    if (close(fd) != 0) return -1;
    if (result != 0 || memcmp(elf, "\177ELF\001\001", 6) != 0 || read_u16(elf + 18) != 40) return -1;
    return 0;
}

static int verify_clean_system_image(void) {
    size_t index;
    struct stat status;
    int result = 0;
    umount2("/system", MNT_DETACH);
    if (mount_partition(SYSTEM_BLOCK, "/system", MS_RDONLY | MS_NOATIME) != 0) return -1;
    for (index = 0; index < sizeof(forbidden_system_paths) / sizeof(forbidden_system_paths[0]); ++index) {
        errno = 0;
        if (lstat(forbidden_system_paths[index], &status) == 0 || errno != ENOENT) {
            result = -1;
            break;
        }
    }
    if (result == 0) {
        for (index = 0; index < sizeof(required_system_paths) / sizeof(required_system_paths[0]); ++index) {
            if (lstat(required_system_paths[index], &status) != 0) {
                result = -1;
                break;
            }
        }
    }
    if (result == 0 && verify_remote_native_layout() != 0) result = -1;
    if (result == 0 &&
        (file_contains("/system/vendor/starnet/launcher/config/config-tab", "org.mozilla.firefox") != 0 ||
         file_contains("/system/vendor/starnet/launcher/config/config-tab", "org.telegram.messenger.web") != 0 ||
         file_contains("/system/vendor/starnet/launcher/config/config-tab", "me.zhanghai.android.files") != 0)) {
        result = -1;
    }
    if (umount("/system") != 0) return -1;
    return result;
}

int main(int argc, char **argv) {
    char *end = NULL;
    long parsed_fd;
    int zip_fd = -1;
    if (argc == 2 && strcmp(argv[1], "--self-test") == 0) {
        puts("D31 update-binary self-test: PASS");
        printf("system=%llu boot=%llu userdata=%llu payloads=%zu forbidden=%zu required=%zu\n",
               (unsigned long long)EXPECTED_SYSTEM_SIZE,
               (unsigned long long)EXPECTED_BOOT_SIZE,
               (unsigned long long)EXPECTED_DATA_SIZE,
               sizeof(payload_files) / sizeof(payload_files[0]),
               sizeof(forbidden_system_paths) / sizeof(forbidden_system_paths[0]),
               sizeof(required_system_paths) / sizeof(required_system_paths[0]));
        return 0;
    }
    if (argc != 4) return 2;
    errno = 0;
    parsed_fd = strtol(argv[2], &end, 10);
    if (errno != 0 || end == argv[2] || *end != '\0' || parsed_fd < 0) return 3;
    output_fd = (int)parsed_fd;

#if defined(D31_PACKAGE_146)
    ui_print("D31完整刷机包 v1.4.6");
#elif defined(D31_PACKAGE_145)
    ui_print("D31完整刷机包 v1.4.5");
#else
    ui_print("D31完整刷机包 v1.4.4");
#endif
    ui_print("将清除全部用户数据、账号和软件配置");
#ifdef D31_PACKAGE_146
    ui_print("将迁移boot和Recovery；不写入身份、校准、NVRAM、preloader、lk或分区表");
    if (validate_platform_146() != 0) {
        ui_print("拒绝：不是已批准的MT6737T D31平台");
        return 10;
    }
    if (validate_layout_146() != 0) {
        ui_print("拒绝：块设备映射、分区尺寸或非重叠检查失败");
        return 11;
    }
#else
    ui_print("不会写入boot、Recovery、设备身份、校准或NVRAM分区");

    if (file_contains("/default.prop", EXPECTED_FINGERPRINT) != 0) {
        ui_print("拒绝：Recovery构建指纹不匹配");
        return 10;
    }
    if (block_size_matches(SYSTEM_BLOCK, EXPECTED_SYSTEM_SIZE) != 0 ||
        block_size_matches(BOOT_BLOCK, EXPECTED_BOOT_SIZE) != 0 ||
        block_size_matches(RECOVERY_BLOCK, EXPECTED_RECOVERY_SIZE) != 0 ||
        block_size_matches(DATA_BLOCK, EXPECTED_DATA_SIZE) != 0 ||
        block_size_matches(LOGO_BLOCK, EXPECTED_LOGO_SIZE) != 0) {
        ui_print("拒绝：目标分区尺寸不匹配");
        return 11;
    }
#endif
    zip_fd = open(argv[3], O_RDONLY | O_CLOEXEC);
    if (zip_fd < 0 || validate_required_entries(zip_fd) != 0) {
        ui_error("刷机包项目预检");
        if (zip_fd >= 0) close(zip_fd);
        return 12;
    }

#ifdef D31_PACKAGE_146
    /* 两个镜像均完整预读成功后，才允许开始任何分区或用户数据改写。 */
    if (validate_android_image_146(zip_fd, "payload/boot.img", EXPECTED_BOOT_SIZE) != 0 ||
        validate_android_image_146(zip_fd, "payload/recovery.img", EXPECTED_RECOVERY_SIZE) != 0) {
        ui_print("拒绝：boot或Recovery载荷长度、Android头或完整CRC校验失败");
        close(zip_fd);
        return 13;
    }
    if (clear_cache_146(zip_fd, argv[3]) != 0) {
        ui_error("核验或清除cache失败；需真实可写cache挂载，安装包不得位于cache");
        close(zip_fd);
        return 26;
    }
    ui_print("已清除cache旧缓存，仅保留recovery日志目录；未格式化分区");
#else
    if (verify_stored_block(zip_fd, "payload/boot.img", BOOT_BLOCK, EXPECTED_BOOT_SIZE) != 0) {
        ui_print("拒绝：现有boot与基线不一致；本包不修复或写入boot");
        close(zip_fd);
        return 13;
    }
    ui_print("boot逐字校验通过，仅保留原件，不写入");
    if (verify_stored_block(zip_fd, "payload/recovery.img", RECOVERY_BLOCK, EXPECTED_RECOVERY_SIZE) != 0) {
        ui_print("拒绝：现有Recovery与基线不一致；本包不修复或写入Recovery");
        close(zip_fd);
        return 14;
    }
    ui_print("Recovery逐字校验通过，仅保留原件，不写入");
#endif
    ui_print("预检通过，开始写入system分区");
    umount2("/system", MNT_DETACH);
    if (flash_gzip_system(zip_fd) != 0) {
        ui_error("写入system分区");
        close(zip_fd);
        return 20;
    }
    ui_print("system分区写入完成");

    if (verify_clean_system_image() != 0) {
        ui_error("核验物理精简system镜像");
        close(zip_fd);
        return 21;
    }
    ui_print("物理精简system镜像核验通过");
    if (flash_logo(zip_fd) != 0) {
        ui_error("更新开机图片分区");
        close(zip_fd);
        return 22;
    }

    ui_print("开始清除userdata并安装无配置软件");
    if (prepare_clean_data(zip_fd) != 0) {
        ui_error("清除userdata或安装预装软件");
        close(zip_fd);
        return 23;
    }

#ifdef D31_PACKAGE_146
    if (flash_stored_block(zip_fd, "payload/boot.img", BOOT_BLOCK, EXPECTED_BOOT_SIZE, 1) != 0) {
        ui_error("写入或回读boot失败；请留在Recovery使用电脑备份恢复，勿重启系统");
        close(zip_fd);
        return 24;
    }
    ui_print("boot逐字回读通过");
    if (flash_stored_block(zip_fd, "payload/recovery.img", RECOVERY_BLOCK, EXPECTED_RECOVERY_SIZE, 1) != 0) {
        ui_error("写入或回读Recovery失败；请留在当前Recovery修复，勿重启");
        close(zip_fd);
        return 25;
    }
    ui_print("Recovery逐字回读通过");
#endif
    close(zip_fd);
    sync();
    ui_print("刷机完成：所有软件均为未配置状态");
    ui_print("首次启动会重新优化应用，耗时可能较长");
    return 0;
}
