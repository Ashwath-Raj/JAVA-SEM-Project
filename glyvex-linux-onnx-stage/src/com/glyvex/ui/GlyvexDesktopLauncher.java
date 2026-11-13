package com.glyvex.ui;

import javafx.application.Application;

/** Entry point kept separate from Application for reliable classpath launching. */
public final class GlyvexDesktopLauncher {
    private GlyvexDesktopLauncher() {}
    public static void main(String[] args) {
        Application.launch(GlyvexDesktopApp.class, args);
    }
}
