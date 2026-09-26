/* 146专用离线故障注入：包含生产代码，在独立根目录中执行真实文件读写。 */
#define _GNU_SOURCE
#include <stdarg.h>
#include <sys/sysmacros.h>
#include <sys/wait.h>
#define main installer_main
#include "update_binary.c"
#undef main

#define CHECK(condition) do { if (!(condition)) { \
    fprintf(stderr, "断言失败：%s:%d: %s (errno=%d)\n", __FILE__, __LINE__, #condition, errno); \
    exit(1); } } while (0)

int __real_open(const char *, int, ...);
int __real_close(int);
int __real_fstat(int, struct stat *);
ssize_t __real_write(int, const void *, size_t);
ssize_t __real_read(int, void *, size_t);
int __real_fsync(int);
int __real_openat(int, const char *, int, ...);
int __real_fstatat(int, const char *, struct stat *, int);
int __real_unlinkat(int, const char *, int);

#define TEST_CACHE_BLOCK "/dev/block/platform/mtk-msdc.0/11230000.msdc0/by-name/cache"
static int fd_cache[1024], wrong_cache_device, fail_cache_unlink, fail_cache_fsync, wrong_cache_child;
static const char *package_argument = "/package.zip";

static const unsigned long long kib[24] = {
    3072, 5120, 10240, 10240, 512, 512, 16384, 16384, 8192, 10240, 10240, 512,
    2048, 6144, 8192, 5120, 5120, 1024, 32768, 35840, 1572864, 409600, 13200896, 16384
};
static int fd_partition[1024];
static uint64_t bytes_written[25], bytes_verified[25];
static int write_opens[25], flushed[25], data_done, touched, forbidden_writes;
static int fail_write, fail_flush, corrupt_after_flush, short_io, interrupted;
static int wrong_size, regular_block, wrong_rdev, fail_system_check, fail_data;
static int test_count;
static char events[128];
static size_t event_count;
static void event(char value) { CHECK(event_count + 1 < sizeof(events)); events[event_count++] = value; events[event_count] = 0; }

static int target(int part) { return part == 7 || part == 8 || part == 9 || part == 21 || part == 23; }

static int partition_for(const char *path) {
    int number = 0;
    if (strcmp(path, BOOT_BLOCK) == 0) return 7;
    if (strcmp(path, RECOVERY_BLOCK) == 0) return 8;
    if (strcmp(path, LOGO_BLOCK) == 0) return 9;
    if (strcmp(path, SYSTEM_BLOCK) == 0) return 21;
    if (strcmp(path, DATA_BLOCK) == 0) return 23;
    if (strcmp(path, TEST_CACHE_BLOCK) == 0) return 22;
    if (sscanf(path, "/dev/block/mmcblk0p%d", &number) == 1 && number >= 1 && number <= 24) return number;
    return 0;
}

int __wrap_open(const char *path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    int part = partition_for(path);
    if (strncmp(path, "/dev/block/", 11) == 0 && (flags & O_ACCMODE) != O_RDONLY) {
        if (!target(part)) { ++forbidden_writes; errno = EPERM; return -1; }
        ++write_opens[part];
        ++touched;
        if (part == 7) { CHECK(data_done); event('B'); }
        if (part == 8) {
            CHECK(data_done);
            CHECK(bytes_written[7] == 0 || (flushed[7] && bytes_verified[7] == EXPECTED_BOOT_SIZE));
            event('R');
        }
        if (part == 21) event('S');
        if (part == 9) event('L');
    }
    int fd = __real_open(path, flags, mode);
    CHECK(fd < (int)(sizeof(fd_partition) / sizeof(fd_partition[0])));
    if (fd >= 0) { fd_partition[fd] = part; fd_cache[fd] = strcmp(path, "/cache") == 0; }
    return fd;
}

int __wrap_close(int fd) {
    if (fd >= 0 && fd < 1024) { fd_partition[fd] = 0; fd_cache[fd] = 0; }
    return __real_close(fd);
}

