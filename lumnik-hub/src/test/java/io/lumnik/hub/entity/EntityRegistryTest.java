package io.lumnik.hub.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EntityRegistryTest {

  @Test
  void stores_and_finds_case_insensitively() {
    EntityRegistry r = new EntityRegistry();
    EntityManifest m = new EntityManifest("Entity", "Customer", java.util.List.of("id"),
        java.util.List.of(new EntityManifest.Source("t_cust_src",
            new java.util.LinkedHashMap<>(java.util.Map.of("ROW_ID", "id")))));
    r.put(m);
    assertThat(r.find("customer")).isSameAs(m);
    assertThat(r.find("CUSTOMER")).isSameAs(m);
    assertThat(r.find("unknown")).isNull();
    assertThat(r.find(null)).isNull();
  }

  @Test
  void all_returns_every_manifest_sorted_by_name() {
    EntityRegistry r = new EntityRegistry();
    r.put(new EntityManifest("Entity", "Zeta", java.util.List.of("id"), java.util.List.of()));
    r.put(new EntityManifest("Entity", "Alpha", java.util.List.of("id"), java.util.List.of()));
    assertThat(r.all())
        .extracting(EntityManifest::name)
        .containsExactly("Alpha", "Zeta");
  }
}
