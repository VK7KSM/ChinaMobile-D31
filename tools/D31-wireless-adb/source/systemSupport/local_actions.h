/* 仅授权系统支持应用连接的本机命令通道，不监听网络。 */
#include <arpa/inet.h>

static int transfer(int fd, void *data, size_t count, int writing) {
    char *p = data;
    while (count) {
        ssize_t n = writing ? write(fd, p, count) : read(fd, p, count);
        if (n <= 0) return 0;
        p += n; count -= n;
    }
    return 1;
}

static void serve_action(int fd) {
    uint32_t size;
    if (!transfer(fd, &size, sizeof size, 0)) return;
    size = ntohl(size);
    if (!size || size > 32768) return;
    char command[32769];
    if (!transfer(fd, command, size, 0) || memchr(command, 0, size)) return;
    command[size] = 0;
    int pipefd[2];
    if (pipe2(pipefd, O_CLOEXEC)) return;
    pid_t child = fork();
    if (child == 0) {
        setsid();
        dup2(pipefd[1], 1); dup2(pipefd[1], 2);
        close(pipefd[0]); close(pipefd[1]); close(fd);
        execl("/system/bin/sh", "sh", "-c", command, (char *)0);
        _exit(127);
    }
    close(pipefd[1]);
    if (child < 0) { close(pipefd[0]); return; }
    fcntl(pipefd[0], F_SETFL, O_NONBLOCK);
    char output[65536], block[2048];
    size_t used = 0;
    int status = 0, ended = 0;
    for (int tick = 0; tick < 140; tick++) {
        ssize_t n;
        for (int batch = 0; batch < 32 && (n = read(pipefd[0], block, sizeof block)) > 0; batch++) {
            size_t keep = (size_t)n < sizeof output - used ? (size_t)n : sizeof output - used;
            memcpy(output + used, block, keep); used += keep;
        }
        if (waitpid(child, &status, WNOHANG) == child) { ended = 1; break; }
        usleep(50000);
    }
    if (!ended) { kill(-child, SIGKILL); kill(child, SIGKILL); waitpid(child, &status, 0); }
    ssize_t n;
    for (int batch = 0; batch < 32 && (n = read(pipefd[0], block, sizeof block)) > 0; batch++) {
        size_t keep = (size_t)n < sizeof output - used ? (size_t)n : sizeof output - used;
        memcpy(output + used, block, keep); used += keep;
    }
    close(pipefd[0]);
    uint32_t response[2] = {htonl(ended && WIFEXITED(status) ? (uint32_t)WEXITSTATUS(status) : 124), htonl((uint32_t)used)};
    if (transfer(fd, response, sizeof response, 1)) transfer(fd, output, used, 1);
}

static int action_server(void) {
#ifndef ACTION_SOCKET
#define ACTION_SOCKET "/dev/socket/d31-system-actions"
#endif
    const char *path = ACTION_SOCKET;
    struct stat app;
    /* 全新userdata的应用目录由PMS稍后创建，不能把暂未创建当成永久失败。 */
    while (stat(APP, &app) || app.st_uid < 10000) {
        if (access(ROOT "/disabled", F_OK) == 0) return 0;
        sleep(1);
    }
    int listener = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (listener < 0) return 31;
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    snprintf(address.sun_path, sizeof address.sun_path, "%s", path);
    unlink(path);
    if (bind(listener, (struct sockaddr *)&address, sizeof address)
            || chown(path, app.st_uid, app.st_uid) || chmod(path, 0600) || listen(listener, 2)) {
        close(listener); return 32;
    }
    signal(SIGPIPE, SIG_IGN);
    while (access(ROOT "/disabled", F_OK) != 0) {
        struct pollfd event = {.fd = listener, .events = POLLIN};
        if (poll(&event, 1, 1000) <= 0) continue;
        int fd = accept4(listener, NULL, NULL, SOCK_CLOEXEC);
        if (fd < 0) continue;
        struct timeval timeout = {.tv_sec = 2};
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof timeout);
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof timeout);
        struct ucred peer;
        socklen_t length = sizeof peer;
        if (!getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &peer, &length) && peer.uid == app.st_uid)
            serve_action(fd);
        close(fd);
    }
    close(listener); unlink(path);
    return 0;
}
