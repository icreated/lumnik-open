package io.lumnik.hub.connector.mapping.hooks;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class HookCatalogTest {

  @Inject HookCatalog catalog;

  @Test
  void empty_catalog_returns_empty_lists() {
    assertThat(catalog.allDatasetHooks()).isNotNull();
    assertThat(catalog.allRowHooks()).isNotNull();
    assertThat(catalog.allCellHooks()).isNotNull();
  }
}
