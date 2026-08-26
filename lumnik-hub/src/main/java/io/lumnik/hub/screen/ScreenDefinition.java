package io.lumnik.hub.screen;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public record ScreenDefinition(
    String id,
    String type,
    Map<String, String> title,
    String entity,
    ScreenApi api,
    List<ScreenColumn> columns,    // list screens
    List<ScreenSection> sections,  // form screens
    ScreenActions actions
) {
    public record ScreenApi(
        String endpoint,
        @JsonProperty("page_size") int pageSize
    ) {}

    // ── List screen ──────────────────────────────────────────────────────────

    public record ScreenColumn(
        String field,
        Map<String, String> label,
        String type,
        Integer width,
        boolean sortable,
        boolean filterable,
        @JsonProperty("enum_id") String enumId,
        String format
    ) {}

    // ── Form screen ──────────────────────────────────────────────────────────

    public record ScreenSection(
        String id,
        Map<String, String> label,
        List<ScreenField> fields
    ) {}

    public record ScreenField(
        String field,
        Map<String, String> label,
        String type,
        boolean required,
        boolean readonly,
        @JsonProperty("hidden_when_new") boolean hiddenWhenNew,
        @JsonProperty("max_length") Integer maxLength,
        Integer rows,
        @JsonProperty("enum_id") String enumId,
        @JsonProperty("default") String defaultValue
    ) {}

    // ── Actions (shared) ─────────────────────────────────────────────────────

    public record ScreenActions(
        List<ScreenAction> toolbar,
        List<ScreenAction> row
    ) {}

    public record ScreenAction(
        String id,
        Map<String, String> label,
        String icon,
        String style,
        String type,
        String permission,
        @JsonProperty("visible_when") String visibleWhen,
        Map<String, Object> confirm
    ) {}
}
