package io.lumnik.hub.testsupport;

/**
 * A minimal {@code kind: App} manifest, for tests that only need a name, a scope and an extra
 * {@code spec} line (the default flag, a theme block, …). Shared by {@code AppApplyResourceIT}
 * and {@code AppRegistryAuthIT} — same text block, same three arguments — since the two would
 * otherwise drift apart the day a {@code kind:App} field is added.
 */
public final class AppManifestYaml {

    private AppManifestYaml() {}

    /** {@code title} and {@code metadata.name} both take {@code name}; {@code extra} is raw YAML
     *  appended under {@code spec:}, indented by the caller (e.g. {@code "  default: true\n"}). */
    public static String manifest(String name, String scope, String extra) {
        return """
            kind: App
            apiVersion: apps.lumnik.io/v1
            metadata:
              name: %s
            spec:
              title: %s
              scope: %s
              icon: event
            %s""".formatted(name, name, scope, extra);
    }
}
