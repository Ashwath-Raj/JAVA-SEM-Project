package com.glyvex.test;

import com.glyvex.commands.CommandAction;
import com.glyvex.commands.CommandRegistry;
import com.glyvex.config.GlyvexAppConfig;
import com.glyvex.ml.GestureRasterizer;
import com.glyvex.ml.ModelResources;
import com.glyvex.ml.OnnxCharacterClassifier;
import com.glyvex.ml.Prediction;
import com.glyvex.ml.RasterizedGesture;
import com.glyvex.touchpad.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end integration and regression test suite.
 * Validates FSM activation, gesture capture, 128x128 rasterization,
 * ONNX inference, confidence gating, and command allowlist execution.
 */
public final class PipelineIntegrationTest {

    public static void main(String[] args) throws Exception {
        System.out.println("================================================================================");
        System.out.println("RUNNING GLYVEX AUTOMATED PIPELINE & REGRESSION TESTS");
        System.out.println("================================================================================");

        int passed = 0;
        int failed = 0;

        try {
            testActivationFsmSuccess();
            System.out.println("PASS: testActivationFsmSuccess");
            passed++;
        } catch (Throwable t) {
            System.err.println("FAIL: testActivationFsmSuccess: " + t.getMessage());
            failed++;
        }

        try {
            testActivationFsmWrongCornerReset();
            System.out.println("PASS: testActivationFsmWrongCornerReset");
            passed++;
        } catch (Throwable t) {
            System.err.println("FAIL: testActivationFsmWrongCornerReset: " + t.getMessage());
            failed++;
        }

        try {
            testRasterizerParity();
            System.out.println("PASS: testRasterizerParity");
            passed++;
        } catch (Throwable t) {
            System.err.println("FAIL: testRasterizerParity: " + t.getMessage());
            failed++;
        }

        try {
            testOnnxClassificationAndCommandExecution();
            System.out.println("PASS: testOnnxClassificationAndCommandExecution");
            passed++;
        } catch (Throwable t) {
            System.err.println("FAIL: testOnnxClassificationAndCommandExecution: " + t.getMessage());
            failed++;
        }

        try {
            testConfidenceGateRejectionSafety();
            System.out.println("PASS: testConfidenceGateRejectionSafety");
            passed++;
        } catch (Throwable t) {
            System.err.println("FAIL: testConfidenceGateRejectionSafety: " + t.getMessage());
            failed++;
        }

        try {
            testEmergencyKillSwitch();
            System.out.println("PASS: testEmergencyKillSwitch");
            passed++;
        } catch (Throwable t) {
            System.err.println("FAIL: testEmergencyKillSwitch: " + t.getMessage());
            failed++;
        }

        System.out.println("--------------------------------------------------------------------------------");
        System.out.printf("RESULTS: %d PASSED, %d FAILED%n", passed, failed);
        System.out.println("================================================================================");

        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void testActivationFsmSuccess() {
        AtomicBoolean armed = new AtomicBoolean(false);
        CornerActivationFSM fsm = new CornerActivationFSM(ActivationConfig.defaults(), () -> armed.set(true));

        long t = 1000;
        double[][] corners = {{0.05, 0.05}, {0.95, 0.05}, {0.95, 0.95}, {0.05, 0.95}};
        for (double[] c : corners) {
            fsm.process(new TouchEvent(TouchEvent.Type.DOWN, 0, c[0], c[1], t));
            fsm.process(new TouchEvent(TouchEvent.Type.UP, 0, c[0], c[1], t + 10));
            t += 100;
        }

        assertTrue(fsm.isArmed(), "FSM should be ARMED after 4 corners");
        assertTrue(armed.get(), "Activation callback should have fired");
    }

    private static void testActivationFsmWrongCornerReset() {
        AtomicBoolean armed = new AtomicBoolean(false);
        CornerActivationFSM fsm = new CornerActivationFSM(ActivationConfig.defaults(), () -> armed.set(true));

        long t = 1000;
        // Hit TL
        fsm.process(new TouchEvent(TouchEvent.Type.DOWN, 0, 0.05, 0.05, t));
        fsm.process(new TouchEvent(TouchEvent.Type.UP, 0, 0.05, 0.05, t + 10));
        // Hit Center instead of TR (wrong corner)
        fsm.process(new TouchEvent(TouchEvent.Type.DOWN, 0, 0.50, 0.50, t + 100));
        fsm.process(new TouchEvent(TouchEvent.Type.UP, 0, 0.50, 0.50, t + 110));

        assertFalse(fsm.isArmed(), "FSM should NOT be armed after wrong corner");
        assertEquals(CornerActivationFSM.State.WAITING, fsm.state(), "FSM state should reset to WAITING");
        assertEquals(0, fsm.expectedIndex(), "Expected corner index should reset to 0 (TOP_LEFT)");
    }

    private static void testRasterizerParity() {
        TouchStroke s = new TouchStroke();
        s.add(new Point(0.2, 0.2, 1));
        s.add(new Point(0.2, 0.8, 2));
        s.add(new Point(0.8, 0.8, 3));
        Gesture g = new Gesture(List.of(s), 1, 3);

        GestureRasterizer rasterizer = new GestureRasterizer();
        RasterizedGesture r = rasterizer.rasterize(g);

        assertEquals(128, r.image().getWidth(), "Width must be 128");
        assertEquals(128, r.image().getHeight(), "Height must be 128");
        assertEquals(16384, r.modelInput().length, "Model input length must be 16384 (128x128)");

        for (float v : r.modelInput()) {
            assertTrue(v >= -1.0001f && v <= 1.0001f, "Normalized pixel must be in [-1, 1]");
        }
    }

    private static void testOnnxClassificationAndCommandExecution() throws Exception {
        Path projectRoot = findProjectRoot();
        Path model = projectRoot.resolve("Dataset/GLYVEX/ml/demo_retrain/onnx/glyvex_demo_char_classifier.onnx");
        Path classes = projectRoot.resolve("Dataset/GLYVEX/ml/demo_retrain/onnx/classes.json");
        Path gate = projectRoot.resolve("Dataset/GLYVEX/ml/demo_retrain/confidence_gate.json");

        ModelResources res = ModelResources.load(model, classes, gate, 0.90);
        try (OnnxCharacterClassifier classifier = new OnnxCharacterClassifier(res)) {
            CommandRegistry registry = CommandRegistry.createDefaultLinux();
            registry.setGlobalDryRun(true);

            AtomicReference<String> executedSymbol = new AtomicReference<>();
            registry.setOnExecuteListener((sym, act) -> executedSymbol.set(sym));

            // Generate an arc gesture (letter C)
            TouchStroke cStroke = new TouchStroke();
            for (int i = 0; i < 40; i++) {
                double theta = 0.3 * Math.PI + (i / 39.0) * (1.4 * Math.PI);
                double x = 0.50 + 0.25 * Math.cos(theta);
                double y = 0.50 - 0.25 * Math.sin(theta);
                cStroke.add(new Point(x, y, 1000 + i * 10));
            }
            Gesture g = new Gesture(List.of(cStroke), 1000, 1400);

            GestureRasterizer rasterizer = new GestureRasterizer();
            RasterizedGesture r = rasterizer.rasterize(g);
            Prediction p = classifier.predict(r.modelInput());

            assertEquals("C", p.character(), "Letter C curve should classify as 'C'");
            assertTrue(p.confidence() > 0.85, "Confidence should exceed 85%");

            boolean executed = registry.execute(p.character());
            assertTrue(executed, "CommandRegistry should successfully execute registered symbol 'C'");
            assertEquals("C", executedSymbol.get(), "Executed symbol should match 'C'");
        }
    }

    private static void testConfidenceGateRejectionSafety() throws Exception {
        Path projectRoot = findProjectRoot();
        Path model = projectRoot.resolve("Dataset/GLYVEX/ml/demo_retrain/onnx/glyvex_demo_char_classifier.onnx");
        Path classes = projectRoot.resolve("Dataset/GLYVEX/ml/demo_retrain/onnx/classes.json");
        Path gate = projectRoot.resolve("Dataset/GLYVEX/ml/demo_retrain/confidence_gate.json");

        // High threshold of 0.99
        ModelResources res = ModelResources.load(model, classes, gate, 0.999);
        try (OnnxCharacterClassifier classifier = new OnnxCharacterClassifier(res)) {
            CommandRegistry registry = CommandRegistry.createDefaultLinux();
            registry.setGlobalDryRun(true);
            AtomicBoolean executed = new AtomicBoolean(false);
            registry.setOnExecuteListener((s, a) -> executed.set(true));

            // Short ambiguous diagonal tick
            TouchStroke s = new TouchStroke();
            s.add(new Point(0.3, 0.3, 1));
            s.add(new Point(0.4, 0.4, 2));
            Gesture g = new Gesture(List.of(s), 1, 2);

            RasterizedGesture r = new GestureRasterizer().rasterize(g);
            Prediction p = classifier.predict(r.modelInput());

            // With strict 0.999 threshold, this must be rejected
            assertFalse(p.accepted(), "Ambiguous stroke must be REJECTED by confidence gate");

            // Safety policy: do NOT call registry if rejected
            if (!p.accepted()) {
                // correctly blocked
            } else {
                registry.execute(p.character());
            }

            assertFalse(executed.get(), "Command execution must NEVER trigger when gate rejects");
        }
    }

    private static void testEmergencyKillSwitch() {
        CommandRegistry registry = CommandRegistry.createDefaultLinux();
        registry.setGlobalDryRun(true);
        AtomicBoolean executed = new AtomicBoolean(false);
        registry.setOnExecuteListener((s, a) -> executed.set(true));

        registry.setEmergencyKillSwitch(true);
        boolean success = registry.execute("C");

        assertFalse(success, "Execute must return false when Kill Switch is active");
        assertFalse(executed.get(), "No command listener should fire when Kill Switch is active");
    }

    private static Path findProjectRoot() {
        Path p = Paths.get(".").toAbsolutePath().normalize();
        if (p.resolve("Dataset").toFile().exists()) return p;
        if (p.getParent() != null && p.getParent().resolve("Dataset").toFile().exists()) {
            return p.getParent();
        }
        return Paths.get("/home/reoo/ashwath/JAVA-SEM-Project-main");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError("Assertion failed: " + message);
    }

    private static void assertFalse(boolean condition, String message) {
        if (condition) throw new AssertionError("Assertion failed: " + message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null && actual == null) return;
        if (expected != null && expected.equals(actual)) return;
        throw new AssertionError(String.format("%s [expected: %s, actual: %s]", message, expected, actual));
    }
}
