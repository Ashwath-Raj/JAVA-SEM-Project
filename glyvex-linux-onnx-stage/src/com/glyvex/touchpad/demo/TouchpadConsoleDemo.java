package com.glyvex.touchpad.demo;

import com.glyvex.touchpad.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Reads the existing native GLYVEX tab protocol from stdin.
 * Run the native helper as a separate process (the provided shell script does this).
 */
public final class TouchpadConsoleDemo {
    public static void main(String[] args) throws Exception {
        ActivationConfig activation = ActivationConfig.defaults();

        try (TouchpadInteractionEngine engine = new TouchpadInteractionEngine(
                activation,
                850,
                5000,
                gesture -> {
                    System.out.println();
                    System.out.println("=== GESTURE READY FOR ML ===");
                    System.out.println("strokes=" + gesture.strokes().size());
                    System.out.println("points=" + gesture.pointCount());
                    System.out.printf("duration=%.3fs%n", gesture.durationMicros() / 1_000_000.0);
                    for (int i = 0; i < gesture.strokes().size(); i++) {
                        TouchStroke s = gesture.strokes().get(i);
                        System.out.printf("stroke[%d] points=%d start=(%.3f,%.3f) end=(%.3f,%.3f)%n",
                                i + 1,
                                s.size(),
                                s.first().x(), s.first().y(),
                                s.last().x(), s.last().y());
                    }
                    System.out.println("===========================");
                },
                status -> System.out.println("[GLYVEX] " + status))) {

            System.out.println("GLYVEX Linux interaction demo");
            System.out.println("Activation: TL -> TR -> BR -> BL (release after each corner)");
            System.out.println("Then draw the character. Capture ends after 850 ms idle.");
            System.out.println();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    TouchEvent event = TouchpadProtocolParser.parse(line);
                    if (event != null) {
                        engine.process(event);
                    }
                }
            }
        }
    }

}