int __wrap_fstat(int fd, struct stat *status) {
    int result = __real_fstat(fd, status);
    int part = fd_partition[fd];
    if (result == 0 && fd_cache[fd]) status->st_dev = makedev(179, wrong_cache_device ? 23 : 22);
    if (result == 0 && part) {
        status->st_mode = (status->st_mode & ~S_IFMT) | (regular_block == part ? S_IFREG : S_IFBLK);
        status->st_rdev = makedev(179, wrong_rdev == part ? part + 1 : part);
    }
    return result;
}

int __wrap_openat(int fd, const char *name, int flags, ...) {
    CHECK(fd_cache[fd] && (flags & O_ACCMODE) == O_RDONLY && !(flags & O_CREAT));
    int child = __real_openat(fd, name, flags);
    CHECK(child < 1024);
    if (child >= 0) { fd_partition[child] = 0; fd_cache[child] = 1; }
    return child;
}

int __wrap_fstatat(int fd, const char *name, struct stat *status, int flags) {
    CHECK(fd_cache[fd] && flags == AT_SYMLINK_NOFOLLOW);
    int result = __real_fstatat(fd, name, status, flags);
    if (result == 0) status->st_dev = makedev(179, wrong_cache_child && strcmp(name, "dalvik-cache") == 0 ? 23 : 22);
    return result;
}

int __wrap_unlinkat(int fd, const char *name, int flags) {
    CHECK(fd_cache[fd]);
    if (fail_cache_unlink) { errno = EACCES; return -1; }
    ++touched;
    return __real_unlinkat(fd, name, flags);
}

int __wrap_ioctl(int fd, unsigned long request, ...) {
    va_list ap;
    va_start(ap, request);
    uint64_t *size = va_arg(ap, uint64_t *);
    va_end(ap);
    struct stat status;
    CHECK(request == BLKGETSIZE64 && fd_partition[fd]);
    CHECK(__real_fstat(fd, &status) == 0);
    *size = (uint64_t)status.st_size + (wrong_size == fd_partition[fd] ? 512 : 0);
    return 0;
}

ssize_t __wrap_write(int fd, const void *data, size_t count) {
    int part = fd_partition[fd];
    if (part && fail_write == part && bytes_written[part] >= 4096) { errno = EIO; return -1; }
    if (part && fail_write == -part) return 0;
    if (part && short_io && !interrupted) { interrupted = 1; errno = EINTR; return -1; }
    if (part && (short_io || fail_write == part) && count > 4096) count = 4096;
    if (part == 21) {
        /* system使用真实gzip解压；零块变为稀疏文件，避免离线测试占用1.5GiB。 */
        const unsigned char *bytes = data;
        for (size_t i = 0; i < count; ++i) CHECK(bytes[i] == 0);
        CHECK(lseek(fd, (off_t)count, SEEK_CUR) >= 0);
        bytes_written[part] += count;
        return (ssize_t)count;
    }
    ssize_t result = __real_write(fd, data, count);
    if (result > 0 && part) bytes_written[part] += (uint64_t)result;
    return result;
}

ssize_t __wrap_read(int fd, void *data, size_t count) {
    int part = fd_partition[fd];
    if (part && short_io && count > 8191) count = 8191;
    ssize_t result = __real_read(fd, data, count);
    if (result > 0 && part && flushed[part]) bytes_verified[part] += (uint64_t)result;
    return result;
}

int __wrap_fsync(int fd) {
    if (fd_cache[fd] && fail_cache_fsync) { errno = EIO; return -1; }
    int part = fd_partition[fd];
    if (part && fail_flush == part) { errno = EIO; return -1; }
    int result = __real_fsync(fd);
    if (result == 0 && part) {
        flushed[part] = 1;
        if (part == 7) event('b');
        if (part == 8) event('r');
        if (corrupt_after_flush == part) {
            char path[80];
            unsigned char bad = 0xee;
            snprintf(path, sizeof(path), "/dev/block/mmcblk0p%d", part);
            int output = __real_open(path, O_WRONLY);
            CHECK(output >= 0 && pwrite(output, &bad, 1, (off_t)(kib[part - 1] * 1024 - 1)) == 1);
            CHECK(__real_fsync(output) == 0 && __real_close(output) == 0);
        }
    }
    return result;
}

