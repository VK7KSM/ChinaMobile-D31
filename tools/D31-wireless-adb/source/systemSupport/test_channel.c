#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>
#include <time.h>
#include <signal.h>

static int exact(int fd, void *p, size_t n, int send_data) {
    char *b = p;
    while (n) {
        ssize_t r = send_data ? write(fd, b, n) : read(fd, b, n);
        if (r <= 0) return 0;
        b += r; n -= r;
    }
    return 1;
}
int main(int argc, char **argv) {
    if (argc != 2 || getuid() != 0) return 10;
    int denied = !strcmp(argv[1], "deny-root");
    const char *command = NULL;
    int expected = 0;
    if (!strcmp(argv[1], "identity") || denied) command = "id";
    else if (!strcmp(argv[1], "exit")) { command = "exit 7"; expected = 7; }
    else if (!strcmp(argv[1], "timeout")) { command = "sleep 12"; expected = 124; }
    else if (!strcmp(argv[1], "output")) { command = "while true; do echo bounded-output; done"; expected = 124; }
    else return 11;
    struct stat app;
    if (stat("/data/data/net.elfradio.d31system", &app)) return 12;
    if (!denied && (setgid(app.st_uid) || setuid(app.st_uid))) return 13;
    signal(SIGPIPE, SIG_IGN);
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    strcpy(address.sun_path, "/dev/socket/d31-system-actions");
    struct timeval timeout = {.tv_sec = 10};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof timeout);
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof timeout);
    if (connect(fd, (struct sockaddr *)&address, sizeof address)) { perror("connect"); return 14; }
    uint32_t n = htonl(strlen(command)), response[2];
    struct timespec begin, end;
    clock_gettime(CLOCK_MONOTONIC, &begin);
    int ok = exact(fd, &n, 4, 1) && exact(fd, (void *)command, strlen(command), 1) && exact(fd, response, 8, 0);
    if (denied) { close(fd); printf("peer_rejected=%d\n", !ok); return ok ? 15 : 0; }
    if (!ok) return 16;
    unsigned int size = ntohl(response[1]);
    if (size > 65536) return 17;
    char result[65537];
    if (!exact(fd, result, size, 0)) return 18;
    result[size] = 0;
    clock_gettime(CLOCK_MONOTONIC, &end);
    double elapsed = end.tv_sec - begin.tv_sec + (end.tv_nsec - begin.tv_nsec) / 1e9;
    printf("exit=%u bytes=%u elapsed=%.3f\n", ntohl(response[0]), size, elapsed);
    if (!strcmp(argv[1], "identity")) printf("%s", result);
    close(fd);
    return ntohl(response[0]) == (unsigned int)expected && elapsed < 9.5 ? 0 : 19;
}
