/*
 * PadBridge's only native code: the evdev and uinput calls Java can't make, for the engine.
 * Uploading a rumble effect (EVIOCSFF) and setting the event clock (EVIOCSCLOCKID) pass a
 * pointer to a struct, which android.system.Os can't do.
 *
 * Built with -nostdlib and raw aarch64 system calls, so it needs nothing from the NDK and loads
 * into any process: see build.sh.
 */

typedef unsigned short u16;
typedef short s16;
typedef void *JNIEnv;
typedef void *jclass;
typedef int jint;

#define EVIOCSFF 0x40304580      /* _IOW('E', 0x80, struct ff_effect), 48 bytes on 64-bit */
#define EVIOCRMFF 0x40044581     /* _IOW('E', 0x81, int) */
#define EVIOCSCLOCKID 0x400445a0 /* _IOW('E', 0xa0, int) */
#define EV_FF 0x15
#define FF_RUMBLE 0x50
#define CLOCK_MONOTONIC 1

struct ff_effect {
    u16 type;
    s16 id;
    u16 direction;
    u16 trigger_button, trigger_interval;
    u16 replay_length, replay_delay;
    /* The union starts 8-aligned: its periodic member holds a pointer. */
    u16 pad;
    u16 strong_magnitude, weak_magnitude;
    char rest[28];
};

_Static_assert(sizeof(struct ff_effect) == 48, "struct ff_effect is 48 bytes on aarch64");

struct input_event {
    long sec, usec;
    u16 type, code;
    int value;
};

_Static_assert(sizeof(struct input_event) == 24, "struct input_event is 24 bytes on aarch64");

static long sys3(long nr, long a, long b, long c) {
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x8 __asm__("x8") = nr;
    __asm__ volatile("svc 0" : "+r"(x0) : "r"(x1), "r"(x2), "r"(x8) : "memory");
    return x0;
}

#define SYS_ioctl 29
#define SYS_write 64

/* Uploads (or with id >= 0, updates) a rumble effect. Returns its id, or -errno. */
jint Java_com_padbridge_Native_rumbleUpload(JNIEnv *env, jclass cls, jint fd, jint id, jint strong, jint weak, jint ms) {
    struct ff_effect e;
    char *p = (char *) &e;
    for (unsigned i = 0; i < sizeof e; i++) p[i] = 0;
    e.type = FF_RUMBLE;
    e.id = (s16) id;
    e.replay_length = (u16) ms;
    e.strong_magnitude = (u16) strong;
    e.weak_magnitude = (u16) weak;
    long r = sys3(SYS_ioctl, fd, EVIOCSFF, (long) &e);
    return r < 0 ? (jint) r : e.id;
}

/* Starts (on != 0) or stops an uploaded effect. Returns 0, or -errno. */
jint Java_com_padbridge_Native_rumblePlay(JNIEnv *env, jclass cls, jint fd, jint id, jint on) {
    struct input_event ev = {0, 0, EV_FF, (u16) id, on ? 1 : 0};
    long r = sys3(SYS_write, fd, (long) &ev, sizeof ev);
    return r < 0 ? (jint) r : 0;
}

jint Java_com_padbridge_Native_rumbleErase(JNIEnv *env, jclass cls, jint fd, jint id) {
    return (jint) sys3(SYS_ioctl, fd, EVIOCRMFF, id);
}

/* Event timestamps on CLOCK_MONOTONIC, like System.nanoTime(). Returns 0, or -errno. */
jint Java_com_padbridge_Native_monotonicClock(JNIEnv *env, jclass cls, jint fd) {
    int clock = CLOCK_MONOTONIC;
    return (jint) sys3(SYS_ioctl, fd, EVIOCSCLOCKID, (long) &clock);
}

/*
 * Player pads made straight on /dev/uinput, so a report goes from the controller to the
 * player pad in one write, in this process: no `uinput` tool, pipe or JSON in between.
 */

#define SYS_openat 56
#define SYS_close 57
#define SYS_read 63
#define AT_FDCWD -100
#define O_RDWR 2
#define O_CLOEXEC 0x80000

typedef void *jbyteArray;
/* JNI's GetByteArrayRegion and SetByteArrayRegion: entries 200 and 208 of JNIEnv's table. */
typedef void (*ByteRegion)(JNIEnv *env, jbyteArray a, jint start, jint len, void *buf);
#define JNI_FN(env, i) ((ByteRegion) ((void **) *(void ***) (env))[i])

/* Opens /dev/uinput. Returns the fd, or -errno. */
jint Java_com_padbridge_Native_uinputOpen(JNIEnv *env, jclass cls) {
    static const char path[] = "/dev/uinput";
    return (jint) sys3(SYS_openat, AT_FDCWD, (long) path, O_RDWR | O_CLOEXEC);
}

jint Java_com_padbridge_Native_close(JNIEnv *env, jclass cls, jint fd) {
    return (jint) sys3(SYS_close, fd, 0, 0);
}

/* An ioctl that takes a plain int (UI_SET_EVBIT and friends, UI_DEV_CREATE). */
jint Java_com_padbridge_Native_ioctlValue(JNIEnv *env, jclass cls, jint fd, jint cmd, jint value) {
    return (jint) sys3(SYS_ioctl, fd, (unsigned) cmd, value);
}

/* An ioctl on a struct, passed in (and back out of) `buf`. Structs up to 256 bytes. */
jint Java_com_padbridge_Native_ioctlStruct(JNIEnv *env, jclass cls, jint fd, jint cmd, jbyteArray buf, jint len) {
    long s[32];
    if (len < 0 || len > (jint) sizeof s) return -22;
    JNI_FN(env, 200)(env, buf, 0, len, s);
    long r = sys3(SYS_ioctl, fd, (unsigned) cmd, (long) s);
    JNI_FN(env, 208)(env, buf, 0, len, s);
    return (jint) r;
}

/* Writes `len` bytes of input events. Returns bytes written, or -errno. */
jint Java_com_padbridge_Native_write(JNIEnv *env, jclass cls, jint fd, jbyteArray buf, jint len) {
    long s[256];
    if (len < 0 || len > (jint) sizeof s) return -22;
    JNI_FN(env, 200)(env, buf, 0, len, s);
    return (jint) sys3(SYS_write, fd, (long) s, len);
}

static long sys5(long nr, long a, long b, long c, long d, long e) {
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    register long x4 __asm__("x4") = e;
    register long x8 __asm__("x8") = nr;
    __asm__ volatile("svc 0" : "+r"(x0) : "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x8) : "memory");
    return x0;
}

#define SYS_ppoll 73
#define POLLIN 1

struct pollfd { int fd; short events, revents; };
struct timespec { long sec, nsec; };

/*
 * Reads up to `len` bytes, waiting at most `ms` for them, so the reading thread can notice
 * when its pad is being removed. Returns bytes read, 0 after waiting, or -errno.
 */
jint Java_com_padbridge_Native_read(JNIEnv *env, jclass cls, jint fd, jbyteArray buf, jint len, jint ms) {
    long s[64];
    if (len > (jint) sizeof s) len = sizeof s;
    struct pollfd p = {fd, POLLIN, 0};
    struct timespec t = {ms / 1000, (ms % 1000) * 1000000L};
    long r = sys5(SYS_ppoll, (long) &p, 1, (long) &t, 0, 0);
    if (r <= 0) return r == -4 ? 0 : (jint) r; /* -EINTR: try again */
    r = sys3(SYS_read, fd, (long) s, len);
    if (r > 0) JNI_FN(env, 208)(env, buf, 0, (jint) r, s);
    return (jint) r;
}
