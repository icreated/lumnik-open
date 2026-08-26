package io.lumnik.common.error;

import java.util.Map;

@SuppressWarnings("java:S1948")
public class BusinessException extends RuntimeException {
    private final String code;
    private final Map<String, Object> details;

    public BusinessException(String code, String message) {
        this(code, message, Map.of());
    }

    public BusinessException(String code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details == null ? Map.of() : details;
    }

    public String code() { return code; }
    public Map<String, Object> details() { return details; }
}
