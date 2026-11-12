package com.glyvex.touchpad.demo;

import com.glyvex.touchpad.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class SelfTest {
    public static void main(String[] args) throws Exception {
        AtomicReference<Gesture> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        try (TouchpadInteractionEngine engine = new TouchpadInteractionEngine(
                ActivationConfig.defaults(),
                50,
                1000,
                g -> { result.set(g); latch.countDown(); },
                s -> { })) {

            long t = 1_000_000;
            double[][] corners = {
                    {0.05, 0.05},
                    {0.95, 0.05},
                    {0.95, 0.95},
                    {0.05, 0.95}
            };

            for (double[] c : corners) {
                engine.process(new TouchEvent(TouchEvent.Type.DOWN, 0, c[0], c[1], t));
                engine.process(new TouchEvent(TouchEvent.Type.UP, 0, c[0], c[1], t + 10));
                t += 100_000;
            }

            engine.process(new TouchEvent(TouchEvent.Type.DOWN, 0, 0.40, 0.30, t));
            engine.process(new TouchEvent(TouchEvent.Type.MOVE, 0, 0.50, 0.40, t + 10));
            engine.process(new TouchEvent(TouchEvent.Type.UP, 0, 0.60, 0.50, t + 20));

            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("gesture did not complete");
            }
            Gesture g = result.get();
            if (g == null || g.strokes().size() != 1 || g.pointCount() != 3) {
                throw new AssertionError("unexpected gesture result");
            }
        }

        System.out.println("SELF-TEST PASS");
    }
}
