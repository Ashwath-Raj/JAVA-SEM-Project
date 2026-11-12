package com.glyvex.ml;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtException;

import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class OnnxCharacterClassifier implements AutoCloseable {
    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String inputName;
    private final String outputName;
    private final List<String> classes;
    private final double confidenceThreshold;

    public OnnxCharacterClassifier(ModelResources resources) throws OrtException {
        this.environment = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        this.session = environment.createSession(resources.modelPath().toString(), options);
        this.inputName = session.getInputNames().iterator().next();
        this.outputName = session.getOutputNames().iterator().next();
        this.classes = resources.classes();
        this.confidenceThreshold = resources.confidenceThreshold();
    }

    public Prediction predict(float[] input) throws OrtException {
        if (input == null || input.length != 128 * 128) {
            throw new IllegalArgumentException("Expected 128x128 single-channel input");
        }

        float[][][][] tensorData = new float[1][1][128][128];
        int offset = 0;
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                tensorData[0][0][y][x] = input[offset++];
            }
        }

        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, tensorData);
             OrtSession.Result result = session.run(Collections.singletonMap(inputName, tensor))) {
            Object value = result.get(outputName).orElseThrow(() -> new OrtException("Missing ONNX output: " + outputName)).getValue();
            float[][] logits = (float[][]) value;
            return classify(logits[0]);
        }
    }

    private Prediction classify(float[] logits) {
        if (logits.length != classes.size()) {
            throw new IllegalStateException("Model returned " + logits.length + " logits for " + classes.size() + " classes");
        }

        double max = Double.NEGATIVE_INFINITY;
        for (float logit : logits) max = Math.max(max, logit);
        double sum = 0.0;
        double[] probs = new double[logits.length];
        for (int i = 0; i < logits.length; i++) {
            probs[i] = Math.exp(logits[i] - max);
            sum += probs[i];
        }
        int best = 0;
        for (int i = 0; i < probs.length; i++) {
            probs[i] /= sum;
            if (probs[i] > probs[best]) best = i;
        }

        double confidence = probs[best];
        return new Prediction(best, classes.get(best), confidence, confidence >= confidenceThreshold);
    }

    public double confidenceThreshold() {
        return confidenceThreshold;
    }

    @Override
    public void close() throws OrtException {
        session.close();
    }
}
