package com.glyvex.touchpad.demo;

import com.glyvex.commands.CommandAction;
import com.glyvex.commands.CommandRegistry;
import com.glyvex.ml.GestureRasterizer;
import com.glyvex.ml.ModelResources;
import com.glyvex.ml.OnnxCharacterClassifier;
import com.glyvex.ml.Prediction;
import com.glyvex.ml.RasterizedGesture;
import com.glyvex.touchpad.ActivationConfig;
import com.glyvex.touchpad.Gesture;
import com.glyvex.touchpad.TouchEvent;
import com.glyvex.touchpad.TouchpadInteractionEngine;
import com.glyvex.touchpad.TouchpadProtocolParser;
import com.glyvex.ui.DesktopFeedback;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

/**
 * Full Linux demo: touchpad event stream -> activation -> gesture -> raster -> ONNX -> command execution.
 */
public final class EndToEndDemo {
    private EndToEndDemo() {}

    public static void main(String[] args) throws Exception {
        Path projectRoot = Paths.get("..").toAbsolutePath().normalize();
        Path model = pathArg(args, "--model", projectRoot.resolve("Dataset/GLYVEX/ml/demo_retrain/onnx/glyvex_demo_char_classifier.onnx"));
        Path classes = pathArg(args, "--classes", projectRoot.resolve("Dataset/GLYVEX/ml/demo_retrain/onnx/classes.json"));
        Path gate = pathArg(args, "--gate", projectRoot.resolve("Dataset/GLYVEX/ml/demo_retrain/confidence_gate.json"));
        Path debugDir = pathArg(args, "--debug", Paths.get("runtime/debug"));
        Files.createDirectories(debugDir);

        Double thresholdOverride = doubleArg(args, "--threshold", null);
        boolean dryRun = hasFlag(args, "--dry-run");

        CommandRegistry registry = CommandRegistry.createDefaultLinux();
        registry.setGlobalDryRun(dryRun);

        System.out.println("================================================================================");
        System.out.println("GLYVEX Linux END-TO-END DEMO (WITH COMMAND REGISTRY)");
        System.out.println("================================================================================");
        System.out.println("Model:       " + model);
        System.out.println("Classes:     " + classes);
        System.out.println("Gate:        " + gate);
        System.out.println("Debug:       " + debugDir.toAbsolutePath().normalize());
        System.out.println("Dry Run:     " + (dryRun ? "ENABLED (Will not spawn OS processes)" : "DISABLED (Live execution)"));
        System.out.println("Allowlisted Actions: " + registry.allActions().keySet());
        System.out.println("================================================================================");

        ModelResources resources = ModelResources.load(model, classes, gate, thresholdOverride);
        GestureRasterizer rasterizer = new GestureRasterizer();

        try (OnnxCharacterClassifier classifier = new OnnxCharacterClassifier(resources)) {
            System.out.printf("Loaded ONNX. Classes=%s threshold=%.3f%n", resources.classes(), classifier.confidenceThreshold());
            System.out.println("Waiting for TL -> TR -> BR -> BL activation, then draw...\n");

            TouchpadInteractionEngine engine = new TouchpadInteractionEngine(
                    ActivationConfig.defaults(),
                    850,
                    5000,
                    gesture -> processGesture(gesture, rasterizer, classifier, registry, debugDir),
                    status -> {
                        System.out.println("[GLYVEX] " + status);
                        if (status.contains("FOUR-CORNER ACTIVATION COMPLETE")) {
                            DesktopFeedback.onArmed();
                        }
                    }
            );

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    TouchEvent event = TouchpadProtocolParser.parse(line);
                    if (event != null) engine.process(event);
                }
            } finally {
                engine.close();
            }
        }
    }

    private static void processGesture(
            Gesture gesture,
            GestureRasterizer rasterizer,
            OnnxCharacterClassifier classifier,
            CommandRegistry registry,
            Path debugDir
    ) {
        try {
            RasterizedGesture rasterized = rasterizer.rasterize(gesture);
            Path imagePath = debugDir.resolve("gesture-" + Instant.now().toEpochMilli() + ".png");
            rasterizer.writePng(rasterized, imagePath);

            Prediction prediction = classifier.predict(rasterized.modelInput());
            CommandAction action = registry.getCommand(prediction.character());

            System.out.println();
            System.out.println("=== GLYVEX ML & COMMAND RESULT ===");
            System.out.println("strokes:     " + gesture.strokes().size());
            System.out.println("points:      " + gesture.pointCount());
            System.out.printf("prediction:  %s%n", prediction.character());
            System.out.printf("confidence:  %.4f%n", prediction.confidence());
            System.out.printf("threshold:   %.4f%n", classifier.confidenceThreshold());
            System.out.println("decision:    " + (prediction.accepted() ? "ACCEPT" : "REJECT"));
            System.out.println("raster:      " + imagePath.toAbsolutePath().normalize());

            DesktopFeedback.onPredictionResult(prediction, action, classifier.confidenceThreshold());

            if (prediction.accepted()) {
                if (action != null) {
                    System.out.printf("ACTION:      LAUNCHING -> %s [%s]%n",
                            action.displayName(), String.join(" ", action.commandLine()));
                    registry.execute(prediction.character());
                } else {
                    System.out.printf("ACTION:      NONE (No command registered for symbol '%s')%n", prediction.character());
                }
            } else {
                System.out.println("ACTION:      BLOCKED (Prediction below safety threshold)");
            }
            System.out.println("==================================");
            System.out.println();
        } catch (Exception ex) {
            System.err.println("[GLYVEX] ML pipeline error: " + ex.getMessage());
            ex.printStackTrace(System.err);
        }
    }

    private static Path pathArg(String[] args, String flag, Path fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equals(args[i])) return Paths.get(args[i + 1]);
        }
        return fallback;
    }

    private static Double doubleArg(String[] args, String flag, Double fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equals(args[i])) {
                try {
                    return Double.parseDouble(args[i + 1]);
                } catch (NumberFormatException ignored) {}
            }
        }
        return fallback;
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String a : args) {
            if (flag.equals(a)) return true;
        }
        return false;
    }
}
