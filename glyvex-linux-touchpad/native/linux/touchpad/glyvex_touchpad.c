#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/time.h>
#include <unistd.h>

typedef struct {
    int min_x;
    int max_x;
    int min_y;
    int max_y;
    int have_x;
    int have_y;
    int active;
    int slot;
    int tracking_id;
    int suppress_until_sync;
} TouchState;

static double clamp01(double v) {
    if (v < 0.0) return 0.0;
    if (v > 1.0) return 1.0;
    return v;
}

static double normalize_axis(int value, int minv, int maxv) {
    if (maxv <= minv) {
        return 0.0;
    }
    return clamp01((double)(value - minv) / (double)(maxv - minv));
}

static long long event_time_us(const struct input_event *ev) {
    return ((long long)ev->time.tv_sec * 1000000LL) +
           (long long)ev->time.tv_usec;
}

static int read_abs_info(int fd, int code, int *minv, int *maxv) {
    struct input_absinfo info;
    memset(&info, 0, sizeof(info));

    if (ioctl(fd, EVIOCGABS(code), &info) < 0) {
        return -1;
    }

    *minv = info.minimum;
    *maxv = info.maximum;
    return 0;
}

static void print_sample(const char *kind,
                         const TouchState *s,
                         int x,
                         int y,
                         long long ts_us) {
    double nx = normalize_axis(x, s->min_x, s->max_x);
    double ny = normalize_axis(y, s->min_y, s->max_y);

    printf("%s\t%d\t%.6f\t%.6f\t%lld\n",
           kind,
           s->slot,
           nx,
           ny,
           ts_us);
    fflush(stdout);
}

static void usage(const char *prog) {
    fprintf(stderr, "Usage: %s /dev/input/eventX\n", prog);
}

int main(int argc, char **argv) {
    if (argc != 2) {
        usage(argv[0]);
        return 2;
    }

    const char *device = argv[1];

    int fd = open(device, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        fprintf(stderr,
                "ERROR: cannot open %s: %s\n",
                device,
                strerror(errno));
        return 1;
    }

    TouchState s;
    memset(&s, 0, sizeof(s));
    s.slot = 0;
    s.tracking_id = -1;

    if (read_abs_info(fd, ABS_MT_POSITION_X,
                      &s.min_x, &s.max_x) < 0) {
        fprintf(stderr,
                "ERROR: %s does not expose ABS_MT_POSITION_X\n",
                device);
        close(fd);
        return 1;
    }

    if (read_abs_info(fd, ABS_MT_POSITION_Y,
                      &s.min_y, &s.max_y) < 0) {
        fprintf(stderr,
                "ERROR: %s does not expose ABS_MT_POSITION_Y\n",
                device);
        close(fd);
        return 1;
    }

    char name[256] = {0};
    if (ioctl(fd, EVIOCGNAME(sizeof(name)), name) < 0) {
        strncpy(name, "unknown", sizeof(name) - 1);
    }

    fprintf(stderr, "GLYVEX touchpad backend\n");
    fprintf(stderr, "Device: %s\n", device);
    fprintf(stderr, "Name:   %s\n", name);
    fprintf(stderr, "X:      %d..%d\n", s.min_x, s.max_x);
    fprintf(stderr, "Y:      %d..%d\n", s.min_y, s.max_y);
    fprintf(stderr, "Format: TYPE<TAB>SLOT<TAB>X<TAB>Y<TAB>TIMESTAMP_US\n");
    fprintf(stderr, "Single-finger capture active. Ctrl+C to stop.\n\n");

    struct input_event ev;
    int pending_x = 0;
    int pending_y = 0;
    int have_position_this_sync = 0;
    int finger_seen = 0;

    while (1) {
        ssize_t n = read(fd, &ev, sizeof(ev));

        if (n == 0) {
            fprintf(stderr, "ERROR: touchpad device returned EOF\n");
            break;
        }

        if (n < 0) {
            if (errno == EINTR) {
                continue;
            }
            fprintf(stderr,
                    "ERROR: read failed: %s\n",
                    strerror(errno));
            break;
        }

        if ((size_t)n != sizeof(ev)) {
            fprintf(stderr, "ERROR: short input_event read\n");
            break;
        }

        if (ev.type == EV_ABS) {
            switch (ev.code) {
                case ABS_MT_SLOT:
                    s.slot = ev.value;
                    /* GLYVEX is intentionally single-finger for now. */
                    break;

                case ABS_MT_TRACKING_ID:
                    if (ev.value < 0) {
                        if (s.active && s.have_x && s.have_y) {
                            print_sample("UP",
                                         &s,
                                         pending_x,
                                         pending_y,
                                         event_time_us(&ev));
                        }
                        s.active = 0;
                        s.tracking_id = -1;
                        finger_seen = 0;
                        have_position_this_sync = 0;
                    } else {
                        if (s.slot == 0) {
                            s.tracking_id = ev.value;
                            s.active = 1;
                            finger_seen = 0;
                            have_position_this_sync = 0;
                        } else {
                            /*
                             * Ignore additional fingers. If a second finger
                             * appears, suppress movement until the next
                             * clean single-finger contact.
                             */
                            s.suppress_until_sync = 1;
                        }
                    }
                    break;

                case ABS_MT_POSITION_X:
                    if (s.slot == 0) {
                        pending_x = ev.value;
                        s.have_x = 1;
                        have_position_this_sync = 1;
                    }
                    break;

                case ABS_MT_POSITION_Y:
                    if (s.slot == 0) {
                        pending_y = ev.value;
                        s.have_y = 1;
                        have_position_this_sync = 1;
                    }
                    break;

                default:
                    break;
            }
        } else if (ev.type == EV_SYN && ev.code == SYN_REPORT) {
            if (s.active &&
                s.slot == 0 &&
                !s.suppress_until_sync &&
                s.have_x &&
                s.have_y) {

                if (!finger_seen && have_position_this_sync) {
                    print_sample("DOWN",
                                 &s,
                                 pending_x,
                                 pending_y,
                                 event_time_us(&ev));
                    finger_seen = 1;
                } else if (finger_seen && have_position_this_sync) {
                    print_sample("MOVE",
                                 &s,
                                 pending_x,
                                 pending_y,
                                 event_time_us(&ev));
                }
            }

            if (s.suppress_until_sync) {
                /*
                 * If multiple fingers are present, suppress this stream.
                 * The higher-level Java FSM will treat the missing clean
                 * sequence as invalid. This keeps one-finger drawing safe.
                 */
            }

            have_position_this_sync = 0;
        }
    }

    close(fd);
    return 0;
}
