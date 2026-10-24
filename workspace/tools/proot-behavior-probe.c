/* Device-only static fixture for PRoot's executable/fd and child lifecycle contracts.
 * This is Bionic code, not evidence for a guest distribution's libc or coreutils.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

extern char **environ;

static void fail(const char *operation)
{
    perror(operation);
    exit(1);
}

static void print_link(const char *name)
{
    char result[PATH_MAX];
    ssize_t size = readlink(name, result, sizeof(result) - 1);
    if (size < 0 || size >= (ssize_t)sizeof(result) - 1) fail(name);
    result[size] = 0;
    puts(result);
}

static void check_mode(int fd, int expected)
{
    int flags = fcntl(fd, F_GETFL);
    if (flags < 0) fail("F_GETFL");
    if ((flags & O_ACCMODE) != expected) {
        fprintf(stderr, "wrong reopened access mode: %d expected %d\n", flags & O_ACCMODE, expected);
        exit(1);
    }
}

static void check_reopen(int writable, const char *kind)
{
    char name[64];
    snprintf(name, sizeof(name), "/proc/self/fd/%d", writable);
    char target[PATH_MAX];
    ssize_t size = readlink(name, target, sizeof(target) - 1);
    if (size < 0) fail("readlink original descriptor");
    target[size] = 0;
    struct stat info;
    if (fstat(writable, &info) < 0) fail("fstat original descriptor");
    fprintf(stderr, "%s fd=%d target=%s mode=%o flags=%x\n", kind, writable, target,
            (unsigned int)info.st_mode, fcntl(writable, F_GETFL));
    int readonly = open(name, O_RDONLY);
    if (readonly < 0 && errno == EACCES && strcmp(kind, "memfd") == 0) {
        puts("memfd-readonly-denied:EACCES");
        exit(42);
    }
    if (readonly < 0) fail("reopen readonly");
    check_mode(readonly, O_RDONLY);
    errno = 0;
    if (write(readonly, "x", 1) != -1 || errno != EBADF) {
        fprintf(stderr, "readonly descriptor accepted write (errno=%d)\n", errno);
        exit(1);
    }
    snprintf(name, sizeof(name), "/proc/self/fd/%d", readonly);
    int writeonly = open(name, O_WRONLY);
    if (writeonly < 0) fail("reopen writeonly");
    check_mode(writeonly, O_WRONLY);
    if (write(writeonly, "x", 1) != 1) fail("write reopened descriptor");
    errno = 0;
    char byte;
    if (read(writeonly, &byte, 1) != -1 || errno != EBADF) {
        fprintf(stderr, "writeonly descriptor accepted read (errno=%d)\n", errno);
        exit(1);
    }
    close(writeonly);
    close(readonly);
}

static void wait_success(pid_t child)
{
    int status;
    pid_t result;
    do { result = waitpid(child, &status, 0); } while (result < 0 && errno == EINTR);
    if (result != child) fail("waitpid");
    if (!WIFEXITED(status) || WEXITSTATUS(status) != 0) {
        fprintf(stderr, "child %d status %d\n", child, status);
        exit(1);
    }
}

int main(int argc, char **argv)
{
    if (argc < 2) return 2;
    char security_context[256];
    int context_fd = open("/proc/self/attr/current", O_RDONLY);
    ssize_t context_size = context_fd >= 0 ? read(context_fd, security_context, sizeof(security_context) - 1) : -1;
    if (context_fd >= 0) close(context_fd);
    if (context_size < 0) context_size = 0;
    security_context[context_size] = 0;
    fprintf(stderr, "uid=%u euid=%u context=%s\n", (unsigned int)getuid(), (unsigned int)geteuid(), security_context);
    if (strcmp(argv[1], "self") == 0) {
        print_link("/proc/self/exe");
    } else if (strcmp(argv[1], "execat") == 0 && argc == 3) {
        char *child_args[] = { argv[2], "self", NULL };
        syscall(SYS_execveat, AT_FDCWD, argv[2], child_args, environ, 0);
        fail("execveat"); // ENOSYS is a failed required case, not a silent skip.
    } else if ((strcmp(argv[1], "nofollow") == 0 || strcmp(argv[1], "path") == 0) && argc == 3) {
        int flags = O_NOFOLLOW | (strcmp(argv[1], "path") == 0 ? O_PATH : O_RDONLY);
        int fd = open(argv[2], flags);
        if (fd < 0) fail("open nofollow");
        char name[64];
        snprintf(name, sizeof(name), "/proc/self/fd/%d", fd);
        print_link(name);
        close(fd);
    } else if (strcmp(argv[1], "create") == 0 && argc == 3) {
#ifdef SYS_creat
        int fd = syscall(SYS_creat, argv[2], 0600);
#else
        int fd = creat(argv[2], 0600);
#endif
        if (fd < 0) fail("creat");
        struct stat created;
        if (fstat(fd, &created) < 0) fail("fstat creat");
        if ((created.st_mode & 0777) != 0600) {
            fprintf(stderr, "creat mode mismatch: %o\n", (unsigned int)created.st_mode);
            return 1;
        }
        if (write(fd, "truncate-me", 11) != 11) fail("write creat");
        close(fd);
#ifdef SYS_creat
        fd = syscall(SYS_creat, argv[2], 0600);
#else
        fd = creat(argv[2], 0600);
#endif
        if (fd < 0) fail("creat existing");
        if (fstat(fd, &created) < 0) fail("fstat truncated");
        if (created.st_size != 0) {
            fprintf(stderr, "creat did not truncate existing file\n");
            return 1;
        }
        close(fd);
        puts("creat-ok");
    } else if (strcmp(argv[1], "reopen") == 0 && argc == 3) {
        int regular = open(argv[2], O_RDWR | O_CREAT | O_TRUNC, 0600);
        if (regular < 0) fail("open regular");
        check_reopen(regular, "regular");
        close(regular);
        puts("regular-reopen-ok");
        int memory = syscall(SYS_memfd_create, "pilot-proot-reopen", 0);
        if (memory < 0) fail("memfd_create");
        check_reopen(memory, "memfd");
        close(memory);
        puts("regular-memfd-reopen-ok");
    } else if (strcmp(argv[1], "spawn") == 0 && argc == 3) {
        char *child_args[] = { argv[2], "child", NULL };
        for (int iteration = 0; iteration < 16; ++iteration) {
            pid_t child = vfork();
            if (child < 0) fail("vfork");
            if (child == 0) {
                execve(argv[2], child_args, environ);
                _exit(111);
            }
            wait_success(child);
            int error = posix_spawn(&child, argv[2], NULL, NULL, child_args, environ);
            if (error) { errno = error; fail("posix_spawn"); }
            wait_success(child);
        }
        puts("vfork-posix-spawn-ok");
    } else if (strcmp(argv[1], "child") == 0) {
        return 0;
    } else {
        return 2;
    }
    return 0;
}