int __wrap_mount(const char *source, const char *dest, const char *type, unsigned long flags, const void *data) {
    (void)type; (void)flags; (void)data;
    CHECK(strcmp(source, SYSTEM_BLOCK) == 0 || strcmp(source, DATA_BLOCK) == 0);
    ++touched;
    if (strcmp(dest, "/data") == 0 && fail_data) { errno = EIO; return -1; }
    return 0;
}
int __wrap_umount2(const char *path, int flags) { (void)path; (void)flags; ++touched; return 0; }
int __wrap_umount(const char *path) {
    if (strcmp(path, "/data") == 0) { data_done = 1; event('D'); }
    return 0;
}
void __wrap_sync(void) { }
int __wrap_reboot(int command) { (void)command; CHECK(0); return -1; }
ssize_t __wrap_lgetxattr(const char *path, const char *name, void *value, size_t size) {
    static const char label[] = "u:object_r:system_file:s0";
    (void)path;
    CHECK(strcmp(name, "security.selinux") == 0 && size >= sizeof(label));
    if (fail_system_check) { errno = EIO; return -1; }
    memcpy(value, label, sizeof(label));
    return sizeof(label);
}

static void make_parent(const char *path) {
    char parent[1024];
    CHECK(strlen(path) < sizeof(parent));
    strcpy(parent, path);
    char *slash = strrchr(parent, '/');
    CHECK(slash != NULL);
    *slash = 0;
    if (*parent) CHECK(mkdir_recursive(parent, 0755) == 0);
}

static void put_file(const char *path, const void *bytes, size_t size) {
    make_parent(path);
    int fd = __real_open(path, O_CREAT | O_TRUNC | O_WRONLY, 0644);
    CHECK(fd >= 0 && __real_write(fd, bytes, size) == (ssize_t)size && __real_close(fd) == 0);
}

static void put_number(const char *path, uint64_t value) {
    char text[64];
    int length = snprintf(text, sizeof(text), "%llu\n", (unsigned long long)value);
    put_file(path, text, (size_t)length);
}

static void layout(void) {
    uint64_t start = 2048;
    char path[128], contents[64];
    for (unsigned int i = 1; i <= 24; ++i) {
        snprintf(path, sizeof(path), "/sys/block/mmcblk0/mmcblk0p%u/partition", i); put_number(path, i);
        snprintf(path, sizeof(path), "/sys/block/mmcblk0/mmcblk0p%u/start", i); put_number(path, start);
        snprintf(path, sizeof(path), "/sys/block/mmcblk0/mmcblk0p%u/size", i); put_number(path, kib[i - 1] * 2);
        snprintf(path, sizeof(path), "/sys/block/mmcblk0/mmcblk0p%u/dev", i);
        int length = snprintf(contents, sizeof(contents), "179:%u\n", i);
        put_file(path, contents, (size_t)length);
        start += kib[i - 1] * 2;
    }
    put_number("/sys/block/mmcblk0/size", start + 2048);
}

static void properties(int old_build) {
    char contents[1024];
    int length = snprintf(contents, sizeof(contents),
        "ro.board.platform=mt6737t\nro.mediatek.platform=MT6737T\n"
        "ro.product.name=full_hct6737t_66_m0\nro.product.device=hct6735_66_m0\n"
        "ro.build.fingerprint=%s\nro.bootimage.build.fingerprint=%s\n",
        old_build ? EXPECTED_FINGERPRINT : "another-vendor-build",
        old_build ? EXPECTED_FINGERPRINT : "another-recovery-build");
    put_file("/default.prop", contents, (size_t)length);
}

static void store16(unsigned char *p, uint16_t value) { p[0] = value; p[1] = value >> 8; }
static void store32(unsigned char *p, uint32_t value) { store16(p, (uint16_t)value); store16(p + 2, (uint16_t)(value >> 16)); }
typedef struct { const char *name; uint32_t offset, size, crc; } FixtureEntry;
static FixtureEntry entries[64];
static size_t entry_count;

