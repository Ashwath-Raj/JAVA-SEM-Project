package com.glyvex.commands;

import java.util.List;

/**
 * Encapsulates an allowlisted, executable system command.
 */
public record CommandAction(
        String id,
        String displayName,
        List<String> commandLine,
        boolean dryRun
) {
    public CommandAction {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Action ID cannot be null/blank");
        if (displayName == null || displayName.isBlank()) displayName = id;
        commandLine = List.copyOf(commandLine);
        if (commandLine.isEmpty()) throw new IllegalArgumentException("commandLine cannot be empty");
    }

    public static CommandAction of(String id, String displayName, String... command) {
        return new CommandAction(id, displayName, List.of(command), false);
    }
}
