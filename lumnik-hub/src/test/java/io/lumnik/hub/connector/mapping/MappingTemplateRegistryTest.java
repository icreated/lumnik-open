package io.lumnik.hub.connector.mapping;

import io.lumnik.hub.connector.mapping.spec.MappingSpec;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class MappingTemplateRegistryTest {

  @Inject MappingTemplateRegistry registry;

  @Test
  void programmatic_registration_works() {
    registry.register("test-template", """
      id: test-template
      version: 1
      target: foo
      """);
    assertThat(registry.allTemplateIds()).contains("test-template");
    MappingSpec spec = registry.load("test-template");
    assertThat(spec.id()).isEqualTo("test-template");
  }

  @Test
  void unknown_id_returns_null() {
    assertThat(registry.load("does-not-exist")).isNull();
    assertThat(registry.rawYaml("does-not-exist")).isNull();
  }
}
