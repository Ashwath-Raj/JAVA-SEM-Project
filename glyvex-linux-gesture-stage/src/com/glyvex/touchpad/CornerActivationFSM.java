package com.glyvex.touchpad;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Four-corner activation state machine.
 *
 * Default pattern: TL -> TR -> BR -> BL, with a release between corner hits.
 * Each corner hit is initiated by a DOWN that occurs inside the expected corner.
 * A wrong corner or timeout resets the FSM back to the first corner.
 */
public final class CornerActivationFSM {
    public enum State { WAITING, TOUCHING_CORNER, ARMED }

    private final ActivationConfig config;
    private final List<Corner> sequence;
    private final Consumer<Void> activationCallback;
    private final Consumer<String> statusCallback;
    private State state = State.WAITING;
    private int expectedIndex = 0;
    private boolean currentTouchStartedInExpectedCorner = false;
    private long lastSuccessfulCornerMicros = Long.MIN_VALUE;

    public CornerActivationFSM(ActivationConfig config, Runnable onActivated) {
        this(config, onActivated, null);
    }

    public CornerActivationFSM(ActivationConfig config, Runnable onActivated, Consumer<String> onStatus) {
        this.config = Objects.requireNonNull(config, "config");
        this.sequence = config.sequence();
        Objects.requireNonNull(onActivated, "onActivated");
        this.activationCallback = ignored -> onActivated.run();
        this.statusCallback = onStatus;
    }

    public synchronized void reset() {
        state = State.WAITING;
        expectedIndex = 0;
        currentTouchStartedInExpectedCorner = false;
        lastSuccessfulCornerMicros = Long.MIN_VALUE;
    }

    public synchronized State state() {
        return state;
    }

    public synchronized int expectedIndex() {
        return expectedIndex;
    }

    public synchronized Corner expectedCorner() {
        if (state == State.ARMED) return null;
        return sequence.get(expectedIndex);
    }

    public synchronized boolean isArmed() {
        return state == State.ARMED;
    }

    public synchronized void process(TouchEvent event) {
        Objects.requireNonNull(event, "event");
        if (state == State.ARMED) {
            return;
        }

        if (lastSuccessfulCornerMicros != Long.MIN_VALUE &&
                event.timestampMicros() - lastSuccessfulCornerMicros > config.interCornerTimeoutMicros()) {
            if (expectedIndex > 0) {
                emitStatus(String.format("Activation timeout (inter-corner limit of %.1fs exceeded). Sequence reset to %s.",
                        config.interCornerTimeoutMicros() / 1_000_000.0, sequence.get(0)));
            }
            reset();
        }

        switch (event.type()) {
            case DOWN -> onDown(event);
            case MOVE -> onMove(event);
            case UP, CANCEL -> onUpOrCancel(event);
        }
    }

    private void onDown(TouchEvent event) {
        Corner expected = sequence.get(expectedIndex);
        currentTouchStartedInExpectedCorner = expected.contains(event.x(), event.y(), config.cornerMargin());
        state = State.TOUCHING_CORNER;

        if (currentTouchStartedInExpectedCorner) {
            emitStatus(String.format("[CORNER %d/%d] %s touch detected at (%.2f, %.2f) — release finger to confirm",
                    expectedIndex + 1, sequence.size(), expected, event.x(), event.y()));
        } else {
            if (expectedIndex > 0) {
                emitStatus(String.format("Missed corner %d/%d: touch at (%.2f, %.2f) was outside %s (margin=%.2f). Sequence reset to %s.",
                        expectedIndex + 1, sequence.size(), event.x(), event.y(), expected, config.cornerMargin(), sequence.get(0)));
            }
            reset();
        }
    }

    private void onMove(TouchEvent event) {
        if (state != State.TOUCHING_CORNER) {
            return;
        }
        // We intentionally do not require the finger to stay inside the corner;
        // the successful hit is defined by where the contact started.
    }

    private void onUpOrCancel(TouchEvent event) {
        if (state != State.TOUCHING_CORNER) {
            return;
        }
        if (event.type() == TouchEvent.Type.CANCEL || !currentTouchStartedInExpectedCorner) {
            if (expectedIndex > 0) {
                emitStatus("Touch cancelled. Activation sequence reset to " + sequence.get(0));
            }
            reset();
            return;
        }

        Corner completedCorner = sequence.get(expectedIndex);
        currentTouchStartedInExpectedCorner = false;
        expectedIndex++;
        lastSuccessfulCornerMicros = event.timestampMicros();

        if (expectedIndex >= sequence.size()) {
            state = State.ARMED;
            emitStatus(String.format("[CORNER %d/%d] %s ✓ — FOUR-CORNER ACTIVATION COMPLETE! Ready to draw.",
                    expectedIndex, sequence.size(), completedCorner));
            activationCallback.accept(null);
        } else {
            state = State.WAITING;
            emitStatus(String.format("[CORNER %d/%d] %s ✓ — Next: %s",
                    expectedIndex, sequence.size(), completedCorner, sequence.get(expectedIndex)));
        }
    }

    private void emitStatus(String message) {
        if (statusCallback != null) {
            statusCallback.accept(message);
        }
    }
}
