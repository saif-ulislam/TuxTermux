/*
 * PTY host for TuxTerm.
 *
 * One live shell per session. The master fd is returned to the caller, which
 * reads and writes it directly: there is no staging file and no polling, which
 * is where the previous design lost every byte of output.
 *
 * The child owns the slave side (0/1/2), so stdout, stderr and the shell's own
 * echo all arrive on the same fd the caller reads. Termios ECHO is left at its
 * default: the UI draws the emulator grid, not a synthetic prompt line, so the
 * terminal must behave like a real one.
 */
#include <jni.h>

#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define TUX_STILL_RUNNING (-2)

static char *dup_jstring(JNIEnv *env, jstring value)
{
    const char *utf;
    char *copy;

    if (!value)
        return NULL;
    utf = (*env)->GetStringUTFChars(env, value, NULL);
    if (!utf)
        return NULL;
    copy = strdup(utf);
    (*env)->ReleaseStringUTFChars(env, value, utf);
    return copy;
}

/* NULL-terminated argv/envp built from a Java String array. */
static char **dup_string_array(JNIEnv *env, jobjectArray array, jsize *out_len)
{
    jsize len = array ? (*env)->GetArrayLength(env, array) : 0;
    char **result = (char **)calloc((size_t)len + 1, sizeof(char *));
    jsize i;

    if (!result)
        return NULL;
    for (i = 0; i < len; i++) {
        jstring element = (jstring)(*env)->GetObjectArrayElement(env, array, i);
        result[i] = dup_jstring(env, element);
        if (element)
            (*env)->DeleteLocalRef(env, element);
    }
    result[len] = NULL;
    if (out_len)
        *out_len = len;
    return result;
}

static void free_string_array(char **array, jsize len)
{
    jsize i;
    if (!array)
        return;
    for (i = 0; i < len; i++)
        free(array[i]);
    free(array);
}

JNIEXPORT jintArray JNICALL
Java_dev_tuxterm_Native_ptySpawn(JNIEnv *env, jobject self, jobjectArray jargv,
                                 jobjectArray jenvp, jstring jcwd,
                                 jint rows, jint cols)
{
    char **argv = NULL;
    char **envp = NULL;
    jsize argc = 0, envc = 0;
    char *cwd = NULL;
    char *slave_name = NULL;
    int master = -1;
    pid_t pid;
    jint values[2];
    jintArray result;

    (void)self;

    argv = dup_string_array(env, jargv, &argc);
    envp = dup_string_array(env, jenvp, &envc);
    cwd = dup_jstring(env, jcwd);
    if (!argv || argc == 0)
        goto fail;

    master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0)
        goto fail;
    if (grantpt(master) != 0 || unlockpt(master) != 0)
        goto fail;
    /* ptsname() returns a pointer into a static buffer that any other call to
     * ptsname/ttyname in this process overwrites. Copy it before the fork so
     * the child is not reading storage that a sibling thread can mutate
     * between fork() and open(). */
    {
        const char *named = ptsname(master);
        if (!named)
            goto fail;
        slave_name = strdup(named);
        if (!slave_name)
            goto fail;
    }

    pid = fork();
    if (pid < 0)
        goto fail;

    if (pid == 0) {
        int slave;
        struct winsize size;
        struct termios term;

        close(master);
        if (setsid() < 0)
            _exit(126);
        slave = open(slave_name, O_RDWR);
        if (slave < 0)
            _exit(126);
#ifdef TIOCSCTTY
        ioctl(slave, TIOCSCTTY, 0);
#endif
        memset(&size, 0, sizeof(size));
        size.ws_row = (unsigned short)(rows > 0 ? rows : 24);
        size.ws_col = (unsigned short)(cols > 0 ? cols : 80);
        ioctl(slave, TIOCSWINSZ, &size);

        /* Keep the window sane for line editing and colour output. */
        if (tcgetattr(slave, &term) == 0) {
            term.c_lflag |= (ECHO | ICANON | ISIG | IEXTEN);
            term.c_iflag |= (ICRNL | IXON);
            term.c_oflag |= (OPOST | ONLCR);
            tcsetattr(slave, TCSANOW, &term);
        }

        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO)
            close(slave);

        if (cwd && chdir(cwd) != 0)
            _exit(126);
        if (envp && envp[0])
            execve(argv[0], argv, envp);
        else
            execv(argv[0], argv);
        /* Only reached when the executable could not be started. Writing to the
         * slave is what makes this visible instead of a silent blank screen. */
        dprintf(STDERR_FILENO, "tuxterm: exec failed: %s: %s\n", argv[0],
                strerror(errno));
        _exit(127);
    }

    free_string_array(argv, argc);
    free_string_array(envp, envc);
    free(cwd);
    free(slave_name);

    values[0] = (jint)pid;
    values[1] = (jint)master;
    result = (*env)->NewIntArray(env, 2);
    if (!result)
        return NULL;
    (*env)->SetIntArrayRegion(env, result, 0, 2, values);
    return result;

fail:
    if (master >= 0)
        close(master);
    free_string_array(argv, argc);
    free_string_array(envp, envc);
    free(cwd);
    free(slave_name);
    return NULL;
}

/* Reap without blocking. Returns the exit status, or TUX_STILL_RUNNING. */
JNIEXPORT jint JNICALL
Java_dev_tuxterm_Native_ptyWait(JNIEnv *env, jobject self, jint pid, jboolean noHang)
{
    int status = 0;
    pid_t waited;

    (void)env;
    (void)self;
    waited = waitpid((pid_t)pid, &status, noHang ? WNOHANG : 0);
    if (waited == 0)
        return TUX_STILL_RUNNING;
    if (waited < 0)
        return (errno == ECHILD) ? 0 : -1;
    if (WIFEXITED(status))
        return WEXITSTATUS(status);
    if (WIFSIGNALED(status))
        return 128 + WTERMSIG(status);
    return 0;
}

JNIEXPORT void JNICALL
Java_dev_tuxterm_Native_ptyKill(JNIEnv *env, jobject self, jint pid, jint signal)
{
    (void)env;
    (void)self;
    kill((pid_t)pid, signal);
}

/* Terminal geometry. TIOCSWINSZ goes to the master; the kernel notifies the
 * foreground process group, so full-screen programs rewrap like a real tty. */
JNIEXPORT jboolean JNICALL
Java_dev_tuxterm_Native_ptyResize(JNIEnv *env, jobject self, jint fd, jint rows, jint cols)
{
    struct winsize size;

    (void)env;
    (void)self;
    memset(&size, 0, sizeof(size));
    size.ws_row = (unsigned short)(rows > 0 ? rows : 24);
    size.ws_col = (unsigned short)(cols > 0 ? cols : 80);
    return ioctl((int)fd, TIOCSWINSZ, &size) == 0 ? JNI_TRUE : JNI_FALSE;
}
