package com.glyvex.ml;

import com.glyvex.touchpad.Gesture;
import com.glyvex.touchpad.Point;
import com.glyvex.touchpad.TouchStroke;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Java implementation of the exact GLYVEX training rasterization contract:
 * 128x128 grayscale, white background, black trajectory, bbox normalization,
 * aspect-ratio preservation, 12-pixel margin, stroke boundaries preserved,
 * 3-pixel line width.
 */
public final class GestureRasterizer {
    public static final int CANVAS = 128;
    public static final int MARGIN = 12;
    public static final int LINE_WIDTH = 3;

    public RasterizedGesture rasterize(Gesture gesture) {
        if (gesture == null) throw new IllegalArgumentException("gesture is null");
        List<StrokeData> strokes = flatten(gesture);
        if (strokes.isEmpty()) throw new IllegalArgumentException("gesture contains no points");

        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;

        for (StrokeData stroke : strokes) {
            for (Point point : stroke.points()) {
                minX = Math.min(minX, point.x());
                maxX = Math.max(maxX, point.x());
                minY = Math.min(minY, point.y());
                maxY = Math.max(maxY, point.y());
            }
        }

        double width = maxX - minX;
        double height = maxY - minY;
        double scaleX = width > 1e-12 ? (CANVAS - 2.0 * MARGIN) / width : Double.POSITIVE_INFINITY;
        double scaleY = height > 1e-12 ? (CANVAS - 2.0 * MARGIN) / height : Double.POSITIVE_INFINITY;

        final double scale;
        if (Double.isInfinite(scaleX) && Double.isInfinite(scaleY)) {
            scale = 1.0;
        } else if (Double.isInfinite(scaleX)) {
            scale = scaleY;
        } else if (Double.isInfinite(scaleY)) {
            scale = scaleX;
        } else {
            scale = Math.min(scaleX, scaleY);
        }

        List<List<PixelPoint>> mapped = new ArrayList<>(strokes.size());
        double outW = 0.0;
        double outH = 0.0;
        for (StrokeData stroke : strokes) {
            List<PixelPoint> mappedStroke = new ArrayList<>(stroke.points().size());
            for (Point point : stroke.points()) {
                double x = (point.x() - minX) * scale;
                double y = (point.y() - minY) * scale;
                mappedStroke.add(new PixelPoint(x, y));
                outW = Math.max(outW, x);
                outH = Math.max(outH, y);
            }
            mapped.add(mappedStroke);
        }

        double offsetX = (CANVAS - outW) / 2.0;
        double offsetY = (CANVAS - outH) / 2.0;

        BufferedImage image = new BufferedImage(CANVAS, CANVAS, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, CANVAS, CANVAS);
            graphics.setColor(Color.BLACK);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
            graphics.setStroke(new BasicStroke(LINE_WIDTH, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_ROUND));

            for (List<PixelPoint> stroke : mapped) {
                if (stroke.isEmpty()) continue;

                if (stroke.size() == 1) {
                    PixelPoint p = stroke.get(0);
                    drawPoint(graphics, p.x() + offsetX, p.y() + offsetY);
                    continue;
                }

                PixelPoint previous = null;
                for (PixelPoint p : stroke) {
                    double x = p.x() + offsetX;
                    double y = p.y() + offsetY;
                    if (previous != null) {
                        graphics.drawLine(
                                round(previous.x() + offsetX),
                                round(previous.y() + offsetY),
                                round(x),
                                round(y)
                        );
                    }
                    drawPoint(graphics, x, y);
                    previous = p;
                }
            }
        } finally {
            graphics.dispose();
        }

        return new RasterizedGesture(image, toModelInput(image),
                minX, minY, maxX, maxY, scale, offsetX, offsetY);
    }

    public void writePng(RasterizedGesture rasterized, Path path) throws IOException {
        Files.createDirectories(path.toAbsolutePath().normalize().getParent());
        ImageIO.write(rasterized.image(), "PNG", path.toFile());
    }

    private static void drawPoint(Graphics2D graphics, double x, double y) {
        int cx = round(x);
        int cy = round(y);
        int radius = Math.max(1, LINE_WIDTH / 2);
        graphics.fillOval(cx - radius, cy - radius, 2 * radius + 1, 2 * radius + 1);
    }

    private static int round(double v) {
        return (int) Math.round(v);
    }

    private static float[] toModelInput(BufferedImage image) {
        float[] input = new float[CANVAS * CANVAS];
        int i = 0;
        for (int y = 0; y < CANVAS; y++) {
            for (int x = 0; x < CANVAS; x++) {
                int gray = image.getRaster().getSample(x, y, 0);
                input[i++] = (gray / 255.0f - 0.5f) / 0.5f;
            }
        }
        return input;
    }

    private static List<StrokeData> flatten(Gesture gesture) {
        List<StrokeData> strokes = new ArrayList<>();
        for (TouchStroke stroke : gesture.strokes()) {
            if (!stroke.isEmpty()) strokes.add(new StrokeData(stroke.points()));
        }
        return strokes;
    }

    private record StrokeData(List<Point> points) {}
    private record PixelPoint(double x, double y) {}
}
