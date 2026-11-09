# GLYVEX Linux ML Integration

This package adds the real Linux end-to-end bridge after the working touchpad + four-corner FSM stage.

## Pipeline

`evdev touchpad -> Java parser -> four-corner FSM -> gesture capture -> 128x128 rasterizer -> ONNX -> softmax -> confidence gate`

The command registry is intentionally NOT executed yet. This stage only proves recognition and rejection.

## Training preprocessing contract reproduced in Java

- 128x128 grayscale
- white background
- black trajectory
- bounding-box normalization over all strokes
- 12-pixel margin
- aspect-ratio preserving scale
- centered drawing
- 3-pixel trajectory width
- stroke boundaries preserved
- no pressure input
- no timestamps fed to the CNN
- model input: `(gray/255 - 0.5) / 0.5`
- ONNX output: 7 logits, class order loaded from `classes.json`
- acceptance threshold loaded from `confidence_gate.json`

## Build

From this directory:

```bash
./scripts/build_ml.sh
```

Requires Java 17+ and Maven. ONNX Runtime Java 1.30.0 is declared in `pom.xml`.

## Run on the current Acer Linux touchpad

The script automatically detects the touchpad and uses sudo only for the native `/dev/input/eventX` reader:

```bash
./scripts/run_end_to_end.sh
```

Then perform:

1. TOP_LEFT touch/release
2. TOP_RIGHT touch/release
3. BOTTOM_RIGHT touch/release
4. BOTTOM_LEFT touch/release
5. Draw a character
6. Release your finger and wait for gesture timeout

The generated raster image is saved under `runtime/debug/` for visual comparison with the dataset rendering.

## Expected output

```text
[GLYVEX] FOUR-CORNER ACTIVATION COMPLETE — next touch starts capture
[GLYVEX] CAPTURE ARMED — draw the character
[GLYVEX] GESTURE COMPLETE: ...
=== GLYVEX ML RESULT ===
prediction: B
confidence: 0.99..
threshold:  0.9800
decision:   ACCEPT
```

An output below the threshold is rejected instead of routed anywhere.
