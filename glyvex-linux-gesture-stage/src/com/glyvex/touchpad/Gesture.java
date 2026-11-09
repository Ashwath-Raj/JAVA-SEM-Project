package com.glyvex.touchpad;

import java.util.Collections;
import java.util.List;

public record Gesture(List<TouchStroke> strokes, long startedMicros, long endedMicros) {
    public Gesture {
        strokes = List.copyOf(strokes);
        if (strokes.isEmpty()) throw new IllegalArgumentException("gesture must contain at least one stroke");
    }

    public int pointCount() {
        return strokes.stream().mapToInt(TouchStroke::size).sum();
    }

    public long durationMicros() {
        return Math.max(0, endedMicros - startedMicros);
    }
}
