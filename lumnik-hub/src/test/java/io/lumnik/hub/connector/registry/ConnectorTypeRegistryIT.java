package io.lumnik.hub.connector.registry;

import io.lumnik.hub.connector.exceptions.UnknownConnectorTypeException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@QuarkusTest
class ConnectorTypeRegistryIT {
  @Inject ConnectorTypeRegistry registry;

  @Test
  void registry_contains_hello_world() {
    assertThat(registry.all()).contains("hello-world");
    assertThat(registry.find("hello-world")).isNotNull();
  }

  @Test
  void unknown_type_throws() {
    assertThatThrownBy(() -> registry.find("not-a-real-type"))
        .isInstanceOf(UnknownConnectorTypeException.class);
  }
}