static void add_entry(int zip, const char *name, uint32_t size, int android, int source) {
    unsigned char header[30] = {0}, buffer[65536];
    uint32_t remaining = size;
    FixtureEntry *entry = &entries[entry_count++];
    entry->name = name; entry->size = size; entry->offset = (uint32_t)lseek(zip, 0, SEEK_CUR);
    entry->crc = crc32(0, Z_NULL, 0);
    store32(header, 0x04034b50); store16(header + 4, 20);
    store32(header + 18, size); store32(header + 22, size); store16(header + 26, (uint16_t)strlen(name));
    CHECK(write_all(zip, header, sizeof(header)) == 0 && write_all(zip, name, strlen(name)) == 0);
    while (remaining) {
        size_t count = remaining < sizeof(buffer) ? remaining : sizeof(buffer);
        memset(buffer, android ? 0x6a : 0, count);
        if (android && remaining == size) memcpy(buffer, "ANDROID!", 8);
        if (source >= 0) CHECK(read_all(source, buffer, count) == 0);
        entry->crc = crc32(entry->crc, buffer, (uInt)count);
        CHECK(write_all(zip, buffer, count) == 0);
        remaining -= (uint32_t)count;
    }
    store32(header + 14, entry->crc);
    CHECK(pwrite(zip, header, sizeof(header), entry->offset) == sizeof(header));
}

static void build_zip(void) {
    unsigned char zero[1024 * 1024] = {0};
    gzFile gzip = gzopen("/system.gz", "wb1");
    CHECK(gzip != NULL);
    for (uint64_t count = 0; count < EXPECTED_SYSTEM_SIZE; count += sizeof(zero))
        CHECK(gzwrite(gzip, zero, sizeof(zero)) == sizeof(zero));
    CHECK(gzclose(gzip) == Z_OK);
    int source = __real_open("/system.gz", O_RDONLY);
    struct stat status;
    CHECK(source >= 0 && __real_fstat(source, &status) == 0);
    int zip = __real_open("/base.zip", O_CREAT | O_TRUNC | O_RDWR, 0644);
    CHECK(zip >= 0);
    add_entry(zip, "payload/system.img.gz", (uint32_t)status.st_size, 0, source);
    CHECK(__real_close(source) == 0);
    add_entry(zip, "payload/logo.img", EXPECTED_LOGO_SIZE, 0, -1);
    for (size_t i = 0; i < sizeof(payload_files) / sizeof(payload_files[0]); ++i)
        add_entry(zip, payload_files[i].entry, 1, 0, -1);
    add_entry(zip, "payload/boot.img", EXPECTED_BOOT_SIZE, 1, -1);
    add_entry(zip, "payload/recovery.img", EXPECTED_RECOVERY_SIZE, 1, -1);
    uint32_t central = (uint32_t)lseek(zip, 0, SEEK_CUR);
    for (size_t i = 0; i < entry_count; ++i) {
        unsigned char header[46] = {0};
        store32(header, 0x02014b50); store16(header + 4, 20); store16(header + 6, 20);
        store32(header + 16, entries[i].crc); store32(header + 20, entries[i].size);
        store32(header + 24, entries[i].size); store16(header + 28, (uint16_t)strlen(entries[i].name));
        store32(header + 42, entries[i].offset);
        CHECK(write_all(zip, header, sizeof(header)) == 0 && write_all(zip, entries[i].name, strlen(entries[i].name)) == 0);
    }
    unsigned char end[22] = {0};
    store32(end, 0x06054b50); store16(end + 8, (uint16_t)entry_count); store16(end + 10, (uint16_t)entry_count);
    store32(end + 12, (uint32_t)lseek(zip, 0, SEEK_CUR) - central); store32(end + 16, central);
    CHECK(write_all(zip, end, sizeof(end)) == 0 && __real_close(zip) == 0);
}

static void seed_system(void) {
    for (size_t i = 0; i < sizeof(required_system_paths) / sizeof(required_system_paths[0]); ++i)
        put_file(required_system_paths[i], "", 0);
    const char *library = "/system/priv-app/D31ElfRemote/lib/arm/libjingle_peerconnection_so.so";
    unsigned char elf[20] = {0x7f, 'E', 'L', 'F', 1, 1}; elf[18] = 40;
    put_file(library, elf, sizeof(elf));
    CHECK(truncate(library, 6536680) == 0);
    static const char config[] = "org.mozilla.firefox org.telegram.messenger.web me.zhanghai.android.files";
    put_file("/system/vendor/starnet/launcher/config/config-tab", config, sizeof(config));
}

