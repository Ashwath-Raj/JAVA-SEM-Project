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

#define MAX_SLOTS 32

typedef struct {
    int active;
    int tracking_id;
    int x;
    int y;
    int have_x;
    int have_y;
    int pos_updated;
} Slot;

typedef struct {
    int min_x, max_x;
    int min_y, max_y;
    int current_slot;
    Slot slots[MAX_SLOTS];
    int tracked_slot;
    int finger_is_down;
    double last_x;
    double last_y;
} TouchpadState;

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

    TouchpadState state;
    memset(&state, 0, sizeof(state));
    state.current_slot = 0;
    state.tracked_slot = -1;
    state.finger_is_down = 0;
    for (int i = 0; i < MAX_SLOTS; i++) {
        state.slots[i].tracking_id = -1;
    }

    // Read X axis bounds
    if (read_abs_info(fd, ABS_MT_POSITION_X, &state.min_x, &state.max_x) < 0) {
        if (read_abs_info(fd, ABS_X, &state.min_x, &state.max_x) < 0) {
            fprintf(stderr, "ERROR: %s does not expose ABS_MT_POSITION_X or ABS_X\n", device);
            close(fd);
            return 1;
        }
    }

    // Read Y axis bounds
    if (read_abs_info(fd, ABS_MT_POSITION_Y, &state.min_y, &state.max_y) < 0) {
        if (read_abs_info(fd, ABS_Y, &state.min_y, &state.max_y) < 0) {
            fprintf(stderr, "ERROR: %s does not expose ABS_MT_POSITION_Y or ABS_Y\n", device);
            close(fd);
            return 1;
        }
    }

    char name[256] = {0};
    if (ioctl(fd, EVIOCGNAME(sizeof(name)), name) < 0) {
        strncpy(name, "unknown", sizeof(name) - 1);
    }

    fprintf(stderr, "GLYVEX touchpad backend (Multi-Slot Tracking v2.0)\n");
    fprintf(stderr, "Device: %s\n", device);
    fprintf(stderr, "Name:   %s\n", name);
    fprintf(stderr, "X:      %d..%d\n", state.min_x, state.max_x);
    fprintf(stderr, "Y:      %d..%d\n", state.min_y, state.max_y);
    fprintf(stderr, "Format: TYPE<TAB>SLOT<TAB>X<TAB>Y<TAB>TIMESTAMP_US\n");
    fprintf(stderr, "Single-finger normalization active. Ctrl+C to stop.\n\n");

    struct input_event ev;

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
            fprintf(stderr, "ERROR: read failed: %s\n", strerror(errno));
            break;
        }

        if ((size_t)n != sizeof(ev)) {
            fprintf(stderr, "ERROR: short input_event read\n");
            break;
        }

        if (ev.type == EV_ABS) {
            switch (ev.code) {
                case ABS_MT_SLOT:
                    if (ev.value >= 0 && ev.value < MAX_SLOTS) {
                        state.current_slot = ev.value;
                    }
                    break;

                case ABS_MT_TRACKING_ID: {
                    int slot = state.current_slot;
                    if (slot >= 0 && slot < MAX_SLOTS) {
                        if (ev.value < 0) {
                            state.slots[slot].active = 0;
                            state.slots[slot].tracking_id = -1;
                        } else {
                            state.slots[slot].active = 1;
                            state.slots[slot].tracking_id = ev.value;
                        }
                    }
                    break;
                }

                case ABS_MT_POSITION_X: {
                    int slot = state.current_slot;
                    if (slot >= 0 && slot < MAX_SLOTS) {
                        state.slots[slot].x = ev.value;
                        state.slots[slot].have_x = 1;
                        state.slots[slot].pos_updated = 1;
                    }
                    break;
                }

                case ABS_MT_POSITION_Y: {
                    int slot = state.current_slot;
                    if (slot >= 0 && slot < MAX_SLOTS) {
                        state.slots[slot].y = ev.value;
                        state.slots[slot].have_y = 1;
                        state.slots[slot].pos_updated = 1;
                    }
                    break;
                }

                case ABS_X: {
                    int slot = state.current_slot;
                    if (slot >= 0 && slot < MAX_SLOTS && !state.slots[slot].have_x) {
                        state.slots[slot].x = ev.value;
                        state.slots[slot].have_x = 1;
                        state.slots[slot].pos_updated = 1;
                    }
                    break;
                }

                case ABS_Y: {
                    int slot = state.current_slot;
                    if (slot >= 0 && slot < MAX_SLOTS && !state.slots[slot].have_y) {
                        state.slots[slot].y = ev.value;
                        state.slots[slot].have_y = 1;
                        state.slots[slot].pos_updated = 1;
                    }
                    break;
                }

                default:
                    break;
            }
        } else if (ev.type == EV_KEY) {
            if (ev.code == BTN_TOUCH) {
                if (ev.value == 0) {
                    /* All physical contacts released */
                    for (int i = 0; i < MAX_SLOTS; i++) {
                        state.slots[i].active = 0;
                        state.slots[i].tracking_id = -1;
                    }
                } else if (ev.value == 1) {
                    /* Ensure at least current_slot is marked active if no MT tracking id was sent */
                    int slot = state.current_slot;
                    if (slot >= 0 && slot < MAX_SLOTS && state.slots[slot].tracking_id < 0) {
                        state.slots[slot].active = 1;
                        state.slots[slot].tracking_id = 1;
                    }
                }
            }
        } else if (ev.type == EV_SYN && ev.code == SYN_REPORT) {
            long long ts_us = event_time_us(&ev);

            /* Count active slots */
            int active_count = 0;
            int single_active_idx = -1;
            for (int i = 0; i < MAX_SLOTS; i++) {
                if (state.slots[i].active) {
                    active_count++;
                    single_active_idx = i;
                }
            }

            if (active_count == 0) {
                if (state.finger_is_down) {
                    printf("UP\t0\t%.6f\t%.6f\t%lld\n",
                           state.last_x, state.last_y, ts_us);
                    fflush(stdout);
                    state.finger_is_down = 0;
                    state.tracked_slot = -1;
                }
            } else if (active_count == 1) {
                Slot *s = &state.slots[single_active_idx];
                if (s->have_x && s->have_y) {
                    double nx = normalize_axis(s->x, state.min_x, state.max_x);
                    double ny = normalize_axis(s->y, state.min_y, state.max_y);

                    if (!state.finger_is_down) {
                        /* Single finger down */
                        printf("DOWN\t0\t%.6f\t%.6f\t%lld\n",
                               nx, ny, ts_us);
                        fflush(stdout);
                        state.finger_is_down = 1;
                        state.tracked_slot = single_active_idx;
                        state.last_x = nx;
                        state.last_y = ny;
                    } else if (state.tracked_slot == single_active_idx) {
                        /* Continuing single finger movement */
                        if (s->pos_updated) {
                            printf("MOVE\t0\t%.6f\t%.6f\t%lld\n",
                                   nx, ny, ts_us);
                            fflush(stdout);
                            state.last_x = nx;
                            state.last_y = ny;
                        }
                    } else {
                        /* Slot handover: emit UP for old slot, DOWN for new slot */
                        printf("UP\t0\t%.6f\t%.6f\t%lld\n",
                               state.last_x, state.last_y, ts_us);
                        printf("DOWN\t0\t%.6f\t%.6f\t%lld\n",
                               nx, ny, ts_us);
                        fflush(stdout);
                        state.tracked_slot = single_active_idx;
                        state.last_x = nx;
                        state.last_y = ny;
                    }
                }
            } else {
                /* active_count > 1: Multi-touch contact */
                if (state.finger_is_down) {
                    printf("CANCEL\t0\t%.6f\t%.6f\t%lld\n",
                           state.last_x, state.last_y, ts_us);
                    fflush(stdout);
                    state.finger_is_down = 0;
                    state.tracked_slot = -1;
                }
                /* Suppress output during multi-finger contact */
            }

            /* Reset frame update flags */
            for (int i = 0; i < MAX_SLOTS; i++) {
                state.slots[i].pos_updated = 0;
            }
        }
    }

    close(fd);
    return 0;
}
