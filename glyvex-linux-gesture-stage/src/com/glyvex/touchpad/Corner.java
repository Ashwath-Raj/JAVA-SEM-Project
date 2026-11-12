package com.glyvex.touchpad;

public enum Corner {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_RIGHT,
    BOTTOM_LEFT;

    public boolean contains(double x, double y, double margin) {
        double m = Math.max(0.01, Math.min(0.49, margin));
        return switch (this) {
            case TOP_LEFT -> x <= m && y <= m;
            case TOP_RIGHT -> x >= 1.0 - m && y <= m;
            case BOTTOM_RIGHT -> x >= 1.0 - m && y >= 1.0 - m;
            case BOTTOM_LEFT -> x <= m && y >= 1.0 - m;
        };
    }
}
