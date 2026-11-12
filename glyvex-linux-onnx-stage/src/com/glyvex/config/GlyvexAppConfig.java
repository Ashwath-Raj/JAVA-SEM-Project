package com.glyvex.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.glyvex.commands.CommandAction;
import com.glyvex.commands.CommandRegistry;
import com.glyvex.touchpad.ActivationConfig;
import com.glyvex.touchpad.Corner;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public record GlyvexAppConfig(
        ActivationConfig activationConfig,
        long inactivityTimeoutMillis,
        long maxGestureDurationMillis,
        Path modelPath,
        Path classesPath,
        Path gatePath,
        Double thresholdOverride,
        Path debugDir,
        boolean dryRun,
        boolean notificationsEnabled,
        Map<String, CommandAction> customCommands
) {

    public static GlyvexAppConfig loadOrDefault(Path configPath, Path projectRoot) {
        if (configPath != null && Files.isRegularFile(configPath)) {
            try {
                return parseJson(configPath, projectRoot);
            } catch (Exception ex) {
                System.err.println("[GLYVEX CONFIG] Warning: Could not parse " + configPath + " (" + ex.getMessage() + "). Using defaults.");
            }
        }
        return createDefaults(projectRoot);
    }

    public static GlyvexAppConfig createDefaults(Path projectRoot) {
        Path root = projectRoot != null ? projectRoot : Paths.get("..").toAbsolutePath().normalize();
        Path model = root.resolve("Dataset/GLYVEX/ml/demo_retrain/onnx/glyvex_demo_char_classifier.onnx");
        Path classes = root.resolve("Dataset/GLYVEX/ml/demo_retrain/onnx/classes.json");
        Path gate = root.resolve("Dataset/GLYVEX/ml/demo_retrain/confidence_gate.json");
        Path debug = Paths.get("runtime/debug");

        return new GlyvexAppConfig(
                ActivationConfig.defaults(),
                850L,
                5000L,
                model,
                classes,
                gate,
                null,
                debug,
                false,
                true,
                Collections.emptyMap()
        );
    }

    private static GlyvexAppConfig parseJson(Path jsonPath, Path projectRoot) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(jsonPath.toFile());
        Path base = projectRoot != null ? projectRoot : jsonPath.toAbsolutePath().getParent();

        // Activation section
        JsonNode act = root.path("activation");
        List<Corner> sequence = new ArrayList<>();
        if (act.path("sequence").isArray()) {
            for (JsonNode c : act.path("sequence")) {
                sequence.add(Corner.valueOf(c.asText().toUpperCase()));
            }
        }
        if (sequence.isEmpty()) {
            sequence = List.of(Corner.TOP_LEFT, Corner.TOP_RIGHT, Corner.BOTTOM_RIGHT, Corner.BOTTOM_LEFT);
        }
        double margin = act.path("cornerMargin").asDouble(0.28);
        long interCornerTimeoutMicros = (long) (act.path("interCornerTimeoutMillis").asDouble(4000) * 1000L);
        boolean requireRelease = act.path("requireReleaseBetweenCorners").asBoolean(true);
        ActivationConfig activationConfig = new ActivationConfig(sequence, margin, interCornerTimeoutMicros, requireRelease);

        // Capture section
        JsonNode cap = root.path("capture");
        long inactivity = cap.path("inactivityTimeoutMillis").asLong(850L);
        long maxDuration = cap.path("maxGestureDurationMillis").asLong(5000L);

        // ML section
        JsonNode ml = root.path("ml");
        Path model = resolvePath(base, ml.path("modelPath").asText("Dataset/GLYVEX/ml/demo_retrain/onnx/glyvex_demo_char_classifier.onnx"));
        Path classes = resolvePath(base, ml.path("classesPath").asText("Dataset/GLYVEX/ml/demo_retrain/onnx/classes.json"));
        Path gate = resolvePath(base, ml.path("gatePath").asText("Dataset/GLYVEX/ml/demo_retrain/confidence_gate.json"));
        Double thresholdOverride = ml.hasNonNull("overrideThreshold") ? ml.path("overrideThreshold").asDouble() : null;

        Path debugDir = resolvePath(base, root.path("debugDir").asText("runtime/debug"));
        boolean dryRun = root.path("dryRun").asBoolean(false);
        boolean notifications = root.path("notificationsEnabled").asBoolean(true);

        // Commands section
        Map<String, CommandAction> custom = new HashMap<>();
        JsonNode cmdNode = root.path("commands");
        if (cmdNode.isObject()) {
            cmdNode.fields().forEachRemaining(entry -> {
                String sym = entry.getKey().toUpperCase();
                JsonNode val = entry.getValue();
                List<String> cmd = new ArrayList<>();
                if (val.isArray()) {
                    val.forEach(v -> cmd.add(v.asText()));
                } else if (val.isTextual()) {
                    cmd.addAll(Arrays.asList(val.asText().split("\\s+")));
                }
                if (!cmd.isEmpty()) {
                    custom.put(sym, new CommandAction(sym, sym, cmd, dryRun));
                }
            });
        }

        return new GlyvexAppConfig(
                activationConfig,
                inactivity,
                maxDuration,
                model,
                classes,
                gate,
                thresholdOverride,
                debugDir,
                dryRun,
                notifications,
                custom
        );
    }

    private static Path resolvePath(Path base, String raw) {
        Path p = Paths.get(raw);
        if (p.isAbsolute()) return p;
        if (Files.exists(p)) return p;
        return base.resolve(p).toAbsolutePath().normalize();
    }

    public CommandRegistry buildRegistry() {
        CommandRegistry registry = CommandRegistry.createDefaultLinux();
        registry.setGlobalDryRun(dryRun);
        customCommands.forEach(registry::register);
        return registry;
    }
}
