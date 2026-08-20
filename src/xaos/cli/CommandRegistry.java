package xaos.cli;

import java.util.ArrayList;
import java.util.List;

/** Shared machine/text command metadata; handlers remain on the game thread. */
public final class CommandRegistry {
    private CommandRegistry() { }

    private static final List<ProtocolTypes.CommandDescriptor> OPERATIONS = List.of(
            new ProtocolTypes.CommandDescriptor("capabilities", List.of(), List.of(), "Discover protocol capabilities and limits"),
            new ProtocolTypes.CommandDescriptor("describe", List.of(), List.of("action", "catalog", "filter"), "Discover action and catalog schemas"),
            new ProtocolTypes.CommandDescriptor("observe", List.of(), List.of("include", "limit", "cursor", "since_revision", "box", "near"), "Read a bounded immutable world snapshot"),
            new ProtocolTypes.CommandDescriptor("act", List.of("action"), List.of("target", "client_tag", "dry_run", "actions"), "Submit normal player actions"),
            new ProtocolTypes.CommandDescriptor("advance", List.of(), List.of("until", "max_ticks", "timeout_ms"), "Advance deterministically to a bound or condition"),
            new ProtocolTypes.CommandDescriptor("assert", List.of("condition"), List.of(), "Evaluate a safe world condition"),
            new ProtocolTypes.CommandDescriptor("checkpoint", List.of("name"), List.of("mode"), "Save or load a named checkpoint")
    );

    public static List<ProtocolTypes.CommandDescriptor> operations(boolean includeTestOperations) {
        if (includeTestOperations) return OPERATIONS;
        return OPERATIONS.subList(0, 5);
    }

    public static List<String> humanCommands() {
        return new ArrayList<>(List.of("help", "commands", "status", "world", "cell", "livings", "items", "tasks", "why", "buildings", "zones", "stockpiles", "catalog", "observe", "act", "advance", "assert", "hash", "origin", "tick", "save", "pause", "resume", "quit"));
    }
}
