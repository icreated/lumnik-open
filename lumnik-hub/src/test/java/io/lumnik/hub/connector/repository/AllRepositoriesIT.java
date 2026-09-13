package io.lumnik.hub.connector.repository;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@QuarkusTest
class AllRepositoriesIT {
  @Inject ConnectorRepository connectors;
  @Inject ConnectorRunRepository runs;
  @Inject ConnectorCursorEntityRepository cursors;
  @Inject ConnectorCredentialRepository credentials;
  @Inject ConnectorDeadLetterRepository dlqs;

  @Test
  void all_repositories_injectable() {
    assertThat(connectors).isNotNull();
    assertThat(runs).isNotNull();
    assertThat(cursors).isNotNull();
    assertThat(credentials).isNotNull();
    assertThat(dlqs).isNotNull();
  }
}