static void reset_case(void) {
    char path[128];
    properties(0); layout();
    CHECK(remove_tree("/data") == 0);
    put_file("/data/old-user-data", "keep", 4);
    CHECK(remove_tree("/cache") == 0);
    put_file("/cache/dalvik-cache/arm/stale.bin", "old", 3);
    put_file("/cache/app-cache/stale.bin", "old", 3);
    put_file("/cache/loose-file", "old", 3);
    put_file("/cache/.hidden-cache/stale.bin", "old", 3);
    put_file("/cache/recovery/log", "live-log", 8);
    put_file("/outside-cache/sentinel", "keep", 4);
    CHECK(symlink("/outside-cache", "/cache/cache-link") == 0);
    static const char mountinfo[] = "12 1 179:22 / /cache rw,nosuid,nodev - ext4 /dev/block/mmcblk0p22 rw\n";
    put_file("/proc/self/mountinfo", mountinfo, sizeof(mountinfo) - 1);
    for (int i = 1; i <= 24; ++i) {
        snprintf(path, sizeof(path), "/dev/block/mmcblk0p%d", i);
        if (target(i)) {
            put_file(path, "", 0);
            CHECK(truncate(path, (off_t)(kib[i - 1] * 1024)) == 0);
        } else {
            put_file(path, "untouched", 9);
            if (i == 22) CHECK(truncate(path, (off_t)(kib[i - 1] * 1024)) == 0);
        }
    }
    put_file("/dev/block/mmcblk0boot0", "untouched", 9);
    put_file("/dev/block/mmcblk0boot1", "untouched", 9);
    put_file("/dev/block/mmcblk0", "untouched", 9);
    int from = __real_open("/base.zip", O_RDONLY);
    int to = __real_open("/package.zip", O_CREAT | O_TRUNC | O_WRONLY, 0644);
    unsigned char buffer[65536]; ssize_t count;
    CHECK(from >= 0 && to >= 0);
    while ((count = __real_read(from, buffer, sizeof(buffer))) > 0) CHECK(__real_write(to, buffer, (size_t)count) == count);
    CHECK(count == 0 && __real_close(from) == 0 && __real_close(to) == 0);
    memset(fd_partition, 0, sizeof(fd_partition));
    memset(fd_cache, 0, sizeof(fd_cache));
    memset(bytes_written, 0, sizeof(bytes_written)); memset(bytes_verified, 0, sizeof(bytes_verified));
    memset(write_opens, 0, sizeof(write_opens)); memset(flushed, 0, sizeof(flushed));
    data_done = touched = forbidden_writes = 0; event_count = 0; events[0] = 0;
    fail_write = fail_flush = corrupt_after_flush = short_io = interrupted = 0;
    wrong_size = regular_block = wrong_rdev = fail_system_check = fail_data = 0;
    wrong_cache_device = fail_cache_unlink = fail_cache_fsync = wrong_cache_child = 0;
    package_argument = "/package.zip";
}

static void assert_protected(void) {
    char path[80], content[10];
    for (int i = 0; i <= 26; ++i) {
        if (target(i)) continue;
        if (i == 0) strcpy(path, "/dev/block/mmcblk0");
        else if (i > 24) snprintf(path, sizeof(path), "/dev/block/mmcblk0boot%d", i - 25);
        else snprintf(path, sizeof(path), "/dev/block/mmcblk0p%d", i);
        int fd = __real_open(path, O_RDONLY);
        CHECK(fd >= 0 && __real_read(fd, content, i == 22 ? 9 : sizeof(content)) == 9 && memcmp(content, "untouched", 9) == 0);
        CHECK(__real_close(fd) == 0);
    }
    CHECK(forbidden_writes == 0);
}

