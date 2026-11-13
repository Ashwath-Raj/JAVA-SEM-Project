package com.glyvex.commands;

import java.util.List;

/**
 * A user-configured Linux action. Commands are argument vectors by default;
 * shell parsing and administrator elevation are explicit per-action options.
 */
public record CommandAction(
        String id,
        String displayName,
        List<String> commandLine,
        boolean dryRun,
        boolean useSudo,
        boolean useShell
) {
    public CommandAction {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Action ID cannot be null/blank");
        if (displayName == null || displayName.isBlank()) displayName = id;
        if (commandLine == null || commandLine.isEmpty()) throw new IllegalArgumentException("commandLine cannot be empty");
        commandLine = List.copyOf(commandLine);
        if (useShell && commandLine.size() != 1) {
            throw new IllegalArgumentException("Shell mode expects one complete shell command string");
        }
    }

    /** Backwards-compatible constructor for older call sites. */
    public CommandAction(String id, String displayName, List<String> commandLine, boolean dryRun) {
        this(id, displayName, commandLine, dryRun, false, false);
    }

    public static CommandAction of(String id, String displayName, String... command) {
        return new CommandAction(id, displayName, List.of(command), false, false, false);
    }
}
