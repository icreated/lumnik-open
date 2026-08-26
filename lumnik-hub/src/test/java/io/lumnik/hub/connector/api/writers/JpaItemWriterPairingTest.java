package io.lumnik.hub.connector.api.writers;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.SkipReason;
import io.lumnik.hub.connector.api.types.WriteOutcome;
import io.lumnik.hub.connector.domain.Connector;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Latent bug #2: {@code JpaItemWriter} used to append one {@code SkipReason} per FAILED item
 * while iterating ALL items, and {@code ChunkExecutor.outcomeSkipped} zipped that list
 * positionally with the read batch — so when item N of M failed, the DLQ payload stored was
 * {@code records.get(skipped.size() - 1)}, not the item that actually failed. This test pins
 * that the {@code SkippedRecord} the writer now produces carries the FAILED item itself.
 */
@QuarkusTest
class JpaItemWriterPairingTest {

  @Inject EntityManager em;

  private static final String MIDDLE = "MIDDLE";

  private static JpaItemWriter<String> writerFailingOn(String failing) {
    return new JpaItemWriter<>() {
      @Override
      protected Object toEntity(String item, ConnectorContext ctx) {
        if (item.equals(failing)) {
          throw new PersistenceException("boom on " + item);
        }
        Connector c = new Connector();
        c.setTenantId(1L);
        c.setName("jpa-pairing-" + item + "-" + UUID.randomUUID());
        c.setTypeId("hello-world");
        return c;
      }
    };
  }

  @Test
  @TestTransaction
  void the_skipped_record_carries_the_item_that_actually_failed_not_the_first_one() {
    var writer = writerFailingOn(MIDDLE);
    List<String> items = List.of("first", MIDDLE, "last");

    WriteOutcome outcome = TenantContext.callAsSystem(() -> writer.write(items, null));

    assertThat(outcome).isInstanceOf(WriteOutcome.PartialWrite.class);
    var partial = (WriteOutcome.PartialWrite) outcome;
    assertThat(partial.written()).isEqualTo(2);
    assertThat(partial.skipped()).hasSize(1);
    assertThat(partial.skipped().get(0).payload())
        .as("the DLQ payload must be the item that failed, not items[0]")
        .isEqualTo(MIDDLE);
    assertThat(partial.skipped().get(0).reason()).isInstanceOf(SkipReason.WriteFailed.class);
  }
}
