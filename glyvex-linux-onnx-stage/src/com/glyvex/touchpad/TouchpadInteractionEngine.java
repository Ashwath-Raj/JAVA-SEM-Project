package com.glyvex.touchpad;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Coordinates activation and capture. The classifier is deliberately not part
 * of this layer; this produces a clean Gesture object for the ML pipeline.
 */
public final class TouchpadInteractionEngine implements AutoCloseable {
    private final CornerActivationFSM activationFSM;
    private final GestureCapture capture;
    private final Consumer<Gesture> onGesture;
    private final Consumer<String> onStatus;

    public TouchpadInteractionEngine(ActivationConfig activationConfig,
                                     long inactivityTimeoutMillis,
                                     long maxGestureDurationMillis,
                                     Consumer<Gesture> onGesture,
                                     Consumer<String> onStatus) {
        this.onGesture = Objects.requireNonNull(onGesture, "onGesture");
        this.onStatus = Objects.requireNonNull(onStatus, "onStatus");
        this.capture = new GestureCapture(
                inactivityTimeoutMillis,
                maxGestureDurationMillis,
                this::handleGestureComplete,
                onStatus
        );
        this.activationFSM = new CornerActivationFSM(activationConfig, this::handleActivated);
    }

    public void process(TouchEvent event) {
        if (capture.isActive()) {
            capture.process(event);
            return;
        }

        if (activationFSM.isArmed()) {
            if (event.type() == TouchEvent.Type.DOWN) {
                capture.start();
                capture.process(event);
            } else if (event.type() == TouchEvent.Type.CANCEL) {
                activationFSM.reset();
            }
            return;
        }

        activationFSM.process(event);
    }

    public CornerActivationFSM.State activationState() {
        return activationFSM.state();
    }

    public Corner expectedCorner() {
        return activationFSM.expectedCorner();
    }

    private void handleActivated() {
        onStatus.accept("FOUR-CORNER ACTIVATION COMPLETE — next touch starts capture");
    }

    private void handleGestureComplete(Gesture gesture) {
        onGesture.accept(gesture);
        activationFSM.reset();
        onStatus.accept("READY — repeat corner sequence for next command");
    }

    @Override
    public void close() {
        capture.close();
    }
}
