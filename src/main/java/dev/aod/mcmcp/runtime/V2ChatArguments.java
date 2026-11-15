package dev.aod.mcmcp.runtime;

import java.util.Map;
import java.util.Set;

record V2ChatArguments(String operation, String text, String cursor, int limit) {
    static V2ChatArguments parse(Map<String, Object> args) {
        String operation = RuntimeArguments.stringArgument(args, "operation");
        if (operation.equals("read")) {
            RuntimeArguments.requireAllowedKeys(args, "agent_chat read", Set.of("operation", "cursor", "limit"));
            String cursor = args.containsKey("cursor") ? RuntimeArguments.stringArgument(args, "cursor") : null;
            if (cursor != null && (cursor.length() > 64 || !cursor.matches("[0-9a-f-]{36}:[0-9]{1,19}")))
                throw new IllegalArgumentException("invalid chat cursor");
            int limit = args.containsKey("limit") ? RuntimeArguments.intArgument(args, "limit") : 50;
            if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be in 1..100");
            return new V2ChatArguments(operation, null, cursor, limit);
        }
        if (!operation.equals("send")) throw new IllegalArgumentException("unsupported chat operation");
        RuntimeArguments.requireExactKeys(args, "agent_chat send", Set.of("operation", "text"));
        String text = RuntimeArguments.stringArgument(args, "text");
        validateText(text);
        return new V2ChatArguments(operation, text, null, 0);
    }

    static void validateText(String text) {
        dev.aod.mcmcp.client.AgentChatSettings.validateMessage(text);
    }
}
