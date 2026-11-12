package com.glyvex.touchpad;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Multi-stroke gesture capture after activation.
 *
 * Capture ends automatically after an inactivity period following an UP event,
 * or when the maximum gesture duration is reached.
 */
public final class GestureCapture implements AutoCloseable {
    private final long inactivityTimeoutMillis;
    private final long maxGestureDurationMillis;
    private final Consumer<Gesture> onGestureComplete;
    private final Consumer<String> onStatus;
    private final ScheduledExecutorService scheduler;

    private final List<TouchStroke> strokes = new ArrayList<>();
    private TouchStroke currentStroke;
    private boolean active;
    private long startedMicros;
    private ScheduledFuture<?> inactivityFuture;
    private ScheduledFuture<?> maxDurationFuture;

    public GestureCapture(long inactivityTimeoutMillis,
                          long maxGestureDurationMillis,
                          Consumer<Gesture> onGestureComplete,
                          Consumer<String> onStatus) {
        if (inactivityTimeoutMillis <= 0) throw new IllegalArgumentException("inactivity timeout must be > 0");
        if (maxGestureDurationMillis <= inactivityTimeoutMillis) {
            throw new IllegalArgumentException("max duration must exceed inactivity timeout");
        }
        this.inactivityTimeoutMillis = inactivityTimeoutMillis;
        this.maxGestureDurationMillis = maxGestureDurationMillis;
        this.onGestureComplete = Objects.requireNonNull(onGestureComplete, "onGestureComplete");
        this.onStatus = Objects.requireNonNull(onStatus, "onStatus");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "glyvex-gesture-timer");
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized void start() {
        cancelTimers();
        strokes.clear();
        currentStroke = null;
        active = true;
        startedMicros = 0L;
        onStatus.accept("CAPTURE ARMED — draw the character");
    }

    public synchronized boolean isActive() {
        return active;
    }

    public synchronized void process(TouchEvent event) {
        if (!active) return;
        if (event.slot() != 0) {
            abort("MULTI-FINGER INPUT — gesture cancelled");
            return;
        }

        switch (event.type()) {
            case DOWN -> onDown(event);
            case MOVE -> onMove(event);
            case UP -> onUp(event);
            case CANCEL -> abort("TOUCH CANCELLED");
        }
    }

    public synchronized void abort(String reason) {
        if (!active) return;
        onStatus.accept(reason);
        cancelTimers();
        active = false;
        currentStroke = null;
        strokes.clear();
    }

    private void onDown(TouchEvent event) {
        cancelInactivityTimer();
        if (!active) return;
        if (startedMicros == 0L) {
            startedMicros = event.timestampMicros();
            scheduleMaxDuration();
        }
        currentStroke = new TouchStroke();
        currentStroke.add(new Point(event.x(), event.y(), event.timestampMicros()));
        strokes.add(currentStroke);
        onStatus.accept("STROKE " + strokes.size() + " DOWN");
    }

    private void onMove(TouchEvent event) {
        if (currentStroke == null) return;
        currentStroke.add(new Point(event.x(), event.y(), event.timestampMicros()));
    }

    private void onUp(TouchEvent event) {
        if (currentStroke == null) return;
        currentStroke.add(new Point(event.x(), event.y(), event.timestampMicros()));
        currentStroke = null;
        scheduleInactivityTimer();
        onStatus.accept("STROKE UP — waiting for next stroke");
    }

    private void scheduleInactivityTimer() {
        cancelInactivityTimer();
        inactivityFuture = scheduler.schedule(this::completeIfIdle,
                inactivityTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    private void scheduleMaxDuration() {
        maxDurationFuture = scheduler.schedule(() -> {
            synchronized (GestureCapture.this) {
                if (active) {
                    onStatus.accept("MAX GESTURE DURATION — finalizing");
                    completeInternal();
                }
            }
        }, maxGestureDurationMillis, TimeUnit.MILLISECONDS);
    }

    private void completeIfIdle() {
        synchronized (this) {
            if (!active || currentStroke != null || strokes.isEmpty()) return;
            completeInternal();
        }
    }

    private void completeInternal() {
        if (!active || strokes.isEmpty()) return;
        long endMicros = strokes.get(strokes.size() - 1).last().timestampMicros();
        Gesture gesture = new Gesture(new ArrayList<>(strokes), startedMicros, endMicros);
        cancelTimers();
        active = false;
        currentStroke = null;
        strokes.clear();
        onStatus.accept("GESTURE COMPLETE: " + gesture.strokes().size() + " stroke(s), " + gesture.pointCount() + " points");
        onGestureComplete.accept(gesture);
    }

    private void cancelInactivityTimer() {
        if (inactivityFuture != null) {
            inactivityFuture.cancel(false);
            inactivityFuture = null;
        }
    }

    private void cancelTimers() {
        cancelInactivityTimer();
        if (maxDurationFuture != null) {
            maxDurationFuture.cancel(false);
            maxDurationFuture = null;
        }
    }

    @Override
    public synchronized void close() {
        cancelTimers();
        scheduler.shutdownNow();
        active = false;
    }
}
