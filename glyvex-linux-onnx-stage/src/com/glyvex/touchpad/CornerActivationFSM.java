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
    private State state = State.WAITING;
    private int expectedIndex = 0;
    private boolean currentTouchStartedInExpectedCorner = false;
    private long lastSuccessfulCornerMicros = Long.MIN_VALUE;

    public CornerActivationFSM(ActivationConfig config, Runnable onActivated) {
        this.config = Objects.requireNonNull(config, "config");
        this.sequence = config.sequence();
        Objects.requireNonNull(onActivated, "onActivated");
        this.activationCallback = ignored -> onActivated.run();
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
            reset();
        }

        switch (event.type()) {
            case DOWN -> onDown(event);
            case MOVE -> onMove(event);
            case UP, CANCEL -> onUpOrCancel(event);
        }
    }

    private void onDown(TouchEvent event) {
        if (event.slot() != 0) {
            reset();
            return;
        }
        Corner expected = sequence.get(expectedIndex);
        currentTouchStartedInExpectedCorner = expected.contains(event.x(), event.y(), config.cornerMargin());
        state = State.TOUCHING_CORNER;
        if (!currentTouchStartedInExpectedCorner) {
            reset();
        }
    }

    private void onMove(TouchEvent event) {
        if (state != State.TOUCHING_CORNER || event.slot() != 0) {
            return;
        }
        // We intentionally do not require the finger to stay inside the corner;
        // the successful hit is defined by where the contact started.
    }

    private void onUpOrCancel(TouchEvent event) {
        if (state != State.TOUCHING_CORNER || event.slot() != 0) {
            return;
        }
        if (event.type() == TouchEvent.Type.CANCEL || !currentTouchStartedInExpectedCorner) {
            reset();
            return;
        }

        currentTouchStartedInExpectedCorner = false;
        expectedIndex++;
        lastSuccessfulCornerMicros = event.timestampMicros();

        if (expectedIndex >= sequence.size()) {
            state = State.ARMED;
            activationCallback.accept(null);
        } else {
            state = State.WAITING;
        }
    }
}
