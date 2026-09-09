/* 在独立临时目录重现首次开机时应用目录晚于守护创建的条件。 */
#define _GNU_SOURCE
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>
#define ROOT "/data/local/tmp/d31-firstboot-broker-test-v141"
#define APP ROOT "/app"
#define ACTION_SOCKET ROOT "/actions"
#include "local_actions.h"
int main(void) {
    if (getuid()!=0 || mkdir(ROOT,0700)) return 10;
    pid_t child=fork();
    if(child<0) return 11;
    if(child==0) return action_server();
    sleep(3);
    int status=0;
    if(waitpid(child,&status,WNOHANG)==child) { puts("FAIL_EARLY_EXIT"); return 12; }
    int result=0;
    if(mkdir(APP,0700) || chown(APP,10001,10001)) result=13;
    for(int i=0;i<40 && access(ACTION_SOCKET,F_OK);i++) usleep(100000);
    struct stat value;
    if(lstat(ACTION_SOCKET,&value) || !S_ISSOCK(value.st_mode)
            || value.st_uid!=10001 || (value.st_mode&0777)!=0600) result=14;
    int fd=open(ROOT "/disabled",O_CREAT|O_WRONLY,0600);
    if(fd>=0) close(fd);
    for(int i=0;i<30;i++) {
        if(waitpid(child,&status,WNOHANG)==child) { child=0; break; }
        usleep(100000);
    }
    if(child>0) { kill(child,SIGKILL); waitpid(child,&status,0); result=15; }
    unlink(ROOT "/disabled"); unlink(ACTION_SOCKET); rmdir(APP); rmdir(ROOT);
    printf("DELAYED_APP_DIRECTORY_TEST=%d\n",result);
    return result;
}
