package com.glyvex.ui;

import javafx.application.Application;

/** Keeps JavaFX Application out of the JVM special launcher path. */
public final class GlyvexDesktopLauncher {
    private GlyvexDesktopLauncher() {}
    public static void main(String[] args) {
        Application.launch(GlyvexDesktopApp.class, args);
    }
}
