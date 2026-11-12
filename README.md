# GLYVEX: Linux Touchpad Gesture Interaction & Command System

[![Java](https://img.shields.io/badge/Java-17%2B-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Linux](https://img.shields.io/badge/Linux-Kernel_evdev-FCC624?style=for-the-badge&logo=linux&logoColor=black)](https://www.kernel.org/)
[![ONNX Runtime](https://img.shields.io/badge/ONNX_Runtime-1.30.0-005CED?style=for-the-badge&logo=onnx&logoColor=white)](https://onnxruntime.ai/)
[![Architecture](https://img.shields.io/badge/Model-ResNet--18-45B8AC?style=for-the-badge)](https://pytorch.org/)
[![Build Status](https://img.shields.io/badge/Tests-6%2F6%20Passing-brightgreen?style=for-the-badge)]()

**GLYVEX** is a low-latency, hardware-level Linux touchpad gesture execution system. It captures raw Linux multi-touch input via `evdev`, enforces a fail-safe **Four-Corner Activation State Machine**, rasterizes multi-stroke gestures into scale-invariant 128×128 grayscale tensors, classifies handwritten characters using an embedded **ResNet-18 ONNX** neural network with confidence gating, and executes allowlisted desktop commands.

---

## 📌 Architecture & Pipeline

```text
 ┌──────────────────────┐
 │ Linux Hardware Input │  /dev/input/eventX (MSFT / Elan / Synaptics Touchpad)
 └──────────┬───────────┘
            │  Kernel Multi-Touch Protocol Type B (ABS_MT_*)
            ▼
 ┌──────────────────────┐
 │  glyvex-touchpad (C) │  High-performance evdev reader with multi-slot tracking & normalization
 └──────────┬───────────┘
            │  Structured Stream: TYPE \t SLOT \t X \t Y \t TIMESTAMP_US
            ▼
 ┌──────────────────────┐
 │ CornerActivationFSM  │  Four-Corner Guard (TL -> TR -> BR -> BL); rejects accidental touches
 └──────────┬───────────┘
            │  Armed trigger
            ▼
 ┌──────────────────────┐
 │    GestureCapture    │  Multi-stroke accumulator with inter-stroke timeouts & max duration limits
 └──────────┬───────────┘
            │  Gesture Stroke Vector
            ▼
 ┌──────────────────────┐
 │  GestureRasterizer   │  128x128 aspect-preserving, centered, Cartesian tensor rasterizer
 └──────────┬───────────┘
            │  Float Tensor [-1.0, 1.0]
            ▼
 ┌──────────────────────┐
 │  ONNX Classifier     │  Embedded ResNet-18 Inference Engine + Softmax + Confidence Gating
 └──────────┬───────────┘
            │  Accepted Prediction (Confidence >= Threshold)
            ▼
 ┌──────────────────────┐
 │   CommandRegistry    │  Allowlisted desktop actions, dry-run safety & desktop notifications
 └──────────────────────┘
```

---

## 🚀 Key Features

- **Hardware Multi-Slot Reader (`glyvex_touchpad.c`):** C binary tracking up to 32 kernel multi-touch slots, normalizes single-finger input, resets cleanly on finger lift, and rejects multi-finger/palm touches without stream lockouts.
- **Fail-Safe Four-Corner FSM:** Eliminates unintended gestures during standard mouse navigation by requiring an explicit corner tap sequence ($TL \rightarrow TR \rightarrow BR \rightarrow BL$) within a comfortable $4.0\text{s}$ timeout window and $28\%$ corner margin.
- **Real-Time Step-by-Step Terminal Diagnostics:** Live console reporting of corner detections, remaining targets, and exact $(x, y)$ touch coordinates if a tap lands outside the target margin.
- **Embedded ResNet-18 ONNX Engine:** Zero cloud dependencies. Runs inference locally in $< 15\text{ms}$ with sub-millisecond tensor normalization.
- **Confidence Gating & Security Allowlisting:** Dynamic threshold gating (default $\ge 88\%$) rejects ambiguous strokes. Strict allowlist execution ensures only explicitly configured commands can be launched.
- **Desktop Visual Feedback:** Native desktop notifications via `notify-send` for activation, execution, and rejection events.
- **Safe Dry-Run Mode:** Test gestures and inspect classifications without triggering live system applications.

---

## ⌨️ Gesture Vocabulary & Command Mappings

| Character | Action Name | Default Executable Command | Description |
| :---: | :--- | :--- | :--- |
| **`B`** | Web Browser | `xdg-open https://google.com` | Opens default web browser |
| **`C`** | Calculator | `gnome-calculator` | Launches desktop calculator |
| **`F`** | File Manager | `xdg-open .` | Opens current workspace in file manager |
| **`T`** | Terminal | `x-terminal-emulator` | Spawns a new terminal window |
| **`E`** | Text Editor | `gedit` | Opens graphical text editor |
| **`S`** | System Settings | `gnome-control-center` | Opens system control center |

*All mappings can be customized in `config/glyvex-config.json`.*

---

## 🛠️ Prerequisites & Installation

### System Requirements
- **OS:** Linux (Ubuntu/Debian, Fedora, Arch, etc.)
- **Touchpad:** Precision touchpad supporting Linux `evdev` (I2C/HID/Synaptics/Elan)
- **Runtime:** Java 17 or higher
- **Build Tools:** `gcc`, `make` (or standard build-essential)
- **Desktop Notifications:** `libnotify-bin` (`notify-send`)

### Verifying Dependencies
```bash
gcc --version
java -version
notify-send --version
```

---

## ⚡ Quickstart Guide

### 1. Build the System
Builds the native C driver and compiles all Java subsystems:
```bash
./scripts/build.sh
```

### 2. Run Comprehensive Automated Tests
Verifies FSM activation, wrong corner resets, 128×128 rasterizer parity, ONNX model classification, confidence gating, and the emergency kill switch:
```bash
./scripts/test.sh
```

### 3. Run on Physical Touchpad
Starts GLYVEX with automatic touchpad detection (`/dev/input/eventX`):
```bash
./scripts/run.sh
```

*(Optional)* Run in **Dry-Run mode** to practice drawing without launching apps:
```bash
./scripts/run.sh --dry-run
```

### 4. Run Synthetic Stream Simulation
Test end-to-end classification and command routing without physical hardware:
```bash
# Simulate gesture 'C' (Calculator)
./scripts/simulate.sh C --dry-run

# Simulate gesture 'T' (Terminal)
./scripts/simulate.sh T --dry-run
```

---

## 🖐️ How Activation & Gesture Drawing Works

### The Four-Corner Activation Sequence
To arm the system for drawing, tap the four corners of your touchpad in sequence:

```text
 ┌───────────────┐
 │ [1]       [2] │   1. Tap TOP_LEFT
 │  TL        TR │   2. Tap TOP_RIGHT
 │               │   3. Tap BOTTOM_RIGHT
 │  BL        BR │   4. Tap BOTTOM_LEFT
 │ [4]       [3] │
 └───────────────┘
```

**Real-Time Terminal Output:**
```text
[GLYVEX] [CORNER 1/4] TOP_LEFT touch detected at (0.12, 0.10) — release finger to confirm
[GLYVEX] [CORNER 1/4] TOP_LEFT ✓ — Next: TOP_RIGHT
[GLYVEX] [CORNER 2/4] TOP_RIGHT touch detected at (0.91, 0.08) — release finger to confirm
[GLYVEX] [CORNER 2/4] TOP_RIGHT ✓ — Next: BOTTOM_RIGHT
[GLYVEX] [CORNER 3/4] BOTTOM_RIGHT touch detected at (0.93, 0.92) — release finger to confirm
[GLYVEX] [CORNER 3/4] BOTTOM_RIGHT ✓ — Next: BOTTOM_LEFT
[GLYVEX] [CORNER 4/4] BOTTOM_LEFT touch detected at (0.09, 0.90) — release finger to confirm
[GLYVEX] [CORNER 4/4] BOTTOM_LEFT ✓ — FOUR-CORNER ACTIVATION COMPLETE! Ready to draw.
[GLYVEX] ACTIVATION ARMED — draw gesture character on touchpad now
```

Once armed, simply draw your desired character (single or multi-stroke) anywhere on the touchpad. When you lift your finger, GLYVEX waits $850\text{ms}$ for additional strokes, rasterizes the gesture, runs inference, and executes the associated command!

---

## 🧠 Machine Learning & Preprocessing

The preprocessing pipeline reproduces the training contract used by the DigiLeTs dataset:
- **Canvas Resolution:** 128×128 grayscale.
- **Stroke Normalization:** Aspect-preserving bounding-box scaling with a 12-pixel margin.
- **Stroke Thickness:** 3-pixel anti-aliased trajectory.
- **Coordinate System:** Cartesian normalization with inverted Y mapping to match tablet training data.
- **Tensor Normalization:** Standardized to $[-1.0, 1.0]$ via `(gray / 255.0 - 0.5) / 0.5`.
- **Confidence Gate:** Softmax probabilities must meet or exceed the class threshold (default $88\%$) to prevent false positives.
- **Debug Inspection:** Every captured raster is optionally exported to `glyvex-linux-onnx-stage/runtime/debug/` for visual inspection.

---

## 📁 Repository Structure

```text
.
├── config/
│   └── glyvex-config.json              # Main application configuration & command mappings
├── Dataset/
│   └── GLYVEX/
│       ├── ml/demo_retrain/onnx/       # Production ResNet-18 ONNX model & confidence gate
│       └── metadata/                   # Class mappings and evaluation metrics
├── Docs/
│   ├── GLYVEX_Submission_Worthy_Project_Report.pdf
│   ├── GLYVEX_Research_Paper_Style_Final.pdf
│   └── GLYVEX_Project_Documentation_FINAL.pdf
├── glyvex-linux-onnx-stage/
│   ├── src/com/glyvex/
│   │   ├── app/                        # Main CLI Application Coordinator
│   │   ├── commands/                   # Command Registry & Security Execution
│   │   ├── config/                     # Configuration Parser (Jackson)
│   │   ├── ml/                         # ONNX Runtime Inference & 128x128 Rasterizer
│   │   ├── test/                       # Pipeline Regression Test Suite
│   │   ├── touchpad/                   # Touchpad Protocol, FSM, and Capture Engine
│   │   └── ui/                         # Desktop Notifications (notify-send)
│   ├── target/classpath.txt            # Precomputed Maven Classpath for standalone run
│   └── pom.xml                         # Maven dependencies (ONNX Runtime, Jackson)
├── glyvex-linux-touchpad/
│   └── native/linux/touchpad/
│       └── glyvex_touchpad.c           # Linux evdev C reader with multi-slot tracking
├── scripts/
│   ├── build.sh                        # Automated C & Java compilation script
│   ├── test.sh                         # Full regression test suite runner
│   ├── run.sh                          # Physical hardware launcher (with auto-detect)
│   ├── simulate.sh                     # Synthetic gesture simulation runner
│   └── simulate_gesture.py             # Synthetic evdev stream generator
├── .gitignore                          # Production Git ignore rules
└── README.md                           # Project documentation
```

---

## 🔒 Safety & Security Design

1. **Strict Command Allowlisting:** Commands must be explicitly registered with predefined argument arrays (`ProcessBuilder`), eliminating shell injection risks.
2. **Emergency Kill Switch:** A global kill switch disables all execution immediately upon trigger.
3. **Multi-Touch Palm Rejection:** Multiple contacts or resting palms immediately cancel the current stroke without locking up the driver.
4. **Isolated Desktop Execution:** Commands run as child processes under user-space permissions only.

---

## 📄 License & Academic Attribution

Developed as a Java Semester Capstone Project. Comprehensive academic research paper, system documentation, and architecture reports are available in the [`Docs/`](Docs/) directory.
