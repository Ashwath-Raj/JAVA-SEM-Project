package com.glyvex.touchpad;

import java.util.List;

public record ActivationConfig(
        List<Corner> sequence,
        double cornerMargin,
        long interCornerTimeoutMicros,
        boolean requireReleaseBetweenCorners
) {
    public ActivationConfig {
        sequence = List.copyOf(sequence);
        if (sequence.size() < 2) throw new IllegalArgumentException("activation sequence must have >= 2 corners");
        if (!(cornerMargin > 0 && cornerMargin < 0.5)) throw new IllegalArgumentException("cornerMargin must be in (0,0.5)");
        if (interCornerTimeoutMicros <= 0) throw new IllegalArgumentException("interCornerTimeoutMicros must be > 0");
    }

    public static ActivationConfig defaults() {
        return new ActivationConfig(
                List.of(Corner.TOP_LEFT, Corner.TOP_RIGHT, Corner.BOTTOM_RIGHT, Corner.BOTTOM_LEFT),
                0.28,
                4_000_000L,
                true
        );
    }
}
