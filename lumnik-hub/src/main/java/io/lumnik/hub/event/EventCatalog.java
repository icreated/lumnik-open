package io.lumnik.hub.event;

import java.util.List;
import java.util.Map;

public record EventCatalog(List<EventDescriptor> events) {
    public record EventDescriptor(
        String module,
        String type,
        String description,
        Map<String, String> payload
    ) {}
}
