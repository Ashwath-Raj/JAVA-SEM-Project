package com.glyvex.touchpad;

public record Point(double x, double y, long timestampMicros) {
    public Point {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("x/y must be finite");
        }
        if (x < 0.0 || x > 1.0 || y < 0.0 || y > 1.0) {
            throw new IllegalArgumentException("x/y must be normalized to [0,1]");
        }
    }
}
