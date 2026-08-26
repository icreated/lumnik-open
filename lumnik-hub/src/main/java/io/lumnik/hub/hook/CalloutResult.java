package io.lumnik.hub.hook;

import java.util.List;
import java.util.Map;

public record CalloutResult(Map<String, Object> patch, List<FieldError> errors) {

    public static CalloutResult patch(Map<String, Object> patch) {
        return new CalloutResult(patch, List.of());
    }

    public static CalloutResult error(String code) {
        return new CalloutResult(Map.of(), List.of(new FieldError(null, code, code)));
    }

    public static CalloutResult error(String field, String code, String message) {
        return new CalloutResult(Map.of(), List.of(new FieldError(field, code, message)));
    }

    public record FieldError(String field, String code, String message) {}
}
