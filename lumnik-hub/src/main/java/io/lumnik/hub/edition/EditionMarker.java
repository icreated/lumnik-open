package io.lumnik.hub.edition;

/**
 * Presence on the classpath marks a commercial build; absence marks the Open edition. Resolved via
 * {@code Instance<EditionMarker>} — never implemented directly by callers, only by the pro-edition
 * bean that carries the fact of its own presence.
 */
public interface EditionMarker {
}
