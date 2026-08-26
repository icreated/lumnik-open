package io.lumnik.hub.hook;

import java.util.Map;

public class HookCancellation extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;
    // Carries arbitrary cancellation context; never serialized in practice, so transient
    // keeps the exception serializable without constraining the value types.
    private final transient Map<String, Object> details;

    public HookCancellation(String code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details == null ? Map.of() : details;
    }

    public String code() { return code; }
    public Map<String, Object> details() { return details == null ? Map.of() : details; }
}
