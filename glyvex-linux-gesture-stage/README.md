# GLYVEX Linux Touchpad Interaction Stage

This module implements the next GLYVEX interaction layer after raw Linux evdev capture:

```text
Acer touchpad
    -> native evdev reader (existing glyvex-linux-touchpad)
    -> tab protocol
    -> Java parser
    -> four-corner activation FSM
    -> multi-stroke gesture capture
    -> Gesture object
    -> future 128x128 rasterizer / ONNX classifier
```

## Activation pattern

Default sequence:

```text
TOP_LEFT -> TOP_RIGHT -> BOTTOM_RIGHT -> BOTTOM_LEFT
```

Each corner is a separate single-finger contact. The contact must begin inside the expected corner region, then be released. A wrong corner or timeout resets the sequence.

Corner margin is `0.18` of the normalized touchpad width/height.

## Gesture capture

After the fourth corner is released, the next touch begins character capture.

Multiple strokes are supported:

```text
DOWN -> MOVE... -> UP   = stroke 1
DOWN -> MOVE... -> UP   = stroke 2
...
```

Capture completes after 850 ms without a new stroke, or after a 5 s maximum duration.

The resulting `Gesture` is intentionally independent of ML. The next stage can rasterize it into the exact 128x128 grayscale input used by the trained GLYVEX model.

## Build

From this module directory:

```bash
./scripts/build_java.sh
```

## Live test

The script expects the existing native Linux touchpad backend at:

```text
../glyvex-linux-touchpad/native/linux/touchpad/
```

Run:

```bash
./scripts/run_linux_demo.sh
```

Typical interaction:

1. Touch/release top-left.
2. Touch/release top-right.
3. Touch/release bottom-right.
4. Touch/release bottom-left.
5. Draw a character using one or more strokes.
6. Wait ~850 ms after the final stroke.

You should see `GESTURE COMPLETE` followed by a summary of strokes and point counts.

## Architecture note

The Java side never reads `/dev/input/eventX` directly. The small native reader owns the privileged evdev access and emits a normalized, unprivileged text protocol. This keeps Linux device access isolated from the Java/ML application.
