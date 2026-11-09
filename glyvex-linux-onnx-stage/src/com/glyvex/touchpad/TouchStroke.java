package com.glyvex.touchpad;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TouchStroke {
    private final List<Point> points = new ArrayList<>();

    public void add(Point point) {
        points.add(point);
    }

    public List<Point> points() {
        return Collections.unmodifiableList(points);
    }

    public boolean isEmpty() {
        return points.isEmpty();
    }

    public int size() {
        return points.size();
    }

    public Point first() {
        if (points.isEmpty()) throw new IllegalStateException("empty stroke");
        return points.get(0);
    }

    public Point last() {
        if (points.isEmpty()) throw new IllegalStateException("empty stroke");
        return points.get(points.size() - 1);
    }
}
