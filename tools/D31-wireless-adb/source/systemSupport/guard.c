#define _GNU_SOURCE
#include <errno.h>
#include <dirent.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/file.h>
#include <sys/inotify.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>
#include <time.h>
#include <stdint.h>
#include <sys/xattr.h>
#include <sys/socket.h>
#include <sys/un.h>

#define ROOT "/data/local/d31-system-support"
#define APP "/data/data/net.elfradio.d31system"
#define SOCKET "/data/.snSudoSocket"
#include "local_actions.h"

struct acl_entry { uint16_t tag, perm; uint32_t id; };
struct socket_acl { uint32_t version; struct acl_entry entries[5]; };

static int grant_system(const char *path) {
    const struct socket_acl wanted = {2, {
        {1, 6, UINT32_MAX}, {2, 6, 1000}, {4, 6, UINT32_MAX},
        {16, 6, UINT32_MAX}, {32, 0, UINT32_MAX}
    }};
    struct socket_acl actual;
    ssize_t n = lgetxattr(path, "system.posix_acl_access", &actual, sizeof actual);
    if (n == sizeof wanted && !memcmp(&actual, &wanted, sizeof wanted)) return 1;
    return lsetxattr(path, "system.posix_acl_access", &wanted, sizeof wanted, 0) == 0;
}

static int test_acl(void) {
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    snprintf(address.sun_path, sizeof address.sun_path, ROOT "/acl-test-%d", getpid());
    int fd = socket(AF_UNIX, SOCK_DGRAM, 0);
    if (fd < 0) return 20;
    if (bind(fd, (struct sockaddr *)&address, sizeof address)) { close(fd); return 21; }
    int ok = grant_system(address.sun_path);
    if (!ok) perror("socket ACL");
    unlink(address.sun_path); close(fd);
    return ok ? 0 : 22;
}

static int reconcile(void) {
    struct stat app, socket, nexui;
    if (stat(APP, &app) || !S_ISDIR(app.st_mode) || app.st_uid < 10000) return 0;
    if (stat("/data/data/com.starnet.nexui", &nexui) || nexui.st_uid < 10000) return 0;
    if (lstat(SOCKET, &socket) || !S_ISSOCK(socket.st_mode)) return 0;
    if ((socket.st_uid != nexui.st_uid || socket.st_gid != 1000)
            && chown(SOCKET, nexui.st_uid, 1000)) return 0;
    if ((socket.st_mode & 0777) != 0660 && chmod(SOCKET, 0660)) return 0;
    return 1;
}

static int app_running(void) {
    DIR *proc = opendir("/proc");
    if (!proc) return 0;
    struct dirent *entry;
    int found = 0;
    while ((entry = readdir(proc)) != NULL) {
        if (entry->d_name[0] < '0' || entry->d_name[0] > '9') continue;
        char path[320], name[128] = {0};
        snprintf(path, sizeof path, "/proc/%s/cmdline", entry->d_name);
        int fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd < 0) continue;
        ssize_t length = read(fd, name, sizeof name - 1);
        close(fd);
        if (length > 0 && strcmp(name, "net.elfradio.d31system") == 0) { found = 1; break; }
    }
    closedir(proc);
    return found;
}

int main(int argc, char **argv) {
    if (getuid() != 0) return 10;
    if (argc == 2 && strcmp(argv[1], "--test-acl") == 0) return test_acl();
    if (argc == 2 && strcmp(argv[1], "--check-no-acl") == 0) {
        char acl[256];
        ssize_t n = lgetxattr(SOCKET, "system.posix_acl_access", acl, sizeof acl);
        return n < 0 && errno == ENODATA ? 0 : 23;
    }
    if (argc == 2 && strcmp(argv[1], "--remove-acl") == 0) {
        return lremovexattr(SOCKET, "system.posix_acl_access") == 0 || errno == ENODATA ? 0 : 24;
    }
    int lock = open(ROOT "/guard.lock", O_CREAT | O_RDWR | O_CLOEXEC, 0600);
    if (lock < 0 || flock(lock, LOCK_EX | LOCK_NB)) return 11;
    pid_t broker = fork();
    if (broker < 0) return 13;
    if (broker == 0) { close(lock); return action_server(); }
    int notify = inotify_init1(IN_CLOEXEC | IN_NONBLOCK);
    if (notify < 0 || inotify_add_watch(notify, "/data", IN_CREATE | IN_MOVED_TO | IN_ATTRIB) < 0) return 12;
    int attempts = 0;
    time_t last_attempt = -5;
    while (access(ROOT "/disabled", F_OK) != 0) {
        struct timespec now;
        clock_gettime(CLOCK_MONOTONIC, &now);
        int status = 0;
        if (waitpid(broker, &status, WNOHANG) == broker) {
            fprintf(stderr, "ACTION_SERVER_EXIT=%d\n", status);
            broker = fork();
            if (broker < 0) return 13;
            if (broker == 0) { close(lock); close(notify); return action_server(); }
        }
        int ready = reconcile();
        int running = app_running();
        if (running) attempts = 0;
        /* 慢首启不耗尽尝试次数；持续故障退避到每分钟，避免反复拉起抢占启动资源。 */
        int delay = attempts < 6 ? 5 : 60;
        if (ready && !running && now.tv_sec - last_attempt >= delay) {
            attempts++;
            last_attempt = now.tv_sec;
            pid_t child = fork();
            if (child == 0) {
                setsid();
                execl("/system/bin/am", "am", "startservice", "--user", "0", "-n",
                      "net.elfradio.d31system/.SystemSupportService", (char *)0);
                _exit(127);
            }
            if (child > 0) {
                int status = 0, ended = 0;
                for (int i = 0; i < 100; i++) {
                    if (waitpid(child, &status, WNOHANG) == child) { ended = 1; break; }
                    usleep(100000);
                }
                if (!ended) { kill(-child, SIGKILL); kill(child, SIGKILL); waitpid(child, &status, 0); }
                fprintf(stderr, "SUPPORT_START_ATTEMPT=%d EXIT=%d\n", attempts,
                        ended && WIFEXITED(status) ? WEXITSTATUS(status) : 124);
            }
        }
        struct pollfd event = {.fd = notify, .events = POLLIN};
        if (poll(&event, 1, 5000) > 0) {
            char buffer[4096];
            while (read(notify, buffer, sizeof buffer) > 0) { }
        }
    }
    waitpid(broker, NULL, 0);
    close(notify); close(lock);
    return 0;
}