static int run_installer(void) {
    int ui = __real_open("/ui.txt", O_CREAT | O_TRUNC | O_RDWR, 0644);
    char fd_text[32]; snprintf(fd_text, sizeof(fd_text), "%d", ui);
    char *args[] = {"update-binary", "3", fd_text, (char *)package_argument, NULL};
    CHECK(ui >= 0);
    int result = installer_main(4, args);
    CHECK(lseek(ui, 0, SEEK_SET) == 0);
    char text[8192] = {0}; CHECK(__real_read(ui, text, sizeof(text) - 1) > 0);
    CHECK((strstr(text, "刷机完成") != NULL) == (result == 0));
    if (result == 24 || result == 25) CHECK(strstr(text, "勿重启") != NULL);
    CHECK(__real_close(ui) == 0);
    assert_protected();
    CHECK(file_contains("/cache/recovery/log", "live-log") == 0);
    CHECK(file_contains("/outside-cache/sentinel", "keep") == 0);
    if (result == 0) {
#ifdef D31_PACKAGE_146
        DIR *cache = opendir("/cache"); struct dirent *item;
        CHECK(cache != NULL);
        while ((item = readdir(cache)) != NULL)
            CHECK(strcmp(item->d_name, ".") == 0 || strcmp(item->d_name, "..") == 0 || strcmp(item->d_name, "recovery") == 0);
        CHECK(closedir(cache) == 0);
#else
        CHECK(file_contains("/cache/dalvik-cache/arm/stale.bin", "old") == 0);
#endif
    }
    return result;
}

static void expect(const char *name, int result, int untouched) {
    CHECK(run_installer() == result);
    if (untouched) {
        CHECK(touched == 0 && file_contains("/data/old-user-data", "keep") == 0);
        CHECK(file_contains("/cache/dalvik-cache/arm/stale.bin", "old") == 0);
    }
    printf("通过：%s；返回=%d；写入顺序=%s\n", name, result, *events ? events : "无");
    ++test_count;
}

static void set_identical(const char *name, const char *path) {
    ZipEntry entry;
    int zip = __real_open("/base.zip", O_RDONLY), block = __real_open(path, O_WRONLY);
    CHECK(zip >= 0 && block >= 0 && find_zip_entry(zip, name, &entry) == 0);
    CHECK(copy_stored_entry(zip, &entry, block) == 0);
    CHECK(__real_close(zip) == 0 && __real_close(block) == 0);
}

static void change_payload(const char *name, int kind) {
    ZipEntry entry;
    int fd = __real_open("/package.zip", O_RDWR);
    CHECK(fd >= 0 && find_zip_entry(fd, name, &entry) == 0);
    unsigned char byte = 0x55;
    if (kind == 0) CHECK(pwrite(fd, &byte, 1, (off_t)(entry.data_offset + entry.uncompressed_size - 1)) == 1);
    if (kind == 1) CHECK(pwrite(fd, &byte, 1, (off_t)entry.data_offset) == 1);
    if (kind == 2) CHECK(ftruncate(fd, (off_t)(entry.data_offset + entry.uncompressed_size - 1)) == 0);
    if (kind == 3) {
        unsigned char length[4]; store32(length, entry.compressed_size - 1);
        CHECK(pwrite(fd, length, 4, (off_t)(entry.data_offset - strlen(name) - 30 + 18)) == 4);
    }
    CHECK(__real_close(fd) == 0);
}

