#!/usr/bin/env python3
"""
GLYVEX Synthetic Gesture Stream Generator.
Simulates raw evdev protocol events for four-corner activation and handwriting.
"""

import math
import sys
import time

def emit_corners(start_t=1_000_000):
    corners = [
        (0.05, 0.05),  # TOP_LEFT
        (0.95, 0.05),  # TOP_RIGHT
        (0.95, 0.95),  # BOTTOM_RIGHT
        (0.05, 0.95),  # BOTTOM_LEFT
    ]
    t = start_t
    events = []
    for x, y in corners:
        events.append(f"DOWN\t0\t{x:.6f}\t{y:.6f}\t{t}")
        t += 10_000
        events.append(f"UP\t0\t{x:.6f}\t{y:.6f}\t{t}")
        t += 150_000
    return events, t

def line_points(p1, p2, steps=25):
    pts = []
    for i in range(steps + 1):
        frac = i / float(steps)
        pts.append((p1[0] + frac * (p2[0] - p1[0]), p1[1] + frac * (p2[1] - p1[1])))
    return pts

def stroke_to_events(pts, start_t):
    t = start_t
    events = []
    if not pts:
        return events, t
    events.append(f"DOWN\t0\t{pts[0][0]:.6f}\t{pts[0][1]:.6f}\t{t}")
    for p in pts[1:-1]:
        t += 10_000
        events.append(f"MOVE\t0\t{p[0]:.6f}\t{p[1]:.6f}\t{t}")
    if len(pts) > 1:
        t += 10_000
        events.append(f"UP\t0\t{pts[-1][0]:.6f}\t{pts[-1][1]:.6f}\t{t}")
    return events, t

def generate_strokes(char):
    char = char.upper()
    strokes = []

    if char == 'C':
        pts = []
        for i in range(45):
            theta = 0.30 * math.pi + (i / 44.0) * (1.40 * math.pi)
            x = 0.50 + 0.25 * math.cos(theta)
            y = 0.50 - 0.25 * math.sin(theta)
            pts.append((x, y))
        strokes.append(pts)

    elif char == 'L':
        pts = line_points((0.35, 0.20), (0.35, 0.80), 30)
        pts += line_points((0.35, 0.80), (0.65, 0.80), 25)[1:]
        strokes.append(pts)

    elif char == 'T':
        # Stroke 1: horizontal bar
        strokes.append(line_points((0.30, 0.25), (0.70, 0.25), 30))
        # Stroke 2: vertical stem
        strokes.append(line_points((0.50, 0.25), (0.50, 0.80), 30))

    elif char == 'F':
        # Stroke 1: vertical stem
        strokes.append(line_points((0.35, 0.20), (0.35, 0.80), 30))
        # Stroke 2: top horizontal bar
        strokes.append(line_points((0.35, 0.22), (0.65, 0.22), 20))
        # Stroke 3: middle horizontal bar
        strokes.append(line_points((0.35, 0.50), (0.60, 0.50), 18))

    elif char == 'E':
        # Stroke 1: vertical stem
        strokes.append(line_points((0.35, 0.20), (0.35, 0.80), 30))
        # Stroke 2: top bar
        strokes.append(line_points((0.35, 0.22), (0.65, 0.22), 20))
        # Stroke 3: middle bar
        strokes.append(line_points((0.35, 0.50), (0.58, 0.50), 18))
        # Stroke 4: bottom bar
        strokes.append(line_points((0.35, 0.78), (0.65, 0.78), 20))

    elif char == 'S':
        pts = []
        for i in range(50):
            t = i / 49.0
            x = 0.50 + 0.16 * math.sin(t * 2 * math.pi)
            y = 0.25 + 0.50 * t
            pts.append((x, y))
        strokes.append(pts)

    elif char == 'B':
        # Stroke 1: vertical stem
        strokes.append(line_points((0.35, 0.20), (0.35, 0.80), 30))
        # Stroke 2: top and bottom loops
        loop_pts = []
        for i in range(25):
            theta = -0.5 * math.pi + (i / 24.0) * math.pi
            loop_pts.append((0.35 + 0.16 * math.cos(theta), 0.35 - 0.15 * math.sin(theta)))
        for i in range(25):
            theta = -0.5 * math.pi + (i / 24.0) * math.pi
            loop_pts.append((0.35 + 0.18 * math.cos(theta), 0.65 - 0.15 * math.sin(theta)))
        strokes.append(loop_pts)

    else:
        # Unknown/Ambiguous scribble to test rejection
        pts = line_points((0.40, 0.40), (0.60, 0.60), 15)
        strokes.append(pts)

    return strokes

def main():
    target = sys.argv[1] if len(sys.argv) > 1 else 'C'
    skip_corners = "--no-corners" in sys.argv

    events = []
    t = 1_000_000
    if not skip_corners:
        c_events, t = emit_corners(t)
        events.extend(c_events)
        t += 200_000

    strokes = generate_strokes(target)
    for s in strokes:
        s_events, t = stroke_to_events(s, t)
        events.extend(s_events)
        t += 60_000  # Pause between multi-strokes

    for e in events:
        print(e, flush=True)
        time.sleep(0.015)

    # Allow 1.5s for inactivity timer (850ms) to trigger completion
    time.sleep(1.8)

if __name__ == '__main__':
    main()
