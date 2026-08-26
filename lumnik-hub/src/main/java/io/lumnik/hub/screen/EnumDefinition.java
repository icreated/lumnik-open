package io.lumnik.hub.screen;

import java.util.List;
import java.util.Map;

public record EnumDefinition(
    String id,
    List<EnumValue> values
) {
    public record EnumValue(
        String value,
        Map<String, String> label,
        String color,
        Integer order
    ) {}
}
