package com.glyvex.ml;

import com.glyvex.touchpad.Gesture;
import com.glyvex.touchpad.Point;
import com.glyvex.touchpad.TouchStroke;

import java.util.List;

public final class RasterizerSelfTest {
    public static void main(String[] args) {
        TouchStroke a = new TouchStroke();
        a.add(new Point(0.10, 0.10, 1));
        a.add(new Point(0.10, 0.90, 2));
        TouchStroke b = new TouchStroke();
        b.add(new Point(0.10, 0.10, 3));
        b.add(new Point(0.90, 0.10, 4));
        Gesture g = new Gesture(List.of(a, b), 1, 4);
        RasterizedGesture r = new GestureRasterizer().rasterize(g);
        if (r.image().getWidth() != 128 || r.image().getHeight() != 128) throw new AssertionError("bad image size");
        if (r.modelInput().length != 16384) throw new AssertionError("bad input length");
        for (float v : r.modelInput()) {
            if (v < -1.0001f || v > 1.0001f) throw new AssertionError("bad normalized pixel");
        }
        System.out.println("RASTERIZER SELF-TEST PASS");
    }
}
