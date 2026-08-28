package io.lumnik.hub.entity;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EntityRegistryTest {

  @Test
  void stores_and_finds_case_insensitively() {
    EntityRegistry r = new EntityRegistry();
    EntityManifest m = new EntityManifest("Entity", "Customer", List.of("id"),
        List.of(new EntityManifest.Source("t_cust_src",
            new LinkedHashMap<>(Map.of("ROW_ID", "id")))));
    r.put(m);
    assertThat(r.find("customer")).isSameAs(m);
    assertThat(r.find("CUSTOMER")).isSameAs(m);
    assertThat(r.find("unknown")).isNull();
    assertThat(r.find(null)).isNull();
  }

  @Test
  void all_returns_every_manifest_sorted_by_name() {
    EntityRegistry r = new EntityRegistry();
    r.put(new EntityManifest("Entity", "Zeta", List.of("id"), List.of()));
    r.put(new EntityManifest("Entity", "Alpha", List.of("id"), List.of()));
    assertThat(r.all())
        .extracting(EntityManifest::name)
        .containsExactly("Alpha", "Zeta");
  }
}
