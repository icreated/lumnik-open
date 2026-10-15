package io.lumnik.hub.testsupport;

import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.EntityRegistry;

import java.util.List;
import java.util.Map;

/**
 * A minimal, generic {@code kind: Entity} manifest — with and without a {@code resolution:}
 * policy — plus a fake {@link EntityRegistry} serving it, for unit tests of a door gated by
 * {@code io.lumnik.hub.entity.ResolutionGate}. Shared because #622 grew three call sites
 * ({@code EntityResolveResourceTest}-style fixtures did not exist yet, {@code
 * EntityAssertionResourceTest}, {@code EntityResolutionReadResourceTest}) each writing out the
 * identical manifest shape and registry fake by hand.
 */
public final class ResolvableManifests {

  private ResolvableManifests() {}

  public static EntityManifest resolvable(String name, List<String> scopes) {
    EntityManifest.Resolution resolution = new EntityManifest.Resolution(
        Map.of("connector.t_src", "code"), List.of());
    return new EntityManifest("Entity", name, null, List.of("code"),
        List.of(new EntityManifest.Source("connector.t_src", Map.of("code", "code"))),
        scopes, null, Map.of(), resolution);
  }

  public static EntityManifest withoutResolution(String name, List<String> scopes) {
    return new EntityManifest("Entity", name, null, List.of("code"),
        List.of(new EntityManifest.Source("connector.t_src", Map.of("code", "code"))),
        scopes, null, Map.of(), null);
  }

  /** {@code findReloadingIfMissing}, not {@code find}: a genuine miss must not fall through to
   *  {@code loadFromDb()}, which needs a real admin datasource a unit test never wires. */
  public static EntityRegistry registryServing(EntityManifest m) {
    return new EntityRegistry() {
      @Override public EntityManifest findReloadingIfMissing(String name) { return m; }
    };
  }
}
