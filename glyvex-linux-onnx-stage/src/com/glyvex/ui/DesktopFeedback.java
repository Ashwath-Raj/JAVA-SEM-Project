package com.glyvex.ui;

import com.glyvex.commands.CommandAction;
import com.glyvex.ml.Prediction;

import java.io.IOException;

/**
 * Provides on-screen desktop notifications (via Linux notify-send)
 * and structured terminal diagnostics.
 */
public final class DesktopFeedback {
    private static volatile boolean desktopNotificationsEnabled = true;

    private DesktopFeedback() {}

    public static void setDesktopNotificationsEnabled(boolean enabled) {
        desktopNotificationsEnabled = enabled;
    }

    public static void sendNotification(String title, String body, String urgency) {
        if (!desktopNotificationsEnabled) return;
        String display = System.getenv("DISPLAY");
        String wayland = System.getenv("WAYLAND_DISPLAY");
        if (display == null && wayland == null) return;

        Thread t = new Thread(() -> {
            try {
                new ProcessBuilder(
                        "notify-send",
                        "-u", urgency,
                        "-t", "2500",
                        "-a", "GLYVEX",
                        title,
                        body
                ).start();
            } catch (IOException ignored) {}
        }, "glyvex-notify");
        t.setDaemon(true);
        t.start();
    }

    public static void onArmed() {
        sendNotification(
                "GLYVEX Armed",
                "Four-corner sequence complete.\nDraw your gesture on the touchpad now.",
                "normal"
        );
    }

    public static void onPredictionResult(Prediction prediction, CommandAction action, double threshold) {
        if (prediction.accepted() && action != null) {
            String title = "GLYVEX: " + action.displayName();
            String msg = String.format("Recognized '%s' (%.1f%% confidence)%nExecuting allowlisted action: %s",
                    prediction.character(), prediction.confidence() * 100, action.id());
            sendNotification(title, msg, "normal");
        } else if (prediction.accepted() && action == null) {
            String title = "GLYVEX: Unmapped Gesture";
            String msg = String.format("Recognized '%s' (%.1f%%), but no command is configured.",
                    prediction.character(), prediction.confidence() * 100);
            sendNotification(title, msg, "low");
        } else {
            String title = "GLYVEX: Gesture Rejected";
            String msg = String.format("Confidence %.1f%% is below safety threshold %.1f%%.%nNo command executed.",
                    prediction.confidence() * 100, threshold * 100);
            sendNotification(title, msg, "low");
        }
    }

    public static void onReset(String reason) {
        if (reason != null && !reason.isBlank() && !reason.startsWith("READY")) {
            sendNotification("GLYVEX Reset", reason, "low");
        }
    }
}
