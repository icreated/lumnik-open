package io.lumnik.hub.app;

/**
 * A parsed kind:App manifest — one métier app's identity, declared at runtime instead of compiled
 * into the PWA's METIERS map. The theme fields are never null: {@link AppManifestParser} fills in
 * today's shipped values when the manifest declares none, so every reader downstream sees a colour
 * and none of them has to know who chose it.
 */
public record AppManifest(String name, String scope, String title, String icon,
                          String accentColor, String backgroundColor, boolean isDefault) {}
