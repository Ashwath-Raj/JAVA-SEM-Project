package com.glyvex.ml;

import java.awt.image.BufferedImage;

public record RasterizedGesture(
        BufferedImage image,
        float[] modelInput,
        double minX,
        double minY,
        double maxX,
        double maxY,
        double scale,
        double offsetX,
        double offsetY
) {
    public RasterizedGesture {
        if (image == null) throw new IllegalArgumentException("image is null");
        if (modelInput == null || modelInput.length != 128 * 128) {
            throw new IllegalArgumentException("model input must contain 16384 floats");
        }
    }
}
