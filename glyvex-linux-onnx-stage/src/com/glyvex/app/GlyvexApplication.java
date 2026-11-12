package com.glyvex.app;

import com.glyvex.commands.CommandAction;
import com.glyvex.commands.CommandRegistry;
import com.glyvex.config.GlyvexAppConfig;
import com.glyvex.ml.GestureRasterizer;
import com.glyvex.ml.ModelResources;
import com.glyvex.ml.OnnxCharacterClassifier;
import com.glyvex.ml.Prediction;
import com.glyvex.ml.RasterizedGesture;
import com.glyvex.touchpad.Gesture;
import com.glyvex.touchpad.Point;
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
import java.util.List;

/**
 * GLYVEX Full Application Coordinator:
 * Touchpad Event Stream -> Four-Corner FSM -> Gesture Capture -> 128x128 Rasterizer
 * -> ResNet-18 ONNX Inference -> Softmax & Confidence Gating -> Command Registry Execution.
 */
public final class GlyvexApplication {
    private final GlyvexAppConfig config;
    private final CommandRegistry registry;
    private final ModelResources resources;
    private final OnnxCharacterClassifier classifier;
    private final GestureRasterizer rasterizer;

    public GlyvexApplication(GlyvexAppConfig config) throws Exception {
        this.config = config;
        this.registry = config.buildRegistry();
        this.resources = ModelResources.load(
                config.modelPath(),
                config.classesPath(),
                config.gatePath(),
                config.thresholdOverride()
        );
        this.classifier = new OnnxCharacterClassifier(resources);
        this.rasterizer = new GestureRasterizer();

        DesktopFeedback.setDesktopNotificationsEnabled(config.notificationsEnabled());
        Files.createDirectories(config.debugDir());
    }

    public void runFromStandardInput() throws Exception {
        System.out.println("================================================================================");
        System.out.println("           GLYVEX: TOUCHPAD GESTURE INTERACTION & COMMAND SYSTEM                ");
        System.out.println("================================================================================");
        System.out.println("Model:         " + resources.modelPath().toAbsolutePath().normalize());
        System.out.println("Classes:       " + resources.classes());
        System.out.printf("Threshold:     %.4f (%.2f%%)%n", classifier.confidenceThreshold(), classifier.confidenceThreshold() * 100);
        System.out.println("Dry Run:       " + (registry.isGlobalDryRun() ? "ENABLED (Safe mode)" : "DISABLED (Live launch)"));
        System.out.println("Active Mappings:");
        registry.allActions().forEach((sym, act) ->
                System.out.printf("  [%s] -> %-15s [%s]%n", sym, act.displayName(), String.join(" ", act.commandLine())));
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("State: WAITING FOR ACTIVATION (Touch Corners: TL -> TR -> BR -> BL)...");
        System.out.println("================================================================================");
        System.out.println();

        try (TouchpadInteractionEngine engine = new TouchpadInteractionEngine(
                config.activationConfig(),
                config.inactivityTimeoutMillis(),
                config.maxGestureDurationMillis(),
                this::handleGestureComplete,
                this::handleStatusChange
        )) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    TouchEvent event = TouchpadProtocolParser.parse(line);
                    if (event != null) engine.process(event);
                }
            }
        } finally {
            classifier.close();
        }
    }

    public void processGesture(Gesture gesture) {
        handleGestureComplete(gesture);
    }

    private void handleStatusChange(String status) {
        System.out.println("[GLYVEX] " + status);
        if (status.contains("FOUR-CORNER ACTIVATION COMPLETE")) {
            DesktopFeedback.onArmed();
        } else if (status.contains("CANCELLED") || status.contains("TIMEOUT")) {
            DesktopFeedback.onReset(status);
        }
    }

    private void handleGestureComplete(Gesture gesture) {
        try {
            RasterizedGesture rasterized = rasterizer.rasterize(gesture);
            Path debugPng = config.debugDir().resolve("gesture-" + Instant.now().toEpochMilli() + ".png");
            rasterizer.writePng(rasterized, debugPng);

            Prediction prediction = classifier.predict(rasterized.modelInput());
            CommandAction action = registry.getCommand(prediction.character());

            System.out.println();
            System.out.println("========================== GLYVEX PIPELINE RESULT ==========================");
            System.out.printf("Strokes:       %d stroke(s), %d total points%n", gesture.strokes().size(), gesture.pointCount());
            System.out.printf("Prediction:    %s%n", prediction.character());
            System.out.printf("Confidence:    %.4f (%.2f%%)%n", prediction.confidence(), prediction.confidence() * 100);
            System.out.printf("Threshold:     %.4f (%.2f%%)%n", classifier.confidenceThreshold(), classifier.confidenceThreshold() * 100);
            System.out.printf("Decision:      %s%n", (prediction.accepted() ? "ACCEPT" : "REJECT"));
            System.out.printf("Debug Raster:  %s%n", debugPng.toAbsolutePath().normalize());

            DesktopFeedback.onPredictionResult(prediction, action, classifier.confidenceThreshold());

            if (prediction.accepted()) {
                if (action != null) {
                    System.out.printf("Action:        EXECUTING -> %s [%s]%n",
                            action.displayName(), String.join(" ", action.commandLine()));
                    registry.execute(prediction.character());
                } else {
                    System.out.printf("Action:        NO COMMAND REGISTERED FOR SYMBOL '%s'%n", prediction.character());
                }
            } else {
                System.out.println("Action:        BLOCKED (Confidence below threshold. No command executed.)");
            }
            System.out.println("============================================================================");
            System.out.println();

        } catch (Exception ex) {
            System.err.println("[GLYVEX ERROR] Failed to process gesture: " + ex.getMessage());
            ex.printStackTrace(System.err);
        }
    }

    public CommandRegistry registry() {
        return registry;
    }

    public OnnxCharacterClassifier classifier() {
        return classifier;
    }

    public static void main(String[] args) {
        try {
            Path projectRoot = Paths.get("..").toAbsolutePath().normalize();
            Path configFile = Paths.get("config/glyvex-config.json");

            for (int i = 0; i < args.length - 1; i++) {
                if ("--config".equals(args[i])) {
                    configFile = Paths.get(args[i + 1]);
                } else if ("--root".equals(args[i])) {
                    projectRoot = Paths.get(args[i + 1]);
                }
            }

            GlyvexAppConfig config = GlyvexAppConfig.loadOrDefault(configFile, projectRoot);

            // Handle optional command-line flags
            boolean dryRun = config.dryRun();
            Double threshold = config.thresholdOverride();
            for (int i = 0; i < args.length; i++) {
                if ("--dry-run".equals(args[i])) dryRun = true;
                if ("--execute".equals(args[i])) dryRun = false;
                if ("--threshold".equals(args[i]) && i < args.length - 1) {
                    threshold = Double.parseDouble(args[i + 1]);
                }
            }

            if (dryRun != config.dryRun() || (threshold != null && !threshold.equals(config.thresholdOverride()))) {
                config = new GlyvexAppConfig(
                        config.activationConfig(),
                        config.inactivityTimeoutMillis(),
                        config.maxGestureDurationMillis(),
                        config.modelPath(),
                        config.classesPath(),
                        config.gatePath(),
                        threshold,
                        config.debugDir(),
                        dryRun,
                        config.notificationsEnabled(),
                        config.customCommands()
                );
            }

            GlyvexApplication app = new GlyvexApplication(config);
            app.runFromStandardInput();

        } catch (Exception e) {
            System.err.println("[GLYVEX FATAL] Application crashed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
