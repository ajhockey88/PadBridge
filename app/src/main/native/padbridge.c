/*
 * PadBridge's only native code: the evdev calls Java can't make, for the engine.
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