static void tests(void) {
    CHECK(mkdir_recursive("/dev", 0755) == 0 && mknod("/dev/null", S_IFCHR | 0666, makedev(1, 3)) == 0);
    const char *paths[] = {BOOT_BLOCK, RECOVERY_BLOCK, LOGO_BLOCK, SYSTEM_BLOCK, DATA_BLOCK, TEST_CACHE_BLOCK};
    const char *links[] = {"/dev/block/mmcblk0p7", "/dev/block/mmcblk0p8", "/dev/block/mmcblk0p9", "/dev/block/mmcblk0p21", "/dev/block/mmcblk0p23", "/dev/block/mmcblk0p22"};
    for (size_t i = 0; i < 6; ++i) { make_parent(paths[i]); CHECK(symlink(links[i], paths[i]) == 0); }
    seed_system(); build_zip();
#ifdef D31_PACKAGE_146
    reset_case(); expect("不同构建及不同旧镜像完整迁移", 0, 0);
    CHECK(strcmp(events, "SLDBbRr") == 0 && bytes_verified[7] == EXPECTED_BOOT_SIZE && bytes_verified[8] == EXPECTED_RECOVERY_SIZE);
    reset_case(); set_identical("payload/boot.img", BOOT_BLOCK); set_identical("payload/recovery.img", RECOVERY_BLOCK);
    expect("相同镜像跳过写入", 0, 0); CHECK(!write_opens[7] && !write_opens[8]);
    reset_case(); set_identical("payload/recovery.img", RECOVERY_BLOCK);
    expect("Recovery相同时仍迁移不同boot", 0, 0); CHECK(write_opens[7] == 1 && !write_opens[8]);
    for (int which = 0; which < 2; ++which) {
        const char *name = which ? "payload/recovery.img" : "payload/boot.img";
        reset_case(); change_payload(name, 0); expect(which ? "Recovery尾字节CRC损坏提前拒绝" : "boot尾字节CRC损坏提前拒绝", 13, 1);
        reset_case(); change_payload(name, 1); expect("Android头损坏提前拒绝", 13, 1);
    }
    reset_case(); change_payload("payload/recovery.img", 2); expect("截断载荷提前拒绝", 13, 1);
    reset_case(); change_payload("payload/recovery.img", 3); expect("存储长度不一致提前拒绝", 13, 1);
    reset_case(); put_file("/default.prop", "# ro.board.platform=mt6737t\n", 27); expect("注释不能冒充平台属性", 10, 1);
    reset_case(); int props = __real_open("/default.prop", O_APPEND | O_WRONLY);
    static const char duplicate[] = "ro.board.platform=mt6735\n";
    CHECK(__real_write(props, duplicate, sizeof(duplicate) - 1) == sizeof(duplicate) - 1 && __real_close(props) == 0);
    expect("冲突平台属性提前拒绝", 10, 1);
    reset_case(); CHECK(unlink(BOOT_BLOCK) == 0 && symlink("/dev/block/mmcblk0p8", BOOT_BLOCK) == 0);
    expect("等尺寸错误别名提前拒绝", 11, 1);
    CHECK(unlink(BOOT_BLOCK) == 0 && symlink("/dev/block/mmcblk0p7", BOOT_BLOCK) == 0);
    reset_case(); wrong_size = 7; expect("块设备尺寸错误提前拒绝", 11, 1);
    reset_case(); regular_block = 8; expect("普通文件不能冒充生产块设备", 11, 1);
    reset_case(); wrong_rdev = 7; expect("设备号与sysfs不一致提前拒绝", 11, 1);
    reset_case(); put_number("/sys/block/mmcblk0/mmcblk0p7/start", 2048); expect("boot侵入身份分区提前拒绝", 11, 1);
    reset_case(); put_number("/sys/block/mmcblk0/mmcblk0p8/start", 63488); expect("boot与Recovery重叠提前拒绝", 11, 1);
    reset_case(); put_number("/sys/block/mmcblk0/mmcblk0p23/start", UINT64_MAX - 1); expect("范围溢出提前拒绝", 11, 1);
    reset_case(); CHECK(unlink("/sys/block/mmcblk0/mmcblk0p2/start") == 0); expect("保护分区元数据缺失提前拒绝", 11, 1);
    reset_case(); CHECK(mkdir("/sys/block/mmcblk0/mmcblk0p25", 0755) == 0); expect("额外分区提前拒绝", 11, 1);
    CHECK(rmdir("/sys/block/mmcblk0/mmcblk0p25") == 0);
    reset_case(); fail_write = 21; expect("system失败不写boot及Recovery", 20, 0); CHECK(!write_opens[7] && !write_opens[8]);
    reset_case(); fail_system_check = 1; expect("system验收失败不写引导分区", 21, 0); CHECK(!write_opens[7] && !write_opens[8]);
    reset_case(); fail_flush = 9; expect("logo失败不写引导分区", 22, 0); CHECK(!write_opens[7] && !write_opens[8]);
    reset_case(); fail_data = 1; expect("data失败不写引导分区", 23, 0); CHECK(!write_opens[7] && !write_opens[8]);
    for (int part = 7; part <= 8; ++part) {
        reset_case(); fail_write = part; expect(part == 7 ? "boot部分写入失败" : "Recovery部分写入失败", part + 17, 0);
        CHECK(bytes_written[part] == 4096 && (part != 7 || !write_opens[8]));
        reset_case(); fail_flush = part; expect("fsync失败不能报告成功", part + 17, 0); CHECK(part != 7 || !write_opens[8]);
        reset_case(); corrupt_after_flush = part; expect("落盘末字节损坏被逐字回读发现", part + 17, 0); CHECK(part != 7 || !write_opens[8]);
    }
    reset_case(); fail_write = -7; expect("零字节写入失败不写Recovery", 24, 0); CHECK(!write_opens[8]);
    reset_case(); short_io = 1; expect("短读短写和EINTR可恢复", 0, 0); CHECK(interrupted);
    reset_case(); make_parent("/data/local/tmp/D31-factory-v1.4.6.zip");
    CHECK(rename("/package.zip", "/data/local/tmp/D31-factory-v1.4.6.zip") == 0);
    package_argument = "/data/local/tmp/D31-factory-v1.4.6.zip";
    expect("实际data安装通路清空data和旧cache且保留日志", 0, 0);
    CHECK(access(package_argument, F_OK) != 0 && errno == ENOENT);
    reset_case(); CHECK(rename("/package.zip", "/cache/install.zip") == 0); package_argument = "/cache/install.zip";
    expect("cache安装包改写前拒绝", 26, 1); CHECK(access(package_argument, F_OK) == 0);
    reset_case(); CHECK(rename("/package.zip", "/cache/recovery/install.zip") == 0);
    CHECK(symlink("/cache/recovery/install.zip", "/package.zip") == 0);
    expect("指向cache的包路径别名提前拒绝", 26, 1);
    CHECK(unlink("/package.zip") == 0);
    reset_case(); wrong_cache_device = 1; expect("cache目录设备号不符拒绝删除", 26, 1);
    reset_case(); put_file("/proc/self/mountinfo", "", 0); expect("未挂载cache拒绝删除", 26, 1);
    static const char *bad_mounts[] = {
        "12 1 179:23 / /cache rw - ext4 /dev/block/mmcblk0p23 rw\n",
        "12 1 179:22 /subtree /cache rw - ext4 /dev/block/mmcblk0p22 rw\n",
        "12 1 179:22 / /cache ro - ext4 /dev/block/mmcblk0p22 ro\n",
        "12 1 179:22 / /cache rw - ext4 /dev/block/mmcblk0p22 rw\n13 12 179:22 /subtree /cache/app-cache rw - ext4 /dev/block/mmcblk0p22 rw\n",
        "12 1 179:22 / /cache rw - ext4 /dev/block/mmcblk0p23 rw\n"
    };
    for (size_t i = 0; i < sizeof(bad_mounts) / sizeof(bad_mounts[0]); ++i) {
        reset_case(); put_file("/proc/self/mountinfo", bad_mounts[i], strlen(bad_mounts[i]));
        expect("错误来源或只读或bind或子挂载拒绝删除", 26, 1);
    }
    reset_case(); fail_cache_unlink = 1; expect("cache删除失败中止", 26, 1); CHECK(!write_opens[21] && !write_opens[7]);
    reset_case(); fail_cache_fsync = 1; expect("cache目录同步失败中止", 26, 0); CHECK(!write_opens[21] && !write_opens[7]);
    reset_case(); wrong_cache_child = 1; expect("cache子目录设备号不符中止", 26, 0); CHECK(!write_opens[21]);
#else
    reset_case(); expect("旧版仍拒绝不同Recovery指纹", 10, 1);
    reset_case(); properties(1); expect("旧版仍拒绝不同boot", 13, 1);
    reset_case(); properties(1); set_identical("payload/boot.img", BOOT_BLOCK);
    expect("旧版仍拒绝不同Recovery", 14, 1);
    reset_case(); properties(1); set_identical("payload/boot.img", BOOT_BLOCK); set_identical("payload/recovery.img", RECOVERY_BLOCK);
    expect("旧版相同镜像按原流程成功", 0, 0); CHECK(!write_opens[7] && !write_opens[8]);
#endif
    printf("全部通过：%d项；未写入任何非目标分区；未请求重启。\n", test_count);
}

int main(int argc, char **argv) {
    CHECK(argc == 2 && geteuid() == 0);
    CHECK(mkdir(argv[1], 0700) == 0);
    pid_t child = fork(); CHECK(child >= 0);
    if (child == 0) {
        CHECK(chroot(argv[1]) == 0 && chdir("/") == 0);
        tests();
        exit(0);
    }
    int status; CHECK(waitpid(child, &status, 0) == child);
    CHECK(remove_tree(argv[1]) == 0);
    return WIFEXITED(status) ? WEXITSTATUS(status) : 1;
}
