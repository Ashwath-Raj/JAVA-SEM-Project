package com.glyvex.ml;

public record Prediction(
        int classIndex,
        String character,
        double confidence,
        boolean accepted
) {}
