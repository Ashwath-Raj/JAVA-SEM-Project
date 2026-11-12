package com.glyvex.touchpad;

public final class TouchpadProtocolParser {
    private TouchpadProtocolParser() {}

    public static TouchEvent parse(String line) {
        if (line == null || line.isBlank()) return null;
        String[] p = line.trim().split("\\t");
        if (p.length != 5) return null;

        TouchEvent.Type type = switch (p[0]) {
            case "DOWN" -> TouchEvent.Type.DOWN;
            case "MOVE" -> TouchEvent.Type.MOVE;
            case "UP" -> TouchEvent.Type.UP;
            case "CANCEL" -> TouchEvent.Type.CANCEL;
            default -> null;
        };
        if (type == null) return null;

        try {
            int slot = Integer.parseInt(p[1]);
            double x = Double.parseDouble(p[2]);
            double y = Double.parseDouble(p[3]);
            long ts = Long.parseLong(p[4]);
            return new TouchEvent(type, slot, x, y, ts);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
