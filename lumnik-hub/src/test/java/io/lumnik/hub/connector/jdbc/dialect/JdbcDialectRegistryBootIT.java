package io.lumnik.hub.connector.jdbc.dialect;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The PostgreSQL provider is a bean in another jar: Arc sees it only through its jandex index. Without
 * it the hub would boot with zero dialects and fail on the first connector — so boot is where it is pinned.
 */
@QuarkusTest
class JdbcDialectRegistryBootIT {

  @Inject JdbcDialectRegistry registry;

  /**
   * The embedded PostgreSQL provider is always there; the Pro build adds exactly Oracle, the open edition
   * adds nothing. No stranger bean may slip in, and the edition is read from the registry itself, so this
   * test names no provider class and travels to the open edition unchanged.
   */
  @Test
  void the_installed_providers_are_postgresql_plus_at_most_oracle() {
    List<String> ids = registry.installedIds();
    assertThat(ids).contains("postgres").isSubsetOf("oracle", "postgres").doesNotHaveDuplicates();
    assertThat(registry.resolve("postgres")).isSameAs(registry.resolve("postgresql"));
    assertThat(registry.resolveFor("jdbc:postgresql://h/d", null).id()).isEqualTo("postgres");
  }

  @Test
  void oracle_resolves_when_installed_and_is_refused_by_name_when_not() {
    if (registry.installedIds().contains("oracle")) {
      assertThat(registry.resolveFor("jdbc:oracle:thin:@//h:1521/ERP", null).id()).isEqualTo("oracle");
    } else {
      assertThatThrownBy(() -> registry.resolveFor("jdbc:oracle:thin:@//h:1521/ERP", "oracle"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'oracle' is not installed").hasMessageContaining("Available: postgres");
    }
  }
}
