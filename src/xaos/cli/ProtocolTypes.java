package xaos.cli;

import java.util.List;
import java.util.Map;

/** Immutable protocol DTOs used by integrations that do not want to parse JSON envelopes. */
public final class ProtocolTypes {
    private ProtocolTypes() { }

    public record CommandRequest(String id, String operation, Map<String, Object> args,
                                  String protocolVersion, Long ifRevision, Long timeoutMs) { }

    public record CommandError(String code, String message, String field, Object value,
                                List<String> suggestions) { }

    public record CommandResponse(String id, boolean ok, String protocolVersion,
                                   long tick, long revision, Object result,
                                   CommandError error, List<String> warnings) { }

    public record CommandDescriptor(String name, List<String> required,
                                     List<String> optional, String description) { }
}
