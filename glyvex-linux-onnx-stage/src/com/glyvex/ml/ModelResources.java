package com.glyvex.ml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public record ModelResources(
        Path modelPath,
        List<String> classes,
        double confidenceThreshold
) {
    public ModelResources {
        classes = List.copyOf(classes);
        if (classes.isEmpty()) throw new IllegalArgumentException("no classes");
        if (!(confidenceThreshold >= 0.0 && confidenceThreshold <= 1.0)) {
            throw new IllegalArgumentException("confidence threshold must be in [0,1]");
        }
    }

    public static ModelResources load(Path modelPath, Path classesPath, Path gatePath) throws IOException {
        return load(modelPath, classesPath, gatePath, null);
    }

    public static ModelResources load(Path modelPath, Path classesPath, Path gatePath, Double thresholdOverride) throws IOException {
        if (!Files.isRegularFile(modelPath)) throw new IOException("Model not found: " + modelPath);
        ObjectMapper mapper = new ObjectMapper();

        JsonNode classRoot = mapper.readTree(classesPath.toFile());
        List<String> classes = new ArrayList<>();
        JsonNode names = classRoot.path("class_names");
        if (names.isArray()) {
            names.forEach(n -> classes.add(n.asText()));
        }
        if (classes.isEmpty()) {
            JsonNode indexMap = classRoot.path("index_to_class");
            List<String> temp = new ArrayList<>();
            indexMap.fields().forEachRemaining(e -> {
                int i = Integer.parseInt(e.getKey());
                while (temp.size() <= i) temp.add(null);
                temp.set(i, e.getValue().asText());
            });
            classes.addAll(temp);
        }

        double threshold = 0.98;
        if (thresholdOverride != null && thresholdOverride >= 0.0 && thresholdOverride <= 1.0) {
            threshold = thresholdOverride;
        } else if (Files.isRegularFile(gatePath)) {
            JsonNode gateRoot = mapper.readTree(gatePath.toFile());
            threshold = gateRoot.path("threshold").asDouble(0.98);
        }

        return new ModelResources(modelPath, classes, threshold);
    }
}
