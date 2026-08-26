package io.lumnik.hub.validator;

import java.util.List;

public record ValidationResult(
    List<ValidationError> errors,
    List<ValidationError> warnings
) {
    public boolean isValid() { return errors.isEmpty(); }
    public static ValidationResult ok() {
        return new ValidationResult(List.of(), List.of());
    }
}
