package io.lumnik.hub.validator;

import java.util.List;

public record ValidationError(
    String path,
    Severity severity,
    String message,
    List<String> recommendations
) {
    public enum Severity { ERROR, WARNING }

    public ValidationError {
        if (recommendations == null) recommendations = List.of();
    }
}
