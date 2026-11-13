package com.glyvex.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Thread-safe command allowlist. The classifier can only select a configured
 * character mapping; it cannot supply a command or shell string itself.
 */
public final class CommandRegistry {
    private final Map<String, CommandAction> registry = new ConcurrentHashMap<>();
    private volatile boolean emergencyKillSwitch = false;
    private volatile boolean globalDryRun = false;
    private volatile BiConsumer<String, CommandAction> onExecuteListener;

    public static CommandRegistry createDefaultLinux() {
        CommandRegistry r = new CommandRegistry();
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
        return symbol == null ? null : registry.get(symbol.trim().toUpperCase());
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
        BiConsumer<String, CommandAction> listener = onExecuteListener;
        if (listener != null) {
            try { listener.accept(key, action); }
            catch (Exception ex) { System.err.println("[GLYVEX REGISTRY] Listener error: " + ex.getMessage()); }
        }

        if (globalDryRun || action.dryRun()) {
            System.out.println("[GLYVEX REGISTRY] (DRY RUN) Would execute: " + action.displayName()
                    + " [" + previewVector(action) + "]");
            return true;
        }

        List<String> launchVector = buildLaunchVector(action);
        Thread launcher = new Thread(() -> launch(action, launchVector), "glyvex-cmd-" + action.id());
        launcher.setDaemon(true);
        launcher.start();
        return true;
    }

    private String previewVector(CommandAction action) {
        String prefix = action.useSudo() ? "pkexec " : "";
        if (action.useShell()) return prefix + "/bin/bash -lc " + action.commandLine().get(0);
        return prefix + String.join(" ", action.commandLine());
    }

    private List<String> buildLaunchVector(CommandAction action) {
        List<String> vector;
        if (action.useShell()) {
            vector = List.of("/bin/bash", "-lc", action.commandLine().get(0));
        } else {
            vector = action.commandLine();
        }
        if (action.useSudo()) {
            String pkexec = findPkexec();
            if (pkexec == null) {
                throw new IllegalStateException("Administrator mode requires pkexec (PolicyKit). Install the polkit package; GLYVEX will not silently fall back to an unprompted sudo command.");
            }
            java.util.ArrayList<String> elevated = new java.util.ArrayList<>();
            elevated.add(pkexec);
            elevated.addAll(vector);
            return List.copyOf(elevated);
        }
        return vector;
    }

    private String findPkexec() {
        for (String candidate : List.of("/usr/bin/pkexec", "/bin/pkexec")) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path) && Files.isExecutable(path)) return candidate;
        }
        return null;
    }

    private void launch(CommandAction action, List<String> launchVector) {
        try {
            System.out.println("[GLYVEX EXECUTOR] Launching " + action.displayName()
                    + (action.useSudo() ? " via PolicyKit authorization" : "") + "...");
            ProcessBuilder pb = new ProcessBuilder(launchVector);
            pb.redirectErrorStream(true);
            // Do not leave a child blocked on a pipe if its output is verbose.
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process process = pb.start();
            System.out.println("[GLYVEX EXECUTOR] Process spawned: PID " + process.pid() + " for " + action.id());
        } catch (IOException | RuntimeException ex) {
            System.err.println("[GLYVEX EXECUTOR] Could not launch " + action.displayName() + ": " + ex.getMessage());
        }
    }

    public void setEmergencyKillSwitch(boolean killSwitch) { emergencyKillSwitch = killSwitch; }
    public boolean isEmergencyKillSwitch() { return emergencyKillSwitch; }
    public void setGlobalDryRun(boolean dryRun) { globalDryRun = dryRun; }
    public boolean isGlobalDryRun() { return globalDryRun; }
    public void setOnExecuteListener(BiConsumer<String, CommandAction> listener) { onExecuteListener = listener; }
    public Map<String, CommandAction> allActions() { return Collections.unmodifiableMap(registry); }
}
