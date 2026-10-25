package io.lumnik.hub.connector.api.types;

/**
 * One version of a Kotlin script a run's rows went through (#1430): the reference a manifest names it by
 * ({@code hooks:shipping-fee.kts}) and the SHA-256 of its text — for a {@code hooks:} script, the fingerprint
 * {@code lm hook scripts} shows. A script the operator replaced mid-run is two versions.
 */
public record ScriptVersion(String name, String sha256) {}
