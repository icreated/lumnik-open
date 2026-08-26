package io.lumnik.hub.connector.rest.pagination.preset;

import java.util.Map;
import java.util.Set;

/** A named pagination convention that expands to a real strategy config (Open/Closed: add a bean). */
public interface PaginationPreset {

    /** The manifest kind that selects this preset, e.g. {@code "spring-page"}. */
    String name();

    /** The real strategy config it expands to: a {@code kind} plus default fields. */
    Map<String, Object> expansion();

    /** Field names the user MUST supply (substituted into {@code {placeholder}}s), e.g. {@code "items"}. */
    default Set<String> requiredOverrides() { return Set.of(); }
}
