package com.glyvex.commands;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Thread-safe allowlist command registry and process executor.
 *
 * Ensures the ML classifier never executes arbitrary shell text,
 * but only pre-approved, benign desktop operations.
 */
public final class CommandRegistry {
    private final Map<String, CommandAction> registry = new ConcurrentHashMap<>();
    private volatile boolean emergencyKillSwitch = false;
    private volatile boolean globalDryRun = false;
    private BiConsumer<String, CommandAction> onExecuteListener;

    public static CommandRegistry createDefaultLinux() {
        CommandRegistry r = new CommandRegistry();
        // Safe, benign desktop applications for Linux desktop
        r.register("C", CommandAction.of("CALC", "Calculator", "gnome-calculator"));
        r.register("B", CommandAction.of("BROWSER", "Web Browser", "xdg-open", "https://google.com"));
        r.register("F", CommandAction.of("FILES", "File Manager", "xdg-open", System.getProperty("user.home")));
        r.register("T", CommandAction.of("TERMINAL", "Terminal", "x-terminal-emulator"));
        r.register("E", CommandAction.of("EDITOR", "Text Editor", "gedit"));
        r.register("L", CommandAction.of("LOCK", "Lock Screen", "loginctl", "lock-session"));
        r.register("S", CommandAction.of("SETTINGS", "Settings", "gnome-control-center"));
        return r;
    }

    public void register(String symbol, CommandAction action) {
        if (symbol == null || symbol.isBlank() || action == null) return;
        registry.put(symbol.trim().toUpperCase(), action);
    }

    public boolean hasCommand(String symbol) {
        return symbol != null && registry.containsKey(symbol.trim().toUpperCase());
    }

    public CommandAction getCommand(String symbol) {
        return symbol != null ? registry.get(symbol.trim().toUpperCase()) : null;
    }

    public boolean execute(String symbol) {
        if (emergencyKillSwitch) {
            System.err.println("[GLYVEX SECURITY] Execution blocked: Emergency Kill Switch is ACTIVE.");
            return false;
        }

        if (symbol == null) return false;
        String key = symbol.trim().toUpperCase();
        CommandAction action = registry.get(key);
        if (action == null) {
            System.out.println("[GLYVEX REGISTRY] No allowlisted command mapped for symbol: '" + key + "'");
            return false;
        }

        if (onExecuteListener != null) {
            try {
                onExecuteListener.accept(key, action);
            } catch (Exception ex) {
                System.err.println("[GLYVEX REGISTRY] Listener error: " + ex.getMessage());
            }
        }

        if (globalDryRun || action.dryRun()) {
            System.out.println("[GLYVEX REGISTRY] (DRY RUN) Would execute: " + action.displayName()
                    + " [" + String.join(" ", action.commandLine()) + "]");
            return true;
        }

        // Asynchronous launch in background daemon thread
        Thread launcher = new Thread(() -> {
            try {
                System.out.println("[GLYVEX EXECUTOR] Launching " + action.displayName() + "...");
                ProcessBuilder pb = new ProcessBuilder(action.commandLine());
                // Avoid hanging stdin/stdout
                pb.redirectErrorStream(true);
                Process process = pb.start();
                System.out.println("[GLYVEX EXECUTOR] Successfully spawned PID " + process.pid() + " for " + action.id());
            } catch (IOException ex) {
                System.err.println("[GLYVEX EXECUTOR] Could not launch " + action.displayName()
                        + " (" + ex.getMessage() + ")");
                // Fallback attempt for standard commands: try xdg-open if specific binary was not found
                attemptFallback(action);
            }
        }, "glyvex-cmd-" + action.id());
        launcher.setDaemon(true);
        launcher.start();
        return true;
    }

    private void attemptFallback(CommandAction action) {
        if ("CALC".equals(action.id())) {
            tryFallback("kcalc", "xcalc");
        } else if ("TERMINAL".equals(action.id())) {
            tryFallback("gnome-terminal", "konsole", "xterm");
        } else if ("EDITOR".equals(action.id())) {
            tryFallback("kate", "mousepad", "nano");
        }
    }

    private void tryFallback(String... alternatives) {
        for (String alt : alternatives) {
            try {
                Process p = new ProcessBuilder(alt).start();
                System.out.println("[GLYVEX EXECUTOR] Fallback alternative launched: " + alt + " (PID " + p.pid() + ")");
                return;
            } catch (IOException ignored) {}
        }
    }

    public void setEmergencyKillSwitch(boolean killSwitch) {
        this.emergencyKillSwitch = killSwitch;
    }

    public boolean isEmergencyKillSwitch() {
        return emergencyKillSwitch;
    }

    public void setGlobalDryRun(boolean dryRun) {
        this.globalDryRun = dryRun;
    }

    public boolean isGlobalDryRun() {
        return globalDryRun;
    }

    public void setOnExecuteListener(BiConsumer<String, CommandAction> listener) {
        this.onExecuteListener = listener;
    }

    public Map<String, CommandAction> allActions() {
        return Collections.unmodifiableMap(registry);
    }
}
